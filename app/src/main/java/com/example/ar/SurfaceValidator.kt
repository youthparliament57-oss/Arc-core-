package com.example.ar

import com.google.ar.core.Camera
import com.google.ar.core.HitResult
import com.google.ar.core.Plane
import com.google.ar.core.Pose
import com.google.ar.core.TrackingState
import java.nio.FloatBuffer
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Multi-stage pipeline that processes raw ARCore planes and extracts
 * reliable, stable, and validated placement surfaces.
 *
 * Pipeline Stages:
 * 1. Tracking State Validation & Subsumption Filter (prune subsumed & non-tracking planes)
 * 2. Orientation Validation (reject ceilings, inverted planes, unknown orientations)
 * 3. Temporal Stability Tracking (track consecutive frames and center translation jitter)
 * 4. Geometry & Polygon Validation (Shoelace area, aspect ratio, polygon-to-box ratio)
 * 5. Minimum Usable Area & Extents Thresholding
 * 6. Co-planar Fragment & Duplicate Wall Handling (suppress redundant plane fragments)
 * 7. Center Reticle Intersection & Targeting State Evaluation
 */
class SurfaceValidator(
    val config: SurfaceValidationConfig = SurfaceValidationConfig.DEFAULT
) {

    // Internal tracking history for each plane (keyed by plane hashCode)
    private val planeHistories = HashMap<Int, PlaneTrackerState>()

    // Reusable arrays to eliminate per-frame allocations on render thread
    private val normalVector = FloatArray(3)
    private val tempVector = FloatArray(3)

    /**
     * Internal state maintained across frames for a tracked plane.
     */
    private class PlaneTrackerState(
        val planeHashCode: Int,
        var consecutiveTrackingFrames: Int = 0,
        var lastCenterX: Float = 0f,
        var lastCenterY: Float = 0f,
        var lastCenterZ: Float = 0f,
        var lastExtentX: Float = 0f,
        var lastExtentZ: Float = 0f,
        var averageCenterJitter: Float = 0f,
        var currentConfidence: SurfaceConfidence = SurfaceConfidence.CANDIDATE
    )

    /**
     * Processes all raw ARCore planes for the current frame, evaluates stability and geometry,
     * checks reticle intersection against candidates, and returns comprehensive telemetry.
     */
    fun processFrame(
        allPlanes: Collection<Plane>,
        cameraPoseData: CameraPoseData?,
        camera: Camera,
        centerReticleHit: HitResult?
    ): PlanesTelemetry {
        val currentActiveHashCodes = HashSet<Int>()
        val rawPlanesList = ArrayList<DetectedPlaneData>()
        val evaluatedSurfaces = ArrayList<ValidatedSurface>()

        val cameraTx = cameraPoseData?.translationX ?: 0f
        val cameraTy = cameraPoseData?.translationY ?: 0f
        val cameraTz = cameraPoseData?.translationZ ?: 0f

        // 1. First pass: Filter subsumed/stopped planes and update raw list
        for (plane in allPlanes) {
            val hashCode = plane.hashCode()

            // Subsumption check: Obsolete planes merged into parent must be pruned
            if (plane.subsumedBy != null) {
                planeHistories.remove(hashCode)
                continue
            }

            if (plane.trackingState != TrackingState.TRACKING) {
                // Plane not actively tracking
                val history = planeHistories[hashCode]
                if (history != null) {
                    history.consecutiveTrackingFrames = 0
                    history.currentConfidence = SurfaceConfidence.LOST
                }
                continue
            }

            currentActiveHashCodes.add(hashCode)
            val rawData = DetectedPlaneData.fromArCorePlane(plane, cameraPoseData)
            rawPlanesList.add(rawData)

            // 2. Orientation Validation
            val planeType = PlaneType.fromArCoreType(plane.type)
            if (planeType != PlaneType.HORIZONTAL_UPWARD && planeType != PlaneType.VERTICAL) {
                // Reject ceilings (HORIZONTAL_DOWNWARD) and UNKNOWN
                continue
            }

            // Extract surface normal from plane CenterPose (in ARCore, +Y is plane normal)
            extractPlaneNormal(plane.centerPose, normalVector)
            val nx = normalVector[0]
            val ny = normalVector[1]
            val nz = normalVector[2]

            // Verify normal consistency:
            // Horizontal upward normal should have high positive Y (|ny| > 0.85)
            // Vertical wall normal should be perpendicular to gravity (|ny| < 0.35)
            if (planeType == PlaneType.HORIZONTAL_UPWARD && ny < 0.80f) {
                continue
            }
            if (planeType == PlaneType.VERTICAL && abs(ny) > 0.35f) {
                continue
            }

            // 3. Temporal Stability Tracking
            val cx = plane.centerPose.tx()
            val cy = plane.centerPose.ty()
            val cz = plane.centerPose.tz()
            val extentX = plane.extentX
            val extentZ = plane.extentZ
            val boundingArea = extentX * extentZ

            var history = planeHistories[hashCode]
            if (history == null) {
                history = PlaneTrackerState(
                    planeHashCode = hashCode,
                    consecutiveTrackingFrames = 1,
                    lastCenterX = cx,
                    lastCenterY = cy,
                    lastCenterZ = cz,
                    lastExtentX = extentX,
                    lastExtentZ = extentZ,
                    averageCenterJitter = 0f,
                    currentConfidence = SurfaceConfidence.CANDIDATE
                )
                planeHistories[hashCode] = history
            } else {
                history.consecutiveTrackingFrames++
                val dx = cx - history.lastCenterX
                val dy = cy - history.lastCenterY
                val dz = cz - history.lastCenterZ
                val frameJitter = sqrt(dx * dx + dy * dy + dz * dz)

                // Exponential moving average for center jitter
                history.averageCenterJitter = (history.averageCenterJitter * 0.7f) + (frameJitter * 0.3f)
                history.lastCenterX = cx
                history.lastCenterY = cy
                history.lastCenterZ = cz
                history.lastExtentX = extentX
                history.lastExtentZ = extentZ
            }

            // 4. Geometry & Polygon Validation
            val polygon = plane.polygon
            val polygonPoints = if (polygon != null) polygon.remaining() / 2 else 0
            val polygonArea = if (polygon != null) calculatePolygonArea(polygon) else 0f

            val minExtent = min(extentX, extentZ)
            val maxExtent = max(extentX, extentZ)
            val aspectRatio = if (minExtent > 0.01f) maxExtent / minExtent else 100f
            val polygonToBoxRatio = if (boundingArea > 0.001f) polygonArea / boundingArea else 0f

            // 5. Evaluate Confidence Classification
            val confidence = evaluatePlaneConfidence(
                planeType = planeType,
                extentX = extentX,
                extentZ = extentZ,
                polygonPoints = polygonPoints,
                polygonArea = polygonArea,
                aspectRatio = aspectRatio,
                polygonToBoxRatio = polygonToBoxRatio,
                history = history
            )
            history.currentConfidence = confidence

            val distFromCamera = sqrt(
                (cx - cameraTx) * (cx - cameraTx) +
                (cy - cameraTy) * (cy - cameraTy) +
                (cz - cameraTz) * (cz - cameraTz)
            )

            evaluatedSurfaces.add(
                ValidatedSurface(
                    id = hashCode.toString(),
                    planeHashCode = hashCode,
                    type = planeType,
                    confidence = confidence,
                    trackingState = plane.trackingState,
                    centerTranslationX = cx,
                    centerTranslationY = cy,
                    centerTranslationZ = cz,
                    normalX = nx,
                    normalY = ny,
                    normalZ = nz,
                    extentX = extentX,
                    extentZ = extentZ,
                    polygonAreaSquareMeters = polygonArea,
                    boundingAreaSquareMeters = boundingArea,
                    trackingFramesCount = history.consecutiveTrackingFrames,
                    centerJitterMeters = history.averageCenterJitter,
                    distanceFromCamera = distFromCamera,
                    isUnderReticle = false
                )
            )
        }

        // Clean up stale plane histories no longer present in ARCore
        planeHistories.keys.retainAll(currentActiveHashCodes)

        // 6. Fragment & Duplicate Co-planar Suppression
        val validatedSurfaces = suppressDuplicateFragments(evaluatedSurfaces)

        // 7. Center Reticle Target State Analysis
        var reticleState = ReticleTargetState.SEARCHING
        var targetedSurface: ValidatedSurface? = null
        var targetedDistance: Float? = null

        if (centerReticleHit != null) {
            val hitTrackable = centerReticleHit.trackable
            val hitDist = centerReticleHit.distance

            if (hitTrackable is Plane && hitDist in config.minRayHitDistanceMeters..config.maxRayHitDistanceMeters) {
                val hitHashCode = hitTrackable.hashCode()

                // Check if reticle hit is within the actual polygon boundary
                val isInsidePolygon = try {
                    hitTrackable.isPoseInPolygon(centerReticleHit.hitPose)
                } catch (e: Exception) {
                    true
                }

                if (isInsidePolygon) {
                    // Match against our validated surfaces
                    val matchingValidated = validatedSurfaces.find { it.planeHashCode == hitHashCode }
                    if (matchingValidated != null && matchingValidated.confidence == SurfaceConfidence.VALID) {
                        reticleState = ReticleTargetState.VALID_SURFACE
                        targetedSurface = matchingValidated.copy(isUnderReticle = true)
                        targetedDistance = hitDist
                    } else {
                        // Check if it's an active candidate or stabilizing surface
                        val matchingAny = evaluatedSurfaces.find { it.planeHashCode == hitHashCode }
                        if (matchingAny != null && (matchingAny.confidence == SurfaceConfidence.STABILIZING || matchingAny.confidence == SurfaceConfidence.CANDIDATE)) {
                            reticleState = ReticleTargetState.ANALYZING_SURFACE
                            targetedSurface = matchingAny.copy(isUnderReticle = true)
                            targetedDistance = hitDist
                        }
                    }
                }
            }
        }

        // If a validated surface is targeted, update it in the list
        val finalValidatedSurfaces = if (targetedSurface != null && reticleState == ReticleTargetState.VALID_SURFACE) {
            validatedSurfaces.map { surface ->
                if (surface.planeHashCode == targetedSurface.planeHashCode) {
                    surface.copy(isUnderReticle = true)
                } else {
                    surface.copy(isUnderReticle = false)
                }
            }
        } else {
            validatedSurfaces
        }

        var validatedHorizontalCount = 0
        var validatedVerticalCount = 0
        var nearestDist = Float.MAX_VALUE

        for (surface in finalValidatedSurfaces) {
            if (surface.type.isHorizontal) {
                validatedHorizontalCount++
            } else if (surface.type == PlaneType.VERTICAL) {
                validatedVerticalCount++
            }
            if (surface.distanceFromCamera < nearestDist) {
                nearestDist = surface.distanceFromCamera
            }
        }

        return PlanesTelemetry(
            planes = rawPlanesList,
            activePlaneCount = rawPlanesList.size,
            horizontalPlaneCount = validatedHorizontalCount,
            verticalPlaneCount = validatedVerticalCount,
            hasDetectedUsableSurface = finalValidatedSurfaces.isNotEmpty(),
            isAimingAtSurface = reticleState == ReticleTargetState.VALID_SURFACE,
            nearestPlaneDistance = if (finalValidatedSurfaces.isNotEmpty()) nearestDist else null,
            rawPlanes = rawPlanesList,
            rawPlaneCount = rawPlanesList.size,
            validatedSurfaces = finalValidatedSurfaces,
            validatedSurfaceCount = finalValidatedSurfaces.size,
            validatedHorizontalCount = validatedHorizontalCount,
            validatedVerticalCount = validatedVerticalCount,
            hasValidatedSurface = finalValidatedSurfaces.isNotEmpty(),
            reticleTargetState = reticleState,
            targetedSurface = targetedSurface,
            targetedDistance = targetedDistance,
            allEvaluatedSurfaces = evaluatedSurfaces
        )
    }

    /**
     * Determines whether a plane qualifies as VALID, STABILIZING, CANDIDATE, or is rejected.
     */
    private fun evaluatePlaneConfidence(
        planeType: PlaneType,
        extentX: Float,
        extentZ: Float,
        polygonPoints: Int,
        polygonArea: Float,
        aspectRatio: Float,
        polygonToBoxRatio: Float,
        history: PlaneTrackerState
    ): SurfaceConfidence {
        // Check minimum polygon points
        if (polygonPoints < config.minPolygonVertices) {
            return SurfaceConfidence.TOO_SMALL
        }

        // Check aspect ratio (reject extreme thin slivers)
        if (aspectRatio > config.maxAspectRatio) {
            return SurfaceConfidence.DEGENERATE
        }

        // Check polygon fill density vs bounding box
        if (polygonToBoxRatio < config.minPolygonToBoundingBoxRatio) {
            return SurfaceConfidence.DEGENERATE
        }

        // Check orientation-specific area & extents
        val (minArea, minExtentX, minExtentZ) = if (planeType.isHorizontal) {
            Triple(config.minHorizontalAreaSquareMeters, config.minHorizontalExtentX, config.minHorizontalExtentZ)
        } else {
            Triple(config.minVerticalAreaSquareMeters, config.minVerticalExtentX, config.minVerticalExtentZ)
        }

        if (polygonArea < minArea || extentX < minExtentX || extentZ < minExtentZ) {
            return SurfaceConfidence.TOO_SMALL
        }

        // Check temporal stability
        if (history.consecutiveTrackingFrames < 4) {
            return SurfaceConfidence.CANDIDATE
        }

        if (history.consecutiveTrackingFrames < config.minStabilityFrames) {
            return SurfaceConfidence.STABILIZING
        }

        // Check jitter threshold
        if (history.averageCenterJitter > config.maxCenterJitterMeters) {
            return SurfaceConfidence.UNSTABLE
        }

        return SurfaceConfidence.VALID
    }

    /**
     * Suppresses redundant plane fragments belonging to the same physical wall or floor.
     * When two planes have nearly identical normal vectors and are coplanar, only keep the larger plane.
     */
    private fun suppressDuplicateFragments(surfaces: List<ValidatedSurface>): List<ValidatedSurface> {
        val validList = surfaces.filter { it.confidence == SurfaceConfidence.VALID }
        if (validList.size <= 1) return validList

        // Sort descending by polygon area so largest representative planes are evaluated first
        val sorted = validList.sortedByDescending { it.polygonAreaSquareMeters }
        val retained = ArrayList<ValidatedSurface>()

        for (candidate in sorted) {
            var isRedundantFragment = false

            for (primary in retained) {
                // Must be of same orientation type
                if (candidate.type != primary.type) continue

                // Check normal alignment (dot product)
                val dot = candidate.normalX * primary.normalX +
                          candidate.normalY * primary.normalY +
                          candidate.normalZ * primary.normalZ
                val angleRad = acos(dot.coerceIn(-1.0f, 1.0f))
                val angleDeg = Math.toDegrees(angleRad.toDouble()).toFloat()

                if (angleDeg < config.normalAngleToleranceDegrees) {
                    // Check perpendicular distance between candidate center and primary plane equation
                    // Plane equation: n . (X - P0) = 0
                    val dx = candidate.centerTranslationX - primary.centerTranslationX
                    val dy = candidate.centerTranslationY - primary.centerTranslationY
                    val dz = candidate.centerTranslationZ - primary.centerTranslationZ
                    val perpDist = abs(dx * primary.normalX + dy * primary.normalY + dz * primary.normalZ)

                    if (perpDist < config.planeCoplanarDistanceToleranceMeters) {
                        // Candidate is an overlapping or adjacent fragment of an already retained primary plane
                        isRedundantFragment = true
                        break
                    }
                }
            }

            if (!isRedundantFragment) {
                retained.add(candidate)
            }
        }

        return retained
    }

    /**
     * Extracts plane unit normal vector from ARCore Pose (+Y axis in plane local coordinates).
     */
    private fun extractPlaneNormal(pose: Pose, outNormal: FloatArray) {
        pose.getTransformedAxis(1, 1.0f, outNormal, 0)
    }

    /**
     * Computes the 2D polygon area using the Shoelace formula on local plane vertices.
     */
    fun calculatePolygonArea(polygon: FloatBuffer): Float {
        val count = polygon.remaining() / 2
        if (count < 3) return 0f

        val originalPos = polygon.position()
        var area = 0.0

        var prevX = polygon.get(originalPos + (count - 1) * 2)
        var prevZ = polygon.get(originalPos + (count - 1) * 2 + 1)

        for (i in 0 until count) {
            val curX = polygon.get(originalPos + i * 2)
            val curZ = polygon.get(originalPos + i * 2 + 1)
            area += (prevX * curZ - curX * prevZ).toDouble()
            prevX = curX
            prevZ = curZ
        }

        polygon.position(originalPos)
        return (abs(area) * 0.5).toFloat()
    }
}
