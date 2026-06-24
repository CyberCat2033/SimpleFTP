package com.cybercat.simpleftp

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal class AppUpdateManager(
    context: Context,
    private val scope: CoroutineScope
) {
    private val appContext = context.applicationContext
    private val packageManager = appContext.packageManager
    private var checkedOnStartup = false
    private var lastLanguageCode = "en"
    private var pendingPermissionUpdate: AvailableAppUpdate? = null
    private var installJob: Job? = null

    val state = MutableStateFlow(
        AppUpdateState(
            currentVersionName = currentVersionName(),
            currentVersionCode = currentVersionCode()
        )
    )

    init {
        scope.launch(Dispatchers.IO) {
            cleanupInstalledUpdateCache()
        }
    }

    fun checkForUpdatesOnStartup(languageCode: String) {
        if (checkedOnStartup) return
        checkedOnStartup = true
        lastLanguageCode = languageCode.ifBlank { "en" }
        scope.launch {
            state.value = state.value.copy(isChecking = true, status = null)
            val result = withContext(Dispatchers.IO) {
                runCatching { loadAvailableUpdate(lastLanguageCode) }
            }
            result
                .onSuccess { update ->
                    state.value = state.value.copy(
                        isChecking = false,
                        availableUpdate = update,
                        status = null
                    )
                }
                .onFailure {
                    state.value = state.value.copy(isChecking = false, status = null)
                }
        }
    }

    fun installAvailableUpdate() {
        val update = state.value.availableUpdate ?: return
        if (state.value.isDownloading || installJob?.isActive == true) return
        installJob = scope.launch {
            state.value = state.value.copy(
                isDownloading = true,
                downloadProgress = null,
                status = AppUpdateStatus.Downloading
            )
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val latestUpdate = loadAvailableUpdate(lastLanguageCode)
                        ?: throw AppUpdateException(AppUpdateErrorReason.NoCompatibleArtifact)
                    val apk = downloadVerifiedApk(latestUpdate, ::updateDownloadProgress)
                    verifyApkPackage(apk)
                    latestUpdate to apk
                }
            }
            result
                .onSuccess { (latestUpdate, apk) ->
                    when (launchInstaller(latestUpdate, apk)) {
                        AppUpdateInstallLaunchResult.InstallerStarted -> {
                            state.value = state.value.copy(
                                isDownloading = false,
                                downloadProgress = null,
                                availableUpdate = latestUpdate,
                                status = AppUpdateStatus.ReadyToInstall
                            )
                        }

                        AppUpdateInstallLaunchResult.PermissionRequired -> {
                            state.value = state.value.copy(
                                isDownloading = false,
                                downloadProgress = null,
                                availableUpdate = latestUpdate,
                                status = AppUpdateStatus.PermissionRequired
                            )
                        }

                        AppUpdateInstallLaunchResult.InstallUnavailable -> {
                            state.value = state.value.copy(
                                isDownloading = false,
                                downloadProgress = null,
                                status = AppUpdateStatus.Error(AppUpdateErrorReason.InstallUnavailable)
                            )
                        }
                    }
                }
                .onFailure { throwable ->
                    if (throwable is CancellationException) throw throwable
                    state.value = state.value.copy(
                        isDownloading = false,
                        downloadProgress = null,
                        status = AppUpdateStatus.Error(throwable.toUpdateErrorReason())
                    )
                }
        }
    }

    fun resumePendingInstall() {
        if (pendingPermissionUpdate == null || !canRequestPackageInstalls()) return
        state.value = state.value.copy(
            availableUpdate = pendingPermissionUpdate,
            status = null
        )
        pendingPermissionUpdate = null
        installAvailableUpdate()
    }

    fun dismissAvailableUpdate() {
        if (state.value.isDownloading) return
        state.value = state.value.copy(
            availableUpdate = null,
            downloadProgress = null,
            status = null
        )
    }

    private fun loadAvailableUpdate(languageCode: String): AvailableAppUpdate? {
        val manifest = fetchManifest()
        validateManifest(manifest)
        if (manifest.versionCode <= currentVersionCode()) return null

        val artifact = selectArtifact(manifest)
            ?: throw AppUpdateException(AppUpdateErrorReason.NoCompatibleArtifact)
        validateArtifact(artifact)
        val changelog = loadChangelog(manifest, languageCode)
        return AvailableAppUpdate(
            versionName = manifest.versionName,
            versionCode = manifest.versionCode,
            artifact = artifact,
            changelog = changelog
        )
    }

    private fun fetchManifest(): AppUpdateManifest {
        val body = fetchText(
            url = BuildConfig.UPDATE_MANIFEST_URL,
            accept = "application/json",
            maxBytes = MAX_MANIFEST_BYTES,
            invalidException = { AppUpdateException(AppUpdateErrorReason.InvalidManifest, it) },
            networkException = { AppUpdateException(AppUpdateErrorReason.Network, it) }
        )
        return runCatching { body.toUpdateManifest() }
            .getOrElse { throwable ->
                throw AppUpdateException(AppUpdateErrorReason.InvalidManifest, throwable)
            }
    }

    private fun validateManifest(manifest: AppUpdateManifest) {
        if (manifest.schemaVersion != 1 ||
            manifest.packageName != appContext.packageName ||
            manifest.versionName.isBlank() ||
            manifest.versionCode <= 0L ||
            manifest.minSdk > Build.VERSION.SDK_INT ||
            manifest.artifacts.isEmpty()
        ) {
            throw AppUpdateException(AppUpdateErrorReason.InvalidManifest)
        }
    }

    private fun validateArtifact(artifact: AppUpdateArtifact) {
        val url = URL(artifact.url)
        if (url.protocol != HTTPS_PROTOCOL ||
            artifact.fileName.isBlank() ||
            !artifact.fileName.endsWith(".apk", ignoreCase = true) ||
            !SHA256_PATTERN.matches(artifact.sha256)
        ) {
            throw AppUpdateException(AppUpdateErrorReason.InvalidManifest)
        }
    }

    private fun selectArtifact(manifest: AppUpdateManifest): AppUpdateArtifact? {
        val artifactsByAbi = manifest.artifacts.associateBy { it.abi }
        return Build.SUPPORTED_ABIS.firstNotNullOfOrNull { abi -> artifactsByAbi[abi] }
            ?: artifactsByAbi[UNIVERSAL_ABI]
    }

    private fun loadChangelog(manifest: AppUpdateManifest, languageCode: String): String? =
        runCatching {
            val changelogUrl = manifest.changelogUrls[languageCode.lowercase()]
                ?: manifest.changelogUrls[languageCode.substringBefore('-').lowercase()]
                ?: manifest.changelogUrls["en"]
                ?: manifest.changelogUrl
                ?: return null
            val markdown = fetchText(
                url = changelogUrl,
                accept = "text/markdown, text/plain, */*",
                maxBytes = MAX_CHANGELOG_BYTES,
                invalidException = { AppUpdateException(AppUpdateErrorReason.InvalidManifest, it) },
                networkException = { AppUpdateException(AppUpdateErrorReason.Network, it) }
            )
            extractVersionChangelog(markdown, manifest.versionName)
        }.getOrNull()?.takeIf { it.isNotBlank() }

    private suspend fun downloadVerifiedApk(
        update: AvailableAppUpdate,
        onProgress: (bytesRead: Long, totalBytes: Long?) -> Unit
    ): File {
        val target = File(updateCacheDir(), "${update.versionCode}-${update.artifact.fileName.safeFileName()}")
        val expectedHash = update.artifact.sha256.lowercase()
        if (target.isFile && target.sha256() == expectedHash) return target

        runCatching { target.delete() }
        cleanupStaleApks(keep = null)
        downloadFile(
            url = update.artifact.url,
            target = target,
            accept = APK_ACCEPT_HEADER,
            onProgress = onProgress
        )
        if (target.sha256() != expectedHash) {
            runCatching { target.delete() }
            throw AppUpdateException(AppUpdateErrorReason.ChecksumMismatch)
        }
        cleanupStaleApks(keep = target)
        return target
    }

    private suspend fun downloadFile(
        url: String,
        target: File,
        accept: String,
        onProgress: (bytesRead: Long, totalBytes: Long?) -> Unit
    ) {
        target.parentFile?.mkdirs()
        val temporary = File(target.parentFile, "${target.name}.download")
        runCatching { temporary.delete() }
        val connection = openConnection(url, accept).apply {
            readTimeout = DOWNLOAD_READ_TIMEOUT_MS
            useCaches = true
        }
        val cancellationHandle = coroutineContext[Job]?.invokeOnCompletion { cause ->
            if (cause is CancellationException) {
                runCatching { connection.disconnect() }
            }
        }
        try {
            val code = connection.responseCode
            if (code !in HTTP_SUCCESS_CODES) {
                throw AppUpdateException(AppUpdateErrorReason.DownloadFailed)
            }
            val totalBytes = connection.contentLengthLong.takeIf { it > 0L }
            var bytesRead = 0L
            connection.inputStream.use { input ->
                temporary.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = input.read(buffer)
                        coroutineContext.ensureActive()
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        bytesRead += read
                        onProgress(bytesRead, totalBytes)
                    }
                }
            }
            coroutineContext.ensureActive()
            if (!temporary.renameTo(target)) {
                throw AppUpdateException(AppUpdateErrorReason.DownloadFailed)
            }
        } catch (exception: IOException) {
            throw AppUpdateException(AppUpdateErrorReason.DownloadFailed, exception)
        } finally {
            cancellationHandle?.dispose()
            connection.disconnect()
            runCatching { temporary.delete() }
        }
    }

    private fun updateDownloadProgress(bytesRead: Long, totalBytes: Long?) {
        state.value = state.value.copy(
            downloadProgress = AppUpdateDownloadProgress(bytesRead, totalBytes)
        )
    }

    private fun verifyApkPackage(apk: File) {
        val archiveInfo = packageManager.getPackageArchiveInfoCompat(apk.absolutePath)
            ?: throw AppUpdateException(AppUpdateErrorReason.InvalidManifest)
        if (archiveInfo.packageName != appContext.packageName ||
            archiveInfo.longVersionCodeCompat() <= currentVersionCode()
        ) {
            throw AppUpdateException(AppUpdateErrorReason.InvalidManifest)
        }

        val installedSignatures = packageManager
            .getPackageInfoCompat(appContext.packageName)
            .signingCertificateBytes()
        val archiveSignatures = archiveInfo.signingCertificateBytes()
        if (installedSignatures.isEmpty() || installedSignatures != archiveSignatures) {
            throw AppUpdateException(AppUpdateErrorReason.SignatureMismatch)
        }
    }

    private fun launchInstaller(
        update: AvailableAppUpdate,
        apk: File
    ): AppUpdateInstallLaunchResult {
        if (!canRequestPackageInstalls()) {
            pendingPermissionUpdate = update
            val settingsIntent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${appContext.packageName}")
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            return runCatching { appContext.startActivity(settingsIntent) }
                .fold(
                    onSuccess = { AppUpdateInstallLaunchResult.PermissionRequired },
                    onFailure = {
                        pendingPermissionUpdate = null
                        AppUpdateInstallLaunchResult.InstallUnavailable
                    }
                )
        }

        val uri = FileProvider.getUriForFile(
            appContext,
            "${appContext.packageName}.fileprovider",
            apk
        )
        @Suppress("DEPRECATION")
        val installIntent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
            data = uri
            putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
            putExtra(Intent.EXTRA_RETURN_RESULT, false)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching { appContext.startActivity(installIntent) }
            .fold(
                onSuccess = { AppUpdateInstallLaunchResult.InstallerStarted },
                onFailure = { AppUpdateInstallLaunchResult.InstallUnavailable }
            )
    }

    private fun canRequestPackageInstalls(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || packageManager.canRequestPackageInstalls()

    private fun cleanupInstalledUpdateCache() {
        updateCacheDir().listFiles().orEmpty().forEach { file ->
            if (!file.isFile || !file.name.endsWith(".apk", ignoreCase = true)) {
                runCatching { file.deleteRecursively() }
                return@forEach
            }
            val archiveInfo = packageManager.getPackageArchiveInfoCompat(file.absolutePath)
            val shouldDelete = archiveInfo == null ||
                archiveInfo.packageName != appContext.packageName ||
                archiveInfo.longVersionCodeCompat() <= currentVersionCode()
            if (shouldDelete) {
                runCatching { file.delete() }
            }
        }
    }

    private fun cleanupStaleApks(keep: File?) {
        updateCacheDir().listFiles().orEmpty().forEach { file ->
            if (file != keep) runCatching { file.deleteRecursively() }
        }
    }

    private fun currentVersionName(): String =
        packageManager.getPackageInfoCompat(appContext.packageName).versionName ?: BuildConfig.VERSION_NAME

    private fun currentVersionCode(): Long =
        packageManager.getPackageInfoCompat(appContext.packageName).longVersionCodeCompat()

    private fun updateCacheDir(): File = File(appContext.cacheDir, UPDATE_CACHE_DIR)

    private fun Throwable.toUpdateErrorReason(): AppUpdateErrorReason =
        (this as? AppUpdateException)?.reason ?: AppUpdateErrorReason.Unknown
}

