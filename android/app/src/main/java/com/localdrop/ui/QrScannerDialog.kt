package com.localdrop.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BinaryBitmap
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

@Composable
fun QrScannerDialog(
    title: String = "Scanner le QR Code Wi-Fi",
    onScanned: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val ctx = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var hasCam by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val camLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasCam = granted
    }

    LaunchedEffect(Unit) {
        if (!hasCam) camLauncher.launch(Manifest.permission.CAMERA)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (!hasCam) {
                    Text("Autorisez la caméra pour scanner le QR Code sans taper le mot de passe.")
                    Button(onClick = { camLauncher.launch(Manifest.permission.CAMERA) }) { Text("Autoriser Caméra") }
                } else {
                    val previewView = remember { PreviewView(ctx) }
                    val executor = remember { Executors.newSingleThreadExecutor() }
                    val handled = remember { AtomicBoolean(false) }

                    DisposableEffect(hasCam) {
                        val future = ProcessCameraProvider.getInstance(ctx)
                        var provider: ProcessCameraProvider? = null
                        future.addListener({
                            runCatching {
                                val p = future.get().also { provider = it }
                                val preview = Preview.Builder().build().apply {
                                    setSurfaceProvider(previewView.surfaceProvider)
                                }
                                val reader = QRCodeReader()
                                val analysis = ImageAnalysis.Builder()
                                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                    .build()
                                    .apply {
                                        setAnalyzer(executor) { img ->
                                            try {
                                                if (handled.get()) return@setAnalyzer
                                                val txt = decodeQr(img, reader) ?: return@setAnalyzer
                                                if (handled.compareAndSet(false, true)) {
                                                    previewView.post { onScanned(txt) }
                                                }
                                            } finally {
                                                img.close()
                                            }
                                        }
                                    }
                                p.unbindAll()
                                p.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                            }
                        }, ContextCompat.getMainExecutor(ctx))

                        onDispose {
                            runCatching { provider?.unbindAll() }
                            executor.shutdown()
                        }
                    }

                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxWidth()) {
                        AndroidView(
                            factory = { previewView },
                            modifier = Modifier.fillMaxWidth().height(260.dp).clip(RoundedCornerShape(16.dp))
                        )
                        Box(
                            modifier = Modifier
                                .size(170.dp)
                                .border(2.dp, MaterialTheme.colorScheme.secondary, RoundedCornerShape(14.dp))
                        )
                    }
                    Spacer(Modifier.height(2.dp))
                    Text("Visez le QR Code affiché sur l'autre téléphone", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } }
    )
}

private fun decodeQr(image: ImageProxy, reader: QRCodeReader): String? {
    val w = image.width
    val h = image.height
    if (w <= 0 || h <= 0 || image.planes.isEmpty()) return null
    val y = extractY(image)
    val src = PlanarYUVLuminanceSource(y, w, h, 0, 0, w, h, false)
    val bmp = BinaryBitmap(HybridBinarizer(src))
    return try {
        reader.decode(bmp).text
    } catch (_: Exception) {
        null
    } finally {
        reader.reset()
    }
}

private fun extractY(image: ImageProxy): ByteArray {
    val plane = image.planes[0]
    val buf = plane.buffer
    val w = image.width
    val h = image.height
    val rowStride = plane.rowStride
    val pixelStride = plane.pixelStride
    val out = ByteArray(w * h)
    if (pixelStride == 1 && rowStride == w) {
        buf.get(out, 0, w * h)
    } else {
        val row = ByteArray(rowStride)
        for (y in 0 until h) {
            val toRead = minOf(rowStride, buf.remaining())
            if (toRead <= 0) break
            buf.get(row, 0, toRead)
            if (pixelStride == 1) System.arraycopy(row, 0, out, y * w, minOf(w, toRead))
            else for (x in 0 until w) {
                val idx = x * pixelStride
                if (idx < toRead) out[y * w + x] = row[idx]
            }
        }
    }
    return out
}
