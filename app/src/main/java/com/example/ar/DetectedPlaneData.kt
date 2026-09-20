package com.example.ar

import com.google.ar.core.Plane
import com.google.ar.core.TrackingState
import kotlin.math.sqrt

/**
 * Categorization of physical planes detected by ARCore.
 */
enum class PlaneType {
    HORIZONTAL_UPWARD,   // Floor, desk, tabletop
    HORIZONTAL_DOWNWARD, // Ceiling
    VERTICAL,            // Wall, door, vertical barrier
    UNKNOWN;

    val displayName: String
        get() = when (this) {
            HORIZONTAL_UPWARD -> "Horizontal (Floor/Table)"
            HORIZONTAL_DOWNWARD -> "Horizontal (Ceiling)"
            VERTICAL -> "Vertical (Wall/Door)"
            UNKNOWN -> "Surface"
        }

    val isHorizontal: Boolean
        get() = this == HORIZONTAL_UPWARD || this == HORIZONTAL_DOWNWARD

    companion object {
        fun fromArCoreType(type: Plane.Type): PlaneType {
            return when (type) {
                Plane.Type.HORIZONTAL_UPWARD_FACING -> HORIZONTAL_UPWARD
                Plane.Type.HORIZONTAL_DOWNWARD_FACING -> HORIZONTAL_DOWNWARD
                Plane.Type.VERTICAL -> VERTICAL
                else -> UNKNOWN
            }
        }
    }
}

/**
 * Immutable telemetry model representing a physical plane detected by ARCore.
 */
data class DetectedPlaneData(
    val id: String,
    val type: PlaneType,
    val trackingState: TrackingState,
    val centerTranslationX: Float,
    val centerTranslationY: Float,
    val centerTranslationZ: Float,
    val extentX: Float,
    val extentZ: Float,
    val areaSquareMeters: Float = extentX * extentZ,
    val distanceFromCamera: Float = 0f
) {
    companion object {
        fun fromArCorePlane(plane: Plane, cameraPose: CameraPoseData?): DetectedPlaneData {
            val centerPose = plane.centerPose
            val cx = centerPose.tx()
            val cy = centerPose.ty()
            val cz = centerPose.tz()

            val dist = if (cameraPose != null) {
                val dx = cx - cameraPose.translationX
                val dy = cy - cameraPose.translationY
                val dz = cz - cameraPose.translationZ
                sqrt(dx * dx + dy * dy + dz * dz)
            } else {
                sqrt(cx * cx + cy * cy + cz * cz)
            }

            return DetectedPlaneData(
                id = plane.hashCode().toString(),
                type = PlaneType.fromArCoreType(plane.type),
                trackingState = plane.trackingState,
                centerTranslationX = cx,
                centerTranslationY = cy,
                centerTranslationZ = cz,
                extentX = plane.extentX,
                extentZ = plane.extentZ,
                areaSquareMeters = plane.extentX * plane.extentZ,
                distanceFromCamera = dist
            )
        }
    }
}

/**
 * Aggregated telemetry of all surfaces currently tracked by the AR session,
 * distinguishing raw ARCore planes from validated, placement-ready surfaces.
 */
data class PlanesTelemetry(
    val planes: List<DetectedPlaneData> = emptyList(),
    val activePlaneCount: Int = 0,
    val horizontalPlaneCount: Int = 0,
    val verticalPlaneCount: Int = 0,
    val hasDetectedUsableSurface: Boolean = false,
    val isAimingAtSurface: Boolean = false,
    val nearestPlaneDistance: Float? = null,
    val rawPlanes: List<DetectedPlaneData> = planes,
    val rawPlaneCount: Int = activePlaneCount,
    val validatedSurfaces: List<ValidatedSurface> = emptyList(),
    val validatedSurfaceCount: Int = 0,
    val validatedHorizontalCount: Int = 0,
    val validatedVerticalCount: Int = 0,
    val hasValidatedSurface: Boolean = false,
    val reticleTargetState: ReticleTargetState = ReticleTargetState.SEARCHING,
    val targetedSurface: ValidatedSurface? = null,
    val targetedDistance: Float? = null,
    val allEvaluatedSurfaces: List<ValidatedSurface> = emptyList()
) {
    companion object {
        val EMPTY = PlanesTelemetry()
    }
}
