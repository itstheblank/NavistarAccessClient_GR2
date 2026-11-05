package com.example.navistaraccessclient.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.example.navistaraccessclient.model.AuthStatus
import com.example.navistaraccessclient.utils.OverlayGeometry
import com.example.navistaraccessclient.viewmodel.FaceFrame

/**
 * Lớp phủ giao diện:
 *  - Oval hướng dẫn = tín hiệu chính cho người dùng (đổi màu theo trạng thái).
 *  - Khung vuông bounding box CHỈ hiện khi bật "Hiển thị khung debug" trong Cài đặt.
 *
 * Các provider được đọc BÊN TRONG Canvas (pha draw) → mỗi frame chỉ vẽ lại Canvas,
 * không recompose cả màn hình / Dialog.
 */
@Composable
fun FaceOverlay(
    frameProvider: () -> FaceFrame,
    statusProvider: () -> AuthStatus,
    showDebugBoxProvider: () -> Boolean,
    onCanvasSizeChanged: (IntSize) -> Unit,
    isFrontCamera: Boolean = true,
    modifier: Modifier = Modifier
) {
    Canvas(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged(onCanvasSizeChanged)
    ) {
        val status = statusProvider()
        val oval = OverlayGeometry.guideOval(size.width, size.height)

        val guideColor = when (status) {
            AuthStatus.SUCCESS -> Color(0xFF00E676)
            AuthStatus.FAILED -> Color(0xFFFF5252)
            AuthStatus.DETECTED -> Color(0xFF448AFF)
            AuthStatus.MULTIPLE_FACES -> Color(0xFFFF9100)
            AuthStatus.IDLE -> Color.White.copy(alpha = 0.6f)
        }
        // Oval dày hơn khi có kết quả để người dùng dễ nhận ra
        val strokeWidth = if (status == AuthStatus.IDLE) 3.dp.toPx() else 5.dp.toPx()

        drawOval(
            color = guideColor,
            topLeft = Offset(oval.left, oval.top),
            size = Size(oval.width, oval.height),
            style = Stroke(width = strokeWidth)
        )

        if (!showDebugBoxProvider()) return@Canvas

        // ---- Chế độ debug: vẽ bounding box của ML Kit ----
        val frame = frameProvider()
        if (frame.imageWidth <= 0 || frame.imageHeight <= 0 || frame.faces.isEmpty()) return@Canvas
        for (face in frame.faces) {
            val r = OverlayGeometry.mapToCanvas(
                face.boundingBox, frame.imageWidth, frame.imageHeight,
                size.width, size.height, isFrontCamera
            )
            drawRect(
                color = Color.Yellow.copy(alpha = 0.8f),
                topLeft = Offset(r.left, r.top),
                size = Size(r.width(), r.height()),
                style = Stroke(width = 1.5.dp.toPx())
            )
        }
    }
}
