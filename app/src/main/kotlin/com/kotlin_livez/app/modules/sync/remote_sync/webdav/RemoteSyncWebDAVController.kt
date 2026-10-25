package com.kotlin_livez.app.modules.sync.remote_sync.webdav

import com.kotlin_livez.app.app.Constant
import com.kotlin_livez.app.app.EventBus
import com.kotlin_livez.app.app.Log
import com.kotlin_livez.app.app.controller.AppSettingsController
import com.kotlin_livez.app.app.controller.BaseController
import com.kotlin_livez.app.di.ServiceContainer
import com.kotlin_livez.app.services.BulkDataImportService
import com.kotlin_livez.app.services.LocalStorageService
import com.kotlin_livez.app.services.ProfileBackupService
import com.kotlin_livez.app.services.ProfileImportOptions
import com.kotlin_livez.app.services.SyncProgress
import com.kotlin_livez.app.services.SyncProgressCallback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * WebDAV 远程备份控制器 —— 对应 Dart `remote_sync_webdav_controller.dart`（444 行）。
 * 上传：ProfileBackupService 完整配置包（zip 内含 Profile v3 + 7 个分项 json）；
 * 恢复：下载解压后走 ProfileBackupService.importProfileJson（v3）或分项导入（旧包）。
 * 进度对话框与文件路径由 UI 层负责。
 */
