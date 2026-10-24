package com.kotlin_livez.app.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.kotlin_livez.app.services.SyncProgress
import com.kotlin_livez.app.services.SyncService
import com.kotlin_livez.app.services.SyncUiEvent
import com.kotlin_livez.app.ui.Router

/**
 * 局域网同步接收侧全局宿主 —— 对应 Dart 各接收 handler 里的
 * `SyncProgressDialog.show/update/dismiss` + `SmartDialog.showToast`。
 *
 * 本机可能停留在任意页面时被对端推送，故挂在 MainActivity 根组合（`UpdateHost` 之后），
 * 收集 [SyncService.syncUiEvents]：Show/Update 渲染进度框、Dismiss 收起、Toast 转发
 * [Router.toast]。进度框不可点外/按返回关闭（对齐 Dart `SmartBackType.block`——
 * compose-ui 1.8 的 DialogProperties 已无 dismissOnBackButton，返回拦截由 Dialog 内容里的
 * BackHandler 承担）。
 */
@Composable
fun SyncProgressHost() {
    var current by remember { mutableStateOf<SyncProgress?>(null) }
    LaunchedEffect(Unit) {
        SyncService.syncUiEvents.collect { event ->
            when (event) {
                is SyncUiEvent.Show -> current = event.progress
                is SyncUiEvent.Update -> current = event.progress
                SyncUiEvent.Dismiss -> current = null
                is SyncUiEvent.Toast -> Router.toast(event.message)
            }
        }
    }
    current?.let { progress ->
        Dialog(
            onDismissRequest = { },
            properties = DialogProperties(dismissOnClickOutside = false),
        ) {
            BackHandler { /* 接收进度进行中：吞掉返回键，对齐 Dart SmartBackType.block */ }
            SyncProgressDialogUi(progress)
        }
    }
}
