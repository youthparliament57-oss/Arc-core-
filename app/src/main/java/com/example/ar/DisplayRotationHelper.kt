package com.example.ar

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display
import android.view.Surface
import android.view.WindowManager
import com.google.ar.core.Session

/**
 * Helper to track display rotation changes and notify the ARCore Session
 * via `session.setDisplayGeometry(rotation, width, height)`.
 */
class DisplayRotationHelper(private val context: Context) : DisplayManager.DisplayListener {

    private val displayManager =
        context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager

    private var viewportChanged = false
    private var viewportWidth = 0
    private var viewportHeight = 0

    val rotation: Int
        get() {
            return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                try {
                    context.display?.rotation ?: Surface.ROTATION_0
                } catch (e: Exception) {
                    val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                    @Suppress("DEPRECATION")
                    wm.defaultDisplay.rotation
                }
            } else {
                val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                @Suppress("DEPRECATION")
                wm.defaultDisplay.rotation
            }
        }

    fun onResume() {
        displayManager.registerDisplayListener(this, null)
    }

    fun onPause() {
        displayManager.unregisterDisplayListener(this)
    }

    fun onSurfaceChanged(width: Int, height: Int) {
        viewportWidth = width
        viewportHeight = height
        viewportChanged = true
    }

    fun updateSessionIfNeeded(session: Session) {
        if (viewportChanged) {
            val displayRotation = rotation
            session.setDisplayGeometry(displayRotation, viewportWidth, viewportHeight)
            viewportChanged = false
        }
    }

    override fun onDisplayAdded(displayId: Int) {}
    override fun onDisplayRemoved(displayId: Int) {}
    override fun onDisplayChanged(displayId: Int) {
        viewportChanged = true
    }
}
