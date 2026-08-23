package dev.livefork.app.ui

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.Spannable
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.tabs.TabLayout
import dev.livefork.app.app.Constant
import dev.livefork.app.app.Sites
import dev.livefork.app.app.Utils
import dev.livefork.app.di.ServiceContainer
import dev.livefork.app.modules.live_room.LiveRoomController
import dev.livefork.app.player.MpvInitConfig
import dev.livefork.app.player.MpvVideoView
import dev.livefork.app.player.PlayerEngine
import dev.livefork.app.services.MpvOptionsService
import dev.livefork.core.model.LiveMessage
import dev.livefork.core.model.LiveMessageType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 直播间页面（竖屏）—— 对应 Flutter `live_room_page.dart` 的竖屏布局（buildPhoneUI）。
 * 结构：标题栏（返回/标题/菜单占位）→ 16:9 视频画面 → 信息区（头像/名称/平台 + 人气）→
 * 标签栏（默认"聊天"）+ 内容区（聊天 = 弹幕列表，其余标签占位）。
 * 播放：libmpv（MpvVideoView + PlayerEngine）；数据/弹幕状态流由 LiveRoomController 提供。
 */
class LiveRoomActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_SITE_ID = "site_id"
        private const val EXTRA_ROOM_ID = "room_id"
        private const val TAB_CHAT = "chat"

        fun start(context: Context, siteId: String, roomId: String) {
            val intent = Intent(context, LiveRoomActivity::class.java)
                .putExtra(EXTRA_SITE_ID, siteId)
                .putExtra(EXTRA_ROOM_ID, roomId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var controller: LiveRoomController
    private lateinit var videoView: MpvVideoView
    private lateinit var unliveOverlay: TextView
    private lateinit var titleText: TextView
    private lateinit var userAvatar: ImageView
    private lateinit var userNameText: TextView
    private lateinit var siteNameText: TextView
    private lateinit var onlineText: TextView
    private lateinit var contentFrame: FrameLayout
    private lateinit var messagesRecycler: RecyclerView
    private lateinit var messagesAdapter: MessagesAdapter

    /** 用户上滑后暂停自动滚底；贴底后恢复。 */
    private var disableAutoScroll = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val siteId = intent.getStringExtra(EXTRA_SITE_ID) ?: run { finish(); return }
        val roomId = intent.getStringExtra(EXTRA_ROOM_ID) ?: run { finish(); return }
        val site = Sites.allSites[siteId] ?: run {
            Router.toast("站点不存在：$siteId")
            finish()
            return
        }

        // 竖屏页：保持系统装饰（顶部状态栏留白由系统处理）；屏幕常亮
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }

        /* ---- 顶部标题栏（对应 Dart AppBar + _buildMobileAppBarTitle）---- */
        val titleBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(8), 0)
            setBackgroundColor(Color.WHITE)
        }
        val backButton = TextView(this).apply {
            text = "←"
            textSize = 22f
            setTextColor(Color.parseColor("#333333"))
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(12), dp(8), dp(12))
        }
        backButton.setOnClickListener { finish() }
        titleBar.addView(backButton, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        titleText = TextView(this).apply {
            textSize = 16f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(Color.parseColor("#333333"))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
        }
        titleBar.addView(titleText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val menuButton = TextView(this).apply {
            text = "⋮"
            textSize = 22f
            setTextColor(Color.parseColor("#333333"))
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(12), dp(8), dp(12))
        }
        // 菜单功能后续任务实现，当前占位
        menuButton.setOnClickListener { Router.toast("菜单功能后续实现") }
        titleBar.addView(menuButton, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        root.addView(titleBar, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ))

        /* ---- 视频区（16:9：宽 = 屏宽，高 = 宽 × 9/16；未开播黑色 + 提示）---- */
        val videoHeight = (resources.displayMetrics.widthPixels / LiveRoomGridLayout.coverAspectRatio).toInt()
        val videoFrame = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
        }
        videoView = MpvVideoView(this)
        videoFrame.addView(videoView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT,
        ))
        unliveOverlay = TextView(this).apply {
            text = "未开播"
            textSize = 16f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        videoFrame.addView(unliveOverlay, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT,
        ))
        root.addView(videoFrame, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, videoHeight,
        ))

        /* ---- 信息区（对应 Dart buildUserProfile：头像/名称/平台 + 人气）---- */
        val infoBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setBackgroundColor(Color.WHITE)
        }
        userAvatar = ImageView(this)
        infoBar.addView(userAvatar, LinearLayout.LayoutParams(dp(48), dp(48)))
        val infoTexts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, dp(12), 0)
        }
        userNameText = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.parseColor("#333333"))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        // 平台行：图标 + 平台名
        val siteRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val siteIcon = ImageView(this).apply { setImageResource(site.logoRes) }
        siteIcon.layoutParams = LinearLayout.LayoutParams(dp(16), dp(16))
        siteNameText = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.parseColor("#888888"))
        }
        siteRow.addView(siteIcon, LinearLayout.LayoutParams(dp(16), dp(16)))
        siteRow.addView(
            siteNameText,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { marginStart = dp(4) },
        )
        infoTexts.addView(userNameText, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        infoTexts.addView(siteRow, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        infoBar.addView(infoTexts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        onlineText = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.parseColor("#FF9800"))
        }
        infoBar.addView(onlineText, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        root.addView(infoBar, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ))

        /* ---- 标签栏（对应 Dart buildMessageArea 的 TabBar；仅"聊天"实现内容）---- */
        val tabs = buildTabEntries(siteId)
        val tabLayout = TabLayout(this).apply {
            setSelectedTabIndicatorColor(Color.parseColor("#3498DB"))
            setTabTextColors(Color.parseColor("#888888"), Color.parseColor("#3498DB"))
            tabGravity = TabLayout.GRAVITY_FILL
            setBackgroundColor(Color.WHITE)
        }
        for ((_, label) in tabs) {
            tabLayout.addTab(tabLayout.newTab().setText(label))
        }
        root.addView(tabLayout, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ))

        /* ---- 内容区（weight=1；聊天 = 弹幕列表，其余标签占位）---- */
        contentFrame = FrameLayout(this)
        messagesRecycler = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@LiveRoomActivity)
            addItemDecoration(ChatGapDecoration(dp(8)))
        }
        messagesAdapter = MessagesAdapter()
        messagesRecycler.adapter = messagesAdapter
        // 上滑暂停自动滚底；贴底恢复（对应 Dart disableAutoScroll）
        messagesRecycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                disableAutoScroll = recyclerView.canScrollVertically(1)
            }
        })
        root.addView(contentFrame, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f,
        ))
        contentFrame.addView(messagesRecycler, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT,
        ))
        val placeholderText = TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = 13f
            setTextColor(Color.parseColor("#888888"))
            visibility = View.GONE
        }
        contentFrame.addView(placeholderText, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT,
        ))

        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                val entry = tabs.getOrNull(tab.position)
                val isChat = entry?.first == TAB_CHAT
                messagesRecycler.visibility = if (isChat) View.VISIBLE else View.GONE
                placeholderText.text = if (isChat) "" else "「${entry?.second ?: ""}」功能后续实现"
                placeholderText.visibility = if (isChat) View.GONE else View.VISIBLE
                if (isChat && messagesAdapter.itemCount > 0) {
                    messagesRecycler.scrollToPosition(messagesAdapter.itemCount - 1)
                }
            }

            override fun onTabUnselected(tab: TabLayout.Tab) {}

            override fun onTabReselected(tab: TabLayout.Tab) {}
        })

        setContentView(root)

        /* ---- 控制器与播放器（沿用原逻辑）---- */
        controller = LiveRoomController(
            appSettings = ServiceContainer.appSettings,
            dbService = ServiceContainer.dbService,
            followService = ServiceContainer.followService,
            currentRoom = ServiceContainer.currentRoom,
            player = PlayerEngine.instance,
        )

        val videoConfig = MpvOptionsService.videoControllerConfiguration(ServiceContainer.appSettings)
        runCatching {
            videoView.initialize(
                configDir = filesDir.absolutePath,
                cacheDir = cacheDir.absolutePath,
                config = MpvInitConfig(
                    vo = videoConfig.vo,
                    hwdec = videoConfig.hwdec,
                    options = MpvOptionsService.applyToPlayerOptions(ServiceContainer.appSettings),
                ),
            )
        }.onFailure { e ->
            // 播放器初始化失败不闪退：提示并继续展示弹幕/信息
            Router.toast("播放器初始化失败：${e.message ?: e.javaClass.simpleName}")
        }
        // 播放器初始化失败（缺 native so 等）→ 提示，页面保持可用
        scope.launch {
            PlayerEngine.instance.initError.collect { message ->
                if (message != null) Router.toast(message)
            }
        }

        bindState()

        controller.create(site, roomId)
    }

    /* ---------- 标签集合（对应 Dart 默认标签顺序 + 条件过滤）---------- */

    private fun buildTabEntries(siteId: String): List<Pair<String, String>> {
        val tabs = mutableListOf<Pair<String, String>>()
        tabs += TAB_CHAT to "聊天"
        when (siteId) {
            Constant.kBiliBili -> tabs += "super_chat" to "SC"
            Constant.kHuya -> tabs += "super_chat" to "头条"
        }
        tabs += "follow" to "关注"
        tabs += "contribution_rank" to if (siteId == Constant.kDouyu) "亲密榜" else "贡献榜"
        tabs += "settings" to "设置"
        return tabs
    }

    /* ---------- 状态绑定 ---------- */

    private fun bindState() {
        scope.launch {
            controller.detail.collect { detail ->
                titleText.text = detail?.title?.ifEmpty { null } ?: "直播间"
                userNameText.text = detail?.userName ?: ""
                siteNameText.text = controller.site?.name ?: ""
                NetImage.loadRound(userAvatar, detail?.userAvatar.orEmpty(), 24f)
            }
        }
        scope.launch {
            controller.online.collect {
                onlineText.text = "🔥 ${Utils.onlineToString(it)}"
            }
        }
        scope.launch {
            controller.liveStatus.collect {
                unliveOverlay.visibility = if (it) View.GONE else View.VISIBLE
            }
        }
        scope.launch {
            controller.messages.collect { list ->
                messagesAdapter.submit(list)
                if (!disableAutoScroll && list.isNotEmpty()) {
                    messagesRecycler.scrollToPosition(list.size - 1)
                }
            }
        }
    }

    override fun onDestroy() {
        controller.dispose()
        videoView.destroy()
        scope.coroutineContext[Job]?.cancel()
        super.onDestroy()
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}

