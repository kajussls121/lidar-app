package com.ks.lidarmeasure.ui

import android.Manifest
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.ks.lidarmeasure.R
import com.ks.lidarmeasure.ar.ArFrameInfo
import com.ks.lidarmeasure.ar.ArMath
import com.ks.lidarmeasure.ar.ArMeasureView
import com.ks.lidarmeasure.ar.ArPoint
import com.ks.lidarmeasure.ar.ArStatus
import com.ks.lidarmeasure.measure.DistanceFormatter
import com.ks.lidarmeasure.measure.MeasureMath
import com.ks.lidarmeasure.measure.MeasureUnit
import kotlinx.coroutines.delay

/** UI state mirrored from the AR view, which owns the session on its GL thread. */
@Stable
class MeasureUiState {
    var frame by mutableStateOf<ArFrameInfo?>(null)
    var status by mutableStateOf(ArStatus.INITIALIZING)
    var points by mutableStateOf<List<ArPoint>>(emptyList())
    var units by mutableStateOf(MeasureUnit.METRIC)
    var hintRes by mutableStateOf<Int?>(null)
    var hintTick by mutableIntStateOf(0)
    var hasSurface by mutableStateOf(false)
}

@Composable
fun MeasureScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val activity = remember(context) { context.findComponentActivity() }
    val state = remember { MeasureUiState() }

    var cameraGranted by remember { mutableStateOf(context.hasCameraPermission()) }
    var arView by remember { mutableStateOf<ArMeasureView?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> cameraGranted = granted }

    LaunchedEffect(Unit) {
        if (!cameraGranted) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    LaunchedEffect(state.hintTick) {
        if (state.hintTick > 0) {
            delay(2600)
            state.hintRes = null
        }
    }

    DisposableEffect(activity, cameraGranted) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> if (cameraGranted) {
                    activity?.let { arView?.resumeAr(it) }
                }

                Lifecycle.Event.ON_PAUSE -> arView?.pauseAr()
                else -> Unit
            }
        }
        activity?.lifecycle?.addObserver(observer)
        onDispose {
            activity?.lifecycle?.removeObserver(observer)
            arView?.closeAr()
        }
    }

    LaunchedEffect(arView, cameraGranted) {
        if (cameraGranted) activity?.let { arView?.resumeAr(it) }
    }

    Box(modifier = modifier.fillMaxSize().background(Color.Black)) {
        if (cameraGranted) {
            AndroidView(
                factory = { ctx ->
                    ArMeasureView(ctx).also { view ->
                        view.listener = object : ArMeasureView.Listener {
                            override fun onFrame(frame: ArFrameInfo) {
                                state.frame = frame
                                if (state.hasSurface != (frame.reticleHit != null)) {
                                    state.hasSurface = frame.reticleHit != null
                                }
                            }

                            override fun onStatusChanged(status: ArStatus) {
                                state.status = status
                            }

                            override fun onPointsChanged(points: List<ArPoint>) {
                                state.points = points
                            }

                            override fun onHint(hintRes: Int) {
                                state.hintRes = hintRes
                                state.hintTick += 1
                            }
                        }
                        arView = view
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )

            MeasureOverlay(
                state = state,
                onTap = { x, y -> arView?.addPointAt(x, y) },
            )
        }

        StatusPill(
            state = state,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        )

        if (cameraGranted) {
            BottomPanel(
                state = state,
                onAdd = { arView?.addPointAtReticle() },
                onUndo = { arView?.undo() },
                onClear = { arView?.clearAll() },
                onToggleUnits = {
                    state.units = if (state.units == MeasureUnit.METRIC) {
                        MeasureUnit.IMPERIAL
                    } else {
                        MeasureUnit.METRIC
                    }
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(16.dp),
            )
        }

        BlockingMessage(state = state, cameraGranted = cameraGranted) { action ->
            when (action) {
                BlockingAction.GRANT_CAMERA -> permissionLauncher.launch(Manifest.permission.CAMERA)
                BlockingAction.RETRY -> activity?.let { arView?.retry(it) }
            }
        }
    }
}

private enum class BlockingAction { GRANT_CAMERA, RETRY }

@Composable
private fun StatusPill(state: MeasureUiState, modifier: Modifier = Modifier) {
    val hint = state.hintRes
    val text = when {
        hint != null -> stringResource(hint)
        state.status == ArStatus.TRACKING && !state.hasSurface -> stringResource(R.string.status_aim_surface)
        state.status == ArStatus.TRACKING && state.points.isEmpty() -> stringResource(R.string.status_first_point)
        state.status == ArStatus.TRACKING -> stringResource(R.string.status_next_point)
        state.status == ArStatus.INITIALIZING -> stringResource(R.string.status_initializing)
        else -> stringResource(R.string.status_searching)
    }

    Surface(color = Color(0xCC000000), shape = RoundedCornerShape(50), modifier = modifier) {
        Text(
            text = text,
            color = Color.White,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun BottomPanel(
    state: MeasureUiState,
    onAdd: () -> Unit,
    onUndo: () -> Unit,
    onClear: () -> Unit,
    onToggleUnits: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val points = state.points
    val units = state.units
    val tracking = state.status == ArStatus.TRACKING
    val placeholder = stringResource(R.string.readout_placeholder)

    val readout by remember(points, units, placeholder) {
        derivedStateOf {
            val frame = state.frame
            val live = if (frame != null && frame.status == ArStatus.TRACKING && points.isNotEmpty()) {
                frame.reticleHit?.let { target ->
                    points.last().distanceTo(target).takeIf { it > 0.004f }
                }
            } else {
                null
            }
            val metres = live
                ?: if (points.size >= 2) points[points.size - 2].distanceTo(points.last()) else null
            metres?.let { DistanceFormatter.format(it, units) } ?: placeholder
        }
    }
    val totalMetres by remember(points) { derivedStateOf { MeasureMath.totalMetres(points) } }

    Surface(color = PanelColor, shape = RoundedCornerShape(28.dp), modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = readout,
                    color = Color.White,
                    style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Bold),
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
                Spacer(Modifier.weight(1f))
                OutlinedButton(onClick = onToggleUnits) {
                    Text(
                        stringResource(
                            if (units == MeasureUnit.METRIC) R.string.unit_metric else R.string.unit_imperial,
                        ),
                    )
                }
            }

            if (points.size >= 2) {
                Text(
                    text = stringResource(R.string.total_label, DistanceFormatter.format(totalMetres, units)) +
                        " · " + stringResource(R.string.segments_label, points.size - 1),
                    color = Color.White.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = onAdd,
                    enabled = tracking,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AccentColor,
                        contentColor = Color.Black,
                    ),
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.button_add_point), fontWeight = FontWeight.SemiBold)
                }
                OutlinedButton(onClick = onUndo, enabled = points.isNotEmpty()) {
                    Text(stringResource(R.string.button_undo))
                }
                OutlinedButton(onClick = onClear, enabled = points.isNotEmpty()) {
                    Text(stringResource(R.string.button_clear))
                }
            }
        }
    }
}

