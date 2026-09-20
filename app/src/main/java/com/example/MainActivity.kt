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
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.ar.ArSessionManager
import com.example.ar.ArSessionState
import com.example.ar.ArSurfaceView
import com.example.ar.CameraPoseData
import com.example.ar.DetectedPlaneData
import com.example.ar.PlanesTelemetry
import com.example.ar.ReticleTargetState
import com.example.ar.SurfaceConfidence
import com.example.ar.ValidatedSurface
import com.example.ui.theme.MyApplicationTheme
import com.google.ar.core.TrackingFailureReason
import com.google.ar.core.TrackingState
import java.util.Locale

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
                val cameraPose by arSessionManager.cameraPose.collectAsState()
                val planesTelemetry by arSessionManager.planesTelemetry.collectAsState()
                val context = LocalContext.current
                val lifecycleOwner = LocalLifecycleOwner.current

                var showSettingsSheet by remember { mutableStateOf(false) }
                var showDebugTelemetryHud by remember { mutableStateOf(false) }
                var showCandidateOutlines by remember { mutableStateOf(false) }

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
                            // Live AR Camera Feed + OpenGL 3D Surface Visualization
                            AndroidView(
                                factory = { ctx ->
                                    ArSurfaceView(ctx).also { view ->
                                        surfaceView = view
                                        view.showDebugCandidatePlanes = showCandidateOutlines
                                        view.onTrackingAndPoseUpdated = { state, reason, pose ->
                                            arSessionManager.updateTrackingAndPose(state, reason, pose)
                                        }
                                        view.onTrackingUpdated = { state, reason ->
                                            arSessionManager.updateTrackingState(state, reason)
                                        }
                                        view.onPlanesUpdated = { telemetry ->
                                            arSessionManager.updatePlanesTelemetry(telemetry)
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

                            // Step 3: Top Status Indicator & Settings Button
                            ArTopBar(
                                state = sessionState,
                                planesTelemetry = planesTelemetry,
                                onOpenSettings = { showSettingsSheet = true },
                                modifier = Modifier
                                    .align(Alignment.TopCenter)
                                    .statusBarsPadding()
                                    .padding(horizontal = 16.dp, vertical = 12.dp)
                            )

                            // Step 3 CORRECTION: Central Spatial Reticle responding to validation states
                            ArCentralReticle(
                                state = sessionState,
                                reticleTargetState = planesTelemetry.reticleTargetState,
                                modifier = Modifier
                                    .align(Alignment.Center)
                                    .size(64.dp)
                                    .testTag("ar_reticle")
                            )

                            // Step 3 CORRECTION: Contextual Surface Feedback Banner (Scanning -> Analyzing -> Surface detected / Ready to place)
                            ArSurfaceFeedbackBanner(
                                reticleTargetState = planesTelemetry.reticleTargetState,
                                hasValidatedSurface = planesTelemetry.hasValidatedSurface,
                                validatedSurfaceCount = planesTelemetry.validatedSurfaceCount,
                                rawPlaneCount = planesTelemetry.rawPlaneCount,
                                modifier = Modifier
                                    .align(Alignment.Center)
                                    .padding(top = 110.dp)
                                    .testTag("ar_feedback_banner")
                            )

                            // Step 3 CORRECTION: Bottom Surface Telemetry Bar reflecting validated surfaces
                            ArBottomSurfaceBar(
                                planesTelemetry = planesTelemetry,
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .navigationBarsPadding()
                                    .padding(start = 16.dp, end = 16.dp, bottom = 16.dp)
                                    .testTag("ar_bottom_surface_bar")
                            )

                            // Step 2: On-screen Developer Telemetry HUD (toggled via Settings)
                            if (showDebugTelemetryHud) {
                                ArMotionDebugOverlay(
                                    state = sessionState,
                                    pose = cameraPose,
                                    planesTelemetry = planesTelemetry,
                                    onClose = { showDebugTelemetryHud = false },
                                    modifier = Modifier
                                        .align(Alignment.TopStart)
                                        .statusBarsPadding()
                                        .padding(top = 64.dp, start = 16.dp, end = 16.dp)
                                )
                            }

                            // Step 3: Settings & Diagnostics Modal Sheet
                            if (showSettingsSheet) {
                                ArSettingsDiagnosticsSheet(
                                    pose = cameraPose,
                                    planesTelemetry = planesTelemetry,
                                    showHudOnScreen = showDebugTelemetryHud,
                                    onToggleHudOnScreen = { showDebugTelemetryHud = it },
                                    showCandidateOutlines = showCandidateOutlines,
                                    onToggleCandidateOutlines = {
                                        showCandidateOutlines = it
                                        surfaceView?.showDebugCandidatePlanes = it
                                    },
                                    onDismiss = { showSettingsSheet = false }
                                )
                            }
                        } else {
                            // Camera Permission Request Screen
                            CameraPermissionRequiredScreen(
                                onRequestPermission = {
                                    permissionLauncher.launch(Manifest.permission.CAMERA)
                                }
                            )
                        }

                        // Error / Unsupported / Install Overlays
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
 * Top Status Bar containing the minimal AR status indicator and the Settings/Diagnostics button.
 */
@Composable
fun ArTopBar(
    state: ArSessionState,
    planesTelemetry: PlanesTelemetry,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = modifier.fillMaxWidth()
    ) {
        // Status Indicator Pill
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = Color(0xD90B1120),
            border = BorderStroke(1.dp, Color(0x33FFFFFF)),
            contentColor = Color.White,
            tonalElevation = 6.dp,
            modifier = Modifier.testTag("ar_status_chip")
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
            ) {
                val (dotColor, statusLabel) = when (state) {
                    is ArSessionState.CheckingCompatibility -> Color(0xFF38BDF8) to stringResource(R.string.status_checking_compatibility)
                    is ArSessionState.PermissionRequired -> Color(0xFFF59E0B) to stringResource(R.string.status_ar_searching)
                    is ArSessionState.ArCoreInstallRequired -> Color(0xFFF59E0B) to stringResource(R.string.status_ar_unavailable)
                    is ArSessionState.UnsupportedDevice -> Color(0xFFEF4444) to stringResource(R.string.status_ar_unavailable)
                    is ArSessionState.Ready -> Color(0xFF10B981) to stringResource(R.string.status_ar_tracking)
                    is ArSessionState.Active -> {
                        when (state.trackingState) {
                            TrackingState.TRACKING -> Color(0xFF10B981) to stringResource(R.string.status_ar_tracking)
                            TrackingState.PAUSED -> {
                                if (state.failureReason == TrackingFailureReason.NONE) {
                                    Color(0xFFF59E0B) to stringResource(R.string.status_ar_searching)
                                } else {
                                    Color(0xFFF97316) to stringResource(R.string.status_ar_limited)
                                }
                            }
                            TrackingState.STOPPED -> Color(0xFFEF4444) to stringResource(R.string.status_ar_unavailable)
                        }
                    }
                    is ArSessionState.Paused -> Color(0xFFF59E0B) to stringResource(R.string.status_ar_searching)
                    is ArSessionState.Error -> Color(0xFFEF4444) to stringResource(R.string.status_ar_unavailable)
                }

                Box(
                    modifier = Modifier
                        .size(9.dp)
                        .clip(CircleShape)
                        .background(dotColor)
                )

                Text(
                    text = statusLabel,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White
                )

                if (planesTelemetry.hasValidatedSurface) {
                    Text(
                        text = "• ${planesTelemetry.validatedSurfaceCount} usable surface${if (planesTelemetry.validatedSurfaceCount > 1) "s" else ""}",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color(0xFF38BDF8)
                    )
                } else if (planesTelemetry.rawPlaneCount > 0) {
                    Text(
                        text = "• Scanning…",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Normal,
                        color = Color(0xFFF59E0B)
                    )
                }
            }
        }

        // Settings / Info Button
        Surface(
            shape = CircleShape,
            color = Color(0xD90B1120),
            border = BorderStroke(1.dp, Color(0x33FFFFFF)),
            contentColor = Color.White,
            modifier = Modifier.size(42.dp)
        ) {
            IconButton(
                onClick = onOpenSettings,
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("ar_settings_button")
            ) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = "Settings and Diagnostics",
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

/**
 * Step 3 CORRECTION: Central AR Reticle.
 * Center-anchored, visually responsive to reticle surface targeting and stability states.
 */
@Composable
fun ArCentralReticle(
    state: ArSessionState,
    reticleTargetState: ReticleTargetState,
    modifier: Modifier = Modifier
) {
    val isTracking = state is ArSessionState.Active && state.trackingState == TrackingState.TRACKING

    Canvas(modifier = modifier) {
        val center = Offset(size.width / 2f, size.height / 2f)

        if (!isTracking) {
            // State: Invalid Target / Paused (Dimmed amber ring with cross)
            val ringRadius = 14.dp.toPx()
            drawCircle(
                color = Color(0xFFFFA726).copy(alpha = 0.5f),
                radius = ringRadius,
                center = center,
                style = Stroke(width = 1.5.dp.toPx())
            )
            val crossSize = 5.dp.toPx()
            drawLine(
                color = Color(0xFFFFA726).copy(alpha = 0.7f),
                start = Offset(center.x - crossSize, center.y - crossSize),
                end = Offset(center.x + crossSize, center.y + crossSize),
                strokeWidth = 1.5.dp.toPx()
            )
            drawLine(
                color = Color(0xFFFFA726).copy(alpha = 0.7f),
                start = Offset(center.x - crossSize, center.y + crossSize),
                end = Offset(center.x + crossSize, center.y - crossSize),
                strokeWidth = 1.5.dp.toPx()
            )
        } else when (reticleTargetState) {
            ReticleTargetState.VALID_SURFACE -> {
                // State: Validated Surface Targeted (Focused Dual Rings with Cyan Core & Precision Crosshairs)
                val outerRadius = 20.dp.toPx()
                val innerRadius = 12.dp.toPx()
                val dotRadius = 3.5.dp.toPx()

                // Outer target ring
                drawCircle(
                    color = Color(0xFF00E5FF).copy(alpha = 0.9f),
                    radius = outerRadius,
                    center = center,
                    style = Stroke(width = 2.dp.toPx())
                )

                // Inner guide ring
                drawCircle(
                    color = Color.White.copy(alpha = 0.5f),
                    radius = innerRadius,
                    center = center,
                    style = Stroke(width = 1.dp.toPx())
                )

                // Center targeting dot
                drawCircle(
                    color = Color(0xFF00E5FF),
                    radius = dotRadius,
                    center = center
                )

                // 4 Focus tick marks
                val tickLength = 5.dp.toPx()
                val tickOffset = outerRadius + 2.dp.toPx()
                drawLine(Color(0xFF00E5FF), Offset(center.x, center.y - tickOffset), Offset(center.x, center.y - tickOffset - tickLength), 2.dp.toPx())
                drawLine(Color(0xFF00E5FF), Offset(center.x, center.y + tickOffset), Offset(center.x, center.y + tickOffset + tickLength), 2.dp.toPx())
                drawLine(Color(0xFF00E5FF), Offset(center.x - tickOffset, center.y), Offset(center.x - tickOffset - tickLength, center.y), 2.dp.toPx())
                drawLine(Color(0xFF00E5FF), Offset(center.x + tickOffset, center.y), Offset(center.x + tickOffset + tickLength, center.y), 2.dp.toPx())
            }
            ReticleTargetState.ANALYZING_SURFACE -> {
                // State: Analyzing Surface Stability (Amber Ring + Focus Dot)
                val ringRadius = 16.dp.toPx()
                drawCircle(
                    color = Color(0xFFF59E0B).copy(alpha = 0.85f),
                    radius = ringRadius,
                    center = center,
                    style = Stroke(width = 1.5.dp.toPx())
                )

                // Center amber dot
                drawCircle(
                    color = Color(0xFFF59E0B),
                    radius = 2.5.dp.toPx(),
                    center = center
                )

                // 4 Small tick marks
                val tickLength = 3.dp.toPx()
                val tickOffset = ringRadius + 2.dp.toPx()
                drawLine(Color(0xFFF59E0B).copy(alpha = 0.8f), Offset(center.x, center.y - tickOffset), Offset(center.x, center.y - tickOffset - tickLength), 1.5.dp.toPx())
                drawLine(Color(0xFFF59E0B).copy(alpha = 0.8f), Offset(center.x, center.y + tickOffset), Offset(center.x, center.y + tickOffset + tickLength), 1.5.dp.toPx())
                drawLine(Color(0xFFF59E0B).copy(alpha = 0.8f), Offset(center.x - tickOffset, center.y), Offset(center.x - tickOffset - tickLength, center.y), 1.5.dp.toPx())
                drawLine(Color(0xFFF59E0B).copy(alpha = 0.8f), Offset(center.x + tickOffset, center.y), Offset(center.x + tickOffset + tickLength, center.y), 1.5.dp.toPx())
            }
            ReticleTargetState.SEARCHING -> {
                // State: Idle / Scanning (+ Crosshair with clean subtle ring)
                val ringRadius = 14.dp.toPx()
                drawCircle(
                    color = Color.White.copy(alpha = 0.65f),
                    radius = ringRadius,
                    center = center,
                    style = Stroke(width = 1.5.dp.toPx())
                )

                // Tiny center pip
                drawCircle(
                    color = Color.White.copy(alpha = 0.85f),
                    radius = 1.8.dp.toPx(),
                    center = center
                )

                // Subtle crosshair ticks
                val tickStart = ringRadius + 2.dp.toPx()
                val tickLength = 4.dp.toPx()
                drawLine(Color.White.copy(alpha = 0.65f), Offset(center.x, center.y - tickStart), Offset(center.x, center.y - tickStart - tickLength), 1.5.dp.toPx())
                drawLine(Color.White.copy(alpha = 0.65f), Offset(center.x, center.y + tickStart), Offset(center.x, center.y + tickStart + tickLength), 1.5.dp.toPx())
                drawLine(Color.White.copy(alpha = 0.65f), Offset(center.x - tickStart, center.y), Offset(center.x - tickStart - tickLength, center.y), 1.5.dp.toPx())
                drawLine(Color.White.copy(alpha = 0.65f), Offset(center.x + tickStart, center.y), Offset(center.x + tickStart + tickLength, center.y), 1.5.dp.toPx())
            }
        }
    }
}

/**
 * Step 3 CORRECTION: Contextual instructional feedback banner.
 * Communicates scanning, analyzing stability, and validated placement readiness.
 */
@Composable
fun ArSurfaceFeedbackBanner(
    reticleTargetState: ReticleTargetState,
    hasValidatedSurface: Boolean,
    validatedSurfaceCount: Int,
    rawPlaneCount: Int,
    modifier: Modifier = Modifier
) {
    val isTargetingValid = reticleTargetState == ReticleTargetState.VALID_SURFACE
    val isAnalyzing = reticleTargetState == ReticleTargetState.ANALYZING_SURFACE

    val borderColor = when {
        isTargetingValid -> Color(0xFF00E5FF).copy(alpha = 0.7f)
        isAnalyzing -> Color(0xFFF59E0B).copy(alpha = 0.6f)
        else -> Color(0x33FFFFFF)
    }

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = if (isTargetingValid || isAnalyzing) Color(0xF20B1120) else Color(0xD90B1120),
        border = BorderStroke(1.dp, borderColor),
        contentColor = Color.White,
        tonalElevation = 6.dp,
        modifier = modifier
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp)
        ) {
            when (reticleTargetState) {
                ReticleTargetState.VALID_SURFACE -> {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF00E5FF))
                    )
                    Column {
                        Text(
                            text = stringResource(R.string.feedback_surface_detected),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Text(
                            text = stringResource(R.string.feedback_ready_to_place),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color(0xFF80D8FF)
                        )
                    }
                }
                ReticleTargetState.ANALYZING_SURFACE -> {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(Color(0xFFF59E0B))
                    )
                    Column {
                        Text(
                            text = stringResource(R.string.feedback_analyzing_surface),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White
                        )
                        Text(
                            text = "Hold steady to confirm stability",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Normal,
                            color = Color(0xFFFDE68A)
                        )
                    }
                }
                ReticleTargetState.SEARCHING -> {
                    if (hasValidatedSurface) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF38BDF8))
                        )
                        Text(
                            text = stringResource(R.string.feedback_target_surface_prompt),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color(0xFFE2E8F0)
                        )
                        Text(
                            text = "(${validatedSurfaceCount} ready)",
                            fontSize = 11.sp,
                            color = Color(0xFF94A3B8)
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFF59E0B))
                        )
                        Text(
                            text = stringResource(R.string.feedback_scan_prompt),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Normal,
                            color = Color(0xFFE2E8F0)
                        )
                    }
                }
            }
        }
    }
}

