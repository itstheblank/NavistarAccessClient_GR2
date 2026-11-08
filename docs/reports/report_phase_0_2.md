# BÁO CÁO TIẾN ĐỘ TRIỂN KHAI DỰ ÁN

**Họ và tên::** Trần Sỹ Nguyên
**MSSV:** 20235985
**Dự án:** Hệ thống Kiểm soát Ra vào IoT (Android Client)
**Học phần:** Đồ án môn học 2 (GR2) / Thực tập Công nghiệp
**Cập nhật:** 07/11/2026

---

## 1. TỔNG QUAN

Ứng dụng chạy trên tablet/thiết bị IoT gắn tường tại cửa Lab (**Android 10 – API 29, RAM < 1GB, màn hình ngang, Offline-First**).
Sau 3 giai đoạn, app đã làm được toàn bộ chuỗi xử lý trên máy, **không gửi ảnh lên Cloud**:

```
CameraX (640x480) → ML Kit phát hiện mặt → Kiểm tra mặt nằm trong Oval
   → Crop 112x112 → TFLite MobileFaceNet (vector 192 chiều) → Cosine Similarity (ngưỡng 0.70)
   → Hiển thị kết quả (Oval đổi màu + thông báo)
```

Người dùng đăng ký được lưu xuống bộ nhớ trong của thiết bị (chỉ lưu vector, không lưu ảnh), tắt/mở app vẫn còn.

### Stack công nghệ (theo `app/build.gradle.kts`)

| Thành phần | Thư viện / Phiên bản |
|---|---|
| Ngôn ngữ / UI | Kotlin 2.2.10, Jetpack Compose (BOM 2026.02.01), Material 3, kiến trúc MVVM |
| Build | AGP 9.4.1 · `minSdk 29` · `targetSdk 37` |
| Camera | CameraX 1.4.2 (`camera-core`, `camera2`, `lifecycle`, `view`) |
| Phát hiện mặt | Google ML Kit Face Detection 16.1.6 |
| Nhận diện mặt | TensorFlow Lite 2.16.1 + model `mobilefacenet.tflite` (~5 MB, trong `assets/`) |
| Đa luồng | Kotlin Coroutines 1.7.3 |
| IoT (cho GĐ 3) | Eclipse Paho MQTT v3 1.2.5 (đã khai báo, chưa dùng) |

---

## 2. GIAI ĐOẠN 0: THIẾT KẾ KIẾN TRÚC

* **Ràng buộc phần cứng:** Android 10 (API 29), RAM khả dụng < 1GB → mục tiêu app ≤ 150 MB, không rò rỉ bộ nhớ.
* **Kiến trúc hệ thống** (`docs/diagrams/system_architecture.md`): tách 2 khối
  * *Android Client (Offline-First):* phát hiện, crop, trích xuất đặc trưng và so khớp hoàn toàn trên máy.
  * *IoT Backend:* giao tiếp qua MQTT Broker của Lab với ESP32/Relay (GĐ 3).
* **Lưu đồ xử lý frame** (`docs/diagrams/activity_diagram.md`): chuẩn hoá vòng đời `ImageProxy`, `imageProxy.close()` được gọi ở MỌI nhánh.
* **Chuyển UI sang Jetpack Compose + MVVM:** `AndroidView` cho CameraX, `Canvas` cho lớp phủ, State nằm trong ViewModel.

## 3. GIAI ĐOẠN 1: CAMERAX & PHÁT HIỆN KHUÔN MẶT

* **Quyền camera:** `ActivityResultContracts.RequestPermission()`; từ chối → hiện thông báo.
* **CameraX (`CameraPreview.kt`):**
  * `Preview` và `ImageAnalysis` dùng chung `ResolutionSelector` **640x480 (4:3)** → khung xem và khung phân tích trùng nhau, giảm tải chuyển ảnh.
  * `STRATEGY_KEEP_ONLY_LATEST`: bỏ frame cũ khi xử lý chưa kịp, không dồn hàng đợi.
  * Ưu tiên camera trước, tự chuyển sang camera sau nếu thiết bị không có.
  * `PreviewView` chế độ `COMPATIBLE` (TextureView), `FILL_CENTER`.
  * Khi rời màn hình: gỡ analyzer → `unbindAll()` → đóng ML Kit → tắt executor (đúng thứ tự, không còn `RejectedExecutionException`).
