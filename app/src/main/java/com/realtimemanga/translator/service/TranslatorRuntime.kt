package com.realtimemanga.translator.service

import com.realtimemanga.translator.domain.AppLogger
import com.realtimemanga.translator.domain.OverlayItem
import com.realtimemanga.translator.domain.SessionEvent
import com.realtimemanga.translator.domain.SessionReducer
import com.realtimemanga.translator.domain.SessionState
import com.realtimemanga.translator.domain.SourceMode
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Same-process bridge between the activity, the foreground service, and the overlay.
 * The service clears [service] in onDestroy so a dead session cannot be reused.
 */
object TranslatorRuntime {
    val state = MutableStateFlow<SessionState>(SessionState.Idle)
    val sourceMode = MutableStateFlow(SourceMode.AUTO)
    val overlayItems = MutableStateFlow<List<OverlayItem>>(emptyList())
    val translationsHidden = MutableStateFlow(false)
    val statusDetail = MutableStateFlow("Sẵn sàng")
    var logger: AppLogger = NoopLogger
    var service: TranslatorService? = null

    fun dispatch(event: SessionEvent) {
        synchronized(this) {
            state.value = SessionReducer.reduce(state.value, event)
        }
    }
}

private object NoopLogger : AppLogger {
    override fun debug(tag: String, message: String) = Unit
    override fun error(tag: String, message: String, error: Throwable?) = Unit
}
