package com.kotlin_livez.app.modules.live_room

import com.kotlin_livez.app.app.Constant
import com.kotlin_livez.app.app.EventBus
import com.kotlin_livez.app.app.Log
import com.kotlin_livez.app.app.Site
import com.kotlin_livez.app.app.Sites
import com.kotlin_livez.app.app.controller.AppSettingsController
import com.kotlin_livez.app.models.FollowUser
import com.kotlin_livez.app.player.PlayerEngine
import com.kotlin_livez.app.service.BackgroundPlayback
import com.kotlin_livez.app.services.CurrentRoomService
import com.kotlin_livez.app.services.DBService
import com.kotlin_livez.app.services.FollowService
import com.kotlin_livez.core.danmaku.LiveDanmaku
import com.kotlin_livez.core.model.LiveMessage
import com.kotlin_livez.core.model.LiveMessageColor
import com.kotlin_livez.core.model.LiveMessageType
import com.kotlin_livez.core.model.LivePlayQuality
import com.kotlin_livez.core.model.LiveRoomDetail
import com.kotlin_livez.core.model.LiveSuperChatMessage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.os.SystemClock
import java.time.Instant
import java.util.LinkedList
import java.util.concurrent.atomic.AtomicBoolean

/** 突发批量弹幕的主线程排队上限；满载时宁可丢弃新消息，也不能拖垮整个应用。 */
private const val DANMAKU_CALLBACK_BUFFER_SIZE = 2_048

/**
 * 直播间控制器 —— 对应 Dart `LiveRoomController`
 * （lib/modules/live_room/live_room_controller.dart，3365 行）。
 * 只迁移核心数据与播放/弹幕/重试逻辑；UI 弹层、手势、动画等 Flutter 专属部分留在 View 层。
 */
