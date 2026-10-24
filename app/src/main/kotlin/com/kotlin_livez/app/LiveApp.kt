package com.kotlin_livez.app

import android.app.Application
import android.content.ContentValues
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
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
        // Release 包静默 core 日志：避免每次网络请求/弹幕解析都执行时间戳格式化和
        // HTTP 头脱敏扫描（task 10-20-live-playback-power-optimization）。
        // 抓取日志开启期间由 LogCaptureService 强制置 true 并在停止时恢复。
        CoreLog.enableLog = BuildConfig.DEBUG
        CoreLog.onPrintLog = { level, message ->
            android.util.Log.d("CoreLog", message)
            // tee 通道（未抓取时为一次布尔读直接返回）：logcat 在部分系统上读不到
            // 应用自身日志，抓取期间 core 日志直写抓取文件保底
            com.kotlin_livez.app.services.LogCaptureService.tee(level.name, "CoreLog", message)
        }
        ServiceContainer.install(app = this)
        // 启动即刷新一次关注列表 + 状态（用户决策：无条件执行，绕过进页开关与 30s 冷却）
        ServiceContainer.followService.refreshOnStartup()
        installCrashHandler()
        // 注（targetSdk 35 迁移）：原 installWindowDecorAdaptation（手动
        // setDecorFitsSystemWindows(false) + 透明系统栏 + light bars 深色图标）已由
        // MainActivity.onCreate 的 enableEdgeToEdge() 取代——API 35 弃用
        // statusBarColor/navigationBarColor setter；单 Activity 项目只需一处调用。
        // light bars 动态切换（全屏 transient 栏白图标）见 LiveRoomScreen。
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
