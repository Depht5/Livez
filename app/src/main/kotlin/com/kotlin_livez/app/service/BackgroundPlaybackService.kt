package com.kotlin_livez.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import com.kotlin_livez.app.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 后台播放保活前台服务 —— 对应 Dart `BackgroundPlaybackService.kt`（Flutter 原生层照搬）。
 *
 * 机制（task fullscreen-status-follow-style-background-play）：无前台服务时切后台，
 * 进程很快被系统冻结（音频停、画面失效）；本服务以 mediaPlayback 类型前台运行维持进程，
 * 播放器内核（全局单例）继续输出音频。启停由 [BackgroundPlayback] 按播放状态与设置驱动。
 */
class BackgroundPlaybackService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        return START_STICKY
    }

    private fun buildNotification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setSmallIcon(applicationInfo.icon)
            .setContentTitle("Livez")
            .setContentText("正在后台播放直播")
            .setOngoing(true)
            .setShowWhen(false)
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    },
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "后台播放",
            NotificationManager.IMPORTANCE_LOW,
        )
        channel.description = "直播后台播放保活"
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "livez_background_playback"
        private const val NOTIFICATION_ID = 1001
    }
}

/**
 * 前台服务启停单例 —— 对应 Dart `BackgroundPlaybackService`（dart 单例 + MethodChannel start/stop）。
 *
 * stop 采用 400ms 可取消延迟（task 09-12-refresh-crash）：刷新/换线时 mpv loadfile 替换
 * 旧文件会触发 END_FILE → playing 毫秒级假低谷 → 立即 stop 紧跟 start 会踩中 Android 12+
 * 已知行为（startForegroundService 后、startForeground 前发生 stop → 系统抛
 * ForegroundServiceDidNotStartInTimeException 闪退）。延迟期内的 start 取消挂起 stop，
 * 假低谷被完全吸收；真实暂停/退出（低谷 >400ms）照常停服务。
 */
object BackgroundPlayback {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var running = false
    private var pendingStopJob: Job? = null

    /** 播放中且设置开启时调用（镜像 Dart _syncBackgroundPlaybackService 的 start 分支）。 */
    fun start() {
        // 振荡保护：取消挂起的 stop——毫秒级 playing 低谷不产生任何服务启停系统调用
        pendingStopJob?.cancel()
        pendingStopJob = null
        if (running) return
        running = true
        val context = com.kotlin_livez.app.di.ServiceContainer.context()
        runCatching {
            context.startForegroundService(Intent(context, BackgroundPlaybackService::class.java))
        }.onFailure {
            running = false
            com.kotlin_livez.core.common.CoreLog.e("启动后台播放服务失败", it)
        }
    }

    /** 暂停/播放结束/退出直播间时调用（镜像 Dart stop 分支）。 */
    fun stop() {
        if (!running) return
        pendingStopJob = scope.launch {
            delay(STOP_DELAY_MS)
            pendingStopJob = null
            running = false
            val context = com.kotlin_livez.app.di.ServiceContainer.context()
            runCatching {
                context.stopService(Intent(context, BackgroundPlaybackService::class.java))
            }
        }
    }

    private const val STOP_DELAY_MS = 400L
}
