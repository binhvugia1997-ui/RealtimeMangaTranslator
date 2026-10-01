package com.realtimemanga.translator.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.realtimemanga.translator.domain.Bounds
import com.realtimemanga.translator.domain.OverlayItem
import com.realtimemanga.translator.domain.SourceMode
import kotlin.math.abs

interface FloatingActions {
    fun onTranslate()
    fun onPauseResume()
    fun onSourceMode(mode: SourceMode)
    fun onToggleHide()
    fun onOpenSettings()
    fun onStop()
    fun isPaused(): Boolean
    fun translationsHidden(): Boolean
    fun sourceMode(): SourceMode
    fun statusText(): String
}

class OverlayController(
    private val context: Context,
    private val actions: FloatingActions,
) {
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private var translationView: TranslationOverlayView? = null
    private var buttonRoot: LinearLayout? = null
    private var translationParams: WindowManager.LayoutParams? = null
    private var buttonParams: WindowManager.LayoutParams? = null
    private var menuPanel: LinearLayout? = null
    private var menuVisible = false
    private var shown = false

    fun show() {
        if (shown) return
        val (screenWidth, screenHeight) = screenSize()
        translationParams = overlayParams(screenWidth, screenHeight)
        translationView = TranslationOverlayView(context).also {
            it.setBackgroundColor(0x00000000)
        }
        windowManager.addView(translationView, translationParams)

        buttonParams = buttonParams(screenWidth, screenHeight)
        buttonRoot = buildButtonRoot()
        windowManager.addView(buttonRoot, buttonParams)
        shown = true
    }

    fun hide() {
        if (!shown) return
        remove(translationView)
        remove(buttonRoot)
        translationView = null
        buttonRoot = null
        menuPanel = null
        shown = false
        menuVisible = false
    }

    fun render(items: List<OverlayItem>) {
        translationView?.items = items
    }

    fun setTranslationsVisible(visible: Boolean) {
        translationView?.visibility = if (visible) View.VISIBLE else View.INVISIBLE
    }

    fun closeMenu() {
        menuVisible = false
        menuPanel?.visibility = View.GONE
    }

    fun refreshStatus() {
        buttonRoot?.findViewWithTag<TextView>(TAG_STATUS)?.text = actions.statusText()
    }

    fun buttonScreenBounds(): Bounds {
        val params = buttonParams ?: return Bounds(0, 0, 0, 0)
        val view = buttonRoot
        val width = view?.width?.takeIf { it > 0 } ?: dp(280)
        val height = view?.height?.takeIf { it > 0 } ?: dp(64)
        return Bounds(params.x, params.y, params.x + width, params.y + height)
    }

    fun onConfigurationChanged() {
        if (!shown) return
        val (screenWidth, screenHeight) = screenSize()
        translationParams?.let { params ->
            params.width = screenWidth
            params.height = screenHeight
            translationView?.let { windowManager.updateViewLayout(it, params) }
        }
        buttonParams?.let { params ->
            params.x = params.x.coerceIn(0, (screenWidth - dp(64)).coerceAtLeast(0))
            params.y = params.y.coerceIn(0, (screenHeight - dp(64)).coerceAtLeast(0))
            buttonRoot?.let { windowManager.updateViewLayout(it, params) }
        }
        render(emptyList())
    }

    private fun buildButtonRoot(): LinearLayout {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, 0)
        }
        val handle = TextView(context).apply {
            text = "文 VI"
            setTextColor(0xFFF4F1EA.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.CENTER
            minWidth = dp(64)
            minHeight = dp(56)
            contentDescription = "Manga Translator"
            background = pill()
            setOnTouchListener(dragListener())
        }
        val status = TextView(context).apply {
            tag = TAG_STATUS
            setTextColor(0xFFD7DEE8.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setPadding(dp(8), dp(4), dp(8), dp(2))
            text = actions.statusText()
        }
        menuPanel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            background = card()
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }.also { panel ->
            addAction(panel, "Dịch ngay") { actions.onTranslate() }
            addAction(panel, if (actions.isPaused()) "Tiếp tục" else "Tạm dừng") {
                actions.onPauseResume()
                refreshMenuLabels()
            }
            addAction(panel, "AUTO") { actions.onSourceMode(SourceMode.AUTO) }
            addAction(panel, "EN → VI") { actions.onSourceMode(SourceMode.EN) }
            addAction(panel, "KR → VI") { actions.onSourceMode(SourceMode.KO) }
            addAction(panel, if (actions.translationsHidden()) "Hiện bản dịch" else "Ẩn bản dịch") {
                actions.onToggleHide()
                refreshMenuLabels()
            }
            addAction(panel, "Cài đặt") { actions.onOpenSettings() }
            addAction(panel, "Dừng") { actions.onStop() }
        }
        root.addView(handle)
        root.addView(status)
        root.addView(menuPanel)
        return root
    }

    private fun refreshMenuLabels() {
        val panel = menuPanel ?: return
        if (panel.childCount >= 7) {
            (panel.getChildAt(1) as? TextView)?.text = if (actions.isPaused()) "Tiếp tục" else "Tạm dừng"
            (panel.getChildAt(5) as? TextView)?.text =
                if (actions.translationsHidden()) "Hiện bản dịch" else "Ẩn bản dịch"
        }
        markSelectedMode()
    }

    private fun markSelectedMode() {
        val panel = menuPanel ?: return
        val mode = actions.sourceMode()
        val labels = listOf(SourceMode.AUTO, SourceMode.EN, SourceMode.KR)
        labels.forEachIndexed { index, item ->
            val view = panel.getChildAt(index + 2) as? TextView ?: return@forEachIndexed
            view.setTextColor(if (item == mode) 0xFFE7B15A.toInt() else 0xFFF4F1EA.toInt())
        }
    }

    private fun addAction(panel: LinearLayout, label: String, click: () -> Unit) {
        val view = TextView(context).apply {
            text = label
            setTextColor(0xFFF4F1EA.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            setOnClickListener {
                click()
                closeMenu()
            }
        }
        panel.addView(view)
    }

    private fun dragListener(): View.OnTouchListener {
        var downRawX = 0f
        var downRawY = 0f
        var startX = 0
        var startY = 0
        var dragging = false
        return View.OnTouchListener { view, event ->
            val params = buttonParams ?: return@OnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startX = params.x
                    startY = params.y
                    dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downRawX).toInt()
                    val dy = (event.rawY - downRawY).toInt()
                    if (abs(dx) > dp(8) || abs(dy) > dp(8)) dragging = true
                    if (dragging) {
                        val (screenWidth, screenHeight) = screenSize()
                        params.x = (startX + dx).coerceIn(0, (screenWidth - view.width).coerceAtLeast(0))
                        params.y = (startY + dy).coerceIn(0, (screenHeight - view.height).coerceAtLeast(0))
                        buttonRoot?.let { windowManager.updateViewLayout(it, params) }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragging) {
                        menuVisible = !menuVisible
                        menuPanel?.visibility = if (menuVisible) View.VISIBLE else View.GONE
                        if (menuVisible) {
                            refreshMenuLabels()
                        }
                        view.performClick()
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun overlayParams(width: Int, height: Int): WindowManager.LayoutParams {
        return WindowManager.LayoutParams(
            width,
            height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_SECURE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
            applyCutout()
        }
    }

    private fun buttonParams(screenWidth: Int, screenHeight: Int): WindowManager.LayoutParams {
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_SECURE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (screenWidth - dp(80)).coerceAtLeast(0)
            y = (screenHeight * 0.55f).toInt()
            applyCutout()
        }
    }

    private fun WindowManager.LayoutParams.applyCutout() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }

    private fun screenSize(): Pair<Int, Int> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            bounds.width() to bounds.height()
        } else {
            val metrics = android.util.DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)
            metrics.widthPixels to metrics.heightPixels
        }
    }

    private fun remove(view: View?) {
        if (view == null) return
        try {
            windowManager.removeView(view)
        } catch (_: IllegalArgumentException) {
        }
    }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()

    private fun pill(): GradientDrawable = GradientDrawable().apply {
        setColor(0xF2161A22.toInt())
        cornerRadius = dp(28).toFloat()
        setStroke(dp(1), 0x66E7B15A)
    }

    private fun card(): GradientDrawable = GradientDrawable().apply {
        setColor(0xF2161A22.toInt())
        cornerRadius = dp(12).toFloat()
    }

    private companion object {
        const val TAG_STATUS = "status"
    }
}