class RemoteSyncWebDAVController : BaseController() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val localStorage: LocalStorageService get() = ServiceContainer.localStorage
    private val appSettings: AppSettingsController get() = ServiceContainer.appSettings

    // UI 状态（对应 Dart .obs）
    val passwordVisible = MutableStateFlow(true)
    val isSyncFollows = MutableStateFlow(true)
    val isSyncHistories = MutableStateFlow(true)
    val isSyncBlockWord = MutableStateFlow(true)
    val isSyncBilibiliAccount = MutableStateFlow(true)
    val isSyncDouyinAccount = MutableStateFlow(true)

    val user = MutableStateFlow("--")
    val lastRecoverTime = MutableStateFlow("--")
    val lastUploadTime = MutableStateFlow("--")

    private var davClient: WebDAVClient? = null

    private val userFollowJsonName = "SimpleLive_follows.json"
    private val userHistoriesJsonName = "SimpleLive_histories.json"
    private val userBlockedWordJsonName = "SimpleLive_blocked_word.json"
    private val userBilibiliAccountJsonName = "SimpleLive_bilibili_account.json"
    private val userDouyinAccountJsonName = "SimpleLive_douyin_account.json"
    private val userSettingsJsonName = "SimpleLive_Settings.json"
    private val userTagsJsonName = "SimpleLive_Tags.json"
    private val profileJsonName = "SimpleLive_Profile_v3.json"
    private val legacyProfileJsonName = "SimpleLive_Profile_v2.json"

    /** 初始化（对应 Dart onInit → doWebDAVInit）。 */
    fun doWebDAVInit() {
        val uri = localStorage.getValue(LocalStorageService.kWebDAVUri, "")
        if (uri.isEmpty()) {
            notLogin.value = true
        } else {
            user.value = localStorage.getValue(LocalStorageService.kWebDAVUser, "")
            val password = localStorage.getValue(LocalStorageService.kWebDAVPassword, "")
            davClient = WebDAVClient(uri, user.value, password)
            loadTimeLabels()
            checkIsLogin()
        }
    }

    private fun loadTimeLabels() {
        lastRecoverTime.value = parseTime(
            localStorage.getValue(LocalStorageService.kWebDAVLastRecoverTime, System.currentTimeMillis()),
        )
        lastUploadTime.value = parseTime(
            localStorage.getValue(LocalStorageService.kWebDAVLastUploadTime, System.currentTimeMillis()),
        )
    }

    /** 检查登录状态（对应 Dart checkIsLogin）。 */
    fun checkIsLogin() {
        val client = davClient ?: return
        scope.launch {
            try {
                val value = client.ping()
                notLogin.value = !value
            } catch (e: Exception) {
                Log.logPrint(e)
                notLogin.value = true
            }
        }
    }

    /** WebDAV 登录（对应 Dart doWebDAVLogin）。 */
    fun doWebDAVLogin(webDAVUri: String, webDAVUser: String, webDAVPassword: String) {
        val client = WebDAVClient(webDAVUri, webDAVUser, webDAVPassword)
        davClient = client
        scope.launch {
            val value = client.ping()
            if (value) {
                localStorage.setValue(LocalStorageService.kWebDAVUri, webDAVUri)
                localStorage.setValue(LocalStorageService.kWebDAVUser, webDAVUser)
                user.value = webDAVUser
                localStorage.setValue(LocalStorageService.kWebDAVPassword, webDAVPassword)
                notLogin.value = false
                Log.d("Toast: 登录成功！")
            } else {
                notLogin.value = true
                Log.d("Toast: WebDAV账号密码验证失败，请重新输入！")
            }
        }
    }

    /** WebDAV 登出（对应 Dart onLogout 的数据清理部分；确认对话框由 UI 层负责）。 */
    fun doWebDAVLogout() {
        localStorage.setValue(LocalStorageService.kWebDAVUri, "")
        localStorage.setValue(LocalStorageService.kWebDAVUser, "")
        localStorage.setValue(LocalStorageService.kWebDAVPassword, "")
        notLogin.value = true
    }

    /** 上传到云端（对应 Dart doWebDAVUpload）。 */
    fun doWebDAVUpload(
        onProgress: ((SyncProgress) -> Unit)? = null,
        onComplete: ((Boolean, String) -> Unit)? = null,
    ) {
        val client = davClient ?: return
        scope.launch {
            onProgress?.invoke(SyncProgress(stage = "正在打包备份"))
            try {
                val value = withContext(Dispatchers.IO) { backupData() }
                if (value.isNotEmpty()) {
                    onProgress?.invoke(SyncProgress(stage = "正在上传到云端"))
                    val backupOk = uploadBackup(client, value)
                    if (backupOk) {
                        val uploadTime = System.currentTimeMillis()
                        lastUploadTime.value = parseTime(uploadTime)
                        localStorage.setValue(LocalStorageService.kWebDAVLastUploadTime, uploadTime)
                        onComplete?.invoke(true, "上传成功")
                    } else {
                        Log.d("Toast: 上传失败")
                        onComplete?.invoke(false, "上传失败")
                    }
                } else {
                    Log.d("Toast: 上传失败")
                    onComplete?.invoke(false, "上传失败")
                }
            } catch (e: Exception) {
                Log.logPrint(e)
                onComplete?.invoke(false, "上传失败：${e.message}")
            }
        }
    }

    private suspend fun uploadBackup(client: WebDAVClient, data: ByteArray): Boolean {
        client.mkdir(client.root)
        return client.write(client.backupFile, data)
    }

    /** 打包备份数据（对应 Dart _backupData）。 */
    private suspend fun backupData(): ByteArray = withContext(Dispatchers.IO) {
        val db = com.kotlin_livez.app.di.ServiceContainer.dbService
        val profileMap = ProfileBackupService.exportProfileMap(db, localStorage, appSettings)
        val profileJson = ProfileBackupService.exportProfileJson(db, localStorage, appSettings)
        val files = LinkedHashMap<String, ByteArray>()
        files[profileJsonName] = profileJson.toByteArray()
        files[userFollowJsonName] = jsonDataBytes(buildJsonObject { put("data", profileMap["followUsers"] ?: JsonArray(emptyList())) })
        files[userTagsJsonName] = jsonDataBytes(buildJsonObject { put("data", profileMap["followUserTags"] ?: JsonArray(emptyList())) })
        files[userHistoriesJsonName] = jsonDataBytes(buildJsonObject { put("data", profileMap["histories"] ?: JsonArray(emptyList())) })
        files[userBlockedWordJsonName] = jsonDataBytes(
            buildJsonObject {
                put("data", JsonArray(localStorage.getAllShield().values.toList().map { JsonPrimitive(it) }))
            },
        )
        files[userBilibiliAccountJsonName] = jsonDataBytes(
            buildJsonObject {
                put("data", buildJsonObject { put("cookie", ServiceContainer.biliBiliAccount.cookie) })
            },
        )
        files[userDouyinAccountJsonName] = jsonDataBytes(
            buildJsonObject {
                put("data", buildJsonObject { put("cookie", ServiceContainer.douyinAccount.cookie) })
            },
        )
        files[userSettingsJsonName] = jsonDataBytes(
            buildJsonObject {
                put("data", buildJsonObject {
                    // 类型化导出（同 ProfileBackupService.exportSettings，保留原生类型）
                    for ((key, value) in localStorage.getAllSettingsTyped()) {
                        // 本机专属配置（斗鱼设备号）不随备份上传，避免多设备共用同一 did
                        if (key in ProfileBackupService.deviceLocalSettings) continue
                        put(key, ProfileBackupService.settingValueToJson(value))
                    }
                })
            },
        )
        zipBytes(files)
    }

    private fun jsonDataBytes(obj: JsonObject): ByteArray =
        Json.encodeToString(JsonObject.serializer(), obj).toByteArray()

    private fun zipBytes(files: Map<String, ByteArray>): ByteArray {
        val baos = ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(baos).use { zos ->
            for ((name, data) in files) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(data)
                zos.closeEntry()
            }
        }
        return baos.toByteArray()
    }

    /** 恢复到本地（对应 Dart doWebDAVRecovery）。 */
    fun doWebDAVRecovery(
        onProgress: ((SyncProgress) -> Unit)? = null,
        onComplete: ((Boolean, String) -> Unit)? = null,
    ) {
        val client = davClient ?: return
        scope.launch {
            onProgress?.invoke(SyncProgress(stage = "正在下载备份"))
            try {
                val data = withContext(Dispatchers.IO) { client.read(client.backupFile) }
                if (data.isEmpty()) {
                    throw IllegalStateException("WebDAV 备份文件下载失败")
                }
                onProgress?.invoke(SyncProgress(stage = "正在解压备份"))
                val entries = decodeZipEntries(data)
                val profileFile = entries.entries.firstOrNull { (name, _) ->
                    name == profileJsonName || name == legacyProfileJsonName
                }
                if (profileFile != null) {
                    try {
                        val summary = withContext(Dispatchers.IO) {
                            ProfileBackupService.importProfileJson(
                                profileFile.value.toString(Charsets.UTF_8),
                                overwrite = true,
                                options = ProfileImportOptions(
                                    settings = true,
                                    follows = isSyncFollows.value,
                                    histories = isSyncHistories.value,
                                    shields = isSyncBlockWord.value,
                                    shieldPresets = isSyncBlockWord.value,
                                ),
                                onProgress = onProgress?.let { cb -> SyncProgressCallback { cb(it) } },
                            )
                        }
                        Log.d("已同步完整配置包：${summary.message}")
                    } catch (e: Exception) {
                        Log.logPrint(e)
                        throw e
                    }
                    for ((name, content) in entries.entries) {
                        if (name == userBilibiliAccountJsonName || name == userDouyinAccountJsonName) {
                            recoveryFile(name, content, onProgress)
                        }
                    }
                } else {
                    for ((name, content) in entries.entries) {
                        recoveryFile(name, content, onProgress)
                    }
                }
                val recoverTime = System.currentTimeMillis()
                lastRecoverTime.value = parseTime(recoverTime)
                localStorage.setValue(LocalStorageService.kWebDAVLastRecoverTime, recoverTime)
                onComplete?.invoke(true, "同步完成")
            } catch (e: Exception) {
                Log.logPrint(e)
                onComplete?.invoke(false, "恢复失败：${e.message}")
            }
        }
    }

    /** 解压 zip（对应 Dart _decodeWebDavBackupArchive → archive 遍历）。 */
    private fun decodeZipEntries(data: ByteArray): Map<String, ByteArray> {
        val result = LinkedHashMap<String, ByteArray>()
        ZipInputStream(data.inputStream()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val bytes = zis.readBytes()
                    result[entry.name] = bytes
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        return result
    }

    /** 分项恢复（对应 Dart _recovery）。 */
    private suspend fun recoveryFile(
        name: String,
        content: ByteArray,
        onProgress: ((SyncProgress) -> Unit)?,
    ) {
        if (!name.endsWith(".json")) return
        val jsonString = content.toString(Charsets.UTF_8)
        val parsed = Json.parseToJsonElement(jsonString) as? JsonObject ?: return
        val jsonData = parsed["data"]
        val progress = onProgress?.let { cb -> SyncProgressCallback { cb(it) } }
        when {
            name == userFollowJsonName && isSyncFollows.value -> {
                try {
                    val list = (jsonData as? JsonArray)?.toList() ?: emptyList()
                    val result = BulkDataImportService.importFollowUsers(list, overwrite = true, onProgress = progress)
                    EventBus.emit(Constant.kUpdateFollow, 0)
                    Log.d("已同步关注用户列表：${result.logSummary}")
                } catch (e: Exception) {
                    Log.logPrint(e)
                }
            }
            name == userHistoriesJsonName && isSyncHistories.value -> {
                try {
                    val list = (jsonData as? JsonArray)?.toList() ?: emptyList()
                    val result = BulkDataImportService.importHistories(list, onProgress = progress)
                    EventBus.emit(Constant.kUpdateHistory, 0)
                    Log.d("已同步用户观看历史记录：${result.logSummary}")
                } catch (e: Exception) {
                    Log.logPrint(e)
                }
            }
            name == userBlockedWordJsonName && isSyncBlockWord.value -> {
                try {
                    val list = (jsonData as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content } ?: emptyList()
                    val result = BulkDataImportService.importShieldValues(list, overwrite = false, onProgress = progress)
                    Log.d("已同步用户屏蔽词：${result.logSummary}")
                } catch (e: Exception) {
                    Log.logPrint(e)
                }
            }
            name == userBilibiliAccountJsonName && isSyncBilibiliAccount.value -> {
                try {
                    val cookie = (jsonData as? JsonObject)?.get("cookie")
                        ?.let { (it as? JsonPrimitive)?.content } ?: ""
                    ServiceContainer.biliBiliAccount.setCookie(cookie)
                    Log.d("已同步哔哩哔哩账号")
                } catch (e: Exception) {
                    Log.logPrint(e)
                }
            }
            name == userDouyinAccountJsonName && isSyncDouyinAccount.value -> {
                try {
                    val cookie = (jsonData as? JsonObject)?.get("cookie")
                        ?.let { (it as? JsonPrimitive)?.content } ?: ""
                    if (cookie.isEmpty()) {
                        ServiceContainer.douyinAccount.clearCookie()
                    } else {
                        ServiceContainer.douyinAccount.setCookie(cookie)
                    }
                    Log.d("已同步抖音账号")
                } catch (e: Exception) {
                    Log.logPrint(e)
                }
            }
            name == userSettingsJsonName -> {
                try {
                    val obj = jsonData as? JsonObject ?: return
                    // 全量恢复会 clearAllSettings()：先缓存本机专属配置（斗鱼设备号）的现值
                    val localDeviceSettings = ProfileBackupService.cacheDeviceLocalSettings(localStorage)
                    localStorage.clearAllSettings()
                    val values = LinkedHashMap<String, Any>()
                    for ((key, value) in obj) {
                        // 过滤云端传入的本机专属配置，避免覆盖本机设备号
                        if (key in ProfileBackupService.deviceLocalSettings) continue
                        if (value is JsonPrimitive) {
                            values[key] = value.content
                        }
                    }
                    // 把本机缓存值写回（未缓存则保持“无值”，由账号服务重新生成）
                    values.putAll(localDeviceSettings)
                    localStorage.putAllSettings(values)
                    appSettings.reloadFromStorage()
                    Log.d("已同步用户设置")
                } catch (e: Exception) {
                    Log.logPrint(e)
                }
            }
            name == userTagsJsonName && isSyncFollows.value -> {
                try {
                    val list = (jsonData as? JsonArray)?.toList() ?: emptyList()
                    val result = BulkDataImportService.importFollowTags(list, overwrite = true, onProgress = progress)
                    EventBus.emit(Constant.kUpdateFollow, 0)
                    Log.d("已同步用户自定义标签：${result.logSummary}")
                } catch (e: Exception) {
                    Log.logPrint(e)
                }
            }
        }
    }

    // UI 控制
    fun changePasswordVisible() {
        passwordVisible.value = !passwordVisible.value
    }

    fun changeIsSyncFollows() {
        isSyncFollows.value = !isSyncFollows.value
    }

    fun changeIsSyncHistories() {
        isSyncHistories.value = !isSyncHistories.value
    }

    fun changeIsSyncBlockWord() {
        isSyncBlockWord.value = !isSyncBlockWord.value
    }

    fun changeIsSyncBilibiliAccount() {
        isSyncBilibiliAccount.value = !isSyncBilibiliAccount.value
    }

    fun changeIsSyncDouyinAccount() {
        isSyncDouyinAccount.value = !isSyncDouyinAccount.value
    }
}

/** 时间格式化（对应 Dart Utils.parseTime）。 */
private fun parseTime(epochMillis: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(epochMillis))