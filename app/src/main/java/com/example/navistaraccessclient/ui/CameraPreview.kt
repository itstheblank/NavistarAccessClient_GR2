// CameraPreview.kt
package com.example.navistaraccessclient.ui

import android.util.Log
import android.util.Size
import android.view.ViewGroup
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.navistaraccessclient.model.FaceAnalyzer
import com.google.mlkit.vision.face.Face
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/**
 * CameraPreview - Composable hiển thị luồng camera real-time (CameraX trong AndroidView).
 *
 * Tối ưu cho thiết bị yếu / giả lập:
 *  - Preview và ImageAnalysis cùng độ phân giải 640x480 (4:3) → webcam/giả lập không phải
 *    scale ảnh, ML Kit + chuyển Bitmap nhẹ hơn nhiều so với độ phân giải mặc định.
 *  - Khi rời màn hình: gỡ analyzer, unbind camera, đóng ML Kit rồi mới tắt executor
 *    → không còn RejectedExecutionException / rò rỉ detector.
 */
@Composable
fun CameraPreview(
    modifier: Modifier = Modifier,
    isPaused: () -> Boolean = { false },
    onFacesDetected: (List<Face>, Int, Int, ImageProxy) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraExecutor: ExecutorService = remember { Executors.newSingleThreadExecutor() }
    val currentOnFaces = rememberUpdatedState(onFacesDetected)
    val currentIsPaused = rememberUpdatedState(isPaused)

    // Executor "an toàn": bỏ qua task nếu executor đã tắt (ML Kit trả kết quả muộn)
    val safeExecutor = remember {
        java.util.concurrent.Executor { r ->
            if (!cameraExecutor.isShutdown) {
                try { cameraExecutor.execute(r) } catch (e: RejectedExecutionException) { /* executor đã tắt */ }
            }
        }
    }

    val analyzer = remember {
        FaceAnalyzer(
            callbackExecutor = safeExecutor,
            isPaused = { currentIsPaused.value() },
            onFacesDetected = { f, w, h, p -> currentOnFaces.value(f, w, h, p) }
        )
    }

    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }
    val imageAnalysisHolder = remember { arrayOfNulls<ImageAnalysis>(1) }

    DisposableEffect(Unit) {
        onDispose {
            imageAnalysisHolder[0]?.clearAnalyzer()
            try {
                if (cameraProviderFuture.isDone) cameraProviderFuture.get().unbindAll()
            } catch (e: Exception) {
                Log.w("CameraPreview", "unbindAll failed", e)
            }
            analyzer.close()
            cameraExecutor.shutdown()
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            val previewView = PreviewView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                scaleType = PreviewView.ScaleType.FILL_CENTER
                // COMPATIBLE (TextureView) ổn định hơn SurfaceView trên giả lập & khi có Dialog đè lên
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            }

            cameraProviderFuture.addListener({
                val cameraProvider = cameraProviderFuture.get()

                val resolutionSelector = ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            Size(640, 480),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER
                        )
                    )
                    .build()

                val preview = Preview.Builder()
                    .setResolutionSelector(resolutionSelector)
                    .build()
                    .also { it.setSurfaceProvider(previewView.surfaceProvider) }

                val imageAnalysis = ImageAnalysis.Builder()
                    .setResolutionSelector(resolutionSelector)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { it.setAnalyzer(cameraExecutor, analyzer) }
                imageAnalysisHolder[0] = imageAnalysis

                val hasFront = try {
                    cameraProvider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)
                } catch (e: Exception) { false }
                val cameraSelector = if (hasFront) {
                    CameraSelector.DEFAULT_FRONT_CAMERA
                } else {
                    Log.w("CameraPreview", "Không có camera trước, dùng camera sau")
                    CameraSelector.DEFAULT_BACK_CAMERA
                }

                try {
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, preview, imageAnalysis)
                    // Log để kiểm tra webcam/giả lập: lọc Logcat theo tag "CameraPreview"
                    Log.i(
                        "CameraPreview",
                        "Camera=${if (hasFront) "FRONT" else "BACK"} | " +
                            "Preview=${preview.resolutionInfo?.resolution} | " +
                            "Analysis=${imageAnalysis.resolutionInfo?.resolution} | " +
                            "rotation=${imageAnalysis.resolutionInfo?.rotationDegrees}"
                    )
                } catch (e: Exception) {
                    Log.e("CameraPreview", "Use case binding failed", e)
                }
            }, ContextCompat.getMainExecutor(ctx))

            previewView
        }
    )
}
