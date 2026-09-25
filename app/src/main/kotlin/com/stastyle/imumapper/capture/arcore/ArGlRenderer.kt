package com.stastyle.imumapper.capture.arcore

import android.content.Context
import android.hardware.display.DisplayManager
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.util.Log
import android.view.Display
import android.view.Surface
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/** Viewport size and display rotation, the inputs of `Session.setDisplayGeometry`. */
data class DisplayGeometry(val rotation: Int, val width: Int, val height: Int)

/**
 * GLSurfaceView renderer for the camera preview. Each frame it asks [ArSessionManager] for the latest
 * ARCore frame (which runs `Session.update()` and the logging) and draws its camera image. Nothing else
 * is rendered: the path is shown in the viewer after the trip, not here.
 */
class ArGlRenderer(private val manager: ArSessionManager, context: Context) : GLSurfaceView.Renderer {

    private val appContext: Context = context.applicationContext
    private val background = BackgroundRenderer()
    private var width = 0
    private var height = 0

    @Volatile
    private var viewportChanged = false

    /** Call when the display rotation changes; the geometry is re-sent on the next frame. */
    fun onDisplayChanged() {
        viewportChanged = true
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.05f, 0.05f, 0.05f, 1f)
        // A new surface means a new EGL context (unless preserved): old texture names are invalid.
        background.invalidate()
        try {
            background.createOnGlThread()
        } catch (e: Exception) {
            Log.e(TAG, "background renderer setup failed", e)
        }
        viewportChanged = true
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        GLES20.glViewport(0, 0, w, h)
        width = w
        height = h
        viewportChanged = true
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        if (background.textureId < 0) return
        var geometry: DisplayGeometry? = null
        if (viewportChanged) {
            viewportChanged = false
            geometry = DisplayGeometry(displayRotation(appContext), width, height)
        }
        val frame = manager.onGlFrame(background.textureId, geometry) ?: return
        try {
            background.draw(frame)
        } catch (e: Exception) {
            Log.w(TAG, "draw failed", e)
        }
    }

    private companion object {
        const val TAG = "ArGlRenderer"
    }
}

/** Current rotation of the default display as a `Surface.ROTATION_*` constant; safe from any thread. */
fun displayRotation(context: Context): Int {
    val manager = context.applicationContext.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
        ?: return Surface.ROTATION_0
    val display: Display = manager.getDisplay(Display.DEFAULT_DISPLAY) ?: return Surface.ROTATION_0
    return display.rotation
}

/** `Surface.ROTATION_*` to degrees. */
fun rotationDegrees(rotation: Int): Int = when (rotation) {
    Surface.ROTATION_90 -> 90
    Surface.ROTATION_180 -> 180
    Surface.ROTATION_270 -> 270
    else -> 0
}