@Composable
private fun BlockingMessage(
    state: MeasureUiState,
    cameraGranted: Boolean,
    onAction: (BlockingAction) -> Unit,
) {
    val resolved = when {
        !cameraGranted -> Triple(
            R.string.camera_title,
            R.string.camera_body,
            BlockingAction.GRANT_CAMERA,
        )

        state.status == ArStatus.UNSUPPORTED -> Triple(
            R.string.ar_unsupported_title,
            R.string.ar_unsupported_body,
            BlockingAction.RETRY,
        )

        state.status == ArStatus.AR_CORE_INSTALL_REQUIRED -> Triple(
            R.string.ar_install_title,
            R.string.ar_install_body,
            BlockingAction.RETRY,
        )

        state.status == ArStatus.CAMERA_UNAVAILABLE -> Triple(
            R.string.camera_unavailable_title,
            R.string.camera_unavailable_body,
            BlockingAction.RETRY,
        )

        state.status == ArStatus.ERROR -> Triple(
            R.string.ar_error_title,
            R.string.ar_error_body,
            BlockingAction.RETRY,
        )

        else -> null
    } ?: return

    val titleRes = resolved.first
    val bodyRes = resolved.second
    val action = resolved.third

    Box(
        modifier = Modifier.fillMaxSize().background(Color(0xE6000000)),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            color = Color(0xFF1C1C1C),
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier.padding(32.dp),
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(titleRes),
                    color = Color.White,
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    text = stringResource(bodyRes),
                    color = Color.White.copy(alpha = 0.85f),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(
                    onClick = { onAction(action) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AccentColor,
                        contentColor = Color.Black,
                    ),
                ) {
                    Text(
                        stringResource(
                            if (action == BlockingAction.GRANT_CAMERA) {
                                R.string.button_grant
                            } else {
                                R.string.button_retry
                            },
                        ),
                    )
                }
            }
        }
    }
}

private val AccentColor = Color(0xFFFFD54F)
private val PanelColor = Color(0xF20E0E0E)
private val MarkerFill = Color(0xFF1B1B1B)

