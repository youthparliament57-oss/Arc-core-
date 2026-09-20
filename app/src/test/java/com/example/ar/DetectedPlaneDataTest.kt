package com.example.ar

import com.google.ar.core.TrackingState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DetectedPlaneDataTest {

    @Test
    fun planeType_propertiesAreAccurate() {
        assertTrue(PlaneType.HORIZONTAL_UPWARD.isHorizontal)
        assertTrue(PlaneType.HORIZONTAL_DOWNWARD.isHorizontal)
        assertFalse(PlaneType.VERTICAL.isHorizontal)
        assertEquals("Horizontal (Floor/Table)", PlaneType.HORIZONTAL_UPWARD.displayName)
        assertEquals("Vertical (Wall/Door)", PlaneType.VERTICAL.displayName)
    }

    @Test
    fun planesTelemetry_emptyStateIsCorrect() {
        val empty = PlanesTelemetry.EMPTY
        assertEquals(0, empty.activePlaneCount)
        assertEquals(0, empty.horizontalPlaneCount)
        assertEquals(0, empty.verticalPlaneCount)
        assertFalse(empty.hasDetectedUsableSurface)
        assertFalse(empty.isAimingAtSurface)
        assertEquals(null, empty.nearestPlaneDistance)
    }

    @Test
    fun planesTelemetry_computesMetricsCorrectly() {
        val plane1 = DetectedPlaneData(
            id = "plane-1",
            type = PlaneType.HORIZONTAL_UPWARD,
            trackingState = TrackingState.TRACKING,
            centerTranslationX = 0f,
            centerTranslationY = -0.8f,
            centerTranslationZ = -1.5f,
            extentX = 1.2f,
            extentZ = 0.8f,
            distanceFromCamera = 1.7f
        )

        val plane2 = DetectedPlaneData(
            id = "plane-2",
            type = PlaneType.VERTICAL,
            trackingState = TrackingState.TRACKING,
            centerTranslationX = 1.0f,
            centerTranslationY = 0f,
            centerTranslationZ = -2.0f,
            extentX = 1.5f,
            extentZ = 2.2f,
            distanceFromCamera = 2.24f
        )

        val telemetry = PlanesTelemetry(
            planes = listOf(plane1, plane2),
            activePlaneCount = 2,
            horizontalPlaneCount = 1,
            verticalPlaneCount = 1,
            hasDetectedUsableSurface = true,
            isAimingAtSurface = true,
            nearestPlaneDistance = 1.7f
        )

        assertEquals(2, telemetry.activePlaneCount)
        assertEquals(1, telemetry.horizontalPlaneCount)
        assertEquals(1, telemetry.verticalPlaneCount)
        assertTrue(telemetry.hasDetectedUsableSurface)
        assertTrue(telemetry.isAimingAtSurface)
        assertEquals(1.7f, telemetry.nearestPlaneDistance ?: 0f, 1e-4f)
    }
}
