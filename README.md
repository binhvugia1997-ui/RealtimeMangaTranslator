# Realtime Manga Translator

Android app dịch truyện trên màn hình, English/Korean → tiếng Việt, xử lý on-device.

Phiên bản này là **V1**. Chưa có auto-scroll (V2), bubble/context (V3), hay thay chữ gốc (V4).

## V1

- Xin quyền overlay, notification, MediaProjection
- Foreground service, một frame mỗi lần bấm Dịch ngay
- OCR Latin + Korean (ML Kit on-device)
- Dịch EN/KR → VI (ML Kit on-device)
- AUTO theo từng block
- Cache LRU
- Overlay chạm xuyên, nút nổi kéo được
- Start / Pause / Stop

Không dùng API trả phí. `INTERNET` chỉ để tải model lần đầu.

## Build

Cần JDK 17+ và Android SDK 35.

```bash
./gradlew assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`

## Test trên máy thật

1. Cài debug APK. Máy cần Google Play Services.
2. Lần đầu cần mạng để tải OCR Hàn và model dịch.
3. Bấm Bắt đầu, cấp quyền hiển thị trên app khác.
4. Khi Android hỏi chia sẻ màn hình, chọn **toàn bộ màn hình**, không chọn một app.
5. Mở app truyện. Bấm nút `文 VI` → Dịch ngay.
6. Bản dịch hiện trên chữ gốc. Cuộn/chạm app truyện vẫn được.
7. Dừng từ nút nổi hoặc notification.
