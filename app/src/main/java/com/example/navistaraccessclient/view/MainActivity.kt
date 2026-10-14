// MainActivity.kt
package com.example.navistaraccessclient.view

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.navistaraccessclient.ui.CameraPreview
import com.example.navistaraccessclient.ui.FaceOverlay
import com.example.navistaraccessclient.ui.theme.NavistarAccessClientTheme
import com.example.navistaraccessclient.viewmodel.CameraViewModel

/**
 * MainActivity - Màn hình chính của ứng dụng.
 *
 * Cấu trúc UI (Box layout - các lớp đè lên nhau):
 * ┌──────────────────────────────┐
 * │  Lớp trên: FaceOverlay      │  ← Vẽ Bounding Box (nền trong suốt)
 * │  Lớp dưới: CameraPreview    │  ← Hiển thị camera real-time
 * └──────────────────────────────┘
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            NavistarAccessClientTheme {
                val viewModel: CameraViewModel = viewModel()
                AccessControlScreen(viewModel = viewModel)
            }
        }
    }
}

/**
 * AccessControlScreen - Composable chính, quản lý quyền Camera và hiển thị UI.
 */
@Composable
fun AccessControlScreen(
    viewModel: CameraViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

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
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    if (hasCameraPermission) {
        Box(modifier = modifier.fillMaxSize()) {
            CameraPreview(
                modifier = Modifier.fillMaxSize(),
                onFacesDetected = { faces, width, height ->
                    viewModel.onFacesDetected(faces, width, height)
                }
            )

            FaceOverlay(
                faces = viewModel.detectedFaces,
                imageWidth = viewModel.imageWidth,
                imageHeight = viewModel.imageHeight,
                isFrontCamera = true,
                modifier = Modifier.fillMaxSize()
            )
        }
    } else {
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "Please grant camera permission\nto use the app.",
                fontSize = 18.sp,
                textAlign = TextAlign.Center
            )
        }
    }
}