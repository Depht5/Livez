package com.kotlin_livez.app.modules.sync.local_sync

import com.kotlin_livez.app.app.Constant
import com.kotlin_livez.app.app.EventBus
import com.kotlin_livez.app.app.Log
import com.kotlin_livez.app.app.controller.BaseController
import com.kotlin_livez.app.di.ServiceContainer
import com.kotlin_livez.app.models.SyncClientInfoModel
import com.kotlin_livez.app.services.BulkDataImportService
import com.kotlin_livez.app.services.SyncClient
import com.kotlin_livez.app.services.SyncProgress
import com.kotlin_livez.app.services.SyncProgressCallback
import com.kotlin_livez.app.services.parseDouyuAccountBody
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** 同步数据类别（task 10-25-lan-sync-batch-ui：设备页由七个单类入口改为多选项 + 方向按钮）。
 *  枚举序即页面展示序与批量执行序。[ALL] 为快捷项，执行时映射为
 *  「配置包 + 斗鱼凭据通道」而非逐类展开（配置包已含设置/关注/历史/屏蔽词/哔哩哔哩与抖音
 *  账号 Cookie，斗鱼 ltp0/dyDid 不在包内、由独立通道补齐）。 */
enum class SyncDataType(val label: String) {
    ALL("全部数据"),
    SETTINGS("设置选项"),
    FOLLOW("关注列表"),
    HISTORY("观看记录"),
    DANMU("弹幕屏蔽词"),
    BILIBILI("哔哩哔哩账号"),
    DOUYU("斗鱼账号"),
    DOUYIN("抖音账号"),
}

/**
 * 局域网同步设备控制器 —— 对应 Dart `sync_device_controller.dart`。
 * task 10-25-lan-sync-batch-ui 起由「单类推送方法」重构为**批量选择式**：
 * 页面先勾选数据类别，再经 [pushSelection]（本机 → 对端）或 [pullSelection]
 * （对端 → 本机，Livez 私有 GET 扩展）顺序执行。分块策略（normal/medium/large/huge）
 * 与 Dart `_syncJsonChunks` 一致；覆盖/合并弹窗与进度对话框由 UI 层负责。
 */