private fun fetchText(
    url: String,
    accept: String,
    maxBytes: Int,
    invalidException: (Throwable?) -> Exception,
    networkException: (Throwable?) -> Exception
): String {
    val parsedUrl = URL(url)
    if (parsedUrl.protocol != HTTPS_PROTOCOL) throw invalidException(null)
    val connection = openConnection(url, accept).apply {
        useCaches = false
    }
    try {
        val code = connection.responseCode
        if (code !in HTTP_SUCCESS_CODES) throw networkException(null)
        return connection.inputStream.use { input ->
            input.readLimitedText(maxBytes, invalidException)
        }
    } catch (exception: IOException) {
        throw networkException(exception)
    } finally {
        connection.disconnect()
    }
}

private fun openConnection(url: String, accept: String): HttpURLConnection =
    (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = CONNECT_TIMEOUT_MS
        readTimeout = READ_TIMEOUT_MS
        instanceFollowRedirects = true
        requestMethod = "GET"
        setRequestProperty("Accept", accept)
        setRequestProperty("Cache-Control", "no-cache")
        setRequestProperty("Pragma", "no-cache")
        setRequestProperty("User-Agent", USER_AGENT)
    }

private fun String.toUpdateManifest(): AppUpdateManifest {
    val json = JSONObject(this)
    val artifactJson = json.getJSONArray("artifacts")
    val artifacts = buildList {
        for (index in 0 until artifactJson.length()) {
            val item = artifactJson.getJSONObject(index)
            add(
                AppUpdateArtifact(
                    abi = item.getString("abi"),
                    fileName = item.getString("fileName"),
                    url = item.getString("url"),
                    sha256 = item.getString("sha256"),
                    sizeBytes = item.optionalLong("sizeBytes")
                )
            )
        }
    }
    val changelogUrlsJson = json.optJSONObject("changelogUrls")
    val changelogUrls = buildMap {
        if (changelogUrlsJson != null) {
            val keys = changelogUrlsJson.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                put(key.lowercase(), changelogUrlsJson.getString(key))
            }
        }
    }
    return AppUpdateManifest(
        schemaVersion = json.getInt("schemaVersion"),
        packageName = json.getString("packageName"),
        versionName = json.getString("versionName"),
        versionCode = json.getLong("versionCode"),
        minSdk = json.getInt("minSdk"),
        changelogUrl = json.optionalString("changelogUrl"),
        changelogUrls = changelogUrls,
        artifacts = artifacts
    )
}

