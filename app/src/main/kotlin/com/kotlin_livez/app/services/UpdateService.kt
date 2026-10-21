package com.kotlin_livez.app.services

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.FileProvider
import com.kotlin_livez.app.BuildConfig
import com.kotlin_livez.app.MainActivity
import com.kotlin_livez.app.app.Log
import com.kotlin_livez.app.app.controller.AppSettingsController
import com.kotlin_livez.core.update.VersionName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * 应用内更新服务（task 10-21-settings-about-update-check）。
 *
 * 职责边界：
 * - 渠道定义与 release 拉取（Gitee v5 / GitHub v3，均取 `/releases?per_page=20`，一次请求同时
 *   服务「最新版本」与「本机版本发布日期」两个用途）；
 * - 版本比较走 `VersionName`（core，纯逻辑 + 单测），本类不做字符串比较；
 * - 下载（OkHttp 流式 + 系统通知进度）与安装前校验（包名 / versionCode / 签名）;
 * - 状态以 StateFlow 暴露给 UI（About 页与全局更新弹窗共用）。
 *
 * 设计约定（详见 design.md）：
 * - 手动检查与冷启动静默检查**共用**同一套结果状态；静默检查失败只写日志不打扰；
 * - 主渠道失败自动用另一渠道重试一次，成功时结果标注实际渠道，**不改**用户已保存的渠道；
 * - 日期一律换算到设备本地时区（GitHub 给 UTC、Gitee 给 +08:00，直接截字符串会差一天）。
 */
