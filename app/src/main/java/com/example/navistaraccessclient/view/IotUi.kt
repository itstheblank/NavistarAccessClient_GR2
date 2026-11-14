package com.example.navistaraccessclient.view

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.navistaraccessclient.iot.ConnState
import com.example.navistaraccessclient.iot.DeviceConfig
import com.example.navistaraccessclient.iot.DeviceState
import com.example.navistaraccessclient.iot.protocol.DoorStatus
import com.example.navistaraccessclient.viewmodel.CameraViewModel

/*
 * [GĐ3] Giao diện phần IoT: chip trạng thái trên màn hình chính + mục cấu hình trong Dialog Cài đặt.
 */

private fun connLabel(s: ConnState): Pair<Color, String> = when (s) {
    is ConnState.Connected -> Color(0xFF43A047) to "Server: Đã kết nối"
    ConnState.FetchingToken -> Color(0xFFFFB300) to "Server: Đang lấy token..."
    ConnState.Connecting -> Color(0xFFFFB300) to "Server: Đang kết nối..."
    is ConnState.Waiting -> Color(0xFFE53935) to "Server: Mất kết nối"
    is ConnState.Misconfigured -> Color(0xFFE53935) to "Server: Thiếu cấu hình"
    ConnState.Disabled -> Color.Gray to "Server: Tắt"
}

/** Chip góc trên trái: kết nối + trạng thái cửa + cảnh báo disable/emergency */
@Composable
fun IotStatusChip(conn: ConnState, door: DoorStatus, state: DeviceState, modifier: Modifier = Modifier) {
    val (color, label) = connLabel(conn)
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier
                .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(50))
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(10.dp).background(color, CircleShape))
            Spacer(Modifier.width(8.dp))
            Text(label, color = Color.White, fontSize = 13.sp)
            Spacer(Modifier.width(12.dp))
            Text(
                text = when {
                    state.isEmergencyOpen -> "🚪 Mở khẩn cấp"
                    door == DoorStatus.OPEN -> "🚪 Cửa mở"
                    else -> "🚪 Cửa đóng"
                },
                color = if (door == DoorStatus.OPEN || state.isEmergencyOpen) Color(0xFF69F0AE) else Color.White,
                fontSize = 13.sp
            )
        }
        if (state.isDeviceDisable) {
            Text(
                "Thiết bị đang bị vô hiệu hoá bởi quản trị viên",
                color = Color.White, fontSize = 12.sp,
                modifier = Modifier
                    .background(Color(0xFFC62828).copy(alpha = 0.85f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            )
        }
    }
}

/**
 * Mục "Kết nối hệ thống Lab" trong Dialog Cài đặt.
 * Giữ bản nháp cục bộ, chỉ áp dụng khi bấm "Lưu & kết nối lại".
 */
@Composable
fun DeviceSettingsSection(viewModel: CameraViewModel) {
    val context = LocalContext.current
    val current: DeviceConfig = viewModel.deviceConfig
    val st: DeviceState = viewModel.deviceState

    var enabled by remember { mutableStateOf(current.enabled) }
    var apiUrl by remember { mutableStateOf(current.apiBaseUrl) }
    var host by remember { mutableStateOf(current.brokerHost) }
    var port by remember { mutableStateOf(current.brokerPort.toString()) }
    var mac by remember { mutableStateOf(current.macAddress) }
    var salt by remember { mutableStateOf(current.salt) }
    var applyThreshold by remember { mutableStateOf(current.applyServerThreshold) }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Kết nối hệ thống Lab (MQTT v5)", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        Switch(checked = enabled, onCheckedChange = { enabled = it })
    }
    Text(
        when (val c = viewModel.connState) {
            is ConnState.Connected -> "Đã kết nối ${c.uri}"
            is ConnState.Waiting -> "${c.reason}" + if (c.retryInSec > 0) " (thử lại sau ${c.retryInSec}s)" else ""
            is ConnState.Misconfigured -> "Thiếu: ${c.missing.joinToString()}"
            else -> connLabel(c).second
        },
        color = Color.Gray, fontSize = 12.sp
    )

    OutlinedTextField(
        value = apiUrl, onValueChange = { apiUrl = it },
        label = { Text("URL ApiGateway (VD http://10.0.2.2:8080)") }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        modifier = Modifier.fillMaxWidth()
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = host, onValueChange = { host = it },
            label = { Text("Broker host") }, singleLine = true,
            modifier = Modifier.weight(2f)
        )
        OutlinedTextField(
            value = port, onValueChange = { v -> port = v.filter { it.isDigit() }.take(5) },
            label = { Text("Port") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.weight(1f)
        )
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = mac, onValueChange = { mac = it.uppercase().take(17) },
            label = { Text("MAC thiết bị") }, singleLine = true,
            isError = !DeviceConfig.isValidMac(mac),
            modifier = Modifier.weight(1f)
        )
        TextButton(onClick = { mac = viewModel.generateMac() }) { Text("Sinh mới") }
    }
    OutlinedTextField(
        value = salt, onValueChange = { salt = it },
        label = { Text("Salt (nhóm dự án cấp)") }, singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth()
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Dùng ngưỡng nhận diện của server", fontWeight = FontWeight.Medium)
            Text(
                "IDENTIFY_THRESHOLD = ${st.serverThreshold ?: "chưa nhận"} (app đang dùng Cosine 0.70)",
                color = Color.Gray, fontSize = 12.sp
            )
        }
        Switch(checked = applyThreshold, onCheckedChange = { applyThreshold = it })
    }
    Button(
        onClick = {
            viewModel.saveDeviceConfig(
                current.copy(
                    enabled = enabled,
                    apiBaseUrl = apiUrl.trim(),
                    brokerHost = host.trim(),
                    brokerPort = port.toIntOrNull() ?: 1883,
                    macAddress = mac.trim().uppercase(),
                    salt = salt,
                    applyServerThreshold = applyThreshold
                )
            )
            Toast.makeText(context, "Đã lưu, đang kết nối lại...", Toast.LENGTH_SHORT).show()
        },
        modifier = Modifier.fillMaxWidth()
    ) { Text("Lưu & kết nối lại") }

    // Thông tin server đã cấp (chỉ đọc) — tiện chụp màn hình báo cáo / gỡ lỗi
    Text(
        text = "DoorId: ${st.doorId ?: "—"} · Hướng: ${st.direction ?: "—"} · " +
               "Giữ cửa: ${st.lockerTimeHoldMs}ms\n" +
               "Vô hiệu hoá: ${st.isDeviceDisable} · Khẩn cấp: ${st.isEmergencyOpen}\n" +
               "Version: ${DeviceConfig.APP_VERSION}",
        fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = Color.DarkGray
    )
}
