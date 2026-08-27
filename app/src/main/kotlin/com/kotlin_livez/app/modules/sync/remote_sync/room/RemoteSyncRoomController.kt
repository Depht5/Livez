package com.kotlin_livez.app.modules.sync.remote_sync.room

import com.kotlin_livez.app.app.Constant
import com.kotlin_livez.app.app.EventBus
import com.kotlin_livez.app.app.Log
import com.kotlin_livez.app.app.controller.BaseController
import com.kotlin_livez.app.di.ServiceContainer
import com.kotlin_livez.app.services.BulkDataImportService
import com.kotlin_livez.app.services.BulkDataScale
import com.kotlin_livez.app.services.Resp
import com.kotlin_livez.app.services.RoomSyncPayload
import com.kotlin_livez.app.services.RoomUser
import com.kotlin_livez.app.services.SignalRConnectionState
import com.kotlin_livez.app.services.SignalRService
import com.kotlin_livez.app.services.SyncProgress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 远程房间同步控制器 —— 对应 Dart `remote_sync_room_controller.dart`（540 行）。
 * 通过 SignalRService（WebSocket JSON RPC）创建/加入同步房间，
 * 发送并接收关注/历史/屏蔽词/B 站账号；倒计时 600s 自动关闭页面。
 * 覆盖弹窗、二维码展示与页面返回（Get.back）由 UI 层负责。
 */
