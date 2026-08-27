package com.kotlin_livez.app

import android.app.Activity
import android.app.Application
import android.content.ContentValues
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.kotlin_livez.app.di.ServiceContainer
import com.kotlin_livez.core.common.CoreLog
import java.io.File

/**
 * 应用入口。
 * 对应 Flutter 端 `lib/main.dart` 的启动装配（GetX 服务注册 → 此处以 ServiceContainer 承载）。
 * CoreLog 的 onPrintLog 在此接入 android.util.Log。
 */
class LiveApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CoreLog.onPrintLog = { level, message ->
            android.util.Log.d("CoreLog", message)
        }
        ServiceContainer.install(app = this)
        installCrashHandler()
        installWindowDecorAdaptation()
    }

    /**
     * 全局窗口装饰适配（沉浸式，Material 3 任务延续）：
     * - `setDecorFitsSystemWindows(false)`：全窗口 edge-to-edge，内容可延伸到
     *   状态栏/导航条区域，由各页面自身的背景色覆盖系统栏区域（状态栏+底部导航条
     *   不再显示固定白条，随页面背景统一）；
     * - 系统栏背景透明 + 图标深色（浅色页面下清晰可见）。
     */
    private fun installWindowDecorAdaptation() {
        registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                val window = activity.window
                WindowCompat.setDecorFitsSystemWindows(window, false)
                window.statusBarColor = Color.TRANSPARENT
                window.navigationBarColor = Color.TRANSPARENT
                val controller = WindowInsetsControllerCompat(window, window.decorView)
                controller.isAppearanceLightStatusBars = true
                controller.isAppearanceLightNavigationBars = true
            }

            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    /**
     * 全局 Java 崩溃捕获：把堆栈写入共享存储
     * `/storage/emulated/0/Download/livez_logs/`（Android 10+ 走 MediaStore）
     * 与私有目录各一份，避免崩溃日志再次抓不到。
     */
    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                val content = buildString {
                    appendLine("=== Livez crash ===")
                    appendLine("time: ${System.currentTimeMillis()}")
                    appendLine("thread: ${thread.name}")
                    appendLine(throwable.stackTraceToString())
                }
                val name = "crash_${System.currentTimeMillis()}.txt"
                // 私有目录（必定成功）
                runCatching {
                    File(filesDir, name).writeText(content)
                }
                // 共享存储
                runCatching {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val values = ContentValues().apply {
                            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                            put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                            put(
                                MediaStore.MediaColumns.RELATIVE_PATH,
                                Environment.DIRECTORY_DOWNLOADS + "/livez_logs",
                            )
                        }
                        val uri = contentResolver.insert(
                            MediaStore.Downloads.EXTERNAL_CONTENT_URI, values,
                        )
                        if (uri != null) {
                            contentResolver.openOutputStream(uri)?.use { it.write(content.toByteArray()) }
                        }
                    } else {
                        val dir = File(Environment.getExternalStorageDirectory(), "Download/livez_logs")
                        if (dir.exists() || dir.mkdirs()) {
                            File(dir, name).writeText(content)
                        }
                    }
                }
            }
            android.util.Log.e("LiveApp", "uncaught crash on ${thread.name}", throwable)
            previous?.uncaughtException(thread, throwable)
        }
    }
}
