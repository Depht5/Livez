package com.kotlin_livez.app.modules.follow_user

import com.kotlin_livez.app.app.EventBus
import com.kotlin_livez.app.app.Log
import com.kotlin_livez.app.app.Sites
import com.kotlin_livez.app.app.controller.AppSettingsController
import com.kotlin_livez.app.app.controller.BasePageController
import com.kotlin_livez.app.models.FollowUser
import com.kotlin_livez.app.models.FollowUserTag
import com.kotlin_livez.app.services.CurrentRoomService
import com.kotlin_livez.app.services.DBService
import com.kotlin_livez.app.services.FollowService
import com.kotlin_livez.core.model.FollowRefreshScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** 关注分组模式（对应 Dart FollowGroupMode）。 */
enum class FollowGroupMode { liveStatus, platform }

/**
 * 分组选项（对应 Dart FollowGroupOption）。
 *
 * 值语义（`data class`）：groupOptions 是**计算属性**（每次读取都新建列表与选项实例），
 * 值等值让 UI 侧能按 groupMode/followList 记忆化它、并让等值的新旧列表互相抵消；
 * 选中判定用的是 `option.id == selectedGroupId`（见 UI 与 filterBySelectedGroup），
 * 无任何按实例身份比较或把选项当集合键的用法，故改值等值无行为变化。
 */
data class FollowGroupOption(
    val id: String,
    val title: String,
    val siteId: String? = null,
    val liveStatus: Int? = null,
)

/**
 * 关注页控制器 —— 对应 Dart `FollowUserController`
 * （lib/modules/follow_user/follow_user_controller.dart）。
 * 分页/筛选/分组逻辑完整镜像；滚动动画、确认框、打开直播间路由由 UI 层处理。
 * 多开同屏（inline multi-room）在 Android 已关闭（Dart PlatformUtils 冻结 false）。
 */
