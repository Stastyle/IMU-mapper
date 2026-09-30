package com.stastyle.imumapper.ui.nav

import com.stastyle.imumapper.capture.RecordingState
import com.stastyle.imumapper.pipeline.core.CarryPosition
import com.stastyle.imumapper.pipeline.core.TripMode
import com.stastyle.imumapper.update.ReleaseInfo
import com.stastyle.imumapper.update.UpdateState
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** When the Record and Settings tabs carry their dots, and what TalkBack says for them. */
class TabBadgesTest {

    private val release = ReleaseInfo(
        tagName = "v1.2.0",
        version = "1.2.0",
        name = "1.2.0",
        notes = "",
        apkUrl = "https://example.invalid/imu-mapper.apk",
        apkSizeBytes = 1L,
        apkSha256 = null,
        publishedAt = "",
        htmlUrl = "",
    )

    @Test
    fun recordTabWhileRecordingOrSaving() {
        val recording = RecordingState.Recording(
            tripId = 1L,
            mode = TripMode.POCKET,
            carryPosition = CarryPosition.POCKET,
            startedNs = 0L,
            elapsedNs = 0L,
            paused = true,
            stepCount = 0,
            annotationCount = 0,
            photosDir = File("photos"),
        )
        assertEquals("Recording in progress", recordingBadge(recording))
        // Still a dot, but the trip has ended and cannot be returned to, so TalkBack must not call it running.
        assertEquals("Saving the last trip", recordingBadge(RecordingState.Stopping))
        assertNull(recordingBadge(RecordingState.Idle))
    }

    @Test
    fun settingsTabInTheStatesTheUpdateBannerShows() {
        val apk = File("update.apk")
        assertTrue(updateBadge(UpdateState.Available(release)))
        assertTrue(updateBadge(UpdateState.Downloading(release, 0.5f)))
        assertTrue(updateBadge(UpdateState.ReadyToInstall(release, apk)))
        assertTrue(updateBadge(UpdateState.NeedsInstallPermission(release, apk)))

        assertFalse(updateBadge(UpdateState.Idle))
        assertFalse(updateBadge(UpdateState.Checking))
        assertFalse(updateBadge(UpdateState.UpToDate("1.2.0")))
        assertFalse(updateBadge(UpdateState.Error("offline")))
        assertFalse(updateBadge(UpdateState.Error("install failed", release)))
    }

    @Test
    fun eachDotIsSpokenOnItsOwnTab() {
        val running = "Recording in progress"
        val saving = "Saving the last trip"
        assertEquals(running, tabBadgeDescription(Routes.NEW_TRIP, running, updateAvailable = false))
        assertEquals(saving, tabBadgeDescription(Routes.NEW_TRIP, saving, updateAvailable = false))
        assertEquals("Update available", tabBadgeDescription(Routes.SETTINGS, null, updateAvailable = true))
        // Both at once: each tab speaks only its own dot.
        assertEquals(running, tabBadgeDescription(Routes.NEW_TRIP, running, updateAvailable = true))
        assertEquals("Update available", tabBadgeDescription(Routes.SETTINGS, running, updateAvailable = true))
    }

    @Test
    fun noDotNoStateDescription() {
        for (route in listOf(Routes.TRIPS, Routes.NEW_TRIP, Routes.CALIBRATION, Routes.SETTINGS)) {
            assertNull(tabBadgeDescription(route, recording = null, updateAvailable = false))
        }
        // Trips and Calibrate never carry a dot, whatever else is going on.
        for (route in listOf(Routes.TRIPS, Routes.CALIBRATION)) {
            assertNull(tabBadgeDescription(route, recording = "Recording in progress", updateAvailable = true))
        }
    }
}
