package com.kotlin_livez.app.services

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * 本地键值存储服务 —— 对应 Dart `LocalStorageService`（lib/services/local_storage_service.dart）。
 * Dart 侧用 Hive 三个 box（LocalStorage / DanmuShield / DanmuShieldPreset），此处以三个
 * DataStore preferences 文件镜像；全部 key 常量冻结保留。
 *
 * 注意：DataStore 的 key 带类型（int/long/boolean/float/string），getValue 按默认值类型路由，
 * 与 Dart Hive 的动态类型语义对齐。
 */
class LocalStorageService(private val appContext: Context) {

    /* ---------- 冻结 key 常量（与 Dart 完全一致） ---------- */
    companion object {
        const val kFirstRun = "FirstRun"
        const val kPlayerScaleMode = "ScaleMode"
        const val kSiteSort = "SiteSort"
        const val kHomeSort = "HomeSort"
        const val kLiveRoomTabSort = "LiveRoomTabSort"
        const val kLiveRoomQuickAccessSort = "LiveRoomQuickAccessSort"
        const val kLiveRoomQuickAccessEnabled = "LiveRoomQuickAccessEnabled"
        const val kLiveRoomShortcutFullScreen = "LiveRoomShortcutFullScreen"
        const val kLiveRoomShortcutDanmaku = "LiveRoomShortcutDanmaku"
        const val kLiveRoomShortcutMute = "LiveRoomShortcutMute"
        const val kLiveRoomShortcutRefresh = "LiveRoomShortcutRefresh"
        const val kLiveRoomShortcutToggleChat = "LiveRoomShortcutToggleChat"
        const val kLiveRoomShortcutVolumeUp = "LiveRoomShortcutVolumeUp"
        const val kLiveRoomShortcutVolumeDown = "LiveRoomShortcutVolumeDown"
        const val kLastSearchSiteId = "LastSearchSiteId"
        const val kFollowGroupMode = "FollowGroupMode"
        const val kFollowSelectedGroupId = "FollowSelectedGroupId"
        const val kFollowDisplayStyle = "FollowDisplayStyle"
        const val kFollowOnlyLive = "FollowOnlyLive"
        const val kFollowRefreshOnEnter = "FollowRefreshOnEnter"
        const val kFollowShowLiveCover = "FollowShowLiveCover"
        const val kRememberWindowPlacement = "RememberWindowPlacement"
        const val kDesktopWindowBounds = "DesktopWindowBounds"
        const val kDesktopWindowMaximized = "DesktopWindowMaximized"
        const val kMultiRoomGap = "MultiRoomGap"
        const val kMultiRoomCollapseChat = "MultiRoomCollapseChat"
        const val kThemeMode = "ThemeMode"
        const val kAmoledBlack = "AmoledBlack"
        const val kDebugModeKey = "DebugMode"
        const val kDanmuSize = "DanmuSize"
        const val kDanmuSpeed = "DanmuSpeed"
        const val kDanmuArea = "DanmuArea"
        const val kDanmuLineCount = "DanmuLineCount"
        const val kDanmuDelay = "DanmuDelay"
        const val kDanmuBubble = "DanmuBubble"
        const val kDanmuOpacity = "DanmuOpacity"
        const val kDanmuStrokeWidth = "DanmuStrokeWidth"
        const val kDanmuHideScroll = "DanmuHideScroll"
        const val kDanmuHideBottom = "DanmuHideBottom"
        const val kDanmuHideTop = "DanmuHideTop"
        const val kDanmuTopMargin = "DanmuTopMargin"
        const val kDanmuBottomMargin = "DanmuBottomMargin"
        const val kDanmuEnable = "DanmuEnable"
        const val kDanmuRenderEmoji = "DanmuRenderEmoji"
        const val kDanmuShieldEnable = "DanmuShieldEnable"
        const val kDanmuKeywordShieldEnable = "DanmuKeywordShieldEnable"
        const val kDanmuUserShieldEnable = "DanmuUserShieldEnable"
        const val kDanmuFontWeight = "DanmuFontWeight"
        const val kContributionRankEnable = "ContributionRankEnable"
        const val kIosOriginalQualityPowerSaving = "IosOriginalQualityPowerSaving"
        const val kChatTextSize = "ChatTextSize"
        const val kChatTextGap = "ChatTextGap"
        const val kChatBubbleStyle = "ChatBubbleStyle"
        const val kQualityLevel = "QualityLevel"
        const val kQualityLevelCellular = "QualityLevelCellular"
        const val kAutoExitEnable = "AutoExitEnable"
        const val kAutoExitDuration = "AutoExitDuration"
        const val kRoomAutoExitDuration = "RoomAutoExitDuration"
        // 旧版「兼容模式」键：仅用于一次性迁移读取（迁移到 mpv 档位后置 false），不再对应设置项
        const val kPlayerCompatMode = "PlayerCompatMode"
        const val kPlayerAutoPause = "PlayerAutoPause"
        const val kAllowBackgroundPlayback = "AllowBackgroundPlayback"
        const val kPlayerBufferSize = "PlayerBufferSize"
        const val kPlayerForceHttps = "PlayerForceHttps"
        const val kPlayerGestureControlEnable = "PlayerGestureControlEnable"
        const val kAutoSwitchNextOnLiveEnd = "AutoSwitchNextOnLiveEnd"
        const val kAutoSwitchNextOnPlaybackFailure = "AutoSwitchNextOnPlaybackFailure"
        const val kAutoFullScreen = "AutoFullScreen"
        const val kAutoPipOnExit = "AutoPipOnExit"
        const val kPlayerShowSuperChat = "PlayerShowSuperChat"
        const val kLiveEventFlowEnable = "LiveEventFlowEnable"
        const val kLiveEventFlowLimit = "LiveEventFlowLimit"
        const val kLiveEventFlowOverlayEnable = "LiveEventFlowOverlayEnable"
        const val kLiveEventFlowWindowSeconds = "LiveEventFlowWindowSeconds"
        const val kLiveEventFlowDisplaySeconds = "LiveEventFlowDisplaySeconds"
        const val kLiveEventFlowMinCount = "LiveEventFlowMinCount"
        const val kPlayerVolume = "PlayerVolume"
        const val kPIPHideDanmu = "PIPHideDanmu"
        const val kPIPHideDanmuDefaultMigrated = "PIPHideDanmuDefaultMigrated"
        const val kSuperChatSortDesc = "SuperChatSortDesc"
        const val kDanmuDedupeEnable = "DanmuDedupeEnable"
        const val kDanmuDedupeMode = "DanmuDedupeMode"
        const val kDanmuDedupeWindow = "DanmuDedupeWindow"
        const val kDanmuDedupeStep = "DanmuDedupeStep"
        const val kBilibiliCookie = "BilibiliCookie"
        const val kDouyinCookie = "DouyinCookie"
        // 斗鱼登录 Cookie（键名与 Dart LocalStorageService.kDouyuCookie 一致，保证备份/同步兼容）
        const val kDouyuCookie = "DouyuCookie"
        // 斗鱼本机设备号（32 位小写 hex，对应 DouyuSite 的签名 did 与 dy_did/acf_did）。
        // 本机专属：备份/WebDAV 同步导出时剔除，导入时保留本机现有值（防止多设备共用同一 did）。
        const val kDouyuDeviceId = "DouyuDeviceId"
        // 斗鱼扫码登录得到的续期凭据（Passport LTP0 + dy_did）：播放 Cookie 约 7 天过期后
        // 由 safeAuth 自动换取新 Cookie。键名与 Dart 一致（备份/同步兼容）；
        // 属于账号凭据、跟账号走，**不**列入 deviceLocalSettings（见面板/备份过滤逻辑）。
        const val kDouyuLtp0 = "DouyuLtp0"
        const val kDouyuPassportDid = "DouyuPassportDid"
        const val kStyleColor = "kStyleColor"
        const val kIsDynamic = "kIsDynamic"
        const val kBilibiliLoginTip = "BilibiliLoginTip"
        const val kAudioOutputDriver = "AudioOutputDriver"
        const val kMpvProfile = "MpvProfile"
        const val kMpvAdvancedOptions = "MpvAdvancedOptions"
        const val kImportedMpvConfPath = "ImportedMpvConfPath"
        const val kAutoUpdateFollowEnable = "AutoUpdateFollowEnable"
        const val kUpdateFollowDuration = "AutoUpdateFollowDuration"
        const val kUpdateFollowThreadCount = "UpdateFollowThreadCount"
        const val kFollowPageSize = "FollowPageSize"
        const val kFollowRefreshTaskState = "FollowRefreshTaskState"
        const val kFollowRefreshTaskTargets = "FollowRefreshTaskTargets"
        const val kUserRemarks = "UserRemarks"
        const val kLastLiveRoom = "LastLiveRoom"
        const val kLastLiveRoomResumePending = "LastLiveRoomResumePending"
        const val kWebDAVUri = "WebDAVUri"
        const val kWebDAVUser = "WebDAVUser"
        const val kWebDAVPassword = "kWebDAVPassword"
        const val kWebDAVLastUploadTime = "kWebDAVLastUploadTime"
        const val kWebDAVLastRecoverTime = "kWebDAVLastRecoverTime"
        const val kLiveSubtitleEnable = "LiveSubtitleEnable"
        const val kLiveSubtitleModelPath = "LiveSubtitleModelPath"
        const val kLiveSubtitleLanguage = "LiveSubtitleLanguage"
        const val kLiveSubtitleFontSize = "LiveSubtitleFontSize"
        const val kLiveSubtitlePosition = "LiveSubtitlePosition"
        const val kLiveSubtitleOffsetX = "LiveSubtitleOffsetX"
        const val kLiveSubtitleOffsetY = "LiveSubtitleOffsetY"
        const val kLiveSubtitleColor = "LiveSubtitleColor"
        const val kLiveSubtitleFontWeight = "LiveSubtitleFontWeight"
        const val kLiveSubtitleBackgroundEnable = "LiveSubtitleBackgroundEnable"
        const val kLiveSubtitlePositionLocked = "LiveSubtitlePositionLocked"
        const val kLiveSubtitleStartupGuard = "LiveSubtitleStartupGuard"
        // 应用内更新（task 10-21-settings-about-update-check）：渠道、启动自检开关，
        // 以及「本机版本对应的仓库发布日期」缓存（带版本名，升级后自动失效）
        const val kUpdateChannel = "UpdateChannel"
        const val kUpdateAutoCheck = "UpdateAutoCheck"
        const val kUpdateReleaseDate = "UpdateReleaseDate"
        const val kUpdateReleaseDateVersion = "UpdateReleaseDateVersion"
    }

