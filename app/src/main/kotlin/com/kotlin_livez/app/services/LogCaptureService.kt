package com.kotlin_livez.app.services

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.kotlin_livez.core.common.CoreLog
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import kotlin.concurrent.thread

/**
 * 日志抓取服务（手动开关）。
 *
 * 抓取写入共享存储 `/storage/emulated/0/Download/livez_logs/livez_<ts>.log`，
 * Android 10+ 走 MediaStore（免权限），Android 9- 直接写 File。
 *
 * **双通道采集（2026-10-24 修复「开启后日志文件空白」）**：
 * - **tee 通道（保底，不依赖系统）**：抓取期间 [com.kotlin_livez.app.app.Log] 与 CoreLog 的
 *   输出经 [tee] 直接入队写文件。Android 13+ 起应用 exec `logcat` 被限制为「仅自身日志」，
 *   个别系统上连自身日志也拿不到（stdout 全空、错误只进 stderr）——旧实现只读 stdout，
 *   logcat 一失效文件就是 0 字节。tee 通道保证应用侧日志一定能抓到。
 * - **logcat 通道（尽力而为）**：先 `logcat -d` 转储存量（含 App 启动期日志），再持续流式；
 *   `redirectErrorStream(true)` 把 stderr 合并进文件——logcat 是否被系统拒绝会直接写在文件里。
 * - 文件头写抓取元数据（时间/版本/设备/SDK/命令行），文件不可能再空白。
 *
 * 抓取期间强制 `CoreLog.enableLog = true`（release 默认 BuildConfig.DEBUG=false 会静默 core 侧
 * 网络与弹幕日志），停止时恢复原值。旧实现的 `logcat -c`（清缓冲区）已移除：普通应用无权清，
 * 且保留存量对诊断更有价值。
 */
object LogCaptureService {

    private const val TAG = "LogCapture"
    private const val FLUSH_INTERVAL = 50
    private const val DIRELATIVE = "Download/livez_logs"

    /** 只采集应用诊断标签 + 崩溃（*:S 静默其余，避免厂商解码器刷屏）。 */
    private val LOGCAT_FILTERS = arrayOf(
        "Livez:V", "CoreLog:V", "LogCapture:V", "LiveApp:V",
        "AndroidRuntime:E", "System.err:W", "*:S",
    )

    /** 停止哨兵（按对象身份比较，避免与日志内容撞车）。 */
    private val STOP: String = "[[LogCapture.STOP]]"

    @Volatile
    private var active = false
    private var process: java.lang.Process? = null
    private var queue: LinkedBlockingQueue<String>? = null
    private var writerThread: Thread? = null

    /** 抓取开始前的 CoreLog 开关原值（停止时恢复）。 */
    private var previousCoreLogEnabled = false

    /** 展示用路径（含 /storage/emulated/0 前缀）。 */
    private var displayPath: String? = null

    /** 是否正在抓取。 */
    val isCapturing: Boolean get() = active

    /** 最近一次抓取日志的展示路径。 */
    fun lastLogPath(): String? = displayPath

    /**
     * 应用侧日志直写入口（tee 通道）：[com.kotlin_livez.app.app.Log] 与 LiveApp 的 CoreLog
     * 桥在抓取期间调用；未开启时为一次布尔读，直接返回。
     */
    fun tee(level: String, tag: String, message: String) {
        if (!active) return
        val ts = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        queue?.offer("$ts tee/$level $tag: $message")
    }

    /** 开始抓取（幂等；重复调用无效）。 */
    fun start(context: Context) {
        if (active) return
        val target = createTarget(context) ?: run {
            displayPath = null
            return
        }
        active = true
        displayPath = target.first
        // 抓取期间放开 core 日志（release 默认关），停止时恢复
        previousCoreLogEnabled = CoreLog.enableLog
        CoreLog.enableLog = true

        val q = LinkedBlockingQueue<String>()
        queue = q
        q.offer(captureHeader())

        // 唯一写文件线程：消费队列（tee + logcat 两个生产者），避免流竞态
        writerThread = thread(name = "logcapture-writer", isDaemon = true) {
            try {
                context.contentResolver.openOutputStream(target.second).let {
                    if (it == null) {
                        Log.e(TAG, "openOutputStream 返回 null，无法写日志文件")
                        return@thread
                    }
                    it.use { out ->
                        val writer = out.bufferedWriter()
                        var pending = 0
                        while (true) {
                            val line = q.take()
                            if (line === STOP) break
                            writer.write(line)
                            writer.newLine()
                            if (++pending >= FLUSH_INTERVAL) {
                                writer.flush()
                                pending = 0
                            }
                        }
                        writer.flush()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "capture writer ended: $e")
            }
        }

        // logcat 读取线程：先 -d 转储存量（含启动期日志），再持续流式；stderr 合并进文件
        thread(name = "logcapture-reader", isDaemon = true) {
            try {
                val dump = ProcessBuilder(listOf("logcat", "-d", "-v", "threadtime") + LOGCAT_FILTERS)
                    .redirectErrorStream(true)
                    .start()
                dump.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { q.offer(it) }
                }
                q.offer("[LogCapture] 存量转储完成，开始流式跟随")

                val p = ProcessBuilder(listOf("logcat", "-v", "threadtime") + LOGCAT_FILTERS)
                    .redirectErrorStream(true)
                    .start()
                process = p
                p.inputStream.bufferedReader().use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        q.offer(line)
                    }
                }
                q.offer("[LogCapture] logcat 流式读取结束（进程退出）")
            } catch (e: Exception) {
                q.offer("[LogCapture] logcat 读取异常：$e")
            }
        }
        Log.d(TAG, "log capture started: $displayPath")
    }

    /** 停止抓取（幂等）。 */
    fun stop() {
        if (!active) return
        active = false
        CoreLog.enableLog = previousCoreLogEnabled
        queue?.offer(STOP)
        process?.destroy()
        process = null
        queue = null
        writerThread = null
        Log.d(TAG, "log capture stopped")
    }

    /** 抓取文件头：元数据自证（时间/版本/设备/命令），空白文件不可能再出现。 */
    private fun captureHeader(): String {
        val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        return buildString {
            appendLine("[LogCapture] 抓取开始 $ts")
            append("[LogCapture] 版本 ${com.kotlin_livez.app.BuildConfig.VERSION_NAME} " +
                "PID ${android.os.Process.myPid()} " +
                "SDK ${Build.VERSION.SDK_INT} (${Build.VERSION.RELEASE}) " +
                "${Build.MANUFACTURER} ${Build.MODEL}")
        }.trimEnd()
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
                // Android 9-：直接写 File（需要 WRITE_EXTERNAL_STORAGE 权限，调用端负责申请）
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
