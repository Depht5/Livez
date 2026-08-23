package dev.livefork.app.modules.sync.local_sync

import dev.livefork.app.app.Log
import dev.livefork.app.app.controller.BaseController
import dev.livefork.app.di.ServiceContainer
import dev.livefork.app.models.FollowUser
import dev.livefork.app.models.FollowUserTag
import dev.livefork.app.models.History
import dev.livefork.app.models.SyncClientInfoModel
import dev.livefork.app.services.BulkDataImportService
import dev.livefork.app.services.SyncClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * 局域网同步设备控制器 —— 对应 Dart `sync_device_controller.dart`（273 行）。
 * 向对端设备推送本地数据；分块策略（normal/medium/large/huge）与 Dart `_syncJsonChunks` 一致。
 * 覆盖/合并弹窗与进度对话框由 UI 层负责（overlay 通过参数传入）。
 */
class SyncDeviceController(
    val client: SyncClient,
    val info: SyncClientInfoModel,
) : BaseController() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val request = SyncClientRequest()
    private val dbService get() = ServiceContainer.dbService

    /** 分块发送（对应 Dart _syncJsonChunks）。 */
    private suspend fun <T> syncJsonChunks(
        items: List<T>,
        overlay: Boolean,
        label: String,
        toJson: (T) -> Any,
        send: suspend (body: String, chunkOverlay: Boolean, chunkParams: Map<String, String>) -> Boolean,
        onProgress: ((String, Int, Int, String) -> Unit)? = null,
    ) {
        val policy = BulkDataImportService.policyForCount(items.size)
        val chunkSize = if (policy.scale == dev.livefork.app.services.BulkDataScale.normal) {
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
            val body = kotlinx.serialization.json.JsonArray(
                chunk.map { toJson(it) as kotlinx.serialization.json.JsonElement },
            ).toString()
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

    /** 同步关注与标签（对应 Dart syncFollowAndTag）。 */
    fun syncFollowAndTag(
        overlay: Boolean,
        onToast: (String) -> Unit = { Log.d("Toast: $it") },
        onProgress: ((String, Int, Int, String) -> Unit)? = null,
    ) {
        scope.launch {
            try {
                val users = withContext(Dispatchers.IO) { dbService.getFollowList() }
                val tags = withContext(Dispatchers.IO) { dbService.getFollowTagList() }
                syncJsonChunks(
                    items = users,
                    overlay = overlay,
                    label = "关注",
                    toJson = { it.toJsonObject() as Any },
                    send = { body, chunkOverlay, chunkParams ->
                        request.syncFollow(client, body, overlay = chunkOverlay, extraQueryParameters = chunkParams)
                    },
                    onProgress = onProgress,
                )
                // 标签和关注必须同时同步
                syncJsonChunks(
                    items = tags,
                    overlay = overlay,
                    label = "标签",
                    toJson = { it.toJsonObject() as Any },
                    send = { body, chunkOverlay, chunkParams ->
                        request.syncTag(client, body, overlay = chunkOverlay, extraQueryParameters = chunkParams)
                    },
                    onProgress = onProgress,
                )
                onToast("已同步关注列表和标签")
            } catch (e: Exception) {
                onToast("同步失败：${e.message}")
                Log.logPrint(e)
            }
        }
    }

    /** 同步配置包（对应 Dart syncProfile）。 */
    fun syncProfile(
        overlay: Boolean,
        onToast: (String) -> Unit = { Log.d("Toast: $it") },
        onProgress: ((String, Int, Int, String) -> Unit)? = null,
    ) {
        scope.launch {
            try {
                onProgress?.invoke("同步配置包", 0, 0, "")
                val profileJson = withContext(Dispatchers.IO) {
                    dev.livefork.app.services.ProfileBackupService.exportProfileJson(
                        dbService,
                        ServiceContainer.localStorage,
                        ServiceContainer.appSettings,
                    )
                }
                request.syncProfile(client, profileJson, overlay = overlay)
                onToast("已同步配置包")
            } catch (e: Exception) {
                onToast("同步失败：${e.message}")
                Log.logPrint(e)
            }
        }
    }

    /** 同步历史（对应 Dart syncHistory）。 */
    fun syncHistory(
        overlay: Boolean,
        onToast: (String) -> Unit = { Log.d("Toast: $it") },
        onProgress: ((String, Int, Int, String) -> Unit)? = null,
    ) {
        scope.launch {
            try {
                val histories = withContext(Dispatchers.IO) { dbService.getHistores() }
                syncJsonChunks(
                    items = histories,
                    overlay = overlay,
                    label = "历史",
                    toJson = { it.toJsonObject() as Any },
                    send = { body, chunkOverlay, chunkParams ->
                        request.syncHistory(client, body, overlay = chunkOverlay, extraQueryParameters = chunkParams)
                    },
                    onProgress = onProgress,
                )
                onToast("已同步历史记录")
            } catch (e: Exception) {
                onToast("同步失败：${e.message}")
                Log.logPrint(e)
            }
        }
    }

    /** 同步屏蔽词（对应 Dart syncBlockedWord）。 */
    fun syncBlockedWord(
        overlay: Boolean,
        onToast: (String) -> Unit = { Log.d("Toast: $it") },
        onProgress: ((String, Int, Int, String) -> Unit)? = null,
    ) {
        scope.launch {
            try {
                val shieldList = ServiceContainer.localStorage.getAllShield().values.toList()
                syncJsonChunks(
                    items = shieldList,
                    overlay = overlay,
                    label = "屏蔽词",
                    toJson = { kotlinx.serialization.json.JsonPrimitive(it) as Any },
                    send = { body, chunkOverlay, chunkParams ->
                        request.syncBlockedWord(client, body, overlay = chunkOverlay, extraQueryParameters = chunkParams)
                    },
                    onProgress = onProgress,
                )
                onToast("已同步屏蔽词")
            } catch (e: Exception) {
                onToast("同步失败：${e.message}")
                Log.logPrint(e)
            }
        }
    }

    /** 同步哔哩哔哩账号（对应 Dart syncBiliAccount）。 */
    fun syncBiliAccount(
        onToast: (String) -> Unit = { Log.d("Toast: $it") },
    ) {
        val account = ServiceContainer.biliBiliAccount
        if (!account.logined.value) {
            onToast("未登录哔哩哔哩")
            return
        }
        scope.launch {
            try {
                onToast("同步哔哩哔哩账号")
                request.syncBiliAccount(client, account.cookie)
                onToast("已同步哔哩哔哩账号")
            } catch (e: Exception) {
                onToast("同步失败：${e.message}")
                Log.logPrint(e)
            }
        }
    }

    /** 同步抖音账号（对应 Dart syncDouyinAccount）。 */
    fun syncDouyinAccount(
        onToast: (String) -> Unit = { Log.d("Toast: $it") },
    ) {
        val account = ServiceContainer.douyinAccount
        if (!account.hasCookie.value) {
            onToast("未配置抖音 Cookie")
            return
        }
        scope.launch {
            try {
                onToast("同步抖音账号")
                request.syncDouyinAccount(client, account.cookie)
                onToast("已同步抖音账号")
            } catch (e: Exception) {
                onToast("同步失败：${e.message}")
                Log.logPrint(e)
            }
        }
    }

    /** 同步快手账号（对应 Dart syncKuaishouAccount）。 */
    fun syncKuaishouAccount(
        onToast: (String) -> Unit = { Log.d("Toast: $it") },
    ) {
        val account = ServiceContainer.kuaishouAccount
        if (!account.hasCookie.value) {
            onToast("未配置快手 Cookie")
            return
        }
        scope.launch {
            try {
                onToast("同步快手账号")
                request.syncKuaishouAccount(
                    client,
                    account.cookie,
                    account.kww,
                    account.cookieExpiresAtMs.value,
                )
                onToast("已同步快手账号")
            } catch (e: Exception) {
                onToast("同步失败：${e.message}")
                Log.logPrint(e)
            }
        }
    }
}