    private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore("LocalStorage")
    private val Context.shieldStore: DataStore<Preferences> by preferencesDataStore("DanmuShield")
    private val Context.shieldPresetStore: DataStore<Preferences> by preferencesDataStore("DanmuShieldPreset")

    /** 对应 Dart getValue<T>（Hive 同步 get）：按默认值类型路由 DataStore key。 */
    @Suppress("UNCHECKED_CAST")
    fun <T> getValue(key: String, defaultValue: T): T {
        return try {
            val prefs = runBlocking { appContext.settingsStore.data.first() }
            when (defaultValue) {
                is Int -> (prefs[intPreferencesKey(key)] ?: defaultValue) as T
                is Long -> (prefs[longPreferencesKey(key)] ?: defaultValue) as T
                is Boolean -> (prefs[booleanPreferencesKey(key)] ?: defaultValue) as T
                is Float -> (prefs[floatPreferencesKey(key)] ?: defaultValue) as T
                is String -> (prefs[stringPreferencesKey(key)] ?: defaultValue) as T
                else -> defaultValue
            }
        } catch (e: Exception) {
            defaultValue
        }
    }

    /** 对应 Dart setValue<T>（Hive put）：按值类型写入（同步等待落盘）。 */
    fun setValue(key: String, value: Any) {
        runBlocking {
            appContext.settingsStore.edit { prefs ->
                when (value) {
                    is Int -> prefs[intPreferencesKey(key)] = value
                    is Long -> prefs[longPreferencesKey(key)] = value
                    is Boolean -> prefs[booleanPreferencesKey(key)] = value
                    is Float -> prefs[floatPreferencesKey(key)] = value
                    is String -> prefs[stringPreferencesKey(key)] = value
                    else -> Unit
                }
            }
        }
    }

