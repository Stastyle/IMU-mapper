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

/** When the Record and Settings tabs carry their dots. */
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
        assertTrue(recordingBadge(recording))
        assertTrue(recordingBadge(RecordingState.Stopping))
        assertFalse(recordingBadge(RecordingState.Idle))
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
        assertEquals("Recording in progress", tabBadgeDescription(Routes.NEW_TRIP, true, updateAvailable = false))
        assertEquals("Update available", tabBadgeDescription(Routes.SETTINGS, false, updateAvailable = true))
        // Both at once: each tab speaks only its own dot.
        assertEquals("Recording in progress", tabBadgeDescription(Routes.NEW_TRIP, true, updateAvailable = true))
        assertEquals("Update available", tabBadgeDescription(Routes.SETTINGS, true, updateAvailable = true))
    }

    @Test
    fun noDotNoStateDescription() {
        for (route in listOf(Routes.TRIPS, Routes.NEW_TRIP, Routes.CALIBRATION, Routes.SETTINGS)) {
            assertNull(tabBadgeDescription(route, recordingActive = false, updateAvailable = false))
        }
        // Trips and Calibrate never carry a dot, whatever else is going on.
        for (route in listOf(Routes.TRIPS, Routes.CALIBRATION)) {
            assertNull(tabBadgeDescription(route, recordingActive = true, updateAvailable = true))
        }
    }
}
