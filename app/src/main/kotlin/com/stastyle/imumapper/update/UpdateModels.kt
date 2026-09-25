package com.stastyle.imumapper.update

import java.io.File

/** A GitHub release that carries an APK. */
data class ReleaseInfo(
    /** e.g. "v1.2.3" */
    val tagName: String,
    /** e.g. "1.2.3" (tag without the leading v) */
    val version: String,
    val name: String,
    /** Release notes (markdown). */
    val notes: String,
    val apkUrl: String,
    val apkSizeBytes: Long,
    /** Lower-case hex sha256 when GitHub supplies an asset digest, else null. */
    val apkSha256: String?,
    val publishedAt: String,
    val htmlUrl: String,
) {
    /** File name of the APK asset on GitHub, which is also the name used for the local download. */
    val apkFileName: String get() = apkUrl.substringAfterLast('/').ifEmpty { "imu-mapper-v$version.apk" }
}

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class UpToDate(val currentVersion: String) : UpdateState
    data class Available(val release: ReleaseInfo) : UpdateState
    data class Downloading(val release: ReleaseInfo, val progress: Float) : UpdateState
    data class ReadyToInstall(val release: ReleaseInfo, val apk: File) : UpdateState

    /**
     * The APK is downloaded but Android has not yet allowed this app to install packages. The
     * system settings page was opened; the user comes back and taps Install again.
     */
    data class NeedsInstallPermission(val release: ReleaseInfo, val apk: File) : UpdateState

    /** [release] is the release whose download or install failed, so the UI can offer a retry. */
    data class Error(val message: String, val release: ReleaseInfo? = null) : UpdateState
}

/** Fetches the newest release from GitHub. Implemented by the app-updater work item. */
interface UpdateChecker {
    /**
     * Null only when the repository has no release at all (HTTP 404). A latest release without an
     * APK asset is an [UpdateCheckException], like network and API errors, so it is never mistaken
     * for "up to date".
     */
    suspend fun fetchLatest(): ReleaseInfo?
}

/** A failed update check with a message written for the user, not the developer. */
class UpdateCheckException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Semantic version comparison used by the updater. Ignores build metadata. */
object SemVer {
    data class Version(val major: Int, val minor: Int, val patch: Int, val preRelease: String?) : Comparable<Version> {
        override fun compareTo(other: Version): Int {
            if (major != other.major) return major.compareTo(other.major)
            if (minor != other.minor) return minor.compareTo(other.minor)
            if (patch != other.patch) return patch.compareTo(other.patch)
            // A pre-release sorts before the release of the same number.
            return when {
                preRelease == null && other.preRelease == null -> 0
                preRelease == null -> 1
                other.preRelease == null -> -1
                else -> preRelease.compareTo(other.preRelease)
            }
        }
    }

    /** Parses "1.2.3", "v1.2.3", "1.2.3-beta.1", "1.2" or "1.2.3-debug". Null if not a version. */
    fun parse(text: String): Version? {
        val core = text.trim().removePrefix("v").removePrefix("V").substringBefore('+')
        val pre = core.substringAfter('-', "").ifEmpty { null }
        val nums = core.substringBefore('-').split('.')
        if (nums.isEmpty() || nums.size > 3) return null
        val parts = nums.map { it.toIntOrNull() ?: return null }
        return Version(parts[0], parts.getOrElse(1) { 0 }, parts.getOrElse(2) { 0 }, pre)
    }

    /** True when [candidate] is strictly newer than [installed]. Unparseable input is never newer. */
    fun isNewer(candidate: String, installed: String): Boolean {
        val c = parse(candidate) ?: return false
        val i = parse(installed) ?: return false
        return c > i
    }

    /**
     * True for the `-debug` versionNameSuffix of the debug build type. That build has a different
     * applicationId, so a release APK could never update it: the installer would add a second app.
     */
    fun isDebugBuild(installed: String): Boolean = installed.trim().endsWith(DEBUG_SUFFIX, ignoreCase = true)

    private const val DEBUG_SUFFIX = "-debug"
}
