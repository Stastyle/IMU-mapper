package com.stastyle.imumapper.update

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Environment
import androidx.core.content.ContextCompat
import com.stastyle.imumapper.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** A failed or unverifiable download, with a message written for the user. */
class UpdateDownloadException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Downloads a release APK through the system [DownloadManager] into this app's external files
 * `Download/` directory (the path the manifest's FileProvider exposes) and verifies it. The system
 * downloader is used because it survives the app going to the background and shows its own
 * notification; we only watch its progress.
 */
class UpdateDownloader(context: Context, private val appVersion: String = BuildConfig.VERSION_NAME) {

    private val context: Context = context.applicationContext
    private val manager: DownloadManager? = this.context.getSystemService(DownloadManager::class.java)

    private data class Snapshot(val status: Int, val reason: Int, val downloaded: Long, val total: Long) {
        val terminal: Boolean
            get() = status == DownloadManager.STATUS_SUCCESSFUL || status == DownloadManager.STATUS_FAILED ||
                status == STATUS_MISSING
    }

    /** Where the APK of [release] lands, or null when external storage is unavailable. */
    fun apkFile(release: ReleaseInfo): File? = downloadsDir()?.let { File(it, release.apkFileName) }

    /**
     * Downloads and verifies the APK. [onProgress] gets a fraction in 0..1 from the calling
     * dispatcher (IO). Throws [UpdateDownloadException] on any failure, after removing every trace
     * of the partial file, and cleans up the same way when the coroutine is cancelled.
     */
    suspend fun download(release: ReleaseInfo, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val manager = manager ?: throw UpdateDownloadException("The system download service is not available")
        val uri = Uri.parse(release.apkUrl)
        if (uri.scheme != "https") throw UpdateDownloadException("The release asset is not served over HTTPS")
        val target = apkFile(release) ?: throw UpdateDownloadException("External storage is not available")
        removeExisting(target)

        val request = DownloadManager.Request(uri)
            .setTitle("IMU Mapper ${release.version}")
            .setDescription("Downloading update")
            .setMimeType(APK_MIME)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)
            .addRequestHeader("User-Agent", "IMU-Mapper/$appVersion")
        try {
            request.setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, target.name)
        } catch (e: IllegalStateException) {
            throw UpdateDownloadException("External storage is not available", e)
        }
        val id = manager.enqueue(request)
        try {
            val end = snapshots(id)
                .onEach { s -> if (!s.terminal) onProgress(fraction(s, release.apkSizeBytes)) }
                .first { it.terminal }
            when (end.status) {
                DownloadManager.STATUS_SUCCESSFUL -> Unit
                DownloadManager.STATUS_FAILED -> throw UpdateDownloadException(describeReason(end.reason))
                else -> throw UpdateDownloadException("The download was cancelled")
            }
            verify(target, release)
            onProgress(1f)
            target
        } catch (t: Throwable) {
            // remove() also deletes the file the manager wrote, so nothing partial is left behind.
            runCatching { manager.remove(id) }
            runCatching { target.delete() }
            throw t
        }
    }

    /** Progress of a download in 0..1, polled twice a second until the flow is cancelled. */
    fun progress(downloadId: Long): Flow<Float> = snapshots(downloadId).map { fraction(it, 0L) }

    /**
     * Deletes APKs left in the downloads directory by earlier updates, except [keepFileName].
     * Called once the app is known to be up to date, i.e. after an update was installed.
     */
    fun deleteStaleApks(keepFileName: String? = null) {
        val files = downloadsDir()?.listFiles() ?: return
        for (file in files) {
            if (file.isFile && file.name.endsWith(".apk", ignoreCase = true) && file.name != keepFileName) {
                removeExisting(file)
            }
        }
    }

    /**
     * Emits the manager's view of the download: every [POLL_MS] by polling, and immediately when the
     * completion broadcast arrives. Both paths query the manager, so the broadcast only shortens the
     * wait and the poll covers devices that deliver the broadcast late or not at all.
     */
    private fun snapshots(id: Long): Flow<Snapshot> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                if (intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L) == id) trySend(query(id))
            }
        }
        // The broadcast comes from the system, so on API 33+ the receiver has to be exported.
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            ContextCompat.RECEIVER_EXPORTED,
        )
        val poller = launch {
            while (isActive) {
                send(query(id))
                delay(POLL_MS)
            }
        }
        awaitClose {
            poller.cancel()
            runCatching { context.unregisterReceiver(receiver) }
        }
    }

    private fun query(id: Long): Snapshot {
        val manager = manager ?: return MISSING
        val cursor = manager.query(DownloadManager.Query().setFilterById(id)) ?: return MISSING
        cursor.use { c ->
            if (!c.moveToFirst()) return MISSING
            return Snapshot(
                status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)),
                reason = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)),
                downloaded = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)),
                total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)),
            )
        }
    }

    private fun fraction(s: Snapshot, expectedBytes: Long): Float {
        val total = if (s.total > 0) s.total else expectedBytes
        if (total <= 0 || s.downloaded <= 0) return 0f
        return (s.downloaded.toDouble() / total.toDouble()).toFloat().coerceIn(0f, 1f)
    }

    private fun verify(file: File, release: ReleaseInfo) {
        if (!file.isFile) throw UpdateDownloadException("The download finished but the file is missing")
        if (release.apkSizeBytes > 0 && file.length() != release.apkSizeBytes) {
            throw UpdateDownloadException(
                "The downloaded file is ${file.length()} bytes but the release says ${release.apkSizeBytes}",
            )
        }
        val expected = release.apkSha256 ?: return
        val actual = Checksums.sha256Hex(file)
        if (!actual.equals(expected, ignoreCase = true)) {
            throw UpdateDownloadException("The downloaded file's checksum does not match the release")
        }
    }

    /**
     * Forgets earlier downloads of the same file and deletes it, so the manager writes to the exact
     * name we hand to the FileProvider instead of inventing "name-1.apk".
     */
    private fun removeExisting(target: File) {
        val manager = manager
        if (manager != null) {
            val stale = ArrayList<Long>()
            val cursor = runCatching { manager.query(DownloadManager.Query()) }.getOrNull()
            cursor?.use { c ->
                val idCol = c.getColumnIndex(DownloadManager.COLUMN_ID)
                val uriCol = c.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)
                if (idCol >= 0 && uriCol >= 0) {
                    while (c.moveToNext()) {
                        val local = c.getString(uriCol) ?: continue
                        if (local.endsWith("/" + target.name)) stale += c.getLong(idCol)
                    }
                }
            }
            for (id in stale) runCatching { manager.remove(id) }
        }
        if (target.exists()) target.delete()
    }

    private fun downloadsDir(): File? = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)

    private fun describeReason(reason: Int): String = when (reason) {
        DownloadManager.ERROR_INSUFFICIENT_SPACE -> "Not enough storage space for the update"
        DownloadManager.ERROR_DEVICE_NOT_FOUND -> "External storage is not available"
        DownloadManager.ERROR_FILE_ERROR -> "The update file could not be written"
        DownloadManager.ERROR_FILE_ALREADY_EXISTS -> "An update file with the same name already exists"
        DownloadManager.ERROR_HTTP_DATA_ERROR -> "The connection dropped while downloading"
        DownloadManager.ERROR_CANNOT_RESUME -> "The download could not be resumed"
        DownloadManager.ERROR_TOO_MANY_REDIRECTS -> "Too many redirects while downloading"
        DownloadManager.ERROR_UNHANDLED_HTTP_CODE -> "GitHub answered with an unexpected HTTP status"
        DownloadManager.ERROR_UNKNOWN -> "The download failed"
        else -> if (reason in 100..599) "GitHub answered HTTP $reason" else "The download failed (code $reason)"
    }

    private companion object {
        const val APK_MIME = "application/vnd.android.package-archive"
        const val POLL_MS = 500L

        /** Status used when the manager no longer knows the id (user removed it from the tray). */
        const val STATUS_MISSING = -1
        val MISSING = Snapshot(STATUS_MISSING, 0, 0L, 0L)
    }
}
