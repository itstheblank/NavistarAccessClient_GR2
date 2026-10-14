package com.example.navistaraccessclient.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.google.mlkit.vision.face.Face
import kotlin.math.max

@Composable
fun FaceOverlay(
    faces: List<Face>,
    imageWidth: Int,
    imageHeight: Int,
    isFrontCamera: Boolean = true,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier.fillMaxSize()) {
        if (imageWidth <= 0 || imageHeight <= 0) return@Canvas

        // 1. Tính toán Scale dựa trên FILL_CENTER (giống logic của PreviewView)
        val scaleFactor = max(
            size.width / imageWidth.toFloat(),
            size.height / imageHeight.toFloat()
        )

        // 2. Tính phần bị crop (do FILL_CENTER sẽ cắt bớt ảnh thừa)
        val scaledImageWidth = imageWidth * scaleFactor
        val scaledImageHeight = imageHeight * scaleFactor
        
        val offsetX = (size.width - scaledImageWidth) / 2f
        val offsetY = (size.height - scaledImageHeight) / 2f

        for (face in faces) {
            val bounds = face.boundingBox
            
            // 3. Scale tọa độ và dịch chuyển theo offset
            var left = bounds.left * scaleFactor + offsetX
            val top = bounds.top * scaleFactor + offsetY
            var right = bounds.right * scaleFactor + offsetX
            val bottom = bounds.bottom * scaleFactor + offsetY

            // 4. Mirror (Lật ngang) nếu dùng camera trước
            if (isFrontCamera) {
                val mirroredLeft = size.width - right
                val mirroredRight = size.width - left
                left = mirroredLeft
                right = mirroredRight
            }

            // Vẽ khung
            drawRect(
                color = Color.Green,
                topLeft = Offset(left, top),
                size = Size(right - left, bottom - top),
                style = Stroke(width = 3.dp.toPx())
            )
        }
    }
}
