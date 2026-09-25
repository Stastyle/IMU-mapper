package com.stastyle.imumapper.ui.record

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.opengl.GLSurfaceView
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stastyle.imumapper.capture.RecordingController
import com.stastyle.imumapper.capture.RecordingState
import com.stastyle.imumapper.capture.arcore.ArAvailability
import com.stastyle.imumapper.capture.arcore.ArCoreAvailability
import com.stastyle.imumapper.capture.arcore.ArGlRenderer
import com.stastyle.imumapper.capture.arcore.ArSessionManager
import com.stastyle.imumapper.capture.arcore.ArSessionState
import com.stastyle.imumapper.capture.arcore.ArStatus
import com.stastyle.imumapper.capture.arcore.displayRotation
import com.stastyle.imumapper.capture.arcore.rotationDegrees
import com.stastyle.imumapper.capture.modeLabel
import com.stastyle.imumapper.pipeline.core.TrackingState
import com.stastyle.imumapper.pipeline.core.TripMode

/**
 * Camera half of the recording screen for the ARCore modes: the live camera preview with the tracking
 * state, the torch switch and the keyframe counter on top. The ARCore session runs only while
 * [controller] is recording, the activity is resumed, CAMERA is granted and ARCore is installed; poses,
 * point clouds and keyframes go to the controller's log from [ArSessionManager].
 */
@Composable
fun ArSection(mode: TripMode, controller: RecordingController, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val availability = remember { ArCoreAvailability(context) }
    val manager = remember { ArSessionManager(context, controller) }
    val renderer = remember { ArGlRenderer(manager, context) }
    val glView = remember {
        GLSurfaceView(context).apply {
            setEGLContextClientVersion(2)
            setEGLConfigChooser(8, 8, 8, 8, 16, 0)
            // Keeps the camera texture alive across pauses so the preview resumes without a black frame.
            setPreserveEGLContextOnPause(true)
            setRenderer(renderer)
            setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY)
        }
    }

    val recordingState by controller.state.collectAsStateWithLifecycle()
    val recording = recordingState as? RecordingState.Recording
    val availabilityState by availability.state.collectAsStateWithLifecycle()
    val arState by manager.state.collectAsStateWithLifecycle()
    var cameraGranted by remember { mutableStateOf(hasCameraPermission(context)) }
    var resumed by remember { mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    var retry by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) { availability.check() }

    fun onLifecycleEvent(event: Lifecycle.Event) {
        if (event == Lifecycle.Event.ON_RESUME) {
            cameraGranted = hasCameraPermission(context)
            resumed = true
            val activity = context.findActivity()
            if (availability.state.value is ArAvailability.InstallRequested && activity != null) {
                // Back from the Play Store: this call completes the install and reports the result.
                availability.requestInstall(activity, false)
            } else {
                availability.check()
            }
        } else if (event == Lifecycle.Event.ON_PAUSE) {
            resumed = false
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event -> onLifecycleEvent(event) }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    DisposableEffect(Unit) {
        val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = Unit
            override fun onDisplayRemoved(displayId: Int) = Unit
            override fun onDisplayChanged(displayId: Int) {
                renderer.onDisplayChanged()
            }
        }
        displayManager?.registerDisplayListener(listener, Handler(Looper.getMainLooper()))
        onDispose { displayManager?.unregisterDisplayListener(listener) }
    }

    val ready = availabilityState is ArAvailability.Ready
    val failed = arState.status == ArStatus.FAILED
    val active = recording != null && cameraGranted && ready && resumed && !failed

    // The session follows the recording and the activity: open and resume while every condition holds,
    // pause as soon as one drops, close once the recording is gone. Effects run on the main thread in
    // order, so open/resume/pause never interleave. Order matters: the view is paused before the
    // session so the GL thread cannot call update() on a paused session.
    DisposableEffect(active, recording?.tripId, retry) {
        if (active && recording != null) {
            val opened = manager.open(mode, recording.photosDir, rotationDegrees(displayRotation(context)))
            if (opened && manager.resume()) glView.onResume()
        } else if (recording == null) {
            glView.onPause()
            manager.close()
        }
        onDispose {
            glView.onPause()
            manager.pause()
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            glView.onPause()
            manager.close()
        }
    }

    Box(modifier = modifier.background(Color.Black)) {
        if (ready && cameraGranted) {
            AndroidView(factory = { glView }, modifier = Modifier.fillMaxSize())
        }
        val state = availabilityState
        when {
            !cameraGranted -> CenterMessage("Camera permission is needed for ${modeLabel(mode)} mode.")
            state is ArAvailability.Checking -> CenterMessage("Checking ARCore…", progress = true)
            state is ArAvailability.NeedsInstall -> CenterMessage(
                "${state.detail}. Camera tracking is off until it is; the IMU log still records.",
                actionLabel = "Install ARCore",
                onAction = { context.findActivity()?.let { availability.requestInstall(it, true) } },
            )
            state is ArAvailability.InstallRequested -> CenterMessage("Finishing the ARCore install", progress = true)
            state is ArAvailability.Unsupported -> CenterMessage("${state.detail}. The IMU log still records.")
            state is ArAvailability.Error -> CenterMessage(
                state.message,
                actionLabel = "Retry",
                onAction = { availability.check() },
            )
            failed -> CenterMessage(
                arState.error ?: "ARCore failed",
                actionLabel = "Retry",
                onAction = {
                    manager.close()
                    retry++
                },
            )
            else -> ArOverlay(mode = mode, state = arState, onToggleTorch = { manager.setTorch(!arState.torchOn) })
        }
    }
}

