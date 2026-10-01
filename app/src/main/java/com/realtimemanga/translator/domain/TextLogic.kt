package com.realtimemanga.translator.domain

import java.text.Normalizer
import java.util.LinkedHashMap

object TextNormalizer {
    private val whitespace = Regex("\\s+")

    fun normalize(text: String): String {
        return Normalizer.normalize(text, Normalizer.Form.NFC)
            .replace('\u00A0', ' ')
            .trim()
            .replace(whitespace, " ")
    }
}

class ScriptLanguageDetector : LanguageDetector {
    override fun detect(text: String): SourceLanguage {
        val hangul = hangulCount(text)
        val latin = latinCount(text)
        return when {
            hangul >= 1 && hangul >= latin -> SourceLanguage.KO
            latin >= 2 && latin > hangul -> SourceLanguage.EN
            latin == 1 && hangul == 0 -> SourceLanguage.EN
            else -> SourceLanguage.UNKNOWN
        }
    }

    override fun hangulCount(text: String): Int {
        return text.count { ch ->
            ch in '\uAC00'..'\uD7A3' || ch in '\u1100'..'\u11FF' || ch in '\u3130'..'\u318F'
        }
    }

    override fun latinCount(text: String): Int {
        return text.count { ch -> ch.isLetter() && ch.code < 128 }
    }
}

object OcrNoiseFilter {
    private val clock = Regex("^\\d{1,2}:\\d{2}(:\\d{2})?$")
    private val percent = Regex("^\\d{1,3}%$")
    private val digits = Regex("^[0-9\\s.+-]+$")

    fun isNoise(text: String): Boolean {
        val normalized = TextNormalizer.normalize(text)
        if (normalized.length < TranslatorConfig.MIN_TEXT_LENGTH) return true
        if (clock.matches(normalized) || percent.matches(normalized) || digits.matches(normalized)) {
            return true
        }
        return false
    }
}

class LanguageRouter(
    private val detector: LanguageDetector,
    private val overlapThreshold: Float = TranslatorConfig.OVERLAP_IOU,
) {
    fun select(
        mode: SourceMode,
        latin: List<OcrTextBlock>,
        korean: List<OcrTextBlock>,
    ): List<OcrTextBlock> {
        val latinUseful = latin.mapNotNull { accept(it, SourceLanguage.EN) }
        val koreanUseful = korean.mapNotNull { accept(it, SourceLanguage.KO) }
        val selected = when (mode) {
            SourceMode.EN -> latinUseful
            SourceMode.KR -> koreanUseful
            SourceMode.AUTO -> merge(latinUseful, koreanUseful)
        }
        return selected
            .sortedByDescending { it.bounds.width * it.bounds.height }
            .take(TranslatorConfig.MAX_BLOCKS)
    }

    private fun accept(block: OcrTextBlock, expected: SourceLanguage): OcrTextBlock? {
        if (block.bounds.isEmpty() || OcrNoiseFilter.isNoise(block.text)) return null
        val detected = detector.detect(block.text)
        if (detected != SourceLanguage.UNKNOWN && detected != expected) return null
        if (detected == SourceLanguage.UNKNOWN && expected == SourceLanguage.KO &&
            detector.hangulCount(block.text) == 0
        ) {
            return null
        }
        val language = if (detected == SourceLanguage.UNKNOWN) expected else detected
        return block.copy(language = language)
    }

    private fun merge(
        latin: List<OcrTextBlock>,
        korean: List<OcrTextBlock>,
    ): List<OcrTextBlock> {
        val ranked = (korean + latin).sortedByDescending { scriptScore(it) }
        val kept = mutableListOf<OcrTextBlock>()
        for (block in ranked) {
            if (kept.any { iou(it.bounds, block.bounds) > overlapThreshold }) continue
            kept += block
        }
        return kept
    }

    private fun scriptScore(block: OcrTextBlock): Int {
        val hangul = detector.hangulCount(block.text)
        val latin = detector.latinCount(block.text)
        // Pure Hangul outranks a Latin misread of the same bubble. Non-overlapping
        // English stays, because merge only drops boxes that actually overlap.
        return when (block.language) {
            SourceLanguage.KO -> hangul * 100 + if (hangul > 0 && latin == 0) 10_000 else 0
            SourceLanguage.EN -> latin * 100 + if (latin > 0 && hangul == 0) 1_000 else 0
            SourceLanguage.UNKNOWN -> 0
        }
    }
}

fun iou(a: Bounds, b: Bounds): Float {
    val left = maxOf(a.left, b.left)
    val top = maxOf(a.top, b.top)
    val right = minOf(a.right, b.right)
    val bottom = minOf(a.bottom, b.bottom)
    val intersection = (right - left).coerceAtLeast(0) * (bottom - top).coerceAtLeast(0)
    if (intersection == 0) return 0f
    val union = a.width * a.height + b.width * b.height - intersection
    if (union <= 0) return 0f
    return intersection.toFloat() / union.toFloat()
}

class LruTranslationCache(
    private val maxEntries: Int = TranslatorConfig.CACHE_MAX_ENTRIES,
) : TranslationCache {
    private val lock = Any()
    private val map = object : LinkedHashMap<String, String>(maxEntries, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean {
            return size > maxEntries
        }
    }

    override fun get(key: CacheKey): String? = synchronized(lock) { map[key.serialized()] }

    override fun put(key: CacheKey, value: String) {
        synchronized(lock) { map[key.serialized()] = value }
    }

    override fun clear() {
        synchronized(lock) { map.clear() }
    }

    fun size(): Int = synchronized(lock) { map.size }
}

