package com.example.ar

import com.google.ar.core.TrackingState

/**
 * Validated, high-confidence physical surface suitable for virtual object/panel placement.
 * Distinct from raw ARCore planes.
 */
data class ValidatedSurface(
    val id: String,
    val planeHashCode: Int,
    val type: PlaneType,
    val confidence: SurfaceConfidence,
    val trackingState: TrackingState,
    val centerTranslationX: Float,
    val centerTranslationY: Float,
    val centerTranslationZ: Float,
    val normalX: Float,
    val normalY: Float,
    val normalZ: Float,
    val extentX: Float,
    val extentZ: Float,
    val polygonAreaSquareMeters: Float,
    val boundingAreaSquareMeters: Float,
    val trackingFramesCount: Int,
    val centerJitterMeters: Float,
    val distanceFromCamera: Float,
    val isUnderReticle: Boolean = false
) {
    val isPlacementReady: Boolean
        get() = confidence == SurfaceConfidence.VALID && trackingState == TrackingState.TRACKING
}
