package com.example.ar

import android.content.Context
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.Log
import com.google.ar.core.Session
import com.google.ar.core.TrackingFailureReason
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.CameraNotAvailableException
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * GLSurfaceView dedicated to ARCore camera background rendering.
 */
class ArSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : GLSurfaceView(context, attrs), GLSurfaceView.Renderer {

    private val backgroundRenderer = BackgroundRenderer()
    val displayRotationHelper = DisplayRotationHelper(context)
    private val mainHandler = Handler(Looper.getMainLooper())

    var session: Session? = null

    var onTrackingUpdated: ((TrackingState, TrackingFailureReason) -> Unit)? = null
    var onSessionError: ((String) -> Unit)? = null

    private var lastTrackingState: TrackingState? = null
    private var lastFailureReason: TrackingFailureReason? = null

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

            if (currentTrackingState != lastTrackingState || currentFailureReason != lastFailureReason) {
                lastTrackingState = currentTrackingState
                lastFailureReason = currentFailureReason
                mainHandler.post {
                    onTrackingUpdated?.invoke(currentTrackingState, currentFailureReason)
                }
            }

            backgroundRenderer.draw(frame)
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
