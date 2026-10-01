package com.realtimemanga.translator

import com.realtimemanga.translator.domain.Bounds
import com.realtimemanga.translator.domain.CacheKey
import com.realtimemanga.translator.domain.CoordinateMapper
import com.realtimemanga.translator.domain.EmptyFrameDetector
import com.realtimemanga.translator.domain.LanguageRouter
import com.realtimemanga.translator.domain.LruTranslationCache
import com.realtimemanga.translator.domain.OcrNoiseFilter
import com.realtimemanga.translator.domain.OcrTextBlock
import com.realtimemanga.translator.domain.ScriptLanguageDetector
import com.realtimemanga.translator.domain.SessionEvent
import com.realtimemanga.translator.domain.SessionReducer
import com.realtimemanga.translator.domain.SessionState
import com.realtimemanga.translator.domain.SourceLanguage
import com.realtimemanga.translator.domain.SourceMode
import com.realtimemanga.translator.domain.TargetLanguage
import com.realtimemanga.translator.domain.TextNormalizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DomainLogicTest {
    private val detector = ScriptLanguageDetector()
    private val router = LanguageRouter(detector)

    @Test
    fun normalizesWhitespaceAndNfcWithoutLowercasing() {
        val composed = TextNormalizer.normalize("  Where   are\nyou  going?  ")
        assertEquals("Where are you going?", composed)
        val nfc = TextNormalizer.normalize("e\u0301")
        assertEquals("\u00e9", nfc)
        assertEquals("OK", TextNormalizer.normalize("OK"))
    }

    @Test
    fun detectsEnglishAndKorean() {
        assertEquals(SourceLanguage.EN, detector.detect("Where are you going?"))
        assertEquals(SourceLanguage.KO, detector.detect("지금 뭐 하는 거야?"))
        assertEquals(SourceLanguage.KO, detector.detect("뭐"))
        assertEquals(SourceLanguage.UNKNOWN, detector.detect("..."))
    }

    @Test
    fun autoKeepsBothScriptsAndDropsOverlap() {
        val latin = listOf(
            block("Hello", Bounds(0, 0, 40, 20), "en"),
            block("WHAT ARE YOU DOING?", Bounds(10, 100, 200, 160), "en-overlap"),
        )
        val korean = listOf(
            block("지금 뭐 해?", Bounds(12, 102, 198, 158), "ko-overlap"),
        )
        val selected = router.select(SourceMode.AUTO, latin, korean)
        assertTrue(selected.any { it.id == "en" && it.language == SourceLanguage.EN })
        assertTrue(selected.any { it.id == "ko-overlap" && it.language == SourceLanguage.KO })
        assertFalse(selected.any { it.id == "en-overlap" })
    }

    @Test
    fun manualModeDoesNotMixEngines() {
        val latin = listOf(block("Hello", Bounds(0, 0, 40, 20), "en"))
        val korean = listOf(block("안녕", Bounds(0, 40, 40, 70), "ko"))
        assertEquals(listOf("en"), router.select(SourceMode.EN, latin, korean).map { it.id })
        assertEquals(listOf("ko"), router.select(SourceMode.KR, latin, korean).map { it.id })
    }

    @Test
    fun dropsClockAndPercent() {
        assertTrue(OcrNoiseFilter.isNoise("12:35"))
        assertTrue(OcrNoiseFilter.isNoise("87%"))
        assertTrue(OcrNoiseFilter.isNoise("4"))
        assertFalse(OcrNoiseFilter.isNoise("Hello"))
    }

    @Test
    fun cacheKeyIncludesLanguageAndEvictsOldest() {
        val cache = LruTranslationCache(maxEntries = 2)
        val en = CacheKey("Hello", SourceLanguage.EN, TargetLanguage.VI)
        val ko = CacheKey("Hello", SourceLanguage.KO, TargetLanguage.VI)
        cache.put(en, "Xin chào")
        cache.put(ko, "Khác")
        cache.get(en)
        cache.put(CacheKey("Next", SourceLanguage.EN, TargetLanguage.VI), "Tiếp")
        assertEquals("Xin chào", cache.get(en))
        assertNull(cache.get(ko))
        assertEquals("EN|VI|Hello", en.serialized())
    }

    @Test
    fun mapsCaptureCoordinatesToScreenAndBack() {
        val mapper = CoordinateMapper(100, 200, 200, 400)
        val screen = mapper.toScreen(Bounds(10, 20, 40, 60))
        assertEquals(Bounds(20, 40, 80, 120), screen)
        val image = mapper.toImage(screen)
        assertEquals(Bounds(10, 20, 40, 60), image)
    }

    @Test
    fun blankFrameIsDarkAndFlat() {
        assertTrue(EmptyFrameDetector.isLikelyBlank(IntArray(20) { 0xFF000000.toInt() }))
        assertFalse(EmptyFrameDetector.isLikelyBlank(intArrayOf(0xFFFFFFFF.toInt(), 0xFF000000.toInt())))
    }

    @Test
    fun stateMachineDoesNotUseConflictingFlags() {
        var state: SessionState = SessionState.Idle
        state = SessionReducer.reduce(state, SessionEvent.StartRequested)
        assertEquals(SessionState.RequestingPermission, state)
        state = SessionReducer.reduce(state, SessionEvent.ModelsProgress("Đang tải"))
        assertTrue(state is SessionState.PreparingModels)
        state = SessionReducer.reduce(state, SessionEvent.SessionStarted)
        assertEquals(SessionState.Ready, state)
        state = SessionReducer.reduce(state, SessionEvent.Pause)
        assertEquals(SessionState.Paused, state)
        state = SessionReducer.reduce(state, SessionEvent.Stop)
        assertEquals(SessionState.Stopping, state)
        state = SessionReducer.reduce(state, SessionEvent.CaptureStarted)
        assertEquals(SessionState.Stopping, state)
        state = SessionReducer.reduce(state, SessionEvent.Stopped)
        assertEquals(SessionState.Idle, state)
    }

    private fun block(text: String, bounds: Bounds, id: String) = OcrTextBlock(
        id = id,
        text = text,
        bounds = bounds,
        language = SourceLanguage.UNKNOWN,
        confidence = null,
    )
}