class SyncDeviceController(
    val client: SyncClient,
    val info: SyncClientInfoModel,
) : BaseController() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val request = SyncClientRequest()
    private val json = Json { ignoreUnknownKeys = true }
    private val dbService get() = ServiceContainer.dbService

    /** 取消协程（对齐 Dart onClose；页面出栈时由 Screen 的 DisposableEffect 调用，
     *  批量执行中离开页面会一并取消该次传输）。 */
    fun dispose() {
        scope.coroutineContext[Job]?.cancel()
    }

    /**
     * 断开连接（用户决策 task 10-25-lan-sync-polish：退出设备页回到局域网连接页时调用）：
     * fire-and-forget 发 `GET /bye` 让对端立即移除连接提示卡；失败忽略。
     * 独立线程执行——[dispose] 已取消本控制器协程域。
     */
    fun disconnect() {
        dispose()
        kotlin.concurrent.thread(isDaemon = true, name = "sync-bye") {
            runCatching { kotlinx.coroutines.runBlocking { request.bye(client) } }
        }
    }

    /* ---------- 发送（本机 → 对端） ---------- */

    /**
     * 批量推送所选类别（顺序执行、失败即停、未登录账号跳过并在结尾汇总）。
     * 「全部数据」走配置包 + 斗鱼凭据通道一次到位，不逐类展开。
     */
    fun pushSelection(
        selection: Set<SyncDataType>,
        overlay: Boolean,
        onToast: (String) -> Unit,
        onProgress: ((String, Int, Int, String) -> Unit)? = null,
    ) {
        scope.launch {
            val skipped = mutableListOf<String>()
            var currentLabel = ""
            try {
                if (SyncDataType.ALL in selection) {
                    currentLabel = "配置包"
                    onProgress?.invoke("同步配置包", 0, 2, "")
                    val profileJson = withContext(Dispatchers.IO) {
                        com.kotlin_livez.app.services.ProfileBackupService.exportProfileJson(
                            dbService,
                            ServiceContainer.localStorage,
                            ServiceContainer.appSettings,
                        )
                    }
                    request.syncProfile(client, profileJson, overlay = overlay)
                    val account = ServiceContainer.douyuAccount
                    if (!account.hasCookie.value) {
                        skipped.add("斗鱼（本机未配置）")
                    } else {
                        onProgress?.invoke("同步配置包", 1, 2, "发送斗鱼续期凭据")
                        request.syncDouyuAccount(
                            client,
                            account.cookie,
                            ltp0 = account.ltp0,
                            dyDid = account.dyDid,
                        )
                    }
                } else {
                    val categories = planCategories(selection)
                    categories.forEachIndexed { index, type ->
                        currentLabel = type.label
                        val stage = "同步${type.label} (${index + 1}/${categories.size})"
                        pushCategory(type, overlay, stage, skipped, onProgress)
                    }
                }
                onToast(completionToast("已发送所选数据", skipped))
            } catch (e: Exception) {
                onToast("同步${currentLabel}失败：${e.message}")
                Log.logPrint(e)
            }
        }
    }

    /** 单类推送；未登录账号记入 [skipped]（不中断整批）。 */
    private suspend fun pushCategory(
        type: SyncDataType,
        overlay: Boolean,
        stage: String,
        skipped: MutableList<String>,
        onProgress: ((String, Int, Int, String) -> Unit)?,
    ) {
        val progress = wrapProgress(stage, onProgress)
        when (type) {
            SyncDataType.SETTINGS -> {
                onProgress?.invoke(stage, 0, 0, "")
                val payload = withContext(Dispatchers.IO) {
                    com.kotlin_livez.app.services.ProfileBackupService.exportSettingsJson(
                        ServiceContainer.localStorage,
                    )
                }
                request.syncSettings(client, payload, overlay = overlay)
            }
            SyncDataType.FOLLOW -> pushFollowAndTag(overlay, progress)
            SyncDataType.HISTORY -> {
                val histories = withContext(Dispatchers.IO) { dbService.getHistores() }
                syncJsonChunks(histories, overlay, "历史", { it.toJsonObject() as JsonElement }, progress) { body, chunkOverlay, chunkParams ->
                    request.syncHistory(client, body, overlay = chunkOverlay, extraQueryParameters = chunkParams)
                }
            }
            SyncDataType.DANMU -> {
                val shieldList = ServiceContainer.localStorage.getAllShield().values.toList()
                syncJsonChunks(shieldList, overlay, "屏蔽词", { JsonPrimitive(it) }, progress) { body, chunkOverlay, chunkParams ->
                    request.syncBlockedWord(client, body, overlay = chunkOverlay, extraQueryParameters = chunkParams)
                }
            }
            SyncDataType.BILIBILI -> {
                val account = ServiceContainer.biliBiliAccount
                if (!account.logined.value) {
                    skipped.add("哔哩哔哩（本机未登录）")
                } else {
                    onProgress?.invoke(stage, 0, 0, "")
                    request.syncBiliAccount(client, account.cookie)
                }
            }
            SyncDataType.DOUYIN -> {
                val account = ServiceContainer.douyinAccount
                if (!account.hasCookie.value) {
                    skipped.add("抖音（本机未配置）")
                } else {
                    onProgress?.invoke(stage, 0, 0, "")
                    request.syncDouyinAccount(client, account.cookie)
                }
            }
            SyncDataType.DOUYU -> {
                val account = ServiceContainer.douyuAccount
                if (!account.hasCookie.value) {
                    skipped.add("斗鱼（本机未配置）")
                } else {
                    onProgress?.invoke(stage, 0, 0, "")
                    request.syncDouyuAccount(client, account.cookie, ltp0 = account.ltp0, dyDid = account.dyDid)
                }
            }
            SyncDataType.ALL -> Unit // ALL 在 pushSelection 顶部单独处理，不会进入本函数
        }
    }

    /* ---------- 拉取（对端 → 本机，Livez 私有 GET 扩展） ---------- */

    /**
     * 从对端拉取所选类别并导入本机（复用接收端同款导入服务，设置类型化链路一致）。
     * 对端未登录的账号跳过（不用空数据清掉本机账号）；失败即停并注明类别。
     */
    fun pullSelection(
        selection: Set<SyncDataType>,
        overlay: Boolean,
        onToast: (String) -> Unit,
        onProgress: ((String, Int, Int, String) -> Unit)? = null,
    ) {
        scope.launch {
            val skipped = mutableListOf<String>()
            var currentLabel = ""
            try {
                if (SyncDataType.ALL in selection) {
                    currentLabel = "配置包"
                    onProgress?.invoke("拉取配置包", 0, 2, "")
                    val profileBody = request.pullData(client, "profile")
                    withContext(Dispatchers.IO) {
                        com.kotlin_livez.app.services.ProfileBackupService.importProfileJson(
                            profileBody,
                            overwrite = overlay,
                            onProgress = importProgress("拉取配置包", onProgress),
                        )
                    }
                    currentLabel = SyncDataType.DOUYU.label
                    onProgress?.invoke("拉取配置包", 1, 2, "拉取斗鱼续期凭据")
                    pullDouyuAccount(skipped)
                } else {
                    val categories = planCategories(selection)
                    categories.forEachIndexed { index, type ->
                        currentLabel = type.label
                        val stage = "拉取${type.label} (${index + 1}/${categories.size})"
                        pullCategory(type, overlay, stage, skipped, onProgress)
                    }
                }
                onToast(completionToast("已同步所选数据", skipped))
            } catch (e: Exception) {
                onToast("拉取${currentLabel}失败：${e.message}")
                Log.logPrint(e)
            }
        }
    }

    /** 单类拉取导入；对端空 Cookie 的账号记入 [skipped]（不清本机账号）。 */
    private suspend fun pullCategory(
        type: SyncDataType,
        overlay: Boolean,
        stage: String,
        skipped: MutableList<String>,
        onProgress: ((String, Int, Int, String) -> Unit)?,
    ) {
        when (type) {
            SyncDataType.SETTINGS -> {
                onProgress?.invoke(stage, 0, 0, "")
                val body = request.pullData(client, "settings")
                withContext(Dispatchers.IO) {
                    com.kotlin_livez.app.services.ProfileBackupService.importProfileJson(
                        body,
                        overwrite = overlay,
                    )
                }
            }
            SyncDataType.FOLLOW -> {
                onProgress?.invoke(stage, 0, 0, "")
                val users = parseArrayBody(request.pullData(client, "follow"), "关注列表格式不是数组")
                withContext(Dispatchers.IO) {
                    BulkDataImportService.importFollowUsers(
                        users, overwrite = overlay,
                        onProgress = importProgress(stage, onProgress),
                    )
                }
                // 标签和关注必须同时同步
                val tags = parseArrayBody(request.pullData(client, "tag"), "标签列表格式不是数组")
                withContext(Dispatchers.IO) {
                    BulkDataImportService.importFollowTags(
                        tags, overwrite = overlay,
                        onProgress = importProgress(stage, onProgress),
                    )
                }
                EventBus.emit(Constant.kUpdateFollow, 0)
            }
            SyncDataType.HISTORY -> {
                onProgress?.invoke(stage, 0, 0, "")
                val histories = parseArrayBody(request.pullData(client, "history"), "历史记录格式不是数组")
                withContext(Dispatchers.IO) {
                    BulkDataImportService.importHistories(
                        histories, overwrite = overlay,
                        onProgress = importProgress(stage, onProgress),
                    )
                }
                EventBus.emit(Constant.kUpdateHistory, 0)
            }
            SyncDataType.DANMU -> {
                onProgress?.invoke(stage, 0, 0, "")
                val words = parseStringArrayBody(request.pullData(client, "blocked_word"), "屏蔽词格式不是数组")
                withContext(Dispatchers.IO) {
                    BulkDataImportService.importShieldValues(
                        words, overwrite = overlay,
                        onProgress = importProgress(stage, onProgress),
                    )
                }
            }
            SyncDataType.BILIBILI -> {
                onProgress?.invoke(stage, 0, 0, "")
                val cookie = parseCookieBody(request.pullData(client, "account/bilibili"))
                if (cookie.isEmpty()) {
                    skipped.add("哔哩哔哩（对端未登录）")
                } else {
                    withContext(Dispatchers.IO) {
                        ServiceContainer.biliBiliAccount.setCookie(cookie)
                        // 对齐接收端：落 Cookie 后立即拉用户信息（失败不影响同步结果）
                        runCatching { ServiceContainer.biliBiliAccount.loadUserInfo() }
                            .onFailure { Log.logPrint(it) }
                    }
                }
            }
            SyncDataType.DOUYIN -> {
                onProgress?.invoke(stage, 0, 0, "")
                val cookie = parseCookieBody(request.pullData(client, "account/douyin"))
                if (cookie.isEmpty()) {
                    skipped.add("抖音（对端未配置）")
                } else {
                    withContext(Dispatchers.IO) { ServiceContainer.douyinAccount.setCookie(cookie) }
                }
            }
            SyncDataType.DOUYU -> {
                onProgress?.invoke(stage, 0, 0, "")
                pullDouyuAccount(skipped)
            }
            SyncDataType.ALL -> Unit // ALL 在 pullSelection 顶部单独处理，不会进入本函数
        }
    }

    /**
     * 拉取斗鱼账号（cookie + 续期凭据）。对端空 cookie = 对端未登录 → 跳过
     * （拉取语义是「获取数据」，不用空数据清除本机账号）。
     */
    private suspend fun pullDouyuAccount(skipped: MutableList<String>) {
        val body = request.pullData(client, "account/douyu")
        val account = parseDouyuAccountBody(json, body)
        if (account.cookie.isEmpty()) {
            skipped.add("斗鱼（对端未配置）")
            return
        }
        withContext(Dispatchers.IO) {
            ServiceContainer.douyuAccount.setCookie(account.cookie, account.ltp0, account.dyDid)
        }
    }

    /* ---------- 共用 ---------- */

    /** 分块发送（对应 Dart _syncJsonChunks）。 */
    private suspend fun <T> syncJsonChunks(
        items: List<T>,
        overlay: Boolean,
        label: String,
        toJson: (T) -> JsonElement,
        onProgress: ((String, Int, Int, String) -> Unit)? = null,
        send: suspend (body: String, chunkOverlay: Boolean, chunkParams: Map<String, String>) -> Boolean,
    ) {
        val policy = BulkDataImportService.policyForCount(items.size)
        val chunkSize = if (policy.scale == com.kotlin_livez.app.services.BulkDataScale.normal) {
            items.size
        } else {
            policy.dbBatchSize
        }
        val chunkTotal = if (items.isEmpty()) 1 else ((items.size - 1) / chunkSize) + 1
        Log.d("本地发送$label：count=${items.size} scale=${policy.scale.label}")
        if (items.isEmpty()) {
            onProgress?.invoke("发送$label", 0, 0, "发送空列表")
            send("[]", overlay, mapOf(
                "chunkIndex" to "1",
                "chunkTotal" to "1",
                "itemStart" to "0",
                "itemEnd" to "0",
                "itemTotal" to "0",
            ))
            return
        }
        var start = 0
        var chunkIndex = 1
        while (start < items.size) {
            val end = (start + chunkSize).coerceAtMost(items.size)
            val chunk = items.subList(start, end)
            val body = JsonArray(chunk.map { toJson(it) }).toString()
            onProgress?.invoke(
                "发送$label", end, items.size,
                "发送第 $chunkIndex/$chunkTotal 段，$end/${items.size}",
            )
            send(body, overlay && start == 0, mapOf(
                "chunkIndex" to chunkIndex.toString(),
                "chunkTotal" to chunkTotal.toString(),
                "itemStart" to start.toString(),
                "itemEnd" to end.toString(),
                "itemTotal" to items.size.toString(),
            ))
            Log.d("本地发送${label}分段：${start + 1}-$end/${items.size} bytes=${body.length}")
            if (end >= items.size) break
            start = end
            chunkIndex++
        }
    }

    /** 同步关注与标签（对应 Dart syncFollowAndTag，关注与标签必须同时同步）。 */
    private suspend fun pushFollowAndTag(
        overlay: Boolean,
        onProgress: ((String, Int, Int, String) -> Unit)?,
    ) {
        val users = withContext(Dispatchers.IO) { dbService.getFollowList() }
        val tags = withContext(Dispatchers.IO) { dbService.getFollowTagList() }
        syncJsonChunks(
            items = users,
            overlay = overlay,
            label = "关注",
            toJson = { it.toJsonObject() },
            onProgress = onProgress,
            send = { body, chunkOverlay, chunkParams ->
                request.syncFollow(client, body, overlay = chunkOverlay, extraQueryParameters = chunkParams)
            },
        )
        syncJsonChunks(
            items = tags,
            overlay = overlay,
            label = "标签",
            toJson = { it.toJsonObject() },
            onProgress = onProgress,
            send = { body, chunkOverlay, chunkParams ->
                request.syncTag(client, body, overlay = chunkOverlay, extraQueryParameters = chunkParams)
            },
        )
    }

    /** UI 四参进度 → 导入服务回调（阶段名换成批量上下文的 [stage]）。 */
    private fun importProgress(
        stage: String,
        onProgress: ((String, Int, Int, String) -> Unit)?,
    ): SyncProgressCallback? = onProgress?.let { callback ->
        SyncProgressCallback { progress: SyncProgress ->
            callback(stage, progress.current, progress.total, progress.message)
        }
    }

    /** 分块推送的进度事件统一冠以类别阶段名（携带批量序号）。 */
    private fun wrapProgress(
        stage: String,
        onProgress: ((String, Int, Int, String) -> Unit)?,
    ): ((String, Int, Int, String) -> Unit)? = onProgress?.let { callback ->
        { _, current, total, message -> callback(stage, current, total, message) }
    }

    private fun completionToast(done: String, skipped: List<String>): String =
        if (skipped.isEmpty()) done else "$done；已跳过：${skipped.joinToString("、")}"

    private fun parseArrayBody(body: String, errMsg: String): List<JsonElement> {
        val element = json.parseToJsonElement(body)
        if (element !is JsonArray) throw IllegalArgumentException(errMsg)
        return element.toList()
    }

    private fun parseStringArrayBody(body: String, errMsg: String): List<String> {
        val array = json.parseToJsonElement(body) as? JsonArray
            ?: throw IllegalArgumentException(errMsg)
        return array.mapNotNull { (it as? JsonPrimitive)?.content }
    }

    private fun parseCookieBody(body: String): String {
        val obj = json.parseToJsonElement(body).let { it as? JsonObject }
            ?: throw IllegalArgumentException("账号数据格式不正确")
        return (obj["cookie"] as? JsonPrimitive)?.takeIf { it !is kotlinx.serialization.json.JsonNull }
            ?.content ?: ""
    }

    companion object {
        /** 选项展开为执行序列（纯函数供 LocalSyncLogicTest 复测）：
         *  含 ALL → 全部 7 类（枚举序，不含 ALL 自身）；否则按枚举序过滤所选。 */
        fun planCategories(selection: Set<SyncDataType>): List<SyncDataType> =
            if (SyncDataType.ALL in selection) {
                SyncDataType.entries.filter { it != SyncDataType.ALL }
            } else {
                SyncDataType.entries.filter { it in selection }
            }
    }
}
