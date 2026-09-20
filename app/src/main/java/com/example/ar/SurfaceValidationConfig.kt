package com.example.ar

/**
 * Configurable thresholds and stability parameters for validating raw ARCore planes
 * into reliable user-facing placement surfaces.
 *
 * All thresholds are centrally configurable rather than hardcoded throughout the codebase.
 */
data class SurfaceValidationConfig(
    // Minimum usable area (m²)
    // Minimum 0.20 m² (~45cm x 45cm) to comfortably host a virtual panel
    val minHorizontalAreaSquareMeters: Float = 0.20f,
    val minVerticalAreaSquareMeters: Float = 0.20f,

    // Minimum physical extents (width and length/height in meters)
    val minHorizontalExtentX: Float = 0.35f,
    val minHorizontalExtentZ: Float = 0.35f,
    val minVerticalExtentX: Float = 0.35f,
    val minVerticalExtentZ: Float = 0.35f,

    // Stability thresholds over time
    // Must be observed across at least 12 AR frames (~400ms at 30fps)
    val minStabilityFrames: Int = 12,
    // Maximum cumulative center translation drift allowed per frame to be considered stable (meters)
    val maxCenterJitterMeters: Float = 0.12f,

    // Geometry validation
    // Minimum polygon vertices (ARCore plane boundary must have at least 4 vertices)
    val minPolygonVertices: Int = 4,
    // Maximum aspect ratio (extentMax / extentMin). Rejects thin strips, moldings, wires, ledges
    val maxAspectRatio: Float = 6.0f,
    // Minimum ratio of actual 2D polygon area to bounding box (extentX * extentZ)
    val minPolygonToBoundingBoxRatio: Float = 0.25f,

    // Raycast Reticle validation
    val minRayHitDistanceMeters: Float = 0.30f,
    val maxRayHitDistanceMeters: Float = 4.00f,

    // Duplicate/fragment cluster tolerance
    // Planes whose normals deviate by less than 15 deg and perpendicular distance is < 0.12m
    val normalAngleToleranceDegrees: Float = 15.0f,
    val planeCoplanarDistanceToleranceMeters: Float = 0.12f
) {
    companion object {
        val DEFAULT = SurfaceValidationConfig()
    }
}
