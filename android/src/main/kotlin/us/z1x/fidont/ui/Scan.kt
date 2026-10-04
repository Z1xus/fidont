package us.z1x.fidont.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BinaryBitmap
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import us.z1x.fidont.R
import us.z1x.fidont.hybrid.Qr
import us.z1x.fidont.transport.hybrid.HybridTransport

private val PERMISSIONS = arrayOf(Manifest.permission.CAMERA, Manifest.permission.BLUETOOTH_ADVERTISE)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Scan(
    onClose: () -> Unit,
    onFailed: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var connecting by remember { mutableStateOf(false) }
    var allowed by remember {
        mutableStateOf(PERMISSIONS.all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED })
    }
    val allow =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
            allowed = granted.values.all { it }
        }

    LaunchedEffect(Unit) {
        if (!allowed) allow.launch(PERMISSIONS)
    }
    BackHandler(onBack = onClose)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.scan)) },
                navigationIcon = {
                    IconButton(onClick = onClose) { Icon(painterResource(R.drawable.ic_back), stringResource(R.string.back)) }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
            when {
                connecting -> {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        CircularProgressIndicator()
                        Text(stringResource(R.string.scan_connecting))
                    }
                }

                allowed -> {
                    Camera { text ->
                        val qr = Qr.parse(text)
                        if (qr != null && !connecting) {
                            connecting = true
                            scope.launch(Dispatchers.Default) {
                                if (HybridTransport(context).serve(qr)) onClose() else onFailed()
                            }
                        }
                    }
                    Text(stringResource(R.string.scan_hint), Modifier.align(Alignment.BottomCenter).padding(24.dp))
                }

                else -> {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text(stringResource(R.string.scan_permissions))
                        Button(onClick = { allow.launch(PERMISSIONS) }) { Text(stringResource(R.string.allow)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun Camera(onText: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    var surface by remember { mutableStateOf<SurfaceRequest?>(null) }

    LaunchedEffect(Unit) {
        val provider = ProcessCameraProvider.awaitInstance(context)
        val preview = Preview.Builder().build().apply { setSurfaceProvider { surface = it } }
        val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
        analysis.setAnalyzer(Dispatchers.Default.asExecutor()) { image -> image.use { read(it)?.let(onText) } }
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
