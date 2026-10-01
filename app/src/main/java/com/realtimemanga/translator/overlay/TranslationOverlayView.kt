package com.realtimemanga.translator.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.View
import com.realtimemanga.translator.domain.OverlayItem

class TranslationOverlayView(context: Context) : View(context) {
    private val background = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xD014161C.toInt() }
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
    }

    var items: List<OverlayItem> = emptyList()
        set(value) {
            field = value
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        val density = resources.displayMetrics.density
        val pad = 8f * density
        val radius = 10f * density
        val minWidth = (96f * density).toInt()
        val maxWidth = (width * 0.86f).toInt().coerceAtLeast(minWidth)
        for (item in items) {
            if (item.text.isBlank()) continue
            val boxWidth = item.bounds.width.coerceIn(minWidth, maxWidth)
            val layout = fitText(item.text, (boxWidth - pad * 2).toInt().coerceAtLeast(1), density)
            val boxHeight = (layout.height + pad * 2).toInt().coerceAtLeast(item.bounds.height)
            var left = item.bounds.left.toFloat()
            var top = item.bounds.top.toFloat()
            if (left + boxWidth > width) left = (width - boxWidth).toFloat().coerceAtLeast(0f)
            if (top + boxHeight > height) top = (height - boxHeight).toFloat().coerceAtLeast(0f)
            canvas.drawRoundRect(
                RectF(left, top, left + boxWidth, top + boxHeight),
                radius,
                radius,
                background,
            )
            canvas.save()
            canvas.translate(left + pad, top + pad)
            layout.draw(canvas)
            canvas.restore()
        }
    }

    private fun fitText(text: String, width: Int, density: Float): StaticLayout {
        var size = 16f * density
        val min = 11f * density
        var layout = makeLayout(text, width, size)
        while (size > min && layout.height > width * 2 && layout.lineCount > 6) {
            size -= density
            layout = makeLayout(text, width, size)
        }
        return layout
    }

    private fun makeLayout(text: String, width: Int, textSize: Float): StaticLayout {
        textPaint.textSize = textSize
        return StaticLayout.Builder.obtain(text, 0, text.length, textPaint, width)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setIncludePad(false)
            .setLineSpacing(0f, 1.05f)
            .build()
    }
}