class FollowUserController(
    private val appSettings: AppSettingsController,
    private val followService: FollowService,
    private val dbService: DBService,
    private val currentRoom: CurrentRoomService,
) : BasePageController<FollowUser>() {

    companion object {
        const val paginationThreshold = 400
        const val TAG_ALL = "全部"
        const val TAG_LIVE = "直播中"
        const val TAG_NOT_LIVE = "未开播"
    }

    /** 多开同屏在 Android 关闭（对应 Dart PlatformUtils.supportsInlineMultiRoom=false） */
    private val supportsInlineMultiRoom = false

    val groupMode = MutableStateFlow(FollowGroupMode.liveStatus)
    val selectedGroupId = MutableStateFlow("all")
    val searchKeyword = MutableStateFlow("")
    val multiSelectMode = MutableStateFlow(false)
    val selectedMultiRoomKeys = MutableStateFlow<Set<String>>(emptySet())
    val currentDisplayPage = MutableStateFlow(1)
    val totalDisplayPages = MutableStateFlow(1)
    val paginationEnabled = MutableStateFlow(false)

    /** 内置标签 + 用户自定义标签（对应 Dart tagList） */
    val tagList = MutableStateFlow<List<FollowUserTag>>(
        listOf(
            FollowUserTag(id = "0", tag = TAG_ALL, userId = emptyList()),
            FollowUserTag(id = "1", tag = TAG_LIVE, userId = emptyList()),
            FollowUserTag(id = "2", tag = TAG_NOT_LIVE, userId = emptyList()),
        ),
    )

    /** 用户自定义标签（对应 Dart userTagList） */
    val userTagList = MutableStateFlow<List<FollowUserTag>>(emptyList())

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var indexedEventSub: Job? = null
    private var updatedListJob: Job? = null

    /** 打开直播间回调（UI 层注入路由） */
    var onOpenRoom: ((siteId: String, roomId: String) -> Unit)? = null

    init {
        pageSize = appSettings.followPageSize.value
        restoreGroupSelection()
        indexedEventSub = EventBus.listen<Int>(EventBus.kBottomNavigationBarClicked) { index ->
            if (index == 1) refreshData()
        }
        updatedListJob = scope.launch {
            followService.updatedListEvents.collect { filterData() }
        }
        // 首次加载与启动刷新共用 FollowService.ensureLoaded()（加载单飞）：
        // 不再各自 loadFollows 互相替换列表，避免启动刷新的状态写回孤儿对象。
        scope.launch {
            followService.ensureLoaded().join()
            // 原 init 的 refreshData(forceStatus = false) 会在加载后同步标签列表；保留该行为。
            updateTagList()
            filterData()
            // 进页自动刷新不再以「当前列表非空」为前提（应用启动时 DB 尚未读完，旧判据恒为假）。
            // 与 refreshOnStartup 的 force=true 刷新由 30s 冷却自然去重。
            if (appSettings.followRefreshOnEnter.value) {
                followService.startUpdateStatus(force = false)
            }
        }
    }

    private fun restoreGroupSelection() {
        groupMode.value = if (appSettings.followGroupMode.value == "platform") {
            FollowGroupMode.platform
        } else {
            FollowGroupMode.liveStatus
        }
        selectedGroupId.value = appSettings.followSelectedGroupId.value
    }

    /** 刷新（对应 Dart refreshData；不走基类分页链路，直接重载关注数据）。 */
    override fun refreshData() {
        refreshData(forceStatus = true)
    }

    fun refreshData(forceStatus: Boolean) {
        pageSize = appSettings.followPageSize.value
        scope.launch {
            followService.loadData(
                updateStatus = forceStatus,
                forceUpdateStatus = forceStatus,
            )
            updateTagList()
            filterData()
        }
    }

    override suspend fun getData(page: Int, pageSize: Int): List<FollowUser> {
        val items = buildFilteredList()
        val start = (page - 1) * pageSize
        if (start >= items.size) return emptyList()
        val end = (start + pageSize).coerceIn(0, items.size)
        return items.subList(start, end)
    }

    fun updateTagList() {
        userTagList.value = followService.followTagList.value
        val combined = tagList.value.take(3).toMutableList()
        val existing = combined.map { it.tag }.toSet()
        for (tag in userTagList.value) {
            if (tag.tag !in existing) {
                combined.add(tag)
            }
        }
        tagList.value = combined
    }

    fun filterData() {
        val items = buildFilteredList()
        rebuildPagedList(items)
        pageEmpty.value = items.isEmpty()
    }

    private fun rebuildPagedList(items: List<FollowUser>) {
        pageSize = appSettings.followPageSize.value
        paginationEnabled.value = items.size > paginationThreshold
        if (!paginationEnabled.value) {
            currentDisplayPage.value = 1
            totalDisplayPages.value = 1
            list.value = items
            requestVisiblePreviews(items)
            return
        }
        val maxPageSize = ((items.size / 2) + 1).coerceIn(2, items.size)
        val effectivePageSize = pageSize.coerceIn(2, maxPageSize)
        if (effectivePageSize != pageSize) {
            pageSize = effectivePageSize
            appSettings.setFollowPageSize(effectivePageSize)
        }
        totalDisplayPages.value = ((items.size + effectivePageSize - 1) / effectivePageSize)
            .coerceIn(1, items.size)
        if (currentDisplayPage.value > totalDisplayPages.value) {
            currentDisplayPage.value = totalDisplayPages.value
        }
        if (currentDisplayPage.value < 1) {
            currentDisplayPage.value = 1
        }
        val start = (currentDisplayPage.value - 1) * effectivePageSize
        val end = (start + effectivePageSize).coerceIn(0, items.size)
        list.value = items.subList(start, end)
        requestVisiblePreviews(list.value)
    }

    val currentPageTargets: List<FollowUser> get() = list.value

    val currentRefreshScopeKey: String
        get() {
            val mode = if (groupMode.value == FollowGroupMode.platform) "platform" else "live"
            return "${currentDisplayPage.value}:${selectedGroupId.value}:$mode"
        }

    /** 刷新当前页状态（对应 Dart refreshCurrentPageStatus）。 */
    fun refreshCurrentPageStatus() {
        scope.launch {
            val pageItems = if (paginationEnabled.value) currentPageTargets else buildFilteredList()
            followService.refreshSelectedStatus(
                followService.buildPageFrontTargets(pageItems),
                force = true,
                scope = FollowRefreshScope.page(
                    scopeKey = followService.buildPageRefreshScopeKey(currentRefreshScopeKey),
                ),
            )
            filterData()
        }
    }

    /** 全量刷新（对应 Dart refreshAllStatus）。 */
    fun refreshAllStatus() {
        scope.launch {
            followService.refreshSelectedStatus(
                buildFilteredList(),
                includeAllNormals = true,
                force = true,
                scope = FollowRefreshScope.all(),
            )
            filterData()
        }
    }

    fun goToNextPage() {
        if (!paginationEnabled.value || currentDisplayPage.value >= totalDisplayPages.value) return
        currentDisplayPage.value += 1
        filterData()
    }

    fun goToPreviousPage() {
        if (!paginationEnabled.value || currentDisplayPage.value <= 1) return
        currentDisplayPage.value -= 1
        filterData()
    }

    private fun currentRoomIndexIn(items: List<FollowUser>): Int {
        val currentKey = currentRoom.currentKey
        if (currentKey.isEmpty()) return -1
        return items.indexOfFirst { "${it.siteId}_${it.roomId}" == currentKey }
    }

    private fun distinctFollowUsers(items: Iterable<FollowUser>): List<FollowUser> {
        val result = mutableListOf<FollowUser>()
        val seenIds = mutableSetOf<String>()
        for (item in items) {
            val id = if (item.id.trim().isNotEmpty()) item.id.trim()
            else "${item.siteId}_${item.roomId}"
            if (seenIds.add(id)) result.add(item)
        }
        return result
    }

    /** 分组选项（对应 Dart groupOptions）。 */
    val groupOptions: List<FollowGroupOption>
        get() {
            val options = mutableListOf(FollowGroupOption(id = "all", title = "全部"))
            if (groupMode.value == FollowGroupMode.liveStatus) {
                options.add(FollowGroupOption(id = "live", title = "直播中", liveStatus = 2))
                options.add(FollowGroupOption(id = "not_live", title = "未开播", liveStatus = 1))
            } else {
                val siteIds = followService.followList.value.map { it.siteId }.toSet().toMutableList()
                val siteSort = Sites.supportSites.map { it.id }
                siteIds.sortWith { a, b ->
                    val aIndex = siteSort.indexOf(a)
                    val bIndex = siteSort.indexOf(b)
                    when {
                        aIndex < 0 && bIndex < 0 -> a.compareTo(b)
                        aIndex < 0 -> 1
                        bIndex < 0 -> -1
                        else -> aIndex.compareTo(bIndex)
                    }
                }
                for (siteId in siteIds) {
                    val site = Sites.allSites[siteId]
                    options.add(
                        FollowGroupOption(
                            id = "site:$siteId",
                            title = site?.name ?: siteId,
                            siteId = siteId,
                        ),
                    )
                }
            }
            return options
        }

    private fun filterBySelectedGroup(): List<FollowUser> {
        val selected = groupOptions.firstOrNull { it.id == selectedGroupId.value }
        val source = followService.followList.value
        if (selected == null || selected.id == "all") {
            selectedGroupId.value = "all"
            return followService.sortFollowUsers(distinctFollowUsers(source))
        }
        val liveStatus = selected.liveStatus
        if (liveStatus != null) {
            val expectedStatus = if (liveStatus == 1) setOf(0, 1) else setOf(liveStatus)
            return followService.sortFollowUsers(
                distinctFollowUsers(source.filter { it.liveStatus in expectedStatus }),
            )
        }
        val siteId = selected.siteId
        if (siteId != null) {
            return followService.sortFollowUsers(
                distinctFollowUsers(source.filter { it.siteId == siteId }),
            )
        }
        return followService.sortFollowUsers(distinctFollowUsers(source))
    }

    private fun buildFilteredList(): List<FollowUser> {
        var items: Iterable<FollowUser> = filterBySelectedGroup()
        if (appSettings.followOnlyLive.value) {
            items = items.filter { it.liveStatus == 2 }
        }
        val keyword = searchKeyword.value.trim().lowercase()
        if (keyword.isNotEmpty()) {
            items = items.filter { it.userName.lowercase().contains(keyword) }
        }
        // 关注页显示排序 = 关注时间升序（task 08-29-chat-pin-bubble-follow-grid 用户定标：
        // 先关注的排前面；不再用 sortFollowUsers 的「直播中优先桶 + 时间倒序」）。
        // 仅显示层排序——状态刷新调度（服务内 _orderRefreshBucketBySite 等）不受影响。
        return distinctFollowUsers(items).sortedBy { it.addTime }
    }

    fun setSearchKeyword(value: String) {
        searchKeyword.value = value.trim()
        currentDisplayPage.value = 1
        filterData()
    }

    fun clearSearchKeyword() {
        if (searchKeyword.value.isEmpty()) return
        searchKeyword.value = ""
        currentDisplayPage.value = 1
        filterData()
    }

    private fun requestVisiblePreviews(items: List<FollowUser>) {
        if (items.isEmpty() || !appSettings.followShowLiveCover.value) return
        followService.refreshVisiblePreviews(items)
    }

    fun setDisplayStyle(value: String) {
        appSettings.setFollowDisplayStyle(value)
        filterData()
    }

    fun setOnlyLive(value: Boolean) {
        appSettings.setFollowOnlyLive(value)
        currentDisplayPage.value = 1
        filterData()
    }

    fun setRefreshOnEnter(value: Boolean) {
        appSettings.setFollowRefreshOnEnter(value)
    }

    fun setShowLiveCover(value: Boolean) {
        appSettings.setFollowShowLiveCover(value)
        filterData()
    }

    fun setGroupMode(mode: FollowGroupMode) {
        groupMode.value = mode
        selectedGroupId.value = "all"
        saveGroupSelection()
        filterData()
    }

    fun setGroupOption(option: FollowGroupOption) {
        selectedGroupId.value = option.id
        saveGroupSelection()
        filterData()
    }

    private fun saveGroupSelection() {
        appSettings.setFollowGroupSelection(
            mode = if (groupMode.value == FollowGroupMode.platform) "platform" else "liveStatus",
            groupId = selectedGroupId.value,
        )
    }

    /** 取消关注（确认框由 UI 层弹出，文案见 Dart 冻结值）。 */
    fun removeItem(item: FollowUser) {
        scope.launch {
            if (item.tag != TAG_ALL) {
                val tag = tagList.value.firstOrNull { it.tag == item.tag }
                if (tag != null) {
                    updateTag(tag.copy(userId = tag.userId - item.id))
                }
            }
            dbService.deleteFollow(item.id)
            refreshData()
        }
    }

    fun updateItem(item: FollowUser) {
        scope.launch { followService.addFollow(item) }
    }

    fun isSelectedForMultiRoom(item: FollowUser): Boolean =
        selectedMultiRoomKeys.value.contains(item.id)

    fun toggleMultiSelectMode() {
        if (!supportsInlineMultiRoom) {
            Log.d("Toast: 当前移动端版本已关闭多开同屏")
            return
        }
        multiSelectMode.value = !multiSelectMode.value
        if (!multiSelectMode.value) {
            selectedMultiRoomKeys.value = emptySet()
        }
    }

    fun toggleMultiRoomItem(item: FollowUser) {
        if (!supportsInlineMultiRoom) return
        if (item.liveStatus != 2) {
            Log.d("Toast: 只能选择直播中的关注")
            return
        }
        val keys = selectedMultiRoomKeys.value
        selectedMultiRoomKeys.value =
            if (item.id in keys) keys - item.id else keys + item.id
    }

    fun openSelectedMultiRooms() {
        Log.d("Toast: 当前移动端版本已关闭多开同屏")
    }

    fun toggleSpecialFollow(item: FollowUser) {
        scope.launch {
            followService.updateSpecialFollow(item, !item.isSpecialFollow)
            filterData()
        }
    }

    /** 打开关注直播间（对应 Dart openFollowRoom；路由由 UI 层注入）。 */
    fun openFollowRoom(item: FollowUser) {
        scope.launch {
            val resolved = followService.resolveFollowBeforeEnter(item)
            val site = Sites.allSites[resolved.siteId] ?: return@launch
            onOpenRoom?.invoke(site.id, resolved.roomId)
        }
    }

    /** 修改 item 的标签（对应 Dart setItemTag）。 */
    fun setItemTag(item: FollowUser, targetTag: FollowUserTag) {
        val curTag = tagList.value.firstOrNull { it.tag == item.tag }
        val tarTag = targetTag
        val updatedCur = curTag?.copy(userId = (curTag.userId - item.id).distinct())
        val updatedTar = tarTag.copy(userId = if (tarTag.userId.contains(item.id)) tarTag.userId else tarTag.userId + item.id)
        item.tag = tarTag.tag
        if (updatedCur != null) updateTag(updatedCur)
        updateTag(updatedTar)
        updateItem(item)
        filterData()
    }

    /** 删除标签（对应 Dart removeTag）。 */
    fun removeTag(tag: FollowUserTag) {
        scope.launch {
            for (userId in tag.userId) {
                val follow = dbService.getFollow(userId)
                if (follow != null) {
                    follow.tag = TAG_ALL
                    followService.addFollow(follow)
                }
            }
            followService.delFollowUserTag(tag)
            updateTagList()
            Log.i("删除tag${tag.tag}")
        }
    }

    fun addTag(tag: String) {
        scope.launch {
            followService.addFollowUserTag(tag)
            updateTagList()
        }
    }

    fun updateTag(followUserTag: FollowUserTag) {
        if (followUserTag.tag == TAG_ALL) return
        scope.launch { followService.updateFollowUserTag(followUserTag) }
    }

    /** 重命名标签（对应 Dart updateTagName）。 */
    fun updateTagName(followUserTag: FollowUserTag, newTagName: String) {
        if (followUserTag.tag == newTagName) return
        if (tagList.value.any { it.tag == newTagName }) {
            Log.d("Toast: 标签名重复，修改失败")
            return
        }
        val newTag = followUserTag.copy(tag = newTagName)
        updateTag(newTag)
        scope.launch {
            for (userId in newTag.userId) {
                val follow = dbService.getFollow(userId)
                if (follow != null) {
                    follow.tag = newTagName
                    followService.addFollow(follow)
                }
            }
            Log.d("Toast: 标签名修改成功")
            updateTagList()
            filterData()
        }
    }

    /** 调整标签顺序（对应 Dart updateTagOrder）。 */
    fun updateTagOrder(oldIndex: Int, newIndex: Int) {
        var target = newIndex
        if (target > oldIndex) target -= 1
        val current = userTagList.value.toMutableList()
        if (oldIndex < 0 || oldIndex >= current.size) return
        val item = current.removeAt(oldIndex)
        current.add(target.coerceIn(0, current.size), item)
        userTagList.value = current
        tagList.value = tagList.value.take(3) + current
        scope.launch { dbService.updateFollowTagOrder(current) }
    }

    override fun dispose() {
        indexedEventSub?.cancel()
        updatedListJob?.cancel()
        scope.coroutineContext[Job]?.cancel()
        super.dispose()
    }
}