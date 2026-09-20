package com.example.ar

import com.google.ar.core.Pose
import kotlin.math.sqrt

/**
 * Holds 6-DoF camera pose telemetry (Position + Rotation) from ARCore.
 * Coordinates are relative to the ARCore world coordinate origin (meters).
 */
data class CameraPoseData(
    val translationX: Float = 0f,
    val translationY: Float = 0f,
    val translationZ: Float = 0f,
    val qx: Float = 0f,
    val qy: Float = 0f,
    val qz: Float = 0f,
    val qw: Float = 1f,
    val pitch: Float = 0f,
    val yaw: Float = 0f,
    val roll: Float = 0f,
    val hasValidPose: Boolean = false
) {
    /**
     * Distance in meters from the AR world coordinate origin.
     */
    val distanceFromOrigin: Float
        get() = sqrt(translationX * translationX + translationY * translationY + translationZ * translationZ)

    companion object {
        val INITIAL = CameraPoseData()

        /**
         * Converts quaternion components to Euler angles in degrees: (pitch, yaw, roll).
         * Aligned with Android device frame:
         * - Pitch (X-axis): Tilt forward / backward (tilt up/down)
         * - Yaw (Y-axis): Pan left / right
         * - Roll (Z-axis): Tilt left / right (portrait/landscape rotation)
         */
        fun quaternionToEuler(qx: Float, qy: Float, qz: Float, qw: Float): Triple<Float, Float, Float> {
            // Pitch (rotation around X-axis)
            val sinpCosp = 2.0 * (qw * qx + qy * qz)
            val cospCosp = 1.0 - 2.0 * (qx * qx + qy * qy)
            val pitchRad = Math.atan2(sinpCosp, cospCosp)

            // Yaw (rotation around Y-axis)
            val siny = 2.0 * (qw * qy - qz * qx)
            val yawRad = if (Math.abs(siny) >= 1.0) {
                Math.copySign(Math.PI / 2.0, siny)
            } else {
                Math.asin(siny)
            }

            // Roll (rotation around Z-axis)
            val sinrCosr = 2.0 * (qw * qz + qx * qy)
            val cosrCosr = 1.0 - 2.0 * (qy * qy + qz * qz)
            val rollRad = Math.atan2(sinrCosr, cosrCosr)

            return Triple(
                Math.toDegrees(pitchRad).toFloat(),
                Math.toDegrees(yawRad).toFloat(),
                Math.toDegrees(rollRad).toFloat()
            )
        }

        /**
         * Extracts position translation and rotation quaternion from an ARCore [Pose],
         * and computes human-readable Euler angles (Pitch, Yaw, Roll in degrees).
         */
        fun fromPose(pose: Pose): CameraPoseData {
            val tx = pose.tx()
            val ty = pose.ty()
            val tz = pose.tz()
            val qx = pose.qx()
            val qy = pose.qy()
            val qz = pose.qz()
            val qw = pose.qw()

            val (pitch, yaw, roll) = quaternionToEuler(qx, qy, qz, qw)

            return CameraPoseData(
                translationX = tx,
                translationY = ty,
                translationZ = tz,
                qx = qx,
                qy = qy,
                qz = qz,
                qw = qw,
                pitch = pitch,
                yaw = yaw,
                roll = roll,
                hasValidPose = true
            )
        }
    }
}
