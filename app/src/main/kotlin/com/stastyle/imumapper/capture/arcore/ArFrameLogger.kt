package com.stastyle.imumapper.capture.arcore

import android.media.Image
import android.os.SystemClock
import android.util.Log
import com.google.ar.core.Frame
import com.google.ar.core.Pose
import com.google.ar.core.TrackingFailureReason
import com.google.ar.core.TrackingState as ArTrackingState
import com.stastyle.imumapper.capture.RecordingController
import com.stastyle.imumapper.pipeline.core.EventKind
import com.stastyle.imumapper.pipeline.core.EventRecord
import com.stastyle.imumapper.pipeline.core.KeyframeSample
import com.stastyle.imumapper.pipeline.core.PointCloudSample
import com.stastyle.imumapper.pipeline.core.PoseSample
import com.stastyle.imumapper.pipeline.core.TrackingState
import com.stastyle.imumapper.pipeline.core.Vec3
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

/**
 * Turns each new ARCore frame into log records: a [PoseSample] per camera frame, TRACKING_LOST /
 * TRACKING_REGAINED events on transitions, a decimated [PointCloudSample] every few frames while tracking
 * and, when a [KeyframeSaver] is given (illuminated mode), a photo keyframe by [KeyframePolicy].
 *
 * One logger serves one ARCore `Session`, and every session starts its own world frame, so the log marks
 * the session boundaries: a TRACKING_LOST is written before the session's first pose (and, through
 * [onSessionStopped], when the camera stops while tracking). A pause/resume of the same session therefore
 * shows as LOST ... REGAINED, while a new session shows as a second TRACKING_LOST with no
 * TRACKING_REGAINED in between: the pipeline must re-yaw the run that follows instead of assuming the old
 * frame. The first session of a trip starts with the same TRACKING_LOST; it precedes any pose and is harmless.
 *
 * [onFrame] runs on the GL thread right after `Session.update()`; it only copies data out of the frame.
 * Writes go through [writeExecutor], JPEG work through the saver's own thread.
 */
