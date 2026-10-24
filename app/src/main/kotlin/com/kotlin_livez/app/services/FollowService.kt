package com.kotlin_livez.app.services

import com.kotlin_livez.app.app.Constant
import com.kotlin_livez.app.app.EventBus
import com.kotlin_livez.app.app.Log
import com.kotlin_livez.app.app.Sites
import com.kotlin_livez.app.app.controller.AppSettingsController
import com.kotlin_livez.app.models.FollowUser
import com.kotlin_livez.app.models.FollowUserTag
import com.kotlin_livez.core.common.DouyinCookieHelper
import com.kotlin_livez.core.common.DouyinFollowRefreshLimiter
import com.kotlin_livez.core.model.FollowRefreshProgress
import com.kotlin_livez.core.model.FollowRefreshScope
import com.kotlin_livez.core.model.LiveRoomDetail
import com.kotlin_livez.core.site.DouyinSite
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * 关注服务 —— 对应 Dart `FollowService`
 * （lib/services/follow_service.dart, 1700 行）。
 * 状态镜像：Dart 的 per-item Rx 字段（liveStatus/liveStartTime）在 Kotlin 为 FollowUser 上的
 * 普通可变字段（@Transient 不入库），列表级变更经 followList 等 StateFlow 通知 UI；
 * 刷新进度用 core 的 [FollowRefreshProgress]。
 */