class RemoteSyncRoomController(
    private val roomId: String,
) : BaseController() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val signalR = SignalRService()

    val currentRoomId = MutableStateFlow(if (roomId.isNotEmpty()) roomId else "--")
    val roomUsers = MutableStateFlow<List<RoomUser>>(emptyList())
    val countDown = MutableStateFlow(600)

    val hasValidRoomId: Boolean
        get() = currentRoomId.value.trim().length == SignalRService.kRoomIdLength

    private var countDownJob: Job? = null

    /** UI 层注入：房间被销毁/倒计时结束时的页面返回。 */
    var onExitListener: (() -> Unit)? = null

    fun init() {
        signalR.onToast = { Log.d("Toast: $it") }
        signalR.onProgress = { onProgressListener?.invoke(it) }
    }

    /** UI 层注入：同步进度（stage/current/total/message）。 */
    var onProgressListener: ((SyncProgress) -> Unit)? = null

    /** 连接同步服务（对应 Dart connect；onExit 对应 Get.back）。 */
    fun connect(
        onToast: (String) -> Unit = { Log.d("Toast: $it") },
        onExit: () -> Unit = {},
    ) {
        scope.launch {
            try {
                listenSignalR()
                signalR.connect()
                if (signalR.state.value == SignalRConnectionState.connected) {
                    if (roomId.isEmpty()) {
                        createRoom(onToast, onExit)
                    } else {
                        joinRoom(roomId, onToast, onExit)
                    }
                }
            } catch (e: Exception) {
                Log.logPrint(e)
                onToast("连接同步服务失败：${formatSyncError(e)}")
                onExit()
            }
        }
    }

    private suspend fun createRoom(
        onToast: (String) -> Unit,
        onExit: () -> Unit,
    ) {
        try {
            val resp = signalR.createRoom()
            if (resp.isSuccess && resp.data?.trim()?.isNotEmpty() == true) {
                currentRoomId.value = resp.data!!.trim()
                startTimer(onExit)
            } else {
                onToast(
                    if (resp.message.isEmpty()) "创建房间失败：服务未返回房间号"
                    else "创建房间失败：${formatSyncError(resp.message)}",
                )
                onExit()
            }
        } catch (e: Exception) {
            Log.logPrint(e)
            onToast("创建房间失败：${formatSyncError(e)}")
            onExit()
        }
    }

    private fun startTimer(onExit: () -> Unit) {
        countDownJob?.cancel()
        countDown.value = 600
        countDownJob = scope.launch {
            while (countDown.value > 0) {
                delay(1_000)
                countDown.value -= 1
            }
            onExit()
        }
    }

    private suspend fun joinRoom(
        roomId: String,
        onToast: (String) -> Unit,
        onExit: () -> Unit,
    ) {
        try {
            val resp = signalR.joinRoom(roomId)
            if (!resp.isSuccess) {
                onToast(resp.message)
                onExit()
            }
        } catch (e: Exception) {
            Log.logPrint(e)
            onToast("加入房间失败：${formatSyncError(e)}")
            onExit()
        }
    }

    private fun formatSyncError(e: Any): String {
        val text = e.toString().trim()
        return text.removePrefix("Exception: ").trim().ifEmpty { "未知错误" }
    }

    /** 订阅服务端推送（对应 Dart listenSignalR）。 */
    private fun listenSignalR() {
        scope.launch {
            signalR.onRoomDestroyed.collect {
                signalR.onToast?.invoke("房间已被销毁")
                onExitListener?.invoke()
            }
        }
        scope.launch {
            signalR.onRoomUserUpdated.collect { users ->
                roomUsers.value = users
            }
        }
        scope.launch {
            signalR.onFavorite.collect { onReceiveFavorite(it) }
        }
        scope.launch {
            signalR.onHistory.collect { onReceiveHistory(it) }
        }
        scope.launch {
            signalR.onShieldWord.collect { onReceiveShieldWord(it) }
        }
        scope.launch {
            signalR.onBiliAccount.collect { onReceiveBiliAccount(it) }
        }
    }

    /* ---------- 接收（对端推送 → 本地导入） ---------- */

    private suspend fun onReceiveFavorite(payload: RoomSyncPayload) {
        try {
            broadcastProgress("接收关注", payload)
            val jsonBody = Json.parseToJsonElement(payload.content)
            if (jsonBody !is JsonArray) throw IllegalArgumentException("关注列表格式不是数组")
            val result = BulkDataImportService.importFollowUsers(
                jsonBody.toList(),
                overwrite = payload.overlay,
                onProgress = wrapPayloadProgress(payload),
            )
            Log.d("房间同步关注完成：${result.logSummary}")
            if (payload.isLastChunk) {
                EventBus.emit(Constant.kUpdateFollow, 0)
                signalR.onToast?.invoke(
                    "已同步关注列表（${if (payload.itemTotal > 0) payload.itemTotal else result.imported} 条）",
                )
            }
        } catch (e: Exception) {
            signalR.onToast?.invoke("同步失败:$e")
            Log.logPrint(e)
        }
    }

    private suspend fun onReceiveHistory(payload: RoomSyncPayload) {
        try {
            broadcastProgress("接收历史", payload)
            val jsonBody = Json.parseToJsonElement(payload.content)
            if (jsonBody !is JsonArray) throw IllegalArgumentException("历史记录格式不是数组")
            val result = BulkDataImportService.importHistories(
                jsonBody.toList(),
                overwrite = payload.overlay,
                onProgress = wrapPayloadProgress(payload),
            )
            Log.d("房间同步历史完成：${result.logSummary}")
            if (payload.isLastChunk) {
                EventBus.emit(Constant.kUpdateHistory, 0)
                signalR.onToast?.invoke(
                    "已同步历史记录（${if (payload.itemTotal > 0) payload.itemTotal else result.imported} 条）",
                )
            }
        } catch (e: Exception) {
            signalR.onToast?.invoke("同步失败:$e")
            Log.logPrint(e)
        }
    }

    private suspend fun onReceiveShieldWord(payload: RoomSyncPayload) {
        try {
            broadcastProgress("接收屏蔽词", payload)
            val jsonBody = Json.parseToJsonElement(payload.content)
            if (jsonBody !is JsonArray) throw IllegalArgumentException("屏蔽词格式不是数组")
            val list = jsonBody.mapNotNull { (it as? JsonPrimitive)?.content }
            val result = BulkDataImportService.importShieldValues(
                list,
                overwrite = payload.overlay,
                onProgress = wrapPayloadProgress(payload),
            )
            Log.d("房间同步屏蔽词完成：${result.logSummary}")
            if (payload.isLastChunk) {
                signalR.onToast?.invoke(
                    "已同步屏蔽词（${if (payload.itemTotal > 0) payload.itemTotal else result.imported} 条）",
                )
            }
        } catch (e: Exception) {
            signalR.onToast?.invoke("同步失败:$e")
            Log.logPrint(e)
        }
    }

    private suspend fun onReceiveBiliAccount(payload: RoomSyncPayload) {
        try {
            val jsonBody = Json.parseToJsonElement(payload.content) as? JsonObject
                ?: throw IllegalArgumentException("账号数据格式不是对象")
            val cookie = jsonBody["cookie"]?.let { (it as? JsonPrimitive)?.content } ?: ""
            if (cookie.isEmpty()) throw IllegalArgumentException("账号 Cookie 为空")
            ServiceContainer.biliBiliAccount.setCookie(cookie)
            signalR.onToast?.invoke("已同步哔哩哔哩账号")
        } catch (e: Exception) {
            signalR.onToast?.invoke("同步失败:$e")
            Log.logPrint(e)
        }
    }

    private fun stageProgress(stage: String, payload: RoomSyncPayload) {
        val total = if (payload.itemTotal > 0) payload.itemTotal else payload.chunkTotal
        val current = if (payload.itemTotal > 0) payload.itemEnd else payload.chunkIndex
        signalR.onProgress?.invoke(
            SyncProgress(stage, current, total, if (payload.chunkTotal > 1) "接收第 ${payload.chunkIndex}/${payload.chunkTotal} 段" else stage),
        )
    }

    private fun broadcastProgress(stage: String, payload: RoomSyncPayload) {
        stageProgress(stage, payload)
    }

    private fun wrapPayloadProgress(payload: RoomSyncPayload): com.kotlin_livez.app.services.SyncProgressCallback {
        return com.kotlin_livez.app.services.SyncProgressCallback { progress ->
            if (payload.itemTotal <= 0) {
                signalR.onProgress?.invoke(SyncProgress(progress.stage, progress.current, progress.total, progress.message))
                return@SyncProgressCallback
            }
            val current = (payload.itemStart + progress.current).coerceIn(0, payload.itemTotal)
            signalR.onProgress?.invoke(
                SyncProgress(progress.stage, current, payload.itemTotal, "${progress.stage} $current/${payload.itemTotal}"),
            )
        }
    }

    /* ---------- 发送 ---------- */

    private suspend fun <T> sendJsonChunks(
        items: List<T>,
        overlay: Boolean,
        label: String,
        action: String,
        toJson: (T) -> Any,
    ): Resp<String> {
        val policy = BulkDataImportService.policyForCount(items.size)
        val chunkSize = if (policy.scale == BulkDataScale.normal) items.size else policy.dbBatchSize
        val chunkTotal = if (items.isEmpty()) 1 else ((items.size - 1) / chunkSize) + 1
        Log.d("房间发送$label：count=${items.size} scale=${policy.scale.label}")
        if (items.isEmpty()) {
            signalR.onProgress?.invoke(SyncProgress("发送$label", 0, 0, "发送空列表"))
            return signalR.sendContent(
                roomName = currentRoomId.value,
                action = action,
                overlay = overlay,
                content = "[]",
                extraPayload = mapOf(
                    "chunkIndex" to 1,
                    "chunkTotal" to 1,
                    "itemStart" to 0,
                    "itemEnd" to 0,
                    "itemTotal" to 0,
                ),
            )
        }
        var lastResp: Resp<String>? = null
        var start = 0
        var chunkIndex = 1
        while (start < items.size) {
            val end = (start + chunkSize).coerceAtMost(items.size)
            val chunk = items.subList(start, end)
            val content = JsonArray(chunk.map { toJson(it) as kotlinx.serialization.json.JsonElement }).toString()
            signalR.onProgress?.invoke(
                SyncProgress(
                    "发送$label", end, items.size,
                    "发送第 $chunkIndex/$chunkTotal 段，$end/${items.size}",
                ),
            )
            lastResp = signalR.sendContent(
                roomName = currentRoomId.value,
                action = action,
                overlay = overlay && start == 0,
                content = content,
                extraPayload = mapOf(
                    "chunkIndex" to chunkIndex,
                    "chunkTotal" to chunkTotal,
                    "itemStart" to start,
                    "itemEnd" to end,
                    "itemTotal" to items.size,
                ),
            )
            Log.d("房间发送${label}分段：${start + 1}-$end/${items.size} bytes=${content.length}")
            if (!lastResp.isSuccess) {
                return lastResp
            }
            if (end >= items.size) break
            start = end
            chunkIndex++
        }
        return lastResp ?: Resp(false, "没有可同步的数据", null)
    }

    /* ---------- 各同步操作（覆盖弹窗 overlay 由 UI 层传入） ---------- */

    fun syncFollow(
        overlay: Boolean,
        onToast: (String) -> Unit = { Log.d("Toast: $it") },
    ) {
        if (roomUsers.value.size <= 1) {
            onToast("无设备连接")
            return
        }
        scope.launch {
            try {
                val users = withContext(Dispatchers.IO) { ServiceContainer.dbService.getFollowList() }
                val resp = sendJsonChunks(
                    items = users,
                    label = "关注",
                    action = "SendFavorite",
                    overlay = overlay,
                    toJson = { it.toJsonObject() as Any },
                )
                if (resp.isSuccess) onToast("已发送关注列表")
                else onToast("发送失败:${resp.message}")
            } catch (e: Exception) {
                onToast("发送失败:$e")
                Log.logPrint(e)
            }
        }
    }

    fun syncHistory(
        overlay: Boolean,
        onToast: (String) -> Unit = { Log.d("Toast: $it") },
    ) {
        if (roomUsers.value.size <= 1) {
            onToast("无设备连接")
            return
        }
        scope.launch {
            try {
                val histories = withContext(Dispatchers.IO) { ServiceContainer.dbService.getHistores() }
                val resp = sendJsonChunks(
                    items = histories,
                    label = "历史",
                    action = "SendHistory",
                    overlay = overlay,
                    toJson = { it.toJsonObject() as Any },
                )
                if (resp.isSuccess) onToast("已发送历史记录")
                else onToast("发送失败:${resp.message}")
            } catch (e: Exception) {
                onToast("发送失败:$e")
                Log.logPrint(e)
            }
        }
    }

    fun syncBlockedWord(
        overlay: Boolean,
        onToast: (String) -> Unit = { Log.d("Toast: $it") },
    ) {
        if (roomUsers.value.size <= 1) {
            onToast("无设备连接")
            return
        }
        scope.launch {
            try {
                val shieldList = ServiceContainer.localStorage.getAllShield().values.toList()
                val resp = sendJsonChunks(
                    items = shieldList,
                    label = "屏蔽词",
                    action = "SendShieldWord",
                    overlay = overlay,
                    toJson = { JsonPrimitive(it) as Any },
                )
                if (resp.isSuccess) onToast("已发送屏蔽词")
                else onToast("发送失败:${resp.message}")
            } catch (e: Exception) {
                onToast("发送失败:$e")
                Log.logPrint(e)
            }
        }
    }

    fun syncBiliAccount(
        onToast: (String) -> Unit = { Log.d("Toast: $it") },
    ) {
        if (roomUsers.value.size <= 1) {
            onToast("无设备连接")
            return
        }
        val account = ServiceContainer.biliBiliAccount
        if (!account.logined.value) {
            onToast("未登录哔哩哔哩")
            return
        }
        scope.launch {
            try {
                val resp = signalR.sendContent(
                    roomName = currentRoomId.value,
                    action = "SendBiliAccount",
                    overlay = true,
                    content = buildJsonObject { put("cookie", account.cookie) }.toString(),
                )
                if (resp.isSuccess) onToast("已发送哔哩哔哩账号")
                else onToast("发送失败:${resp.message}")
            } catch (e: Exception) {
                onToast("同步失败:$e")
                Log.logPrint(e)
            }
        }
    }

    /** 房间信息（对应 Dart showQRInfo 的数据部分；二维码由 UI 层渲染）。 */
    fun roomInfoText(): String {
        return "当前服务：${SignalRService.configuredServerOption}\n" +
            "请让另一台设备选择相同服务后扫码\n" +
            "自建服务器与 Cloudflare 的房间不互通"
    }

    /** 关闭（对应 Dart onClose；UI 层在页面销毁时调用）。 */
    fun close() {
        countDownJob?.cancel()
        signalR.dispose()
    }
}