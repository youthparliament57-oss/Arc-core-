package com.example.ar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraPoseDataTest {

    @Test
    fun initialPose_hasZeroPositionAndIdentityRotation() {
        val initial = CameraPoseData.INITIAL
        assertEquals(0f, initial.translationX, 1e-5f)
        assertEquals(0f, initial.translationY, 1e-5f)
        assertEquals(0f, initial.translationZ, 1e-5f)
        assertEquals(0f, initial.qx, 1e-5f)
        assertEquals(0f, initial.qy, 1e-5f)
        assertEquals(0f, initial.qz, 1e-5f)
        assertEquals(1f, initial.qw, 1e-5f)
        assertEquals(0f, initial.pitch, 1e-5f)
        assertEquals(0f, initial.yaw, 1e-5f)
        assertEquals(0f, initial.roll, 1e-5f)
        assertEquals(0f, initial.distanceFromOrigin, 1e-5f)
    }

    @Test
    fun customPose_calculatesDistanceFromOriginCorrectly() {
        val pose = CameraPoseData(
            translationX = 3f,
            translationY = 4f,
            translationZ = 0f,
            qx = 0f,
            qy = 0f,
            qz = 0f,
            qw = 1f,
            pitch = 0f,
            yaw = 0f,
            roll = 0f
        )
        assertEquals(5f, pose.distanceFromOrigin, 1e-4f)
    }

    @Test
    fun eulerAngles_convertsPitchCorrectly() {
        // 90 degree rotation around X axis: qx = sin(45°), qw = cos(45°)
        val halfAngle = Math.toRadians(45.0)
        val qx = Math.sin(halfAngle).toFloat()
        val qw = Math.cos(halfAngle).toFloat()

        val (pitch, yaw, roll) = CameraPoseData.quaternionToEuler(qx, 0f, 0f, qw)
        assertEquals(90f, pitch, 0.5f)
        assertEquals(0f, yaw, 0.5f)
        assertEquals(0f, roll, 0.5f)
    }

    @Test
    fun eulerAngles_convertsYawCorrectly() {
        // 90 degree rotation around Y axis: qy = sin(45°), qw = cos(45°)
        val halfAngle = Math.toRadians(45.0)
        val qy = Math.sin(halfAngle).toFloat()
        val qw = Math.cos(halfAngle).toFloat()

        val (pitch, yaw, roll) = CameraPoseData.quaternionToEuler(0f, qy, 0f, qw)
        assertEquals(0f, pitch, 0.5f)
        assertEquals(90f, yaw, 0.5f)
        assertEquals(0f, roll, 0.5f)
    }
}