/**
 * Step 3 CORRECTION: Bottom interaction area with active validated surface telemetry.
 */
@Composable
fun ArBottomSurfaceBar(
    planesTelemetry: PlanesTelemetry,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = Color(0xE60B1120),
        border = BorderStroke(1.dp, Color(0x33FFFFFF)),
        contentColor = Color.White,
        tonalElevation = 8.dp,
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Surface(
                    shape = CircleShape,
                    color = Color(0xFF1E293B),
                    modifier = Modifier.size(36.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Layers,
                            contentDescription = null,
                            tint = if (planesTelemetry.hasValidatedSurface) Color(0xFF00E5FF) else Color(0xFF38BDF8),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                Column {
                    Text(
                        text = stringResource(R.string.feedback_surface_active),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White
                    )
                    Text(
                        text = if (planesTelemetry.hasValidatedSurface) {
                            "${planesTelemetry.validatedSurfaceCount} validated surface${if (planesTelemetry.validatedSurfaceCount > 1) "s" else ""} (${planesTelemetry.rawPlaneCount} raw planes)"
                        } else {
                            "Detecting floors, tables and walls (${planesTelemetry.rawPlaneCount} raw)"
                        },
                        fontSize = 11.sp,
                        color = Color(0xFF94A3B8)
                    )
                }
            }

            // Surface Count Indicators
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (planesTelemetry.validatedHorizontalCount > 0) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0xFF0F2B48),
                        border = BorderStroke(1.dp, Color(0xFF38BDF8).copy(alpha = 0.5f))
                    ) {
                        Text(
                            text = "Floor: ${planesTelemetry.validatedHorizontalCount}",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color(0xFF7DD3FC),
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }

                if (planesTelemetry.validatedVerticalCount > 0) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0xFF2E1B4E),
                        border = BorderStroke(1.dp, Color(0xFFA78BFA).copy(alpha = 0.5f))
                    ) {
                        Text(
                            text = "Wall: ${planesTelemetry.validatedVerticalCount}",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color(0xFFC4B5FD),
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }

                if (planesTelemetry.validatedSurfaceCount == 0) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0xFF261D11),
                        border = BorderStroke(1.dp, Color(0xFFF59E0B).copy(alpha = 0.4f))
                    ) {
                        Text(
                            text = if (planesTelemetry.rawPlaneCount > 0) "Analyzing..." else "Scanning...",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Normal,
                            color = Color(0xFFFDE68A),
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }
        }
    }
}