    /** 对应 Dart removeValue<T>：尝试移除各类型 key（只会有一种存在）。 */
    fun removeValue(key: String) {
        runBlocking {
            appContext.settingsStore.edit { prefs ->
                prefs.remove(intPreferencesKey(key))
                prefs.remove(longPreferencesKey(key))
                prefs.remove(booleanPreferencesKey(key))
                prefs.remove(floatPreferencesKey(key))
                prefs.remove(stringPreferencesKey(key))
            }
        }
    }

    /* ---------- DanmuShield box（字符串键值，镜像 Dart shieldBox） ---------- */

    fun getShield(key: String, default: String = ""): String =
        runBlocking { appContext.shieldStore.data.first() }[stringPreferencesKey(key)] ?: default

    fun setShield(key: String, value: String) {
        runBlocking { appContext.shieldStore.edit { it[stringPreferencesKey(key)] = value } }
    }

    fun removeShield(key: String) {
        runBlocking { appContext.shieldStore.edit { it.remove(stringPreferencesKey(key)) } }
    }

    /* ---------- DanmuShieldPreset box（字符串键值，镜像 Dart shieldPresetBox） ---------- */

    fun getShieldPreset(key: String, default: String = ""): String =
        runBlocking { appContext.shieldPresetStore.data.first() }[stringPreferencesKey(key)] ?: default

