package com.stastyle.imumapper.pipeline.vio

import com.stastyle.imumapper.pipeline.core.Quat
import com.stastyle.imumapper.pipeline.core.Vec3
import kotlin.math.PI

/**
 * ARCore's world frame is right-handed with +Y up and -Z forward at session start; the pipeline
 * world frame is ENU (x east, y north, z up). The axis permutation east = x, north = -z, up = y is
 * a proper rotation (+90 degrees about X), so it can be applied as a quaternion to positions and
 * composed with camera orientations alike. Nothing outside this package sees ARCore coordinates.
 */
object ArCoreFrame {
    /** Rotation taking ARCore world vectors to ENU before any yaw alignment. */
    val AR_TO_ENU: Quat = Quat.fromAxisAngle(Vec3.UNIT_X, PI / 2.0)

    /**
     * Yaw rotation (about ENU up) that turns a direction of compass heading [fromHeadingRad] into
     * one of heading [toHeadingRad]. Headings are clockwise from north while [Quat.yaw] is
     * counter-clockwise seen from above, hence the sign.
     */
    fun yawBetween(fromHeadingRad: Double, toHeadingRad: Double): Quat = Quat.yaw(-(toHeadingRad - fromHeadingRad))
}

/**
 * One rigid transform from ARCore world coordinates to the pipeline's ENU frame:
 * p_enu = yaw * (AR_TO_ENU * p_ar) + offset, q_enu = yaw * AR_TO_ENU * q_ar.
 * A new transform is created for every stretch of continuous tracking (see [VioProcessor]).
 */
class FrameTransform(val yaw: Quat, val offset: Vec3) {
    private val rotation: Quat = (yaw * ArCoreFrame.AR_TO_ENU).normalized()

    fun position(pAr: Vec3): Vec3 = rotation.rotate(pAr) + offset

    fun orientation(qAr: Quat): Quat = (rotation * qAr).normalized()

    /** Compass heading of the camera's viewing direction (-Z of the camera frame) in ENU. */
    fun cameraHeadingRad(qAr: Quat): Double = orientation(qAr).cameraHeadingRad()

    /** Same yaw, with the offset chosen so that [pAr] lands on [target]. */
    fun anchoredAt(pAr: Vec3, target: Vec3): FrameTransform {
        val unshifted = FrameTransform(yaw, Vec3.ZERO)
        return FrameTransform(yaw, target - unshifted.position(pAr))
    }

    /** New yaw so that the camera heading of [qAr] becomes [targetHeadingRad], with [pAr] landing on [target]. */
    fun realigned(qAr: Quat, targetHeadingRad: Double, pAr: Vec3, target: Vec3): FrameTransform {
        val rawHeading = FrameTransform(Quat.IDENTITY, Vec3.ZERO).cameraHeadingRad(qAr)
        val yaw = ArCoreFrame.yawBetween(rawHeading, targetHeadingRad)
        return FrameTransform(yaw, Vec3.ZERO).anchoredAt(pAr, target)
    }

    companion object {
        /** The frame conversion alone: no yaw alignment, ARCore origin stays the origin. */
        val IDENTITY: FrameTransform = FrameTransform(Quat.IDENTITY, Vec3.ZERO)
    }
}
