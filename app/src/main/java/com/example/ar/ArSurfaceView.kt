package com.example.ar

import android.content.Context
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.Log
import com.google.ar.core.Plane
import com.google.ar.core.Session
import com.google.ar.core.TrackingFailureReason
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.CameraNotAvailableException
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs
import kotlin.math.min

/**
 * GLSurfaceView dedicated to ARCore camera background rendering and 3D surface visualization.
 */
class ArSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : GLSurfaceView(context, attrs), GLSurfaceView.Renderer {

    private val backgroundRenderer = BackgroundRenderer()
    private val planeRenderer = PlaneRenderer()
    private val surfaceValidator = SurfaceValidator()
    val displayRotationHelper = DisplayRotationHelper(context)
    private val mainHandler = Handler(Looper.getMainLooper())

    // Configurable display mode: whether to show faint outlines for unvalidated candidate planes
    var showDebugCandidatePlanes: Boolean = false

    var session: Session? = null

    var onTrackingUpdated: ((TrackingState, TrackingFailureReason) -> Unit)? = null
    var onPoseUpdated: ((CameraPoseData) -> Unit)? = null
    var onTrackingAndPoseUpdated: ((TrackingState, TrackingFailureReason, CameraPoseData?) -> Unit)? = null
    var onPlanesUpdated: ((PlanesTelemetry) -> Unit)? = null
    var onSessionError: ((String) -> Unit)? = null

    private var lastTrackingState: TrackingState? = null
    private var lastFailureReason: TrackingFailureReason? = null
    private var isPoseUpdatePending = false
    private var isPlanesUpdatePending = false

    // Viewport dimensions
    private var viewportWidth = 1
    private var viewportHeight = 1

    // Reusable matrices for projection and view
    private val projMatrix = FloatArray(16)
    private val viewMatrix = FloatArray(16)

    init {
        preserveEGLContextOnPause = true
        setEGLContextClientVersion(2)
        setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        setRenderer(this)
        renderMode = RENDERMODE_CONTINUOUSLY
        setWillNotDraw(false)
    }

    override fun onResume() {
        super.onResume()
        displayRotationHelper.onResume()
    }

    override fun onPause() {
        displayRotationHelper.onPause()
        super.onPause()
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f)
        backgroundRenderer.createOnGlThread()
        planeRenderer.createOnGlThread()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewportWidth = width
        viewportHeight = height
        GLES20.glViewport(0, 0, width, height)
        displayRotationHelper.onSurfaceChanged(width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

        val currentSession = session ?: return

        try {
            displayRotationHelper.updateSessionIfNeeded(currentSession)

            // Notify ARCore of the texture ID before updating frame
            currentSession.setCameraTextureName(backgroundRenderer.textureId)

            val frame = currentSession.update()
            val camera = frame.camera

            val currentTrackingState = camera.trackingState
            val currentFailureReason = camera.trackingFailureReason

            val poseData = if (currentTrackingState == TrackingState.TRACKING) {
                try {
                    CameraPoseData.fromPose(camera.pose)
                } catch (e: Exception) {
                    null
                }
            } else {
                null
            }

            val stateChanged = (currentTrackingState != lastTrackingState || currentFailureReason != lastFailureReason)
            if (stateChanged) {
                lastTrackingState = currentTrackingState
                lastFailureReason = currentFailureReason
                mainHandler.post {
                    onTrackingUpdated?.invoke(currentTrackingState, currentFailureReason)
                    onTrackingAndPoseUpdated?.invoke(currentTrackingState, currentFailureReason, poseData)
                }
            } else if (!isPoseUpdatePending && poseData != null) {
                isPoseUpdatePending = true
                val capturedPose = poseData
                mainHandler.post {
                    isPoseUpdatePending = false
                    onPoseUpdated?.invoke(capturedPose)
                    onTrackingAndPoseUpdated?.invoke(currentTrackingState, currentFailureReason, capturedPose)
                }
            }

            // Draw camera preview background
            backgroundRenderer.draw(frame)

            // Step 3 CORRECTION: Validated Surface Detection & Rendering Pipeline
            if (currentTrackingState == TrackingState.TRACKING) {
                camera.getProjectionMatrix(projMatrix, 0, 0.1f, 100f)
                camera.getViewMatrix(viewMatrix, 0)

                // 1. Raycast center reticle against ARCore trackables
                val centerX = viewportWidth / 2f
                val centerY = viewportHeight / 2f
                val hitResults = try {
                    frame.hitTest(centerX, centerY)
                } catch (e: Exception) {
                    emptyList()
                }
                val centerReticleHit = hitResults.firstOrNull { hit ->
                    hit.trackable is Plane && hit.distance in surfaceValidator.config.minRayHitDistanceMeters..surfaceValidator.config.maxRayHitDistanceMeters
                }

                // 2. Fetch all raw ARCore planes
                val allPlanes = currentSession.getAllTrackables(Plane::class.java)

                // 3. Process planes through multi-stage validation engine
                val telemetry = surfaceValidator.processFrame(allPlanes, poseData, camera, centerReticleHit)

                // 4. Extract validated plane hash codes and targeted plane hash code
                val validatedHashes = telemetry.validatedSurfaces.map { it.planeHashCode }.toSet()
                val targetedHash = telemetry.targetedSurface?.planeHashCode

                // 5. Render physical surfaces overlay in 3D world space (validated surfaces highlighted, raw clutter suppressed)
                planeRenderer.draw(
                    planes = allPlanes,
                    camera = camera,
                    projMatrix = projMatrix,
                    viewMatrix = viewMatrix,
                    validatedPlaneHashCodes = validatedHashes,
                    targetedPlaneHashCode = targetedHash,
                    showUnvalidatedPlanes = showDebugCandidatePlanes
                )

                // 6. Post validated telemetry to UI thread
                if (!isPlanesUpdatePending) {
                    isPlanesUpdatePending = true
                    mainHandler.post {
                        isPlanesUpdatePending = false
                        onPlanesUpdated?.invoke(telemetry)
                    }
                }
            } else if (!isPlanesUpdatePending) {
                // If tracking is lost or paused, report empty telemetry
                isPlanesUpdatePending = true
                mainHandler.post {
                    isPlanesUpdatePending = false
                    onPlanesUpdated?.invoke(PlanesTelemetry.EMPTY)
                }
            }
        } catch (e: CameraNotAvailableException) {
            Log.e(TAG, "Camera not available during draw frame", e)
            mainHandler.post {
                onSessionError?.invoke("Camera unavailable: ${e.message}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in ARCore onDrawFrame", e)
        }
    }

    companion object {
        private const val TAG = "ArSurfaceView"
    }
}