    fun setShieldPreset(key: String, value: String) {
        runBlocking { appContext.shieldPresetStore.edit { it[stringPreferencesKey(key)] = value } }
    }

    fun removeShieldPreset(key: String) {
        runBlocking { appContext.shieldPresetStore.edit { it.remove(stringPreferencesKey(key)) } }
    }

    /** 判断 settings box 是否已存在该 key（对应 Dart settingsBox.containsKey）。 */
    fun containsKey(key: String): Boolean {
        val prefs = runBlocking { appContext.settingsStore.data.first() }
        return prefs.contains(intPreferencesKey(key)) ||
            prefs.contains(longPreferencesKey(key)) ||
            prefs.contains(booleanPreferencesKey(key)) ||
            prefs.contains(floatPreferencesKey(key)) ||
            prefs.contains(stringPreferencesKey(key))
    }

    /** 返回 DanmuShield box 全部键值（对应 Dart shieldBox.toMap()）。 */
    fun getAllShield(): Map<String, String> {
        val prefs = runBlocking { appContext.shieldStore.data.first() }
        return prefs.asMap().entries.associate { (key, value) -> key.name to value.toString() }
    }

    /** 清空 DanmuShield box（对应 Dart shieldBox.clear()）。 */
    fun clearShield() {
        runBlocking { appContext.shieldStore.edit { it.clear() } }
    }

    /** 返回 DanmuShieldPreset box 全部键值（对应 Dart shieldPresetBox.toMap()）。 */
    fun getAllShieldPreset(): Map<String, String> {
        val prefs = runBlocking { appContext.shieldPresetStore.data.first() }
        return prefs.asMap().entries.associate { (key, value) -> key.name to value.toString() }
    }

    /** 清空 DanmuShieldPreset box（对应 Dart shieldPresetBox.clear()）。 */
    fun clearShieldPreset() {
        runBlocking { appContext.shieldPresetStore.edit { it.clear() } }
    }

    /** 清空全部设置（对应 Dart settingsBox.clear()）。 */
    fun clearAllSettings() {
        runBlocking { appContext.settingsStore.edit { it.clear() } }
    }

    /** 返回 settings box 全部键值（对应 Dart settingsBox.toMap()）。 */
    fun getAllSettings(): Map<String, String> {
        val prefs = runBlocking { appContext.settingsStore.data.first() }
        return prefs.asMap().entries.associate { (key, value) -> key.name to value.toString() }
    }

    /** 批量写入 settings（对应 Dart settingsBox.putAll）。 */
    fun putAllSettings(values: Map<String, Any>) {
        runBlocking {
            appContext.settingsStore.edit { prefs ->
                for ((key, value) in values) {
                    prefs[stringPreferencesKey(key)] = value.toString()
                }
            }
        }
    }

    /** 按 key 删除 settings（对应 Dart settingsBox.deleteAll）。 */
    fun deleteAllSettings(keys: List<String>) {
        runBlocking {
            appContext.settingsStore.edit { prefs ->
                for (key in keys) {
                    prefs.remove(stringPreferencesKey(key))
                }
            }
        }
    }

    /** 写入单个屏蔽预设（对应 Dart shieldPresetBox.put）。 */
    fun putShieldPreset(name: String, value: String) {
        runBlocking { appContext.shieldPresetStore.edit { it[stringPreferencesKey(name)] = value } }
    }
}