package com.realtimemanga.translator.data

import android.graphics.Bitmap
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.realtimemanga.translator.domain.OcrTextBlock
import com.realtimemanga.translator.domain.SourceLanguage
import com.realtimemanga.translator.domain.TargetLanguage
import com.realtimemanga.translator.domain.TranslationContext
import com.realtimemanga.translator.domain.TranslationEngine
import com.realtimemanga.translator.domain.UserMessages
import kotlinx.coroutines.tasks.await
import java.util.UUID

class ModelUnavailableException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

interface OcrEngine {
    suspend fun recognize(bitmap: Bitmap): List<OcrTextBlock>
    suspend fun warmUp()
    fun close()
}

class MlKitOcrEngine(
    private val recognizer: TextRecognizer,
) : OcrEngine {
    override suspend fun recognize(bitmap: Bitmap): List<OcrTextBlock> {
        val image = InputImage.fromBitmap(bitmap, 0)
        val text = recognizer.process(image).await()
        return text.textBlocks.mapNotNull { block ->
            val box = block.boundingBox ?: return@mapNotNull null
            val value = block.text.trim()
            if (value.isEmpty()) return@mapNotNull null
            OcrTextBlock(
                id = UUID.randomUUID().toString(),
                text = value,
                bounds = com.realtimemanga.translator.domain.Bounds(
                    box.left,
                    box.top,
                    box.right,
                    box.bottom,
                ),
                language = SourceLanguage.UNKNOWN,
                confidence = null,
            )
        }
    }

    override suspend fun warmUp() {
        val probe = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        try {
            recognize(probe)
        } finally {
            probe.recycle()
        }
    }

    override fun close() {
        recognizer.close()
    }
}

class MlKitTranslationEngine : TranslationEngine {
    private val translators = mutableMapOf<SourceLanguage, Translator>()

    override suspend fun ensureModel(source: SourceLanguage) {
        val translator = translator(source)
        val conditions = DownloadConditions.Builder().build()
        try {
            translator.downloadModelIfNeeded(conditions).await()
        } catch (error: Exception) {
            throw ModelUnavailableException(UserMessages.MODEL_FAILED, error)
        }
    }

    override suspend fun translate(
        text: String,
        source: SourceLanguage,
        target: TargetLanguage,
        context: TranslationContext,
    ): String {
        // context is intentionally unused until a context-aware engine exists.
        return translator(source).translate(text).await()
    }

    override fun close() {
        translators.values.forEach { it.close() }
        translators.clear()
    }

    private fun translator(source: SourceLanguage): Translator {
        val existing = translators[source]
        if (existing != null) return existing
        val sourceTag = when (source) {
            SourceLanguage.EN -> TranslateLanguage.ENGLISH
            SourceLanguage.KO -> TranslateLanguage.KOREAN
            SourceLanguage.UNKNOWN -> TranslateLanguage.ENGLISH
        }
        val options = TranslatorOptions.Builder()
            .setSourceLanguage(sourceTag)
            .setTargetLanguage(TranslateLanguage.VIETNAMESE)
            .build()
        return Translation.getClient(options).also { translators[source] = it }
    }
}

class EngineRegistry {
    private var latin: OcrEngine? = null
    private var korean: OcrEngine? = null
    private var translator: TranslationEngine? = null

    fun latinOcr(): OcrEngine = latin ?: MlKitOcrEngine(
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS),
    ).also { latin = it }

    fun koreanOcr(): OcrEngine = korean ?: MlKitOcrEngine(
        TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build()),
    ).also { korean = it }

    fun translator(): TranslationEngine = translator ?: MlKitTranslationEngine().also { translator = it }

    suspend fun prepare(onStatus: (String) -> Unit) {
        try {
            onStatus("Đang tải OCR tiếng Anh...")
            latinOcr().warmUp()
            onStatus("Đang tải OCR tiếng Hàn...")
            koreanOcr().warmUp()
            onStatus("Đang tải mô hình EN → VI...")
            translator().ensureModel(SourceLanguage.EN)
            onStatus("Đang tải mô hình KR → VI...")
            translator().ensureModel(SourceLanguage.KO)
        } catch (error: ModelUnavailableException) {
            throw error
        } catch (error: Exception) {
            throw ModelUnavailableException(UserMessages.MODEL_FAILED, error)
        }
    }

    fun close() {
        latin?.close()
        korean?.close()
        translator?.close()
        latin = null
        korean = null
        translator = null
    }
}