@Composable
private fun ArOverlay(mode: TripMode, state: ArSessionState, onToggleTorch: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.SpaceBetween) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TrackingChip(state)
            if (state.torchSupported) {
                Surface(color = Color.Black.copy(alpha = 0.5f), shape = RoundedCornerShape(24.dp)) {
                    IconButton(onClick = onToggleTorch) {
                        Icon(
                            imageVector = if (state.torchOn) Icons.Filled.FlashOn else Icons.Filled.FlashOff,
                            contentDescription = if (state.torchOn) "Torch on" else "Torch off",
                            tint = if (state.torchOn) Color(0xFFFFD54F) else Color.White,
                        )
                    }
                }
            } else if (mode == TripMode.FLASHLIGHT) {
                Label("No torch on this camera")
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            if (mode == TripMode.ILLUMINATED) Label("Photos: ${state.keyframeCount}") else Label("")
            if (state.status == ArStatus.RUNNING && state.tracking != TrackingState.TRACKING) {
                Label("Path continues from steps")
            }
        }
    }
}

@Composable
private fun TrackingChip(state: ArSessionState) {
    val text: String
    val color: Color
    when {
        state.status != ArStatus.RUNNING -> {
            text = "CAMERA PAUSED"
            color = Color(0xFFBDBDBD)
        }
        state.tracking == TrackingState.TRACKING -> {
            text = "TRACKING"
            color = Color(0xFF81C784)
        }
        else -> {
            val detail = state.trackingDetail
            text = if (detail.isEmpty()) "PAUSED" else "PAUSED · $detail"
            color = Color(0xFFFFB74D)
        }
    }
    Surface(color = Color.Black.copy(alpha = 0.5f), shape = RoundedCornerShape(8.dp)) {
        Text(
            text = text,
            color = color,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun Label(text: String) {
    if (text.isEmpty()) return
    Surface(color = Color.Black.copy(alpha = 0.5f), shape = RoundedCornerShape(8.dp)) {
        Text(
            text = text,
            color = Color.White,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun CenterMessage(
    text: String,
    progress: Boolean = false,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (progress) {
            CircularProgressIndicator(modifier = Modifier.size(28.dp).padding(bottom = 4.dp), color = Color.White)
        }
        Text(
            text = text,
            color = Color.White,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        if (actionLabel != null && onAction != null) {
            Button(onClick = onAction, modifier = Modifier.padding(top = 12.dp)) { Text(actionLabel) }
        }
    }
}

private fun hasCameraPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

/** The activity behind a Compose context, needed by ARCore's install flow. */
private fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}
