package com.example.navistaraccessclient.utils

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Rect
import androidx.camera.core.ImageProxy

object BitmapUtils{
    /**
     * Convert ImageProxy to a Bitmap with the correct rotation applied
     */
    fun imageProxyToBitmap(imageProxy: ImageProxy): Bitmap?{
        val bitmap = imageProxy.toBitmap()?: return null
        val rotationDegrees = imageProxy.imageInfo.rotationDegrees
        if(rotationDegrees == 0) return bitmap

        val matrix = Matrix().apply{
            postRotate(rotationDegrees.toFloat())
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    /**
     * Crop the face image from the ML Kit bounding box (including 15% safety padding) and resize it to 112x112 px.
     */
    fun cropFace(srcBitmap: Bitmap, boundingBox: Rect, targetSize: Int = 112): Bitmap?{
        return try{
            val paddingWidth = (boundingBox.width() * 0.15f).toInt()
            val paddingHeight = (boundingBox.height() * 0.15f).toInt()

            val left = (boundingBox.left - paddingWidth).coerceAtLeast(0)
            val top = (boundingBox.top - paddingHeight).coerceAtLeast(0)
            val right = (boundingBox.right + paddingWidth).coerceAtMost(srcBitmap.width)
            val bottom = (boundingBox.bottom + paddingHeight).coerceAtMost(srcBitmap.height)

            val width = right - left
            val height = bottom - top
            if (width <= 0 || height <= 0) return null

            val croppedBitmap = Bitmap.createBitmap(srcBitmap, left, top, width, height)
            Bitmap.createScaledBitmap(croppedBitmap, targetSize, targetSize, true)
        } catch (e: Exception){
            e.printStackTrace()
            null
        }
    }
}