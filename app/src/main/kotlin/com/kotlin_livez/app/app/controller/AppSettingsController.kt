package com.kotlin_livez.app.app.controller

import android.graphics.Paint
import com.kotlin_livez.app.app.ALL_HOME_PAGES
import com.kotlin_livez.app.app.ALL_LIVE_ROOM_QUICK_ACCESS
import com.kotlin_livez.app.app.ALL_LIVE_ROOM_TABS
import com.kotlin_livez.app.app.Constant
import com.kotlin_livez.app.app.Log
import com.kotlin_livez.app.app.Sites
import com.kotlin_livez.app.models.DanmuShieldPreset
import com.kotlin_livez.app.services.LocalStorageService
import com.kotlin_livez.app.services.MpvOptionsService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.math.roundToInt

/**
 * 应用设置控制器 —— 对应 Dart `AppSettingsController`
 * （lib/app/controller/app_settings_controller.dart, 2558 行）。
 * 状态以 StateFlow 镜像 `.obs`；存储读写走同步 [LocalStorageService]；
 * 桌面端专属（窗口位置/快捷键/主题弹窗/BackgroundPlayback）按平台差异裁剪并在注释说明。
 */
class AppSettingsController(
    private val localStorage: LocalStorageService,
) {

    companion object {
        private const val KEYWORD_SHIELD_PREFIX = "keyword:"
        private const val USER_SHIELD_PREFIX = "user:"
        const val kUpdateChannelGitee = "gitee"
        const val kUpdateChannelGithub = "github"
        const val kGlobalUserShieldSiteId = "__all__"
        const val kDanmuDedupeModeUser = 0
        const val kDanmuDedupeModeStrict = 1
        const val kDanmuDedupeDefaultWindow = 10
        const val kDanmuDedupeStrictMinWindow = 5
        const val kDanmuDedupeMaxWindow = 100
        const val kDanmuDedupeStrictWarnWindow = 20
        const val kLiveEventFlowDefaultLimit = 100
        const val kLiveEventFlowMinLimit = 100
        const val kLiveEventFlowMaxLimit = 500
        const val kLiveEventFlowDefaultWindowSeconds = 30
        const val kLiveEventFlowMinWindowSeconds = 5
        const val kLiveEventFlowMaxWindowSeconds = 120
        const val kLiveEventFlowDefaultDisplaySeconds = 10
        const val kLiveEventFlowMinDisplaySeconds = 3
        const val kLiveEventFlowMaxDisplaySeconds = 60
        const val kLiveEventFlowDefaultMinCount = 5
        const val kLiveEventFlowMinCount = 2
        const val kLiveEventFlowMaxCount = 100
        const val kMultiRoomDefaultGap = 2
        const val kMultiRoomMinGap = 0
        const val kMultiRoomMaxGap = 24
        const val kShortcutDisabled = 0
        const val kFollowPageSizeDefault = 200
        const val kFollowPageSizeMin = 2

        val followDisplayStyleOptions = listOf("default", "compact", "card")
    }

    /* ---------- 基本设置 ---------- */

    private val _scaleMode = MutableStateFlow(0)
    val scaleMode: StateFlow<Int> = _scaleMode

    private val _themeMode = MutableStateFlow(0)
    val themeMode: StateFlow<Int> = _themeMode

    var firstRun: Boolean = false

    /* ---------- 弹幕设置 ---------- */

    private val _danmuSize = MutableStateFlow(16.0F)
    val danmuSize: StateFlow<Float> = _danmuSize

    private val _danmuOpacity = MutableStateFlow(1.0F)
    val danmuOpacity: StateFlow<Float> = _danmuOpacity

    private val _danmuArea = MutableStateFlow(0.8F)
    val danmuArea: StateFlow<Float> = _danmuArea

    private val _danmuLineCount = MutableStateFlow(8)
    val danmuLineCount: StateFlow<Int> = _danmuLineCount

    private val _danmuSpeed = MutableStateFlow(10.0F)
    val danmuSpeed: StateFlow<Float> = _danmuSpeed

    private val _danmuEnable = MutableStateFlow(true)
    val danmuEnable: StateFlow<Boolean> = _danmuEnable

    private val _danmuRenderEmoji = MutableStateFlow(true)
    val danmuRenderEmoji: StateFlow<Boolean> = _danmuRenderEmoji

    /** 弹幕气泡（task 08-29-chat-pin-bubble-follow-grid）：聊天框弹幕加主题色圆角背景。 */
    private val _danmuBubble = MutableStateFlow(false)
    val danmuBubble: StateFlow<Boolean> = _danmuBubble

    private val _danmuShieldEnable = MutableStateFlow(true)
    val danmuShieldEnable: StateFlow<Boolean> = _danmuShieldEnable

    private val _danmuKeywordShieldEnable = MutableStateFlow(true)
    val danmuKeywordShieldEnable: StateFlow<Boolean> = _danmuKeywordShieldEnable

    private val _danmuUserShieldEnable = MutableStateFlow(true)
    val danmuUserShieldEnable: StateFlow<Boolean> = _danmuUserShieldEnable

    private val _danmuStrokeWidth = MutableStateFlow(2.0F)
    val danmuStrokeWidth: StateFlow<Float> = _danmuStrokeWidth

    private val _danmuTopMargin = MutableStateFlow(0.0F)
    val danmuTopMargin: StateFlow<Float> = _danmuTopMargin

    private val _danmuBottomMargin = MutableStateFlow(0.0F)
    val danmuBottomMargin: StateFlow<Float> = _danmuBottomMargin

    private val _danmuFontWeight = MutableStateFlow(4)
    val danmuFontWeight: StateFlow<Int> = _danmuFontWeight

    private val _danmuDelayMs = MutableStateFlow(0)
    val danmuDelayMs: StateFlow<Int> = _danmuDelayMs

    private val _danmuDelayBySite = MutableStateFlow<Map<String, Int>>(emptyMap())
    val danmuDelayBySite: StateFlow<Map<String, Int>> = _danmuDelayBySite

    /* ---------- 直播/播放设置 ---------- */

    private val _contributionRankEnable = MutableStateFlow(true)
    val contributionRankEnable: StateFlow<Boolean> = _contributionRankEnable

    private val _chatTextSize = MutableStateFlow(14.0F)
    val chatTextSize: StateFlow<Float> = _chatTextSize

    private val _chatTextGap = MutableStateFlow(4.0F)
    val chatTextGap: StateFlow<Float> = _chatTextGap

    private val _chatBubbleStyle = MutableStateFlow(false)
    val chatBubbleStyle: StateFlow<Boolean> = _chatBubbleStyle

    private val _qualityLevel = MutableStateFlow(1)
    val qualityLevel: StateFlow<Int> = _qualityLevel

    private val _qualityLevelCellular = MutableStateFlow(1)
    val qualityLevelCellular: StateFlow<Int> = _qualityLevelCellular

    private val _autoExitEnable = MutableStateFlow(false)
    val autoExitEnable: StateFlow<Boolean> = _autoExitEnable

    private val _autoExitDuration = MutableStateFlow(60)
    val autoExitDuration: StateFlow<Int> = _autoExitDuration

    private val _roomAutoExitDuration = MutableStateFlow(60)
    val roomAutoExitDuration: StateFlow<Int> = _roomAutoExitDuration

    private val _playerBufferSize = MutableStateFlow(32)
    val playerBufferSize: StateFlow<Int> = _playerBufferSize

    private val _playerAutoPause = MutableStateFlow(false)
    val playerAutoPause: StateFlow<Boolean> = _playerAutoPause

    private val _allowBackgroundPlayback = MutableStateFlow(true)
    val allowBackgroundPlayback: StateFlow<Boolean> = _allowBackgroundPlayback

    private val _autoFullScreen = MutableStateFlow(false)
    val autoFullScreen: StateFlow<Boolean> = _autoFullScreen

    private val _autoSwitchNextOnLiveEnd = MutableStateFlow(false)
    val autoSwitchNextOnLiveEnd: StateFlow<Boolean> = _autoSwitchNextOnLiveEnd

    private val _autoSwitchNextOnPlaybackFailure = MutableStateFlow(false)
    val autoSwitchNextOnPlaybackFailure: StateFlow<Boolean> = _autoSwitchNextOnPlaybackFailure

    private val _autoPipOnExit = MutableStateFlow(false)
    val autoPipOnExit: StateFlow<Boolean> = _autoPipOnExit

    private val _playershowSuperChat = MutableStateFlow(false)
    val playershowSuperChat: StateFlow<Boolean> = _playershowSuperChat

    private val _playerForceHttps = MutableStateFlow(false)
    val playerForceHttps: StateFlow<Boolean> = _playerForceHttps

    private val _playerVolume = MutableStateFlow(100.0F)
    val playerVolume: StateFlow<Float> = _playerVolume

    private val _playerGestureControlEnable = MutableStateFlow(true)
    val playerGestureControlEnable: StateFlow<Boolean> = _playerGestureControlEnable

    private val _pipHideDanmu = MutableStateFlow(true)
    val pipHideDanmu: StateFlow<Boolean> = _pipHideDanmu

    /* ---------- 事件流 / 弹幕去重 ---------- */

    private val _superChatSortDesc = MutableStateFlow(false)
    val superChatSortDesc: StateFlow<Boolean> = _superChatSortDesc

    private val _liveEventFlowEnable = MutableStateFlow(false)
    val liveEventFlowEnable: StateFlow<Boolean> = _liveEventFlowEnable

    private val _liveEventFlowLimit = MutableStateFlow(kLiveEventFlowDefaultLimit)
    val liveEventFlowLimit: StateFlow<Int> = _liveEventFlowLimit

    private val _liveEventFlowOverlayEnable = MutableStateFlow(true)
    val liveEventFlowOverlayEnable: StateFlow<Boolean> = _liveEventFlowOverlayEnable

    private val _liveEventFlowWindowSeconds = MutableStateFlow(kLiveEventFlowDefaultWindowSeconds)
    val liveEventFlowWindowSeconds: StateFlow<Int> = _liveEventFlowWindowSeconds
    val effectiveLiveEventFlowWindowSeconds: Int
        get() = normalizeLiveEventFlowWindowSeconds(_liveEventFlowWindowSeconds.value)

    private val _liveEventFlowDisplaySeconds =
        MutableStateFlow(kLiveEventFlowDefaultDisplaySeconds)
    val liveEventFlowDisplaySeconds: StateFlow<Int> = _liveEventFlowDisplaySeconds
    val effectiveLiveEventFlowDisplaySeconds: Int
        get() = normalizeLiveEventFlowDisplaySeconds(_liveEventFlowDisplaySeconds.value)

    private val _liveEventFlowMinCount = MutableStateFlow(kLiveEventFlowDefaultMinCount)
    val liveEventFlowMinCount: StateFlow<Int> = _liveEventFlowMinCount
    val effectiveLiveEventFlowMinCount: Int
        get() = normalizeLiveEventFlowMinCount(_liveEventFlowMinCount.value)

    private val _danmuDedupeEnable = MutableStateFlow(false)
    val danmuDedupeEnable: StateFlow<Boolean> = _danmuDedupeEnable

    private val _danmuDedupeMode = MutableStateFlow(kDanmuDedupeModeUser)
    val danmuDedupeMode: StateFlow<Int> = _danmuDedupeMode
    val danmuDedupeStrictMode: Boolean get() = _danmuDedupeMode.value == kDanmuDedupeModeStrict
    val danmuDedupeWindowMin: Int get() = if (danmuDedupeStrictMode) kDanmuDedupeStrictMinWindow else 1
    val effectiveDanmuDedupeWindow: Int
        get() = normalizeDanmuDedupeWindow(_danmuDedupeWindow.value)

    private val _danmuDedupeWindow = MutableStateFlow(kDanmuDedupeDefaultWindow)
    val danmuDedupeWindow: StateFlow<Int> = _danmuDedupeWindow

    private val _danmuDedupeStep = MutableStateFlow(2)
    val danmuDedupeStep: StateFlow<Int> = _danmuDedupeStep

    /* ---------- 屏蔽词 / 预设 ---------- */

    private val _shieldList = MutableStateFlow<Set<String>>(emptySet())
    val shieldList: StateFlow<Set<String>> = _shieldList

    private val _userShieldList = MutableStateFlow<Set<String>>(emptySet())
    val userShieldList: StateFlow<Set<String>> = _userShieldList

    private val _userShieldGroups =
        MutableStateFlow<Map<String, List<String>>>(emptyMap())
    val userShieldGroups: StateFlow<Map<String, List<String>>> = _userShieldGroups

    private val _shieldPresetList = MutableStateFlow<List<DanmuShieldPreset>>(emptyList())
    val shieldPresetList: StateFlow<List<DanmuShieldPreset>> = _shieldPresetList

    private val _userRemarks = MutableStateFlow<Map<String, String>>(emptyMap())
    val userRemarks: StateFlow<Map<String, String>> = _userRemarks

    val allShieldValues: Collection<String> get() = localStorage.getAllShield().values

    /* ---------- 其他设置 ---------- */

    private val _styleColor = MutableStateFlow(0xff3498db.toInt())
    val styleColor: StateFlow<Int> = _styleColor

    private val _isDynamic = MutableStateFlow(false)
    val isDynamic: StateFlow<Boolean> = _isDynamic

    /** 纯黑主题（OLED，task 08-29-dark-mode）：仅深色实际生效时覆盖页面级背景为纯黑。 */
    private val _amoledBlack = MutableStateFlow(false)
    val amoledBlack: StateFlow<Boolean> = _amoledBlack

    private val _bilibiliLoginTip = MutableStateFlow(true)
    val bilibiliLoginTip: StateFlow<Boolean> = _bilibiliLoginTip

    private val _liveSubtitleEnable = MutableStateFlow(false)
    val liveSubtitleEnable: StateFlow<Boolean> = _liveSubtitleEnable

    private val _liveSubtitleModelPath = MutableStateFlow("")
    val liveSubtitleModelPath: StateFlow<String> = _liveSubtitleModelPath

    private val _liveSubtitleLanguage = MutableStateFlow("auto")
    val liveSubtitleLanguage: StateFlow<String> = _liveSubtitleLanguage

    private val _liveSubtitleFontSize = MutableStateFlow(18.0F)
    val liveSubtitleFontSize: StateFlow<Float> = _liveSubtitleFontSize

    private val _liveSubtitlePosition = MutableStateFlow(1)
    val liveSubtitlePosition: StateFlow<Int> = _liveSubtitlePosition

    private val _liveSubtitleOffsetX = MutableStateFlow(0.5F)
    val liveSubtitleOffsetX: StateFlow<Float> = _liveSubtitleOffsetX

    private val _liveSubtitleOffsetY = MutableStateFlow(0.82F)
    val liveSubtitleOffsetY: StateFlow<Float> = _liveSubtitleOffsetY

    private val _liveSubtitleColor = MutableStateFlow(0xffffffff.toInt())
    val liveSubtitleColor: StateFlow<Int> = _liveSubtitleColor

    private val _liveSubtitleFontWeight = MutableStateFlow(6)
    val liveSubtitleFontWeight: StateFlow<Int> = _liveSubtitleFontWeight

    private val _liveSubtitleBackgroundEnable = MutableStateFlow(true)
    val liveSubtitleBackgroundEnable: StateFlow<Boolean> = _liveSubtitleBackgroundEnable

    private val _liveSubtitlePositionLocked = MutableStateFlow(false)
    val liveSubtitlePositionLocked: StateFlow<Boolean> = _liveSubtitlePositionLocked

    private val _audioOutputDriver = MutableStateFlow("audiotrack")
    val audioOutputDriver: StateFlow<String> = _audioOutputDriver

    private val _mpvProfile = MutableStateFlow("balanced")
    val mpvProfile: StateFlow<String> = _mpvProfile

    private val _mpvAdvancedOptions = MutableStateFlow("")
    val mpvAdvancedOptions: StateFlow<String> = _mpvAdvancedOptions

    private val _importedMpvConfPath = MutableStateFlow("")
    val importedMpvConfPath: StateFlow<String> = _importedMpvConfPath

    /* ---------- 关注刷新 ---------- */

    private val _autoUpdateFollowEnable = MutableStateFlow(true)
    val autoUpdateFollowEnable: StateFlow<Boolean> = _autoUpdateFollowEnable

    private val _autoUpdateFollowDuration = MutableStateFlow(10)
    val autoUpdateFollowDuration: StateFlow<Int> = _autoUpdateFollowDuration

    private val _updateFollowThreadCount = MutableStateFlow(0)
    val updateFollowThreadCount: StateFlow<Int> = _updateFollowThreadCount
    val effectiveUpdateFollowThreadCount: Int
        get() = normalizeUpdateFollowThreadCount(_updateFollowThreadCount.value)

    private val _followPageSize = MutableStateFlow(kFollowPageSizeDefault)
    val followPageSize: StateFlow<Int> = _followPageSize

    private val _lastSearchSiteId = MutableStateFlow(Constant.kBiliBili)
    val lastSearchSiteId: StateFlow<String> = _lastSearchSiteId

    private val _followGroupMode = MutableStateFlow("liveStatus")
    val followGroupMode: StateFlow<String> = _followGroupMode

    private val _followSelectedGroupId = MutableStateFlow("all")
    val followSelectedGroupId: StateFlow<String> = _followSelectedGroupId

    private val _followDisplayStyle = MutableStateFlow("default")
    val followDisplayStyle: StateFlow<String> = _followDisplayStyle

    private val _followOnlyLive = MutableStateFlow(false)
    val followOnlyLive: StateFlow<Boolean> = _followOnlyLive

    private val _followRefreshOnEnter = MutableStateFlow(false)
    val followRefreshOnEnter: StateFlow<Boolean> = _followRefreshOnEnter

    private val _followShowLiveCover = MutableStateFlow(false)
    val followShowLiveCover: StateFlow<Boolean> = _followShowLiveCover

    /* ---------- 应用内更新（task 10-21-settings-about-update-check） ---------- */

    /** 更新渠道：`gitee`（默认）/ `github`；存储里出现未知值一律回落 gitee。 */
    private val _updateChannel = MutableStateFlow(kUpdateChannelGitee)
    val updateChannel: StateFlow<String> = _updateChannel

    /** 启动时自动检查一次更新（默认开启）。 */
    private val _autoCheckUpdate = MutableStateFlow(true)
    val autoCheckUpdate: StateFlow<Boolean> = _autoCheckUpdate

    /* ---------- 排序 / 多窗口（桌面部分裁剪） ---------- */

    private val _siteSort = MutableStateFlow<List<String>>(emptyList())
    val siteSort: StateFlow<List<String>> = _siteSort

    private val _homeSort = MutableStateFlow<List<String>>(emptyList())
    val homeSort: StateFlow<List<String>> = _homeSort

    private val _liveRoomTabSort = MutableStateFlow<List<String>>(emptyList())
    val liveRoomTabSort: StateFlow<List<String>> = _liveRoomTabSort

    private val _liveRoomQuickAccessSort = MutableStateFlow<List<String>>(emptyList())
    val liveRoomQuickAccessSort: StateFlow<List<String>> = _liveRoomQuickAccessSort

    private val _liveRoomQuickAccessEnabled = MutableStateFlow<Set<String>>(emptySet())
    val liveRoomQuickAccessEnabled: StateFlow<Set<String>> = _liveRoomQuickAccessEnabled

    // 桌面快捷键（LogicalKeyboardKey）在 Android 不适用：快捷键默认关闭(0)，映射留给 UI/物理键层。
    private val _liveRoomShortcutFullScreen = MutableStateFlow(kShortcutDisabled)
    val liveRoomShortcutFullScreen: StateFlow<Int> = _liveRoomShortcutFullScreen

    private val _liveRoomShortcutDanmaku = MutableStateFlow(kShortcutDisabled)
    val liveRoomShortcutDanmaku: StateFlow<Int> = _liveRoomShortcutDanmaku

    private val _liveRoomShortcutMute = MutableStateFlow(kShortcutDisabled)
    val liveRoomShortcutMute: StateFlow<Int> = _liveRoomShortcutMute

    private val _liveRoomShortcutRefresh = MutableStateFlow(kShortcutDisabled)
    val liveRoomShortcutRefresh: StateFlow<Int> = _liveRoomShortcutRefresh

    private val _liveRoomShortcutToggleChat = MutableStateFlow(kShortcutDisabled)
    val liveRoomShortcutToggleChat: StateFlow<Int> = _liveRoomShortcutToggleChat

    private val _liveRoomShortcutVolumeUp = MutableStateFlow(kShortcutDisabled)
    val liveRoomShortcutVolumeUp: StateFlow<Int> = _liveRoomShortcutVolumeUp

    private val _liveRoomShortcutVolumeDown = MutableStateFlow(kShortcutDisabled)
    val liveRoomShortcutVolumeDown: StateFlow<Int> = _liveRoomShortcutVolumeDown

    private val _rememberWindowPlacement = MutableStateFlow(false)
    val rememberWindowPlacement: StateFlow<Boolean> = _rememberWindowPlacement

    private val _multiRoomGap = MutableStateFlow(kMultiRoomDefaultGap)
    val multiRoomGap: StateFlow<Int> = _multiRoomGap
    val effectiveMultiRoomGap: Int get() = normalizeMultiRoomGap(_multiRoomGap.value)

    private val _multiRoomCollapseChat = MutableStateFlow(true)
    val multiRoomCollapseChat: StateFlow<Boolean> = _multiRoomCollapseChat

    /* ================================================================
     * 初始化（镜像 Dart onInit → _loadFromStorage）
     * ================================================================ */

    fun init() {
        _loadFromStorage()
    }

    private fun _loadFromStorage() {
        _themeMode.value = localStorage.getValue(LocalStorageService.kThemeMode, 0)
        firstRun = localStorage.getValue(LocalStorageService.kFirstRun, true)
        _danmuSize.value = localStorage.getValue(LocalStorageService.kDanmuSize, 16.0F)
        _danmuOpacity.value = localStorage.getValue(LocalStorageService.kDanmuOpacity, 1.0F)
        _danmuArea.value = localStorage.getValue(LocalStorageService.kDanmuArea, 0.8F)
        _danmuLineCount.value = localStorage.getValue(LocalStorageService.kDanmuLineCount, 8)
        _danmuSpeed.value = localStorage.getValue(LocalStorageService.kDanmuSpeed, 10.0F)
        _danmuEnable.value = localStorage.getValue(LocalStorageService.kDanmuEnable, true)
        _danmuRenderEmoji.value =
            localStorage.getValue(LocalStorageService.kDanmuRenderEmoji, true)
        _danmuBubble.value = localStorage.getValue(LocalStorageService.kDanmuBubble, false)
        _danmuShieldEnable.value =
            localStorage.getValue(LocalStorageService.kDanmuShieldEnable, true)
        _danmuKeywordShieldEnable.value =
            localStorage.getValue(LocalStorageService.kDanmuKeywordShieldEnable, true)
        _danmuUserShieldEnable.value =
            localStorage.getValue(LocalStorageService.kDanmuUserShieldEnable, true)
        _danmuStrokeWidth.value =
            localStorage.getValue(LocalStorageService.kDanmuStrokeWidth, 2.0F)
        _danmuTopMargin.value = localStorage.getValue(LocalStorageService.kDanmuTopMargin, 0.0F)
        _danmuBottomMargin.value =
            localStorage.getValue(LocalStorageService.kDanmuBottomMargin, 0.0F)
        _danmuFontWeight.value = localStorage.getValue(LocalStorageService.kDanmuFontWeight, 4)
        _contributionRankEnable.value =
            localStorage.getValue(LocalStorageService.kContributionRankEnable, true)

        _chatTextSize.value = localStorage.getValue(LocalStorageService.kChatTextSize, 14.0F)
        _chatTextGap.value = localStorage.getValue(LocalStorageService.kChatTextGap, 4.0F)
        _chatBubbleStyle.value =
            localStorage.getValue(LocalStorageService.kChatBubbleStyle, false)

        _qualityLevel.value = localStorage.getValue(LocalStorageService.kQualityLevel, 1)
        _qualityLevelCellular.value =
            localStorage.getValue(LocalStorageService.kQualityLevelCellular, 1)

        _autoExitEnable.value = localStorage.getValue(LocalStorageService.kAutoExitEnable, false)
        _autoExitDuration.value = localStorage.getValue(LocalStorageService.kAutoExitDuration, 60)
        _roomAutoExitDuration.value =
            localStorage.getValue(LocalStorageService.kRoomAutoExitDuration, 60)

        _playerAutoPause.value = localStorage.getValue(LocalStorageService.kPlayerAutoPause, false)
        _allowBackgroundPlayback.value = _loadAllowBackgroundPlayback(_playerAutoPause.value)

        _playerForceHttps.value =
            localStorage.getValue(LocalStorageService.kPlayerForceHttps, false)
        _autoSwitchNextOnLiveEnd.value =
            localStorage.getValue(LocalStorageService.kAutoSwitchNextOnLiveEnd, false)
        _autoSwitchNextOnPlaybackFailure.value =
            localStorage.getValue(LocalStorageService.kAutoSwitchNextOnPlaybackFailure, false)

        _autoFullScreen.value = localStorage.getValue(LocalStorageService.kAutoFullScreen, false)
        _autoPipOnExit.value = localStorage.getValue(LocalStorageService.kAutoPipOnExit, false)
        _playershowSuperChat.value =
            localStorage.getValue(LocalStorageService.kPlayerShowSuperChat, false)
        _liveEventFlowEnable.value =
            localStorage.getValue(LocalStorageService.kLiveEventFlowEnable, false)
        _liveEventFlowLimit.value = normalizeLiveEventFlowLimit(
            localStorage.getValue(LocalStorageService.kLiveEventFlowLimit, kLiveEventFlowDefaultLimit),
        )
        _liveEventFlowOverlayEnable.value =
            localStorage.getValue(LocalStorageService.kLiveEventFlowOverlayEnable, true)
        _liveEventFlowWindowSeconds.value = normalizeLiveEventFlowWindowSeconds(
            localStorage.getValue(
                LocalStorageService.kLiveEventFlowWindowSeconds,
                kLiveEventFlowDefaultWindowSeconds,
            ),
        )
        _liveEventFlowDisplaySeconds.value = normalizeLiveEventFlowDisplaySeconds(
            localStorage.getValue(
                LocalStorageService.kLiveEventFlowDisplaySeconds,
                kLiveEventFlowDefaultDisplaySeconds,
            ),
        )
        _liveEventFlowMinCount.value = normalizeLiveEventFlowMinCount(
            localStorage.getValue(
                LocalStorageService.kLiveEventFlowMinCount,
                kLiveEventFlowDefaultMinCount,
            ),
        )
        _superChatSortDesc.value =
            localStorage.getValue(LocalStorageService.kSuperChatSortDesc, false)
        _danmuDedupeEnable.value =
            localStorage.getValue(LocalStorageService.kDanmuDedupeEnable, false)
        _danmuDedupeMode.value = normalizeDanmuDedupeMode(
            localStorage.getValue(LocalStorageService.kDanmuDedupeMode, kDanmuDedupeModeUser),
        )
        _danmuDedupeWindow.value = normalizeDanmuDedupeWindow(
            localStorage.getValue(
                LocalStorageService.kDanmuDedupeWindow,
                kDanmuDedupeDefaultWindow,
            ),
        )
        _danmuDedupeStep.value =
            localStorage.getValue(LocalStorageService.kDanmuDedupeStep, 2)

        _loadDanmuDelaySettings()
        _loadUserRemarks()
        _loadShieldList()
        _loadShieldPresetList()

        _scaleMode.value = localStorage.getValue(LocalStorageService.kPlayerScaleMode, 0)
        _playerVolume.value = localStorage.getValue(LocalStorageService.kPlayerVolume, 100.0F)
        _playerGestureControlEnable.value =
            localStorage.getValue(LocalStorageService.kPlayerGestureControlEnable, true)
        _pipHideDanmu.value = _loadPipHideDanmu()

        _styleColor.value = localStorage.getValue(LocalStorageService.kStyleColor, 0xff3498db.toInt())
        _isDynamic.value = localStorage.getValue(LocalStorageService.kIsDynamic, false)
        _amoledBlack.value = localStorage.getValue(LocalStorageService.kAmoledBlack, false)
        // 更新渠道：只接受已知渠道，历史脏值回落 gitee（默认渠道）
        val storedChannel = localStorage.getValue(
            LocalStorageService.kUpdateChannel,
            kUpdateChannelGitee,
        )
        _updateChannel.value = if (storedChannel == kUpdateChannelGithub) {
            kUpdateChannelGithub
        } else {
            kUpdateChannelGitee
        }
        _autoCheckUpdate.value =
            localStorage.getValue(LocalStorageService.kUpdateAutoCheck, true)
        _bilibiliLoginTip.value =
            localStorage.getValue(LocalStorageService.kBilibiliLoginTip, true)
        _playerBufferSize.value =
            localStorage.getValue(LocalStorageService.kPlayerBufferSize, 32)

        // 旧版「兼容模式」一次性迁移（task 10-20-live-playback-power-optimization）：
        // 开启过兼容模式 = 走 mediacodec 直通 → 迁移到「低功耗」档并清除旧键。
        val legacyCompat = localStorage.getValue(LocalStorageService.kPlayerCompatMode, false)
        val savedProfile = localStorage.getValue(LocalStorageService.kMpvProfile, "balanced")
        if (legacyCompat) {
            _mpvProfile.value = "performance"
            localStorage.setValue(LocalStorageService.kMpvProfile, "performance")
            localStorage.setValue(LocalStorageService.kPlayerCompatMode, false)
        } else {
            _mpvProfile.value =
                if (savedProfile in MpvOptionsService.profileLabels) savedProfile else "balanced"
        }
        _mpvAdvancedOptions.value =
            localStorage.getValue(LocalStorageService.kMpvAdvancedOptions, "")
        _importedMpvConfPath.value =
            localStorage.getValue(LocalStorageService.kImportedMpvConfPath, "")

        // 字幕功能冻结停用（镜像 Dart：先写 false 再读取）
        localStorage.setValue(LocalStorageService.kLiveSubtitleEnable, false)
        localStorage.setValue(LocalStorageService.kLiveSubtitleStartupGuard, false)
        _liveSubtitleEnable.value = false
        _liveSubtitleModelPath.value =
            localStorage.getValue(LocalStorageService.kLiveSubtitleModelPath, "")
        _liveSubtitleLanguage.value =
            localStorage.getValue(LocalStorageService.kLiveSubtitleLanguage, "auto")
        _liveSubtitleFontSize.value =
            localStorage.getValue(LocalStorageService.kLiveSubtitleFontSize, 18.0F)
        _liveSubtitlePosition.value =
            localStorage.getValue(LocalStorageService.kLiveSubtitlePosition, 1)
        _liveSubtitleOffsetX.value =
            localStorage.getValue(LocalStorageService.kLiveSubtitleOffsetX, 0.5F)
        _liveSubtitleOffsetY.value =
            localStorage.getValue(LocalStorageService.kLiveSubtitleOffsetY, 0.82F)
        _liveSubtitleColor.value =
            localStorage.getValue(LocalStorageService.kLiveSubtitleColor, 0xffffffff.toInt())
        _liveSubtitleFontWeight.value =
            localStorage.getValue(LocalStorageService.kLiveSubtitleFontWeight, 6)
        _liveSubtitleBackgroundEnable.value =
            localStorage.getValue(LocalStorageService.kLiveSubtitleBackgroundEnable, true)
        _liveSubtitlePositionLocked.value =
            localStorage.getValue(LocalStorageService.kLiveSubtitlePositionLocked, false)

        _audioOutputDriver.value =
            localStorage.getValue(LocalStorageService.kAudioOutputDriver, "audiotrack")

        _autoUpdateFollowEnable.value =
            localStorage.getValue(LocalStorageService.kAutoUpdateFollowEnable, true)
        _autoUpdateFollowDuration.value =
            localStorage.getValue(LocalStorageService.kUpdateFollowDuration, 10)
        _updateFollowThreadCount.value = normalizeUpdateFollowThreadCount(
            localStorage.getValue(LocalStorageService.kUpdateFollowThreadCount, 0),
        )
        _followPageSize.value = normalizeFollowPageSize(
            localStorage.getValue(
                LocalStorageService.kFollowPageSize,
                kFollowPageSizeDefault,
            ),
        )

        _lastSearchSiteId.value =
            localStorage.getValue(LocalStorageService.kLastSearchSiteId, Constant.kBiliBili)

        _followGroupMode.value =
            localStorage.getValue(LocalStorageService.kFollowGroupMode, "liveStatus")
        _followSelectedGroupId.value =
            localStorage.getValue(LocalStorageService.kFollowSelectedGroupId, "all")
        _followDisplayStyle.value = normalizeFollowDisplayStyle(
            localStorage.getValue(LocalStorageService.kFollowDisplayStyle, "default"),
        )
        _followOnlyLive.value =
            localStorage.getValue(LocalStorageService.kFollowOnlyLive, false)
        _followRefreshOnEnter.value =
            localStorage.getValue(LocalStorageService.kFollowRefreshOnEnter, false)
        _followShowLiveCover.value =
            localStorage.getValue(LocalStorageService.kFollowShowLiveCover, false)

        _rememberWindowPlacement.value =
            localStorage.getValue(LocalStorageService.kRememberWindowPlacement, false)
        _multiRoomGap.value = normalizeMultiRoomGap(
            localStorage.getValue(LocalStorageService.kMultiRoomGap, kMultiRoomDefaultGap),
        )
        _multiRoomCollapseChat.value =
            localStorage.getValue(LocalStorageService.kMultiRoomCollapseChat, true)

        initSiteSort()
        initHomeSort()
        initLiveRoomTabSort()
        initLiveRoomQuickAccessSettings()
    }

    private fun _loadPipHideDanmu(): Boolean {
        val migrated = localStorage.getValue(LocalStorageService.kPIPHideDanmuDefaultMigrated, false)
        if (!migrated && !localStorage.containsKey(LocalStorageService.kPIPHideDanmu)) {
            localStorage.setValue(LocalStorageService.kPIPHideDanmuDefaultMigrated, true)
            return false
        }
        return localStorage.getValue(LocalStorageService.kPIPHideDanmu, false)
    }

    private fun _loadAllowBackgroundPlayback(legacyAutoPause: Boolean): Boolean {
        if (localStorage.containsKey(LocalStorageService.kAllowBackgroundPlayback)) {
            return localStorage.getValue(LocalStorageService.kAllowBackgroundPlayback, true)
        }
        val value = !legacyAutoPause
        localStorage.setValue(LocalStorageService.kAllowBackgroundPlayback, value)
        return value
    }

    /* ================================================================
     * 排序初始化
     * ================================================================ */

    fun initSiteSort() {
        val sort = localStorage
            .getValue(LocalStorageService.kSiteSort, Sites.allSites.keys.joinToString(","))
            .split(",")
            .filter { it.trim().isNotEmpty() }
            .toMutableList()
        if (sort.size != Sites.allSites.size) {
            for (key in Sites.allSites.keys) {
                if (!sort.contains(key)) sort.add(key)
            }
        }
        _siteSort.value = sort
    }

    fun initHomeSort() {
        val sort = localStorage
            .getValue(LocalStorageService.kHomeSort, ALL_HOME_PAGES.keys.joinToString(","))
            .split(",")
            .filter { it.trim().isNotEmpty() }
            .toMutableList()
        if (sort.size != ALL_HOME_PAGES.size) {
            for (key in ALL_HOME_PAGES.keys) {
                if (!sort.contains(key)) sort.add(key)
            }
        }
        _homeSort.value = sort
    }

    fun initLiveRoomTabSort() {
        val keys = ALL_LIVE_ROOM_TABS.keys.toList()
        val sort = localStorage
            .getValue(LocalStorageService.kLiveRoomTabSort, keys.joinToString(","))
            .split(",")
            .filter { it.trim().isNotEmpty() }
            .toMutableList()
        sort.removeAll { it !in keys }
        for (key in keys) {
            if (!sort.contains(key)) sort.add(key)
        }
        _liveRoomTabSort.value = sort
    }

    fun initLiveRoomQuickAccessSettings() {
        val keys = ALL_LIVE_ROOM_QUICK_ACCESS.keys.toList()
        val sort = localStorage
            .getValue(LocalStorageService.kLiveRoomQuickAccessSort, keys.joinToString(","))
            .split(",")
            .filter { it.trim().isNotEmpty() }
            .toMutableList()
        sort.removeAll { it !in keys }
        for (key in keys) {
            if (!sort.contains(key)) sort.add(key)
        }
        _liveRoomQuickAccessSort.value = sort

        val enabledRaw = localStorage.getValue(
            LocalStorageService.kLiveRoomQuickAccessEnabled,
            keys.joinToString(","),
        )
        _liveRoomQuickAccessEnabled.value = enabledRaw
            .split(",")
            .filter { it in keys }
            .toSet()
    }

    /* ================================================================
     * 设置项写入（镜像 Dart 各 setXxx）
     * ================================================================ */

    fun setNoFirstRun() {
        localStorage.setValue(LocalStorageService.kFirstRun, false)
    }

    fun setTheme(i: Int) {
        _themeMode.value = i
        localStorage.setValue(LocalStorageService.kThemeMode, i)
    }

    fun setScaleMode(value: Int) {
        _scaleMode.value = value
        localStorage.setValue(LocalStorageService.kPlayerScaleMode, value)
    }

    fun setChatTextSize(e: Float) {
        _chatTextSize.value = e
        localStorage.setValue(LocalStorageService.kChatTextSize, e)
    }

    fun setChatTextGap(e: Float) {
        _chatTextGap.value = e
        localStorage.setValue(LocalStorageService.kChatTextGap, e)
    }

    fun setChatBubbleStyle(e: Boolean) {
        _chatBubbleStyle.value = e
        localStorage.setValue(LocalStorageService.kChatBubbleStyle, e)
    }

    fun setDanmuSize(e: Float) {
        _danmuSize.value = e
        localStorage.setValue(LocalStorageService.kDanmuSize, e)
    }

    fun setDanmuSpeed(e: Float) {
        _danmuSpeed.value = e
        localStorage.setValue(LocalStorageService.kDanmuSpeed, e)
    }

    fun setDanmuArea(e: Float) {
        _danmuArea.value = e
        localStorage.setValue(LocalStorageService.kDanmuArea, e)
    }

    fun setDanmuLineCount(e: Int) {
        val value = e.coerceIn(0, 40)
        _danmuLineCount.value = value
        localStorage.setValue(LocalStorageService.kDanmuLineCount, value)
    }

    fun setDanmuOpacity(e: Float) {
        _danmuOpacity.value = e
        localStorage.setValue(LocalStorageService.kDanmuOpacity, e)
    }

    fun setDanmuEnable(e: Boolean) {
        _danmuEnable.value = e
        localStorage.setValue(LocalStorageService.kDanmuEnable, e)
    }

    fun setDanmuRenderEmoji(e: Boolean) {
        _danmuRenderEmoji.value = e
        localStorage.setValue(LocalStorageService.kDanmuRenderEmoji, e)
    }

    fun setDanmuBubble(e: Boolean) {
        _danmuBubble.value = e
        localStorage.setValue(LocalStorageService.kDanmuBubble, e)
    }

    fun setDanmuStrokeWidth(e: Float) {
        _danmuStrokeWidth.value = e
        localStorage.setValue(LocalStorageService.kDanmuStrokeWidth, e)
    }

    fun setDanmuFontWeight(e: Int) {
        _danmuFontWeight.value = e
        localStorage.setValue(LocalStorageService.kDanmuFontWeight, e)
    }

    fun setDanmuTopMargin(e: Float) {
        _danmuTopMargin.value = e
        localStorage.setValue(LocalStorageService.kDanmuTopMargin, e)
    }

    fun setDanmuBottomMargin(e: Float) {
        _danmuBottomMargin.value = e
        localStorage.setValue(LocalStorageService.kDanmuBottomMargin, e)
    }

    fun setContributionRankEnable(e: Boolean) {
        _contributionRankEnable.value = e
        localStorage.setValue(LocalStorageService.kContributionRankEnable, e)
    }

    fun setQualityLevel(level: Int) {
        _qualityLevel.value = level
        localStorage.setValue(LocalStorageService.kQualityLevel, level)
    }

    fun setQualityLevelCellular(level: Int) {
        _qualityLevelCellular.value = level
        localStorage.setValue(LocalStorageService.kQualityLevelCellular, level)
    }

    fun setAutoExitEnable(e: Boolean) {
        _autoExitEnable.value = e
        localStorage.setValue(LocalStorageService.kAutoExitEnable, e)
    }

    fun setAutoExitDuration(e: Int) {
        _autoExitDuration.value = e
        localStorage.setValue(LocalStorageService.kAutoExitDuration, e)
    }

    fun setRoomAutoExitDuration(e: Int) {
        _roomAutoExitDuration.value = e
        localStorage.setValue(LocalStorageService.kRoomAutoExitDuration, e)
    }

    fun setPlayerBufferSize(e: Int) {
        _playerBufferSize.value = e
        localStorage.setValue(LocalStorageService.kPlayerBufferSize, e)
    }

    fun setPlayerAutoPause(e: Boolean) {
        _playerAutoPause.value = e
        localStorage.setValue(LocalStorageService.kPlayerAutoPause, e)
    }

    fun setAllowBackgroundPlayback(e: Boolean) {        _allowBackgroundPlayback.value = e
        _playerAutoPause.value = !e
        localStorage.setValue(LocalStorageService.kAllowBackgroundPlayback, e)
        localStorage.setValue(LocalStorageService.kPlayerAutoPause, !e)
        if (!e) {
            // 背景播放服务 Android 端策略由播放器层处理（TODO）
        }
    }

    fun setAutoFullScreen(e: Boolean) {
        _autoFullScreen.value = e
        localStorage.setValue(LocalStorageService.kAutoFullScreen, e)
    }

    fun setAutoSwitchNextOnLiveEnd(e: Boolean) {
        _autoSwitchNextOnLiveEnd.value = e
        localStorage.setValue(LocalStorageService.kAutoSwitchNextOnLiveEnd, e)
    }

    fun setAutoSwitchNextOnPlaybackFailure(e: Boolean) {
        _autoSwitchNextOnPlaybackFailure.value = e
        localStorage.setValue(LocalStorageService.kAutoSwitchNextOnPlaybackFailure, e)
    }

    fun setAutoPipOnExit(e: Boolean) {
        _autoPipOnExit.value = e
        localStorage.setValue(LocalStorageService.kAutoPipOnExit, e)
    }

    fun setPlayerShowSuperChat(e: Boolean) {
        _playershowSuperChat.value = e
        localStorage.setValue(LocalStorageService.kPlayerShowSuperChat, e)
    }

    fun setPlayerForceHttps(e: Boolean) {
        _playerForceHttps.value = e
        localStorage.setValue(LocalStorageService.kPlayerForceHttps, e)
    }

    fun setPlayerVolume(value: Float) {
        _playerVolume.value = value
        localStorage.setValue(LocalStorageService.kPlayerVolume, value)
    }

    fun setPlayerGestureControlEnable(value: Boolean) {
        _playerGestureControlEnable.value = value
        localStorage.setValue(LocalStorageService.kPlayerGestureControlEnable, value)
    }

    fun setPIPHideDanmu(e: Boolean) {
        _pipHideDanmu.value = e
        localStorage.setValue(LocalStorageService.kPIPHideDanmu, e)
    }

    fun setDanmuShieldEnable(e: Boolean) {
        _danmuShieldEnable.value = e
        localStorage.setValue(LocalStorageService.kDanmuShieldEnable, e)
    }

    fun setDanmuKeywordShieldEnable(e: Boolean) {
        _danmuKeywordShieldEnable.value = e
        localStorage.setValue(LocalStorageService.kDanmuKeywordShieldEnable, e)
    }

    fun setDanmuUserShieldEnable(e: Boolean) {
        _danmuUserShieldEnable.value = e
        localStorage.setValue(LocalStorageService.kDanmuUserShieldEnable, e)
    }

    fun setSuperChatSortDesc(e: Boolean) {
        _superChatSortDesc.value = e
        localStorage.setValue(LocalStorageService.kSuperChatSortDesc, e)
    }

    fun setLiveEventFlowEnable(e: Boolean) {
        _liveEventFlowEnable.value = e
        localStorage.setValue(LocalStorageService.kLiveEventFlowEnable, e)
    }

    fun setLiveEventFlowLimit(e: Int) {
        val value = normalizeLiveEventFlowLimit(e)
        _liveEventFlowLimit.value = value
        localStorage.setValue(LocalStorageService.kLiveEventFlowLimit, value)
    }

    fun setLiveEventFlowOverlayEnable(e: Boolean) {
        _liveEventFlowOverlayEnable.value = e
        localStorage.setValue(LocalStorageService.kLiveEventFlowOverlayEnable, e)
    }

    fun setLiveEventFlowWindowSeconds(e: Int) {
        val value = normalizeLiveEventFlowWindowSeconds(e)
        _liveEventFlowWindowSeconds.value = value
        localStorage.setValue(LocalStorageService.kLiveEventFlowWindowSeconds, value)
    }

    fun setLiveEventFlowDisplaySeconds(e: Int) {
        val value = normalizeLiveEventFlowDisplaySeconds(e)
        _liveEventFlowDisplaySeconds.value = value
        localStorage.setValue(LocalStorageService.kLiveEventFlowDisplaySeconds, value)
    }

    fun setLiveEventFlowMinCount(e: Int) {
        val value = normalizeLiveEventFlowMinCount(e)
        _liveEventFlowMinCount.value = value
        localStorage.setValue(LocalStorageService.kLiveEventFlowMinCount, value)
    }

    fun setDanmuDedupeEnable(e: Boolean) {
        _danmuDedupeEnable.value = e
        localStorage.setValue(LocalStorageService.kDanmuDedupeEnable, e)
    }

    fun setDanmuDedupeMode(e: Int) {
        val value = normalizeDanmuDedupeMode(e)
        _danmuDedupeMode.value = value
        localStorage.setValue(LocalStorageService.kDanmuDedupeMode, value)
        if (value == kDanmuDedupeModeStrict) {
            setDanmuDedupeEnable(true)
            setDanmuDedupeWindow(kDanmuDedupeDefaultWindow)
        }
    }

    fun setDanmuDedupeWindow(e: Int) {
        val value = normalizeDanmuDedupeWindow(e)
        _danmuDedupeWindow.value = value
        localStorage.setValue(LocalStorageService.kDanmuDedupeWindow, value)
    }

    fun setDanmuDedupeStep(e: Int) {
        val value = e.coerceIn(1, 20)
        _danmuDedupeStep.value = value
        localStorage.setValue(LocalStorageService.kDanmuDedupeStep, value)
    }

    fun setStyleColor(e: Int) {
        _styleColor.value = e
        localStorage.setValue(LocalStorageService.kStyleColor, e)
    }

    fun setIsDynamic(e: Boolean) {
        _isDynamic.value = e
        localStorage.setValue(LocalStorageService.kIsDynamic, e)
    }

    fun setAmoledBlack(e: Boolean) {
        _amoledBlack.value = e
        localStorage.setValue(LocalStorageService.kAmoledBlack, e)
    }

    /** 更新渠道（只允许 gitee / github，其余值忽略）。 */
    fun setUpdateChannel(value: String) {
        if (value != kUpdateChannelGitee && value != kUpdateChannelGithub) {
            Log.d("更新：忽略未知渠道值 $value")
            return
        }
        _updateChannel.value = value
        localStorage.setValue(LocalStorageService.kUpdateChannel, value)
    }

    fun setAutoCheckUpdate(e: Boolean) {
        _autoCheckUpdate.value = e
        localStorage.setValue(LocalStorageService.kUpdateAutoCheck, e)
    }

    fun setBiliBiliLoginTip(e: Boolean) {
        _bilibiliLoginTip.value = e
        localStorage.setValue(LocalStorageService.kBilibiliLoginTip, e)
    }

    fun setLiveSubtitleEnable(e: Boolean) {
        _liveSubtitleEnable.value = e
        localStorage.setValue(LocalStorageService.kLiveSubtitleEnable, e)
    }

    fun setLiveSubtitleModelPath(e: String) {
        _liveSubtitleModelPath.value = e.trim()
        localStorage.setValue(LocalStorageService.kLiveSubtitleModelPath, _liveSubtitleModelPath.value)
    }

    fun setLiveSubtitleLanguage(e: String) {
        val value = e.trim().ifEmpty { "auto" }
        _liveSubtitleLanguage.value = value
        localStorage.setValue(LocalStorageService.kLiveSubtitleLanguage, value)
    }

    fun setLiveSubtitleFontSize(e: Float) {
        val value = e.coerceIn(12.0F, 36.0F)
        _liveSubtitleFontSize.value = value
        localStorage.setValue(LocalStorageService.kLiveSubtitleFontSize, value)
    }

    fun setLiveSubtitlePosition(e: Int) {
        val value = e.coerceIn(0, 2)
        _liveSubtitlePosition.value = value
        localStorage.setValue(LocalStorageService.kLiveSubtitlePosition, value)
    }

    fun setLiveSubtitleOffset(x: Float? = null, y: Float? = null) {
        if (x != null) {
            val value = x.coerceIn(0.05F, 0.95F)
            _liveSubtitleOffsetX.value = value
            localStorage.setValue(LocalStorageService.kLiveSubtitleOffsetX, value)
        }
        if (y != null) {
            val value = y.coerceIn(0.08F, 0.92F)
            _liveSubtitleOffsetY.value = value
            localStorage.setValue(LocalStorageService.kLiveSubtitleOffsetY, value)
        }
    }

    fun setLiveSubtitleColor(e: Int) {
        _liveSubtitleColor.value = e
        localStorage.setValue(LocalStorageService.kLiveSubtitleColor, e)
    }

    fun setLiveSubtitleFontWeight(e: Int) {
        val value = e.coerceIn(1, 9)
        _liveSubtitleFontWeight.value = value
        localStorage.setValue(LocalStorageService.kLiveSubtitleFontWeight, value)
    }

    fun setLiveSubtitleBackgroundEnable(e: Boolean) {
        _liveSubtitleBackgroundEnable.value = e
        localStorage.setValue(LocalStorageService.kLiveSubtitleBackgroundEnable, e)
    }

    fun setLiveSubtitlePositionLocked(e: Boolean) {
        _liveSubtitlePositionLocked.value = e
        localStorage.setValue(LocalStorageService.kLiveSubtitlePositionLocked, e)
    }

    fun setAudioOutputDriver(e: String) {
        _audioOutputDriver.value = e
        localStorage.setValue(LocalStorageService.kAudioOutputDriver, e)
    }

    fun setMpvProfile(e: String) {
        _mpvProfile.value = e
        localStorage.setValue(LocalStorageService.kMpvProfile, e)
    }

    fun setMpvAdvancedOptions(e: String) {
        _mpvAdvancedOptions.value = e
        localStorage.setValue(LocalStorageService.kMpvAdvancedOptions, e)
    }

    fun setImportedMpvConfPath(e: String) {
        _importedMpvConfPath.value = e
        localStorage.setValue(LocalStorageService.kImportedMpvConfPath, e)
    }

    fun setAutoUpdateFollowEnable(e: Boolean) {
        _autoUpdateFollowEnable.value = e
        localStorage.setValue(LocalStorageService.kAutoUpdateFollowEnable, e)
    }

    fun setAutoUpdateFollowDuration(e: Int) {
        _autoUpdateFollowDuration.value = e
        localStorage.setValue(LocalStorageService.kUpdateFollowDuration, e)
    }

    fun setUpdateFollowThreadCount(e: Int) {
        val value = normalizeUpdateFollowThreadCount(e)
        _updateFollowThreadCount.value = value
        localStorage.setValue(LocalStorageService.kUpdateFollowThreadCount, value)
    }

    fun setFollowPageSize(value: Int) {
        val normalized = normalizeFollowPageSize(value)
        _followPageSize.value = normalized
        localStorage.setValue(LocalStorageService.kFollowPageSize, normalized)
    }

    fun setLastSearchSiteId(siteId: String) {
        if (!Sites.allSites.containsKey(siteId)) return
        _lastSearchSiteId.value = siteId
        localStorage.setValue(LocalStorageService.kLastSearchSiteId, siteId)
    }

    fun setFollowGroupSelection(mode: String, groupId: String) {
        _followGroupMode.value = mode
        _followSelectedGroupId.value = groupId
        localStorage.setValue(LocalStorageService.kFollowGroupMode, mode)
        localStorage.setValue(LocalStorageService.kFollowSelectedGroupId, groupId)
    }

    fun setFollowDisplayStyle(value: String) {
        val normalized = normalizeFollowDisplayStyle(value)
        _followDisplayStyle.value = normalized
        localStorage.setValue(LocalStorageService.kFollowDisplayStyle, normalized)
    }

    fun setFollowOnlyLive(value: Boolean) {
        _followOnlyLive.value = value
        localStorage.setValue(LocalStorageService.kFollowOnlyLive, value)
    }

    fun setFollowRefreshOnEnter(value: Boolean) {
        _followRefreshOnEnter.value = value
        localStorage.setValue(LocalStorageService.kFollowRefreshOnEnter, value)
    }

    fun setFollowShowLiveCover(value: Boolean) {
        _followShowLiveCover.value = value
        localStorage.setValue(LocalStorageService.kFollowShowLiveCover, value)
    }

    fun setRememberWindowPlacement(value: Boolean) {
        _rememberWindowPlacement.value = value
        localStorage.setValue(LocalStorageService.kRememberWindowPlacement, value)
    }

    fun setMultiRoomGap(value: Int) {
        val normalized = normalizeMultiRoomGap(value)
        _multiRoomGap.value = normalized
        localStorage.setValue(LocalStorageService.kMultiRoomGap, normalized)
    }

    fun setMultiRoomCollapseChat(value: Boolean) {
        _multiRoomCollapseChat.value = value
        localStorage.setValue(LocalStorageService.kMultiRoomCollapseChat, value)
    }

    fun setSiteSort(e: List<String>) {
        _siteSort.value = e
        localStorage.setValue(LocalStorageService.kSiteSort, e.joinToString(","))
    }

    fun setHomeSort(e: List<String>) {
        _homeSort.value = e
        localStorage.setValue(LocalStorageService.kHomeSort, e.joinToString(","))
    }

    fun setLiveRoomTabSort(e: List<String>) {
        val keys = ALL_LIVE_ROOM_TABS.keys.toList()
        val value = e.filter { it in keys }.toMutableList()
        for (key in keys) {
            if (!value.contains(key)) value.add(key)
        }
        _liveRoomTabSort.value = value
        localStorage.setValue(LocalStorageService.kLiveRoomTabSort, value.joinToString(","))
    }

    fun setLiveRoomQuickAccessSort(e: List<String>) {
        val keys = ALL_LIVE_ROOM_QUICK_ACCESS.keys.toList()
        val value = e.filter { it in keys }.toMutableList()
        for (key in keys) {
            if (!value.contains(key)) value.add(key)
        }
        _liveRoomQuickAccessSort.value = value
        localStorage.setValue(LocalStorageService.kLiveRoomQuickAccessSort, value.joinToString(","))
    }

    fun setLiveRoomQuickAccessEnabled(key: String, enabled: Boolean) {
        if (!ALL_LIVE_ROOM_QUICK_ACCESS.containsKey(key)) return
        _liveRoomQuickAccessEnabled.value =
            if (enabled) _liveRoomQuickAccessEnabled.value + key
            else _liveRoomQuickAccessEnabled.value - key
        localStorage.setValue(
            LocalStorageService.kLiveRoomQuickAccessEnabled,
            _liveRoomQuickAccessEnabled.value.joinToString(","),
        )
    }

    private val liveRoomShortcutAssignments: Map<String, String> = mapOf(
        LocalStorageService.kLiveRoomShortcutFullScreen to "切换全屏",
        LocalStorageService.kLiveRoomShortcutDanmaku to "显示/隐藏弹幕",
        LocalStorageService.kLiveRoomShortcutMute to "静音/取消静音",
        LocalStorageService.kLiveRoomShortcutRefresh to "刷新直播间",
        LocalStorageService.kLiveRoomShortcutToggleChat to "收起/展开聊天区",
        LocalStorageService.kLiveRoomShortcutVolumeUp to "调高音量",
        LocalStorageService.kLiveRoomShortcutVolumeDown to "调低音量",
    )

    private fun _setLiveRoomShortcut(
        value: Int,
        target: MutableStateFlow<Int>,
        storageKey: String,
    ) {
        val normalized = if (value == kShortcutDisabled) value else value
        if (normalized != kShortcutDisabled) {
            // Android 无键盘快捷键冲突问题；重复键检查保持 Dart 语义（分配表仅用于提示文案）
            val assigned = listOf(
                _liveRoomShortcutFullScreen.value,
                _liveRoomShortcutDanmaku.value,
                _liveRoomShortcutMute.value,
                _liveRoomShortcutRefresh.value,
                _liveRoomShortcutToggleChat.value,
                _liveRoomShortcutVolumeUp.value,
                _liveRoomShortcutVolumeDown.value,
            )
            if (assigned.filter { it == normalized }.size > 1) {
                Log.d("[AppSettings] 该按键已用于${liveRoomShortcutAssignments[storageKey]}")
            }
        }
        target.value = normalized
        localStorage.setValue(storageKey, normalized)
    }

    fun setLiveRoomShortcutFullScreen(value: Int) =
        _setLiveRoomShortcut(value, _liveRoomShortcutFullScreen, LocalStorageService.kLiveRoomShortcutFullScreen)

    fun setLiveRoomShortcutDanmaku(value: Int) =
        _setLiveRoomShortcut(value, _liveRoomShortcutDanmaku, LocalStorageService.kLiveRoomShortcutDanmaku)

    fun setLiveRoomShortcutMute(value: Int) =
        _setLiveRoomShortcut(value, _liveRoomShortcutMute, LocalStorageService.kLiveRoomShortcutMute)

    fun setLiveRoomShortcutRefresh(value: Int) =
        _setLiveRoomShortcut(value, _liveRoomShortcutRefresh, LocalStorageService.kLiveRoomShortcutRefresh)

    fun setLiveRoomShortcutToggleChat(value: Int) =
        _setLiveRoomShortcut(value, _liveRoomShortcutToggleChat, LocalStorageService.kLiveRoomShortcutToggleChat)

    fun setLiveRoomShortcutVolumeUp(value: Int) =
        _setLiveRoomShortcut(value, _liveRoomShortcutVolumeUp, LocalStorageService.kLiveRoomShortcutVolumeUp)

    fun setLiveRoomShortcutVolumeDown(value: Int) =
        _setLiveRoomShortcut(value, _liveRoomShortcutVolumeDown, LocalStorageService.kLiveRoomShortcutVolumeDown)

    /* ================================================================
     * 弹幕延迟设置（镜像 Dart _loadDanmuDelaySettings / _saveDanmuDelaySettings）
     * ================================================================ */

    private fun _loadDanmuDelaySettings() {
        val rawValue = localStorage.getValue(LocalStorageService.kDanmuDelay, "")
        val delayMap = mutableMapOf<String, Int>()
        if (rawValue.trim().isNotEmpty()) {
            try {
                val decoded = Json.parseToJsonElement(rawValue)
                if (decoded is JsonObject) {
                    for ((key, value) in decoded) {
                        val safeKey = key.trim()
                        val parsed = (value as? JsonPrimitive)?.content?.toIntOrNull() ?: 0
                        if (safeKey.isEmpty()) continue
                        delayMap[safeKey] = parsed.coerceIn(0, 5000)
                    }
                }
            } catch (e: Exception) {
                Log.d("加载弹幕延迟设置失败: $e")
            }
        }
        _danmuDelayMs.value = (delayMap.remove("global") ?: 0).coerceIn(0, 5000)
        _danmuDelayBySite.value = delayMap
    }

    private fun _saveDanmuDelaySettings() {
        val payload = buildJsonObject {
            put("global", _danmuDelayMs.value)
            for ((key, value) in _danmuDelayBySite.value) {
                put(key, value.coerceIn(0, 5000))
            }
        }
        localStorage.setValue(LocalStorageService.kDanmuDelay, payload.toString())
    }

    fun getDanmuDelayMs(siteId: String? = null): Int {
        val value = if (siteId.isNullOrBlank()) {
            _danmuDelayMs.value
        } else {
            _danmuDelayBySite.value[siteId.trim()] ?: _danmuDelayMs.value
        }
        return value.coerceIn(0, 5000)
    }

    fun setDanmuDelayMs(value: Int, siteId: String? = null) {
        val safeValue = value.coerceIn(0, 5000)
        if (siteId.isNullOrBlank()) {
            _danmuDelayMs.value = safeValue
        } else {
            val map = _danmuDelayBySite.value.toMutableMap()
            map[siteId.trim()] = safeValue
            _danmuDelayBySite.value = map
        }
        _saveDanmuDelaySettings()
    }

    /* ================================================================
     * 弹幕行数估算（Android Paint 近似 Dart TextPainter）
     * ================================================================ */

    /**
     * 共享测量 Paint / FontMetrics —— 原实现每次调用都 `Paint()` + `paint.fontMetrics`
     * 新建两个对象，而 [estimateDanmuTextHeight] 在每条弹幕入轨时都会被调用（高弹幕量房间里
     * 每秒数十次），是主线程可观的临时分配来源。复用成员实例 + `synchronized` 保护
     * （调用方可能在主线程/其他线程），零对象分配。
     */
    private val danmuMeasurePaint = Paint()
    private val danmuFontMetrics = Paint.FontMetrics()

    /** 估算弹幕文本行高（对应 Dart estimateDanmuTextHeight）。 */
    fun estimateDanmuTextHeight(
        fontSize: Float? = null,
        strokeWidth: Float? = null,
        fontWeight: Int? = null,
    ): Float {
        val size = fontSize ?: _danmuSize.value
        val stroke = strokeWidth ?: _danmuStrokeWidth.value
        return synchronized(danmuMeasurePaint) {
            danmuMeasurePaint.textSize = size
            danmuMeasurePaint.isAntiAlias = true
            danmuMeasurePaint.style = Paint.Style.STROKE
            danmuMeasurePaint.strokeWidth = stroke
            danmuMeasurePaint.strokeCap = Paint.Cap.ROUND
            danmuMeasurePaint.strokeJoin = Paint.Join.ROUND
            danmuMeasurePaint.getFontMetrics(danmuFontMetrics)
            danmuFontMetrics.descent - danmuFontMetrics.ascent + stroke
        }
    }

    /** 可视行数上限（对应 Dart estimateDanmuMaxVisibleLineCount）。 */
    fun estimateDanmuMaxVisibleLineCount(
        viewportHeight: Float,
        area: Float? = null,
        fontSize: Float? = null,
    ): Int {
        if (viewportHeight <= 0) return 1
        val itemHeight = estimateDanmuTextHeight(fontSize = fontSize)
        val safeArea = (area ?: _danmuArea.value).coerceIn(0.1F, 1.0F)
        val maxRows = ((viewportHeight / itemHeight) * safeArea).toInt()
        return maxRows.coerceIn(1, 40)
    }

    /** 目标弹幕行数（对应 Dart resolveDanmuTargetLineCount）。 */
    fun resolveDanmuTargetLineCount(
        viewportHeight: Float,
        area: Float? = null,
        fontSize: Float? = null,
        lineCount: Int? = null,
    ): Int {
        val requested = lineCount ?: _danmuLineCount.value
        if (requested <= 0) return 0
        if (viewportHeight <= 0) return requested.coerceIn(1, 40)
        val maxLines = estimateDanmuMaxVisibleLineCount(
            viewportHeight = viewportHeight,
            area = area,
            fontSize = fontSize,
        )
        return requested.coerceIn(1, maxLines)
    }

    /** 行高（对应 Dart resolveDanmuLineHeight）。 */
    fun resolveDanmuLineHeight(
        viewportHeight: Float,
        area: Float? = null,
        fontSize: Float? = null,
        lineCount: Int? = null,
    ): Float {
        val targetLines = resolveDanmuTargetLineCount(
            viewportHeight = viewportHeight,
            area = area,
            fontSize = fontSize,
            lineCount = lineCount,
        )
        if (targetLines <= 0) return 1.0F
        if (viewportHeight <= 0) return 1.2F
        val safeArea = (area ?: _danmuArea.value).coerceIn(0.1F, 1.0F)
        val itemHeight = estimateDanmuTextHeight(fontSize = fontSize)
        val lineHeight = ((viewportHeight * safeArea) / itemHeight) / targetLines
        return lineHeight.coerceIn(1.0F, 3.0F)
    }

    /** 实际生效区域（对应 Dart resolveDanmuEffectiveArea）。 */
    fun resolveDanmuEffectiveArea(
        viewportHeight: Float,
        area: Float? = null,
        fontSize: Float? = null,
        lineCount: Int? = null,
    ): Float {
        val safeArea = (area ?: _danmuArea.value).coerceIn(0.1F, 1.0F)
        val targetLines = resolveDanmuTargetLineCount(
            viewportHeight = viewportHeight,
            area = safeArea,
            fontSize = fontSize,
            lineCount = lineCount,
        )
        if (targetLines <= 0) return 0.0F
        if (viewportHeight <= 0) return safeArea
        val itemHeight = estimateDanmuTextHeight(fontSize = fontSize)
        val lineHeight = resolveDanmuLineHeight(
            viewportHeight = viewportHeight,
            area = safeArea,
            fontSize = fontSize,
            lineCount = targetLines,
        )
        val targetArea = (targetLines * itemHeight * lineHeight) / viewportHeight
        return targetArea.coerceIn(0.0F, safeArea)
    }

    /** 稀疏警告阈值（对应 Dart estimateDanmuSparseWarningThreshold）。 */
    fun estimateDanmuSparseWarningThreshold(
        viewportHeight: Float,
        area: Float? = null,
        fontSize: Float? = null,
    ): Int {
        val maxLines = estimateDanmuMaxVisibleLineCount(
            viewportHeight = viewportHeight,
            area = area,
            fontSize = fontSize,
        )
        return (maxLines * 0.35).roundToInt().coerceIn(3, 12)
    }

    /* ================================================================
     * 弹幕屏蔽词（镜像 Dart 屏蔽词管理，存储键=存储值）
     * ================================================================ */

    private fun normalizeShieldSiteId(siteId: String?): String {
        val value = siteId?.trim() ?: ""
        if (value.isEmpty()) return kGlobalUserShieldSiteId
        if (value == kGlobalUserShieldSiteId || Sites.allSites.containsKey(value)) return value
        return kGlobalUserShieldSiteId
    }

    fun resolveShieldSiteLabel(siteId: String?): String {
        val value = normalizeShieldSiteId(siteId)
        if (value == kGlobalUserShieldSiteId) return "全平台"
        return Sites.allSites[value]?.name ?: value
    }

    private fun buildShieldStorageValue(value: String, isUser: Boolean, siteId: String? = null): String {
        if (!isUser) return "$KEYWORD_SHIELD_PREFIX$value"
        val siteKey = normalizeShieldSiteId(siteId)
        if (siteKey == kGlobalUserShieldSiteId) return "$USER_SHIELD_PREFIX$value"
        return "$USER_SHIELD_PREFIX$siteKey:$value"
    }

    private fun parseUserShieldStorageValue(rawValue: String): Pair<String, String>? {
        val value = rawValue.substring(USER_SHIELD_PREFIX.length).trim()
        if (value.isEmpty()) return null
        val separatorIndex = value.indexOf(":")
        if (separatorIndex <= 0) return kGlobalUserShieldSiteId to value
        val siteId = value.substring(0, separatorIndex).trim()
        val userName = value.substring(separatorIndex + 1).trim()
        if (userName.isEmpty()) return null
        if (siteId == kGlobalUserShieldSiteId || Sites.allSites.containsKey(siteId)) {
            return normalizeShieldSiteId(siteId) to userName
        }
        return kGlobalUserShieldSiteId to value
    }

    private fun setUserShieldGroupValues(siteId: String, values: Iterable<String>) {
        val safeSiteId = normalizeShieldSiteId(siteId)
        val normalized = values.map { it.trim() }.filter { it.isNotEmpty() }.toSet().sorted()
        val map = _userShieldGroups.value.toMutableMap()
        if (normalized.isEmpty()) {
            map.remove(safeSiteId)
        } else {
            map[safeSiteId] = normalized
        }
        _userShieldGroups.value = map
    }

    private fun refreshUserShieldList() {
        val allValues = _userShieldGroups.value.values.flatten().toSet().sorted().toSet()
        _userShieldList.value = allValues
    }

    fun getUserShieldValues(siteId: String? = null, includeGlobal: Boolean = false): List<String> {
        val safeSiteId = normalizeShieldSiteId(siteId)
        val values = mutableSetOf<String>()
        if (safeSiteId == kGlobalUserShieldSiteId) {
            values.addAll(_userShieldGroups.value[kGlobalUserShieldSiteId] ?: emptyList())
        } else {
            values.addAll(_userShieldGroups.value[safeSiteId] ?: emptyList())
            if (includeGlobal) {
                values.addAll(_userShieldGroups.value[kGlobalUserShieldSiteId] ?: emptyList())
            }
        }
        return values.sorted()
    }

    fun getUserShieldGroupSnapshot(): Map<String, List<String>> {
        val snapshot = linkedMapOf<String, List<String>>()
        for ((key, values) in _userShieldGroups.value) {
            val normalized = values.map { it.trim() }.filter { it.isNotEmpty() }.toSet().sorted()
            if (normalized.isEmpty()) continue
            snapshot[key] = normalized
        }
        return snapshot
    }

    private fun _loadShieldList() {
        val keywords = mutableSetOf<String>()
        val userGroupValues = linkedMapOf<String, MutableSet<String>>()
        for (rawValue in allShieldValues) {
            val value = rawValue.trim()
            if (value.isEmpty()) continue
            if (value.startsWith(USER_SHIELD_PREFIX)) {
                val parsed = parseUserShieldStorageValue(value)
                if (parsed != null) {
                    userGroupValues.getOrPut(parsed.first) { mutableSetOf() }.add(parsed.second)
                }
                continue
            }
            if (value.startsWith(KEYWORD_SHIELD_PREFIX)) {
                keywords.add(value.substring(KEYWORD_SHIELD_PREFIX.length))
                continue
            }
            keywords.add(value)
        }
        _shieldList.value = keywords
        _userShieldGroups.value = emptyMap()
        for ((key, values) in userGroupValues) {
            setUserShieldGroupValues(key, values)
        }
        refreshUserShieldList()
    }

    private fun _loadShieldPresetList() {
        val presets = mutableListOf<DanmuShieldPreset>()
        for ((nameRaw, rawValue) in localStorage.getAllShieldPreset()) {
            val name = nameRaw.trim()
            val value = rawValue.trim()
            if (name.isEmpty() || value.isEmpty()) continue
            try {
                val preset = parseShieldPresetPayload(value, name)
                if (preset != null) presets.add(preset)
            } catch (e: Exception) {
                Log.d("加载历史屏蔽预设失败: $e")
            }
        }
        presets.sortBy { it.name }
        _shieldPresetList.value = presets
    }

    /** 解析屏蔽预设 JSON（对应 Dart _loadShieldPresetList 内层逻辑）。 */
    private fun parseShieldPresetPayload(rawValue: String, name: String): DanmuShieldPreset? {
        val decoded = Json.parseToJsonElement(rawValue) as? JsonObject ?: return null
        val keywordValues = decoded["keywords"]?.jsonArray
            ?.mapNotNull { (it as? JsonPrimitive)?.content?.trim() }
            ?.filter { it.isNotEmpty() }
            ?.toSet()?.sorted() ?: emptyList()
        val globalUsers = decoded["users"]?.jsonArray
            ?.mapNotNull { (it as? JsonPrimitive)?.content?.trim() }
            ?.filter { it.isNotEmpty() }
            ?.toSet()?.sorted() ?: emptyList()
        val userGroups = linkedMapOf<String, List<String>>()
        val rawUserGroups = decoded["userGroups"] as? JsonObject
        if (rawUserGroups != null) {
            for ((key, entryValue) in rawUserGroups) {
                val siteId = normalizeShieldSiteId(key.toString())
                val values = (entryValue as? JsonArray)
                    ?.mapNotNull { (it as? JsonPrimitive)?.content?.trim() }
                    ?.filter { it.isNotEmpty() }
                    ?.toSet()?.sorted() ?: emptyList()
                if (values.isEmpty()) continue
                userGroups[siteId] = values
            }
        }
        if (globalUsers.isNotEmpty()) {
            userGroups[kGlobalUserShieldSiteId] =
                (globalUsers + (userGroups[kGlobalUserShieldSiteId] ?: emptyList())).toSet().sorted()
        }
        return DanmuShieldPreset(
            name = name,
            keywords = keywordValues,
            users = globalUsers,
            userGroups = userGroups,
        )
    }

    fun refreshShieldData() {
        _loadShieldList()
        _loadShieldPresetList()
    }

    fun reloadFromStorage() {
        _loadFromStorage()
    }

    fun importShieldValue(rawValue: String) {
        val value = rawValue.trim()
        if (value.isEmpty()) return
        if (value.startsWith(USER_SHIELD_PREFIX)) {
            val parsed = parseUserShieldStorageValue(value)
            if (parsed != null) {
                addUserShieldList(parsed.second, siteId = parsed.first)
            }
            return
        }
        if (value.startsWith(KEYWORD_SHIELD_PREFIX)) {
            addShieldList(value.substring(KEYWORD_SHIELD_PREFIX.length))
            return
        }
        addShieldList(value)
    }

    fun addShieldList(e: String) {
        val value = e.trim()
        if (value.isEmpty()) return
        val storageValue = buildShieldStorageValue(value, isUser = false)
        _shieldList.value = _shieldList.value + value
        localStorage.removeShield(value)
        localStorage.setShield(storageValue, storageValue)
    }

    fun removeShieldList(e: String) {
        val value = e.trim()
        val storageValue = buildShieldStorageValue(value, isUser = false)
        _shieldList.value = _shieldList.value - value
        localStorage.removeShield(value)
        localStorage.removeShield(storageValue)
    }

    fun updateShieldList(oldValue: String, newValue: String): Boolean {
        val oldText = oldValue.trim()
        val newText = newValue.trim()
        if (oldText.isEmpty() || newText.isEmpty()) return false
        if (oldText == newText) return true
        removeShieldList(oldText)
        addShieldList(newText)
        return true
    }

    fun addUserShieldList(e: String, siteId: String? = null) {
        val value = e.trim()
        if (value.isEmpty()) return
        val safeSiteId = normalizeShieldSiteId(siteId)
        val storageValue = buildShieldStorageValue(value, isUser = true, siteId = safeSiteId)
        val current = (getUserShieldValues(siteId = safeSiteId) + value).toSet().sorted()
        setUserShieldGroupValues(safeSiteId, current)
        refreshUserShieldList()
        localStorage.setShield(storageValue, storageValue)
    }

    fun removeUserShieldList(e: String, siteId: String? = null) {
        val value = e.trim()
        val safeSiteId = normalizeShieldSiteId(siteId)
        val storageValue = buildShieldStorageValue(value, isUser = true, siteId = safeSiteId)
        val current = (getUserShieldValues(siteId = safeSiteId) - value)
        setUserShieldGroupValues(safeSiteId, current)
        refreshUserShieldList()
        localStorage.removeShield(storageValue)
        if (safeSiteId == kGlobalUserShieldSiteId) {
            localStorage.removeShield("$USER_SHIELD_PREFIX$kGlobalUserShieldSiteId:$value")
        }
    }

    fun updateUserShieldList(oldValue: String, newValue: String, siteId: String? = null): Boolean {
        val oldText = oldValue.trim()
        val newText = newValue.trim()
        if (oldText.isEmpty() || newText.isEmpty()) return false
        if (oldText == newText) return true
        removeUserShieldList(oldText, siteId = siteId)
        addUserShieldList(newText, siteId = siteId)
        return true
    }

    fun isUserShielded(userName: String, siteId: String? = null): Boolean {
        val value = userName.trim()
        if (value.isEmpty()) return false
        val safeSiteId = normalizeShieldSiteId(siteId)
        if ((_userShieldGroups.value[kGlobalUserShieldSiteId] ?: emptyList()).contains(value)) {
            return true
        }
        if (safeSiteId == kGlobalUserShieldSiteId) return false
        return (_userShieldGroups.value[safeSiteId] ?: emptyList()).contains(value)
    }

    fun shouldShieldUser(userName: String, siteId: String? = null): Boolean {
        if (!_danmuShieldEnable.value || !_danmuUserShieldEnable.value) return false
        return isUserShielded(userName, siteId = siteId)
    }

    private fun findShieldPreset(name: String): DanmuShieldPreset? {
        val value = name.trim()
        if (value.isEmpty()) return null
        return _shieldPresetList.value.firstOrNull { it.name == value }
    }

    fun saveShieldPreset(name: String): Boolean {
        val value = name.trim()
        if (value.isEmpty()) return false
        val preset = DanmuShieldPreset(
            name = value,
            keywords = _shieldList.value.sorted(),
            users = getUserShieldValues(siteId = kGlobalUserShieldSiteId),
            userGroups = getUserShieldGroupSnapshot(),
        )
        localStorage.setShieldPreset(value, preset.toJsonObject().toString())
        _loadShieldPresetList()
        return true
    }

    fun applyShieldPreset(name: String): Boolean {
        val preset = findShieldPreset(name) ?: return false
        clearShieldList()
        for (keyword in preset.keywords) {
            addShieldList(keyword)
        }
        val userGroups = if (preset.userGroups.isNotEmpty()) {
            preset.userGroups
        } else {
            buildMap {
                if (preset.users.isNotEmpty()) put(kGlobalUserShieldSiteId, preset.users)
            }
        }
        for ((siteKey, users) in userGroups) {
            for (user in users) {
                addUserShieldList(user, siteId = siteKey)
            }
        }
        setDanmuShieldEnable(true)
        setDanmuKeywordShieldEnable(true)
        setDanmuUserShieldEnable(true)
        return true
    }

    fun deleteShieldPreset(name: String): Boolean {
        val value = name.trim()
        if (value.isEmpty()) return false
        localStorage.removeShieldPreset(value)
        _loadShieldPresetList()
        return true
    }

    fun clearShieldList() {
        _shieldList.value = emptySet()
        _userShieldList.value = emptySet()
        _userShieldGroups.value = emptyMap()
        localStorage.clearShield()
    }

    fun clearKeywordShieldList() {
        val keysToDelete = mutableListOf<String>()
        for ((key, rawValue) in localStorage.getAllShield()) {
            val value = rawValue.trim()
            if (value.isEmpty() || !value.startsWith(USER_SHIELD_PREFIX)) {
                keysToDelete.add(key)
            }
        }
        _shieldList.value = emptySet()
        for (key in keysToDelete) {
            localStorage.removeShield(key)
        }
    }

    fun clearUserShieldList(siteId: String? = null) {
        val safeSiteId = if (siteId == null) null else normalizeShieldSiteId(siteId)
        val keysToDelete = mutableListOf<String>()
        for ((key, rawValue) in localStorage.getAllShield()) {
            val value = rawValue.trim()
            if (!value.startsWith(USER_SHIELD_PREFIX)) continue
            if (safeSiteId == null) {
                keysToDelete.add(key)
                continue
            }
            val parsed = parseUserShieldStorageValue(value)
            if (parsed != null && parsed.first == safeSiteId) {
                keysToDelete.add(key)
            }
        }
        if (safeSiteId == null) {
            _userShieldList.value = emptySet()
            _userShieldGroups.value = emptyMap()
        } else {
            val map = _userShieldGroups.value.toMutableMap()
            map.remove(safeSiteId)
            _userShieldGroups.value = map
            refreshUserShieldList()
        }
        for (key in keysToDelete) {
            localStorage.removeShield(key)
        }
    }

    /* ================================================================
     * 用户备注（镜像 Dart _loadUserRemarks / setUserRemark）
     * ================================================================ */

    private fun _loadUserRemarks() {
        val rawValue = localStorage.getValue(LocalStorageService.kUserRemarks, "")
        val remarks = mutableMapOf<String, String>()
        if (rawValue.trim().isNotEmpty()) {
            try {
                val decoded = Json.parseToJsonElement(rawValue)
                if (decoded is JsonObject) {
                    for ((key, value) in decoded) {
                        val safeKey = key.trim()
                        val safeValue = (value as? JsonPrimitive)?.content?.trim() ?: ""
                        if (safeKey.isEmpty() || safeValue.isEmpty()) continue
                        remarks[safeKey] = safeValue
                    }
                }
            } catch (e: Exception) {
                Log.d("加载用户备注失败: $e")
            }
        }
        _userRemarks.value = remarks
    }

    private fun _saveUserRemarks() {
        val payload = buildJsonObject {
            for ((key, value) in _userRemarks.value) {
                put(key, value)
            }
        }
        localStorage.setValue(LocalStorageService.kUserRemarks, payload.toString())
    }

    private fun buildUserRemarkKey(siteId: String, userName: String): String =
        "${normalizeShieldSiteId(siteId)}::${userName.trim()}"

    fun getUserRemark(userName: String, siteId: String): String? {
        val key = buildUserRemarkKey(siteId, userName)
        val value = _userRemarks.value[key]?.trim()
        if (value.isNullOrEmpty()) return null
        return value
    }

    fun setUserRemark(siteId: String, userName: String, remark: String? = null) {
        val normalizedUserName = userName.trim()
        if (normalizedUserName.isEmpty()) return
        val key = buildUserRemarkKey(siteId, normalizedUserName)
        val value = remark?.trim() ?: ""
        val map = _userRemarks.value.toMutableMap()
        if (value.isEmpty()) {
            map.remove(key)
        } else {
            map[key] = value
        }
        _userRemarks.value = map
        _saveUserRemarks()
    }

    /* ================================================================
     * 屏蔽预设导出/导入（镜像 Dart generateShieldPresetJson / importShieldPresetJson）
     * ================================================================ */

    fun generateShieldPresetJson(): String {
        val payload = buildJsonObject {
            put("version", 2)
            put("exportedAt", java.time.Instant.now().toString())
            put(
                "current",
                buildJsonObject {
                    put("keywords", JsonArray(_shieldList.value.sorted().map { JsonPrimitive(it) }))
                    put(
                        "users",
                        JsonArray(getUserShieldValues(siteId = kGlobalUserShieldSiteId).map { JsonPrimitive(it) }),
                    )
                    put("userGroups", userGroupsToJson(getUserShieldGroupSnapshot()))
                },
            )
            put(
                "presets",
                JsonArray(
                    _shieldPresetList.value.map { preset ->
                        buildJsonObject {
                            put("name", preset.name)
                            put("keywords", JsonArray(preset.keywords.map { JsonPrimitive(it) }))
                            put("users", JsonArray(preset.users.map { JsonPrimitive(it) }))
                            put("userGroups", userGroupsToJson(preset.userGroups))
                        }
                    },
                ),
            )
        }
        val json = Json { prettyPrint = true }
        return json.encodeToString(JsonObject.serializer(), payload)
    }

    private fun userGroupsToJson(groups: Map<String, List<String>>): JsonObject = buildJsonObject {
        for ((key, values) in groups) {
            put(key, JsonArray(values.map { JsonPrimitive(it) }))
        }
    }

    fun importShieldPresetJson(content: String, applyCurrent: Boolean = true) {
        val decoded = Json.parseToJsonElement(content) as? JsonObject ?: return
        val presets = mutableListOf<DanmuShieldPreset>()
        var currentPreset: DanmuShieldPreset? = null

        fun parsePreset(raw: JsonElement?, fallbackName: String? = null): DanmuShieldPreset? {
            val obj = raw as? JsonObject ?: return null
            val name = (obj["name"]?.jsonPrimitive?.content?.trim() ?: fallbackName ?: "").trim()
            val keywords = obj["keywords"]?.jsonArray
                ?.mapNotNull { (it as? JsonPrimitive)?.content?.trim() }
                ?.filter { it.isNotEmpty() }?.toSet()?.sorted() ?: emptyList()
            val users = obj["users"]?.jsonArray
                ?.mapNotNull { (it as? JsonPrimitive)?.content?.trim() }
                ?.filter { it.isNotEmpty() }?.toSet()?.sorted() ?: emptyList()
            val groups = linkedMapOf<String, List<String>>()
            val rawGroups = obj["userGroups"] as? JsonObject
            if (rawGroups != null) {
                for ((key, entryValue) in rawGroups) {
                    val groupValues = (entryValue as? JsonArray)
                        ?.mapNotNull { (it as? JsonPrimitive)?.content?.trim() }
                        ?.filter { it.isNotEmpty() }?.toSet()?.sorted() ?: emptyList()
                    if (groupValues.isEmpty()) continue
                    groups[normalizeShieldSiteId(key)] = groupValues
                }
            }
            if (users.isNotEmpty()) {
                groups[kGlobalUserShieldSiteId] =
                    (users + (groups[kGlobalUserShieldSiteId] ?: emptyList())).toSet().sorted()
            }
            if (name.isEmpty() && fallbackName == null) {
                return DanmuShieldPreset("__current__", keywords, users, groups)
            }
            if (name.isEmpty()) return null
            return DanmuShieldPreset(name, keywords, users, groups)
        }

        val decodedCurrent = decoded["current"]
        if (decodedCurrent != null) {
            currentPreset = parsePreset(decodedCurrent)
        }
        val rawPresets = decoded["presets"]?.jsonArray ?: JsonArray(emptyList())
        for (rawPreset in rawPresets) {
            val preset = parsePreset(rawPreset)
            if (preset == null || preset.name == "__current__") continue
            presets.add(preset)
        }

        for (preset in presets) {
            localStorage.setShieldPreset(preset.name, preset.toJsonObject().toString())
        }
        _loadShieldPresetList()

        if (!applyCurrent || currentPreset == null) return
        clearShieldList()
        for (keyword in currentPreset.keywords) {
            addShieldList(keyword)
        }
        val groups = if (currentPreset.userGroups.isNotEmpty()) {
            currentPreset.userGroups
        } else {
            buildMap {
                if (currentPreset.users.isNotEmpty()) {
                    put(kGlobalUserShieldSiteId, currentPreset.users)
                }
            }
        }
        for ((siteKey, users) in groups) {
            for (user in users) {
                addUserShieldList(user, siteId = siteKey)
            }
        }
    }

    /* ================================================================
     * 上次直播间（镜像 Dart getLastLiveRoom / saveLastLiveRoom 等）
     * ================================================================ */

    fun getLastLiveRoom(): Map<String, String>? {
        val rawValue = localStorage.getValue(LocalStorageService.kLastLiveRoom, "")
        if (rawValue.trim().isEmpty()) return null
        return try {
            val decoded = Json.parseToJsonElement(rawValue) as? JsonObject ?: return null
            val siteId = decoded["siteId"]?.jsonPrimitive?.content?.trim() ?: ""
            val roomId = decoded["roomId"]?.jsonPrimitive?.content?.trim() ?: ""
            if (siteId.isEmpty() || roomId.isEmpty() || !Sites.allSites.containsKey(siteId)) {
                return null
            }
            mapOf("siteId" to siteId, "roomId" to roomId)
        } catch (e: Exception) {
            Log.d("读取上次直播间失败: $e")
            null
        }
    }

    fun saveLastLiveRoom(siteId: String, roomId: String, resumePending: Boolean = false) {
        val safeSiteId = siteId.trim()
        val safeRoomId = roomId.trim()
        if (safeSiteId.isEmpty() || safeRoomId.isEmpty() ||
            !Sites.allSites.containsKey(safeSiteId)
        ) {
            clearLastLiveRoom()
            return
        }
        val payload = buildJsonObject {
            put("siteId", safeSiteId)
            put("roomId", safeRoomId)
            put("savedAt", java.time.Instant.now().toString())
        }
        localStorage.setValue(LocalStorageService.kLastLiveRoom, payload.toString())
        localStorage.setValue(LocalStorageService.kLastLiveRoomResumePending, resumePending)
    }

    fun clearLastLiveRoom() {
        localStorage.removeValue(LocalStorageService.kLastLiveRoom)
        localStorage.setValue(LocalStorageService.kLastLiveRoomResumePending, false)
    }

    fun setLastLiveRoomResumePending(value: Boolean) {
        localStorage.setValue(LocalStorageService.kLastLiveRoomResumePending, value)
    }

    fun consumePendingLastLiveRoom(): Map<String, String>? {
        val pending = localStorage.getValue(LocalStorageService.kLastLiveRoomResumePending, false)
        if (!pending) return null
        setLastLiveRoomResumePending(false)
        return getLastLiveRoom()
    }

    /* ================================================================
     * 归一化辅助（镜像 Dart 各 _normalize*）
     * ================================================================ */

    private fun normalizeLiveEventFlowLimit(value: Int): Int =
        value.coerceIn(kLiveEventFlowMinLimit, kLiveEventFlowMaxLimit)

    private fun normalizeLiveEventFlowWindowSeconds(value: Int): Int =
        value.coerceIn(kLiveEventFlowMinWindowSeconds, kLiveEventFlowMaxWindowSeconds)

    private fun normalizeLiveEventFlowDisplaySeconds(value: Int): Int =
        value.coerceIn(kLiveEventFlowMinDisplaySeconds, kLiveEventFlowMaxDisplaySeconds)

    private fun normalizeLiveEventFlowMinCount(value: Int): Int =
        value.coerceIn(kLiveEventFlowMinCount, kLiveEventFlowMaxCount)

    private fun normalizeDanmuDedupeMode(value: Int): Int =
        if (value == kDanmuDedupeModeStrict) kDanmuDedupeModeStrict else kDanmuDedupeModeUser

    private fun normalizeDanmuDedupeWindow(value: Int): Int =
        value.coerceIn(danmuDedupeWindowMin, kDanmuDedupeMaxWindow)

    private fun normalizeFollowDisplayStyle(value: String): String =
        if (value in followDisplayStyleOptions) value else "default"

    private fun normalizeMultiRoomGap(value: Int): Int =
        value.coerceIn(kMultiRoomMinGap, kMultiRoomMaxGap)

    private fun normalizeUpdateFollowThreadCount(value: Int): Int = value.coerceIn(0, 8)

    private fun normalizeFollowPageSize(value: Int): Int = value.coerceIn(kFollowPageSizeMin, 400)
}