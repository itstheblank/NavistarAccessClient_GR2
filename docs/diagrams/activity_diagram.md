# 🔄 Activity Diagram - Luồng Xử lý Frame Camera
**Mục tiêu:** Mô tả vòng lặp xử lý frame ảnh, nhấn mạnh bước giải phóng bộ nhớ (`imageProxy.close()`)

---

## Lưu đồ Thuật toán

```mermaid
stateDiagram-v2
    [*] --> RequestPermission: App khởi động

    RequestPermission --> PermissionDenied: Người dùng từ chối
    RequestPermission --> BindCamera: Quyền CAMERA được cấp
    PermissionDenied --> [*]: Hiện thông báo lỗi

    BindCamera --> WaitForFrame: CameraX bind thành công

    state "🔄 Vòng lặp xử lý Frame (Main Loop)" as FrameLoop {
        WaitForFrame --> ReceiveProxy: ImageAnalysis callback

        ReceiveProxy --> CheckNull: Kiểm tra imageProxy.image

        CheckNull --> SkipFrame: image == null
        CheckNull --> CreateInputImage: image != null

        CreateInputImage --> MLKitProcess: InputImage.fromMediaImage()

        state "🔍 ML Kit Face Detection" as MLKit {
            MLKitProcess --> NoFace: faces.isEmpty()
            MLKitProcess --> FaceFound: faces.isNotEmpty()
        }

        NoFace --> ClearOverlay: Xóa khung vẽ cũ
        FaceFound --> DrawBoundingBox: Vẽ khung xanh quanh mặt
        DrawBoundingBox --> FuturePhase: Giai đoạn 2 - TFLite Verify
    end

    SkipFrame --> ForceClose
    ClearOverlay --> ForceClose
    FuturePhase --> ForceClose

    state "⚠️ CRITICAL: Giải phóng Bộ nhớ" as ForceClose {
        [*] --> CallClose: imageProxy.close()
        CallClose --> [*]: Buffer được giải phóng
    }

    ForceClose --> WaitForFrame: Chờ frame tiếp theo
```

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
> thành công, thất bại, exception, không tìm thấy mặt, image == null.
>
> Cách an toàn nhất: Đặt trong `addOnCompleteListener` (gọi cả khi success lẫn failure).

### Cấu hình tối ưu RAM cho ImageAnalysis
```
ImageAnalysis.Builder()
    .setBackpressureStrategy(STRATEGY_KEEP_ONLY_LATEST)  // Drop frame cũ nếu xử lý chưa kịp
    .setTargetResolution(Size(480, 640))                  // Giảm resolution → giảm RAM
    .build()
```
