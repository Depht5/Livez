package com.kotlin_livez.app.services

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File

/**
 * 日志抓取服务（手动开关）。
 * 开启后通过 `logcat -v threadtime` 实时把系统日志写入共享存储
 * `/storage/emulated/0/Download/livez_logs/` 下，供复现闪退后导出排查。
 * Android 10+ 走 MediaStore（无需权限）；Android 9- 直接写 File（需 WRITE_EXTERNAL_STORAGE）。
 */
object LogCaptureService {

    private const val TAG = "LogCapture"
    private const val FLUSH_INTERVAL = 50
    private const val DIRELATIVE = "Download/livez_logs"

    private var process: Process? = null
    private var writerThread: Thread? = null

    /** 展示用路径（含 /storage/emulated/0 前缀）。 */
    private var displayPath: String? = null

    /** 是否正在抓取。 */
    val isCapturing: Boolean get() = process != null

    /** 最近一次抓取日志的展示路径。 */
    fun lastLogPath(): String? = displayPath

    /** 开始抓取（幂等；重复调用无效）。 */
    fun start(context: Context) {
        if (process != null) return
        val target = createTarget(context) ?: run {
            displayPath = null
            return
        }
        try {
            // 清空历史日志，只保留本次抓取的内容（定位闪退更干净）
            runCatching {
                val clear = Runtime.getRuntime().exec(arrayOf("logcat", "-c"))
                clear.waitFor()
            }
            // 只采集应用诊断标签。原来的 *:V 会把部分厂商解码器“每帧一条”的日志
            // 同步写入公共存储（本次样本中 C2AllocatorGralloc 约 60 条/秒、占 92%），
            // 既迅速淹没弹幕连接证据，也会让日志采集本身产生额外 CPU/IO 功耗。
            val p = Runtime.getRuntime().exec(
                arrayOf(
                    "logcat", "-v", "threadtime",
                    "Livez:V", "CoreLog:V", "LogCapture:V", "LiveApp:V", "AndroidRuntime:E", "*:S",
                )
            )
            process = p
            displayPath = target.first
            writerThread = Thread({
                var lineCount = 0
                try {
                    p.inputStream.bufferedReader().use { reader ->
                        val resolver = context.contentResolver
                        resolver.openOutputStream(target.second)?.use { out ->
                            val writer = out.bufferedWriter()
                            while (true) {
                                val line = reader.readLine() ?: break
                                writer.write(line)
                                writer.newLine()
                                lineCount++
                                if (lineCount >= FLUSH_INTERVAL) {
                                    writer.flush()
                                    lineCount = 0
                                }
                            }
                            writer.flush()
                        }
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "capture ended: ${e.message}")
                }
            }, "logcapture").apply {
                isDaemon = true
                start()
            }
            Log.d(TAG, "log capture started: ${displayPath}")
        } catch (e: Exception) {
            process = null
            displayPath = null
            Log.d(TAG, "start failed: $e")
        }
    }

    /** 停止抓取（幂等）。 */
    fun stop() {
        process?.destroy()
        process = null
        writerThread = null
        Log.d(TAG, "log capture stopped")
    }

    /**
     * @return (展示路径, 输出 Uri/File 的 Uri)
     */
    private fun createTarget(context: Context): Pair<String, Uri>? {
        val name = "livez_${System.currentTimeMillis()}.log"
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10+：MediaStore，免权限写入公共 Download 目录
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, DIRELATIVE)
                }
                val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: return null
                Pair("/storage/emulated/0/$DIRELATIVE/$name", uri)
            } else {
                // Android 9-：直接写 File（需要 WRITE_EXTERNAL_STORAGE 权限，角度端负责申请）
                val dir = File(Environment.getExternalStorageDirectory(), DIRELATIVE)
                if (!dir.exists() && !dir.mkdirs()) return null
                val file = File(dir, name)
                file.createNewFile()
                Pair(file.absolutePath, Uri.fromFile(file))
            }
        } catch (e: Exception) {
            Log.d(TAG, "create target failed: $e")
            null
        }
    }
}
