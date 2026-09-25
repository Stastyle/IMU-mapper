package com.stastyle.imumapper

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.stastyle.imumapper.capture.RecordingController
import com.stastyle.imumapper.ui.nav.AppNavGraph
import com.stastyle.imumapper.ui.theme.ImuMapperTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ImuMapperTheme {
                AppNavGraph()
            }
        }
    }

    /** Volume keys mark a waypoint while recording, so a point can be logged without looking. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val isVolume = event.keyCode == KeyEvent.KEYCODE_VOLUME_UP || event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
        if (isVolume) {
            val controller = RecordingController.get(this)
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 && controller.onVolumeKey()) return true
            // Swallow the matching ACTION_UP and repeats too, so the volume panel does not pop up mid-trip.
            if (controller.isRecording) return true
        }
        return super.dispatchKeyEvent(event)
    }
}
