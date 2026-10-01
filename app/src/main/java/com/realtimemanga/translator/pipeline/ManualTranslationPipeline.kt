package com.realtimemanga.translator.pipeline

import android.graphics.Bitmap
import com.realtimemanga.translator.capture.downscale
import com.realtimemanga.translator.data.OcrEngine
import com.realtimemanga.translator.domain.AppLogger
import com.realtimemanga.translator.domain.Bounds
import com.realtimemanga.translator.domain.CoordinateMapper
import com.realtimemanga.translator.domain.LanguageRouter
import com.realtimemanga.translator.domain.LogTags
import com.realtimemanga.translator.domain.OverlayItem
import com.realtimemanga.translator.domain.SessionEvent
import com.realtimemanga.translator.domain.SourceLanguage
import com.realtimemanga.translator.domain.SourceMode
import com.realtimemanga.translator.domain.TargetLanguage
import com.realtimemanga.translator.domain.TextNormalizer
import com.realtimemanga.translator.domain.TranslationCache
import com.realtimemanga.translator.domain.TranslationEngine
import com.realtimemanga.translator.domain.TranslatorConfig
import com.realtimemanga.translator.domain.CacheKey
import com.realtimemanga.translator.domain.UserMessages

class ManualTranslationPipeline(
    private val latinOcr: OcrEngine,
    private val koreanOcr: OcrEngine,
    private val router: LanguageRouter,
    private val translator: TranslationEngine,
    private val cache: TranslationCache,
    private val logger: AppLogger,
) {
    suspend fun run(
        bitmap: Bitmap,
        screenWidth: Int,
        screenHeight: Int,
        mode: SourceMode,
        excludeScreen: Bounds?,
        onPhase: (SessionEvent) -> Unit,
    ): PipelineOutput {
        val started = System.nanoTime()
        val working = downscale(bitmap, TranslatorConfig.MAX_OCR_EDGE_PX)
        try {
            onPhase(SessionEvent.OcrStarted)
            val ocrStarted = System.nanoTime()
            val latin = if (mode != SourceMode.KR) latinOcr.recognize(working) else emptyList()
            val korean = if (mode != SourceMode.EN) koreanOcr.recognize(working) else emptyList()
            val ocrMs = elapsedMs(ocrStarted)
            val selected = router.select(mode, latin, korean)
            val mapper = CoordinateMapper(
                imageWidth = working.width,
                imageHeight = working.height,
                screenWidth = screenWidth,
                screenHeight = screenHeight,
            )
            val exclude = excludeScreen
                ?.let { mapper.toImage(it).inflate(TranslatorConfig.BUTTON_EXCLUDE_PADDING_PX) }
            val visible = if (exclude == null) {
                selected
            } else {
                selected.filterNot { it.bounds.intersects(exclude) }
            }
            logger.debug(
                LogTags.OCR,
                "latin=${latin.size} korean=${korean.size} kept=${visible.size} ${ocrMs}ms",
            )
            logger.debug(LogTags.LANG, "mode=$mode")
            if (visible.isEmpty()) {
                return PipelineOutput(
                    items = emptyList(),
                    detail = UserMessages.NO_TEXT,
                    sizeMismatch = sizeMismatch(bitmap.width, bitmap.height, screenWidth, screenHeight),
                )
            }
            onPhase(SessionEvent.TranslateStarted)
            val translateStarted = System.nanoTime()
            val items = ArrayList<OverlayItem>(visible.size)
            for (block in visible) {
                val normalized = TextNormalizer.normalize(block.text)
                if (normalized.length < TranslatorConfig.MIN_TEXT_LENGTH) continue
                val key = CacheKey(normalized, block.language, TargetLanguage.VI)
                val cached = cache.get(key)
                val translated = if (cached != null) {
                    logger.debug(LogTags.CACHE, "hit ${block.language.name}")
                    cached
                } else {
                    val value = translator.translate(normalized, block.language)
                    if (value.isNotBlank()) cache.put(key, value)
                    value
                }
                if (translated.isBlank()) continue
                items += OverlayItem(
                    bounds = mapper.toScreen(block.bounds),
                    text = translated,
                )
            }
            val translateMs = elapsedMs(translateStarted)
            logger.debug(LogTags.TRANSLATE, "blocks=${items.size} ${translateMs}ms")
            logger.debug(LogTags.PERF, "total=${elapsedMs(started)}ms ocr=${ocrMs}ms translate=${translateMs}ms")
            val mismatch = sizeMismatch(bitmap.width, bitmap.height, screenWidth, screenHeight)
            val detail = buildString {
                append("Đã dịch ${items.size} vùng")
                if (mismatch) append(". ").append(UserMessages.SIZE_MISMATCH)
            }
            return PipelineOutput(items, detail, mismatch)
        } finally {
            if (working !== bitmap) working.recycle()
            bitmap.recycle()
        }
    }

    private fun sizeMismatch(
        capturedWidth: Int,
        capturedHeight: Int,
        screenWidth: Int,
        screenHeight: Int,
    ): Boolean {
        if (capturedWidth <= 1 || capturedHeight <= 1 || screenWidth <= 0 || screenHeight <= 0) return true
        val widthRatio = capturedWidth.toFloat() / screenWidth.toFloat()
        val heightRatio = capturedHeight.toFloat() / screenHeight.toFloat()
        return widthRatio < 0.9f || heightRatio < 0.9f
    }

    private fun elapsedMs(startedNanos: Long): Long = (System.nanoTime() - startedNanos) / 1_000_000L
}

data class PipelineOutput(
    val items: List<com.realtimemanga.translator.domain.OverlayItem>,
    val detail: String,
    val sizeMismatch: Boolean,
)