class UpdateService(
    private val context: Context,
    private val settings: AppSettingsController,
    private val localStorage: LocalStorageService,
) {

    /* ================================ 渠道 ================================ */

    enum class Channel(
        val id: String,
        val label: String,
        val apiBase: String,
        val webBase: String,
        val accept: String,
    ) {
        GITEE(
            id = "gitee",
            label = "Gitee",
            apiBase = "https://gitee.com/api/v5/repos/Depht5/livez",
            webBase = "https://gitee.com/Depht5/livez",
            accept = "application/json",
        ),
        GITHUB(
            id = "github",
            label = "GitHub",
            apiBase = "https://api.github.com/repos/Depht5/Livez",
            webBase = "https://github.com/Depht5/Livez",
            // GitHub 需要 vnd 头；缺 User-Agent 会直接 403（见 D1）
            accept = "application/vnd.github+json",
        ),
        ;

        companion object {
            fun fromId(id: String): Channel =
                entries.firstOrNull { it.id == id } ?: GITEE

            /** 回退渠道（主渠道之外的另一个）。 */
            fun other(channel: Channel): Channel =
                if (channel == GITEE) GITHUB else GITEE
        }
    }

    /* ================================ 数据 ================================ */

    @Serializable
    internal data class ReleaseDto(
        @SerialName("tag_name") val tagName: String = "",
        val name: String = "",
        val body: String = "",
        val prerelease: Boolean = false,
        val draft: Boolean = false,
        @SerialName("created_at") val createdAt: String? = null,
        @SerialName("published_at") val publishedAt: String? = null,
        val assets: List<AssetDto> = emptyList(),
    )

    @Serializable
    internal data class AssetDto(
        val name: String = "",
        @SerialName("browser_download_url") val downloadUrl: String = "",
        val size: Long = 0,
    )

    /**
     * 一个可安装的远端版本。
     *
     * [version] 是归一化后的版本名（用于比较），[displayVersion] 是 `v` 前缀展示名；
     * [publishedDate] 为本地时区的 `yyyy-MM-dd`（仓库未给日期时为 null）。
     */
    data class Release(
        val version: String,
        val displayVersion: String,
        val publishedDate: String?,
        val notes: String,
        val apkUrl: String?,
        val apkSize: Long,
        val channel: Channel,
        val pageUrl: String,
    )

    sealed interface State {
        data object Idle : State
        data object Checking : State
        data class UpToDate(val currentVersion: String) : State
        data class Available(val release: Release) : State
        data class Failed(val message: String) : State
    }

    sealed interface Download {
        data object Idle : Download

        data class Downloading(
            val release: Release,
            val received: Long,
            val total: Long,
        ) : Download {
            /** 总长未知（缺 Content-Length）时为 -1，UI 用不确定进度条。 */
            val percent: Int
                get() = if (total > 0) ((received * 100) / total).toInt().coerceIn(0, 100) else -1
        }

        data class Verifying(val release: Release) : Download
        data class Ready(val release: Release, val file: File) : Download
        data class Failed(val release: Release?, val message: String) : Download
    }

    /* ================================ 状态 ================================ */

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    /** 待展示的更新（非空 → 全局弹窗显示）。手动检查与冷启动检查都写这里。 */
    private val _pending = MutableStateFlow<Release?>(null)
    val pendingRelease: StateFlow<Release?> = _pending

    private val _download = MutableStateFlow<Download>(Download.Idle)
    val download: StateFlow<Download> = _download

    /** 安装引导提示（例如「未知来源」未授权）；非空时 UI 在安装卡片上显示。 */
    private val _installHint = MutableStateFlow<String?>(null)
    val installHint: StateFlow<String?> = _installHint

    /** 本机版本在仓库的发布日期（缓存优先，进关于页时静默补取）。 */
    private val _releaseDate = MutableStateFlow<String?>(null)
    val releaseDate: StateFlow<String?> = _releaseDate

    /** 冷启动静默检查的「每进程一次」标记。 */
    @Volatile
    private var startupChecked = false

    /** 静默补日期的节流时间戳（毫秒，进程内）。 */
    private var lastDatePrefetchAt = 0L

    private var downloadJob: Job? = null
    private var activeCall: okhttp3.Call? = null

    init {
        _releaseDate.value = cachedReleaseDate()
        cleanupPartialFiles()
    }

    /* ================================ 检查更新 ================================ */

    fun currentVersion(): String = BuildConfig.VERSION_NAME

    /** 手动检查（用户点按钮）：失败会把原因写进 [state] 供 UI 弹窗。 */
    fun checkManually() = startCheck(manual = true)

    /** 冷启动静默检查：开关关闭或本进程已检过则直接返回。 */
    fun checkOnStartup() {
        if (startupChecked) return
        startupChecked = true
        if (!settings.autoCheckUpdate.value) {
            Log.d("更新：自动检查已关闭，跳过启动检查")
            return
        }
        startCheck(manual = false)
    }

    private fun startCheck(manual: Boolean) {
        if (_state.value == State.Checking) {
            Log.d("更新：已有检查在进行中，忽略本次请求")
            return
        }
        val selected = Channel.fromId(settings.updateChannel.value)
        _state.value = State.Checking
        scope.launch {
            val order = listOf(selected, Channel.other(selected))
            var lastError = "未知错误"
            for ((index, channel) in order.withIndex()) {
                try {
                    val result = fetchAndEvaluate(channel)
                    if (result.release != null) {
                        Log.d("更新：发现新版本 ${result.release.displayVersion}（渠道 ${channel.id}${if (index > 0) "，回退" else ""}）")
                        _state.value = State.Available(result.release)
                        _pending.value = result.release
                    } else {
                        Log.d("更新：已是最新（本机 ${VersionName.display(currentVersion())}，渠道 ${channel.id}）")
                        _state.value = State.UpToDate(currentVersion())
                    }
                    return@launch
                } catch (e: Exception) {
                    lastError = mapError(e)
                    Log.d("更新：渠道 ${channel.id} 检查失败：$lastError")
                }
            }
            _state.value = State.Failed(lastError)
        }
    }

    /** 关闭更新弹窗（弹窗的两个按钮都走这里，避免重复弹出）。 */
    fun dismissPending() {
        _pending.value = null
        if (_state.value is State.Available) {
            // 保持 Available 但不再弹窗：重新检查时才会再次提示
            _state.value = State.Idle
        }
    }

    private data class CheckOutcome(val release: Release?)

    /**
     * 拉取一个渠道并评估：返回可安装的新版本（无更新则 release = null）。
     * 同时把「本机版本对应 release 的发布日期」写入缓存（进关于页的日期就靠它）。
     */
    private fun fetchAndEvaluate(channel: Channel): CheckOutcome {
        val releases = fetchReleases(channel)
        if (releases.isEmpty()) throw UpdateParseException("仓库没有发布版本")
        val latest = pickLatestRelease(releases) ?: releases.first()
        val current = currentVersion()

        // 本机版本在仓库中的发布日期（tag 与本机 versionName 等值）
        releases.firstOrNull { VersionName.compare(it.tagName, current) == 0 }
            ?.let { matched ->
                formatLocalDate(matched.publishedAt ?: matched.createdAt)?.let { date ->
                    cacheReleaseDate(current, date)
                }
            }

        val hasNewer = VersionName.isNewer(latest.tagName, current)
        return CheckOutcome(
            release = if (hasNewer) toRelease(latest, channel) else null,
        )
    }

    private fun fetchReleases(channel: Channel): List<ReleaseDto> {
        val request = Request.Builder()
            .url("${channel.apiBase}/releases?per_page=${RELEASES_PER_PAGE}")
            .header("Accept", channel.accept)
            // GitHub 强制要求 User-Agent；Gitee 也接受
            .header("User-Agent", "Livez-Android/${BuildConfig.VERSION_NAME}")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw UpdateHttpException(response.code)
            val bytes = response.body?.bytes() ?: throw UpdateParseException("响应为空")
            // 显式按 UTF-8 解码：默认字符集在部分环境下会把中文更新说明变成乱码
            val text = String(bytes, Charsets.UTF_8)
            val list = runCatching { json.decodeFromString<List<ReleaseDto>>(text) }
                .getOrElse { throw UpdateParseException("发布数据解析失败") }
            return list.filter { !it.draft && !it.prerelease }
        }
    }

    private fun toRelease(dto: ReleaseDto, channel: Channel): Release {
        val apk = selectApkAsset(dto.assets)
        return Release(
            version = VersionName.normalize(dto.tagName),
            displayVersion = VersionName.display(dto.tagName),
            publishedDate = formatLocalDate(dto.publishedAt ?: dto.createdAt),
            notes = dto.body.trim(),
            apkUrl = apk?.downloadUrl,
            apkSize = apk?.size ?: 0L,
            channel = channel,
            pageUrl = "${channel.webBase}/releases/tag/${dto.tagName}",
        )
    }

    /** ISO8601（GitHub 为 UTC、Gitee 为 +08:00）→ 设备本地日期 `yyyy-MM-dd`。 */
    private fun formatLocalDate(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        return runCatching {
            OffsetDateTime.parse(raw)
                .atZoneSameInstant(ZoneId.systemDefault())
                .toLocalDate()
                .toString()
        }.getOrNull()
    }

    /* ================================ 日期缓存 ================================ */

    private fun cachedReleaseDate(): String? {
        val version = localStorage.getValue(LocalStorageService.kUpdateReleaseDateVersion, "")
        if (version != currentVersion()) return null
        return localStorage.getValue(LocalStorageService.kUpdateReleaseDate, "").takeIf { it.isNotBlank() }
    }

    private fun cacheReleaseDate(version: String, date: String) {
        localStorage.setValue(LocalStorageService.kUpdateReleaseDateVersion, version)
        localStorage.setValue(LocalStorageService.kUpdateReleaseDate, date)
        _releaseDate.value = date
    }

    /**
     * 进关于页时静默补取发布日期（不弹 UI、失败静默）。
     *
     * 缓存一旦命中就直接用：某个版本在仓库的发布日期是**不可变**事实，不需要过期重取；
     * 未命中时才打接口，并用 [DATE_PREFETCH_MIN_INTERVAL_MS] 做进程内节流（反复进出页面不重复请求）。
     */
    fun prefetchReleaseDateIfNeeded() {
        val cached = cachedReleaseDate()
        if (cached != null) {
            _releaseDate.value = cached
            return
        }
        val now = System.currentTimeMillis()
        if (now - lastDatePrefetchAt < DATE_PREFETCH_MIN_INTERVAL_MS) return
        lastDatePrefetchAt = now
        val selected = Channel.fromId(settings.updateChannel.value)
        scope.launch {
            for (channel in listOf(selected, Channel.other(selected))) {
                try {
                    val releases = fetchReleases(channel)
                    val matched = releases.firstOrNull {
                        VersionName.compare(it.tagName, currentVersion()) == 0
                    } ?: continue
                    val date = formatLocalDate(matched.publishedAt ?: matched.createdAt) ?: continue
                    cacheReleaseDate(currentVersion(), date)
                    Log.d("更新：补取发布日期成功 $date（渠道 ${channel.id}）")
                    return@launch
                } catch (e: Exception) {
                    Log.d("更新：补取发布日期失败（渠道 ${channel.id}）：${mapError(e)}")
                }
            }
        }
    }

    /* ================================ 下载 ================================ */

    fun downloadAndInstall(release: Release) {
        if (_download.value is Download.Downloading) {
            Log.d("更新：已有下载在进行中，忽略重复请求")
            return
        }
        val url = release.apkUrl
        if (url.isNullOrBlank()) {
            _download.value = Download.Failed(release, "该版本未提供安装包")
            return
        }
        downloadJob = scope.launch {
            val dir = File(context.filesDir, UPDATE_DIR).apply { mkdirs() }
            val target = File(dir, "${release.version}-${release.channel.id}.apk")
            val part = File(dir, "${target.name}.part")

            try {
                if (!(target.exists() && verifyApk(target, release) == null)) {
                    target.delete()
                    download(url, part, release)
                    if (!part.renameTo(target)) {
                        throw UpdateDownloadException("文件保存失败")
                    }
                }
                _download.value = Download.Verifying(release)
                val error = verifyApk(target, release)
                if (error != null) {
                    target.delete()
                    _download.value = Download.Failed(release, error)
                    cancelNotification()
                    return@launch
                }
                _download.value = Download.Ready(release, target)
                notifyCompleted(release)
                withContext(Dispatchers.Main) { launchInstaller(release, target) }
            } catch (e: Exception) {
                part.delete()
                if (e is kotlinx.coroutines.CancellationException) {
                    Log.d("更新：用户取消下载")
                    _download.value = Download.Idle
                    cancelNotification()
                    return@launch
                }
                val message = mapDownloadError(e)
                Log.d("更新：下载失败：$message")
                _download.value = Download.Failed(release, message)
                cancelNotification()
            }
        }
    }

    fun cancelDownload() {
        activeCall?.cancel()
        downloadJob?.cancel()
        activeCall = null
        _download.value = Download.Idle
        cancelNotification()
    }

    /** 关闭下载结果卡片（已完成/失败）；下载进行中先取消再关闭。 */
    fun dismissDownload() {
        if (_download.value is Download.Downloading) {
            cancelDownload()
            return
        }
        _download.value = Download.Idle
        _installHint.value = null
    }

    /** 下载完成后的安装入口（校验已通过才调用）。 */
    fun installReady() {
        val ready = _download.value as? Download.Ready ?: return
        launchInstaller(ready.release, ready.file)
    }

    private suspend fun download(url: String, part: File, release: Release) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Livez-Android/${BuildConfig.VERSION_NAME}")
            .build()
        val call = client.newCall(request)
        activeCall = call
        call.execute().use { response ->
            if (!response.isSuccessful) throw UpdateHttpException(response.code)
            val body = response.body ?: throw UpdateDownloadException("下载响应为空")
            val total = body.contentLength()
            var received = 0L
            var lastNotifiedPercent = -1
            var lastNotifiedAt = 0L

            part.outputStream().use { output ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        if (!currentCoroutineContext().isActive) {
                            throw kotlinx.coroutines.CancellationException("已取消")
                        }
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        received += read
                        _download.value = Download.Downloading(release, received, total)
                        val percent = if (total > 0) ((received * 100) / total).toInt() else -1
                        val now = System.currentTimeMillis()
                        // 通知节流：进度变化 >= 1% 或距上次 >= 250ms
                        if (percent != lastNotifiedPercent && (now - lastNotifiedAt >= NOTIFY_INTERVAL_MS)) {
                            lastNotifiedPercent = percent
                            lastNotifiedAt = now
                            notifyProgress(release, percent, received, total)
                        }
                    }
                }
            }
            activeCall = null
            notifyProgress(release, 100, received.coerceAtLeast(0L), total)
        }
    }

    /* ================================ 校验 ================================ */

    /**
     * 安装前硬门禁：返回 null 表示通过，否则返回给用户看的中文原因。
     *
     * 规则（design D6）：能解包 → 资产版本与 release 一致 → 同包名时 versionCode 更高且签名一致；
     * debug 包装正式包（包名不同）属于「并存安装」，跳过签名比较但要求包名是正式包名。
     */
    private fun verifyApk(file: File, release: Release): String? {
        if (!file.exists() || file.length() <= 0L) return "安装包不存在或为空"
        val pm = context.packageManager
        val archive = archiveInfo(file) ?: return "安装包损坏，无法解析"
        val archivePackage = archive.packageName ?: return "安装包损坏（缺少包名）"
        val archiveVersion = archive.versionName ?: ""
        if (VersionName.compare(archiveVersion, release.version) != 0) {
            return "安装包版本（${VersionName.display(archiveVersion)}）与目标版本（${release.displayVersion}）不一致"
        }
        val installedPackage = context.packageName
        if (archivePackage == installedPackage) {
            val installedCode = installedVersionCode()
            if (archive.longVersionCode <= installedCode) {
                return "安装包版本号（${archive.longVersionCode}）不高于当前版本（$installedCode）"
            }
            val archiveSignature = archiveSignature(file)
            val installedSignature = installedSignature()
            if (archiveSignature != null && installedSignature != null && archiveSignature != installedSignature) {
                return "安装包签名与当前应用不一致，已阻止安装（可能来自其他渠道或密钥）"
            }
        } else if (archivePackage != RELEASE_PACKAGE_NAME) {
            return "安装包包名（$archivePackage）不是本应用"
        }
        return null
    }

    private fun archiveInfo(file: File): android.content.pm.PackageInfo? {
        val pm = context.packageManager
        @Suppress("DEPRECATION")
        val flags = if (Build.VERSION.SDK_INT >= 28) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }
        return runCatching { pm.getPackageArchiveInfo(file.absolutePath, flags) }.getOrNull()
    }

    private fun installedVersionCode(): Long =
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
        }.getOrDefault(0L)

    private fun archiveSignature(file: File): String? {
        val info = archiveInfo(file) ?: return null
        return signatureOf(info)
    }

    private fun installedSignature(): String? =
        runCatching {
            @Suppress("DEPRECATION")
            val flags = if (Build.VERSION.SDK_INT >= 28) {
                PackageManager.GET_SIGNING_CERTIFICATES
            } else {
                PackageManager.GET_SIGNATURES
            }
            context.packageManager.getPackageInfo(context.packageName, flags)
        }.getOrNull()?.let { signatureOf(it) }

    private fun signatureOf(info: android.content.pm.PackageInfo): String? {
        val signatureBytes = (if (Build.VERSION.SDK_INT >= 28) {
            info.signingInfo?.apkContentsSigners?.firstOrNull()?.toByteArray()
        } else {
            @Suppress("DEPRECATION")
            info.signatures?.firstOrNull()?.toByteArray()
        }) ?: return null
        return MessageDigest.getInstance("SHA-256").digest(signatureBytes)
            .joinToString("") { "%02x".format(it) }
    }

    /* ================================ 安装 ================================ */

    private fun launchInstaller(release: Release, file: File) {
        if (!file.exists()) {
            _download.value = Download.Failed(release, "安装包已被清理，请重新下载")
            return
        }
        if (Build.VERSION.SDK_INT >= 26 &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            // 系统「未知来源」未开启：引导到本应用的授权页，返回后再点安装
            runCatching {
                context.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${context.packageName}"),
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
            _download.value = Download.Ready(release, file)
            _installHint.value = "请先允许本应用安装未知来源应用，返回后再次点「安装」"
            return
        }
        _installHint.value = null
        val uri = runCatching {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        }.getOrNull() ?: run {
            _download.value = Download.Failed(release, "安装包共享失败（FileProvider 未配置）")
            return
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val launched = runCatching {
            context.startActivity(intent)
            true
        }.getOrDefault(false)
        if (launched) {
            Log.d("更新：已调起系统安装器 ${file.name}")
            // 安装器已接管：清掉卡片与通知，避免返回后仍看到「已下载」的陈旧状态
            _download.value = Download.Idle
            _installHint.value = null
            cancelNotification()
        } else {
            _download.value = Download.Failed(
                release,
                "无法启动系统安装器，请到文件管理器手动安装：${file.absolutePath}",
            )
        }
    }

    /** 打开渠道的 release 页面（下载失败/无安装包时的兜底）。 */
    fun openReleasePage(url: String) {
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.onFailure { Log.d("更新：无法打开下载页 $url：$it") }
    }

    /* ================================ 通知 ================================ */

    private fun notifyProgress(release: Release, percent: Int, received: Long, total: Long) {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        ensureChannel()
        val text = if (total > 0) {
            "${percent}%（${formatSize(received)} / ${formatSize(total)}）"
        } else {
            formatSize(received)
        }
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("正在下载更新 ${release.displayVersion}")
            .setContentText(text)
            .setContentIntent(appPendingIntent())
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
        if (percent >= 0) {
            builder.setProgress(100, percent, false)
        } else {
            builder.setProgress(0, 0, true)
        }
        if (notificationsGranted()) {
            manager.notify(NOTIFICATION_ID, builder.build())
        }
    }

    private fun notifyCompleted(release: Release) {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled() || !notificationsGranted()) return
        ensureChannel()
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("更新 ${release.displayVersion} 下载完成")
            .setContentText("点击安装")
            .setContentIntent(appPendingIntent())
            .setAutoCancel(true)
            .setProgress(0, 0, false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
        manager.notify(NOTIFICATION_ID, builder.build())
    }

    private fun cancelNotification() {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    private fun appPendingIntent(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "应用更新下载", NotificationManager.IMPORTANCE_LOW).apply {
                description = "显示应用更新的下载进度"
                setShowBadge(false)
            },
        )
    }

    private fun notificationsGranted(): Boolean {
        if (Build.VERSION.SDK_INT < 33) return true
        return androidx.core.content.ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }

    /* ================================ 工具 ================================ */

    private fun cleanupPartialFiles() {
        runCatching {
            File(context.filesDir, UPDATE_DIR).listFiles()
                ?.filter { it.name.endsWith(".part") }
                ?.forEach { it.delete() }
        }
    }

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1024 * 1024 -> String.format(java.util.Locale.ROOT, "%.1f MB", bytes / 1024.0 / 1024.0)
        bytes >= 1024 -> String.format(java.util.Locale.ROOT, "%.0f KB", bytes / 1024.0)
        else -> "$bytes B"
    }

    private fun mapError(e: Exception): String = when (e) {
        is java.net.UnknownHostException, is java.net.ConnectException -> "网络不可用，请检查网络后重试"
        is java.net.SocketTimeoutException -> "请求超时，请稍后重试"
        is UpdateHttpException -> when (e.code) {
            403 -> "请求被限流，请稍后再试"
            404 -> "仓库不可访问（可能为私有或未发布）"
            else -> "服务器返回异常（HTTP ${e.code}）"
        }
        is UpdateParseException -> e.message ?: "数据格式异常，更新源可能已变更"
        is javax.net.ssl.SSLException -> "安全连接失败，请检查网络环境"
        else -> "检查更新失败：${e.message ?: e.javaClass.simpleName}"
    }

    private fun mapDownloadError(e: Exception): String = when (e) {
        is java.net.UnknownHostException, is java.net.ConnectException -> "网络不可用，下载中断"
        is java.net.SocketTimeoutException -> "下载超时，请重试"
        is UpdateHttpException -> "下载失败（HTTP ${e.code}）"
        is UpdateDownloadException -> e.message ?: "下载失败"
        else -> "下载失败：${e.message ?: e.javaClass.simpleName}"
    }

    private class UpdateHttpException(val code: Int) : Exception("HTTP $code")

    private class UpdateParseException(message: String) : Exception(message)

    private class UpdateDownloadException(message: String) : Exception(message)

    private companion object {
        const val CHANNEL_ID = "update_download"
        const val NOTIFICATION_ID = 0x5544
        const val UPDATE_DIR = "updates"
        const val RELEASES_PER_PAGE = 20
        const val CONNECT_TIMEOUT_SECONDS = 8L
        const val READ_TIMEOUT_SECONDS = 15L
        const val BUFFER_SIZE = 8 * 1024
        const val NOTIFY_INTERVAL_MS = 250L
        const val DATE_PREFETCH_MIN_INTERVAL_MS = 5 * 60 * 1000L

        /** 正式包包名：debug 变体带 `.debug` 后缀，用它识别「虽是本应用但不是当前包」的 APK。 */
        const val RELEASE_PACKAGE_NAME = "com.kotlin_livez.app"
    }
}

