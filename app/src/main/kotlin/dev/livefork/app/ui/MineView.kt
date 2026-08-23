package dev.livefork.app.ui

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.widget.LinearLayout
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import dev.livefork.app.di.ServiceContainer
import dev.livefork.app.services.LogCaptureService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 我的视图 —— 对应 Flutter `mine_page.dart`（账号 + 功能入口列表）。
 * 账号登录状态显示来自各账号服务 StateFlow；入口跳转经 Router（后续接入详情页）。
 */
class MineView(context: Context) : LinearLayout(context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val accountItems = mutableListOf<MenuItem>()

    private val recycler = RecyclerView(context)
    private val adapter = object : RecyclerView.Adapter<MenuVH>() {
        private val items = mutableListOf<MenuItem>()
        private var accountCount = 0

        fun submit(list: List<MenuItem>, accountCount: Int) {
            items.clear()
            items.addAll(list)
            this.accountCount = accountCount
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): MenuVH {
            val text = android.widget.TextView(parent.context).apply {
                setPadding(dp(16), dp(14), dp(16), dp(14))
                textSize = 15f
                setTextColor(Color.parseColor("#222222"))
            }
            return MenuVH(text)
        }

        override fun onBindViewHolder(holder: MenuVH, position: Int) {
            val item = items[position]
            var text = item.title
            if (position < accountCount && item.subtitle.isNotEmpty()) {
                text = "${item.title}　${item.subtitle}"
            } else if (item.subtitle.isNotEmpty()) {
                text = item.title
            }
            holder.text.text = text
            holder.text.setOnClickListener { clickItem(item) }
        }

        override fun getItemCount(): Int = items.size
    }

    private fun clickItem(item: MenuItem) {
        when (item.title) {
            "B 站账号", "抖音账号", "快手账号" -> Router.openFeature("account", "账号管理")
            "观看历史" -> Router.openFeature("history", "观看历史")
            "关注用户" -> Router.openFeature("follow", "关注用户")
            "我的收藏" -> Router.openFeature("favorite", "我的收藏")
            "搜索" -> Router.openFeature("search", "搜索")
            "设置" -> Router.openFeature("settings", "设置")
            "局域网同步" -> Router.openFeature("local_sync", "局域网同步")
            "远端房间同步" -> Router.openFeature("remote_sync", "远端房间同步")
            "WebDAV 同步" -> Router.openFeature("webdav", "WebDAV 同步")
            "解析链接" -> Router.openFeature("parse", "解析链接")
            "抓取日志" -> toggleLogCapture()
            else -> Router.toast("功能开发中：${item.title}")
        }
    }

    init {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(Color.WHITE)

        recycler.layoutManager = LinearLayoutManager(context)
        recycler.addItemDecoration(DividerItemDecoration(context, LinearLayoutManager.VERTICAL))
        recycler.adapter = adapter
        addView(recycler, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        refreshAccounts()
        scope.launch {
            // 观察账号登录状态变化刷新
            ServiceContainer.biliBiliAccount.logined.collect { refreshAccounts() }
        }
        scope.launch {
            ServiceContainer.douyinAccount.hasCookie.collect { refreshAccounts() }
        }
        scope.launch {
            ServiceContainer.kuaishouAccount.hasCookie.collect { refreshAccounts() }
        }
    }

    private fun toggleLogCapture() {
        if (LogCaptureService.isCapturing) {
            LogCaptureService.stop()
            Router.toast("日志采集已停止：${LogCaptureService.lastLogPath() ?: "未知路径"}")
        } else {
            ensureWritePermissionIfNeeded()
            AlertDialog.Builder(context)
                .setTitle("抓取日志")
                .setMessage("开启后将实时写入 logcat 日志文件（/storage/emulated/0/Download/livefork_logs/），用于定位闪退。\n需要先进入直播间复现问题，再回来停止采集。")
                .setPositiveButton("开始") { _, _ ->
                    LogCaptureService.start(context)
                    Router.toast("开始抓取：${LogCaptureService.lastLogPath() ?: "启动失败"}")
                }
                .setNegativeButton("取消", null)
                .show()
        }
    }

    /** Android 9- 直接写共享存储需要 WRITE_EXTERNAL_STORAGE。 */
    private fun ensureWritePermissionIfNeeded() {
        if (android.os.Build.VERSION.SDK_INT >= 29) return
        if (context is android.app.Activity) {
            val activity = context as android.app.Activity
            if (androidx.core.content.ContextCompat.checkSelfPermission(
                    activity, android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
                ) != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                androidx.core.app.ActivityCompat.requestPermissions(
                    activity,
                    arrayOf(android.Manifest.permission.WRITE_EXTERNAL_STORAGE),
                    1001,
                )
            }
        }
    }

    private fun refreshAccounts() {
        val bili = ServiceContainer.biliBiliAccount.logined.value
        val douyin = ServiceContainer.douyinAccount.hasCookie.value
        val kuaishou = ServiceContainer.kuaishouAccount.hasCookie.value

        accountItems.clear()
        accountItems.add(MenuItem("B 站账号", if (bili) "已登录" else "未登录"))
        accountItems.add(MenuItem("抖音账号", if (douyin) "已配置 Cookie" else "未配置"))
        accountItems.add(MenuItem("快手账号", if (kuaishou) "已配置 Cookie" else "未配置"))

        val features = listOf(
            MenuItem("观看历史"),
            MenuItem("关注用户"),
            MenuItem("我的收藏"),
            MenuItem("搜索"),
            MenuItem("设置"),
            MenuItem("局域网同步"),
            MenuItem("远端房间同步"),
            MenuItem("WebDAV 同步"),
            MenuItem("解析链接"),
            MenuItem("抓取日志"),
        )
        adapter.submit(accountItems + features, accountItems.size)
    }

    /** 页面销毁。 */
    fun onDestroy() {
        scope.coroutineContext[Job]?.cancel()
    }
}

private class MenuVH(val text: android.widget.TextView) : RecyclerView.ViewHolder(text)

private fun dp(value: Int): Int =
    (value * android.content.res.Resources.getSystem().displayMetrics.density).toInt()