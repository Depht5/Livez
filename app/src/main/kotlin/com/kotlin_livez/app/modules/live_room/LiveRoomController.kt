package com.kotlin_livez.app.modules.live_room

import com.kotlin_livez.app.app.Constant
import com.kotlin_livez.app.app.EventBus
import com.kotlin_livez.app.app.Log
import com.kotlin_livez.app.app.Site
import com.kotlin_livez.app.app.Sites
import com.kotlin_livez.app.app.controller.AppSettingsController
import com.kotlin_livez.app.models.FollowUser
import com.kotlin_livez.app.player.PlayerEngine
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Instant
import java.util.LinkedList

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

    /** 直播间持续时长（对应 Dart liveDuration） */
    private val _liveDuration = MutableStateFlow("00:00:00")
    val liveDuration: StateFlow<String> = _liveDuration

    /** 自动退出倒计时（对应 Dart countdown） */
    private val _countdown = MutableStateFlow(60)
    val countdown: StateFlow<Int> = _countdown

    private val _autoExitEnable = MutableStateFlow(false)
    val autoExitEnable: StateFlow<Boolean> = _autoExitEnable

    private val _autoExitMinutes = MutableStateFlow(60)
    val autoExitMinutes: StateFlow<Int> = _autoExitMinutes

    private val _delayAutoExit = MutableStateFlow(false)
    val delayAutoExit: StateFlow<Boolean> = _delayAutoExit

    /** 是否后台（对应 Dart isBackground） */
    val isBackground: Boolean get() = _isBackground
    private var _isBackground = false

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
    private var _onlineRefreshInFlight = false
    private val _recordingStartTime: Instant? = null

    private val onlineStatusRefreshPolicy = LiveStatusRefreshPolicy()

    /** 去重指纹队列（对应 Dart _recentDanmuFingerprints/_recentDanmuCounts） */
    private val recentDanmuFingerprints = LinkedList<String>()
    private val recentDanmuCounts = HashMap<String, Int>()
    private var recentDanmuEventsSincePrune = 0
    private val danmuFingerprintMutex = Mutex()

    /** 累计收到的 chat 消息数（诊断日志采样用；诊断日志，定位后移除）。 */
    private var wsChatReceivedCount = 0

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
    private var liveDurationJob: Job? = null
    private var autoExitTickerJob: Job? = null
    private var autoExitDeadlineJob: Job? = null
    private var countdownJob: Job? = null

    /** 已完成的播放器打开（对应 openPlaybackMedia 后的事件链）。 */
    private var mediaEventSubJobs = mutableListOf<Job>()

    /** 应用前后台切换回调（UI 层注入）。 */
    var onAppLifecycleBackground: (() -> Unit)? = null
    var onAppLifecycleForeground: (() -> Unit)? = null

    /** 打开原生 APP 回调（UI 层注入，Dart openNaviteAPP）。 */
    var onOpenNativeApp: ((Site) -> Unit)? = null

    /* ---------- 初始化与生命周期 ---------- */

    fun create(site: Site, roomId: String) {
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
        val targetSite = site ?: return
        val targetRoomId = roomId
        scope.launch {
            try {
                _loadError.value = false
                _error.value = null
                errorStackTrace = null
                liveDanmaku?.stop()
                if (!isCurrentLoad(restarted)) return@launch
                val danmaku = withContext(Dispatchers.IO) { targetSite.liveSite.danmaku() }
                liveDanmaku = danmaku
                clearDanmakuReplayHistory()
                addSysMsg("正在读取直播间信息")
                val loadedDetail = withContext(Dispatchers.IO) {
                    targetSite.liveSite.roomDetail(targetRoomId)
                }
                if (!isCurrentLoad(restarted)) return@launch
                _detail.value = loadedDetail

                // 抖音 WebRid 纠偏（对应 Dart 1.6.0 前的收藏兼容处理）
                reconcileRoomId(targetSite, loadedDetail)

                appSettings.saveLastLiveRoom(siteId = targetSite.id, roomId = roomId)
                getSuperChatMessage(silent = true)
                if (appSettings.contributionRankEnable.value) {
                    fetchContributionRank()
                }
                if (!isCurrentLoad(restarted)) return@launch

                addHistory()
                _followed.value = dbService.getFollowExist("${targetSite.id}_$roomId")
                _online.value = loadedDetail.online
                _liveStatus.value = loadedDetail.status || loadedDetail.isRecord
                restartSuperChatRefreshTimer()
                restartOnlineRefreshTimer()
                if (_liveStatus.value) {
                    getPlayQualites()
                }
                if (loadedDetail.isRecord) {
                    addSysMsg("当前主播未开播，正在转播录像")
                }
                addSysMsg("正在连接弹幕服务器")
                if (!isCurrentLoad(restarted)) return@launch
                initDanmaku()
                danmaku.start(loadedDetail.danmakuData)
                startLiveDurationTimer()
            } catch (e: Exception) {
                Log.logPrint(e)
                if (!isCurrentLoad(restarted)) return@launch
                _loadError.value = true
                _error.value = e
                errorStackTrace = e.stackTraceToString()
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
        mediaEventSubJobs.add(completedJob)
        mediaEventSubJobs.add(errorJob)
    }

    private val shouldRefreshUrlsOnPlaybackRetry: Boolean
        get() = site?.id == Constant.kHuya || site?.id == Constant.kDouyu

    private fun isPlaybackEventCurrent(loadGeneration: Int, mediaGeneration: Int): Boolean =
        isCurrentLoad(loadGeneration) && mediaGeneration == _playbackMediaGeneration

    /** 播放结束（对应 Dart mediaEnd）。 */
    fun mediaEnd() {
        val loadGeneration = _loadGeneration
        val mediaGeneration = _playbackMediaGeneration
        if (!isPlaybackEventCurrent(loadGeneration, mediaGeneration)) return
        scope.launch {
            if (mediaErrorRetryCount < 2) {
                Log.d("播放结束，尝试第${mediaErrorRetryCount + 1}次刷新")
                if (mediaErrorRetryCount == 1) {
                    delay(1000)
                }
                if (!isPlaybackEventCurrent(loadGeneration, mediaGeneration)) return@launch
                mediaErrorRetryCount += 1
                if (reloadPlayUrls(silent = true, resetLine = shouldRefreshUrlsOnPlaybackRetry)) {
                    initPlaylist()
                }
                return@launch
            }
            Log.d("播放结束")
            if (_playUrls.value.size - 1 == currentLineIndex) {
                if (site?.id == Constant.kHuya) {
                    currentLineIndex = 0
                    mediaErrorRetryCount = 0
                    if (reloadPlayUrls(resetLine = true, silent = true)) initPlaylist()
                    return@launch
                }
                _liveStatus.value = false
                tryAutoSwitchToNextLiveRoom(reason = "live_end")
            } else {
                changePlayLine(currentLineIndex + 1)
            }
        }
    }

    /** 播放失败（对应 Dart mediaError）。 */
    fun mediaError(errorText: String) {
        val loadGeneration = _loadGeneration
        val mediaGeneration = _playbackMediaGeneration
        if (!isPlaybackEventCurrent(loadGeneration, mediaGeneration)) return
        scope.launch {
            if (mediaErrorRetryCount < 2) {
                Log.d("播放失败，尝试第${mediaErrorRetryCount + 1}次刷新")
                if (mediaErrorRetryCount == 1) {
                    delay(1000)
                }
                if (!isPlaybackEventCurrent(loadGeneration, mediaGeneration)) return@launch
                mediaErrorRetryCount += 1
                if (reloadPlayUrls(silent = true, resetLine = shouldRefreshUrlsOnPlaybackRetry)) {
                    initPlaylist()
                }
                return@launch
            }
            if (_playUrls.value.size - 1 == currentLineIndex) {
                if (site?.id == Constant.kHuya) {
                    currentLineIndex = 0
                    mediaErrorRetryCount = 0
                    if (reloadPlayUrls(resetLine = true, silent = true)) initPlaylist()
                    return@launch
                }
                _errorMsg.value = "播放失败"
                Log.d("Toast: 播放失败: $errorText")
                tryAutoSwitchToNextLiveRoom(reason = "playback_failure")
            } else {
                changePlayLine(currentLineIndex + 1)
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
        if (_roomDisposed) return
        // 清空 SC/贡献榜/去重状态（对应 Dart refreshRoom）
        _superChats.value = emptyList()
        superChatFingerprints.clear()
        clearContributionRankState()
        clearDanmuReplayHistory()
        scope.launch { liveDanmaku?.stop() }
        loadData()
    }

    /* ---------- 弹幕 ---------- */

    /** 初始化弹幕回调（对应 Dart initDanmau）。 */
    private fun initDanmaku() {
        val danmaku = liveDanmaku ?: return
        danmaku.onMessage = { msg -> onWSMessage(msg) }
        danmaku.onClose = { msg -> onWSClose(msg) }
        danmaku.onReady = { onWSReady() }
    }

    /** 接收 WebSocket 消息（对应 Dart onWSMessage）。 */
    fun onWSMessage(raw: LiveMessage) {
        val msg = sanitizeLiveMessage(raw)
        if (msg.type == LiveMessageType.chat) {
            // 顺序修正（弹幕持续性排查候选 1 的结论）：原先「先 drop(1) 裁剪、后过滤」
            // 会让被屏蔽/重复的消息造成列表净缩短——聊天列表无故丢行 + StateFlow 多一次
            // 无意义发射。引用游标本身不受影响（不可变列表，元素引用不变），但语义上
            // 应只在真正追加新消息时才裁剪头部。对齐 Dart：过滤判定在前、裁剪+追加在后。
            if (isUserShielded(msg.userName) || isTempMutedUser(msg.userName)) {
                Log.d("已过滤被屏蔽用户: ${msg.userName}")
                return
            }
            if (isKeywordShielded(msg)) {
                return
            }
            if (isDuplicateDanmu(msg)) {
                return
            }
            if (_messages.value.size > 200 && !_disableAutoScroll.value) {
                _messages.value = _messages.value.drop(1)
            }
            _messages.value = _messages.value + msg
            wsChatReceivedCount++
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
        // 对应 Dart _allowBackgroundPlayback（背景播放设置）；默认关闭
        return false
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
        val parts = text.split(Regex("\\s+")).filter { it.isNotEmpty() }
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
        return text.replace(Regex("\\s+"), " ").trim()
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
        text = Regex("\\s+").replace(text, "")
        text = Regex("[\\p{Punct}！？。，、；：\u201C\u201D''（）【】《》]").replace(text, "")
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

    private fun clearDanmuReplayHistory() {
        danmakuReplayHistory.clear()
    }

    private fun clearDanmakuReplayHistory() {
        danmakuReplayHistory.clear()
    }

    /* ---------- 超级留言（SC） ---------- */

    /** 读取 SC（对应 Dart getSuperChatMessage）。 */
    fun getSuperChatMessage(silent: Boolean = false) {
        val roomDetail = _detail.value ?: return
        val currentSite = site ?: return
        scope.launch {
            try {
                val sc = withContext(Dispatchers.IO) {
                    currentSite.liveSite.superChatMessage(roomDetail.roomId, roomDetail)
                }
                val filtered = sc.filter { item ->
                    !isUserShielded(item.userName) &&
                        !isTempMutedUser(item.userName) &&
                        !isKeywordShielded(superChatToLiveMessage(item))
                }
                appendSuperChats(filtered)
                removeSuperChats()
            } catch (e: Exception) {
                Log.logPrint(e)
                if (!silent) addSysMsg("SC 读取失败")
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
        var text = Regex("\\s+").replace(message.message, "")
        text = Regex("[\\p{Punct}！？。，、；：\u201C\u201D''（）【】《》]").replace(text, "")
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

    private fun restartSuperChatRefreshTimer() {
        superChatRefreshJob?.cancel()
        superChatRefreshJob = scope.launch {
            while (true) {
                delay(60 * 1000L)
                if (_roomDisposed) break
                getSuperChatMessage(silent = true)
            }
        }
    }

    /* ---------- 在线人数/状态定时刷新（对应 Dart _restartOnlineRefreshTimer） ---------- */

    private fun restartOnlineRefreshTimer() {
        onlineRefreshJob?.cancel()
        onlineRefreshJob = scope.launch {
            while (true) {
                delay(60 * 1000L)
                if (_roomDisposed) break
                refreshOnlineStatus()
            }
        }
    }

    private fun refreshOnlineStatus() {
        if (_onlineRefreshInFlight) return
        val currentSite = site ?: return
        val currentRoomId = roomId
        _onlineRefreshInFlight = true
        scope.launch {
            try {
                val detail = withContext(Dispatchers.IO) {
                    currentSite.liveSite.liveStatus(currentRoomId)
                }
                if (_onlineRefreshInFlight) {
                    val detailObj = _detail.value ?: return@launch
                    if (detail) {
                        onlineStatusRefreshPolicy.reset()
                    } else if (onlineStatusRefreshPolicy.confirmOffline(
                            reportedLive = false,
                            hasActivePlaybackEvidence = player.state.value.playing,
                        )
                    ) {
                        _liveStatus.value = false
                        addSysMsg("主播已下播")
                    }
                    val refreshed = withContext(Dispatchers.IO) {
                        currentSite.liveSite.roomDetail(currentRoomId)
                    }
                    _online.value = refreshed.online
                }
            } catch (e: Exception) {
                Log.logPrint(e)
            } finally {
                _onlineRefreshInFlight = false
            }
        }
    }

    /* ---------- 贡献榜（对应 Dart fetchContributionRank/清理） ---------- */

    fun fetchContributionRank() {
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
                _contributionRankLoading.value = false
                _contributionRankFetched.value = true
                contributionRankUsers.value = users
            } catch (e: Exception) {
                Log.logPrint(e)
                _contributionRankLoading.value = false
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
        val id = "${currentSite.id}_$roomId"
        scope.launch {
            val now = System.currentTimeMillis()
            val existing = dbService.getHistory(id)
            val history = existing?.copy(
                roomId = roomId,
                siteId = currentSite.id,
                userName = roomDetail.userName,
                face = roomDetail.userAvatar,
                updateTime = now,
            ) ?: com.kotlin_livez.app.models.History(
                id = id,
                roomId = roomId,
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

                    liveDanmaku?.stop()
                    _messages.value = emptyList()
                    superChatFingerprints.clear()
                    _superChats.value = emptyList()
                    clearContributionRankState()
                    danmakuReplayHistory.clear()

                    val danmaku = withContext(Dispatchers.IO) { targetSite.liveSite.danmaku() }
                    liveDanmaku = danmaku

                    // 停止当前播放（对应 Dart player.stop()）
                    player.stop()

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
            _countdown.value = _autoExitMinutes.value * 60
            scheduleAutoExitDeadline()
            startAutoExitTicker()
        } else {
            stopAutoExit()
        }
    }

    /** 调整自动退出时长（分钟；对应 Dart AutoExitSession 时长设置）。 */
    fun setAutoExitMinutes(minutes: Int) {
        _autoExitMinutes.value = minutes.coerceIn(1, 600)
        if (_autoExitEnable.value) {
            _countdown.value = _autoExitMinutes.value * 60
        }
    }

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

    private fun startAutoExitTicker() {
        autoExitTickerJob?.cancel()
        autoExitTickerJob = scope.launch {
            while (_autoExitEnable.value && !_roomDisposed) {
                delay(1000)
                _countdown.value = (_countdown.value - 1).coerceAtLeast(0)
                if (_countdown.value <= 0) break
            }
        }
    }

    fun stopAutoExit() {
        _autoExitEnable.value = false
        autoExitDeadlineJob?.cancel()
        autoExitTickerJob?.cancel()
    }

    /* ---------- 直播时长（对应 Dart startLiveDurationTimer：主播已开播时长） ---------- */

    private fun startLiveDurationTimer() {
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
            while (!_roomDisposed) {
                delay(1000)
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
        onAppLifecycleBackground?.invoke()
    }

    fun onAppForeground() {
        _isBackground = false
        onAppLifecycleForeground?.invoke()
    }

    /* ---------- 清理 ---------- */

    fun dispose() {
        if (_roomDisposed) return
        _roomDisposed = true
        _loadGeneration += 1
        currentRoom.clearRoom()
        stopAutoExit()
        superChatRefreshJob?.cancel()
        onlineRefreshJob?.cancel()
        liveDurationJob?.cancel()
        countdownJob?.cancel()
        onlineStatusRefreshPolicy.reset()
        mediaEventSubJobs.forEach { it.cancel() }
        mediaEventSubJobs.clear()
        reopenPendingFuture()
        // 不在控制器清理中调用全局 player.stop()：正常退出由 leaveRoom 在 pop 前先停流；
        // 非常规 onDispose 则紧接着按会话号释放引擎。这样迟到的旧控制器不会停止新页面。
        scope.launch { liveDanmaku?.stop() }
        scope.coroutineContext[Job]?.cancel()
    }

    private fun reopenPendingFuture() {
        reopeningDeferred?.complete(Unit)
        reopeningDeferred = null
        openingDeferred?.complete(Unit)
        openingDeferred = null
    }
}
