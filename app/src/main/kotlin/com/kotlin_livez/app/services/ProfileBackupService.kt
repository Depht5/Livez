package com.kotlin_livez.app.services

import com.kotlin_livez.app.app.Constant
import com.kotlin_livez.app.app.EventBus
import com.kotlin_livez.app.app.controller.AppSettingsController
import com.kotlin_livez.app.models.FollowUser
import com.kotlin_livez.app.models.FollowUserTag
import com.kotlin_livez.app.models.History
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.time.Instant

/** 宽容读取 JSON 字符串值（镜像 DbModels 内的私有扩展，文件内独立）。 */
private fun JsonElement?.strTrim(): String? = (this as? JsonPrimitive)?.content?.trim()

/**
 * 配置备份服务 —— 对应 Dart `ProfileBackupService`
 * （lib/services/profile_backup_service.dart, 736 行）。
 * 导出/导入完整镜像；文件选择（SAF）与进度对话框由 UI 层负责。
 */
object ProfileBackupService {

    const val schema = "simple_live_profile"
    const val schemaVersion = 3
    private val supportedSchemaVersions = setOf(1, 2, 3)

    private val excludedSettings = setOf(
        LocalStorageService.kFirstRun,
        LocalStorageService.kLastLiveRoom,
        LocalStorageService.kLastLiveRoomResumePending,
        LocalStorageService.kWebDAVUri,
        LocalStorageService.kWebDAVUser,
        LocalStorageService.kWebDAVPassword,
        LocalStorageService.kWebDAVLastUploadTime,
        LocalStorageService.kWebDAVLastRecoverTime,
    )

    /**
     * 本机专属设置：导出时剔除、导入/全量恢复时保留本机现有值。
     * 斗鱼设备号是「一台设备一个身份」，跨设备复制会让多台设备共用同一 did
     * （斗鱼据此判定异常并周期性掐流），因此不随备份/同步迁移。
     */
    val deviceLocalSettings: Set<String> = setOf(
        LocalStorageService.kDouyuDeviceId,
    )

    suspend fun exportProfileMap(
        dbService: DBService,
        localStorage: LocalStorageService,
        appSettings: AppSettingsController,
    ): JsonObject {
        val settingsPayload = exportSettings(localStorage)
        val shieldPayload = exportShieldValues(localStorage, appSettings)
        val followUsers = dbService.getFollowList().map { it.toJsonObject() }
        val followUserTags = dbService.getFollowTagList().map { it.toJsonObject() }
        val histories = dbService.getHistores().map { it.toJsonObject() }
        val accounts = exportAccounts(localStorage)
        return buildJsonObject {
            put("schema", schema)
            put("schemaVersion", schemaVersion)
            put("appVersion", BuildConfigHolder.versionName)
            put("platform", "android")
            put("exportedAt", Instant.now().toString())
            put("settings", settingsPayload)
            put("accounts", accounts)
            put("danmuShield", shieldPayload)
            put("shieldPresets", exportShieldPresets(localStorage))
            put("followUsers", JsonArray(followUsers))
            put("followUserTags", JsonArray(followUserTags))
            put("histories", JsonArray(histories))
            put(
                "summary",
                buildJsonObject {
                    put("settingCount", settingsPayload.size)
                    put("keywordShieldCount", (shieldPayload["keywords"] as? JsonArray)?.size ?: 0)
                    put("userShieldCount", (shieldPayload["users"] as? JsonArray)?.size ?: 0)
                    put("followUserCount", followUsers.size)
                    put("followTagCount", followUserTags.size)
                    put("historyCount", histories.size)
                    put("accountCount", (accounts["items"] as? JsonArray)?.size ?: 0)
                },
            )
        }
    }

