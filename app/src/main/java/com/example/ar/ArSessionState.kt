package com.example.ar

import com.google.ar.core.TrackingFailureReason
import com.google.ar.core.TrackingState

/**
 * Represents the current operational state of the ARCore session.
 */
sealed interface ArSessionState {
    data object CheckingCompatibility : ArSessionState
    data object PermissionRequired : ArSessionState
    data object ArCoreInstallRequired : ArSessionState
    data object UnsupportedDevice : ArSessionState
    data object Ready : ArSessionState
    data class Active(
        val trackingState: TrackingState = TrackingState.TRACKING,
        val failureReason: TrackingFailureReason = TrackingFailureReason.NONE
    ) : ArSessionState
    data object Paused : ArSessionState
    data class Error(val message: String, val canRetry: Boolean = true) : ArSessionState
}
