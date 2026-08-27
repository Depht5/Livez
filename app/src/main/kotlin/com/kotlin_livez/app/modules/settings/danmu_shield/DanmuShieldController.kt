package com.kotlin_livez.app.modules.settings.danmu_shield

import com.kotlin_livez.app.app.Log
import com.kotlin_livez.app.app.Sites
import com.kotlin_livez.app.app.controller.AppSettingsController
import com.kotlin_livez.app.app.controller.BaseController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 弹幕屏蔽控制器 —— 对应 Dart `DanmuShieldController`
 * （lib/modules/settings/danmu_shield/danmu_shield_controller.dart）。
 * 关键词/用户名屏蔽的增删改与预设管理；文件导入导出走 Android SAF（UI 层），
 * 此处提供文本导入导出能力。确认对话框由 UI 层弹出，文案与 Dart 冻结。
 */
class DanmuShieldController(
    private val settingsController: AppSettingsController,
) : BaseController() {

    /** 当前选中站点（对应 Dart selectedUserSiteId） */
    val selectedUserSiteId = MutableStateFlow(AppSettingsController.kGlobalUserShieldSiteId)
    val selectedUserSiteIdState: StateFlow<String> = selectedUserSiteId

    /** 可选站点列表：全平台 + 各站点（对应 Dart userShieldSiteIds） */
    val userShieldSiteIds: List<String>
        get() = listOf(AppSettingsController.kGlobalUserShieldSiteId) + Sites.allSites.keys

    val currentUserSiteId: String get() = selectedUserSiteId.value

    val currentUserSiteLabel: String
        get() = settingsController.resolveShieldSiteLabel(currentUserSiteId)

    val currentUserShieldValues: List<String>
        get() = settingsController.getUserShieldValues(siteId = currentUserSiteId)

    fun setSelectedUserSite(siteId: String) {
        selectedUserSiteId.value = siteId
    }

    /* ---------- 关键词 ---------- */

    fun add(value: String) {
        val text = value.trim()
        if (text.isEmpty()) {
            Log.d("Toast: 请输入关键词")
            return
        }
        settingsController.addShieldList(text)
    }

    fun remove(item: String) {
        settingsController.removeShieldList(item)
    }

    fun edit(oldValue: String, newValue: String) {
        val value = newValue.trim()
        if (value.isEmpty()) {
            Log.d("Toast: 请输入关键词")
            return
        }
        val success = settingsController.updateShieldList(oldValue, value)
        Log.d("Toast: ${if (success) "已更新关键词" else "更新关键词失败"}")
    }

    /** 清空全部关键词（确认框由 UI 层弹出，文案见 Dart 冻结值）。 */
    fun clearKeywords() {
        if (settingsController.shieldList.value.isEmpty()) {
            Log.d("Toast: 当前没有可清空的过滤关键词")
            return
        }
        settingsController.clearKeywordShieldList()
        Log.d("Toast: 已清空过滤关键词")
    }

    /* ---------- 用户名 ---------- */

    fun addUser(value: String) {
        val text = value.trim()
        if (text.isEmpty()) {
            Log.d("Toast: 请输入用户名")
            return
        }
        settingsController.addUserShieldList(text, siteId = currentUserSiteId)
    }

    fun removeUser(item: String, siteId: String? = null) {
        settingsController.removeUserShieldList(item, siteId = siteId ?: currentUserSiteId)
    }

    fun editUser(oldValue: String, newValue: String, siteId: String? = null) {
        val value = newValue.trim()
        if (value.isEmpty()) {
            Log.d("Toast: 请输入用户名")
            return
        }
        val success = settingsController.updateUserShieldList(
            oldValue,
            value,
            siteId = siteId ?: currentUserSiteId,
        )
        Log.d("Toast: ${if (success) "已更新用户名" else "更新用户名失败"}")
    }

    /** 清空用户名屏蔽（确认框由 UI 层弹出，文案见 Dart 冻结值）。 */
    fun clearUsers(clearAll: Boolean = false) {
        val hasValues = if (clearAll) {
            settingsController.userShieldList.value.isNotEmpty()
        } else {
            currentUserShieldValues.isNotEmpty()
        }
        if (!hasValues) {
            Log.d(
                "Toast: ${if (clearAll) "当前没有可清空的屏蔽用户名" else "当前分组没有可清空的屏蔽用户名"}",
            )
            return
        }
        settingsController.clearUserShieldList(siteId = if (clearAll) null else currentUserSiteId)
        Log.d("Toast: ${if (clearAll) "已清空全部用户名屏蔽" else "已清空当前分组"}")
    }

    /* ---------- 预设 ---------- */

    fun savePreset(name: String): Boolean {
        val value = name.trim()
        if (value.isEmpty()) {
            Log.d("Toast: 请输入预设名称")
            return false
        }
        val success = settingsController.saveShieldPreset(value)
        Log.d("Toast: ${if (success) "已保存屏蔽预设" else "保存屏蔽预设失败"}")
        return success
    }

    /** 重命名预设（同名覆盖需 UI 层确认，文案见 Dart 冻结值）。 */
    fun editPreset(originalName: String, nextName: String): Boolean {
        val oldValue = originalName.trim()
        val newValue = nextName.trim()
        if (newValue.isEmpty()) {
            Log.d("Toast: 请输入预设名称")
            return false
        }
        if (oldValue != newValue &&
            settingsController.shieldPresetList.value.any { it.name == newValue }
        ) {
            Log.d("Toast: 已覆盖同名预设（确认框由 UI 层弹出）")
        }
        val success = settingsController.saveShieldPreset(newValue)
        if (!success) {
            Log.d("Toast: 更新屏蔽预设失败")
            return false
        }
        if (oldValue.isNotEmpty() && oldValue != newValue) {
            settingsController.deleteShieldPreset(oldValue)
        }
        Log.d("Toast: 已更新屏蔽预设")
        return true
    }

    fun applyPreset(name: String): Boolean {
        val success = settingsController.applyShieldPreset(name)
        Log.d("Toast: ${if (success) "已启用屏蔽预设" else "启用屏蔽预设失败"}")
        return success
    }

    fun deletePreset(name: String): Boolean {
        val success = settingsController.deleteShieldPreset(name)
        Log.d("Toast: ${if (success) "已删除屏蔽预设" else "删除屏蔽预设失败"}")
        return success
    }

    /* ---------- 导入导出文本（文件读写走 Android SAF，UI 层负责） ---------- */

    fun exportPresetText(): String = settingsController.generateShieldPresetJson()

    fun importPresetText(content: String): Boolean {
        return try {
            settingsController.importShieldPresetJson(content)
            Log.d("Toast: 导入成功，当前配置和预设已更新")
            true
        } catch (e: Exception) {
            Log.d("Toast: 导入失败，请检查内容是否正确")
            false
        }
    }
}