* **ML Kit (`FaceAnalyzer.kt`):**
  * `PERFORMANCE_MODE_FAST`, tắt landmark & classification, `minFaceSize = 0.15`.
  * Giới hạn tối đa ~15 lần/giây (`minIntervalMs = 66`), tạm dừng hẳn khi mở Cài đặt.
  * Xử lý góc xoay cảm biến (0/90/180/270°) để đổi đúng chiều rộng/cao.
  * `imageProxy.close()` trong khối `finally` của `addOnCompleteListener` → luôn giải phóng kể cả khi lỗi.
* **Ánh xạ toạ độ (`OverlayGeometry.mapToCanvas`):** bù tỉ lệ `FILL_CENTER` (scale = max, offset căn giữa) + lật gương cho camera trước.

## 4. GIAI ĐOẠN 2: XÁC THỰC KHUÔN MẶT OFFLINE 1:1 (TFLITE)

### 4.1 Pipeline nhận diện
1. **Kiến trúc Interface-driven:** UI/ViewModel chỉ làm việc với `FaceRecognizer` → thay model/SDK khác không phải sửa giao diện.
2. **Tiền xử lý (`BitmapUtils`):** `ImageProxy.toBitmap()` → xoay đúng chiều → crop theo bounding box **+15% padding** → resize **112x112**. Bitmap trung gian được `recycle()` ngay.
3. **Model (`TFLiteFaceRecognizer`):**
   * Nạp `mobilefacenet.tflite` bằng Memory Mapping (`MappedByteBuffer`), 2 luồng CPU.
   * Chuẩn hoá pixel về [-1, 1]: `(x − 127.5) / 128`.
   * Đầu ra **vector 192 chiều**; so khớp **Cosine Similarity** với tất cả người đã đăng ký, lấy giá trị lớn nhất.
   * `≥ 0.70` → SUCCESS (chào tên), `< 0.70` → FAILED (người lạ).
   * Tái sử dụng `ByteBuffer` đầu vào (~150 KB) và khoá `synchronized` vì `Interpreter` không thread-safe.
   * Nạp model lỗi → không crash, chạy chế độ giả lập (mock) và ghi log.

### 4.2 Giao diện hướng dẫn & luật 1 khuôn mặt
* **Oval hướng dẫn** giữa màn hình, đổi màu theo trạng thái:

| Trạng thái | Màu Oval | Thông báo |
|---|---|---|
| `IDLE` | Trắng mờ | "Vui lòng đưa mặt vào giữa khung Oval" / "Hãy tiến lại gần hơn" / "Hãy lùi ra xa một chút" |
| `DETECTED` | Xanh dương | "Đang nhận diện..." |
| `SUCCESS` | Xanh lá | "Chào mừng {tên}!" |
| `FAILED` | Đỏ | "Người lạ (Similarity: x.xx)" |
| `MULTIPLE_FACES` | Cam | "Cảnh báo: Có N người! Vui lòng chỉ đứng 1 người." |

* **Oval có hiệu lực thật** (`OverlayGeometry.checkPlacement`): chỉ nhận diện khi tâm mặt nằm trong 60% bán kính Oval và chiều cao mặt bằng 45%–140% chiều cao Oval.
* **≥ 2 khuôn mặt:** không nhận diện, hiện cảnh báo chỉ đứng 1 người.
* Bounding box của ML Kit chỉ hiện khi bật **"Hiển thị khung debug"** trong Cài đặt.

