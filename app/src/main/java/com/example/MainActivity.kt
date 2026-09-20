package com.example

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.ar.ArSessionManager
import com.example.ar.ArSessionState
import com.example.ar.ArSurfaceView
import com.example.ui.theme.MyApplicationTheme
import com.google.ar.core.TrackingFailureReason
import com.google.ar.core.TrackingState

class MainActivity : ComponentActivity() {

    private lateinit var arSessionManager: ArSessionManager
    private var surfaceView: ArSurfaceView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        arSessionManager = ArSessionManager(this)

        setContent {
            MyApplicationTheme {
                val sessionState by arSessionManager.sessionState.collectAsState()
                val context = LocalContext.current
                val lifecycleOwner = LocalLifecycleOwner.current

                var hasCameraPermission by remember {
                    mutableStateOf(
                        ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.CAMERA
                        ) == PackageManager.PERMISSION_GRANTED
                    )
                }

                val permissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestPermission()
                ) { isGranted ->
                    hasCameraPermission = isGranted
                    if (isGranted) {
                        surfaceView?.let { arSessionManager.resumeSession(it) }
                    }
                }

                DisposableEffect(lifecycleOwner) {
                    val observer = LifecycleEventObserver { _, event ->
                        when (event) {
                            Lifecycle.Event.ON_RESUME -> {
                                if (hasCameraPermission) {
                                    surfaceView?.let { arSessionManager.resumeSession(it) }
                                }
                            }
                            Lifecycle.Event.ON_PAUSE -> {
                                surfaceView?.let { arSessionManager.pauseSession(it) }
                            }
                            Lifecycle.Event.ON_DESTROY -> {
                                surfaceView?.let { arSessionManager.destroySession(it) }
                            }
                            else -> {}
                        }
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose {
                        lifecycleOwner.lifecycle.removeObserver(observer)
                    }
                }

                LaunchedEffect(hasCameraPermission) {
                    if (hasCameraPermission) {
                        arSessionManager.checkAvailability { supported ->
                            if (supported) {
                                surfaceView?.let { arSessionManager.resumeSession(it) }
                            }
                        }
                    }
                }

                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    ) {
                        if (hasCameraPermission) {
                            // Live AR Camera Feed via ARCore
                            AndroidView(
                                factory = { ctx ->
                                    ArSurfaceView(ctx).also { view ->
                                        surfaceView = view
                                        view.onTrackingUpdated = { state, reason ->
                                            arSessionManager.updateTrackingState(state, reason)
                                        }
                                        view.onSessionError = { message ->
                                            arSessionManager.reportError(message)
                                        }
                                        arSessionManager.resumeSession(view)
                                    }
                                },
                                modifier = Modifier
                                    .fillMaxSize()
                                    .testTag("ar_camera_view")
                            )

                            // Status HUD badge overlay at top
                            ArStatusOverlay(
                                state = sessionState,
                                modifier = Modifier
                                    .align(Alignment.TopCenter)
                                    .statusBarsPadding()
                                    .padding(top = 16.dp)
                            )
                        } else {
                            // Permission Request UI
                            CameraPermissionRequiredScreen(
                                onRequestPermission = {
                                    permissionLauncher.launch(Manifest.permission.CAMERA)
                                }
                            )
                        }

                        // Error / Unsupported / Install Dialog State
                        when (val state = sessionState) {
                            is ArSessionState.UnsupportedDevice,
                            is ArSessionState.Error -> {
                                ArErrorOverlay(
                                    state = state,
                                    onRetry = {
                                        surfaceView?.let { arSessionManager.resumeSession(it) }
                                    },
                                    modifier = Modifier
                                        .align(Alignment.BottomCenter)
                                        .padding(16.dp)
                                )
                            }
                            is ArSessionState.ArCoreInstallRequired -> {
                                ArInstallPromptOverlay(
                                    onInstall = {
                                        surfaceView?.let { arSessionManager.resumeSession(it) }
                                    },
                                    modifier = Modifier
                                        .align(Alignment.BottomCenter)
                                        .padding(16.dp)
                                )
                            }
                            else -> {}
                        }
                    }
                }
            }
        }
    }
}

/**
 * Minimal clean Material 3 tracking status pill overlay.
 */
