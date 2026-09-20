package com.example.ar

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Session
import com.google.ar.core.TrackingFailureReason
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.FatalException
import com.google.ar.core.exceptions.UnavailableApkTooOldException
import com.google.ar.core.exceptions.UnavailableArcoreNotInstalledException
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException
import com.google.ar.core.exceptions.UnavailableSdkTooOldException
import com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Manages the lifecycle, compatibility checking, configuration,
 * and state transitions of the Google ARCore Session.
 */
class ArSessionManager(private val activity: Activity) {

    private val _sessionState = MutableStateFlow<ArSessionState>(ArSessionState.CheckingCompatibility)
    val sessionState: StateFlow<ArSessionState> = _sessionState.asStateFlow()

    private val _cameraPose = MutableStateFlow(CameraPoseData.INITIAL)
    val cameraPose: StateFlow<CameraPoseData> = _cameraPose.asStateFlow()

    var session: Session? = null
        private set

    private var userRequestedInstall = false

    /**
     * Checks if Google Play Services for AR (com.google.ar.core) is installed.
     */
    fun isArCoreInstalled(): Boolean {
        return try {
            activity.packageManager.getPackageInfo("com.google.ar.core", 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    /**
     * Checks whether Google Play Store or an application that can handle
     * `market://details?id=com.google.ar.core` is available on this device.
     */
    fun canLaunchInstaller(): Boolean {
        val pm = activity.packageManager
        val marketIntent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.google.ar.core"))
        val hasMarketHandler = marketIntent.resolveActivity(pm) != null
        val hasPlayStore = try {
            pm.getPackageInfo("com.android.vending", 0)
            true
        } catch (e: Exception) {
            false
        }
        return hasMarketHandler || hasPlayStore
    }

    /**
     * Verifies ARCore availability on this device.
     * Returns true if ARCore is supported and ready/installable.
     */
    fun checkAvailability(onResult: (Boolean) -> Unit) {
        _sessionState.value = ArSessionState.CheckingCompatibility
        val availability = ArCoreApk.getInstance().checkAvailability(activity)

        if (availability.isTransient) {
            // Re-check after brief delay when transient
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                checkAvailability(onResult)
            }, 200)
            return
        }

        when (availability) {
            ArCoreApk.Availability.SUPPORTED_INSTALLED -> {
                _sessionState.value = ArSessionState.Ready
                onResult(true)
            }
            ArCoreApk.Availability.SUPPORTED_NOT_INSTALLED,
            ArCoreApk.Availability.SUPPORTED_APK_TOO_OLD -> {
                if (canLaunchInstaller()) {
                    _sessionState.value = ArSessionState.ArCoreInstallRequired
                    onResult(true)
                } else {
                    _sessionState.value = ArSessionState.Error(
                        message = "Google Play Services for AR is required, but Google Play Store is not available on this device.",
                        canRetry = false
                    )
                    onResult(false)
                }
            }
            ArCoreApk.Availability.UNSUPPORTED_DEVICE_NOT_CAPABLE -> {
                _sessionState.value = ArSessionState.UnsupportedDevice
                onResult(false)
            }
            else -> {
                _sessionState.value = ArSessionState.Error("Unable to verify ARCore compatibility on this device.")
                onResult(false)
            }
        }
    }

    /**
     * Initializes or resumes the ARCore session.
     */
    fun resumeSession(surfaceView: ArSurfaceView) {
        var installResult: ArCoreApk.InstallStatus = ArCoreApk.InstallStatus.INSTALLED
        try {
            if (session == null) {
                // If ARCore is not installed on this device, check if installer is available
                if (!isArCoreInstalled()) {
                    if (!canLaunchInstaller()) {
                        Log.w(TAG, "ARCore not installed and Google Play Store is unavailable.")
                        _sessionState.value = ArSessionState.Error(
                            message = "Google Play Services for AR is required, but Google Play Store is not available on this device.",
                            canRetry = false
                        )
                        return
                    }
                }

                // Request ARCore installation if required
                installResult = ArCoreApk.getInstance().requestInstall(activity, !userRequestedInstall)
                if (installResult == ArCoreApk.InstallStatus.INSTALL_REQUESTED) {
                    userRequestedInstall = true
                    _sessionState.value = ArSessionState.ArCoreInstallRequired
                    return
                }

                // Create the ARCore Session
                val newSession = Session(activity)
                val config = Config(newSession).apply {
                    updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                    // STEP 1 SCOPE: Plane finding, depth and extra engines explicitly disabled
                    planeFindingMode = Config.PlaneFindingMode.DISABLED
                    lightEstimationMode = Config.LightEstimationMode.DISABLED
                    depthMode = Config.DepthMode.DISABLED
                }
                newSession.configure(config)
                session = newSession
                surfaceView.session = newSession
            }

            session?.resume()
            surfaceView.onResume()
            _sessionState.value = ArSessionState.Active(
                trackingState = TrackingState.PAUSED,
                failureReason = TrackingFailureReason.NONE
            )
        } catch (e: UnavailableArcoreNotInstalledException) {
            Log.w(TAG, "ARCore not installed", e)
            _sessionState.value = ArSessionState.ArCoreInstallRequired
        } catch (e: UnavailableUserDeclinedInstallationException) {
            Log.w(TAG, "User declined ARCore installation", e)
            _sessionState.value = ArSessionState.Error(
                message = "Google Play Services for AR installation was declined. AR features cannot run without it.",
                canRetry = true
            )
        } catch (e: FatalException) {
            Log.w(TAG, "ARCore installer could not be launched", e)
            _sessionState.value = ArSessionState.Error(
                message = "Google Play Services for AR is required, but could not be launched. Please run on an ARCore-supported physical device.",
                canRetry = false
            )
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "No activity found to handle ARCore installation intent", e)
            _sessionState.value = ArSessionState.Error(
                message = "Google Play Store is not available to install Google Play Services for AR.",
                canRetry = false
            )
        } catch (e: UnavailableApkTooOldException) {
            Log.w(TAG, "ARCore APK too old", e)
            _sessionState.value = ArSessionState.Error("Google Play Services for AR must be updated.")
        } catch (e: UnavailableSdkTooOldException) {
            Log.w(TAG, "App SDK too old for ARCore", e)
            _sessionState.value = ArSessionState.Error("App update required to run AR.")
        } catch (e: UnavailableDeviceNotCompatibleException) {
            Log.w(TAG, "Device not compatible with ARCore", e)
            _sessionState.value = ArSessionState.UnsupportedDevice
        } catch (e: CameraNotAvailableException) {
            Log.w(TAG, "Camera not available", e)
            _sessionState.value = ArSessionState.Error("Camera unavailable. Another app may be using the camera.")
        } catch (e: SecurityException) {
            Log.w(TAG, "Camera permission missing", e)
            _sessionState.value = ArSessionState.PermissionRequired
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error resuming AR session", e)
            _sessionState.value = ArSessionState.Error("Failed to initialize AR: ${e.localizedMessage ?: "Unknown error"}")
        }
    }

