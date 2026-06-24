package com.cybercat.simpleftp

internal data class AppUpdateState(
    val currentVersionName: String,
    val currentVersionCode: Long,
    val isChecking: Boolean = false,
    val isDownloading: Boolean = false,
    val downloadProgress: AppUpdateDownloadProgress? = null,
    val availableUpdate: AvailableAppUpdate? = null,
    val status: AppUpdateStatus? = null
)

internal data class AppUpdateDownloadProgress(
    val bytesRead: Long,
    val totalBytes: Long?
) {
    val percent: Int? = totalBytes
        ?.takeIf { it > 0L }
        ?.let { total -> ((bytesRead * 100L) / total).coerceIn(0L, 100L).toInt() }
}

internal data class AvailableAppUpdate(
    val versionName: String,
    val versionCode: Long,
    val artifact: AppUpdateArtifact,
    val changelog: String?
)

internal data class AppUpdateManifest(
    val schemaVersion: Int,
    val packageName: String,
    val versionName: String,
    val versionCode: Long,
    val minSdk: Int,
    val changelogUrl: String?,
    val changelogUrls: Map<String, String>,
    val artifacts: List<AppUpdateArtifact>
)

internal data class AppUpdateArtifact(
    val abi: String,
    val fileName: String,
    val url: String,
    val sha256: String,
    val sizeBytes: Long?
)

internal sealed interface AppUpdateStatus {
    data object Downloading : AppUpdateStatus
    data object PermissionRequired : AppUpdateStatus
    data object ReadyToInstall : AppUpdateStatus
    data class Error(val reason: AppUpdateErrorReason) : AppUpdateStatus
}

internal enum class AppUpdateErrorReason {
    Network,
    InvalidManifest,
    NoCompatibleArtifact,
    DownloadFailed,
    ChecksumMismatch,
    SignatureMismatch,
    InstallUnavailable,
    Unknown
}
