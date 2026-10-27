// MainActivity.kt
package com.example.navistaraccessclient.view

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.navistaraccessclient.model.AuthStatus
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
    var showSettingsDialog by remember { mutableStateOf(false)}

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
                onFacesDetected = { faces, width, height, imageProxy ->
                    viewModel.onFacesDetected(faces, width, height, imageProxy)
                }
            )

            FaceOverlay(
                faces = viewModel.detectedFaces,
                imageWidth = viewModel.imageWidth,
                imageHeight = viewModel.imageHeight,
                isFrontCamera = true,
                authResult = viewModel.authResult,
                modifier = Modifier.fillMaxSize()
            )

            IconButton(
                onClick = { showSettingsDialog = true },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(16.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = "Cài đặt",
                    tint = Color.White,
                    modifier = Modifier.size(32.dp)
                )
            }

            val result = viewModel.authResult
            val statusBgColor = when (result.status){
                AuthStatus.SUCCESS -> Color(0xFF2E7D32).copy(alpha = 0.85f)
                AuthStatus.FAILED -> Color(0xFFC62828).copy(alpha = 0.85f)
                AuthStatus.MULTIPLE_FACES -> Color(0xFFEF6C00).copy(alpha = 0.85f)
                AuthStatus.DETECTED -> Color(0xFF1565C0).copy(alpha = 0.85f)
                AuthStatus.IDLE -> Color.Black.copy(alpha = 0.6f)
            }

            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 24.dp, start = 16.dp, end = 16.dp)
                    .background(statusBgColor, shape = RoundedCornerShape(16.dp))
                    .padding(horizontal = 24.dp, vertical = 14.dp)
            ) {
                Text(
                    text = result.message.ifEmpty { "Vui lòng đưa mặt vào khung Oval" },
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center
                )
            }

            if (showSettingsDialog) {
                SettingsDialog(
                    viewModel = viewModel,
                    onDismiss = { showSettingsDialog = false }
                )
            }
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

/**
 * Hộp thoại Cài đặt: Cho phép Đăng ký khuôn mặt mới và xem danh sách
 */
@Composable
fun SettingsDialog(
    viewModel: CameraViewModel,
    onDismiss: () -> Unit
) {
    var newUserName by remember { mutableStateOf("") }
    val context = LocalContext.current
    val registeredUsers = remember { viewModel.getRegisteredUsers() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = "Cài Đặt Hệ Thống", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "Đăng ký khuôn mặt hiện tại:",
                    fontWeight = FontWeight.SemiBold
                )
                OutlinedTextField(
                    value = newUserName,
                    onValueChange = { newUserName = it },
                    label = { Text("Họ và Tên") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    onClick = {
                        if (newUserName.isNotBlank()) {
                            viewModel.registerCurrentFace(newUserName.trim()) {
                                Toast.makeText(context, "Đã đăng ký: $newUserName", Toast.LENGTH_SHORT).show()
                                onDismiss()
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = newUserName.isNotBlank() && viewModel.detectedFaces.isNotEmpty()
                ) {
                    Text("Lưu Khuôn Mặt Vào Bộ Nhớ")
                }
                Divider(modifier = Modifier.padding(vertical = 4.dp))
                Text(
                    text = "Danh sách đã đăng ký (${registeredUsers.size}):",
                    fontWeight = FontWeight.SemiBold
                )
                if (registeredUsers.isEmpty()) {
                    Text(text = "Chưa có dữ liệu người dùng.", color = Color.Gray, fontSize = 14.sp)
                } else {
                    registeredUsers.forEach { user ->
                        Text(text = "• ${user.name} (${user.id})", fontSize = 14.sp)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Đóng")
            }
        }
    )
}