/** 聊天消息行间距（对应 Dart chatTextGap × 2 = 8dp）。 */
private class ChatGapDecoration(private val gap: Int) : RecyclerView.ItemDecoration() {
    override fun getItemOffsets(
        outRect: android.graphics.Rect,
        view: View,
        parent: RecyclerView,
        state: RecyclerView.State,
    ) {
        outRect.bottom = gap
    }
}

/** 弹幕消息适配器：用户名（灰）+ 消息正文（深色）两段式；系统消息整行灰色。 */
private class MessagesAdapter : RecyclerView.Adapter<MessagesAdapter.VH>() {

    private val items = mutableListOf<LiveMessage>()

    fun submit(list: List<LiveMessage>) {
        items.clear()
        items.addAll(list.takeLast(200))
        notifyDataSetChanged()
    }

    class VH(val text: TextView) : RecyclerView.ViewHolder(text)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val text = TextView(parent.context).apply {
            setPadding(dp(12), dp(4), dp(12), dp(4))
            textSize = 14f
        }
        return VH(text)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.text.text = buildMessageText(items[position])
    }

    override fun getItemCount(): Int = items.size

    private fun buildMessageText(item: LiveMessage): CharSequence {
        val grey = Color.parseColor("#888888")
        val dark = Color.parseColor("#333333")
        if (item.userName == "LiveSysMessage") {
            return SpannableString(item.message).apply {
                setSpan(ForegroundColorSpan(grey), 0, length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        val builder = SpannableStringBuilder()
        val prefix = if (item.type == LiveMessageType.superChat) "【醒目】${item.userName}" else item.userName
        builder.append(prefix).append("：")
        builder.setSpan(ForegroundColorSpan(grey), 0, builder.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        val start = builder.length
        builder.append(item.message)
        builder.setSpan(ForegroundColorSpan(dark), start, builder.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        return builder
    }
}

private fun dp(value: Int): Int =
    (value * android.content.res.Resources.getSystem().displayMetrics.density).toInt()