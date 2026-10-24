package com.gametimer.parent

import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors

/**
 * Camera preview that reports QR code texts (ML Kit, on-device; nothing is uploaded). The camera runs
 * only while this composable is on screen: leaving it unbinds the camera.
 */
@Composable
fun QrScanner(onText: (String) -> Unit, onNoCamera: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    val view = remember { PreviewView(context) }
    val currentOnText by rememberUpdatedState(onText)
    val currentOnNoCamera by rememberUpdatedState(onNoCamera)

    DisposableEffect(lifecycle) {
        val executor = Executors.newSingleThreadExecutor()
        val scanner = BarcodeScanning.getClient(BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build())
        val preview = Preview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }
        val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
        analysis.setAnalyzer(executor) { proxy ->
            val media = proxy.image
            if (media == null) { proxy.close(); return@setAnalyzer }
            scanner.process(InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees))
                .addOnSuccessListener { codes -> codes.firstNotNullOfOrNull { it.rawValue }?.let { currentOnText(it) } }
                .addOnCompleteListener { proxy.close() }
        }
        var disposed = false
        var provider: ProcessCameraProvider? = null
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            if (disposed) return@addListener // left the screen before the camera was ready
            val p = future.get()
            provider = p
            // The app installs on phones without a back camera (camera.any is optional): front, then none
            val selector = listOf(CameraSelector.DEFAULT_BACK_CAMERA, CameraSelector.DEFAULT_FRONT_CAMERA)
                .firstOrNull { runCatching { p.hasCamera(it) }.getOrDefault(false) }
            if (selector == null) { currentOnNoCamera(); return@addListener }
            try {
                p.bindToLifecycle(lifecycle, selector, preview, analysis)
            } catch (e: Exception) {
                currentOnNoCamera()
            }
        }, ContextCompat.getMainExecutor(context))

        onDispose {
            disposed = true
            analysis.clearAnalyzer()
            provider?.unbind(preview, analysis) // the camera turns off (and stays off when the app comes back)
            scanner.close()
            executor.shutdown()
        }
    }

    AndroidView(modifier = modifier, factory = { view })
}