private fun JSONObject.optionalString(name: String): String? =
    if (has(name) && !isNull(name)) optString(name).takeIf { it.isNotBlank() } else null

private fun JSONObject.optionalLong(name: String): Long? =
    if (has(name) && !isNull(name)) optLong(name).takeIf { it > 0L } else null

private fun extractVersionChangelog(markdown: String, versionName: String): String {
    val lines = markdown.lineSequence().toList()
    val versionHeading = Regex(
        "^##\\s+\\[?v?${Regex.escape(versionName)}(?:]|\\b).*$",
        option = RegexOption.IGNORE_CASE
    )
    val unreleasedHeading = Regex(
        pattern = "^##\\s+\\[?Unreleased(?:]|\\b).*$",
        option = RegexOption.IGNORE_CASE
    )
    return findFormattedSection(lines, versionHeading)
        ?: findFormattedSection(lines, unreleasedHeading)
        ?: formatChangelogSection(
            lines.dropWhile { line ->
                val trimmed = line.trim()
                trimmed.isEmpty() || trimmed.startsWith("#")
            }
        )
}

private fun findFormattedSection(lines: List<String>, headingPattern: Regex): String? {
    val startIndex = lines.indexOfFirst { line -> headingPattern.matches(line.trim()) }
    if (startIndex < 0) return null
    return formatChangelogSection(
        lines.drop(startIndex + 1)
            .takeWhile { line -> !itMatchesSecondLevelHeading(line) }
    ).takeIf { it.isNotBlank() }
}

