package com.stastyle.imumapper

import android.app.Application
import android.content.Context
import android.util.Log
import com.stastyle.imumapper.capture.RecordingController
import com.stastyle.imumapper.data.HeadingOffsetReset
import com.stastyle.imumapper.debug.CrashLog
import com.stastyle.imumapper.update.UpdateManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class ImuMapperApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // First, so a crash anywhere below is on record for the Debug screen.
        CrashLog.from(this).install { CrashLog.environment(this) }
        container = AppContainer(this)
        Notifications.createChannels(this)
        // Creating the controller runs the stale-trip sweep on its own background scope, so a trip left
        // in RECORDING by a killed process is closed out on every launch, whichever screen opens first.
        RecordingController.get(this)
        val updates = UpdateManager.get(this)
        updates.autoCheckIfDue()
        resetHeadingOffsetOnce(updates)
    }

    /** See [HeadingOffsetReset]: offsets saved before north came from the compass are meaningless now. */
    private fun resetHeadingOffsetOnce(updates: UpdateManager) {
        val prefs = updates.preferences
        container.applicationScope.launch {
            try {
                val reset = HeadingOffsetReset.runOnce(
                    calibration = container.calibrationRepository,
                    isDone = prefs::headingOffsetResetDone,
                    markDone = prefs::markHeadingOffsetResetDone,
                )
                if (reset) Log.i(TAG, "saved heading offset reset")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "heading offset reset failed; trying again at the next start", e)
            }
        }
    }

    companion object {
        private const val TAG = "ImuMapperApp"

        fun from(context: Context): ImuMapperApp = context.applicationContext as ImuMapperApp
        fun container(context: Context): AppContainer = from(context).container
    }
}
