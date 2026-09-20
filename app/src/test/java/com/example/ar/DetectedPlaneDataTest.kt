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
            rawPlaneCount = 2,
            hasValidatedSurface = false,
            reticleTargetState = ReticleTargetState.SEARCHING
        )

        assertEquals(2, telemetry.rawPlaneCount)
        assertEquals(0, telemetry.validatedSurfaceCount)
        assertFalse(telemetry.hasValidatedSurface)
        assertEquals(ReticleTargetState.SEARCHING, telemetry.reticleTargetState)
    }

    @Test
    fun validatedSurface_placementReadiness() {
        val validSurface = ValidatedSurface(
            id = "surface-1",
            planeHashCode = 101,
            type = PlaneType.HORIZONTAL_UPWARD,
            confidence = SurfaceConfidence.VALID,
            trackingState = TrackingState.TRACKING,
            centerTranslationX = 0f,
            centerTranslationY = -0.5f,
            centerTranslationZ = -1.2f,
            normalX = 0f,
            normalY = 1f,
            normalZ = 0f,
            extentX = 0.8f,
            extentZ = 0.6f,
            polygonAreaSquareMeters = 0.48f,
            boundingAreaSquareMeters = 0.48f,
            trackingFramesCount = 20,
            centerJitterMeters = 0.01f,
            distanceFromCamera = 1.3f
        )

        assertTrue(validSurface.isPlacementReady)

        val candidateSurface = validSurface.copy(confidence = SurfaceConfidence.CANDIDATE)
        assertFalse(candidateSurface.isPlacementReady)

        val pausedSurface = validSurface.copy(trackingState = TrackingState.PAUSED)
        assertFalse(pausedSurface.isPlacementReady)
    }

    @Test
    fun surfaceValidationConfig_defaultValuesAreStrictAndSafe() {
        val config = SurfaceValidationConfig()
        assertTrue("Min horizontal area must be at least 0.10m²", config.minHorizontalAreaSquareMeters >= 0.10f)
        assertTrue("Min vertical area must be at least 0.10m²", config.minVerticalAreaSquareMeters >= 0.10f)
        assertTrue("Min horizontal extent must be at least 0.20m", config.minHorizontalExtentX >= 0.20f)
        assertTrue("Min stability frames required", config.minStabilityFrames >= 5)
        assertTrue("Max center jitter must be limited", config.maxCenterJitterMeters <= 0.15f)
    }
}
