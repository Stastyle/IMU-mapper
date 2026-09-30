package com.stastyle.imumapper.ui.newtrip

import com.stastyle.imumapper.capture.RecordingState
import com.stastyle.imumapper.pipeline.core.CarryPosition
import com.stastyle.imumapper.pipeline.core.TripMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** What the Record tab offers while idle, recording and saving, and which mode it preselects. */
@OptIn(ExperimentalCoroutinesApi::class)
class NewTripViewModelTest {

    private val recording = MutableStateFlow<RecordingState>(RecordingState.Idle)

    /** The "Default trip mode" setting; it has not loaded until something is emitted. */
    private val defaultMode = MutableSharedFlow<TripMode>(replay = 1)

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun running(mode: TripMode, elapsedS: Long = 5, paused: Boolean = false) = RecordingState.Recording(
        tripId = 3L,
        mode = mode,
        carryPosition = CarryPosition.POCKET,
        startedNs = 1_000L,
        elapsedNs = elapsedS * 1_000_000_000L,
        paused = paused,
        stepCount = 8,
        annotationCount = 0,
        photosDir = File("photos"),
    )

    /** A view model whose state is collected, as the screen does, so the shared state stays live. */
    private fun TestScope.subscribed(): NewTripViewModel {
        val vm = NewTripViewModel(recording, defaultMode)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.ui.collect {} }
        return vm
    }

    @Test
    fun recordingHidesTheChoiceAndFollowsTheClock() = runTest {
        defaultMode.emit(TripMode.POCKET)
        recording.value = running(TripMode.FLASHLIGHT)
        val vm = subscribed()
        // The running trip's mode, not the POCKET default, and no mode choice at all.
        assertEquals(NewTripUiState.Recording(running(TripMode.FLASHLIGHT)), vm.ui.value)

        recording.value = running(TripMode.FLASHLIGHT, elapsedS = 6, paused = true)
        assertEquals(NewTripUiState.Recording(running(TripMode.FLASHLIGHT, elapsedS = 6, paused = true)), vm.ui.value)
    }

    @Test
    fun savingOffersNothing() = runTest {
        defaultMode.emit(TripMode.POCKET)
        recording.value = running(TripMode.POCKET)
        val vm = subscribed()
        recording.value = RecordingState.Stopping
        assertEquals(NewTripUiState.Stopping, vm.ui.value)

        recording.value = RecordingState.Idle
        assertEquals(NewTripUiState.Choose(TripMode.POCKET), vm.ui.value)
    }

    @Test
    fun noModeUntilTheDefaultLoads() = runTest {
        val vm = subscribed()
        // Continue waits rather than start in a placeholder mode.
        assertEquals(NewTripUiState.Choose(null), vm.ui.value)

        defaultMode.emit(TripMode.ILLUMINATED)
        assertEquals(NewTripUiState.Choose(TripMode.ILLUMINATED), vm.ui.value)
    }

    @Test
    fun theStateBeforeAnyCollectorIsHonestToo() {
        // The first frame, before the screen's collector starts, already hides the choice.
        recording.value = running(TripMode.ILLUMINATED)
        val whileRecording = NewTripViewModel(recording, defaultMode).ui.value
        assertEquals(NewTripUiState.Recording(running(TripMode.ILLUMINATED)), whileRecording)

        recording.value = RecordingState.Idle
        assertEquals(NewTripUiState.Choose(null), NewTripViewModel(recording, defaultMode).ui.value)
    }

    @Test
    fun defaultIsFollowedUntilTheUserPicks() = runTest {
        defaultMode.emit(TripMode.POCKET)
        val vm = subscribed()
        assertEquals(NewTripUiState.Choose(TripMode.POCKET), vm.ui.value)

        // Changed in Settings while the page was open.
        defaultMode.emit(TripMode.FLASHLIGHT)
        assertEquals(NewTripUiState.Choose(TripMode.FLASHLIGHT), vm.ui.value)

        vm.selectMode(TripMode.ILLUMINATED)
        assertEquals(NewTripUiState.Choose(TripMode.ILLUMINATED), vm.ui.value)

        // A later default no longer overrides the pick.
        defaultMode.emit(TripMode.POCKET)
        assertEquals(NewTripUiState.Choose(TripMode.ILLUMINATED), vm.ui.value)
    }

    @Test
    fun comingBackToThePageKeepsTheDefault() = runTest {
        // DataStore hands each new collector its value only after a hop to its own thread. A source that
        // emits a moment after every collection starts stands in for it; a replaying flow would hand the
        // value over at once and hide a gap.
        val slowDefault = flow {
            delay(1)
            emit(TripMode.FLASHLIGHT)
        }
        val vm = NewTripViewModel(recording, slowDefault)
        val screen = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.ui.collect {} }
        advanceTimeBy(2)
        assertEquals(NewTripUiState.Choose(TripMode.FLASHLIGHT), vm.ui.value)

        // The tab is left for longer than the view model keeps its sources running, then opened again.
        screen.cancel()
        advanceTimeBy(5_001)
        val seen = mutableListOf<NewTripUiState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.ui.collect { seen += it } }
        advanceTimeBy(2)

        // Never Choose(null) on the way: no card selected and Continue off, for a moment.
        assertEquals(listOf<NewTripUiState>(NewTripUiState.Choose(TripMode.FLASHLIGHT)), seen)
    }

    @Test
    fun pickSurvivesARecordingInBetween() = runTest {
        defaultMode.emit(TripMode.POCKET)
        val vm = subscribed()
        vm.selectMode(TripMode.FLASHLIGHT)

        recording.value = running(TripMode.POCKET)
        assertEquals(NewTripUiState.Recording(running(TripMode.POCKET)), vm.ui.value)
        recording.value = RecordingState.Stopping
        recording.value = RecordingState.Idle
        assertEquals(NewTripUiState.Choose(TripMode.FLASHLIGHT), vm.ui.value)
    }
}