class LiveRoomController(
    private val appSettings: AppSettingsController,
    private val dbService: DBService,
    private val followService: FollowService,
    private val currentRoom: CurrentRoomService,
    private val player: PlayerEngine,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /* ---------- 房间参数 ---------- */

    private val _rxSite = MutableStateFlow<Site?>(null)
    val rxSite: StateFlow<Site?> = _rxSite

    private val _rxRoomId = MutableStateFlow("")
    val rxRoomId: StateFlow<String> = _rxRoomId
    val roomId: String get() = _rxRoomId.value

    /** 当前站点（对应 Dart site；chainable 赋值见 resetRoom）。 */
    var site: Site? = null
        private set

    /* ---------- 状态流 ---------- */

    private val _detail = MutableStateFlow<LiveRoomDetail?>(null)
    val detail: StateFlow<LiveRoomDetail?> = _detail

    private val _online = MutableStateFlow(0)
    val online: StateFlow<Int> = _online

    private val _followed = MutableStateFlow(false)
    val followed: StateFlow<Boolean> = _followed

    private val _liveStatus = MutableStateFlow(false)
    val liveStatus: StateFlow<Boolean> = _liveStatus

    private val _loadError = MutableStateFlow(false)
    val loadError: StateFlow<Boolean> = _loadError

    private val _errorMsg = MutableStateFlow("")
    val errorMsg: StateFlow<String> = _errorMsg

    private val _error = MutableStateFlow<Throwable?>(null)
    val error: StateFlow<Throwable?> = _error
    var errorStackTrace: String? = null
        private set

    /** 清晰度列表（对应 Dart qualites） */
    private val _qualites = MutableStateFlow<List<LivePlayQuality>>(emptyList())
    val qualites: StateFlow<List<LivePlayQuality>> = _qualites
    var currentQuality: Int = -1

    private val _currentQualityInfo = MutableStateFlow("")
    val currentQualityInfo: StateFlow<String> = _currentQualityInfo

    /** 播放线路（对应 Dart playUrls） */
    private val _playUrls = MutableStateFlow<List<String>>(emptyList())
    val playUrls: StateFlow<List<String>> = _playUrls
    var playHeaders: Map<String, String> = emptyMap()
    var currentLineIndex: Int = -1

    private val _currentLineInfo = MutableStateFlow("")
    val currentLineInfo: StateFlow<String> = _currentLineInfo

    /** 弹幕消息列表（对应 Dart messages） */
    private val _messages = MutableStateFlow<List<LiveMessage>>(emptyList())
    val messages: StateFlow<List<LiveMessage>> = _messages

    /** 醒目留言（对应 Dart superChats） */
    private val _superChats = MutableStateFlow<List<LiveSuperChatMessage>>(emptyList())
    val superChats: StateFlow<List<LiveSuperChatMessage>> = _superChats

    private val _contributionRankLoading = MutableStateFlow(false)
    val contributionRankLoading: StateFlow<Boolean> = _contributionRankLoading

    private val _contributionRankFetched = MutableStateFlow(false)
    val contributionRankFetched: StateFlow<Boolean> = _contributionRankFetched

    /** 是否禁用自动滚动（对应 Dart disableAutoScroll） */
    private val _disableAutoScroll = MutableStateFlow(false)
    val disableAutoScroll: StateFlow<Boolean> = _disableAutoScroll

    /**
     * 同步「用户上滑回看历史」意图（UI 侧滚动锁 → 控制器，由 LiveRoomScreen 的
     * LaunchedEffect 驱动；task 10-17-ui-power-and-recomposition-governance）。
     *
     * 唯一读取方是聊天入列路径的 200 条上限裁剪（`size > 200 && !_disableAutoScroll`）：
     * 置 true 期间不再裁掉头部消息（对齐 Dart，保证上滑回看时不丢内容），代价是该期间
     * 消息列表无上限增长——用户贴底（置 false）即恢复裁剪。
     */
    fun setDisableAutoScroll(value: Boolean) {
        _disableAutoScroll.value = value
    }

    /** 直播间持续时长（对应 Dart liveDuration） */
    private val _liveDuration = MutableStateFlow("00:00:00")
    val liveDuration: StateFlow<String> = _liveDuration

    private val _autoExitEnable = MutableStateFlow(false)
    val autoExitEnable: StateFlow<Boolean> = _autoExitEnable

    private val _autoExitMinutes = MutableStateFlow(60)
    val autoExitMinutes: StateFlow<Int> = _autoExitMinutes

    private val _delayAutoExit = MutableStateFlow(false)
    val delayAutoExit: StateFlow<Boolean> = _delayAutoExit

    /** 是否后台（对应 Dart isBackground） */
    val isBackground: Boolean get() = _isBackground
    private var _isBackground = false

    /** 切后台快照（对应 Dart _backgroundedAt/_positionBeforeBackground）：回前台恢复判停滞用。 */
    private var backgroundedAtElapsedMs: Long? = null
    private var positionBeforeBackgroundMs: Long? = null

    /* ---------- 内部状态（无界面对应的镜像字段） ---------- */

    private var _roomDisposed = false
    private var _loadGeneration = 0
    private var _autoSwitchingRoom = false
    private var _roomSwitching = false
    private var _pendingRoomSite: Site? = null
    private var _pendingRoomId: String? = null
    /** 自动退出已完成（对应 Dart autoExitCompleting；UI 层监听后执行退出直播间）。 */
    private val _autoExitCompleting = MutableStateFlow(false)
    val autoExitCompleting: StateFlow<Boolean> = _autoExitCompleting
    private var _playbackMediaGeneration = 0
    private var mediaErrorRetryCount = 0

    /**
     * 恢复风暴滑动窗口：每次「媒体错误重试后重新打开成功」记一个时间戳。
     * 对应 Dart 新版 live_room_controller 的 _playbackRecoveryTimes。
     */
    private val _playbackRecoveryTimes = mutableListOf<Long>()

    /** 看门狗上次采样到的播放位置（毫秒）；null 表示尚未建立基准。 */
    private var _lastWatchdogPosition: Long? = null

    /** 播放位置最后一次发生推进的时间（墙钟毫秒）。 */
    private var _lastWatchdogProgressAtMs = 0L

    /** 看门狗上次触发重新拉流的时间（墙钟毫秒，冷却用）。 */
    private var _lastWatchdogRefreshAtMs = 0L

    /** 看门狗重新拉流进行中守卫：避免重拉尚未完成时叠加第二次 setPlayer。 */
    private var _playbackRefreshInFlight = false

    private var _onlineRefreshInFlight = false
    private val _recordingStartTime: Instant? = null

    private val onlineStatusRefreshPolicy = LiveStatusRefreshPolicy()

    /** 去重指纹队列（对应 Dart _recentDanmuFingerprints/_recentDanmuCounts） */
    private val recentDanmuFingerprints = LinkedList<String>()
    private val recentDanmuCounts = HashMap<String, Int>()
    private var recentDanmuEventsSincePrune = 0
    /** 累计收到的 chat 消息数（诊断日志采样用；诊断日志，定位后移除）。 */
    private var wsChatReceivedCount = 0

    /** 每轮 load 仅记录一次的首条聊天到达日志标志（loadData 重置）。 */
    private var wsChatLogged = false
    private var keywordDropLogged = false
    private var dupDropLogged = false

    /** 临时静音用户（对应 Dart tempMutedUsers） */
    private val _tempMutedUsers = MutableStateFlow<Set<String>>(emptySet())
    val tempMutedUsers: StateFlow<Set<String>> = _tempMutedUsers

    /** 超级留言指纹（对应 Dart _superChatFingerprints） */
    private val superChatFingerprints = HashSet<String>()

    /** 弹幕回放历史（对应 Dart danmakuReplayHistory 简化版） */
    private val danmakuReplayHistory = LinkedList<LiveMessage>()

    /* ---------- 播放器重开并发控制（对应 Dart _playerReopeningFuture） ---------- */

    private var reopeningDeferred: CompletableDeferred<Unit>? = null
    private var openingDeferred: CompletableDeferred<Unit>? = null

    private var liveDanmaku: LiveDanmaku? = null

    /* ---------- 定时器 Job ---------- */

    private var superChatRefreshJob: Job? = null
    private var onlineRefreshJob: Job? = null
    private var onlineRefreshRequestJob: Job? = null
    private var liveDurationJob: Job? = null
    private var danmakuCallbackJob: Job? = null
    private var autoExitDeadlineJob: Job? = null

    /** 播放停滞看门狗周期任务（dispose 时取消）。 */
    private var playbackWatchdogJob: Job? = null

    /** 已完成的播放器打开（对应 openPlaybackMedia 后的事件链）。 */
    private var mediaEventSubJobs = mutableListOf<Job>()

    /** 应用前后台切换回调（UI 层注入）。 */
    var onAppLifecycleBackground: (() -> Unit)? = null
    var onAppLifecycleForeground: (() -> Unit)? = null

    /** 打开原生 APP 回调（UI 层注入，Dart openNaviteAPP）。 */
    var onOpenNativeApp: ((Site) -> Unit)? = null

    /* ---------- 初始化与生命周期 ---------- */

    fun create(site: Site, roomId: String) {
        Log.d("房间 create: site=${site.id} roomId=$roomId")
        this.site = site
        _rxSite.value = site
        _rxRoomId.value = roomId
        _roomDisposed = false
        _loadGeneration += 1
        currentRoom.setRoom(site.liveSite, roomId)
        loadData()
    }

    /** 加载直播间信息（对应 Dart loadData）。 */
    fun loadData() {
        val restarted = ++_loadGeneration
        Log.d("loadData: generation=$restarted")
        // 新会话重新计「连续失败」与恢复风暴窗口（对应 Dart 新版 loadData 入口）。
        resetPlaybackRecoveryBookkeeping()
        wsChatLogged = false
        keywordDropLogged = false
        dupDropLogged = false
        val targetSite = site ?: return
        val targetRoomId = roomId
        scope.launch {
            var candidateDanmaku: LiveDanmaku? = null
            var keepCandidate = false
            try {
                // 进入 Main scope 时已经过期的排队任务不得先 stop 新轮已接管的实例。
                if (!isCurrentLoad(restarted)) return@launch
                _loadError.value = false
                // 断流文案同属「加载错误态」：必须与 `_loadError` 一并清。
                // 置位方为 `markPlaybackGivenUp(isError=true)`（"播放失败"），只有 `initPlaylist`
                // 真正开到某条线路时才清（:509）；本轮 loadData 是新会话（create/refreshRoom/
                // resetRoom 均经此入口），若此处不清，上一房间/上一轮的残留文案会随
                // `roomHasError` 让错误层盖住新房间的转圈与「未开播」提示。
                _errorMsg.value = ""
                _error.value = null
                errorStackTrace = null
                // 贡献榜状态同属「新会话复位」：本入口由 create/refreshRoom/resetRoom 共用，
                // 必须回到「尚未开始请求」（loading=false / fetched=false），再由随后的
                // `fetchContributionRank()` 推进到「请求中 → 已结束」。UI 正是靠 fetched=false
                // 显示转圈，否则首帧会先闪一下「暂无贡献榜数据」。
                clearContributionRankState()
                cancelRoomBoundJobs()
                // 先摘除所有权再 stop，防止另一轮 load 将迟到的旧实例当成当前实例。
                liveDanmaku?.also {
                    liveDanmaku = null
                    runCatching { it.stop() }
                        .onFailure { error -> Log.e("重载时停止旧弹幕失败: $error") }
                }
                val danmaku = withContext(Dispatchers.IO) { targetSite.liveSite.danmaku() }
                candidateDanmaku = danmaku
                // danmaku() 在 IO 线程执行。两轮加载若反序恢复，旧轮绝不能
                // 覆盖新轮的 liveDanmaku；否则新 socket 无回调且切房时无法被 stop。
                if (!isCurrentLoad(restarted)) return@launch
                liveDanmaku = danmaku
                clearDanmakuSessionState()
                addSysMsg("正在读取直播间信息")
                val loadedDetail = withContext(Dispatchers.IO) {
                    targetSite.liveSite.roomDetail(targetRoomId)
                }
                if (!isCurrentLoad(restarted)) return@launch
                _detail.value = loadedDetail

                // 抖音 WebRid 纠偏（对应 Dart 1.6.0 前的收藏兼容处理）
                reconcileRoomId(targetSite, loadedDetail)
                if (!isCurrentLoad(restarted)) return@launch

                appSettings.saveLastLiveRoom(siteId = targetSite.id, roomId = roomId)
                getSuperChatMessage(silent = true)
                if (appSettings.contributionRankEnable.value) {
                    fetchContributionRank()
                }
                if (!isCurrentLoad(restarted)) return@launch

                addHistory()
                val followed = dbService.getFollowExist("${targetSite.id}_$roomId")
                // Room 查询是挂起点；只有在恢复后仍持有当前代际才能提交 UI/定时器状态。
                if (!isCurrentLoad(restarted)) return@launch
                _followed.value = followed
                _online.value = loadedDetail.online
                _liveStatus.value = loadedDetail.status || loadedDetail.isRecord
                restartSuperChatRefreshTimer(restarted)
                restartOnlineRefreshTimer(restarted)
                if (_liveStatus.value) {
                    getPlayQualites()
                }
                if (loadedDetail.isRecord) {
                    addSysMsg("当前主播未开播，正在转播录像")
                }
                addSysMsg("正在连接弹幕服务器")
                if (!isCurrentLoad(restarted)) return@launch
                initDanmaku(danmaku, restarted)
                danmaku.start(loadedDetail.danmakuData)
                if (!isCurrentLoad(restarted) || liveDanmaku !== danmaku) return@launch
                keepCandidate = true
                startLiveDurationTimer(restarted)
            } catch (e: Exception) {
                Log.logPrint(e)
                if (!isCurrentLoad(restarted)) return@launch
                _loadError.value = true
                _error.value = e
                errorStackTrace = e.stackTraceToString()
            } finally {
                if (!keepCandidate) {
                    candidateDanmaku?.let { stale ->
                        // stop 必须针对局部实例；不能误停已经接管字段的新一轮连接。
                        runCatching { stale.stop() }
                            .onFailure { Log.e("停止过期弹幕实例失败: $it") }
                        if (liveDanmaku === stale) liveDanmaku = null
                    }
                    // 当前轮启动失败后不应留下 SC/在线刷新等空转任务；旧轮则绝不能
                    // 取消新房间刚创建的任务。
                    if (isCurrentLoad(restarted)) cancelRoomBoundJobs()
                }
            }
        }
    }

    private fun isCurrentLoad(loadGeneration: Int): Boolean =
        !_roomDisposed && loadGeneration == _loadGeneration

    private suspend fun reconcileRoomId(targetSite: Site, loadedDetail: LiveRoomDetail) {
        if (targetSite.id != Constant.kDouyin) return
        if (loadedDetail.roomId == roomId) return
        val oldId = roomId
        val resolvedRoomId = loadedDetail.roomId
        _rxRoomId.value = resolvedRoomId
        // 关注项主键已从 oldId 迁到 resolvedRoomId，currentRoom 必须同步：它是 UI 关注列表
        // 比对 `${siteId}_${roomId}` 判定「当前房间」（高亮 / 置顶）的唯一依据，不同步就会
        // 匹配不到纠偏后的房间。Dart 上游同样不重设 setRoom，故这是 Kotlin 侧有意分叉。
        currentRoom.setRoom(targetSite.liveSite, resolvedRoomId)
        if (_followed.value) {
            dbService.deleteFollow("${targetSite.id}_$oldId")
            dbService.addFollow(
                FollowUser(
                    id = "${targetSite.id}_$resolvedRoomId",
                    roomId = resolvedRoomId,
                    siteId = targetSite.id,
                    userName = loadedDetail.userName,
                    face = loadedDetail.userAvatar,
                    addTime = System.currentTimeMillis(),
                ),
            )
        } else {
            _followed.value = dbService.getFollowExist("${targetSite.id}_$resolvedRoomId")
        }
    }

    /** 读取可用清晰度并选择默认值（对应 Dart getPlayQualites）。 */
    fun getPlayQualites() {
        val loadGeneration = _loadGeneration
        val roomDetail = _detail.value
        val currentSite = site ?: return
        if (roomDetail == null || !isCurrentLoad(loadGeneration)) return
        scope.launch {
            try {
                val playQualites = withContext(Dispatchers.IO) {
                    currentSite.liveSite.playQualities(roomDetail)
                }
                if (!isCurrentLoad(loadGeneration)) return@launch
                if (playQualites.isEmpty()) {
                    val qualityError = RuntimeException("无法读取播放清晰度，请稍后重试")
                    _loadError.value = true
                    _error.value = qualityError
                    errorStackTrace = qualityError.stackTraceToString()
                    return@launch
                }
                _qualites.value = playQualites
                currentQuality = when (getQualityLevel()) {
                    2 -> 0 // 最高
                    0 -> playQualites.size - 1 // 最低
                    else -> playQualites.size / 2 // 中间档
                }
                getPlayUrl()
            } catch (e: Exception) {
                if (!isCurrentLoad(loadGeneration)) return@launch
                Log.e("读取播放清晰度失败：${currentSite.id}/$roomId generation=$loadGeneration error=$e")
                _loadError.value = true
                _error.value = e
                errorStackTrace = e.stackTraceToString()
            }
        }
    }

    /** 清晰度档位（对应 Dart getQualityLevel；移动网络档位简化）。 */
    private suspend fun getQualityLevel(): Int {
        return appSettings.qualityLevel.value
    }

    /** 重新读取播放地址（对应 Dart _reloadPlayUrls）。 */
    private suspend fun reloadPlayUrls(resetLine: Boolean = false, silent: Boolean = false): Boolean {
        val loadGeneration = _loadGeneration
        if (_roomDisposed) return false
        val roomDetail = _detail.value ?: return false
        if (currentQuality < 0 || currentQuality >= _qualites.value.size) return false
        val currentSite = site ?: return false
        _currentQualityInfo.value = _qualites.value[currentQuality].quality
        val playUrl = withContext(Dispatchers.IO) {
            currentSite.liveSite.playUrls(roomDetail, _qualites.value[currentQuality])
        }
        if (!isCurrentLoad(loadGeneration)) return false
        if (playUrl.urls.isEmpty()) {
            if (!silent) Log.d("Toast: 无法读取播放地址")
            return false
        }
        _playUrls.value = playUrl.urls
        playHeaders = playUrl.headers ?: emptyMap()
        currentLineIndex = when {
            resetLine || currentLineIndex < 0 -> 0
            currentLineIndex >= _playUrls.value.size -> _playUrls.value.size - 1
            else -> currentLineIndex
        }
        _currentLineInfo.value = "线路${currentLineIndex + 1}"
        return true
    }

    /** 获取播放地址（对应 Dart getPlayUrl）。 */
    fun getPlayUrl() {
        val loadGeneration = _loadGeneration
        _playUrls.value = emptyList()
        _currentLineInfo.value = ""
        currentLineIndex = -1
        scope.launch {
            if (!reloadPlayUrls(resetLine = true)) return@launch
            if (!isCurrentLoad(loadGeneration)) return@launch
            mediaErrorRetryCount = 0
            initPlaylist()
        }
    }

    /** 切换线路（对应 Dart changePlayLine）。 */
    fun changePlayLine(index: Int) {
        currentLineIndex = index
        mediaErrorRetryCount = 0
        initPlaylist()
    }

    /** 初始化播放列表并打开（对应 Dart initPlaylist/_openPlaylist）。 */
    fun initPlaylist() {
        val loadGeneration = _loadGeneration
        val currentSite = site ?: return
        val currentRoomId = roomId
        scope.launch {
            if (_roomDisposed ||
                currentLineIndex < 0 ||
                currentLineIndex >= _playUrls.value.size
            ) return@launch

            // 等待旧播放器打开完成（对应 Dart _playerReopeningFuture 等待）
            while (true) {
                if (!isCurrentLoad(loadGeneration) ||
                    currentLineIndex < 0 ||
                    currentLineIndex >= _playUrls.value.size
                ) return@launch
                val reopening = reopeningDeferred
                if (reopening == null) break
                try {
                    reopening.await()
                } catch (e: Exception) {
                    Log.e("等待旧播放器打开完成失败: $e")
                }
            }

            if (!isCurrentLoad(loadGeneration) ||
                currentLineIndex < 0 ||
                currentLineIndex >= _playUrls.value.size
            ) return@launch

            val mediaGeneration = ++_playbackMediaGeneration
            _currentLineInfo.value = "线路${currentLineIndex + 1}"
            _errorMsg.value = ""

            var finalUrl = _playUrls.value[currentLineIndex]
            if (appSettings.playerForceHttps.value) {
                finalUrl = finalUrl.replace("http://", "https://")
            }

            val headers = playHeaders
            Log.i(
                "准备打开播放器：target=${currentSite.id}/$currentRoomId " +
                    "quality=${_currentQualityInfo.value} line=${currentLineIndex + 1}/${_playUrls.value.size}",
            )

            val deferred = CompletableDeferred<Unit>()
            reopeningDeferred = deferred
            try {
                openPlaybackMedia(
                    url = finalUrl,
                    headers = headers,
                    loadGeneration = loadGeneration,
                    mediaGeneration = mediaGeneration,
                )
                // 打开后同步字幕预览（LiveSubtitleService 移植后接入）
                Log.d("播放链接\n$finalUrl")
            } finally {
                if (reopeningDeferred === deferred) {
                    reopeningDeferred = null
                    deferred.complete(Unit)
                }
            }
        }
    }

    /** 打开播放媒体（对应 Dart openPlaybackMedia）。 */
    private suspend fun openPlaybackMedia(
        url: String,
        headers: Map<String, String>,
        loadGeneration: Int,
        mediaGeneration: Int,
    ) {
        // 等待旧 open() 完成（对应 Dart _playbackOpenFuture 等待）
        while (true) {
            if (!isCurrentLoad(loadGeneration) || mediaGeneration != _playbackMediaGeneration) {
                return
            }
            val activeOpen = openingDeferred
            if (activeOpen == null) break
            try {
                activeOpen.await()
            } catch (e: Exception) {
                Log.e("等待旧媒体打开失败: $e")
            }
        }
        if (!isCurrentLoad(loadGeneration) || mediaGeneration != _playbackMediaGeneration) {
            return
        }
        val deferred = CompletableDeferred<Unit>()
        openingDeferred = deferred
        try {
            // 根因说明（真机闪退：反复进出直播间 "set_property called but mpv is not initialized"）：
            // 本加载链路虽跑在 controller 自己的 scope（dispose 会 cancel），但协程只在挂起点
            // 响应取消——从 IO 线程恢复到 Main 后直到 player.open 之间若已越过最后一个挂起点，
            // dispose 无法中断已 inline 执行的续体；且 PlayerEngine 是全局单例，转场期间新页面
            // 的 initialize / 旧页面的 destroy 可能交错。此处紧贴 player.open 再查一次 disposed，
            // 与 PlayerEngine.ensureReady（引擎侧守卫）构成两层防御。
            if (_roomDisposed) {
                Log.w("直播间已销毁，放弃打开播放器：$url")
                return
            }
            player.open(url, headers)
            // 打开成功（对应 Dart initPlaylist 打开完成）：此前发生过重试时记一次恢复，
            // 并把 mediaErrorRetryCount 归零 —— 语义是「连续失败」而非「会话累计」，
            // 否则长时间观看时第 3 次周期性掐流会被误判为「重试耗尽」。
            onPlaybackRecovered()
            _followed.value = _followed.value
        } finally {
            if (openingDeferred === deferred) {
                openingDeferred = null
                deferred.complete(Unit)
            }
        }
    }

    /* ---------- 媒体事件（对应 Dart mediaEnd/mediaError） ---------- */

    /** 订阅播放器事件（UI 层在页面可见时调用；dispose 时自动取消）。 */
    fun subscribePlayerEvents() {
        val completedJob = scope.launch {
            player.completedEvents.collect { completed ->
                if (completed) mediaEnd()
            }
        }
        val errorJob = scope.launch {
            player.errorEvents.collect { error -> mediaError(error) }
        }
        // 随播放状态启停后台保活前台服务（对应 Dart _syncBackgroundPlaybackService）：
        // 用 state.playing 的 distinct 流而非 playingEvents——后者在 paused-for-cache
        // 事件里会误发 false（缓冲抖动导致服务反复启停）。
        val playingJob = scope.launch {
            player.state.map { it.playing }.distinctUntilChanged().collect { playing ->
                if (playing && allowBackgroundPlayback()) {
                    BackgroundPlayback.start()
                } else {
                    BackgroundPlayback.stop()
                }
            }
        }
        mediaEventSubJobs.add(completedJob)
        mediaEventSubJobs.add(errorJob)
        mediaEventSubJobs.add(playingJob)
        // 播放停滞看门狗随订阅启动（dispose 时取消）。
        startPlaybackWatchdog()
    }

    private val shouldRefreshUrlsOnPlaybackRetry: Boolean
        get() = site?.id == Constant.kHuya || site?.id == Constant.kDouyu

    private fun isPlaybackEventCurrent(loadGeneration: Int, mediaGeneration: Int): Boolean =
        isCurrentLoad(loadGeneration) && mediaGeneration == _playbackMediaGeneration

    /* ---------- 恢复记账与恢复风暴熔断（对应 Dart _playbackRecoveryTimes 系列） ---------- */

    /** 新会话（进房/刷新/切房）重新计连续失败次数与恢复风暴窗口。 */
    private fun resetPlaybackRecoveryBookkeeping() {
        mediaErrorRetryCount = 0
        _playbackRecoveryTimes.clear()
    }

    private fun prunePlaybackRecoveryTimes(now: Long = System.currentTimeMillis()) {
        _playbackRecoveryTimes.removeAll { now - it > RECOVERY_WINDOW_MS }
    }

    /** 5 分钟窗口内恢复次数达上限：判定恢复风暴，末线路不再无限重签重连。 */
    private val playbackRecoveryStormDetected: Boolean
        get() {
            prunePlaybackRecoveryTimes()
            return _playbackRecoveryTimes.size >= MAX_RECOVERIES_PER_WINDOW
        }

    /**
     * 播放（重新）打开成功后的记账 —— 对应 Dart onPlaybackRecovered。
     * 只有此前发生过重试（mediaErrorRetryCount > 0）才记为一次「恢复」并归零计数；
     * 正常打开不计数，因此计数语义始终是「连续失败」。
     */
    private fun onPlaybackRecovered() {
        if (mediaErrorRetryCount > 0) {
            val now = System.currentTimeMillis()
            prunePlaybackRecoveryTimes(now)
            _playbackRecoveryTimes.add(now)
            mediaErrorRetryCount = 0
        }
    }

    /* ---------- 统一恢复链路（对应 Dart setPlayer/_recoverPlaybackOnce） ---------- */

    /**
     * 重新打开播放器（对应 Dart `setPlayer({refreshUrls})`）。
     *
     * refreshUrls 为真时先重新签名取流；**resetLine 必须为 false** —— 保持当前线路，
     * 不因一次断流就退回线路 1（Dart 原版 `setPlayer(refreshUrls: true)` 调
     * `_reloadPlayUrls(silent: true)` 时 resetLine 取默认值 false）。
     * 取流失败抛异常，由调用方走重试耗尽链路（Dart 是静默 return，Kotlin 显式抛出，
     * 以便统一收敛到 playbackRetriesExhausted）。
     */
    suspend fun setPlayer(refreshUrls: Boolean = false) {
        if (refreshUrls) {
            val refreshed = reloadPlayUrls(resetLine = false, silent = true)
            if (!refreshed) {
                throw IllegalStateException("Failed to refresh play urls")
            }
        }
        initPlaylist()
    }

    /** 放弃播放：错误态提示「播放失败」，正常结束态判定为已下播（对应 Dart 尾部处理）。 */
    private fun markPlaybackGivenUp(
        isError: Boolean,
        errorText: String? = null,
        showFailureToast: Boolean = true,
    ) {
        if (isError) {
            _errorMsg.value = "播放失败"
            if (showFailureToast) {
                Log.d("Toast: 播放失败: ${errorText ?: ""}")
            }
            return
        }
        _liveStatus.value = false
    }

    /** 单次重试：setPlayer 失败（取流失败等）时收敛到重试耗尽链路。 */
    private suspend fun recoverPlaybackOnce(
        loadGeneration: Int,
        mediaGeneration: Int,
        isError: Boolean,
        errorText: String? = null,
    ) {
        try {
            setPlayer(refreshUrls = shouldRefreshUrlsOnPlaybackRetry)
        } catch (e: Throwable) {
            Log.e("恢复播放失败: $e", e)
            if (!isPlaybackEventCurrent(loadGeneration, mediaGeneration)) return
            playbackRetriesExhausted(
                loadGeneration = loadGeneration,
                mediaGeneration = mediaGeneration,
                isError = isError,
                errorText = errorText,
            )
        }
    }

    /**
     * 重试耗尽（对应 Dart 尾部逻辑，但末线路重签循环扩展到斗鱼）：
     * 还有剩余线路 → 切下一条；末线路且站点为虎牙/斗鱼且未触发恢复风暴 →
     * 重置线路索引与失败计数并重新签名取流续播（不再直接判下播/换台）。
     */
    private suspend fun playbackRetriesExhausted(
        loadGeneration: Int,
        mediaGeneration: Int,
        isError: Boolean,
        errorText: String? = null,
    ) {
        if (!isPlaybackEventCurrent(loadGeneration, mediaGeneration)) return
        val reason = if (isError) "playback_failure" else "live_end"
        val urls = _playUrls.value
        if (urls.isEmpty()) {
            markPlaybackGivenUp(isError = isError, errorText = errorText)
            tryAutoSwitchToNextLiveRoom(reason = reason)
            return
        }
        if (urls.size - 1 == currentLineIndex) {
            val siteId = site?.id
            // 斗鱼常只返回 1 条线路：纳入末线路重签重连循环，避免周期性掐流被误判「已下播」。
            if ((siteId == Constant.kHuya || siteId == Constant.kDouyu) &&
                !playbackRecoveryStormDetected
            ) {
                currentLineIndex = 0
                mediaErrorRetryCount = 0
                try {
                    setPlayer(refreshUrls = true)
                    return
                } catch (e: Throwable) {
                    Log.e("重置线路重连失败: $e", e)
                    if (!isPlaybackEventCurrent(loadGeneration, mediaGeneration)) return
                }
            }
            markPlaybackGivenUp(isError = isError, errorText = errorText)
            tryAutoSwitchToNextLiveRoom(reason = reason)
            return
        }
        changePlayLine(currentLineIndex + 1)
    }

    /** 播放结束（对应 Dart mediaEnd）。 */
    fun mediaEnd() {
        val loadGeneration = _loadGeneration
        val mediaGeneration = _playbackMediaGeneration
        if (!isPlaybackEventCurrent(loadGeneration, mediaGeneration)) return
        BackgroundPlayback.stop()
        scope.launch {
            if (mediaErrorRetryCount < 2) {
                Log.d("播放结束，尝试第${mediaErrorRetryCount + 1}次刷新")
                if (mediaErrorRetryCount == 1) {
                    delay(1000)
                }
                if (!isPlaybackEventCurrent(loadGeneration, mediaGeneration)) return@launch
                mediaErrorRetryCount += 1
                recoverPlaybackOnce(
                    loadGeneration = loadGeneration,
                    mediaGeneration = mediaGeneration,
                    isError = false,
                )
                return@launch
            }
            Log.d("播放结束")
            playbackRetriesExhausted(
                loadGeneration = loadGeneration,
                mediaGeneration = mediaGeneration,
                isError = false,
            )
        }
    }

    /** 播放失败（对应 Dart mediaError）。 */
    fun mediaError(errorText: String) {
        val loadGeneration = _loadGeneration
        val mediaGeneration = _playbackMediaGeneration
        if (!isPlaybackEventCurrent(loadGeneration, mediaGeneration)) return
        BackgroundPlayback.stop()
        scope.launch {
            if (mediaErrorRetryCount < 2) {
                Log.d("播放失败，尝试第${mediaErrorRetryCount + 1}次刷新")
                if (mediaErrorRetryCount == 1) {
                    delay(1000)
                }
                if (!isPlaybackEventCurrent(loadGeneration, mediaGeneration)) return@launch
                mediaErrorRetryCount += 1
                recoverPlaybackOnce(
                    loadGeneration = loadGeneration,
                    mediaGeneration = mediaGeneration,
                    isError = true,
                    errorText = errorText,
                )
                return@launch
            }
            playbackRetriesExhausted(
                loadGeneration = loadGeneration,
                mediaGeneration = mediaGeneration,
                isError = true,
                errorText = errorText,
            )
        }
    }

    /* ---------- 播放停滞看门狗（对应 Dart _startPlaybackWatchdog 的精简移植） ---------- */

    /**
     * 每 2 秒采样一次播放位置：画面卡住（TCP 未断、未触发 EOF）时主动重取流续播。
     * 在 subscribePlayerEvents() 时启动，dispose() 时取消。
     */
    private fun startPlaybackWatchdog() {
        playbackWatchdogJob?.cancel()
        _lastWatchdogPosition = null
        _lastWatchdogProgressAtMs = 0L
        playbackWatchdogJob = scope.launch {
            while (!_roomDisposed) {
                delay(PLAYBACK_WATCHDOG_INTERVAL_MS)
                if (_roomDisposed) break
                tickPlaybackWatchdog()
            }
        }
    }

    private fun tickPlaybackWatchdog() {
        val playerState = player.state.value
        // 仅在「直播中且播放器自认为在播」时监测；暂停/未开播/切房一律不干预。
        if (!_liveStatus.value || !playerState.playing) {
            _lastWatchdogPosition = null
            _lastWatchdogProgressAtMs = 0L
            return
        }
        val now = System.currentTimeMillis()
        // 进度与缓冲前探量已从 PlayerState 剥离为引擎上的 @Volatile 属性
        //（task 10-17-ui-power-and-recomposition-governance：避免 30–60Hz 的 time-pos
        // 回调持续发射 state 触发整页重组），此处直接读属性。
        val position = player.positionMs
        val lastPosition = _lastWatchdogPosition
        if (lastPosition == null || position != lastPosition) {
            // 建立基准 / 位置推进：刷新推进时间。
            _lastWatchdogPosition = position
            _lastWatchdogProgressAtMs = now
            return
        }
        val stalledForMs = now - _lastWatchdogProgressAtMs
        // 缓冲态判据更短：mpv 已在等缓存且缓存确实耗尽（bufferMs <= 1s，0 亦含「未上报」），
        // 位置又停滞 4 秒 → 重拉能立刻见效。未处于缓冲态则需停滞 6 秒才动手。
        val stalled = (playerState.pausedForCache &&
            player.bufferMs <= 1000L &&
            stalledForMs >= BUFFERING_STALL_THRESHOLD_MS) ||
            (!playerState.pausedForCache && stalledForMs >= FROZEN_PLAYBACK_THRESHOLD_MS)
        if (!stalled) return
        if (_playbackRefreshInFlight) return
        if (playbackRecoveryStormDetected) return
        if (now - _lastWatchdogRefreshAtMs < WATCHDOG_REFRESH_COOLDOWN_MS) return
        _lastWatchdogRefreshAtMs = now
        // 触发后重置推进基准，配合冷却时间避免连续触发。
        _lastWatchdogProgressAtMs = now
        refreshPlayerFromWatchdog()
    }

    /**
     * 看门狗触发重新拉流：setPlayer(refreshUrls = true)（保持当前线路，resetLine = false）。
     * 失败只记录日志，冷却后由看门狗自行再试（不额外做换台/整房刷新）。
     */
    private fun refreshPlayerFromWatchdog() {
        if (_playbackRefreshInFlight) return
        _playbackRefreshInFlight = true
        val loadGeneration = _loadGeneration
        val mediaGeneration = _playbackMediaGeneration
        scope.launch {
            try {
                Log.d("看门狗检测到播放停滞，尝试重新拉流")
                setPlayer(refreshUrls = true)
            } catch (e: Throwable) {
                Log.e("看门狗刷新失败: $e", e)
                if (isPlaybackEventCurrent(loadGeneration, mediaGeneration)) {
                    Log.d("Toast: 播放停滞，正在重新连接...")
                }
            } finally {
                _playbackRefreshInFlight = false
            }
        }
    }

    /** 自动切到下一个直播中的关注（对应 Dart _tryAutoSwitchToNextLiveRoom）。 */
    private fun tryAutoSwitchToNextLiveRoom(reason: String) {
        val settings = appSettings
        val enabled = if (reason == "live_end") {
            settings.autoSwitchNextOnLiveEnd.value
        } else {
            settings.autoSwitchNextOnPlaybackFailure.value
        }
        if (!enabled || _autoSwitchingRoom) return
        val currentSite = site ?: return

        val liveChannels = followService.sortFollowUsers(followService.liveList.value)
        if (liveChannels.isEmpty()) return

        val currentId = "${currentSite.id}_$roomId"
        val currentIndex = liveChannels.indexOfFirst { it.id == currentId }
        val candidates = liveChannels.filter { it.id != currentId }
        if (candidates.isEmpty()) return

        val target: FollowUser = if (currentIndex < 0 || currentIndex >= liveChannels.size - 1) {
            candidates.first()
        } else {
            val next = liveChannels[currentIndex + 1]
            if (next.id == currentId) candidates.first() else next
        }

        _autoSwitchingRoom = true
        try {
            Log.d("Toast: " + if (reason == "live_end") {
                "当前直播已结束，已切换到下一个直播间"
            } else {
                "当前直播播放失败，已切换到下一个直播间"
            })
            val targetSite = Sites.allSites[target.siteId] ?: return
            resetRoom(targetSite, target.roomId)
        } finally {
            _autoSwitchingRoom = false
        }
    }

    /** 重新加载直播间数据（对应 Dart loadData 前的清场）。 */
    fun refreshRoom() {
        Log.d("refreshRoom")
        if (_roomDisposed) return
        // 手动刷新属于新会话：连续失败计数与恢复风暴窗口一并重置。
        resetPlaybackRecoveryBookkeeping()
        cancelRoomBoundJobs()
        // 清空 SC/贡献榜/去重状态（对应 Dart refreshRoom）
        _superChats.value = emptyList()
        superChatFingerprints.clear()
        clearContributionRankState()
        clearDanmakuSessionState()
        liveDanmaku?.also {
            liveDanmaku = null
            runCatching { it.stop() }.onFailure { error -> Log.e("刷新时停止弹幕失败: $error") }
        }
        loadData()
    }

    /* ---------- 弹幕 ---------- */

    /** 初始化弹幕回调（对应 Dart initDanmau）。 */
    private fun initDanmaku(danmaku: LiveDanmaku, loadGeneration: Int) {
        Log.d("弹幕回调接线")
        danmakuCallbackJob?.cancel()
        val callbackJob = SupervisorJob(scope.coroutineContext[Job])
        danmakuCallbackJob = callbackJob
        val callbackScope = CoroutineScope(callbackJob + Dispatchers.Main.immediate)
        val messageQueue = Channel<LiveMessage>(DANMAKU_CALLBACK_BUFFER_SIZE)
        val overflowLogged = AtomicBoolean(false)
        callbackJob.invokeOnCompletion { messageQueue.close() }
        // 单消费者保持弹幕顺序，同时避免 type22 批量帧为每条消息各建一个协程。
        callbackScope.launch {
            for (msg in messageQueue) {
                if (!isCurrentDanmaku(loadGeneration, danmaku)) break
                onWSMessage(msg)
            }
        }
        // OkHttp 在 reader 线程回调。统一切回控制器的 Main scope，并同时校验加载代际和
        // danmaku 实例身份；这样已经进入回调的旧连接也不能在切房后污染新房间状态。
        danmaku.onMessage = { msg ->
            val result = messageQueue.trySend(msg)
            if (result.isFailure && callbackJob.isActive && overflowLogged.compareAndSet(false, true)) {
                Log.w("弹幕回调队列已满，开始丢弃突发消息以保护主线程")
            }
        }
        danmaku.onClose = { msg ->
            callbackScope.launch {
                if (isCurrentDanmaku(loadGeneration, danmaku)) onWSClose(msg)
            }
        }
        danmaku.onReady = {
            callbackScope.launch {
                if (isCurrentDanmaku(loadGeneration, danmaku)) onWSReady()
            }
        }
    }

    private fun isCurrentDanmaku(loadGeneration: Int, danmaku: LiveDanmaku): Boolean =
        isCurrentLoad(loadGeneration) && liveDanmaku === danmaku

    /** 接收 WebSocket 消息（对应 Dart onWSMessage）。 */
    fun onWSMessage(raw: LiveMessage) {
        val msg = sanitizeLiveMessage(raw)
        if (msg.type == LiveMessageType.chat) {
            if (!wsChatLogged) {
                wsChatLogged = true
                Log.d("onWSMessage: 首条聊天消息到达")
            }
            // 顺序修正（弹幕持续性排查候选 1 的结论）：原先「先 drop(1) 裁剪、后过滤」
            // 会让被屏蔽/重复的消息造成列表净缩短——聊天列表无故丢行 + StateFlow 多一次
            // 无意义发射。引用游标本身不受影响（不可变列表，元素引用不变），但语义上
            // 应只在真正追加新消息时才裁剪头部。对齐 Dart：过滤判定在前、裁剪+追加在后。
            if (isUserShielded(msg.userName) || isTempMutedUser(msg.userName)) {
                Log.d("已过滤被屏蔽用户: ${msg.userName}")
                return
            }
            if (isKeywordShielded(msg)) {
                if (!keywordDropLogged) {
                    keywordDropLogged = true
                    Log.d("onWSMessage: 关键词屏蔽丢弃")
                }
                return
            }
            if (isDuplicateDanmu(msg)) {
                if (!dupDropLogged) {
                    dupDropLogged = true
                    Log.d("onWSMessage: 重复弹幕丢弃")
                }
                return
            }
            if (_messages.value.size > 200 && !_disableAutoScroll.value) {
                _messages.value = _messages.value.drop(1)
            }
            _messages.value = _messages.value + msg
            wsChatReceivedCount++
            // 热门房间逐条写 Log 会触发日志列表复制与 Logcat I/O；仅按百条采样。
            if (wsChatReceivedCount % 100 == 0) {
                Log.d("聊天入列 #$wsChatReceivedCount size=${_messages.value.size}")
            }
            if (!_liveStatus.value || (_isBackground && !allowBackgroundPlayback())) {
                return
            }
            // 叠加弹幕渲染（对应 Dart _scheduleOverlayDanmaku）由 View 层监听 messages 实现
        } else if (msg.type == LiveMessageType.online) {
            _online.value = (msg.data as? Number)?.toInt() ?: msg.data?.toString()?.toIntOrNull() ?: 0
        } else if (msg.type == LiveMessageType.superChat) {
            val superChat = msg.data as? LiveSuperChatMessage ?: return
            if (isUserShielded(superChat.userName) || isTempMutedUser(superChat.userName)) {
                return
            }
            if (isKeywordShielded(superChatToLiveMessage(superChat))) {
                return
            }
            appendSuperChats(listOf(superChat))
        }
    }

    private fun allowBackgroundPlayback(): Boolean {
        // 对应 Dart _allowBackgroundPlayback：读真实设置项。
        // （修复：原硬编码 return false 使"后台播放"设置与切后台逻辑整体失效）
        return appSettings.allowBackgroundPlayback.value
    }

    /** 添加系统消息（对应 Dart addSysMsg）。 */
    fun addSysMsg(text: String) {
        if (!_rxRoomId.value.isNullOrEmpty() && _roomDisposed) return
        _messages.value = _messages.value + LiveMessage(
            type = LiveMessageType.chat,
            userName = "LiveSysMessage",
            message = normalizeMessageText(text),
            color = LiveMessageColor.white,
        )
    }

    private fun onWSClose(text: String) {
        addSysMsg(text)
    }

    private fun onWSReady() {
        addSysMsg("弹幕服务器连接成功")
    }

    /** 消息规范化（对应 Dart _sanitizeLiveMessage，简化保留原文）。 */
    private fun sanitizeLiveMessage(msg: LiveMessage): LiveMessage = msg

    /** 屏蔽关键词检查（对应 Dart _isKeywordShielded）。 */
    private fun isKeywordShielded(msg: LiveMessage): Boolean {
        val shieldList = appSettings.shieldList.value
        if (shieldList.isEmpty()) return false
        val text = normalizeMessageText(msg.message)
        val parts = text.split(WS_REGEX).filter { it.isNotEmpty() }
        val fullNames = shieldList.filter { !it.contains(" ") }
        val phraseNames = shieldList.filter { it.contains(" ") }
        if (fullNames.isNotEmpty() && parts.any { p -> fullNames.any { it.equals(p, ignoreCase = true) } }) {
            return true
        }
        if (phraseNames.isNotEmpty() && phraseNames.any { text.contains(it) }) {
            return true
        }
        return false
    }

    private fun normalizeMessageText(text: String): String {
        return text.replace(WS_REGEX, " ").trim()
    }

    /** 用户屏蔽检查（对应 Dart _isUserShielded；用户屏蔽词列表在 AppSettingsController）。 */
    private fun isUserShielded(userName: String): Boolean {
        val shieldList = appSettings.shieldList.value
        if (shieldList.isEmpty()) return false
        val normalized = normalizeUserName(userName)
        return shieldList.any { it.equals(normalized, ignoreCase = true) }
    }

    private fun normalizeUserName(userName: String): String {
        return userName.trim()
    }

    /** 临时静音（对应 Dart isTempMutedUser/toggleTempMuteUser）。 */
    fun isTempMutedUser(userName: String): Boolean =
        _tempMutedUsers.value.contains(normalizeUserName(userName))

    fun toggleTempMuteUser(userName: String) {
        val normalized = normalizeUserName(userName)
        _tempMutedUsers.value =
            if (normalized in _tempMutedUsers.value) _tempMutedUsers.value - normalized
            else _tempMutedUsers.value + normalized
    }

    fun clearTempMutedUsers() {
        _tempMutedUsers.value = emptySet()
    }

    private fun superChatToLiveMessage(sc: LiveSuperChatMessage): LiveMessage =
        LiveMessage(
            type = LiveMessageType.chat,
            userName = sc.userName,
            message = sc.message,
            color = LiveMessageColor.white,
        )

    /** 弹幕去重（对应 Dart _isDuplicateDanmu）。 */
    private fun isDuplicateDanmu(msg: LiveMessage): Boolean {
        val fingerprint = buildDanmuFingerprint(msg)
        if (fingerprint.isEmpty()) return false
        var count = recentDanmuCounts[fingerprint] ?: 0
        recentDanmuCounts[fingerprint] = count + 1
        recentDanmuFingerprints.add(fingerprint)
        recentDanmuEventsSincePrune += 1
        pruneRecentDanmuFingerprints(80)
        return count >= 3
    }

    private fun buildDanmuFingerprint(msg: LiveMessage): String {
        val user = msg.userName
        val normalizedUser = normalizeUserName(user).trim()
        var text = msg.message
        text = WS_REGEX.replace(text, "")
        text = PUNCT_REGEX.replace(text, "")
        val colorComponents = "${msg.color.r}:${msg.color.g}:${msg.color.b}"
        return if (normalizedUser.isEmpty() && text.isEmpty()) {
            ""
        } else {
            "$normalizedUser|$text|$colorComponents"
        }
    }

    /** 修剪指纹（对应 Dart _pruneRecentDanmuFingerprints）。 */
    private fun pruneRecentDanmuFingerprints(windowSize: Int) {
        if (recentDanmuEventsSincePrune <= windowSize) return
        val target = recentDanmuFingerprints.size - windowSize
        if (target <= 0) return
        repeat(target) {
            val removed = recentDanmuFingerprints.pollFirst() ?: return@repeat
            val existing = recentDanmuCounts[removed] ?: 0
            if (existing <= 1) {
                recentDanmuCounts.remove(removed)
            } else {
                recentDanmuCounts[removed] = existing - 1
            }
        }
        recentDanmuEventsSincePrune = recentDanmuFingerprints.size
    }

    /** 房间级弹幕状态不得跨房间/刷新继承，否则新房相同文案会被误判重复。 */
    private fun clearDanmakuSessionState() {
        danmakuReplayHistory.clear()
        recentDanmuFingerprints.clear()
        recentDanmuCounts.clear()
        recentDanmuEventsSincePrune = 0
        wsChatReceivedCount = 0
    }

    /* ---------- 超级留言（SC） ---------- */

    /** 读取 SC（对应 Dart getSuperChatMessage）。 */
    fun getSuperChatMessage(silent: Boolean = false) {
        val loadGeneration = _loadGeneration
        val roomDetail = _detail.value ?: return
        val currentSite = site ?: return
        scope.launch {
            try {
                val sc = withContext(Dispatchers.IO) {
                    currentSite.liveSite.superChatMessage(roomDetail.roomId, roomDetail)
                }
                if (!isCurrentLoad(loadGeneration)) return@launch
                val filtered = sc.filter { item ->
                    !isUserShielded(item.userName) &&
                        !isTempMutedUser(item.userName) &&
                        !isKeywordShielded(superChatToLiveMessage(item))
                }
                appendSuperChats(filtered)
                removeSuperChats()
            } catch (e: Exception) {
                Log.logPrint(e)
                if (!silent && isCurrentLoad(loadGeneration)) addSysMsg("SC 读取失败")
            }
        }
    }

    /** 移除过期的 SC（对应 Dart removeSuperChats）。 */
    fun removeSuperChats() {
        val now = Instant.now()
        _superChats.value = _superChats.value.filter { it.endTime.isAfter(now) }
        sortSuperChats()
        refreshSuperChatFingerprints()
    }

    private fun appendSuperChats(items: List<LiveSuperChatMessage>) {
        val updated = items.filter { shouldUpdateSuperChat(it) }
        if (updated.isEmpty()) return
        _superChats.value = _superChats.value + updated
        sortSuperChats()
        refreshSuperChatFingerprints()
    }

    private fun shouldUpdateSuperChat(message: LiveSuperChatMessage): Boolean {
        val fingerprint = buildSuperChatFingerprint(message)
        if (fingerprint.isEmpty()) return true
        if (fingerprint in superChatFingerprints) return false
        return true
    }

    private fun buildSuperChatFingerprint(message: LiveSuperChatMessage): String {
        var userName = message.userName.trim()
        var text = WS_REGEX.replace(message.message, "")
        text = PUNCT_REGEX.replace(text, "")
        if (userName.isEmpty()) userName = "..."
        if (text.isEmpty()) text = "..."
        return "$userName|$text"
    }

    private fun sortSuperChats() {
        _superChats.value = _superChats.value.sortedByDescending { it.price }
    }

    private fun refreshSuperChatFingerprints() {
        superChatFingerprints.clear()
        for (item in _superChats.value) {
            superChatFingerprints.add(buildSuperChatFingerprint(item))
        }
    }

    /** 取消所有绑定于旧房间的定时/请求任务。 */
    private fun cancelRoomBoundJobs() {
        superChatRefreshJob?.cancel()
        superChatRefreshJob = null
        onlineRefreshJob?.cancel()
        onlineRefreshJob = null
        onlineRefreshRequestJob?.cancel()
        onlineRefreshRequestJob = null
        liveDurationJob?.cancel()
        liveDurationJob = null
        danmakuCallbackJob?.cancel()
        danmakuCallbackJob = null
        _onlineRefreshInFlight = false
        onlineStatusRefreshPolicy.reset()
    }

    private fun restartSuperChatRefreshTimer(loadGeneration: Int) {
        superChatRefreshJob?.cancel()
        superChatRefreshJob = scope.launch {
            while (isCurrentLoad(loadGeneration)) {
                delay(60 * 1000L)
                if (!isCurrentLoad(loadGeneration)) break
                getSuperChatMessage(silent = true)
            }
        }
    }

    /* ---------- 在线人数/状态定时刷新（对应 Dart _restartOnlineRefreshTimer） ---------- */

    private fun restartOnlineRefreshTimer(loadGeneration: Int) {
        onlineRefreshJob?.cancel()
        onlineRefreshJob = scope.launch {
            while (isCurrentLoad(loadGeneration)) {
                delay(60 * 1000L)
                if (!isCurrentLoad(loadGeneration)) break
                refreshOnlineStatus()
            }
        }
    }

    private fun refreshOnlineStatus() {
        if (_onlineRefreshInFlight) return
        val loadGeneration = _loadGeneration
        val currentSite = site ?: return
        val currentRoomId = roomId
        _onlineRefreshInFlight = true
        onlineRefreshRequestJob = scope.launch {
            try {
                val detail = withContext(Dispatchers.IO) {
                    currentSite.liveSite.liveStatus(currentRoomId)
                }
                if (!isCurrentLoad(loadGeneration) || !_onlineRefreshInFlight) return@launch
                if (_detail.value == null) return@launch
                // 取一次快照，避免 playing 与 pausedForCache 分属两次不同的状态读取。
                val playerState = player.state.value
                if (detail) {
                    onlineStatusRefreshPolicy.reset()
                } else if (onlineStatusRefreshPolicy.confirmOffline(
                        reportedLive = false,
                        // 缓冲态（paused-for-cache）同样是在播证据：此时 playing 仍为 true，
                        // 但接口抖动/切流瞬间提示 playing=false 时不能据此误判下播。
                        hasActivePlaybackEvidence = playerState.playing || playerState.pausedForCache,
                    )
                ) {
                    _liveStatus.value = false
                    addSysMsg("主播已下播")
                }
                val refreshed = withContext(Dispatchers.IO) {
                    currentSite.liveSite.roomDetail(currentRoomId)
                }
                if (!isCurrentLoad(loadGeneration) || !_onlineRefreshInFlight) return@launch
                _online.value = refreshed.online
            } catch (e: Exception) {
                if (isCurrentLoad(loadGeneration)) Log.logPrint(e)
            } finally {
                if (loadGeneration == _loadGeneration) {
                    _onlineRefreshInFlight = false
                    onlineRefreshRequestJob = null
                }
            }
        }
    }

    /* ---------- 贡献榜（对应 Dart fetchContributionRank/清理） ---------- */

    fun fetchContributionRank() {
        val loadGeneration = _loadGeneration
        val roomDetail = _detail.value ?: return
        val currentSite = site ?: return
        if (_contributionRankLoading.value || !_contributionRankFetched.value) {
            _contributionRankLoading.value = true
        }
        scope.launch {
            try {
                val users = withContext(Dispatchers.IO) {
                    currentSite.liveSite.contributionRank(roomDetail.roomId, roomDetail)
                }
                if (!isCurrentLoad(loadGeneration)) return@launch
                _contributionRankLoading.value = false
                _contributionRankFetched.value = true
                contributionRankUsers.value = users
            } catch (e: Exception) {
                if (isCurrentLoad(loadGeneration)) {
                    Log.logPrint(e)
                    _contributionRankLoading.value = false
                    // 请求已结束（无论成败）：必须一并置 fetched，让 UI 从「转圈」落到空态。
                    // 只清 loading 的话，UI 的 `!fetched && !loadError` 会在接口异常时永久转圈。
                    _contributionRankFetched.value = true
                }
            }
        }
    }

    /** 贡献榜用户（对应 Dart contributionRankUsers，待 UI 层渲染）。 */
    val contributionRankUsers = MutableStateFlow<List<com.kotlin_livez.core.model.LiveContributionRankItem>>(emptyList())

    private fun clearContributionRankState() {
        _contributionRankLoading.value = false
        _contributionRankFetched.value = false
        contributionRankUsers.value = emptyList()
    }

    /* ---------- 历史记录与关注（对应 Dart addHistory/followUser/removeFollowUser） ---------- */

    fun addHistory() {
        val roomDetail = _detail.value ?: return
        val currentSite = site ?: return
        val currentRoomId = roomId
        val id = "${currentSite.id}_$currentRoomId"
        scope.launch {
            val now = System.currentTimeMillis()
            val existing = dbService.getHistory(id)
            val history = existing?.copy(
                roomId = currentRoomId,
                siteId = currentSite.id,
                userName = roomDetail.userName,
                face = roomDetail.userAvatar,
                updateTime = now,
            ) ?: com.kotlin_livez.app.models.History(
                id = id,
                roomId = currentRoomId,
                siteId = currentSite.id,
                userName = roomDetail.userName,
                face = roomDetail.userAvatar,
                updateTime = now,
            )
            dbService.addOrUpdateHistory(history)
        }
    }

    fun followUser() {
        val roomDetail = _detail.value ?: return
        val currentSite = site ?: return
        val id = "${currentSite.id}_$roomId"
        scope.launch {
            // 新关注用户带入房间当前已知状态（task 08-29-live-room-bars-follow-status
            // 真机反馈：新关注显示「状态未知」，需刷新+重进关注页才恢复）——正在观看的
            // 房间 liveStatus 已知（未开播也标 1，与状态刷新语义一致，不再用 0=未知）。
            // 经 followService.addFollow 走服务维护内存列表 + filterData：关注页即时出现
            // 且状态正确；后续 kUpdateFollow 重载经运行时状态回填保留该状态。
            val follow = FollowUser(
                id = id,
                roomId = roomId,
                siteId = currentSite.id,
                userName = roomDetail.userName,
                face = roomDetail.userAvatar,
                addTime = System.currentTimeMillis(),
            ).apply {
                liveStatus = if (_liveStatus.value) 2 else 1
            }
            followService.addFollow(follow)
            _followed.value = true
            EventBus.emit(Constant.kUpdateFollow, id)
        }
    }

    fun removeFollowUser(confirmed: Boolean = true) {
        if (!confirmed) return
        val currentSite = site ?: return
        val id = "${currentSite.id}_$roomId"
        scope.launch {
            dbService.deleteFollow(id)
            _followed.value = false
            EventBus.emit(Constant.kUpdateFollow, id)
        }
    }

    /** 分享链接（对应 Dart share；UI 层注入分享 Intent）。 */
    fun shareUrl(): String? = _detail.value?.url

    fun copyUrl(): String? {
        val url = _detail.value?.url
        if (url != null) Log.d("Toast: 已复制直播间链接")
        return url
    }

    /** 复制播放直链（对应 Dart copyPlayUrl）。 */
    suspend fun copyPlayUrl(): String? {
        if (!_liveStatus.value) return null
        val roomDetail = _detail.value ?: return null
        val currentSite = site ?: return null
        if (currentQuality < 0 || currentQuality >= _qualites.value.size) return null
        val playUrl = withContext(Dispatchers.IO) {
            currentSite.liveSite.playUrls(roomDetail, _qualites.value[currentQuality])
        }
        if (playUrl.urls.isEmpty()) {
            Log.d("Toast: 无法读取播放地址")
            return null
        }
        Log.d("Toast: 已复制播放直链")
        return playUrl.urls.first()
    }

    /* ---------- 房间切换（对应 Dart resetRoom） ---------- */

    fun resetRoom(newSite: Site, newRoomId: String) {
        Log.d("resetRoom: ${newSite.id}/$newRoomId")
        val currentSite = site
        if (currentSite == newSite && roomId == newRoomId) return
        if (_roomSwitching) {
            _pendingRoomSite = newSite
            _pendingRoomId = newRoomId
            return
        }
        _roomSwitching = true
        scope.launch {
            try {
                var targetSite = newSite
                var targetRoomId = newRoomId
                while (true) {
                    site = targetSite
                    _rxSite.value = targetSite
                    _rxRoomId.value = targetRoomId
                    currentRoom.setRoom(targetSite.liveSite, targetRoomId)
                    _roomDisposed = false
                    _loadGeneration += 1
                    clearTempMutedUsers()

                    cancelRoomBoundJobs()
                    liveDanmaku?.also {
                        liveDanmaku = null
                        runCatching { it.stop() }
                            .onFailure { error -> Log.e("切房时停止弹幕失败: $error") }
                    }
                    _messages.value = emptyList()
                    superChatFingerprints.clear()
                    _superChats.value = emptyList()
                    clearContributionRankState()
                    clearDanmakuSessionState()

                    // 停止当前播放（对应 Dart player.stop()）
                    runCatching { player.stop() }
                        .onFailure { error -> Log.e("切房时停止播放器失败: $error") }

                    loadData()

                    val pendingSite = _pendingRoomSite
                    val pendingRoomId = _pendingRoomId
                    _pendingRoomSite = null
                    _pendingRoomId = null
                    if (pendingSite == null || pendingRoomId == null) break
                    targetSite = pendingSite
                    targetRoomId = pendingRoomId
                }
            } finally {
                _roomSwitching = false
            }
        }
    }

    /* ---------- 自动退出（对应 Dart AutoExitSession 相关，UI 弹层由 View 负责） ---------- */

    fun setAutoExitEnabled(enabled: Boolean) {
        _autoExitEnable.value = enabled
        if (enabled) {
            scheduleAutoExitDeadline()
        } else {
            stopAutoExit()
        }
    }

    /** 调整自动退出时长（分钟；对应 Dart AutoExitSession 时长设置）。 */
    fun setAutoExitMinutes(minutes: Int) {
        _autoExitMinutes.value = minutes.coerceIn(1, 600)
    }

    /**
     * 自动退出到点触发：分钟级 delay 后置 `_autoExitCompleting`（UI 监听后退出直播间）。
     * 注意：原 `startAutoExitTicker`（每秒递减 `_countdown`）已删除——全仓 UI 无该 flow
     * 消费者，真实触发路径只有本方法（task 10-17-ui-power-and-recomposition-governance）。
     */
    private fun scheduleAutoExitDeadline() {
        autoExitDeadlineJob?.cancel()
        autoExitDeadlineJob = scope.launch {
            delay((_autoExitMinutes.value * 60 * 1000).toLong())
            if (_delayAutoExit.value) {
                while (_delayAutoExit.value) {
                    delay(1000)
                }
            }
            if (_autoExitEnable.value && !_roomDisposed) {
                _autoExitCompleting.value = true
                // 退出直播间（UI 层监听 autoExitCompleting 执行返回/关闭）
                Log.d("自动退出直播间倒计时结束")
            }
        }
    }

    fun stopAutoExit() {
        _autoExitEnable.value = false
        autoExitDeadlineJob?.cancel()
    }

    /* ---------- 直播时长（对应 Dart startLiveDurationTimer：主播已开播时长） ---------- */

    private fun startLiveDurationTimer(loadGeneration: Int) {
        // 先取消旧计时器（切换房间/状态变化时避免旧 job 继续推进）
        liveDurationJob?.cancel()
        val detailObj = _detail.value
        val showTime = detailObj?.showTime
        if (!_liveStatus.value || showTime == null) {
            // 未开播或没有 showTime 时显示 00:00:00（对齐 Dart）
            _liveDuration.value = "00:00:00"
            return
        }
        val startTimeStamp = showTime.toLongOrNull()
        if (startTimeStamp == null) {
            // 解析失败显示占位值（对齐 Dart catch 分支）
            _liveDuration.value = "--:--:--"
            return
        }
        liveDurationJob = scope.launch {
            while (isCurrentLoad(loadGeneration)) {
                delay(1000)
                if (!isCurrentLoad(loadGeneration)) break
                val currentSeconds = System.currentTimeMillis() / 1000
                var seconds = (currentSeconds - startTimeStamp).coerceAtLeast(0L)
                val h = seconds / 3600
                val m = (seconds % 3600) / 60
                val s = seconds % 60
                _liveDuration.value =
                    "${h.toString().padStart(2, '0')}:${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}"
            }
        }
    }

    /* ---------- 应用生命周期（对应 Dart didChangeAppLifecycleState） ---------- */

    fun onAppBackground() {
        _isBackground = true
        backgroundedAtElapsedMs = SystemClock.elapsedRealtime()
        // 进度读引擎上的 @Volatile 属性（已从 PlayerState 剥离，见 tickPlaybackWatchdog 说明）；
        // 切后台/回前台跨线程，@Volatile 保证可见性。
        positionBeforeBackgroundMs = player.positionMs
        // 后台播放关闭 = 切后台立即暂停（设置对 _playerAutoPause 的互补语义，亦为用户预期；
        // Dart 原行为是"不启动保活、任由系统冻结进程"，Kotlin 改为确定性暂停）
        if (!allowBackgroundPlayback() && player.state.value.playing) {
            player.pause()
        }
        onAppLifecycleBackground?.invoke()
    }

    fun onAppForeground() {
        _isBackground = false
        val sinceMs = backgroundedAtElapsedMs
        val beforeMs = positionBeforeBackgroundMs
        backgroundedAtElapsedMs = null
        positionBeforeBackgroundMs = null
        recoverPlaybackAfterForeground(sinceMs, beforeMs)
        onAppLifecycleForeground?.invoke()
    }

    /**
     * 返回前台播放恢复（对应 Dart _recoverPlaybackAfterForeground）：后台超过 3s 且
     * 检测到停滞（位置未推进 / 未播放 / 缓冲中）→ 延迟 1.2s 重新拉流。画面本身的
     * 恢复依赖 MpvVideoView 的 surfaceCreated → attachSurface 链路（进程保活前提下）。
     */
    private fun recoverPlaybackAfterForeground(sinceMs: Long?, beforeMs: Long?) {
        if (sinceMs == null || beforeMs == null) return
        if (SystemClock.elapsedRealtime() - sinceMs < 3_000) return
        // 仅后台播放开启时自动恢复：关闭场景切后台已被确定性暂停（见 onAppBackground），
        // 回前台保持暂停由用户手动续播，若自动重拉会违背暂停意图。
        if (!allowBackgroundPlayback()) return
        val loadGeneration = _loadGeneration
        if (!isCurrentLoad(loadGeneration)) return
        if (!_liveStatus.value || currentLineIndex < 0 || _playUrls.value.isEmpty()) return
        scope.launch {
            delay(1_200)
            if (!isCurrentLoad(loadGeneration) || _isBackground) return@launch
            // 快照只用于仍留在 PlayerState 的字段；进度改读引擎属性（同上）
            val s = player.state.value
            val stalled = player.positionMs <= beforeMs || s.pausedForCache || s.completed || !s.playing
            if (!stalled) return@launch
            Log.d("返回前台后检测到播放停滞，尝试恢复")
            try {
                // 统一走 setPlayer（refreshUrls = true 时内部 resetLine = false，保持当前线路）；
                // 取流失败会抛异常，但本路径既有语义是「尝试一次，失败就算了」：只记日志，
                // 不弹 toast、不切线路、不进重试耗尽链路。
                setPlayer(refreshUrls = true)
            } catch (e: Throwable) {
                Log.e("返回前台恢复播放失败: $e", e)
            }
        }
    }

    /* ---------- 清理 ---------- */

    fun dispose() {
        if (_roomDisposed) return
        _roomDisposed = true
        _loadGeneration += 1
        currentRoom.clearRoom()
        stopAutoExit()
        cancelRoomBoundJobs()
        BackgroundPlayback.stop()
        // 播放停滞看门狗随页面销毁取消，避免旧控制器继续重取流。
        playbackWatchdogJob?.cancel()
        playbackWatchdogJob = null
        _playbackRefreshInFlight = false
        onlineStatusRefreshPolicy.reset()
        mediaEventSubJobs.forEach { it.cancel() }
        mediaEventSubJobs.clear()
        reopenPendingFuture()
        // 不在控制器清理中调用全局 player.stop()：正常退出由 leaveRoom 在 pop 前先停流；
        // 非常规 onDispose 则紧接着按会话号释放引擎。这样迟到的旧控制器不会停止新页面。
        runCatching { liveDanmaku?.stop() }
            .onFailure { Log.e("销毁时停止弹幕失败: $it") }
        liveDanmaku = null
        scope.coroutineContext[Job]?.cancel()
    }

    private fun reopenPendingFuture() {
        reopeningDeferred?.complete(Unit)
        reopeningDeferred = null
        openingDeferred?.complete(Unit)
        openingDeferred = null
    }

    companion object {
        /**
         * 空白字符（弹幕/SC 屏蔽词与去重指纹归一化用）。
         * 提升为静态常量：原实现每次调用都新建 Regex 实例，正则在弹幕热路径上
         * （每条消息都过屏蔽与去重）编译开销被反复支付。
         */
        private val WS_REGEX = Regex("\\s+")

        /**
         * 标点（含全角/中文标点）——去重指纹归一化用；与 [WS_REGEX] 同为热路径正则。
         * 模式里的双引号沿用改动前源码的 \u201C\u201D 转义写法。
         */
        private val PUNCT_REGEX = Regex("[\\p{Punct}！？。，、；：\u201C\u201D''（）【】《》]")

        /** 恢复风暴窗口内允许的最大恢复次数（5 分钟 6 次）。 */
        private const val MAX_RECOVERIES_PER_WINDOW = 6
        private const val RECOVERY_WINDOW_MS = 5 * 60 * 1000L

        /** 播放停滞看门狗采样周期。 */
        private const val PLAYBACK_WATCHDOG_INTERVAL_MS = 2000L

        /** 缓冲态（paused-for-cache）下位置停滞多久判定卡死。 */
        private const val BUFFERING_STALL_THRESHOLD_MS = 4000L

        /** 非缓冲态下位置停滞多久判定卡死。 */
        private const val FROZEN_PLAYBACK_THRESHOLD_MS = 6000L

        /** 看门狗两次重新拉流之间的最小间隔。 */
        private const val WATCHDOG_REFRESH_COOLDOWN_MS = 6000L
    }
}
