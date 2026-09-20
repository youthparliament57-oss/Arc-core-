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
    val displayRotationHelper = DisplayRotationHelper(context)
    private val mainHandler = Handler(Looper.getMainLooper())

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

    // Reusable matrices for projection and view
    private val projMatrix = FloatArray(16)
    private val viewMatrix = FloatArray(16)
    private val samplePoint = FloatArray(3)
    private val localSamplePoint = FloatArray(3)

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

            // Step 3: Surface Detection Rendering & Telemetry
            if (currentTrackingState == TrackingState.TRACKING) {
                camera.getProjectionMatrix(projMatrix, 0, 0.1f, 100f)
                camera.getViewMatrix(viewMatrix, 0)

                val allPlanes = currentSession.getAllTrackables(Plane::class.java)
                // Render physical surfaces overlay in 3D world space
                planeRenderer.draw(allPlanes, camera, projMatrix, viewMatrix)

                // Compute Plane Telemetry for the spatial UI
                if (!isPlanesUpdatePending) {
                    isPlanesUpdatePending = true
                    val telemetry = processPlaneTelemetry(allPlanes, poseData, camera)
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

    private fun processPlaneTelemetry(
        allPlanes: Collection<Plane>,
        cameraPose: CameraPoseData?,
        camera: com.google.ar.core.Camera
    ): PlanesTelemetry {
        val activePlanes = mutableListOf<DetectedPlaneData>()
        var horizontalCount = 0
        var verticalCount = 0
        var minDistance = Float.MAX_VALUE
        var isAimingAtAnyPlane = false

        val cameraPoseObject = camera.pose

        for (plane in allPlanes) {
            if (plane.trackingState != TrackingState.TRACKING || plane.subsumedBy != null) {
                continue
            }

            val planeData = DetectedPlaneData.fromArCorePlane(plane, cameraPose)
            activePlanes.add(planeData)

            if (planeData.type.isHorizontal) {
                horizontalCount++
            } else if (planeData.type == PlaneType.VERTICAL) {
                verticalCount++
            }

            minDistance = min(minDistance, planeData.distanceFromCamera)

            // Check if camera line of sight (reticle) points towards this plane
            if (!isAimingAtAnyPlane) {
                val planeInverse = plane.centerPose.inverse()
                // Sample 3 points along the camera's forward optical axis: 1.0m, 1.75m, 2.5m
                for (dist in floatArrayOf(1.0f, 1.75f, 2.5f)) {
                    samplePoint[0] = 0f
                    samplePoint[1] = 0f
                    samplePoint[2] = -dist
                    cameraPoseObject.transformPoint(samplePoint, 0, samplePoint, 0)
                    planeInverse.transformPoint(samplePoint, 0, localSamplePoint, 0)

                    // In plane local space, Y=0 is the plane surface, X is half-extentX, Z is half-extentZ
                    val withinY = abs(localSamplePoint[1]) < 0.35f
                    val withinX = abs(localSamplePoint[0]) <= (plane.extentX / 2f + 0.15f)
                    val withinZ = abs(localSamplePoint[2]) <= (plane.extentZ / 2f + 0.15f)

                    if (withinY && withinX && withinZ) {
                        isAimingAtAnyPlane = true
                        break
                    }
                }
            }
        }

        val hasUsableSurface = activePlanes.isNotEmpty()

        return PlanesTelemetry(
            planes = activePlanes,
            activePlaneCount = activePlanes.size,
            horizontalPlaneCount = horizontalCount,
            verticalPlaneCount = verticalCount,
            hasDetectedUsableSurface = hasUsableSurface,
            isAimingAtSurface = isAimingAtAnyPlane,
            nearestPlaneDistance = if (activePlanes.isNotEmpty()) minDistance else null
        )
    }

    companion object {
        private const val TAG = "ArSurfaceView"
    }
}