class FollowService(
    private val dbService: DBService,
    private val localStorage: LocalStorageService,
    private val currentRoom: CurrentRoomService,
    private val appSettings: AppSettingsController,
) {
    companion object {
        const val updateStatusCooldownMs = 30_000L
        const val refreshProgressCompletionHoldMs = 2_000L

        /** 空目标刷新的最短可见窗口：保证 updating=true 跨帧被 UI 观察（见 _refreshStatusTargets）。 */
        const val emptyTargetsCollapseDelayMs = 400L
        const val kDouyinLimitedAutoResumeMaxAttempts = 2
        const val kDouyinLimitedAutoResumeBaseDelayMs = 45_000L
        const val kFollowProgressUiBurstThreshold = 500
    }

    /* ---------- 状态（镜像 Dart 各 .obs） ---------- */

    private val _followList = MutableStateFlow<List<FollowUser>>(emptyList())
    val followList: StateFlow<List<FollowUser>> = _followList

    private val _liveList = MutableStateFlow<List<FollowUser>>(emptyList())
    val liveList: StateFlow<List<FollowUser>> = _liveList

    private val _notLiveList = MutableStateFlow<List<FollowUser>>(emptyList())
    val notLiveList: StateFlow<List<FollowUser>> = _notLiveList

    private val _followTagList = MutableStateFlow<List<FollowUserTag>>(emptyList())
    val followTagList: StateFlow<List<FollowUserTag>> = _followTagList

    private val _curTagFollowList = MutableStateFlow<List<FollowUser>>(emptyList())
    val curTagFollowList: StateFlow<List<FollowUser>> = _curTagFollowList

    private val _updating = MutableStateFlow(false)
    val updating: StateFlow<Boolean> = _updating

    private val _refreshProgress = MutableStateFlow(FollowRefreshProgress.IDLE)
    val refreshProgress: StateFlow<FollowRefreshProgress> = _refreshProgress

    /** 列表变更通知（对应 Dart updatedListController 广播流） */
    private val _updatedListEvents = MutableSharedFlow<Int>(extraBufferCapacity = 1)
    val updatedListEvents: SharedFlow<Int> = _updatedListEvents

    /* ---------- 内部状态（镜像 Dart 私有字段） ---------- */

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var _updateGeneration = 0
    private var _lastUpdateStatusStartedAt: Long? = null
    private val _previewRefreshingKeys = mutableSetOf<String>()
    private val _liveNotifyReadyIds = mutableSetOf<String>()
    private val _liveNotifySentIds = mutableSetOf<String>()
    private var _refreshProgressResetJob: Job? = null
    private var _eventSub: Job? = null
    private var _eventDebounceJob: Job? = null

    /**
     * 首次 DB 加载单飞（task 10-20-follow-startup-status-refresh）：
     * 启动刷新与关注页控制器首屏共用同一次 loadFollows，避免两路各自读库、相互整体替换列表，
     * 导致启动刷新持有的旧对象成为孤儿（状态写回丢失）。只在主线程调用，无需额外锁。
     */
    private var initialLoadJob: Deferred<Unit>? = null
    private var initialLoadCompleted = false

    /** 刷新统计与队列共享锁（Dart 单线程事件循环无需；Kotlin 多协程 worker 需要） */
    private val syncLock = Any()

    fun install() {
        // ⚠ 必须 listen<Any>：kUpdateFollow 的发射方类型不统一（直播间关注发 String roomId，
        // 同步/备份/导入发 Int 0，对齐 Dart 的动态类型语义）——listen<String> 的强转会让
        // Integer 发射直接 ClassCastException 闪退（真机实证 2026-10-25：接收端同步配置包后崩）。
        // 本监听不消费数据，只做防抖刷新。
        _eventSub = EventBus.listen<Any>(Constant.kUpdateFollow) {
            _eventDebounceJob?.cancel()
            _eventDebounceJob = scope.launch {
                delay(150)
                loadData(updateStatus = false)
            }
        }
        initTimer()
    }

    /** 自动刷新定时器（镜像 Dart initTimer）。 */
    fun initTimer() {
        if (!appSettings.autoUpdateFollowEnable.value) return
        scope.launch {
            while (isActive) {
                try {
                    delay(appSettings.autoUpdateFollowDuration.value * 60_000L)
                    Log.logPrint("关注自动刷新定时器触发")
                    loadData()
                } catch (e: Exception) {
                    Log.w("关注自动刷新定时器异常: $e")
                }
            }
        }
    }

    /* ================================================================
     * 数据加载与关注 CRUD
     * ================================================================ */

    /** 加载关注列表（对应 Dart loadData）。fire-and-forget，加载核心在 loadFollows。 */
    fun loadData(updateStatus: Boolean = true, forceUpdateStatus: Boolean = false) {
        scope.launch { loadFollows(updateStatus, forceUpdateStatus) }
    }

    /**
     * 关注列表加载核心（suspend）：读库更新 _followList 后必须 filterData()——
     * 关注/取关事件链（kUpdateFollow → 防抖 loadData）依赖它发出 updatedListEvents，
     * 缺失则关注 Tab 列表不即时重建（task player-icons-follow-ux 根因修复）。
     */
    private suspend fun loadFollows(updateStatus: Boolean, forceUpdateStatus: Boolean) {
        val list = dbService.getFollowList()
        getAllTagList()
        if (list.isEmpty()) {
            _updating.value = false
            _resetRefreshProgress()
            _followList.value = emptyList()
            filterData()
            return
        }
        // 运行时状态回填（task 08-29-live-room-bars-follow-status 真机实证：关注/取关任一
        // 用户后其他用户全部显示「状态未知」）——liveStatus/liveStartTime 为 @Transient
        // 不入库，关注/取关/同步导入都会经本方法重读 DB 并整体替换内存列表，新对象状态
        // 恒为 0；按 id 从上一份内存列表回填运行时字段。新关注用户不在旧表中 → 保持默认
        // 0，待下次状态刷新（与刷新语义一致）。previewUpdatedAt 持久化，无需回填。
        val prevById = _followList.value.associateBy { it.id }
        for (item in list) {
            prevById[item.id]?.let { prev ->
                item.liveStatus = prev.liveStatus
                item.liveStartTime = prev.liveStartTime
            }
        }
        _followList.value = list
        filterData()
        if (updateStatus) {
            scope.launch { startUpdateStatus(force = forceUpdateStatus) }
        }
    }

    /**
     * 保证至少完成过一次 DB 加载；并发调用复用同一个 Job（加载单飞）。
     * 已成功加载过则直接返回已完成的 Deferred。失败时下次调用会重新发起加载。
     */
    fun ensureLoaded(): Deferred<Unit> {
        initialLoadJob?.takeIf { it.isActive }?.let { return it }
        if (initialLoadCompleted) return CompletableDeferred(Unit)
        val job = scope.async {
            loadFollows(updateStatus = false, forceUpdateStatus = false)
            initialLoadCompleted = true
        }
        initialLoadJob = job
        return job
    }

    /**
     * 应用启动刷新一次（task player-icons-follow-ux）：先完成列表加载，再强制全量刷状态。
     * force = true 刻意跳过 30s 冷却；ensureLoaded().join() 与关注页控制器首屏共用同一次加载，
     * 避免"列表未加载完就刷空列表"竞态；无条件执行，不受 followRefreshOnEnter（进页自动刷）约束。
     */
    fun refreshOnStartup() {
        scope.launch {
            ensureLoaded().join()
            startUpdateStatus(force = true)
        }
    }

    /** 获取全部标签（对应 Dart getAllTagList）。 */
    suspend fun getAllTagList() {
        _followTagList.value = dbService.getFollowTagList()
    }

    /** 添加关注（对应 Dart addFollow）。 */
    suspend fun addFollow(follow: FollowUser) {
        dbService.addFollow(follow)
        _liveNotifyReadyIds.add(follow.id)
        if (_followList.value.none { it.id == follow.id }) {
            _followList.value = _followList.value + follow
        }
        filterData()
    }

    /** 切换特别关注（对应 Dart updateSpecialFollow）。不可变发布，见 FollowUserSnapshots。 */
    suspend fun updateSpecialFollow(follow: FollowUser, value: Boolean) {
        val current = _followList.value.firstOrNull { it.id == follow.id } ?: follow
        val updated = current.copy(isSpecialFollow = value)
        dbService.addFollow(updated)
        _followList.value = FollowUserSnapshots.replaceById(_followList.value, updated)
        filterData()
        if (!value && updated.liveStatus != 2) {
            _liveNotifySentIds.remove(updated.id)
        }
    }

    /** 添加关注标签（对应 Dart addFollowUserTag）。 */
    suspend fun addFollowUserTag(tag: String) {
        val exists = dbService.getFollowTagExistByTag(tag)
        if (exists) {
            Log.logPrint("关注分组已存在: $tag")
            return
        }
        val created = dbService.addFollowTag(tag)
        if (_followTagList.value.none { it.id == created.id }) {
            _followTagList.value = _followTagList.value + created
        }
        filterData()
    }

    /** 删除关注标签（对应 Dart delFollowUserTag）。 */
    suspend fun delFollowUserTag(tag: FollowUserTag) {
        dbService.deleteFollowTag(tag.id)
        _followTagList.value = _followTagList.value.filterNot { it.id == tag.id }
        filterData()
    }

    /** 更新关注标签（对应 Dart updateFollowUserTag）。 */
    suspend fun updateFollowUserTag(tag: FollowUserTag) {
        dbService.updateFollowTag(tag)
        _followTagList.value = _followTagList.value.map { if (it.id == tag.id) tag else it }
        filterData()
    }

    /** 按标签过滤关注列表（对应 Dart filterDataByTag）。 */
    fun filterDataByTag(tag: FollowUserTag) {
        val tagUserIds = tag.userId.toSet()
        val filtered = _followList.value.filter { it.id in tagUserIds }
        _curTagFollowList.value = sortFollowUsers(filtered)
    }

    /* ================================================================
     * 排序与并发
     * ================================================================ */

    private fun compareFollowUsers(a: FollowUser, b: FollowUser): Int {
        val bucketCompare = _sortBucket(a).compareTo(_sortBucket(b))
        if (bucketCompare != 0) return bucketCompare
        return b.addTime.compareTo(a.addTime)
    }

    private fun _sortBucket(item: FollowUser): Int {
        val isLiving = item.liveStatus == 2
        if (item.isSpecialFollow) return if (isLiving) 0 else 1
        return if (isLiving) 2 else 3
    }

    fun sortFollowUsers(items: Iterable<FollowUser>): List<FollowUser> =
        items.sortedWith(::compareFollowUsers)

    private fun _distinctFollowUsers(items: Iterable<FollowUser>): List<FollowUser> {
        val result = mutableListOf<FollowUser>()
        val seenIds = mutableSetOf<String>()
        for (item in items) {
            val uniqueId = if (item.id.trim().isNotEmpty()) item.id.trim()
            else "${item.siteId}_${item.roomId}"
            if (seenIds.add(uniqueId)) result.add(item)
        }
        return result
    }

    /** 获取关注刷新并发数（对应 Dart getOptimalConcurrency）。 */
    fun getOptimalConcurrency(totalCount: Int? = null): Int {
        val count = totalCount ?: _followList.value.size
        if (count <= 0) return 1
        val manual = appSettings.effectiveUpdateFollowThreadCount
        if (manual > 0) return manual.coerceIn(1, count)
        val concurrency = when {
            count <= 50 -> if (count < 2) count else 2
            count <= 200 -> 3
            else -> 4
        }
        return concurrency.coerceIn(1, count)
    }

    private fun _getConcurrencyMode(): String {
        val manual = appSettings.effectiveUpdateFollowThreadCount
        return if (manual > 0) "手动($manual)" else "自动"
    }

    /** 按平台交错排列，避免单一平台阻塞（对应 Dart interleaveByPlatform）。 */
    fun interleaveByPlatform(list: List<FollowUser>): List<FollowUser> {
        val grouped = linkedMapOf<String, ArrayDeque<FollowUser>>()
        for (item in list) {
            grouped.getOrPut(item.siteId) { ArrayDeque() }.addLast(item)
        }
        val result = mutableListOf<FollowUser>()
        while (grouped.values.any { it.isNotEmpty() }) {
            for (queue in grouped.values) {
                if (queue.isNotEmpty()) result.add(queue.removeFirst())
            }
        }
        return result
    }

    private fun deprioritizeCurrentRoom(items: List<FollowUser>): List<FollowUser> {
        val currentKey = currentRoom.currentKey
        if (currentKey.isEmpty()) return items
        val currentItems = mutableListOf<FollowUser>()
        val others = mutableListOf<FollowUser>()
        for (item in items) {
            val itemKey = "${item.siteId}_${item.roomId}"
            if (itemKey == currentKey) currentItems.add(item) else others.add(item)
        }
        return others + currentItems
    }

    private fun _orderedRefreshSiteIds(siteIds: Iterable<String>): List<String> {
        val preferredOrder = listOf(
            Constant.kBiliBili,
            Constant.kHuya,
            Constant.kDouyu,
            Constant.kDouyin,
        )
        val seen = mutableSetOf<String>()
        val result = mutableListOf<String>()
        for (siteId in preferredOrder) {
            if (siteIds.contains(siteId) && seen.add(siteId)) result.add(siteId)
        }
        val remaining = siteIds.filter { seen.add(it) }.sorted()
        result.addAll(remaining)
        return result
    }

    private fun _orderRefreshBucketBySite(
        items: List<FollowUser>,
        moveCurrentRoomToEnd: Boolean = false,
    ): List<FollowUser> {
        val grouped = linkedMapOf<String, MutableList<FollowUser>>()
        for (item in items) {
            grouped.getOrPut(item.siteId) { mutableListOf() }.add(item)
        }
        val ordered = mutableListOf<FollowUser>()
        for (siteId in _orderedRefreshSiteIds(grouped.keys)) {
            var bucket = sortFollowUsers(grouped[siteId] ?: emptyList())
            if (moveCurrentRoomToEnd) bucket = deprioritizeCurrentRoom(bucket)
            ordered.addAll(bucket)
        }
        return ordered
    }

    /* ================================================================
     * 刷新入口
     * ================================================================ */

    /** 全量刷新（对应 Dart startUpdateStatus）。 */
    fun startUpdateStatus(force: Boolean = false) {
        scope.launch {
            refreshSelectedStatus(
                _followList.value,
                includeAllNormals = true,
                force = force,
                scope = FollowRefreshScope.all(automatic = !force),
                allowDetailRefresh = force,
            )
        }
    }

    /** 按范围刷新（对应 Dart refreshSelectedStatus）。 */
    suspend fun refreshSelectedStatus(
        normalTargets: Iterable<FollowUser>,
        includeAllNormals: Boolean = false,
        force: Boolean = true,
        scope: FollowRefreshScope? = null,
        allowDetailRefresh: Boolean = true,
    ) {
        val resolvedScope = scope ?: FollowRefreshScope.all(automatic = !force)
        val targets = if (resolvedScope.includeAllNormals) {
            _buildRefreshTargets(normalTargets, includeAllNormals = includeAllNormals)
        } else {
            buildPageFrontTargets(normalTargets)
        }
        _refreshStatusTargets(targets, force = force, refreshScope = resolvedScope)
        if (!allowDetailRefresh || resolvedScope.automatic || targets.isEmpty()) return
        // targets 是刷新前的列表快照；状态刷新改为不可变替换后，按 id 回查当前项，
        // 否则用旧 liveStatus 过滤会漏掉「本次刚开播」的房间（保持 R4 的详情补齐语义不变）。
        val detailTargets = _buildManualDetailTargets(
            targets.map { target -> _followList.value.firstOrNull { it.id == target.id } ?: target },
        )
        _refreshMetadataTargets(
            detailTargets,
            scope = resolvedScope,
            stage = "正在补齐封面与标题",
            refreshProgressUi = true,
            reconcileDouyinIdentity = true,
        )
    }

    private fun _buildRefreshTargets(
        normalTargets: Iterable<FollowUser>,
        includeAllNormals: Boolean = false,
    ): List<FollowUser> {
        val specials = _followList.value.filter { it.isSpecialFollow }
        val normals = if (includeAllNormals) {
            _followList.value.filter { !it.isSpecialFollow }
        } else {
            normalTargets.filter { !it.isSpecialFollow }
        }
        return _distinctFollowUsers(sortFollowUsers(specials) + sortFollowUsers(normals))
    }

    /** 构建有序刷新目标（对应 Dart _buildOrderedRefreshTargets）。 */
    private fun _buildOrderedRefreshTargets(items: Iterable<FollowUser>): List<FollowUser> {
        val uniqueItems = _distinctFollowUsers(items)
        val specials = uniqueItems.filter { it.isSpecialFollow }
        val normals = uniqueItems.filter { !it.isSpecialFollow }
        val orderedSpecials = _orderRefreshBucketBySite(specials)
        val orderedNormals = _orderRefreshBucketBySite(normals, moveCurrentRoomToEnd = true)
        return orderedSpecials + orderedNormals
    }

    fun buildPageFrontTargets(pageItems: Iterable<FollowUser>): List<FollowUser> =
        _distinctFollowUsers(sortFollowUsers(pageItems))

    fun buildPageRefreshScopeKey(pageKey: String): String = "page:$pageKey"

    private fun _buildManualDetailTargets(items: List<FollowUser>): List<FollowUser> {
        val candidates = _distinctFollowUsers(
            items.filter { it.siteId == Constant.kDouyin || it.liveStatus == 2 },
        )
        return _orderRefreshBucketBySite(candidates)
    }

    private fun _refreshTargetKey(item: FollowUser): String {
        val uniqueId = if (item.id.trim().isNotEmpty()) item.id.trim()
        else "${item.siteId}_${item.roomId}"
        return "${item.siteId}|${item.roomId}|$uniqueId"
    }

    private fun _sameStringList(a: List<String>, b: List<String>): Boolean {
        if (a.size != b.size) return false
        for (i in a.indices) if (a[i] != b[i]) return false
        return true
    }

    /** 状态刷新主流程（对应 Dart _refreshStatusTargets）。 */
    private suspend fun _refreshStatusTargets(
        targets: List<FollowUser>,
        force: Boolean = false,
        refreshScope: FollowRefreshScope,
    ) {
        val now = System.currentTimeMillis()
        val lastStartedAt = _lastUpdateStatusStartedAt
        if (!force && lastStartedAt != null && now - lastStartedAt < updateStatusCooldownMs) {
            Log.logPrint("关注状态刷新仍在冷却中，跳过本次自动刷新")
            // 只有当没有在飞任务时才收尾 UI 态：启动刷新（force=true）可能正在进行，
            // 控制器 init 随后的 force=false 必然命中本分支；若无条件清空 updating /
            // 进度，会把在飞刷新的下拉指示器与进度卡错误收起。
            if (!_updating.value) {
                _resetRefreshProgress()
            }
            filterData()
            return
        }
        if (_updating.value &&
            _refreshProgress.value.active &&
            _refreshProgress.value.scopeKey == refreshScope.scopeKey &&
            !_refreshProgress.value.completed
        ) {
            Log.logPrint("同一刷新任务仍在进行，复用当前进度: ${refreshScope.scopeKey}")
            return
        }
        _lastUpdateStatusStartedAt = now
        val generation = ++_updateGeneration
        val automatic = refreshScope.automatic
        _cancelRefreshProgressReset()
        if (_updating.value) {
            Log.logPrint("新的关注刷新任务已启动，旧任务将按 generation 自动退出: ${refreshScope.scopeKey}")
        }
        _updating.value = true
        _setRefreshProgress(
            active = true,
            automatic = automatic,
            scopeKey = refreshScope.scopeKey,
            stage = refreshScope.stage,
            current = 0,
            total = targets.size,
        )

        if (targets.isEmpty()) {
            // 空目标（无关注，或分组过滤后为空）：无实际刷新项，但收尾必须跨帧。
            // 根因（task 08-29-scroll-restore-follow-refresh 真机实证：关注页空列表下拉刷新
            // 指示器卡住）：协程为 Main.immediate，本方法至首个挂起点前同步执行——若在此同帧
            // 内 true→false，StateFlow 值合并后 UI 只观察到 false；而 M3 PullToRefreshBox
            // 仅在 isRefreshing 参数变化沿才 animateToHidden（onRelease 已先 animateToThreshold），
            // 指示器会停在阈值位永不收起。delay 让 true 至少跨一帧被重组观察，
            // 观感 = 一次瞬时完成的刷新。generation 守卫：delay 期间若发起新刷新
            // （generation 递增），本协程跳过清理，避免误关新任务的刷新态。
            delay(emptyTargetsCollapseDelayMs)
            if (generation == _updateGeneration) {
                _updating.value = false
                _resetRefreshProgress()
            }
            filterData()
            return
        }

        try {
            val concurrency = getOptimalConcurrency(totalCount = targets.size)
            val policy = BulkDataImportService.policyForCount(targets.size)
            val douyinCookie = (Sites.allSites[Constant.kDouyin]?.liveSite as? DouyinSite)?.cookie ?: ""
            val hasFullDouyinCookie = DouyinCookieHelper.hasFullCookie(douyinCookie)

            Log.logPrint(
                "关注状态阶段开始，并发数: $concurrency，模式: ${_getConcurrencyMode()}，目标数: ${targets.size}，策略: ${policy.scale.label}，" +
                    "scope=${refreshScope.scopeKey} fullDouyinCookie=$hasFullDouyinCookie",
            )

            val orderedTargets = _buildOrderedRefreshTargets(targets)
            val filteredTargets = _applyDouyinRefreshPolicy(
                orderedTargets,
                scope = refreshScope,
                hasFullDouyinCookie = hasFullDouyinCookie,
            )
            val allowedTargets = filteredTargets.allowedTargets
            val orderedAllowedKeys = allowedTargets.map { _refreshTargetKey(it) }
            val targetByKey = allowedTargets.associateBy { _refreshTargetKey(it) }
            val persistedTask = _loadPersistedRefreshTask(refreshScope.scopeKey)
            val resumeTask = refreshScope.includeAllNormals &&
                persistedTask != null &&
                _sameStringList(persistedTask.orderedKeys, orderedAllowedKeys) &&
                persistedTask.pendingKeys.isNotEmpty()
            val pendingKeys = if (resumeTask) {
                persistedTask.pendingKeys.filter { targetByKey.containsKey(it) }.toMutableList()
            } else {
                orderedAllowedKeys.toMutableList()
            }
            val isHugeTask = targets.size >= kFollowProgressUiBurstThreshold
            val douyinTargetCount = allowedTargets.count { it.siteId == Constant.kDouyin }
            val douyinLimiter = if (douyinTargetCount > 0) {
                DouyinFollowRefreshLimiter.forTargetCount(douyinTargetCount)
            } else {
                null
            }

            var completed = if (resumeTask) persistedTask.successCount + persistedTask.failedCount else 0
            var successCount = if (resumeTask) persistedTask.successCount else 0
            var failedCount = if (resumeTask) persistedTask.failedCount else 0
            var deferredCount = filteredTargets.deferredTargets.size
            var limitedCount = 0
            var pausedForResume = false
            var autoResumeAttempt = 0

            fun updateProgress(active: Boolean, done: Boolean) {
                val detail = buildString {
                    append("成功 $successCount")
                    if (failedCount > 0) append("  失败 $failedCount")
                    if (deferredCount > 0) append("  待续跑 $deferredCount")
                }
                _setRefreshProgress(
                    active = active,
                    automatic = automatic,
                    scopeKey = refreshScope.scopeKey,
                    stage = refreshScope.stage,
                    current = completed,
                    total = targets.size,
                    successCount = successCount,
                    failedCount = failedCount,
                    deferredCount = deferredCount,
                    detail = detail,
                    completed = done,
                )
            }

            if (refreshScope.includeAllNormals) {
                _persistRefreshTask(
                    scope = refreshScope,
                    total = targets.size,
                    orderedKeys = orderedAllowedKeys,
                    pendingKeys = pendingKeys,
                    successCount = successCount,
                    failedCount = failedCount,
                    deferredCount = deferredCount,
                )
            }

            if (filteredTargets.deferredTargets.isNotEmpty()) {
                Log.w(
                    "抖音全量刷新受限：scope=${refreshScope.scopeKey} deferred=$deferredCount " +
                        "allowedDouyin=$douyinTargetCount requiresFullCookie=true",
                )
            }
            if (resumeTask) {
                Log.logPrint(
                    "继续上次未完成的全量关注刷新：scope=${refreshScope.scopeKey} remaining=${pendingKeys.size}",
                )
            }

            updateProgress(active = true, done = false)

            while (pendingKeys.isNotEmpty()) {
                val taskQueue = ArrayDeque<FollowUser>()
                for (key in pendingKeys) {
                    targetByKey[key]?.let { taskQueue.addLast(it) }
                }
                pausedForResume = false

                suspend fun worker(workerId: Int) {
                    while (true) {
                        synchronized(syncLock) {
                            if (generation != _updateGeneration || pausedForResume) return
                        }
                        val item: FollowUser? = synchronized(syncLock) {
                            if (taskQueue.isEmpty()) null else taskQueue.removeFirst()
                        }
                        if (item == null) return

                        val result = _updateLiveStatus(
                            item,
                            generation = generation,
                            douyinLimiter = douyinLimiter,
                            workerIndex = workerId,
                            pauseRemainingOnLimited = refreshScope.includeAllNormals,
                        )
                        if (generation != _updateGeneration) return

                        synchronized(syncLock) {
                            if (result.limited) limitedCount++
                            if (!result.keepPending) pendingKeys.remove(_refreshTargetKey(item))
                            when (result.outcome) {
                                _FollowRefreshItemOutcome.success -> { successCount++; completed++ }
                                _FollowRefreshItemOutcome.failed -> { failedCount++; completed++ }
                                _FollowRefreshItemOutcome.deferred -> {}
                                _FollowRefreshItemOutcome.skipped -> {}
                            }
                            if (result.pauseRemaining) {
                                pausedForResume = true
                                deferredCount = filteredTargets.deferredTargets.size + pendingKeys.size
                            }
                        }
                        if (refreshScope.includeAllNormals && !isHugeTask) {
                            var pendKeysSnapshot: List<String> = emptyList()
                            var okCount = 0
                            var ngCount = 0
                            var dfCount = 0
                            synchronized(syncLock) {
                                pendKeysSnapshot = pendingKeys.toList()
                                okCount = successCount
                                ngCount = failedCount
                                dfCount = deferredCount
                            }
                            _persistRefreshTask(
                                scope = refreshScope,
                                total = targets.size,
                                orderedKeys = orderedAllowedKeys,
                                pendingKeys = pendKeysSnapshot,
                                successCount = okCount,
                                failedCount = ngCount,
                                deferredCount = dfCount,
                            )
                        }
                        val snapshot = synchronized(syncLock) { completed }
                        if (!isHugeTask || snapshot % 20 == 0 || pendingKeys.isEmpty()) {
                            updateProgress(active = true, done = false)
                        }
                    }
                }

                coroutineScope {
                    val workers = (0 until concurrency).map { workerId -> async { worker(workerId) } }
                    workers.awaitAll()
                }

                if (generation != _updateGeneration) return
                if (!pausedForResume || pendingKeys.isEmpty()) break
                if (!refreshScope.includeAllNormals || autoResumeAttempt >= kDouyinLimitedAutoResumeMaxAttempts) {
                    break
                }
                autoResumeAttempt++
                val resumeDelay = _douyinLimitedAutoResumeDelay(autoResumeAttempt)
                Log.w(
                    "抖音刷新触发限流，${resumeDelay}s后自动续刷剩余${pendingKeys.size}项 " +
                        "scope=${refreshScope.scopeKey} attempt=$autoResumeAttempt",
                )
                updateProgress(active = true, done = false)
                delay(resumeDelay)
                if (generation != _updateGeneration) return
                synchronized(syncLock) {
                    deferredCount = filteredTargets.deferredTargets.size + pendingKeys.size
                }
            }

            if (generation != _updateGeneration) return
            if (douyinLimiter != null) {
                val summary = douyinLimiter.finish(douyinTargetCount)
                Log.logPrint(
                    "抖音关注刷新总结 scope=${refreshScope.scopeKey} target=${summary.targetCount} " +
                        "startConcurrency=${summary.initialConcurrency} " +
                        "startInterval=${summary.initialInterval.toMillis()}ms " +
                        "finalInterval=${summary.finalInterval.toMillis()}ms " +
                        "success=${summary.successCount} limited=${summary.limitedCount} " +
                        "cooldown=${summary.cooledDown} elapsed=${summary.elapsed.toMillis()}ms " +
                        "failed=$failedCount deferred=$deferredCount limitedObserved=$limitedCount",
                )
            }
            synchronized(syncLock) {
                if (pendingKeys.isNotEmpty()) {
                    if (refreshScope.includeAllNormals) {
                        deferredCount = filteredTargets.deferredTargets.size + pendingKeys.size
                    } else {
                        failedCount += pendingKeys.size
                        completed += pendingKeys.size
                        pendingKeys.clear()
                        deferredCount = filteredTargets.deferredTargets.size
                    }
                }
            }
            updateProgress(active = false, done = true)
            if (refreshScope.includeAllNormals) {
                if (pendingKeys.isEmpty()) {
                    _clearPersistedRefreshTask()
                } else {
                    _persistRefreshTask(
                        scope = refreshScope,
                        total = targets.size,
                        orderedKeys = orderedAllowedKeys,
                        pendingKeys = pendingKeys,
                        successCount = successCount,
                        failedCount = failedCount,
                        deferredCount = deferredCount,
                    )
                }
            }
            filterData()
            Log.logPrint("关注状态阶段完成")
        } finally {
            if (generation == _updateGeneration) {
                _updating.value = false
                _finishRefreshProgressLifecycle(generation)
            }
        }
    }

    private suspend fun _updateLiveStatus(
        item: FollowUser,
        generation: Int? = null,
        douyinLimiter: DouyinFollowRefreshLimiter? = null,
        workerIndex: Int = 0,
        pauseRemainingOnLimited: Boolean = false,
    ): _FollowRefreshItemResult {
        val notifyReady = _liveNotifyReadyIds.contains(item.id)
        try {
            if (item.siteId == Constant.kDouyin && douyinLimiter != null) {
                douyinLimiter.beforeRequest(workerIndex)
            }
            val site = Sites.allSites[item.siteId] ?: run {
                return _FollowRefreshItemResult(_FollowRefreshItemOutcome.failed)
            }
            // 手动/自动关注刷新统一走状态优先，不在主链路同步补详情
            val isLiving = withContext(Dispatchers.IO) { site.liveSite.liveStatus(item.roomId) }
            if (generation != null && generation != _updateGeneration) {
                return _FollowRefreshItemResult(_FollowRefreshItemOutcome.deferred)
            }
            if (item.siteId == Constant.kDouyin && douyinLimiter != null) {
                douyinLimiter.onSuccess()
            }
            // 不可变发布：以「当前列表中按 id 取到的最新项」为基准 copy，不再原地改 item。
            // 刷新期间列表可能已被 loadFollows 重载，旧引用会成为孤儿；按 id 落回当前列表即可兜底。
            val current = _followList.value.firstOrNull { it.id == item.id } ?: item
            val previousStatus = current.liveStatus
            val updated = if (item.siteId == Constant.kDouyin) {
                _reconcileDouyinFollowIdentity(
                    current,
                    isLiving = isLiving,
                    generation = generation,
                    detail = null,
                )
            } else {
                var nextStartTime: Long? = null
                if (isLiving) {
                    val detail = withContext(Dispatchers.IO) { site.liveSite.roomDetail(current.roomId) }
                    if (generation != null && generation != _updateGeneration) {
                        return _FollowRefreshItemResult(_FollowRefreshItemOutcome.deferred)
                    }
                    nextStartTime = parseShowTimeMillis(detail.showTime)
                } else {
                    _liveNotifySentIds.remove(current.id)
                }
                // 详情请求期间列表可能再次被重载：发布前按 id 重取最新项作为基准，
                // 避免用旧快照的标题/封面覆盖重载后的新值（与抖音校正路径同口径）。
                val base = _followList.value.firstOrNull { it.id == current.id } ?: current
                val next = base.copy(
                    liveStatus = if (isLiving) 2 else 1,
                    liveStartTime = nextStartTime,
                )
                _followList.value = FollowUserSnapshots.replaceById(_followList.value, next)
                next
            }
            if (generation != null && generation != _updateGeneration) {
                return _FollowRefreshItemResult(_FollowRefreshItemOutcome.deferred)
            }
            if (updated.isSpecialFollow &&
                notifyReady &&
                previousStatus != 2 &&
                updated.liveStatus == 2 &&
                !_liveNotifySentIds.contains(updated.id)
            ) {
                _liveNotifySentIds.add(updated.id)
                scope.launch { LiveNotificationService.showLiveStart(updated) }
            }
            _liveNotifyReadyIds.add(updated.id)
            return _FollowRefreshItemResult(_FollowRefreshItemOutcome.success)
        } catch (e: Exception) {
            if (generation != null && generation != _updateGeneration) {
                return _FollowRefreshItemResult(_FollowRefreshItemOutcome.deferred)
            }
            val limited = _isDouyinLimited(item, e)
            if (limited) {
                douyinLimiter?.onLimited()
                _handleDouyinLimited(pauseRemainingOnLimited = pauseRemainingOnLimited)
            }
            Log.logPrint(e)
            if (limited) {
                if (pauseRemainingOnLimited) {
                    return _FollowRefreshItemResult(
                        _FollowRefreshItemOutcome.deferred,
                        limited = true,
                        keepPending = true,
                        pauseRemaining = true,
                    )
                }
                return _FollowRefreshItemResult(
                    _FollowRefreshItemOutcome.failed,
                    limited = true,
                )
            }
            // 异常分支同样 copy 发布（写回当前列表项，而不是入队时的旧对象）
            val current = _followList.value.firstOrNull { it.id == item.id } ?: item
            val updated = current.copy(liveStatus = 0, liveStartTime = null)
            _followList.value = FollowUserSnapshots.replaceById(_followList.value, updated)
            return _FollowRefreshItemResult(_FollowRefreshItemOutcome.failed, limited = false)
        }
    }

    /**
     * 校正抖音关注身份（合并同名/迁移 roomId；对应 Dart _reconcileDouyinFollowIdentity）。
     * 不可变发布：以当前列表中的对应项为基准 copy，id 迁移时以旧 id 替换发布；DB 与标签引用迁移
     * 语义不变。返回本次发布的最新快照（id 可能已迁移），调用方不得再通过入参读状态。
     */
    private suspend fun _reconcileDouyinFollowIdentity(
        item: FollowUser,
        isLiving: Boolean,
        generation: Int? = null,
        detail: LiveRoomDetail? = null,
    ): FollowUser {
        val base = _followList.value.firstOrNull { it.id == item.id } ?: item
        var latest = base
        siteOperation(item.siteId) { site ->
            val resolvedDetail = detail ?: withContext(Dispatchers.IO) { site.roomDetail(base.roomId) }
            if (generation != null && generation != _updateGeneration) return@siteOperation
            // 网络期间列表可能已被重载：再次按 id 取当前项（而非写回入队时的旧对象）。
            val current = _followList.value.firstOrNull { it.id == base.id } ?: base
            var updated = current
            val resolvedRoomId = resolvedDetail.roomId.trim()
            if (resolvedRoomId.isNotEmpty() && resolvedRoomId != current.roomId) {
                val oldId = current.id
                val newId = "${current.siteId}_$resolvedRoomId"
                dbService.deleteFollow(oldId)
                updated = updated.copy(id = newId, roomId = resolvedRoomId)
                dbService.addFollow(updated)
                _migrateFollowTagReferences(oldId, newId)
            }
            val title = resolvedDetail.title.trim()
            val cover = resolvedDetail.cover.trim()
            var previewChanged = false
            if (title.isNotEmpty()) {
                updated = updated.copy(roomTitle = title)
                previewChanged = true
            }
            if (cover.isNotEmpty()) {
                updated = updated.copy(roomCover = cover)
                previewChanged = true
            }
            if (previewChanged) updated = updated.copy(previewUpdatedAt = System.currentTimeMillis())
            updated = updated.copy(
                liveStatus = if (resolvedDetail.status) 2 else 1,
                liveStartTime = if (resolvedDetail.status && isLiving) {
                    parseShowTimeMillis(resolvedDetail.showTime)
                } else {
                    null
                },
            )
            if (updated.liveStatus != 2) _liveNotifySentIds.remove(updated.id)
            _followList.value = FollowUserSnapshots.replaceById(_followList.value, updated, oldId = current.id)
            dbService.addFollow(updated)
            latest = updated
        }
        return latest
    }

    /** 迁移关注标签中的旧 id 引用（对应 Dart _migrateFollowTagReferences）。 */
    private suspend fun _migrateFollowTagReferences(oldId: String, newId: String) {
        if (oldId == newId) return
        val tags = _followTagList.value.toMutableList()
        var changed = false
        for ((index, tag) in tags.withIndex()) {
            val listIndex = tag.userId.indexOf(oldId)
            if (listIndex < 0) continue
            val deduplicated = tag.userId.toMutableList().also { list ->
                list[listIndex] = newId
            }.distinct()
            tags[index] = tag.copy(userId = deduplicated)
            changed = true
        }
        if (changed) {
            _followTagList.value = tags
            for (tag in tags) {
                dbService.updateFollowTag(tag)
            }
        }
    }

    /** 站点操作封装：按 siteId 取 LiveSite 并执行；站点不存在时静默跳过。 */
    private suspend fun siteOperation(
        siteId: String,
        block: suspend (com.kotlin_livez.core.interfaces.LiveSite) -> Unit,
    ) {
        val site = Sites.allSites[siteId] ?: return
        block(site.liveSite)
    }

    private fun _isDouyinLimited(item: FollowUser, error: Throwable): Boolean {
        if (item.siteId != Constant.kDouyin) return false
        val message = error.message ?: ""
        return message.contains("444")
    }

    private fun _handleDouyinLimited(pauseRemainingOnLimited: Boolean) {
        if (pauseRemainingOnLimited) {
            Log.w("抖音访问受限，已自动降速并保留剩余任务供后续继续")
        } else {
            Log.w("抖音访问受限，已自动降速并继续处理剩余任务")
        }
    }

    private fun _douyinLimitedAutoResumeDelay(attempt: Int): Long =
        kDouyinLimitedAutoResumeBaseDelayMs * attempt

    private fun _applyDouyinRefreshPolicy(
        orderedTargets: List<FollowUser>,
        scope: FollowRefreshScope,
        hasFullDouyinCookie: Boolean,
    ): _RefreshTargetPolicyResult {
        return _RefreshTargetPolicyResult(
            allowedTargets = orderedTargets,
            deferredTargets = emptyList(),
            toastMessage = if (hasFullDouyinCookie) "" else "抖音未登录时将自动降速刷新；若出现 444，会暂停并保留剩余任务供后续继续。",
        )
    }

    /* ================================================================
     * 封面/标题补齐（对应 Dart _refreshMetadataTargets 系）
     * ================================================================ */

    private fun _buildPreviewTargets(items: Iterable<FollowUser>, force: Boolean = false): List<FollowUser> {
        val now = System.currentTimeMillis()
        return _orderRefreshBucketBySite(
            _distinctFollowUsers(
                items.filter { item ->
                    if (item.liveStatus != 2) return@filter false
                    val targetKey = _refreshTargetKey(item)
                    if (_previewRefreshingKeys.contains(targetKey)) return@filter false
                    if (force) return@filter true
                    val missingPreview = item.roomTitle.trim().isEmpty() || item.roomCover.trim().isEmpty()
                    if (missingPreview) return@filter true
                    val updatedAt = item.previewUpdatedAt
                    if (updatedAt == null) return@filter true
                    now - updatedAt > 30 * 60 * 1000L
                },
            ),
        )
    }

    fun refreshVisiblePreviews(pageItems: Iterable<FollowUser>, force: Boolean = false) {
        scope.launch {
            val targets = _buildPreviewTargets(pageItems, force = force)
            if (targets.isEmpty()) return@launch
            val keys = targets.map { _refreshTargetKey(it) }
            _previewRefreshingKeys.addAll(keys)
            try {
                _refreshMetadataTargets(
                    targets,
                    scope = FollowRefreshScope(
                        scopeKey = "preview",
                        includeAllNormals = false,
                        automatic = true,
                        allowBackgroundSpecials = false,
                        stage = "正在补齐封面与标题",
                        backgroundStage = "",
                    ),
                    stage = "正在补齐封面与标题",
                    refreshProgressUi = false,
                    reconcileDouyinIdentity = true,
                )
            } finally {
                _previewRefreshingKeys.removeAll(keys)
            }
        }
    }

    /**
     * 进入直播间前校正抖音身份（对应 Dart resolveFollowBeforeEnter）。
     * 返回本次校正后发布的最新快照（id/roomId 可能已迁移），而不是入参对象。
     */
    suspend fun resolveFollowBeforeEnter(item: FollowUser): FollowUser {
        if (item.siteId != Constant.kDouyin) return item
        val resolved = _refreshMetadataTargets(
            listOf(item),
            scope = FollowRefreshScope(
                scopeKey = "room-enter",
                includeAllNormals = false,
                automatic = false,
                allowBackgroundSpecials = false,
                stage = "正在校正直播间",
                backgroundStage = "",
            ),
            stage = "正在校正直播间",
            refreshProgressUi = false,
            reconcileDouyinIdentity = true,
        )
        return resolved.firstOrNull()
            ?: _followList.value.firstOrNull { it.id == item.id }
            ?: item
    }

    /**
     * 逐个补齐封面与标题（对应 Dart _refreshMetadataTargets）。
     * 不可变发布：循环内只生成快照暂存 [pending]，循环结束后按 id 一次性合并到**当前**列表、
     * 写库并 filterData()；不会写回循环开始时的旧对象。
     * @return 本次实际发布的最新快照（resolveFollowBeforeEnter 依赖它拿到迁移后的 id/roomId）。
     */
    private suspend fun _refreshMetadataTargets(
        targets: List<FollowUser>,
        scope: FollowRefreshScope,
        stage: String,
        refreshProgressUi: Boolean,
        reconcileDouyinIdentity: Boolean,
    ): List<FollowUser> {
        if (targets.isEmpty()) return emptyList()
        val orderedTargets = _orderRefreshBucketBySite(_distinctFollowUsers(targets))
        val generation = _updateGeneration
        var completed = 0
        var successCount = 0
        var failedCount = 0
        var changed = false
        // key = 最新快照 id；Douyin 身份迁移后为 newId，普通分支为当前项 id。
        val pending = LinkedHashMap<String, FollowUser>()

        fun updateProgress(done: Boolean) {
            if (!refreshProgressUi) return
            val detail = buildString {
                append("成功 $successCount")
                if (failedCount > 0) append("  失败 $failedCount")
            }
            _setRefreshProgress(
                active = !done,
                automatic = scope.automatic,
                scopeKey = scope.scopeKey,
                stage = stage,
                current = completed,
                total = orderedTargets.size,
                successCount = successCount,
                failedCount = failedCount,
                detail = detail,
                completed = done,
            )
        }

        updateProgress(done = false)

        for (item in orderedTargets) {
            if (generation != _updateGeneration) return pending.values.toList()
            try {
                siteOperation(item.siteId) { site ->
                    val detail = withContext(Dispatchers.IO) { site.roomDetail(item.roomId) }
                    if (generation != _updateGeneration) return@siteOperation
                    if (reconcileDouyinIdentity && item.siteId == Constant.kDouyin) {
                        val updated = _reconcileDouyinFollowIdentity(
                            item,
                            isLiving = item.liveStatus == 2 || detail.status,
                            generation = generation,
                            detail = detail,
                        )
                        pending[updated.id] = updated
                    } else {
                        val current = _followList.value.firstOrNull { it.id == item.id } ?: item
                        var updated = current
                        var metadataChanged = false
                        val title = detail.title.trim()
                        val cover = detail.cover.trim()
                        if (title.isNotEmpty() && title != current.roomTitle) {
                            updated = updated.copy(roomTitle = title)
                            metadataChanged = true
                        }
                        if (cover.isNotEmpty() && cover != current.roomCover) {
                            updated = updated.copy(roomCover = cover)
                            metadataChanged = true
                        }
                        if (title.isNotEmpty() || cover.isNotEmpty()) {
                            updated = updated.copy(previewUpdatedAt = System.currentTimeMillis())
                        }
                        if (updated != current) pending[updated.id] = updated
                        if (metadataChanged) changed = true
                    }
                }
                successCount++
            } catch (e: Exception) {
                failedCount++
                Log.logPrint(e)
            }
            completed++
            if ((successCount + failedCount) % 10 == 0) {
                updateProgress(done = false)
            }
        }
        updateProgress(done = true)

        // 一次性合并：即使循环期间列表被重载，也按 id 落到当前列表（不写回旧对象）。
        if (pending.isNotEmpty()) {
            var snapshot = _followList.value
            for (updated in pending.values) {
                snapshot = FollowUserSnapshots.replaceById(snapshot, updated)
            }
            _followList.value = snapshot
            if (changed) dbService.addFollows(snapshot)
            filterData()
        } else if (refreshProgressUi) {
            filterData()
        }
        return pending.values.toList()
    }

    /* ================================================================
     * 续跑任务持久化（对应 Dart _loadPersistedRefreshTask 系）
     * ================================================================ */

    private fun _loadPersistedRefreshTask(scopeKey: String): _PersistedFollowRefreshTaskState? {
        return try {
            val rawState = localStorage.getValue(LocalStorageService.kFollowRefreshTaskState, "")
            val rawTargets = localStorage.getValue(LocalStorageService.kFollowRefreshTaskTargets, "")
            if (rawState.isEmpty() || rawTargets.isEmpty()) return null
            val stateMap = Json.parseToJsonElement(rawState) as? JsonObject ?: return null
            val targetsMap = Json.parseToJsonElement(rawTargets) as? JsonObject ?: return null
            val state = _PersistedFollowRefreshTaskState.fromJson(stateMap, targetsMap)
            if (state.scopeKey != scopeKey) return null
            state
        } catch (e: Exception) {
            Log.w("读取关注刷新续跑状态失败: $e")
            null
        }
    }

    private suspend fun _persistRefreshTask(
        scope: FollowRefreshScope,
        total: Int,
        orderedKeys: List<String>,
        pendingKeys: List<String>,
        successCount: Int,
        failedCount: Int,
        deferredCount: Int,
    ) {
        if (!scope.includeAllNormals) return
        val statePayload = buildJsonObject {
            put("scopeKey", scope.scopeKey)
            put("total", total)
            put("successCount", successCount)
            put("failedCount", failedCount)
            put("deferredCount", deferredCount)
            put("updatedAt", java.time.Instant.now().toString())
        }
        val targetPayload = buildJsonObject {
            put("orderedKeys", JsonArray(orderedKeys.map { JsonPrimitive(it) }))
            put("pendingKeys", JsonArray(pendingKeys.map { JsonPrimitive(it) }))
        }
        localStorage.setValue(LocalStorageService.kFollowRefreshTaskState, statePayload.toString())
        localStorage.setValue(LocalStorageService.kFollowRefreshTaskTargets, targetPayload.toString())
    }

    private fun _clearPersistedRefreshTask() {
        localStorage.removeValue(LocalStorageService.kFollowRefreshTaskState)
        localStorage.removeValue(LocalStorageService.kFollowRefreshTaskTargets)
    }

    /* ================================================================
     * 进度生命周期（对应 Dart _setRefreshProgress 系）
     * ================================================================ */

    private fun _setRefreshProgress(
        active: Boolean,
        automatic: Boolean,
        scopeKey: String,
        stage: String,
        current: Int,
        total: Int,
        successCount: Int = 0,
        failedCount: Int = 0,
        deferredCount: Int = 0,
        skippedCount: Int = 0,
        completed: Boolean = false,
        background: Boolean = false,
        detail: String = "",
    ) {
        _refreshProgress.value = FollowRefreshProgress(
            active = active,
            automatic = automatic,
            scopeKey = scopeKey,
            stage = stage,
            current = current.coerceIn(0, total),
            total = total,
            successCount = successCount,
            failedCount = failedCount,
            deferredCount = deferredCount,
            skippedCount = skippedCount,
            completed = completed,
            background = background,
            detail = detail,
        )
    }

    private fun _resetRefreshProgress() {
        _cancelRefreshProgressReset()
        _refreshProgress.value = FollowRefreshProgress.IDLE
    }

    private fun _cancelRefreshProgressReset() {
        _refreshProgressResetJob?.cancel()
        _refreshProgressResetJob = null
    }

    private fun _finishRefreshProgressLifecycle(generation: Int) {
        if (_refreshProgress.value.completed) {
            _scheduleRefreshProgressReset(generation)
        } else {
            _resetRefreshProgress()
        }
    }

    private fun _scheduleRefreshProgressReset(generation: Int) {
        _cancelRefreshProgressReset()
        _refreshProgressResetJob = scope.launch {
            delay(refreshProgressCompletionHoldMs)
            if (generation != _updateGeneration) return@launch
            if (_updating.value || !_refreshProgress.value.completed) return@launch
            _resetRefreshProgress()
        }
    }

    /* ================================================================
     * 列表分组（对应 Dart filterData）
     * ================================================================ */

    fun filterData() {
        _followList.value = sortFollowUsers(_followList.value)
        _liveList.value = sortFollowUsers(_followList.value.filter { it.liveStatus == 2 })
        _notLiveList.value = sortFollowUsers(_followList.value.filter { it.liveStatus != 2 })
        _updatedListEvents.tryEmit(0)
    }

    /* ================================================================
     * 导入导出（对应 Dart generateJson / inputJson；文件落盘留待 UI 层）
     * ================================================================ */

    fun generateJson(): String {
        val data = _followList.value.map { item ->
            buildJsonObject {
                put("siteId", item.siteId)
                put("id", item.id)
                put("roomId", item.roomId)
                put("userName", item.userName)
                put("face", item.face)
                put("addTime", item.addTime.toString())
                put("tag", item.tag)
                put("isSpecialFollow", item.isSpecialFollow)
            }
        }
        return JsonArray(data).toString()
    }

    /** 导入关注 JSON 文本（对应 Dart inputJson）。 */
    suspend fun importJson(content: String): Boolean {
        return try {
            val decoded = Json.parseToJsonElement(content)
            if (decoded !is JsonArray) {
                Log.w("关注列表格式不是数组")
                return false
            }
            val startNanos = System.nanoTime()
            val result = BulkDataImportService.importFollowUsers(
                decoded.toList(),
                syncTagsFromUserField = true,
            )
            val elapsedMs = (System.nanoTime() - startNanos) / 1_000_000
            Log.i("文本/文件关注导入完成：${result.logSummary} elapsed=${elapsedMs}ms")
            filterData()
            true
        } catch (e: Exception) {
            Log.w("关注导入失败: $e")
            false
        }
    }

    /* ================================================================
     * 生命周期（对应 Dart onClose）
     * ================================================================ */

    fun onClose() {
        _updateGeneration++
        _updating.value = false
        _cancelRefreshProgressReset()
        _resetRefreshProgress()
        _eventDebounceJob?.cancel()
        _eventSub?.cancel()
        scope.coroutineContext[Job]?.cancel()
    }

    /* ---------- 工具 ---------- */

    private fun parseShowTimeMillis(value: String?): Long? {
        if (value.isNullOrBlank()) return null
        return try {
            java.time.Instant.parse(value).toEpochMilli()
        } catch (e: Exception) {
            value.toLongOrNull()
        }
    }
}