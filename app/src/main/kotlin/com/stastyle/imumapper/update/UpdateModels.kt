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
)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class UpToDate(val currentVersion: String) : UpdateState
    data class Available(val release: ReleaseInfo) : UpdateState
    data class Downloading(val release: ReleaseInfo, val progress: Float) : UpdateState
    data class ReadyToInstall(val release: ReleaseInfo, val apk: File) : UpdateState
    data class Error(val message: String) : UpdateState
}

/** Fetches the newest release from GitHub. Implemented by the app-updater work item. */
interface UpdateChecker {
    /** Null when the repo has no release with an APK asset. Throws on network errors. */
    suspend fun fetchLatest(): ReleaseInfo?
}

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
}