class ArFrameLogger(
    private val controller: RecordingController,
    private val writeExecutor: Executor,
    private val saver: KeyframeSaver?,
    private val onTracking: (TrackingState, TrackingFailureReason) -> Unit,
    private val onKeyframeSaved: (Int) -> Unit,
    /** Highest keyframe index already in the trip's photo directory; numbering continues after it. */
    lastKeyframeIndex: Int = 0,
    /** Keyframes already on disk from earlier sessions of the trip, counted into [onKeyframeSaved]. */
    keyframesOnDisk: Int = 0,
) {
    private var started = false
    private var lastFrameTimestamp = -1L
    private var frameCount = 0L
    private var wasTracking = false
    private var reported = false
    private var lastReason: TrackingFailureReason = TrackingFailureReason.NONE
    private var lastCloudTimestamp = -1L
    private val policy = KeyframePolicy()
    private var keyframeIndex = lastKeyframeIndex
    private var keyframesSaved = keyframesOnDisk

    /**
     * The camera stopped ([ArSessionManager.pause] or close): tracking cannot go on, so the log gets the
     * TRACKING_LOST that no frame will deliver. Called under the session lock, never concurrently with
     * [onFrame]. The next frame reports its tracking state afresh.
     */
    fun onSessionStopped() {
        reported = false
        if (!wasTracking) return
        wasTracking = false
        val nowNs = SystemClock.elapsedRealtimeNanos()
        submit { controller.write(EventRecord(nowNs, EventKind.TRACKING_LOST)) }
    }

    fun onFrame(frame: Frame) {
        val frameTs = frame.timestamp
        // With LATEST_CAMERA_IMAGE update() returns at display rate; the same camera image must not be
        // logged twice. Timestamp 0 means no image yet.
        if (frameTs == 0L || frameTs == lastFrameTimestamp) return
        lastFrameTimestamp = frameTs
        val nowNs = SystemClock.elapsedRealtimeNanos()

        val camera = frame.camera
        val pose = camera.pose
        val tracking = when (camera.trackingState) {
            ArTrackingState.TRACKING -> TrackingState.TRACKING
            ArTrackingState.PAUSED -> TrackingState.PAUSED
            else -> TrackingState.STOPPED
        }
        val isTracking = tracking == TrackingState.TRACKING
        val reason = camera.trackingFailureReason
        val sample = PoseSample(
            tNs = nowNs,
            frameTimestampNs = frameTs,
            tx = pose.tx(), ty = pose.ty(), tz = pose.tz(),
            qx = pose.qx(), qy = pose.qy(), qz = pose.qz(), qw = pose.qw(),
            tracking = tracking,
            failureReason = if (isTracking) 0 else reason.ordinal,
        )
        val events = ArrayList<EventKind>(2)
        if (!started) {
            // Session boundary marker, before the first pose of this session (see the class comment).
            started = true
            events.add(EventKind.TRACKING_LOST)
        }
        val transition = isTracking != wasTracking
        if (transition) events.add(if (isTracking) EventKind.TRACKING_REGAINED else EventKind.TRACKING_LOST)
        wasTracking = isTracking
        frameCount++

        val cloud = if (isTracking && frameCount % POINT_CLOUD_EVERY_N_FRAMES == 0L) readPointCloud(frame) else null
        val pointCloud = cloud?.let { PointCloudSample(nowNs, it) }
        submit {
            for (event in events) controller.write(EventRecord(nowNs, event))
            controller.write(sample)
            if (pointCloud != null) controller.write(pointCloud)
        }

        if (!reported || transition || reason != lastReason) {
            reported = true
            lastReason = reason
            onTracking(tracking, reason)
        }
        if (saver != null && isTracking) maybeKeyframe(frame, nowNs, pose, saver)
    }

    private fun readPointCloud(frame: Frame): FloatArray? {
        val cloud = try {
            frame.acquirePointCloud()
        } catch (e: Exception) {
            Log.w(TAG, "acquirePointCloud failed", e)
            return null
        }
        try {
            // ARCore hands back the previous cloud when no new one exists yet; do not log it twice.
            if (cloud.timestamp == lastCloudTimestamp) return null
            lastCloudTimestamp = cloud.timestamp
            val points = cloud.points.duplicate()
            val n = points.remaining() / 4
            if (n == 0) return null
            val all = FloatArray(n * 4)
            points.get(all)
            return decimatePointCloud(all, MAX_POINTS_PER_CLOUD)
        } finally {
            cloud.release()
        }
    }

    private fun maybeKeyframe(frame: Frame, nowNs: Long, pose: Pose, saver: KeyframeSaver) {
        val position = Vec3.of(pose.tx(), pose.ty(), pose.tz())
        val heading = arHeadingRad(pose.qx(), pose.qy(), pose.qz(), pose.qw())
        if (!policy.shouldCapture(nowNs, position, heading)) return
        // A save still running means this keyframe is skipped; the policy is not advanced so the next
        // frame tries again as soon as the saver is free.
        if (!saver.isIdle) return
        val image: Image = try {
            frame.acquireCameraImage()
        } catch (e: Exception) {
            // NotYetAvailable / DeadlineExceeded / ResourceExhausted: try again on a later frame.
            Log.d(TAG, "acquireCameraImage: ${e.javaClass.simpleName}")
            return
        }
        val index = keyframeIndex + 1
        val fileName = keyframeFileName(index)
        val tx = pose.tx()
        val ty = pose.ty()
        val tz = pose.tz()
        val qx = pose.qx()
        val qy = pose.qy()
        val qz = pose.qz()
        val qw = pose.qw()
        val accepted = saver.trySave(image, fileName) { ok ->
            if (ok) {
                // Written once the file exists so the log never points at a photo that is not there.
                controller.write(KeyframeSample(nowNs, fileName, tx, ty, tz, qx, qy, qz, qw))
                val count = synchronized(this) { ++keyframesSaved }
                onKeyframeSaved(count)
            }
        }
        if (!accepted) return
        keyframeIndex = index
        policy.markCaptured(nowNs, position, heading)
    }

    private fun submit(task: () -> Unit) {
        try {
            writeExecutor.execute { task() }
        } catch (e: RejectedExecutionException) {
            // The session is closing; whatever is still queued is written, the rest is dropped.
        }
    }

    private companion object {
        const val TAG = "ArFrameLogger"
    }
}
