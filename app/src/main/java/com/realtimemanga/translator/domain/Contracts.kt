package com.realtimemanga.translator.domain

interface AppLogger {
    fun debug(tag: String, message: String)
    fun error(tag: String, message: String, error: Throwable? = null)
}

interface LanguageDetector {
    fun detect(text: String): SourceLanguage
    fun hangulCount(text: String): Int
    fun latinCount(text: String): Int
}

interface TranslationCache {
    fun get(key: CacheKey): String?
    fun put(key: CacheKey, value: String)
    fun clear()
}

interface TranslationEngine {
    suspend fun ensureModel(source: SourceLanguage)
    suspend fun translate(
        text: String,
        source: SourceLanguage,
        target: TargetLanguage = TargetLanguage.VI,
        context: TranslationContext = TranslationContext(),
    ): String

    fun close()
}

/**
 * V2 will compare settled frames before OCR. V1 never calls this:
 * capture happens only when the user taps Translate now.
 */
interface FrameChangeDetector {
    fun hasChanged(previous: FrameFingerprint?, current: FrameFingerprint): Boolean
}