private fun itMatchesSecondLevelHeading(line: String): Boolean =
    Regex("^##\\s+.*$").matches(line.trim())

private fun formatChangelogSection(lines: List<String>): String =
    lines.joinToString("\n") { line ->
        val trimmed = line.trim()
        when {
            trimmed.startsWith("###") -> trimmed.trimStart('#').trim()
            trimmed.startsWith("- ") -> "- ${trimmed.drop(2)}"
            trimmed.startsWith("* ") -> "- ${trimmed.drop(2)}"
            else -> trimmed
        }
    }
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()

private fun java.io.InputStream.readLimitedText(
    maxBytes: Int,
    tooLargeException: (Throwable?) -> Exception
): String {
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    val output = ByteArrayOutputStream()
    while (true) {
        val read = read(buffer)
        if (read <= 0) break
        if (output.size() + read > maxBytes) throw tooLargeException(null)
        output.write(buffer, 0, read)
    }
    return output.toString(Charsets.UTF_8.name())
}

private fun File.sha256(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    inputStream().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

private fun String.safeFileName(): String = substringAfterLast('/')
    .replace(Regex("[^A-Za-z0-9._-]"), "_")

private class AppUpdateException(
    val reason: AppUpdateErrorReason,
    cause: Throwable? = null
) : Exception(cause)

private enum class AppUpdateInstallLaunchResult {
    InstallerStarted,
    PermissionRequired,
    InstallUnavailable
}

private const val UPDATE_CACHE_DIR = "update-apks"
private const val UNIVERSAL_ABI = "universal"
private const val HTTPS_PROTOCOL = "https"
private const val CONNECT_TIMEOUT_MS = 10_000
private const val READ_TIMEOUT_MS = 10_000
private const val DOWNLOAD_READ_TIMEOUT_MS = 30_000
private const val MAX_MANIFEST_BYTES = 512 * 1024
private const val MAX_CHANGELOG_BYTES = 96 * 1024
private const val APK_ACCEPT_HEADER = "application/vnd.android.package-archive"
private val USER_AGENT = "SimpleFTP/${BuildConfig.VERSION_NAME}"
private val HTTP_SUCCESS_CODES = 200..299
private val SHA256_PATTERN = Regex("^[a-fA-F0-9]{64}$")
