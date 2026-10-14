plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.example.navistaraccessclient"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.navistaraccessclient"
        minSdk = 29
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // 1. CameraX (Xử lý luồng camera siêu nhẹ của Google)
    val cameraxVersion = "1.4.0-alpha01"
    implementation("androidx.camera:camera-core:$cameraxVersion")
    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
    implementation("androidx.camera:camera-view:$cameraxVersion")

    // 2. Google ML Kit (Quét mặt real-time, vẽ khung viền, đo khoảng cách xa/gần)
    implementation("com.google.mlkit:face-detection:16.1.6")

    // 3. TensorFlow Lite (Chạy mô hình AI Face Verification 1:1 local)
    // Lưu ý: tensorflow-lite-support kéo theo tensorflow-lite-support-api
    // cùng namespace → phải exclude để tránh lỗi Manifest merger trên AGP 9.x
    implementation("org.tensorflow:tensorflow-lite:2.16.1")
    implementation("org.tensorflow:tensorflow-lite-support:0.4.4") {
        exclude(group = "org.tensorflow", module = "tensorflow-lite-support-api")
    }

    // 4. Eclipse Paho MQTT (Chuẩn giao tiếp IoT cực nhẹ và ổn định)
    implementation("org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5")

    // 5. Coroutines (Xử lý đa luồng bất đồng bộ để app không bị lag khi chạy AI)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // 6. ViewModel Compose (Cần cho hàm viewModel() trong Composable)
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
}