package com.example.navistaraccessclient.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.google.mlkit.vision.face.Face

/**
 * CameraViewModel - Quản lý state cho Camera và Face Detection
 *
 * Tại sao cần ViewModel:
 * Lưu trữ state theo đúng kiến trúc MVVM
 * Khi xoay màn hình, Activity bị destroy → recreate, nhưng ViewModel vẫn sống
 * → Camera không bị restart lại, data không bị mất
 */
class CameraViewModel : ViewModel() {
    var detectedFaces by mutableStateOf<List<Face>>(emptyList())
        private set

    var imageWidth by mutableIntStateOf(0)
        private set

    var imageHeight by mutableIntStateOf(0)
        private set

    fun onFacesDetected(faces: List<Face>, width: Int, height: Int){
        detectedFaces = faces
        imageHeight = height
        imageWidth = width
    }
}