class CoordinateMapper(
    private val imageWidth: Int,
    private val imageHeight: Int,
    private val screenWidth: Int,
    private val screenHeight: Int,
) {
    init {
        require(imageWidth > 0 && imageHeight > 0 && screenWidth > 0 && screenHeight > 0)
    }

    fun toScreen(bounds: Bounds): Bounds {
        val left = scaleX(bounds.left)
        val top = scaleY(bounds.top)
        val right = scaleX(bounds.right).coerceAtLeast(left + 1)
        val bottom = scaleY(bounds.bottom).coerceAtLeast(top + 1)
        return Bounds(
            left.coerceIn(0, screenWidth),
            top.coerceIn(0, screenHeight),
            right.coerceIn(0, screenWidth),
            bottom.coerceIn(0, screenHeight),
        )
    }

    fun toImage(bounds: Bounds): Bounds {
        val left = unscaleX(bounds.left)
        val top = unscaleY(bounds.top)
        val right = unscaleX(bounds.right).coerceAtLeast(left + 1)
        val bottom = unscaleY(bounds.bottom).coerceAtLeast(top + 1)
        return Bounds(
            left.coerceIn(0, imageWidth),
            top.coerceIn(0, imageHeight),
            right.coerceIn(0, imageWidth),
            bottom.coerceIn(0, imageHeight),
        )
    }

    private fun scaleX(value: Int): Int = (value.toLong() * screenWidth / imageWidth).toInt()
    private fun scaleY(value: Int): Int = (value.toLong() * screenHeight / imageHeight).toInt()
    private fun unscaleX(value: Int): Int = (value.toLong() * imageWidth / screenWidth).toInt()
    private fun unscaleY(value: Int): Int = (value.toLong() * imageHeight / screenHeight).toInt()
}

object EmptyFrameDetector {
    fun isLikelyBlank(argbSamples: IntArray): Boolean {
        if (argbSamples.isEmpty()) return true
        var sum = 0.0
        var sumSq = 0.0
        for (pixel in argbSamples) {
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            val luma = (r * 30 + g * 59 + b * 11) / 100.0
            sum += luma
            sumSq += luma * luma
        }
        val count = argbSamples.size.toDouble()
        val mean = sum / count
        val variance = sumSq / count - mean * mean
        return mean < TranslatorConfig.BLANK_MEAN_MAX && variance < TranslatorConfig.BLANK_VARIANCE_MAX
    }
}

object SessionReducer {
    fun reduce(state: SessionState, event: SessionEvent): SessionState {
        if (event is SessionEvent.Stop) return SessionState.Stopping
        if (event is SessionEvent.Stopped) return SessionState.Idle
        if (state is SessionState.Stopping && event !is SessionEvent.Stopped) return state
        return when (event) {
            SessionEvent.StartRequested -> when (state) {
                SessionState.Idle, is SessionState.Failed -> SessionState.RequestingPermission
                else -> state
            }
            SessionEvent.OverlayMissing -> SessionState.Failed(UserMessages.OVERLAY_DENIED)
            SessionEvent.CaptureConsentDenied -> SessionState.Failed(UserMessages.CAPTURE_DENIED)
            is SessionEvent.ModelsProgress -> SessionState.PreparingModels(event.message)
            SessionEvent.ModelsReady -> SessionState.RequestingPermission
            is SessionEvent.ModelFailed -> SessionState.Failed(event.message)
            SessionEvent.SessionStarted -> SessionState.Ready
            SessionEvent.CaptureStarted -> SessionState.Capturing
            SessionEvent.OcrStarted -> SessionState.Recognizing
            SessionEvent.TranslateStarted -> SessionState.Translating
            SessionEvent.PipelineFinished -> when (state) {
                SessionState.Paused, SessionState.Idle, is SessionState.Failed -> state
                else -> SessionState.Ready
            }
            SessionEvent.Pause -> when (state) {
                SessionState.Ready, SessionState.Capturing, SessionState.Recognizing,
                SessionState.Translating, is SessionState.Failed,
                -> SessionState.Paused
                else -> state
            }
            SessionEvent.Resume -> if (state == SessionState.Paused) SessionState.Ready else state
            is SessionEvent.ProjectionStopped -> SessionState.Failed(event.message)
            is SessionEvent.Failed -> SessionState.Failed(event.message)
            SessionEvent.Stop, SessionEvent.Stopped -> state
        }
    }
}

fun SessionState.statusLabel(): String = when (this) {
    SessionState.Idle, SessionState.Ready -> "READY"
    SessionState.RequestingPermission -> "PERMISSION"
    is SessionState.PreparingModels -> "PREPARING"
    SessionState.Capturing -> "CAPTURING"
    SessionState.Recognizing -> "OCR"
    SessionState.Translating -> "TRANSLATING"
    SessionState.Paused -> "PAUSED"
    is SessionState.Failed -> "ERROR"
    SessionState.Stopping -> "STOPPING"
}

fun SessionState.statusDetail(): String = when (this) {
    SessionState.Idle -> "Sẵn sàng"
    SessionState.Ready -> "Sẵn sàng"
    SessionState.RequestingPermission -> "Đang xin quyền"
    is SessionState.PreparingModels -> message
    SessionState.Capturing -> "Đang chụp"
    SessionState.Recognizing -> "Đang nhận dạng"
    SessionState.Translating -> "Đang dịch"
    SessionState.Paused -> "Tạm dừng"
    is SessionState.Failed -> message
    SessionState.Stopping -> "Đang dừng"
}
