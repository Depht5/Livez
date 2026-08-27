package com.kotlin_livez.app.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch

/**
 * 全局事件总线 —— 对应 Dart `EventBus`（lib/app/event_bus.dart）。
 * Dart 用 StreamController.broadcast；此处用 MutableSharedFlow 镜像，
 * listen 返回 Job（对应 StreamSubscription，可取消）。
 */
object EventBus {

    /** 点击了底部导航 */
    const val kBottomNavigationBarClicked = "BottomNavigationBarClicked"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val lock = Any()
    private val streams = mutableMapOf<String, MutableSharedFlow<Any?>>()

    /** 触发事件（对应 Dart emit<T>）。 */
    fun <T> emit(name: String, data: T) {
        stream(name).tryEmit(data)
    }

    /** 监听事件（对应 Dart listen）。返回 Job，cancel() 即取消订阅。 */
    fun <T> listen(name: String, onData: (T) -> Unit): Job =
        scope.launch {
            stream(name).collect { data ->
                @Suppress("UNCHECKED_CAST")
                onData(data as T)
            }
        }

    private fun stream(name: String): MutableSharedFlow<Any?> = synchronized(lock) {
        streams.getOrPut(name) { MutableSharedFlow(extraBufferCapacity = 16) }
    }
}