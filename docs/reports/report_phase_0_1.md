# BÁO CÁO TIẾN ĐỘ TRIỂN KHAI DỰ ÁN
**Dự án:** Hệ thống Kiểm soát Ra vào IoT (Android Client)
**Đơn vị/Lab:** Navistar, HUST
**Giai đoạn báo cáo:** Giai đoạn 0 (Kiến trúc) & Giai đoạn 1 (Camera & AI Detection)
**Trạng thái:** HOÀN THÀNH (Đạt 100% mục tiêu đề ra)

---

## 1. TỔNG QUAN
Dự án đã hoàn tất việc thiết kế kiến trúc tổng thể và triển khai thành công nền tảng (boilerplate code) kết hợp với luồng xử lý Camera theo chuẩn UI hiện đại. 
Điểm nhấn quan trọng là hệ thống đã vượt qua được bài toán ràng buộc phần cứng khắt khe (**RAM < 1GB** trên API 29) thông qua việc quản lý bộ nhớ thủ công và chiến lược phân bổ khung hình (frame dropping).

## 2. KẾT QUẢ GIAI ĐOẠN 0: THIẾT KẾ KIẾN TRÚC TỔNG THỂ
* **Sơ đồ Kiến trúc Hệ thống (System Architecture):** Đã thiết lập file `docs/diagrams/system_architecture.md`. Mô tả chi tiết pipeline từ lúc lấy luồng CameraX -> Xử lý ML Kit -> Trích xuất vector TFLite -> Kết nối MQTT Broker.
* **Lưu đồ Hoạt động (Activity Diagram):** Đã thiết lập file `docs/diagrams/activity_diagram.md`. Làm rõ vòng đời của một Frame ảnh, đảm bảo không có độ trễ (latency) khi xử lý đồng thời (Concurrency).
* **Định nghĩa Stack Công nghệ:**
  * UI Framework: Đã nâng cấp toàn bộ dự án từ XML Views truyền thống sang **Jetpack Compose** kết hợp kiến trúc **MVVM** để tối ưu hóa hiệu suất render giao diện.
  * Dependency: Hoàn tất cấu hình Gradle với các phiên bản ổn định của CameraX, ML Kit Face Detection, và Coroutines.

## 3. KẾT QUẢ GIAI ĐOẠN 1: TÍCH HỢP CAMERAX VÀ ML KIT (FACE DETECTION)
Toàn bộ logic cốt lõi đã được đưa vào package `com.example.navistaraccessclient` và chạy thử nghiệm thành công.

* **UI & Quyền truy cập:**
  * Triển khai `MainActivity` với Jetpack Compose.
  * Tích hợp thành công luồng xin quyền Camera động (`ActivityResultContracts.RequestPermission`).
* **Quản lý Camera (CameraX):**
  * Sử dụng `CameraSelector.DEFAULT_FRONT_CAMERA` (Sẵn sàng cho thiết bị thật).
  * Áp dụng chiến lược `STRATEGY_KEEP_ONLY_LATEST` cho `ImageAnalysis` để drop các frame cũ, tránh nghẽn hàng đợi (Backpressure) và chống tràn RAM.
  * Quản lý luồng xử lý ảnh trong một `SingleThreadExecutor` riêng biệt.
* **Nhận diện khuôn mặt (ML Kit):**
  * Tích hợp `FaceDetection` với cấu hình `PERFORMANCE_MODE_FAST` siêu nhẹ.
  * Quản lý bộ nhớ nghiêm ngặt: Lệnh `imageProxy.close()` được đóng gói trong khối `addOnCompleteListener`, đảm bảo giải phóng RAM 100% sau mỗi frame ảnh (kể cả khi detect lỗi).
* **Render Bounding Box (Toán học Tọa độ):**
  * Thuật toán `FaceOverlay` đã được hiệu chỉnh hoàn hảo.
  * Giải quyết dứt điểm sai số tọa độ do cơ chế `FILL_CENTER` (crop viền) của màn hình.
  * Đã tích hợp logic Lật ảnh (Mirror) cho Camera trước. Khung xanh bám sát khuôn mặt thời gian thực một cách chính xác.

## 4. KẾT LUẬN & BƯỚC TIẾP THEO
* **Đánh giá:** Giai đoạn 1 đã cung cấp một bộ khung (skeleton) cực kỳ vững chắc, mượt mà và an toàn về bộ nhớ.
* **Kế hoạch Giai đoạn 2 (TensorFlow Lite):**
  * Chuẩn bị nhúng mô hình `.tflite` vào thư mục `assets`.
  * Viết module cắt ảnh (crop image) từ Bounding Box của ML Kit.
  * Chuyển đổi ảnh (Bitmap/TensorImage) và đưa qua Model MobileFaceNet/Facenet để xuất ra vector đặc trưng (Embeddings).
