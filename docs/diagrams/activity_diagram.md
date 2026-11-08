# 🔄 Activity Diagram - Luồng Xử lý Frame Camera
**Mục tiêu:** Mô tả vòng lặp xử lý 1 frame ảnh từ CameraX → ML Kit → TFLite, nhấn mạnh bước giải phóng bộ nhớ (`imageProxy.close()`).
**Cập nhật:** 07/11/2026 — khớp với mã nguồn sau GĐ 2 (`FaceAnalyzer.kt`, `CameraViewModel.kt`).

---

## Lưu đồ Thuật toán

```mermaid
flowchart TD
    START([App khởi động]) --> PERM{Quyền CAMERA?}
    PERM -- Từ chối --> DENY([Hiện thông báo xin quyền])
    PERM -- Được cấp --> BIND[Bind Preview + ImageAnalysis<br/>640x480, KEEP_ONLY_LATEST]
    BIND --> WAIT[Chờ frame]

    subgraph CAMTHREAD["Luồng camera – FaceAnalyzer + CameraViewModel.onFacesDetected"]
        WAIT --> SKIP{image null?<br/>Đang mở Cài đặt?<br/>Chưa đủ 66ms?}
        SKIP -- Có --> CLOSE
        SKIP -- Không --> MLK[ML Kit detector.process]
        MLK --> COUNT{Số khuôn mặt}
        COUNT -- 0 --> S_IDLE[IDLE: Đưa mặt vào Oval]
        COUNT -- "≥ 2" --> S_MULTI[MULTIPLE_FACES: Chỉ đứng 1 người]
        COUNT -- 1 --> PLACE{Vị trí so với Oval}
        PLACE -- "Lệch / xa / gần" --> S_GUIDE[IDLE: Vào giữa / Tiến lại gần / Lùi ra xa]
        PLACE -- Đúng --> S_DET[DETECTED: Đang nhận diện...]
        S_DET --> THR{"Đã qua 500ms<br/>và không có suy luận đang chạy?"}
        THR -- Chưa --> CLOSE
        THR -- Rồi --> CROP[toBitmap + xoay + crop 15%<br/>→ 112x112, recycle frame gốc]
        S_IDLE --> CLOSE
        S_MULTI --> CLOSE
        S_GUIDE --> CLOSE
        CROP --> CLOSE
        CLOSE[["⚠️ imageProxy.close()<br/>(trong finally)"]]
    end

    CLOSE --> WAIT

    subgraph BG["Dispatchers.Default (song song với luồng camera)"]
        CROP -. "launch" .-> VERIFY[TFLite MobileFaceNet<br/>vector 192 chiều]
        VERIFY --> COS{"Cosine lớn nhất ≥ 0.70?"}
        COS -- Có --> OK[SUCCESS]
        COS -- Không --> FAIL[FAILED]
    end

    OK --> UI[Main Thread: cập nhật Oval + thông báo<br/>bỏ kết quả nếu mặt đã rời Oval]
    FAIL --> UI
    UI -. "GĐ 3" .-> MQTT[(Đóng gói AccessLog → MQTT)]
```

**Đọc sơ đồ:** nhánh suy luận TFLite chạy **song song** trên luồng nền với vòng lặp camera. Frame đã được crop thành Bitmap riêng nên `imageProxy` được đóng ngay, CameraX gửi frame tiếp mà không phải chờ TFLite.

## Giải thích Chi tiết

### Tại sao `imageProxy.close()` là điểm sống còn?

| Nếu QUÊN gọi `close()` | Nếu LUÔN gọi `close()` |
|---|---|
| CameraX ngừng gửi frame mới | Frame mới được gửi liên tục |
| Buffer ảnh bị giữ trong RAM | RAM được giải phóng ngay |
| App đóng băng sau 2-3 giây | App chạy ổn định hàng giờ |
| OOM crash trên thiết bị 1GB RAM | Đảm bảo ràng buộc RAM < 1GB |

### Nguyên tắc vàng
> `imageProxy.close()` phải được gọi trong **MỌI nhánh** của luồng xử lý:
> thành công, thất bại, exception, không tìm thấy mặt, image == null, frame bị bỏ qua.
>
> Trong code: frame bị bỏ qua → `close()` ngay đầu `analyze()`; còn lại → `close()` trong khối
> `finally` của `addOnCompleteListener`, **sau khi** `onFacesDetected()` đã chuyển ảnh sang Bitmap.

### Ba tầng giới hạn tải CPU

| Tầng | Vị trí | Giới hạn |
|---|---|---|
| CameraX | `STRATEGY_KEEP_ONLY_LATEST` | Bỏ frame cũ nếu xử lý chưa kịp |
| ML Kit | `FaceAnalyzer.minIntervalMs = 66` | ≤ ~15 lần/giây; tạm dừng khi mở Cài đặt |
| TFLite | `CameraViewModel` (500ms + `AtomicBoolean`) | ≤ 2 lần/giây, không chạy chồng |

### Cấu hình CameraX thực tế (`CameraPreview.kt`)
```kotlin
val resolutionSelector = ResolutionSelector.Builder()
    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
    .setResolutionStrategy(ResolutionStrategy(Size(640, 480),
        ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
    .build()

ImageAnalysis.Builder()
    .setResolutionSelector(resolutionSelector)                       // cùng selector với Preview
    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST) // drop frame cũ
    .build()
```
(`setTargetResolution()` đã bị CameraX 1.3+ đánh dấu deprecated nên không dùng.)
