package com.example.navistaraccessclient.utils

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Rect
import android.util.Log
import androidx.camera.core.ImageProxy

object BitmapUtils {
    /**
     * Convert ImageProxy to a Bitmap with the correct rotation applied.
     * Bitmap trung gian (chưa xoay) được recycle ngay để tránh rác bộ nhớ.
     */
    fun imageProxyToBitmap(imageProxy: ImageProxy): Bitmap? {
        val bitmap = try { imageProxy.toBitmap() } catch (e: Exception) {
            Log.e("BitmapUtils", "toBitmap failed", e); return null
        }
        val rotationDegrees = imageProxy.imageInfo.rotationDegrees
        if (rotationDegrees == 0) return bitmap

        val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (rotated !== bitmap) bitmap.recycle()
        return rotated
    }

    /**
     * Crop the face image from the ML Kit bounding box (including 15% safety padding) and resize it to 112x112 px.
     * Ảnh nguồn KHÔNG bị recycle ở đây — người gọi tự quản lý.
     */
    fun cropFace(srcBitmap: Bitmap, boundingBox: Rect, targetSize: Int = 112): Bitmap? {
        return try {
            val paddingWidth = (boundingBox.width() * 0.15f).toInt()
            val paddingHeight = (boundingBox.height() * 0.15f).toInt()

            val left = (boundingBox.left - paddingWidth).coerceAtLeast(0)
            val top = (boundingBox.top - paddingHeight).coerceAtLeast(0)
            val right = (boundingBox.right + paddingWidth).coerceAtMost(srcBitmap.width)
            val bottom = (boundingBox.bottom + paddingHeight).coerceAtMost(srcBitmap.height)

            val width = right - left
            val height = bottom - top
            if (width <= 0 || height <= 0) return null

            val cropped = Bitmap.createBitmap(srcBitmap, left, top, width, height)
            val scaled = Bitmap.createScaledBitmap(cropped, targetSize, targetSize, true)
            if (scaled !== cropped && cropped !== srcBitmap) cropped.recycle()
            scaled
        } catch (e: Exception) {
            Log.e("BitmapUtils", "cropFace failed", e)
            null
        }
    }
}
