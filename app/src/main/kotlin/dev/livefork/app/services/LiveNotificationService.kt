package dev.livefork.app.services

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dev.livefork.app.app.Log
import dev.livefork.app.models.FollowUser

/**
 * 直播开播通知服务 —— 对应 Dart `LiveNotificationService`
 * （lib/services/live_notification_service.dart）。
 * Android 原生实现：通知权限（Android 13+ POST_NOTIFICATIONS）+ 开播通知。
 */
object LiveNotificationService {

    private const val CHANNEL_ID = "live_start"
    private const val NOTIFICATION_PERMISSION = Manifest.permission.POST_NOTIFICATIONS

    /** 需要通知权限：Android 13+ 且未授予。 */
    fun needsNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT >= 33 &&
            !NotificationManagerCompat.from(appContext()).areNotificationsEnabled()

    /** 对应 Dart requestPermissionIfNeeded（由 UI 层传入 Activity 触发权限请求）。 */
    fun requestPermissionIfNeeded(activity: android.app.Activity?): Boolean {
        if (Build.VERSION.SDK_INT < 33) return true
        val granted = ActivityCompat.checkSelfPermission(
            activity ?: appContext(),
            NOTIFICATION_PERMISSION,
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) return true
        if (activity != null) {
            ActivityCompat.requestPermissions(activity, arrayOf(NOTIFICATION_PERMISSION), 1001)
        }
        return false
    }

    /** 对应 Dart showLiveStart：发送开播通知。 */
    fun showLiveStart(item: FollowUser) {
        val context = appContext()
        if (Build.VERSION.SDK_INT >= 33 &&
            ActivityCompat.checkSelfPermission(context, NOTIFICATION_PERMISSION) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        try {
            ensureChannel(context)
            val notificationId = item.id.hashCode() and 0x7fffffff
            val contentIntent = PendingIntent.getActivity(
                context,
                0,
                Intent(context, dev.livefork.app.MainActivity::class.java).apply {
                    putExtra("siteId", item.siteId)
                    putExtra("roomId", item.roomId)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle("${item.userName} 开播了")
                .setContentText("点击回到 Simple Live 查看直播")
                .setContentIntent(contentIntent)
                .setAutoCancel(true)
                .build()
            NotificationManagerCompat.from(context).notify(notificationId, notification)
        } catch (e: Exception) {
            Log.d("发送开播提醒失败: $e")
        }
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "开播提醒",
                NotificationManager.IMPORTANCE_HIGH,
            )
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    private fun appContext(): Context = dev.livefork.app.di.ServiceContainer.context()
}