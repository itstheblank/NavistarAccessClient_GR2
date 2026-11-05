package com.example.navistaraccessclient.utils

import android.graphics.Rect
import android.graphics.RectF
import kotlin.math.max

/**
 * Hình học dùng chung giữa FaceOverlay (vẽ) và CameraViewModel (kiểm tra mặt trong Oval),
 * đảm bảo 2 bên luôn tính cùng một vị trí.
 */
object OverlayGeometry {

    data class Oval(val left: Float, val top: Float, val width: Float, val height: Float) {
        val centerX get() = left + width / 2f
        val centerY get() = top + height / 2f
        val radiusX get() = width / 2f
        val radiusY get() = height / 2f
    }

    /** Khung Oval hướng dẫn, căn giữa màn hình (theo chiều dọc hơi lệch lên trên). */
    fun guideOval(canvasWidth: Float, canvasHeight: Float): Oval {
        val h = canvasHeight * 0.65f
        val w = h * 0.72f
        return Oval((canvasWidth - w) / 2f, (canvasHeight - h) / 2.5f, w, h)
    }

    /** Ánh xạ bounding box từ toạ độ ảnh → toạ độ màn hình (PreviewView FILL_CENTER + lật gương). */
    fun mapToCanvas(
        box: Rect, imageWidth: Int, imageHeight: Int,
        canvasWidth: Float, canvasHeight: Float, mirror: Boolean
    ): RectF {
        val scale = max(canvasWidth / imageWidth, canvasHeight / imageHeight)
        val offsetX = (canvasWidth - imageWidth * scale) / 2f
        val offsetY = (canvasHeight - imageHeight * scale) / 2f
        var left = box.left * scale + offsetX
        var right = box.right * scale + offsetX
        val top = box.top * scale + offsetY
        val bottom = box.bottom * scale + offsetY
        if (mirror) {
            val l = canvasWidth - right
            right = canvasWidth - left
            left = l
        }
        return RectF(left, top, right, bottom)
    }

    enum class Placement { OK, OFF_CENTER, TOO_FAR, TOO_CLOSE }

    // Ngưỡng có thể tinh chỉnh khi test thực tế tại cửa Lab
    private const val CENTER_TOLERANCE = 0.6f   // tâm mặt nằm trong 60% bán kính Oval
    private const val MIN_SIZE_RATIO = 0.45f    // chiều cao mặt >= 45% chiều cao Oval
    private const val MAX_SIZE_RATIO = 1.4f     // chiều cao mặt <= 140% chiều cao Oval

    fun checkPlacement(face: RectF, oval: Oval): Placement {
        val dx = (face.centerX() - oval.centerX) / oval.radiusX
        val dy = (face.centerY() - oval.centerY) / oval.radiusY
        if (dx * dx + dy * dy > CENTER_TOLERANCE * CENTER_TOLERANCE) return Placement.OFF_CENTER
        val ratio = face.height() / oval.height
        if (ratio < MIN_SIZE_RATIO) return Placement.TOO_FAR
        if (ratio > MAX_SIZE_RATIO) return Placement.TOO_CLOSE
        return Placement.OK
    }
}
