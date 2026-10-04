package us.z1x.fidont.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.compose.CameraXViewfinder
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BinaryBitmap
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import us.z1x.fidont.R
import us.z1x.fidont.hybrid.Qr
import us.z1x.fidont.transport.hybrid.HybridTransport
import kotlin.time.Duration.Companion.milliseconds

private val PERMISSIONS = arrayOf(Manifest.permission.CAMERA, Manifest.permission.BLUETOOTH_ADVERTISE)
private const val FRAME = 0.68f
private const val SCRIM = 0.6f
private val DONE = 900.milliseconds

enum class ScanState { Denied, Scanning, Connecting, Failed, Done }

@Composable
fun Scan(onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val granted = PERMISSIONS.all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    var state by remember { mutableStateOf(if (granted) ScanState.Scanning else ScanState.Denied) }
    var back by remember { mutableFloatStateOf(0f) }
    val allow =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (result.values.all { it }) state = ScanState.Scanning
        }

    LaunchedEffect(Unit) {
        if (!granted) allow.launch(PERMISSIONS)
    }
    PredictiveBackHandler { progress ->
        try {
            progress.collect { back = it.progress }
            onClose()
        } catch (e: CancellationException) {
            back = 0f
            throw e
        }
    }

    ScanScreen(
        state = state,
        onBack = onClose,
        onAllow = { allow.launch(PERMISSIONS) },
        onRetry = { state = ScanState.Scanning },
        modifier =
            Modifier.graphicsLayer {
                scaleX = 1f - back / 10
                scaleY = 1f - back / 10
                shape = RoundedCornerShape((32 * back).dp)
                clip = true
            },
    ) {
        Camera { text ->
            val qr = Qr.parse(text)
            if (qr != null && state == ScanState.Scanning) {
                state = ScanState.Connecting
                scope.launch {
                    haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                    if (withContext(Dispatchers.Default) { HybridTransport(context).serve(qr) }) {
                        state = ScanState.Done
                        delay(DONE)
                        onClose()
                    } else {
                        haptics.performHapticFeedback(HapticFeedbackType.Reject)
                        state = ScanState.Failed
                    }
                }
            }
        }
    }
}

@Composable
fun ScanScreen(
    state: ScanState,
    onBack: () -> Unit,
    onAllow: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    camera: @Composable () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val denied = state == ScanState.Denied
    BoxWithConstraints(modifier.fillMaxSize().background(Color.Black)) {
        val frame = minOf(maxWidth, maxHeight) * FRAME
        if (denied) {
            Surface(Modifier.fillMaxSize()) {
                Box(Modifier.padding(32.dp), contentAlignment = Alignment.Center) {
                    Message(R.string.scan_permissions, R.string.scan_permissions_body, R.drawable.ic_camera, R.string.allow, onAllow)
                }
            }
        } else {
            camera()
            Viewfinder(state)
        }
        FilledTonalIconButton(
            onClick = onBack,
            modifier = Modifier.statusBarsPadding().padding(8.dp),
            colors =
                if (denied) {
                    IconButtonDefaults.filledTonalIconButtonColors()
                } else {
                    IconButtonDefaults.filledTonalIconButtonColors(Color.Black.copy(alpha = SCRIM), Color.White)
                },
        ) {
            Icon(painterResource(R.drawable.ic_back), stringResource(R.string.back))
        }
        AnimatedVisibility(
            visible = state == ScanState.Scanning,
            modifier = Modifier.align(Alignment.Center).offset(y = frame / 2 + 40.dp),
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            Text(
                stringResource(R.string.scan_hint),
                Modifier.padding(horizontal = 32.dp),
                color = Color.White,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.titleMedium,
            )
        }
        AnimatedVisibility(
            visible = state == ScanState.Connecting || state == ScanState.Failed || state == ScanState.Done,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                color = scheme.surfaceContainerLow,
            ) {
                AnimatedContent(state, Modifier.navigationBarsPadding().padding(24.dp), label = "panel") { shown ->
                    when (shown) {
                        ScanState.Failed -> {
                            Message(R.string.scan_failed, R.string.scan_failed_body, R.drawable.ic_error, R.string.try_again, onRetry)
                        }

                        ScanState.Done -> {
                            Message(R.string.scan_done, icon = R.drawable.ic_check)
                        }

                        else -> {
                            Message(R.string.scan_connecting, R.string.scan_connecting_body)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Message(
    title: Int,
    body: Int? = null,
    icon: Int? = null,
    action: Int? = null,
    onClick: () -> Unit = {},
) {
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        if (icon == null) {
            Box(Modifier.size(56.dp).background(scheme.secondaryContainer, CircleShape), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
            }
        } else {
            IconBadge(icon, if (icon == R.drawable.ic_error) scheme.errorContainer else scheme.secondaryContainer, 56.dp)
        }
        Text(stringResource(title), Modifier.padding(top = 16.dp, bottom = 4.dp), style = MaterialTheme.typography.titleLarge)
        if (body != null) {
            Text(
                stringResource(body),
                color = scheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        if (action != null) {
            Button(onClick = onClick, Modifier.fillMaxWidth().padding(top = 24.dp)) { Text(stringResource(action)) }
        }
    }
}

@Composable
private fun Viewfinder(state: ScanState) {
    val found = state == ScanState.Connecting || state == ScanState.Done
    val color by animateColorAsState(if (found) MaterialTheme.colorScheme.primaryFixed else Color.White, label = "frame")
    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
        initialValue = 1f,
        targetValue = 1.04f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "pulse",
    )
    Canvas(Modifier.fillMaxSize().graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }) {
        val side = minOf(size.width, size.height) * FRAME * if (state == ScanState.Scanning) pulse else 1f
        val corner = CornerRadius(28.dp.toPx())
        val origin = Offset((size.width - side) / 2, (size.height - side) / 2)
        drawRect(Color.Black.copy(alpha = SCRIM))
        drawRoundRect(Color.Black, origin, Size(side, side), corner, blendMode = BlendMode.Clear)
        drawRoundRect(color, origin, Size(side, side), corner, style = Stroke(4.dp.toPx()))
    }
}

@Composable
private fun Camera(onText: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    val current by rememberUpdatedState(onText)
    var surface by remember { mutableStateOf<SurfaceRequest?>(null) }

    LaunchedEffect(Unit) {
        val provider = ProcessCameraProvider.awaitInstance(context)
        val preview = Preview.Builder().build().apply { setSurfaceProvider { surface = it } }
        val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
        analysis.setAnalyzer(Dispatchers.Default.asExecutor()) { image -> image.use { read(it)?.let(current) } }
        provider.bindToLifecycle(lifecycle, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
        try {
            awaitCancellation()
        } finally {
            provider.unbindAll()
        }
    }

    surface?.let { CameraXViewfinder(it, Modifier.fillMaxSize()) }
}

private fun read(image: ImageProxy): String? {
    val plane = image.planes[0]
    val pixels = ByteArray(plane.buffer.remaining()).also(plane.buffer::get)
    val source = PlanarYUVLuminanceSource(pixels, plane.rowStride, image.height, 0, 0, image.width, image.height, false)
    return try {
        QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source))).text
    } catch (_: ReaderException) {
        null
    }
}