@Composable
fun ArStatusOverlay(
    state: ArSessionState,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = true,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = Color.Black.copy(alpha = 0.65f),
            contentColor = Color.White,
            tonalElevation = 6.dp,
            modifier = Modifier.testTag("ar_status_chip")
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                val (indicatorColor, statusText) = when (state) {
                    is ArSessionState.CheckingCompatibility -> {
                        Color(0xFF64B5F6) to stringResource(R.string.status_checking_compatibility)
                    }
                    is ArSessionState.PermissionRequired -> {
                        Color(0xFFFFB74D) to stringResource(R.string.camera_permission_required_title)
                    }
                    is ArSessionState.ArCoreInstallRequired -> {
                        Color(0xFFFFB74D) to stringResource(R.string.arcore_installing)
                    }
                    is ArSessionState.UnsupportedDevice -> {
                        Color(0xFFE57373) to stringResource(R.string.arcore_not_supported)
                    }
                    is ArSessionState.Ready -> {
                        Color(0xFF81C784) to stringResource(R.string.status_ready)
                    }
                    is ArSessionState.Active -> {
                        when (state.trackingState) {
                            TrackingState.TRACKING -> Color(0xFF4CAF50) to stringResource(R.string.status_tracking_normal)
                            TrackingState.PAUSED -> {
                                val reasonStr = formatFailureReason(state.failureReason)
                                Color(0xFFFFB300) to stringResource(R.string.status_tracking_limited, reasonStr)
                            }
                            TrackingState.STOPPED -> Color(0xFFE53935) to stringResource(R.string.status_tracking_paused)
                        }
                    }
                    is ArSessionState.Paused -> {
                        Color(0xFFFFB300) to stringResource(R.string.status_tracking_paused)
                    }
                    is ArSessionState.Error -> {
                        Color(0xFFE53935) to state.message
                    }
                }

                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(indicatorColor)
                )

                Text(
                    text = statusText,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color.White
                )
            }
        }
    }
}

/**
 * Screen displayed when camera permission is not yet granted.
 */
@Composable
fun CameraPermissionRequiredScreen(
    onRequestPermission: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(24.dp)
    ) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            ),
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.padding(24.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.CameraAlt,
                    contentDescription = stringResource(R.string.grant_permission_button),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(56.dp)
                )

                Text(
                    text = stringResource(R.string.camera_permission_required_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )

                Text(
                    text = stringResource(R.string.camera_permission_required_description),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(8.dp))

                Button(
                    onClick = onRequestPermission,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("grant_permission_button")
                ) {
                    Text(text = stringResource(R.string.grant_permission_button))
                }
            }
        }
    }
}

/**
 * Overlay shown when ARCore cannot run or encounters an error.
 */
@Composable
fun ArErrorOverlay(
    state: ArSessionState,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    val canRetry = (state as? ArSessionState.Error)?.canRetry ?: (state !is ArSessionState.UnsupportedDevice)

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        ),
        shape = RoundedCornerShape(16.dp),
        modifier = modifier
            .fillMaxWidth()
            .testTag("ar_error_card")
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(16.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(32.dp)
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                val errorText = when (state) {
                    is ArSessionState.UnsupportedDevice -> stringResource(R.string.arcore_not_supported)
                    is ArSessionState.Error -> state.message
                    else -> stringResource(R.string.arcore_not_supported)
                }
                Text(
                    text = errorText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }

            if (canRetry) {
                Spacer(modifier = Modifier.width(8.dp))

                OutlinedButton(
                    onClick = onRetry,
                    modifier = Modifier.testTag("retry_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = stringResource(R.string.retry_button),
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(text = stringResource(R.string.retry_button))
                }
            }
        }
    }
}

/**
 * Overlay shown when Google Play Services for AR installation or update is required.
 */
@Composable
fun ArInstallPromptOverlay(
    onInstall: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        ),
        shape = RoundedCornerShape(16.dp),
        modifier = modifier
            .fillMaxWidth()
            .testTag("ar_install_card")
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(16.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(32.dp)
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.arcore_installing),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            Button(
                onClick = onInstall,
                modifier = Modifier.testTag("install_button")
            ) {
                Text(text = "Install")
            }
        }
    }
}

@Composable
private fun formatFailureReason(reason: TrackingFailureReason): String {
    return when (reason) {
        TrackingFailureReason.NONE -> stringResource(R.string.tracking_reason_none)
        TrackingFailureReason.BAD_STATE -> stringResource(R.string.tracking_reason_bad_state)
        TrackingFailureReason.INSUFFICIENT_LIGHT -> stringResource(R.string.tracking_reason_insufficient_light)
        TrackingFailureReason.EXCESSIVE_MOTION -> stringResource(R.string.tracking_reason_motion)
        TrackingFailureReason.INSUFFICIENT_FEATURES -> "Insufficient visual features"
        TrackingFailureReason.CAMERA_UNAVAILABLE -> "Camera unavailable"
        else -> stringResource(R.string.tracking_reason_none)
    }
}