/**
 * Draws the reticle, the placed points, the segments between them and the live segment
 * to whatever the reticle is aimed at. Reads the frame in the draw phase so the 60 Hz
 * camera stream does not recompose the UI.
 */
@Composable
private fun MeasureOverlay(state: MeasureUiState, onTap: (Float, Float) -> Unit) {
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = remember {
        TextStyle(color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
    val dash = remember { PathEffect.dashPathEffect(floatArrayOf(18f, 12f)) }

    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures { offset -> onTap(offset.x, offset.y) }
            },
    ) {
        val frame = state.frame ?: return@Canvas
        val centre = Offset(size.width / 2f, size.height / 2f)
        val tracking = frame.status == ArStatus.TRACKING

        drawCircle(
            color = Color.White.copy(alpha = 0.85f),
            radius = 8.dp.toPx(),
            center = centre,
            style = Stroke(width = 2.dp.toPx()),
        )
        if (tracking && frame.reticleHit != null) {
            drawCircle(color = AccentColor, radius = 3.dp.toPx(), center = centre)
        }

        if (!tracking || frame.viewportWidth <= 0 || frame.viewportHeight <= 0) return@Canvas

        val viewProjection = ArMath.multiply(frame.projectionMatrix, frame.viewMatrix)
        val scaleX = size.width / frame.viewportWidth
        val scaleY = size.height / frame.viewportHeight
        val buffer = FloatArray(2)

        fun toScreen(point: ArPoint): Offset? {
            val visible = ArMath.project(
                point = point,
                viewProjection = viewProjection,
                widthPx = frame.viewportWidth.toFloat(),
                heightPx = frame.viewportHeight.toFloat(),
                out = buffer,
            )
            return if (visible) Offset(buffer[0] * scaleX, buffer[1] * scaleY) else null
        }

        fun drawLabel(text: String, anchor: Offset) {
            val layout = textMeasurer.measure(AnnotatedString(text), style = labelStyle)
            val padH = 8.dp.toPx()
            val padV = 4.dp.toPx()
            val width = layout.size.width + padH * 2
            val height = layout.size.height + padV * 2
            val topLeft = Offset(
                x = (anchor.x - width / 2f).coerceIn(0f, (size.width - width).coerceAtLeast(0f)),
                y = (anchor.y - height - 10.dp.toPx()).coerceAtLeast(0f),
            )
            drawRoundRect(
                color = Color(0xD9000000),
                topLeft = topLeft,
                size = Size(width, height),
                cornerRadius = CornerRadius(8.dp.toPx()),
            )
            drawText(layout, topLeft = Offset(topLeft.x + padH, topLeft.y + padV))
        }

        val points = state.points
        val screenPoints = points.map { toScreen(it) }

        for (i in 1 until screenPoints.size) {
            val start = screenPoints[i - 1] ?: continue
            val end = screenPoints[i] ?: continue
            drawLine(Color.Black.copy(alpha = 0.45f), start, end, strokeWidth = 7.dp.toPx())
            drawLine(Color.White, start, end, strokeWidth = 3.dp.toPx())
            drawLabel(
                text = DistanceFormatter.format(points[i - 1].distanceTo(points[i]), state.units),
                anchor = Offset((start.x + end.x) / 2f, (start.y + end.y) / 2f),
            )
        }

        val reticle = frame.reticleHit
        val lastPoint = screenPoints.lastOrNull()
        if (reticle != null && lastPoint != null) {
            val target = toScreen(reticle) ?: centre
            drawLine(
                color = Color.Black.copy(alpha = 0.35f),
                start = lastPoint,
                end = target,
                strokeWidth = 6.dp.toPx(),
                pathEffect = dash,
            )
            drawLine(
                color = AccentColor,
                start = lastPoint,
                end = target,
                strokeWidth = 2.5.dp.toPx(),
                pathEffect = dash,
            )
        }

        screenPoints.forEachIndexed { index, position ->
            if (position == null) return@forEachIndexed
            drawCircle(Color.Black.copy(alpha = 0.5f), radius = 10.dp.toPx(), center = position)
            drawCircle(
                color = if (points[index].tracked) Color.White else AccentColor,
                radius = 7.dp.toPx(),
                center = position,
            )
            drawCircle(MarkerFill, radius = 3.dp.toPx(), center = position)
        }
    }
}


private fun Context.hasCameraPermission(): Boolean =
    ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

private fun Context.findComponentActivity(): ComponentActivity? {
    var current: Context = this
    while (current is ContextWrapper) {
        if (current is ComponentActivity) return current
        current = current.baseContext
    }
    return null
}