    /** 导出为 JSON 字符串（对应 Dart exportProfileJson）。 */
    suspend fun exportProfileJson(
        dbService: DBService,
        localStorage: LocalStorageService,
        appSettings: AppSettingsController,
    ): String {
        val json = Json { prettyPrint = true; prettyPrintIndent = "  " }
        return json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), exportProfileMap(dbService, localStorage, appSettings))
    }

    /** isSupportedProfileMap（对应 Dart isSupportedProfileMap）。 */
    fun isSupportedProfileMap(payload: JsonElement?): Boolean {
        val obj = payload as? JsonObject ?: return false
        val schemaName = obj["schema"]?.strTrim() ?: ""
        val version = (obj["schemaVersion"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 1
        return ((schemaName == schema || schemaName == "simple_live_profile") &&
            version in supportedSchemaVersions) ||
            obj["type"]?.strTrim() == "simple_live" ||
            looksLikeLegacyDataFile(obj)
    }

    private fun looksLikeLegacyDataFile(payload: JsonObject): Boolean {
        if (payload["data"] is JsonArray) return true
        val keys = listOf(
            "followUsers", "follows", "favorites",
            "followUserTags", "tags",
            "histories", "history",
        )
        return keys.any { key ->
            val value = payload[key]
            value is JsonArray || (value is JsonObject && value["data"] is JsonArray)
        }
    }

    /* ---------- 导入入口（对应 Dart importProfileJson 各分支） ---------- */

    /** 导入配置包（对应 Dart importProfileJson）。 */
    suspend fun importProfileJson(
        content: String,
        overwrite: Boolean = false,
        options: ProfileImportOptions = ProfileImportOptions(),
        onProgress: SyncProgressCallback? = null,
    ): ProfileImportSummary {
        onProgress?.onProgress(SyncProgress(stage = "解析配置包"))
        val decoded = Json.parseToJsonElement(content)
        val payload = decoded as? JsonObject
            ?: throw IllegalArgumentException("不是 Simple Live 配置包")
        val schemaName = payload["schema"]?.strTrim() ?: ""
        val version = (payload["schemaVersion"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 1
        return when {
            schemaName == schema || schemaName == "simple_live_profile" -> {
                if (version !in supportedSchemaVersions) {
                    throw IllegalArgumentException("暂不支持该配置包版本")
                }
                importProfileMap(payload, overwrite, options, onProgress)
            }
            payload["type"]?.strTrim() == "simple_live" ->
                importLegacyProfileMap(payload, overwrite, options, onProgress)
            looksLikeLegacyDataFile(payload) ->
                importLegacyDataFileMap(payload, overwrite, options, onProgress)
            else -> throw IllegalArgumentException("不是 Simple Live 配置包")
        }
    }

    /** 新版配置包导入（对应 Dart importProfileMap）。 */
    private suspend fun importProfileMap(
        payload: JsonObject,
        overwrite: Boolean,
        options: ProfileImportOptions,
        onProgress: SyncProgressCallback?,
    ): ProfileImportSummary {
        val summary = ProfileImportSummary()
        val db = com.kotlin_livez.app.di.ServiceContainer.dbService
        val localStorage = com.kotlin_livez.app.di.ServiceContainer.localStorage
        val appSettings = com.kotlin_livez.app.di.ServiceContainer.appSettings
        if (options.settings) {
            onProgress?.onProgress(SyncProgress(stage = "导入设置"))
            importSettings(payload["settings"], summary, overwrite, localStorage)
        }
        if (options.shields) {
            importShields(payload["danmuShield"], summary, overwrite, onProgress, localStorage, appSettings)
        }
        importAccounts(payload["accounts"], localStorage)
        if (options.shieldPresets) {
            onProgress?.onProgress(SyncProgress(stage = "导入屏蔽预设"))
            importShieldPresets(payload["shieldPresets"], summary, overwrite, localStorage)
        }
        if (options.follows) {
            importFollowUsers(
                readPayloadList(payload, listOf("followUsers", "follows", "favorites")),
                summary, overwrite, onProgress, db,
            )
            importFollowTags(
                readPayloadList(payload, listOf("followUserTags", "tags")),
                summary, overwrite, onProgress, db,
            )
        }
        if (options.histories) {
            importHistories(
                readPayloadList(payload, listOf("histories", "history")),
                summary, overwrite, onProgress, db,
            )
        }
        if (options.settings || options.shields || options.shieldPresets) {
            appSettings.reloadFromStorage()
        }
        EventBus.emit(Constant.kUpdateFollow, 0)
        EventBus.emit(Constant.kUpdateHistory, 0)
        return summary
    }

    /** 旧版配置包导入（对应 Dart importLegacyProfileMap）。 */
    private suspend fun importLegacyProfileMap(
        payload: JsonObject,
        overwrite: Boolean,
        options: ProfileImportOptions,
        onProgress: SyncProgressCallback?,
    ): ProfileImportSummary {
        val summary = ProfileImportSummary()
        val localStorage = com.kotlin_livez.app.di.ServiceContainer.localStorage
        val appSettings = com.kotlin_livez.app.di.ServiceContainer.appSettings
        if (options.settings) {
            onProgress?.onProgress(SyncProgress(stage = "导入设置"))
            importSettings(payload["config"], summary, overwrite, localStorage)
        }
        if (options.shields) {
            importShields(
                buildJsonObject { put("raw", JsonArray(legacyShieldValues(payload["shield"]).map { JsonPrimitive(it) })) },
                summary, overwrite, onProgress, localStorage, appSettings,
            )
        }
        if (options.settings || options.shields || options.shieldPresets) {
            appSettings.reloadFromStorage()
        }
        EventBus.emit(Constant.kUpdateFollow, 0)
        EventBus.emit(Constant.kUpdateHistory, 0)
        return summary
    }

    /** 旧版数据文件导入（对应 Dart importLegacyDataFileMap）。 */
    private suspend fun importLegacyDataFileMap(
        payload: JsonObject,
        overwrite: Boolean,
        options: ProfileImportOptions,
        onProgress: SyncProgressCallback?,
    ): ProfileImportSummary {
        val summary = ProfileImportSummary()
        val db = com.kotlin_livez.app.di.ServiceContainer.dbService
        val data = payload["data"]
        if (data is JsonArray) {
            importLegacyDataList(data, summary, overwrite, options, onProgress)
        } else {
            if (options.follows) {
                importFollowUsers(
                    readPayloadList(payload, listOf("followUsers", "follows", "favorites")),
                    summary, overwrite, onProgress, db,
                )
                importFollowTags(
                    readPayloadList(payload, listOf("followUserTags", "tags")),
                    summary, overwrite, onProgress, db,
                )
            }
            if (options.histories) {
                importHistories(
                    readPayloadList(payload, listOf("histories", "history")),
                    summary, overwrite, onProgress, db,
                )
            }
        }
        EventBus.emit(Constant.kUpdateFollow, 0)
        EventBus.emit(Constant.kUpdateHistory, 0)
        return summary
    }

    /* ---------- 导出 ---------- */

    private fun exportSettings(localStorage: LocalStorageService): JsonObject {
        return buildJsonObject {
            // 类型化导出（task 10-25）：保留 Int/Boolean/Float 原始类型，接收端按类型落库；
            // 此前 getAllSettings() 全部 toString 会导致对端导入后读不到值
            for ((key, value) in localStorage.getAllSettingsTyped()) {
                if (key in excludedSettings) continue
                // 本机专属配置不导出（避免泄漏本机斗鱼设备号）
                if (key in deviceLocalSettings) continue
                put(key, settingValueToJson(value))
            }
        }
    }

    /** 类型化设置值 → JSON 元素（与 [jsonSettingValue] 互逆；供导出用）。 */
    internal fun settingValueToJson(value: Any): kotlinx.serialization.json.JsonElement =
        when (value) {
            is Boolean -> kotlinx.serialization.json.JsonPrimitive(value)
            is Int -> kotlinx.serialization.json.JsonPrimitive(value)
            is Long -> kotlinx.serialization.json.JsonPrimitive(value)
            is Float -> kotlinx.serialization.json.JsonPrimitive(value)
            is String -> kotlinx.serialization.json.JsonPrimitive(value)
            else -> kotlinx.serialization.json.JsonPrimitive(value.toString())
        }

    private fun exportAccounts(localStorage: LocalStorageService): JsonObject {
        return buildJsonObject {
            put(
                "items",
                JsonArray(
                    listOf(
                        buildJsonObject {
                            put("siteId", Constant.kBiliBili)
                            put("cookie", localStorage.getValue(LocalStorageService.kBilibiliCookie, ""))
                        },
                        buildJsonObject {
                            put("siteId", Constant.kDouyin)
                            put("cookie", localStorage.getValue(LocalStorageService.kDouyinCookie, ""))
                        },
                    ),
                ),
            )
        }
    }

    private fun exportShieldValues(
        localStorage: LocalStorageService,
        appSettings: AppSettingsController,
    ): JsonObject {
        val raw = localStorage.getAllShield().values
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .sorted()
        val keywords = appSettings.shieldList.value.sorted()
        val userGroups = appSettings.getUserShieldGroupSnapshot()
        val users = userGroups.values.flatten().toSet().sorted()
        return buildJsonObject {
            put("raw", JsonArray(raw.map { JsonPrimitive(it) }))
            put("keywords", JsonArray(keywords.map { JsonPrimitive(it) }))
            put("users", JsonArray(users.map { JsonPrimitive(it) }))
            put(
                "userGroups",
                buildJsonObject {
                    for ((siteId, list) in userGroups) {
                        put(siteId, JsonArray(list.map { JsonPrimitive(it) }))
                    }
                },
            )
        }
    }

    private fun exportShieldPresets(localStorage: LocalStorageService): JsonArray {
        val result = mutableListOf<JsonObject>()
        for ((name, value) in localStorage.getAllShieldPreset()) {
            val parsed = runCatching { Json.parseToJsonElement(value) }.getOrElse { JsonPrimitive(value) }
            result.add(
                buildJsonObject {
                    put("name", name)
                    put("value", safeJsonElement(parsed))
                },
            )
        }
        result.sortBy { it["name"]?.strTrim() ?: "" }
        return JsonArray(result)
    }

    /* ---------- 导入分项 ---------- */

    private fun importSettings(
        rawSettings: JsonElement?,
        summary: ProfileImportSummary,
        overwrite: Boolean,
        localStorage: LocalStorageService,
    ) {
        val obj = rawSettings as? JsonObject ?: return
        // 先缓存本机专属配置（斗鱼设备号）的现值，导入结束后原样保留
        val localDeviceSettings = cacheDeviceLocalSettings(localStorage)
        if (overwrite) {
            clearImportableSettings(localStorage)
        }
        val values = LinkedHashMap<String, Any>()
        for ((key, value) in obj) {
            if (key in excludedSettings) continue
            // 过滤外部传入的本机专属配置，避免覆盖本机设备号
            if (key in deviceLocalSettings) continue
            values[key] = jsonSettingValue(value)
        }
        summary.settings = values.size
        // 覆盖导入走了清理路径：把本机缓存值一并写回（未缓存则保持“无值”，由账号服务重新生成）
        if (overwrite && localDeviceSettings.isNotEmpty()) {
            values.putAll(localDeviceSettings)
        }
        localStorage.putAllSettings(values)
    }

    private fun clearImportableSettings(localStorage: LocalStorageService) {
        val keys = localStorage.getAllSettings().keys
            .filter { it !in excludedSettings && it !in deviceLocalSettings }
        if (keys.isNotEmpty()) {
            localStorage.deleteAllSettings(keys)
        }
    }

    /** 读取本机现有的 [deviceLocalSettings] 值（对应设计「导入前先缓存本机值」）。 */
    fun cacheDeviceLocalSettings(localStorage: LocalStorageService): Map<String, Any> {
        val cached = LinkedHashMap<String, Any>()
        for (key in deviceLocalSettings) {
            val value = localStorage.getValue(key, "")
            if (value.isNotEmpty()) {
                cached[key] = value
            }
        }
        return cached
    }

    private suspend fun importShields(
        rawShield: JsonElement?,
        summary: ProfileImportSummary,
        overwrite: Boolean,
        onProgress: SyncProgressCallback?,
        localStorage: LocalStorageService,
        appSettings: AppSettingsController,
    ) {
        if (overwrite) {
            appSettings.clearShieldList()
            localStorage.clearShield()
        }
        val obj = rawShield as? JsonObject ?: return
        val rawValues = obj["raw"]
        if (rawValues is JsonArray && rawValues.isNotEmpty()) {
            val result = BulkDataImportService.importShieldValues(
                rawValues.mapNotNull { it.strTrim() },
                overwrite = false,
                onProgress = onProgress,
            )
            summary.shields += result.imported
            summary.skipped += result.skipped
            return
        }
        val keywords = obj["keywords"]
        if (keywords is JsonArray) {
            for (keyword in keywords) {
                appSettings.addShieldList(keyword.strTrim() ?: continue)
                summary.shields++
            }
        }
        val groups = obj["userGroups"]
        if (groups is JsonObject) {
            for ((siteId, users) in groups) {
                val userList = users as? JsonArray ?: continue
                for (user in userList) {
                    appSettings.addUserShieldList(user.strTrim() ?: continue, siteId = siteId)
                    summary.shields++
                }
            }
        }
    }

    private suspend fun importShieldPresets(
        rawPresets: JsonElement?,
        summary: ProfileImportSummary,
        overwrite: Boolean,
        localStorage: LocalStorageService,
    ) {
        if (overwrite) {
            localStorage.clearShieldPreset()
        }
        val list = rawPresets as? JsonArray ?: return
        for (item in list) {
            val obj = item as? JsonObject ?: continue
            val name = obj["name"]?.strTrim() ?: continue
            if (name.isEmpty()) continue
            val value = obj["value"]
            val encoded = if (value is JsonPrimitive) value.content else value.toString()
            localStorage.putShieldPreset(name, encoded)
            summary.shieldPresets++
        }
        appSettingsFieldReloadShields()
    }

    private fun appSettingsFieldReloadShields() {
        com.kotlin_livez.app.di.ServiceContainer.appSettings.refreshShieldData()
    }

    private fun importAccounts(rawAccounts: JsonElement?, localStorage: LocalStorageService) {
        val obj = rawAccounts as? JsonObject ?: return
        val items = obj["items"] as? JsonArray ?: return
        for (item in items) {
            val account = item as? JsonObject ?: continue
            val siteId = account["siteId"]?.strTrim() ?: ""
            val cookie = account["cookie"]?.strTrim() ?: ""
            when (siteId) {
                Constant.kBiliBili -> {
                    com.kotlin_livez.app.di.ServiceContainer.biliBiliAccount.setCookie(cookie)
                }
                Constant.kDouyin -> {
                    if (cookie.isEmpty()) {
                        com.kotlin_livez.app.di.ServiceContainer.douyinAccount.clearCookie()
                    } else {
                        com.kotlin_livez.app.di.ServiceContainer.douyinAccount.setCookie(cookie)
                    }
                }
            }
        }
    }

    private suspend fun importFollowUsers(
        rawUsers: JsonElement?,
        summary: ProfileImportSummary,
        overwrite: Boolean,
        onProgress: SyncProgressCallback?,
        dbService: DBService,
    ) {
        val list = rawUsers as? JsonArray ?: return
        val result = BulkDataImportService.importFollowUsers(
            list.toList(),
            overwrite = overwrite,
            onProgress = onProgress,
        )
        summary.followUsers += result.imported
        summary.skipped += result.skipped
    }

    private suspend fun importFollowTags(
        rawTags: JsonElement?,
        summary: ProfileImportSummary,
        overwrite: Boolean,
        onProgress: SyncProgressCallback?,
        dbService: DBService,
    ) {
        val list = rawTags as? JsonArray ?: return
        val result = BulkDataImportService.importFollowTags(
            list.toList(),
            overwrite = overwrite,
            onProgress = onProgress,
        )
        summary.followTags += result.imported
        summary.skipped += result.skipped
    }

    private suspend fun importHistories(
        rawHistories: JsonElement?,
        summary: ProfileImportSummary,
        overwrite: Boolean,
        onProgress: SyncProgressCallback?,
        dbService: DBService,
    ) {
        val list = rawHistories as? JsonArray ?: return
        val result = BulkDataImportService.importHistories(
            list.toList(),
            overwrite = overwrite,
            onProgress = onProgress,
        )
        summary.histories += result.imported
        summary.skipped += result.skipped
    }

    private suspend fun importLegacyDataList(
        rawList: JsonArray,
        summary: ProfileImportSummary,
        overwrite: Boolean,
        options: ProfileImportOptions,
        onProgress: SyncProgressCallback?,
    ) {
        if (rawList.isEmpty()) return
        val db = com.kotlin_livez.app.di.ServiceContainer.dbService
        val firstMap = rawList.firstOrNull { it is JsonObject } as? JsonObject
        if (firstMap != null) {
            if (firstMap.containsKey("userId") || firstMap.containsKey("tag")) {
                if (options.follows) importFollowTags(rawList, summary, overwrite, onProgress, db)
                return
            }
            if (firstMap.containsKey("updateTime")) {
                if (options.histories) importHistories(rawList, summary, overwrite, onProgress, db)
                return
            }
            if (firstMap.containsKey("roomId") || firstMap.containsKey("siteId")) {
                if (options.follows) importFollowUsers(rawList, summary, overwrite, onProgress, db)
                return
            }
        }
        if (options.shields && rawList.all { it is JsonPrimitive }) {
            importShields(
                buildJsonObject { put("raw", rawList) },
                summary, overwrite, onProgress,
                com.kotlin_livez.app.di.ServiceContainer.localStorage,
                com.kotlin_livez.app.di.ServiceContainer.appSettings,
            )
        }
    }

    private fun readPayloadList(payload: JsonObject, keys: List<String>): JsonElement? {
        for (key in keys) {
            val value = payload[key]
            if (value is JsonArray) return value
            if (value is JsonObject && value["data"] is JsonArray) return value["data"]
        }
        return null
    }

    private fun legacyShieldValues(rawShield: JsonElement?): List<String> {
        val obj = rawShield as? JsonObject ?: return emptyList()
        return obj.values
            .mapNotNull { it.strTrim() }
            .filter { it.isNotEmpty() }
            .toSet()
            .sorted()
    }

    /**
     * 设置值类型化转换（task 10-25，对应 Dart _safeJsonValue 的动态类型语义）：
     * JSON 字符串 → String；true/false → Boolean；整数 → Int/Long；小数 → Float。
     * ⚠ DataStore 的键按类型路由（见 LocalStorageService.putAllSettings），转换错类型 = 值读不到。
     * 兼容性：旧版导出的全字符串备份里“数字样式字符串”会被按数字导入（与读取端路由对齐），仅影响旧文件。
     */
    internal fun jsonSettingValue(value: JsonElement?): Any {
        if (value is JsonPrimitive) {
            return when {
                value.isString -> value.content
                value.content == "true" -> true
                value.content == "false" -> false
                else -> value.content.toIntOrNull()
                    ?: value.content.toLongOrNull()
                    ?: value.content.toFloatOrNull()
                    ?: value.content
            }
        }
        // null / 数组 / 对象：设置项不会出现，保底空串（同旧实现）
        return ""
    }

    private fun safeJsonElement(value: JsonElement): JsonElement = value
}

/** 导入选项（对应 Dart ProfileImportOptions）。 */
class ProfileImportOptions(
    val settings: Boolean = true,
    val shields: Boolean = true,
    val shieldPresets: Boolean = true,
    val follows: Boolean = true,
    val histories: Boolean = true,
)

/** 导入结果汇总（对应 Dart ProfileImportSummary）。 */
class ProfileImportSummary {
    var settings = 0
    var shields = 0
    var shieldPresets = 0
    var followUsers = 0
    var followTags = 0
    var histories = 0
    var skipped = 0

    val message: String
        get() {
            val base = "设置 $settings 项，屏蔽 $shields 项，预设 $shieldPresets 个，关注 $followUsers 个，标签 $followTags 个，历史 $histories 条"
            return if (skipped > 0) "$base，跳过异常 $skipped 条" else base
        }
}

/** BuildConfig 包装（避免直接引用 app BuildConfig 造成循环）。 */
private object BuildConfigHolder {
    val versionName: String = com.kotlin_livez.app.BuildConfig.VERSION_NAME
}