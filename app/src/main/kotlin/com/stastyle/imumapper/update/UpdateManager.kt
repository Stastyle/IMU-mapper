package com.stastyle.imumapper.update

import android.content.Context
import android.util.Log
import com.stastyle.imumapper.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The updater's state machine, shared by the Settings screen and the trip-list banner. One per
 * process (see [get]) so a download started from the banner is still visible in Settings and
 * survives navigation. [state] is a StateFlow, so progress written from the download thread is safe.
 */
class UpdateManager(
    context: Context,
    private val checker: UpdateChecker = GitHubUpdateChecker(),
    private val downloader: UpdateDownloader = UpdateDownloader(context),
    private val installer: UpdateInstaller = UpdateInstaller(context),
    val preferences: UpdatePreferences = UpdatePreferences(context),
    val installedVersion: String = BuildConfig.VERSION_NAME,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) {

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    /** The releases page on GitHub, for the "open release page" link when no release is known. */
    val releasesPageUrl: String = "https://github.com/${BuildConfig.GITHUB_REPO}/releases"

    private var checkJob: Job? = null
    private var downloadJob: Job? = null

    /** Settings button: always reports the outcome, including "up to date" and errors. */
    fun checkNow() {
        if (checkJob?.isActive == true) return
        checkJob = scope.launch { runCheck(manual = true) }
    }

    /**
     * App start: at most once per 24 h, quiet on failure and about a version the user already
     * dismissed. Never interrupts a check, download or pending install that is in progress.
     */
    fun autoCheckIfDue() {
        if (checkJob?.isActive == true) return
        checkJob = scope.launch {
            val current = _state.value
            if (current !is UpdateState.Idle && current !is UpdateState.UpToDate) return@launch
            val last = runCatching { preferences.lastCheckEpochMs() }.getOrDefault(0L)
            val now = System.currentTimeMillis()
            if (now - last in 0 until AUTO_CHECK_INTERVAL_MS) return@launch
            runCheck(manual = false)
        }
    }

    fun download() {
        if (downloadJob?.isActive == true) return
        val release = when (val s = _state.value) {
            is UpdateState.Available -> s.release
            is UpdateState.Error -> s.release ?: return
            else -> return
        }
        downloadJob = scope.launch {
            _state.value = UpdateState.Downloading(release, 0f)
            try {
                val apk = downloader.download(release) { fraction ->
                    _state.value = UpdateState.Downloading(release, fraction)
                }
                _state.value = UpdateState.ReadyToInstall(release, apk)
            } catch (e: CancellationException) {
                // dismiss() cancels and then sets Idle itself; only a plain cancel falls back to the offer.
                if (_state.value is UpdateState.Downloading) _state.value = UpdateState.Available(release)
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "download failed", e)
                _state.value = UpdateState.Error(e.message ?: "Download failed", release)
            }
        }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
    }

    /** Starts the system installer, or the "install unknown apps" settings page on the first try. */
    fun install() {
        val (release, apk) = when (val s = _state.value) {
            is UpdateState.ReadyToInstall -> s.release to s.apk
            is UpdateState.NeedsInstallPermission -> s.release to s.apk
            else -> return
        }
        _state.value = when (val result = installer.install(apk)) {
            UpdateInstaller.Result.Launched -> UpdateState.ReadyToInstall(release, apk)
            UpdateInstaller.Result.NeedsPermission -> UpdateState.NeedsInstallPermission(release, apk)
            is UpdateInstaller.Result.Failed -> UpdateState.Error(result.message, release)
        }
    }

    /** "Later": hides the offer and keeps the automatic check quiet about this version. */
    fun dismiss() {
        val tag: String? = when (val s = _state.value) {
            is UpdateState.Available -> s.release.tagName
            is UpdateState.ReadyToInstall -> s.release.tagName
            is UpdateState.NeedsInstallPermission -> s.release.tagName
            is UpdateState.Downloading -> {
                cancelDownload()
                s.release.tagName
            }
            else -> null
        }
        _state.value = UpdateState.Idle
        if (tag != null) {
            scope.launch {
                runCatching { preferences.setLastSeenTag(tag) }.onFailure { Log.w(TAG, "prefs write failed", it) }
            }
        }
    }

    /** Error state: repeats whatever failed, the download when a release is known, else the check. */
    fun retry() {
        val s = _state.value as? UpdateState.Error ?: return
        if (s.release != null) download() else checkNow()
    }

    private suspend fun runCheck(manual: Boolean) {
        val current = _state.value
        if (current is UpdateState.Downloading || current is UpdateState.Checking) return
        if (manual) _state.value = UpdateState.Checking
        val release: ReleaseInfo? = try {
            checker.fetchLatest()
        } catch (e: CancellationException) {
            if (manual) _state.value = current
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "update check failed", e)
            if (manual) _state.value = UpdateState.Error(e.message ?: "Update check failed")
            return
        }
        runCatching { preferences.recordCheck(System.currentTimeMillis()) }
            .onFailure { Log.w(TAG, "prefs write failed", it) }
        if (release != null && SemVer.isNewer(release.version, installedVersion)) {
            val dismissed = if (manual) null else runCatching { preferences.lastSeenTag() }.getOrNull()
            if (manual || release.tagName != dismissed) {
                _state.value = UpdateState.Available(release)
            }
        } else {
            // The installed build is the newest, so any APK from an earlier update is just clutter.
            withContext(Dispatchers.IO) {
                runCatching { downloader.deleteStaleApks() }.onFailure { Log.w(TAG, "stale apk cleanup failed", it) }
            }
            if (manual) _state.value = UpdateState.UpToDate(installedVersion)
        }
    }

    companion object {
        private const val TAG = "UpdateManager"
        private const val AUTO_CHECK_INTERVAL_MS = 24L * 60L * 60L * 1000L

        @Volatile
        private var instance: UpdateManager? = null

        /** The process-wide instance, created on first use. */
        fun get(context: Context): UpdateManager =
            instance ?: synchronized(this) {
                instance ?: UpdateManager(context.applicationContext).also { instance = it }
            }
    }
}