### 4.3 Đăng ký & quản lý người dùng (Dialog Cài đặt)
* Nhập tên → lưu khuôn mặt đang nằm đúng trong Oval (nút chỉ bật khi có mặt hợp lệ). TFLite chạy nền.
* Danh sách người đã đăng ký, **có nút xoá** từng người.
* **Lưu bền vững (`data/FaceStore.kt`):** file `registered_faces.json` trong bộ nhớ riêng của app, ghi bằng `AtomicFile` (mất điện giữa chừng không hỏng file). Chỉ lưu tên + vector (~3–4 KB/người), **không lưu ảnh**. File được loại khỏi Auto Backup (`backup_rules.xml`, `data_extraction_rules.xml`) vì là dữ liệu sinh trắc học.
* **Mã người dùng:** `USER_` + 8 ký tự UUID (VD `USER_3F9A1C2B`) → không trùng lặp.

### 4.4 Tối ưu hiệu năng & chống ANR
* **Throttling:** tối đa 1 lần suy luận / 500 ms, không chạy chồng (`AtomicBoolean`).
* **Luật luồng:** ML Kit callback ở luồng camera; TFLite ở `Dispatchers.Default`; đọc/ghi file ở `Dispatchers.IO`; mọi cập nhật State Compose ở Main.
* **Chống ANR khi gõ tên:** tạm dừng ML Kit khi mở Cài đặt; Overlay đọc State trong pha draw (chỉ vẽ lại Canvas, không recompose cả màn hình).
* **Giảm RAM/APK:** bỏ `material-icons-extended`, `tensorflow-lite-support`, `mlModelBinding`.
* Khoá màn hình ngang (`screenOrientation="landscape"`).

---

## 5. KẾT QUẢ NGHIỆM THU (GĐ 0–2)

Môi trường đo: AVD Pixel 4, API 29, webcam laptop.

| Tiêu chí | Yêu cầu | Phương pháp | Kết quả |
|---|---|---|---|
| RAM | ≤ 150 MB, không leak | `adb shell dumpsys meminfo` (TOTAL PSS) | ✅ 104.5 MB lúc khởi động → 108.5 MB sau 5 phút |
| Độ trễ suy luận | ≤ 100 ms | Logcat tag `TFLiteFaceRecognizer` (hàm `verify()`) | ✅ 17–28 ms (TB ~20 ms) |
| Độ chính xác | Phân biệt người quen / người lạ | Thử với người đã và chưa đăng ký | ✅ Người đã đăng ký: sim 0.94–0.96; người lạ bị từ chối; ≥ 2 người cảnh báo đúng |
| Trải nghiệm UI | Không đơ/ANR, Oval đúng tỉ lệ | Gõ tên bằng bàn phím ảo khi camera đang chạy | ✅ Hết ANR; Preview = Analysis = 640x480 |
| Lưu người dùng | Tắt/mở app vẫn còn | Đăng ký → force-stop → mở lại → nhận diện | ✅ OK |


## 6. HẠN CHẾ CÒN LẠI

* **Chưa có liveness** (chống giả mạo bằng ảnh in / màn hình điện thoại) — đề xuất đưa vào GĐ 4 hoặc ghi rõ ngoài phạm vi.
* **Mỗi người chỉ lưu 1 vector mẫu** → độ chính xác giảm khi đổi góc mặt / ánh sáng nhiều. Có thể cải tiến: lưu 3–5 mẫu/người.
* Ngưỡng 0.70 và thông số Oval mới được chỉnh trên giả lập + webcam; cần chỉnh lại tại cửa Lab thật.
* Số liệu RAM mới đo 5 phút; cần đo ≥ 30 phút / qua đêm ở GĐ 4.

## 7. BƯỚC TIẾP THEO – GIAI ĐOẠN 3 (MQTT)

* Chốt với GVHD: địa chỉ Broker, xác thực, topic, format JSON `AccessLog` (kiểu `timestamp`), ai ra lệnh mở cửa.
* Đóng gói kết quả nhận diện thành `AccessLog`, chống gửi lặp khi 1 người đứng lâu trước cửa.
* Module `MqttManager` (Paho): tự kết nối lại, Last Will báo thiết bị offline, cấu hình Broker trên Dialog Cài đặt.