    fun pauseSession(surfaceView: ArSurfaceView) {
        try {
            surfaceView.onPause()
            session?.pause()
            if (_sessionState.value is ArSessionState.Active) {
                _sessionState.value = ArSessionState.Paused
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error pausing AR session", e)
        }
    }

    fun destroySession(surfaceView: ArSurfaceView) {
        try {
            surfaceView.session = null
            session?.close()
            session = null
        } catch (e: Exception) {
            Log.e(TAG, "Error closing AR session", e)
        }
    }

    fun updateTrackingAndPose(state: TrackingState, reason: TrackingFailureReason, pose: CameraPoseData?) {
        val resolvedPose = pose ?: _cameraPose.value
        if (pose != null) {
            _cameraPose.value = pose
        }
        _sessionState.value = ArSessionState.Active(
            trackingState = state,
            failureReason = reason,
            pose = resolvedPose
        )
    }

    fun updateTrackingState(state: TrackingState, reason: TrackingFailureReason) {
        _sessionState.value = ArSessionState.Active(
            trackingState = state,
            failureReason = reason,
            pose = _cameraPose.value
        )
    }

    fun reportError(message: String) {
        _sessionState.value = ArSessionState.Error(message)
    }

    companion object {
        private const val TAG = "ArSessionManager"
    }
}
