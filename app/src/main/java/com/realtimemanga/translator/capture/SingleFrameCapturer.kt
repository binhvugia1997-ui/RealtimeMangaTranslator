package com.realtimemanga.translator.capture

import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.DisplayMetrics
import android.view.WindowManager
import com.realtimemanga.translator.domain.AppLogger
import com.realtimemanga.translator.domain.EmptyFrameDetector
import com.realtimemanga.translator.domain.LogTags
import com.realtimemanga.translator.domain.TranslatorConfig
import com.realtimemanga.translator.domain.UserMessages
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class EmptyCaptureException(message: String) : Exception(message)
class CaptureTimeoutException(message: String) : Exception(message)
class ProjectionLostException(message: String) : Exception(message)

class SingleFrameCapturer(
    private val projection: MediaProjection,
    private val windowManager: WindowManager,
    private val displayMetrics: DisplayMetrics,
    private val logger: AppLogger,
) {
    @Volatile
    private var activeDisplay: VirtualDisplay? = null

    @Volatile
    private var activeReader: ImageReader? = null

    suspend fun captureFrame(): Bitmap = suspendCancellableCoroutine { continuation ->
        val size = windowManager.realScreenSize()
        val width = size.first.coerceAtLeast(2)
        val height = size.second.coerceAtLeast(2)
        val density = displayMetrics.densityDpi.coerceAtLeast(1)
        val thread = HandlerThread("manga-capture").also { it.start() }
        val handler = Handler(thread.looper)
        val delivered = AtomicBoolean(false)
        var reader: ImageReader? = null
        var display: VirtualDisplay? = null
        var sawBlank = false

        fun finish(block: () -> Unit) {
            if (delivered.compareAndSet(false, true)) block()
        }

        fun cleanup() {
            try {
                reader?.setOnImageAvailableListener(null, null)
            } catch (_: RuntimeException) {
            }
            try {
                display?.release()
            } catch (_: RuntimeException) {
            }
            try {
                reader?.close()
            } catch (_: RuntimeException) {
            }
            if (activeDisplay === display) activeDisplay = null
            if (activeReader === reader) activeReader = null
            thread.quitSafely()
        }

        continuation.invokeOnCancellation { finish { cleanup() } }

        val timeout = Runnable {
            finish {
                cleanup()
                if (continuation.isActive) {
                    continuation.resumeWithException(
                        if (sawBlank) {
                            EmptyCaptureException(UserMessages.SELECT_ENTIRE_SCREEN)
                        } else {
                            CaptureTimeoutException(UserMessages.CAPTURE_TIMEOUT)
                        },
                    )
                }
            }
        }
        handler.postDelayed(timeout, TranslatorConfig.CAPTURE_TIMEOUT_MS)

        try {
            reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)
            activeReader = reader
            reader.setOnImageAvailableListener({ imageReader ->
                val image = try {
                    imageReader.acquireLatestImage()
                } catch (_: RuntimeException) {
                    null
                } ?: return@setOnImageAvailableListener
                val bitmap = try {
                    image.toSoftwareBitmap()
                } catch (error: RuntimeException) {
                    image.close()
                    finish {
                        handler.removeCallbacks(timeout)
                        cleanup()
                        if (continuation.isActive) continuation.resumeWithException(error)
                    }
                    return@setOnImageAvailableListener
                }
                image.close()
                if (EmptyFrameDetector.isLikelyBlank(bitmap.sampleArgb()) && !sawBlank) {
                    sawBlank = true
                    bitmap.recycle()
                    logger.debug(LogTags.CAPTURE, "skipped blank first frame")
                    return@setOnImageAvailableListener
                }
                finish {
                    handler.removeCallbacks(timeout)
                    cleanup()
                    if (continuation.isActive) {
                        logger.debug(LogTags.CAPTURE, "frame ${bitmap.width}x${bitmap.height}")
                        continuation.resume(bitmap)
                    } else {
                        bitmap.recycle()
                    }
                }
            }, handler)
            // Callback is registered on the projection before the first capture.
            display = projection.createVirtualDisplay(
                "manga-translator-frame",
                width,
                height,
                density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                null,
                handler,
            )
            activeDisplay = display
        } catch (error: RuntimeException) {
            finish {
                handler.removeCallbacks(timeout)
                cleanup()
                if (continuation.isActive) {
                    continuation.resumeWithException(
                        ProjectionLostException(UserMessages.PROJECTION_EXPIRED),
                    )
                }
            }
            logger.error(LogTags.CAPTURE, "virtual display failed", error)
        }
    }

    fun release() {
        try {
            activeDisplay?.release()
        } catch (_: RuntimeException) {
        }
        try {
            activeReader?.close()
        } catch (_: RuntimeException) {
        }
        activeDisplay = null
        activeReader = null
    }
}

private fun Image.toSoftwareBitmap(): Bitmap {
    val plane = planes[0]
    val buffer = plane.buffer
    buffer.rewind()
    val pixelStride = plane.pixelStride.coerceAtLeast(1)
    val rowPadding = plane.rowStride - pixelStride * width
    val paddedWidth = (width + rowPadding / pixelStride).coerceAtLeast(width)
    val padded = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
    padded.copyPixelsFromBuffer(buffer)
    if (paddedWidth == width) return padded
    val cropped = Bitmap.createBitmap(padded, 0, 0, width, height)
    padded.recycle()
    return cropped
}

private fun Bitmap.sampleArgb(): IntArray {
    val stepX = (width / 48).coerceAtLeast(1)
    val stepY = (height / 48).coerceAtLeast(1)
    val samples = ArrayList<Int>((width / stepX) * (height / stepY))
    var y = 0
    while (y < height) {
        var x = 0
        while (x < width) {
            samples += getPixel(x, y)
            x += stepX
        }
        y += stepY
    }
    return samples.toIntArray()
}

fun WindowManager.realScreenSize(): Pair<Int, Int> {
    // currentWindowMetrics is API 30. Older devices still need the real pixel size,
    // including the status bar, so overlay coordinates stay aligned with the capture.
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val bounds = currentWindowMetrics.bounds
        bounds.width() to bounds.height()
    } else {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        defaultDisplay.getRealMetrics(metrics)
        metrics.widthPixels to metrics.heightPixels
    }
}

fun downscale(source: Bitmap, maxEdge: Int): Bitmap {
    val edge = maxOf(source.width, source.height)
    if (edge <= maxEdge || edge <= 0) return source
    val scale = maxEdge.toFloat() / edge.toFloat()
    val width = (source.width * scale).toInt().coerceAtLeast(1)
    val height = (source.height * scale).toInt().coerceAtLeast(1)
    return Bitmap.createScaledBitmap(source, width, height, true)
}
