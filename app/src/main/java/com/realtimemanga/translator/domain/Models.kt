package com.realtimemanga.translator.domain

enum class SourceLanguage { EN, KO, UNKNOWN }

enum class SourceMode { AUTO, EN, KR }

enum class TargetLanguage { VI }

data class Bounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top

    fun isEmpty(): Boolean = width <= 0 || height <= 0

    fun intersects(other: Bounds): Boolean {
        return left < other.right && right > other.left && top < other.bottom && bottom > other.top
    }

    fun inflate(px: Int): Bounds = Bounds(left - px, top - px, right + px, bottom + px)
}

data class OcrTextBlock(
    val id: String,
    val text: String,
    val bounds: Bounds,
    val language: SourceLanguage,
    val confidence: Float?,
)

data class TranslationResult(
    val sourceText: String,
    val translatedText: String,
    val sourceLanguage: SourceLanguage,
    val targetLanguage: TargetLanguage,
    val bounds: Bounds,
)

data class OverlayItem(
    val bounds: Bounds,
    val text: String,
)

/** Reserved for a future context-aware engine. V1 translators ignore it. */
data class TranslationContext(
    val previousLines: List<String> = emptyList(),
)

/** Reserved for V3. Not used by the V1 pipeline. */
data class BubbleRegion(
    val id: String,
    val bounds: Bounds,
    val textBlocks: List<OcrTextBlock>,
)

/** Reserved for V3 history. V1 does not persist a chapter transcript. */
data class TranslationSession(
    val id: String,
    val createdAt: Long,
    val history: List<TranslationResult>,
)

data class CacheKey(
    val normalizedSource: String,
    val source: SourceLanguage,
    val target: TargetLanguage,
) {
    fun serialized(): String = "${source.name}|${target.name}|$normalizedSource"
}

data class FrameFingerprint(
    val width: Int,
    val height: Int,
    val checksum: Long,
)

sealed interface SessionState {
    data object Idle : SessionState
    data object RequestingPermission : SessionState
    data class PreparingModels(val message: String) : SessionState
    data object Ready : SessionState
    data object Capturing : SessionState
    data object Recognizing : SessionState
    data object Translating : SessionState
    data object Paused : SessionState
    data class Failed(val message: String) : SessionState
    data object Stopping : SessionState
}

sealed interface SessionEvent {
    data object StartRequested : SessionEvent
    data object OverlayMissing : SessionEvent
    data object CaptureConsentDenied : SessionEvent
    data class ModelsProgress(val message: String) : SessionEvent
    data object ModelsReady : SessionEvent
    data class ModelFailed(val message: String) : SessionEvent
    data object SessionStarted : SessionEvent
    data object CaptureStarted : SessionEvent
    data object OcrStarted : SessionEvent
    data object TranslateStarted : SessionEvent
    data object PipelineFinished : SessionEvent
    data object Pause : SessionEvent
    data object Resume : SessionEvent
    data object Stop : SessionEvent
    data object Stopped : SessionEvent
    data class ProjectionStopped(val message: String) : SessionEvent
    data class Failed(val message: String) : SessionEvent
}

object TranslatorConfig {
    const val CACHE_MAX_ENTRIES = 500
    const val MAX_OCR_EDGE_PX = 1600
    const val CAPTURE_TIMEOUT_MS = 4_000L
    const val OVERLAY_HIDE_SETTLE_MS = 120L
    const val MODEL_PREPARE_TIMEOUT_MS = 300_000L
    const val PIPELINE_TIMEOUT_MS = 90_000L
    const val MAX_BLOCKS = 40
    const val MIN_TEXT_LENGTH = 2
    const val OVERLAP_IOU = 0.45f
    const val BUTTON_EXCLUDE_PADDING_PX = 16
    const val BLANK_MEAN_MAX = 8.0
    const val BLANK_VARIANCE_MAX = 16.0
}

object LogTags {
    const val CAPTURE = "CAPTURE"
    const val OCR = "OCR"
    const val LANG = "LANG"
    const val TRANSLATE = "TRANSLATE"
    const val CACHE = "CACHE"
    const val OVERLAY = "OVERLAY"
    const val PERF = "PERF"
}

object UserMessages {
    const val OVERLAY_DENIED = "Chưa có quyền hiển thị trên ứng dụng khác."
    const val CAPTURE_DENIED = "Bạn đã từ chối quyền chụp màn hình."
    const val SELECT_ENTIRE_SCREEN =
        "Khung hình trống hoặc không phải toàn màn hình. Khi Android hỏi, hãy chọn \"Toàn bộ màn hình\"."
    const val CAPTURE_TIMEOUT = "Hết thời gian chụp màn hình. Bấm Dịch ngay để thử lại."
    const val PROJECTION_STOPPED = "Phiên chụp màn hình đã dừng. Bấm Bắt đầu để xin quyền lại."
    const val PROJECTION_EXPIRED = "Quyền chụp màn hình đã hết hạn. Bấm Bắt đầu để xin lại."
    const val NO_TEXT = "Không thấy chữ tiếng Anh hoặc tiếng Hàn trên màn hình."
    const val MODEL_TIMEOUT = "Hết thời gian tải model. Cần mạng cho lần tải đầu, rồi thử lại."
    const val MODEL_FAILED =
        "Không tải được model on-device. Cần Google Play Services và mạng cho lần tải đầu."
    const val ROTATED = "Đã đổi hướng hoặc độ phân giải. Bấm Dịch ngay để dịch lại."
    const val BUSY = "Đang xử lý khung hình trước."
    const val PAUSED = "Đang tạm dừng."
    const val NO_SESSION = "Chưa có phiên chụp màn hình."
    const val SIZE_MISMATCH = "Khung chụp không khớp toàn màn hình. Hãy chia sẻ toàn bộ màn hình."
}