/**
 * Step 3: Settings & Diagnostics Modal Bottom Sheet.
 * Provides access to detailed plane metrics, 6-DoF pose telemetry, and dev HUD toggle.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArSettingsDiagnosticsSheet(
    pose: CameraPoseData,
    planesTelemetry: PlanesTelemetry,
    showHudOnScreen: Boolean,
    onToggleHudOnScreen: (Boolean) -> Unit,
    showCandidateOutlines: Boolean = false,
    onToggleCandidateOutlines: ((Boolean) -> Unit)? = null,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xFF0F172A),
        contentColor = Color.White,
        tonalElevation = 8.dp,
        modifier = Modifier.testTag("ar_diagnostics_sheet")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 8.dp)
        ) {
            // Header
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = stringResource(R.string.settings_title),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        tint = Color.White.copy(alpha = 0.7f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Section 1: Surface Validation & Tracking
            Text(
                text = stringResource(R.string.settings_section_surface),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF38BDF8),
                letterSpacing = 1.sp
            )
            Spacer(modifier = Modifier.height(8.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                MetricCard(
                    title = "Validated",
                    value = "${planesTelemetry.validatedSurfaceCount}",
                    modifier = Modifier.weight(1f)
                )
                MetricCard(
                    title = "Raw Planes",
                    value = "${planesTelemetry.rawPlaneCount}",
                    modifier = Modifier.weight(1f)
                )
                MetricCard(
                    title = "Floors / Walls",
                    value = "${planesTelemetry.validatedHorizontalCount} / ${planesTelemetry.validatedVerticalCount}",
                    modifier = Modifier.weight(1.2f)
                )
            }

            if (planesTelemetry.validatedSurfaces.isNotEmpty()) {
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "VALIDATED SURFACES (USABLE)",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF80D8FF),
                    letterSpacing = 0.5.sp
                )
                Spacer(modifier = Modifier.height(4.dp))
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(130.dp)
                ) {
                    items(planesTelemetry.validatedSurfaces) { surface ->
                        ValidatedSurfaceItemRow(surface = surface)
                    }
                }
            } else if (planesTelemetry.rawPlaneCount > 0) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Analyzing ${planesTelemetry.rawPlaneCount} raw candidate planes for stability and minimum area...",
                    fontSize = 11.sp,
                    color = Color(0xFFFDE68A)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider(color = Color.White.copy(alpha = 0.12f))
            Spacer(modifier = Modifier.height(14.dp))

            // Section 2: Motion Tracking (6-DoF Telemetry)
            Text(
                text = stringResource(R.string.settings_section_motion),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFFA78BFA),
                letterSpacing = 1.sp
            )
            Spacer(modifier = Modifier.height(8.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                MetricCard(
                    title = "Pos (X, Y, Z)",
                    value = String.format(Locale.US, "%.2f, %.2f, %.2f", pose.translationX, pose.translationY, pose.translationZ),
                    modifier = Modifier.weight(1.5f)
                )
                MetricCard(
                    title = "Pitch / Yaw",
                    value = String.format(Locale.US, "%.1f° / %.1f°", pose.pitch, pose.yaw),
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider(color = Color.White.copy(alpha = 0.12f))
            Spacer(modifier = Modifier.height(14.dp))

            // Section 3: Developer Options
            Text(
                text = "DEVELOPER CONTROLS",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF34D399),
                letterSpacing = 1.sp
            )
            Spacer(modifier = Modifier.height(8.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.settings_overlay_toggle),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color.White
                    )
                    Text(
                        text = "Real-time 6-DoF pose & surface stats on camera",
                        fontSize = 11.sp,
                        color = Color(0xFF94A3B8)
                    )
                }

                Switch(
                    checked = showHudOnScreen,
                    onCheckedChange = onToggleHudOnScreen,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = Color(0xFF38BDF8)
                    ),
                    modifier = Modifier.testTag("ar_hud_toggle")
                )
            }

            if (onToggleCandidateOutlines != null) {
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Show raw candidates & clutter",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color.White
                        )
                        Text(
                            text = "Render unvalidated ARCore planes in yellow outlines",
                            fontSize = 11.sp,
                            color = Color(0xFF94A3B8)
                        )
                    }

                    Switch(
                        checked = showCandidateOutlines,
                        onCheckedChange = onToggleCandidateOutlines,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = Color(0xFFF59E0B)
                        ),
                        modifier = Modifier.testTag("ar_raw_planes_toggle")
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            Button(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.settings_close))
            }

            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

@Composable
private fun MetricCard(
    title: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = Color(0xFF1E293B),
        border = BorderStroke(1.dp, Color(0x26FFFFFF)),
        modifier = modifier
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Text(
                text = title,
                fontSize = 10.sp,
                color = Color(0xFF94A3B8)
            )
            Text(
                text = value,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                color = Color.White
            )
        }
    }
}

@Composable
private fun ValidatedSurfaceItemRow(surface: ValidatedSurface) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = Color(0xFF1E293B).copy(alpha = 0.7f),
        border = BorderStroke(1.dp, Color(0x2600E5FF)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(if (surface.type.isHorizontal) Color(0xFF00E5FF) else Color(0xFFA78BFA))
                )
                Text(
                    text = surface.type.displayName,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color.White
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = String.format(Locale.US, "%.2f × %.2f m (%.2f m²)", surface.extentX, surface.extentZ, surface.polygonAreaSquareMeters),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = Color(0xFF94A3B8)
                )

                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = when (surface.confidence) {
                        SurfaceConfidence.VALID -> Color(0xFF00E5FF).copy(alpha = 0.2f)
                        SurfaceConfidence.STABILIZING, SurfaceConfidence.CANDIDATE -> Color(0xFFF59E0B).copy(alpha = 0.2f)
                        else -> Color(0xFFE53935).copy(alpha = 0.2f)
                    }
                ) {
                    Text(
                        text = surface.confidence.name,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = when (surface.confidence) {
                            SurfaceConfidence.VALID -> Color(0xFF00E5FF)
                            SurfaceConfidence.STABILIZING, SurfaceConfidence.CANDIDATE -> Color(0xFFF59E0B)
                            else -> Color(0xFFE53935)
                        },
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun PlaneItemRow(plane: DetectedPlaneData) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = Color(0xFF1E293B).copy(alpha = 0.6f),
        border = BorderStroke(1.dp, Color(0x1AFFFFFF)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(if (plane.type.isHorizontal) Color(0xFF38BDF8) else Color(0xFFA78BFA))
                )
                Text(
                    text = plane.type.displayName,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color.White
                )
            }

            Text(
                text = String.format(Locale.US, "%.2f × %.2f m  (%.2f m)", plane.extentX, plane.extentZ, plane.distanceFromCamera),
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = Color(0xFF94A3B8)
            )
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
                    modifier = Modifier.size(56.dp),
                    tint = MaterialTheme.colorScheme.primary
                )

                Text(
                    text = stringResource(R.string.camera_permission_required_title),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )

                Text(
                    text = stringResource(R.string.camera_permission_required_description),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

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
 * Overlay shown when ARCore installation is requested.
 */