/**
 * 资产选择：只认 `.apk`。
 *
 * 两个渠道的资产命名不一致（Gitee 上传的是 `app-release.apk`，GitHub 是
 * `Livez-0.2.0-arm64-v8a.apk`），因此**不能按 ABI 名硬匹配**；同名冲突时优先带 `arm64` 的那个。
 * 提取为顶层函数是为了让 JVM 单测能直接覆盖（见 `UpdateReleaseSelectionTest`）。
 */
internal fun selectApkAsset(assets: List<UpdateService.AssetDto>): UpdateService.AssetDto? = assets
    .filter {
        it.downloadUrl.isNotBlank() &&
            (it.name.endsWith(".apk", true) || it.downloadUrl.endsWith(".apk", true))
    }
    .sortedByDescending { it.name.contains("arm64", ignoreCase = true) }
    .firstOrNull()

/** 取「最新」的发布：先滤掉 draft / prerelease，再按发布时间（缺发布时间按最旧）取最大。 */
internal fun pickLatestRelease(releases: List<UpdateService.ReleaseDto>): UpdateService.ReleaseDto? =
    releases.filter { !it.draft && !it.prerelease }
        .maxByOrNull { publishedMillis(it) }

/** 发布时间的毫秒值；解析失败/缺失返回 0（排序时视为最旧，不会抢走「最新」）。 */
internal fun publishedMillis(dto: UpdateService.ReleaseDto): Long = runCatching {
    OffsetDateTime.parse(dto.publishedAt ?: dto.createdAt).toInstant().toEpochMilli()
}.getOrDefault(0L)
