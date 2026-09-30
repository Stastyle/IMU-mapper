package com.stastyle.imumapper.ui.settings

import com.stastyle.imumapper.ui.common.StatusTone
import com.stastyle.imumapper.update.ReleaseInfo
import com.stastyle.imumapper.update.UpdateState
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The pill beside the Updates heading in Settings, for every updater state. */
class UpdatePillTest {

    private val release = ReleaseInfo(
        tagName = "v1.4.0",
        version = "1.4.0",
        name = "1.4.0",
        notes = "",
        apkUrl = "https://example.invalid/imu-mapper.apk",
        apkSizeBytes = 1L,
        apkSha256 = null,
        publishedAt = "",
        htmlUrl = "",
    )
    private val apk = File("update.apk")

    private val everyState = listOf(
        UpdateState.Idle,
        UpdateState.Checking,
        UpdateState.UpToDate("1.3.0"),
        UpdateState.Available(release),
        UpdateState.Downloading(release, 0.4f),
        UpdateState.ReadyToInstall(release, apk),
        UpdateState.NeedsInstallPermission(release, apk),
        UpdateState.Error("No network"),
        UpdateState.Error("Install failed", release),
    )

    @Test
    fun debugBuildWinsOverEveryState() {
        val reason = "Updates are unavailable for debug builds."
        val debug = UpdatePill("Debug build", StatusTone.Neutral)
        for (state in everyState) assertEquals(debug, UpdatePill.of(state, reason))
    }

    @Test
    fun idleShowsNothing() {
        // Idle also follows a quiet check or "Later", so it must not claim the app is up to date.
        assertNull(UpdatePill.of(UpdateState.Idle, null))
    }

    @Test
    fun checkingAndUpToDate() {
        assertEquals(UpdatePill("Checking…", StatusTone.Info), UpdatePill.of(UpdateState.Checking, null))
        assertEquals(UpdatePill("Up to date", StatusTone.Success), UpdatePill.of(UpdateState.UpToDate("1.3.0"), null))
    }

    @Test
    fun everyStateWithSomethingToInstallIsAnAvailableUpdate() {
        val available = UpdatePill("Update available", StatusTone.Info)
        assertEquals(available, UpdatePill.of(UpdateState.Available(release), null))
        assertEquals(available, UpdatePill.of(UpdateState.Downloading(release, 0.4f), null))
        assertEquals(available, UpdatePill.of(UpdateState.ReadyToInstall(release, apk), null))
        assertEquals(available, UpdatePill.of(UpdateState.NeedsInstallPermission(release, apk), null))
    }

    @Test
    fun errorsSayWhatFailed() {
        assertEquals(UpdatePill("Check failed", StatusTone.Error), UpdatePill.of(UpdateState.Error("No network"), null))
        assertEquals(
            UpdatePill("Update failed", StatusTone.Error),
            UpdatePill.of(UpdateState.Error("Install failed", release), null),
        )
    }
}
