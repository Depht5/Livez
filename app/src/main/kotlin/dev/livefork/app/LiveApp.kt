package dev.livefork.app

import android.app.Activity
import android.app.Application
import android.content.ContentValues
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import androidx.core.view.WindowInsetsControllerCompat
import dev.livefork.app.di.ServiceContainer
import dev.livefork.core.common.CoreLog
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
     * 全局窗口装饰适配（App 页面均为浅色白底）：
     * - 状态栏图标深色（isAppearanceLightStatusBars），浅色背景下清晰可见；
     * - 底部导航条白色 + 图标深色（isAppearanceLightNavigationBars），与页面融为一体（沉浸）。
     */
    private fun installWindowDecorAdaptation() {
        registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                val window = activity.window
                window.statusBarColor = Color.TRANSPARENT
                window.navigationBarColor = Color.WHITE
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
     * `/storage/emulated/0/Download/livefork_logs/`（Android 10+ 走 MediaStore）
     * 与私有目录各一份，避免崩溃日志再次抓不到。
     */
    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                val content = buildString {
                    appendLine("=== livefork crash ===")
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
                                Environment.DIRECTORY_DOWNLOADS + "/livefork_logs",
                            )
                        }
                        val uri = contentResolver.insert(
                            MediaStore.Downloads.EXTERNAL_CONTENT_URI, values,
                        )
                        if (uri != null) {
                            contentResolver.openOutputStream(uri)?.use { it.write(content.toByteArray()) }
                        }
                    } else {
                        val dir = File(Environment.getExternalStorageDirectory(), "Download/livefork_logs")
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