@Composable
fun ArInstallPromptOverlay(
    onInstall: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
        shape = RoundedCornerShape(16.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(20.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(36.dp)
            )
            Text(
                text = stringResource(R.string.arcore_installing),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center
            )
            Button(
                onClick = onInstall,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(text = stringResource(R.string.retry_button))
            }
        }
    }
}

/**
 * Overlay shown when an AR error or unsupported device state occurs.
 */
@Composable
fun ArErrorOverlay(
    state: ArSessionState,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
        shape = RoundedCornerShape(16.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(20.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(36.dp)
            )
            val message = when (state) {
                is ArSessionState.UnsupportedDevice -> stringResource(R.string.arcore_not_supported)
                is ArSessionState.Error -> state.message
                else -> stringResource(R.string.arcore_not_supported)
            }
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            val canRetry = (state as? ArSessionState.Error)?.canRetry ?: false
            if (canRetry) {
                OutlinedButton(
                    onClick = onRetry,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = stringResource(R.string.retry_button)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = stringResource(R.string.retry_button))
                }
            }
        }
    }
}

/**
 * Step 2: Development / Debug Overlay for Motion Tracking.
 * Displays Tracking State, Camera Position (X, Y, Z), and Camera Orientation.
 */
@Composable
fun ArMotionDebugOverlay(
    state: ArSessionState,
    pose: CameraPoseData,
    planesTelemetry: PlanesTelemetry? = null,
    onClose: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    var isExpanded by remember { mutableStateOf(true) }

    val (trackingState, failureReason) = when (state) {
        is ArSessionState.Active -> state.trackingState to state.failureReason
        is ArSessionState.Paused -> TrackingState.PAUSED to TrackingFailureReason.NONE
        else -> TrackingState.STOPPED to TrackingFailureReason.NONE
    }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = Color.Black.copy(alpha = 0.85f),
        contentColor = Color.White,
        tonalElevation = 8.dp,
        modifier = modifier
            .fillMaxWidth()
            .testTag("ar_debug_overlay")
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // Header: Title, Tracking State Badge & Expand Toggle
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(
                                when (trackingState) {
                                    TrackingState.TRACKING -> Color(0xFF4CAF50)
                                    TrackingState.PAUSED -> Color(0xFFFFB300)
                                    TrackingState.STOPPED -> Color(0xFFE53935)
                                }
                            )
                    )
                    Text(
                        text = "MOTION TRACKING",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                        color = Color.White
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val badgeColor = when (trackingState) {
                        TrackingState.TRACKING -> Color(0xFF4CAF50)
                        TrackingState.PAUSED -> Color(0xFFFFB300)
                        TrackingState.STOPPED -> Color(0xFFE53935)
                    }
                    val badgeLabel = when (trackingState) {
                        TrackingState.TRACKING -> stringResource(R.string.debug_tracking_normal)
                        TrackingState.PAUSED -> stringResource(R.string.debug_tracking_paused)
                        TrackingState.STOPPED -> stringResource(R.string.debug_tracking_stopped)
                    }

                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = badgeColor.copy(alpha = 0.25f),
                        border = BorderStroke(1.dp, badgeColor.copy(alpha = 0.6f))
                    ) {
                        Text(
                            text = badgeLabel,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = badgeColor,
                            modifier = Modifier
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                                .testTag("ar_tracking_state_text")
                        )
                    }

                    IconButton(
                        onClick = { isExpanded = !isExpanded },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                            contentDescription = if (isExpanded) "Collapse debug info" else "Expand debug info",
                            tint = Color.White.copy(alpha = 0.7f),
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    if (onClose != null) {
                        IconButton(
                            onClick = onClose,
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Close HUD",
                                tint = Color.White.copy(alpha = 0.7f),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }

            // Limited / Paused Warning Banner
            if (trackingState != TrackingState.TRACKING || failureReason != TrackingFailureReason.NONE) {
                Spacer(modifier = Modifier.height(8.dp))
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xFF332200),
                    border = BorderStroke(1.dp, Color(0xFFFFB300).copy(alpha = 0.5f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = Color(0xFFFFB300),
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        val advice = when (failureReason) {
                            TrackingFailureReason.EXCESSIVE_MOTION -> "Device moving too fast. Move slower."
                            TrackingFailureReason.INSUFFICIENT_LIGHT -> "Environment too dark. Move to brighter area."
                            TrackingFailureReason.INSUFFICIENT_FEATURES -> "Point camera at textured surfaces (avoid blank walls)."
                            TrackingFailureReason.BAD_STATE -> "System busy or sensor calibrating."
                            TrackingFailureReason.CAMERA_UNAVAILABLE -> "Camera sensor unavailable."
                            TrackingFailureReason.NONE -> "Tracking paused. Repositioning..."
                        }
                        Text(
                            text = advice,
                            fontSize = 11.sp,
                            color = Color(0xFFFFE082),
                            lineHeight = 14.sp
                        )
                    }
                }
            }

            AnimatedVisibility(visible = isExpanded) {
                Column {
                    Spacer(modifier = Modifier.height(10.dp))
                    HorizontalDivider(color = Color.White.copy(alpha = 0.15f))
                    Spacer(modifier = Modifier.height(10.dp))

                    // Position (X, Y, Z in meters)
                    Text(
                        text = "CAMERA POSITION (METERS)",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF90CAF9),
                        letterSpacing = 0.5.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        PoseMetricColumn(
                            label = "X (L/R)",
                            value = String.format(Locale.US, "%+6.3f m", pose.translationX),
                            testTag = "camera_pose_x"
                        )
                        PoseMetricColumn(
                            label = "Y (D/U)",
                            value = String.format(Locale.US, "%+6.3f m", pose.translationY),
                            testTag = "camera_pose_y"
                        )
                        PoseMetricColumn(
                            label = "Z (F/B)",
                            value = String.format(Locale.US, "%+6.3f m", pose.translationZ),
                            testTag = "camera_pose_z"
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Orientation (Pitch, Yaw, Roll in degrees)
                    Text(
                        text = "CAMERA ORIENTATION (EULER / ROTATION)",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFB39DDB),
                        letterSpacing = 0.5.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        PoseMetricColumn(
                            label = "Pitch (Tilt)",
                            value = String.format(Locale.US, "%+5.1f°", pose.pitch),
                            testTag = "camera_pose_pitch"
                        )
                        PoseMetricColumn(
                            label = "Yaw (Pan)",
                            value = String.format(Locale.US, "%+5.1f°", pose.yaw),
                            testTag = "camera_pose_yaw"
                        )
                        PoseMetricColumn(
                            label = "Roll",
                            value = String.format(Locale.US, "%+5.1f°", pose.roll),
                            testTag = "camera_pose_roll"
                        )
                    }

                    if (planesTelemetry != null) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = "VALIDATED SURFACES: ${planesTelemetry.validatedSurfaceCount} (Floor: ${planesTelemetry.validatedHorizontalCount}, Wall: ${planesTelemetry.validatedVerticalCount}) | Raw: ${planesTelemetry.rawPlaneCount}",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF80DEEA),
                            letterSpacing = 0.5.sp
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Quaternion & Displacement telemetry footer
                    Row(
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = String.format(
                                Locale.US,
                                "Q: [%.2f, %.2f, %.2f, %.2f]",
                                pose.qx, pose.qy, pose.qz, pose.qw
                            ),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            color = Color.White.copy(alpha = 0.6f)
                        )
                        Text(
                            text = String.format(Locale.US, "Dist: %.3f m", pose.distanceFromOrigin),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFFA5D6A7)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PoseMetricColumn(
    label: String,
    value: String,
    testTag: String
) {
    Column {
        Text(
            text = label,
            fontSize = 10.sp,
            color = Color.White.copy(alpha = 0.55f)
        )
        Text(
            text = value,
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = Color.White,
            modifier = Modifier.testTag(testTag)
        )
    }
}
