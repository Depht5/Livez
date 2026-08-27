package com.kotlin_livez.app.app.controller

import com.kotlin_livez.app.app.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 页面控制器基类 —— 对应 Dart `BaseController`
 * （lib/app/controller/base_controller.dart）。
 * UI 页状态：加载/空白/错误/未登录 + 统一错误处理。
 */
open class BaseController {
    /** 加载中，更新页面（对应 Dart pageLoadding） */
    val pageLoading = MutableStateFlow(false)

    /** 加载中，不更新页面（对应 Dart loadding） */
    @Volatile
    var loading: Boolean = false

    /** 空白页面（对应 Dart pageEmpty） */
    val pageEmpty = MutableStateFlow(false)

    /** 页面错误（对应 Dart pageError） */
    val pageError = MutableStateFlow(false)

    /** 未登录（对应 Dart notLogin） */
    val notLogin = MutableStateFlow(false)

    /** 错误信息（对应 Dart errorMsg） */
    val errorMsg = MutableStateFlow("")

    /** 登录/登出回调（对应 Dart onLogin / onLogout） */
    open fun onLogin() {}
    open fun onLogout() {}

    /**
     * 显示错误（对应 Dart handleError）。
     * [showPageError]=true 时展示页面错误，否则提示 Toast（Toast 由 UI 层弹出，此处记录日志）。
     */
    fun handleError(exception: Throwable, showPageError: Boolean = false) {
        Log.e(exception.toString())
        val msg = exceptionToString(exception)
        if (showPageError) {
            pageError.value = true
            errorMsg.value = msg
        } else {
            Log.d("Toast: $msg")
        }
    }

    fun exceptionToString(exception: Throwable): String =
        exception.toString().replace("Exception:", "")
}

/**
 * 分页列表控制器基类 —— 对应 Dart `BasePageController<T>`。
 * [T] 为列表元素类型；数据获取由子类实现 [getData]。
 */
open class BasePageController<T> : BaseController() {

    companion object {
        const val refreshCooldownMs = 2_000L
    }

    var currentPage: Int = 1
    var count: Int = 0
    var maxPage: Int = 0
    var pageSize: Int = 24

    /** 是否可加载更多（对应 Dart canLoadMore） */
    val canLoadMore = MutableStateFlow(false)

    /** 列表数据（对应 Dart list） */
    val list = MutableStateFlow<List<T>>(emptyList())
    val listState: StateFlow<List<T>> get() = list

    private var lastRefreshAt: Long? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** 刷新（对应 Dart refreshData）。 */
    open fun refreshData() {
        val now = System.currentTimeMillis()
        val lastRefreshAt = lastRefreshAt
        if (lastRefreshAt != null && now - lastRefreshAt < refreshCooldownMs) {
            Log.d("Toast: 刷新太频繁，请稍后再试")
            return
        }
        this.lastRefreshAt = now
        currentPage = 1
        list.value = emptyList()
        loadData()
    }

    /** 加载数据（对应 Dart loadData）。 */
    fun loadData() {
        if (loading) return
        scope.launch {
            try {
                loading = true
                pageError.value = false
                pageEmpty.value = false
                notLogin.value = false
                val page = currentPage
                pageLoading.value = page == 1

                // 网络/数据库调用统一放 IO 线程（OkHttp 阻塞式）
                val result = withContext(Dispatchers.IO) { getData(page, pageSize) }
                if (page == 1) {
                    list.value = result
                } else {
                    list.value = list.value + result
                }
                if (result.isNotEmpty()) {
                    currentPage = page + 1
                    canLoadMore.value = true
                    pageEmpty.value = false
                } else {
                    canLoadMore.value = false
                    if (page == 1 && result.isEmpty()) {
                        pageEmpty.value = true
                    }
                }
            } catch (e: Exception) {
                handleError(e, showPageError = currentPage == 1)
            } finally {
                loading = false
                pageLoading.value = false
            }
        }
    }

    /** 获取一页数据（对应 Dart getData；子类实现）。 */
    open suspend fun getData(page: Int, pageSize: Int): List<T> = emptyList()

    /** 取消协程（对应 Dart onClose 的清理）。 */
    open fun dispose() {
        scope.coroutineContext[Job]?.cancel()
    }
}