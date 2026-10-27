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
import com.example.navistaraccessclient.model.AuthStatus
import com.example.navistaraccessclient.model.FaceAuthResult
import com.google.mlkit.vision.face.Face
import kotlin.math.max

@Composable
fun FaceOverlay(
    faces: List<Face>,
    imageWidth: Int,
    imageHeight: Int,
    isFrontCamera: Boolean = true,
    authResult: FaceAuthResult,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier.fillMaxSize()) {
        val canvasWidth = size.width
        val canvasHeight = size.height

        // 1. Draw an OVAL GUIDING BOX in the center of the screen
        val ovalHeight = canvasHeight * 0.65f
        val ovalWidth = ovalHeight * 0.72f
        val ovalLeft = (canvasWidth - ovalWidth) / 2f
        val ovalTop = (canvasHeight - ovalHeight) / 2.5f

        // Guide to frame color: Red (>= 2 sides present), Green (Valid), Blue (Detecting), Gray (Waiting)
        val guideColor = when (authResult.status) {
            AuthStatus.SUCCESS -> Color(0xFF00E676)
            AuthStatus.FAILED -> Color(0xFFFF5252)
            AuthStatus.DETECTED -> Color(0xFF448AFF)
            AuthStatus.MULTIPLE_FACES -> Color(0xFFFF9100)
            AuthStatus.IDLE -> Color.White.copy(alpha = 0.5f)
        }

        drawOval(
            color = guideColor,
            topLeft = Offset(ovalLeft, ovalTop),
            size = Size(ovalWidth, ovalHeight),
            style = Stroke(width = 3.dp.toPx())
        )

        if (imageWidth <= 0 || imageHeight <= 0 || faces.isEmpty()) return@Canvas

        // 2. Algorithm for selecting a single face: Choose the face with the largest area
        val primaryFace = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() } ?: return@Canvas

        // 3. Calculate scale based on FILL_CENTER
        val scaleFactor = max(canvasWidth / imageWidth.toFloat(), canvasHeight / imageHeight.toFloat())
        val scaledWidth = imageWidth * scaleFactor
        val scaledHeight = imageHeight * scaleFactor
        val offsetX = (canvasWidth - scaledWidth) / 2f
        val offsetY = (canvasHeight - scaledHeight) / 2f

        val bounds = primaryFace.boundingBox
        var left = bounds.left * scaleFactor + offsetX
        val top = bounds.top * scaleFactor + offsetY
        var right = bounds.right * scaleFactor + offsetX
        val bottom = bounds.bottom * scaleFactor + offsetY

        if (isFrontCamera) {
            val mirroredLeft = canvasWidth - right
            val mirroredRight = canvasWidth - left
            left = mirroredLeft
            right = mirroredRight
        }

        // Draw a border around the selected face
        drawRect(
            color = guideColor,
            topLeft = Offset(left, top),
            size = Size(right - left, bottom - top),
            style = Stroke(width = 2.dp.toPx())
        )
    }
}