package com.realtimemanga.translator.ui

import android.app.Application
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.realtimemanga.translator.MangaTranslatorApp
import com.realtimemanga.translator.domain.LogTags
import com.realtimemanga.translator.domain.SessionEvent
import com.realtimemanga.translator.domain.SessionState
import com.realtimemanga.translator.domain.SourceMode
import com.realtimemanga.translator.domain.TranslatorConfig
import com.realtimemanga.translator.domain.UserMessages
import com.realtimemanga.translator.service.TranslatorRuntime
import com.realtimemanga.translator.service.TranslatorService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

class HomeViewModel(app: Application) : AndroidViewModel(app) {
    private val application = app as MangaTranslatorApp
    val state: StateFlow<SessionState> = TranslatorRuntime.state
    val sourceMode: StateFlow<SourceMode> = TranslatorRuntime.sourceMode
    val statusDetail: StateFlow<String> = TranslatorRuntime.statusDetail
    val showPrivacy = MutableStateFlow(false)
    val requestOverlay = MutableStateFlow(false)
    val requestNotifications = MutableStateFlow(false)
    val awaitingConsent = MutableStateFlow(false)
    private var waitingForOverlay = false

    fun onStartClicked() {
        viewModelScope.launch { beginStart() }
    }

    fun acceptPrivacy() {
        application.prefs.privacyAccepted = true
        showPrivacy.value = false
        onStartClicked()
    }

    fun dismissPrivacy() {
        showPrivacy.value = false
    }

    fun onResumeCheck() {
        if (waitingForOverlay && Settings.canDrawOverlays(application)) {
            waitingForOverlay = false
            viewModelScope.launch { continueAfterOverlay() }
        }
    }

    fun consumeOverlayRequest() {
        requestOverlay.value = false
    }

    fun onNotificationResult() {
        requestNotifications.value = false
        viewModelScope.launch { prepareAndRequestConsent() }
    }

    fun consumeConsent() {
        awaitingConsent.value = false
    }

    fun onProjectionResult(resultCode: Int, data: android.content.Intent?) {
        if (resultCode != android.app.Activity.RESULT_OK || data == null) {
            dispatch(SessionEvent.CaptureConsentDenied)
            TranslatorRuntime.statusDetail.value = UserMessages.CAPTURE_DENIED
            return
        }
        TranslatorService.start(application, resultCode, data)
    }

    fun setSourceMode(mode: SourceMode) {
        TranslatorRuntime.sourceMode.value = mode
        application.prefs.sourceMode = mode
    }

    fun stop() {
        val running = TranslatorRuntime.service
        if (running == null) {
            dispatch(SessionEvent.Stopped)
            TranslatorRuntime.statusDetail.value = "Sẵn sàng"
            return
        }
        application.startService(
            android.content.Intent(application, TranslatorService::class.java)
                .setAction(TranslatorService.ACTION_STOP),
        )
    }

    private suspend fun beginStart() {
        if (!application.prefs.privacyAccepted) {
            showPrivacy.value = true
            return
        }
        val current = TranslatorRuntime.state.value
        if (TranslatorRuntime.service != null ||
            current == SessionState.Ready || current == SessionState.Paused ||
            current is SessionState.PreparingModels
        ) {
            TranslatorRuntime.statusDetail.value = "Translator đang chạy. Dùng nút nổi trên màn hình."
            return
        }
        dispatch(SessionEvent.StartRequested)
        if (!Settings.canDrawOverlays(application)) {
            waitingForOverlay = true
            requestOverlay.value = true
            dispatch(SessionEvent.OverlayMissing)
            return
        }
        continueAfterOverlay()
    }

    private suspend fun continueAfterOverlay() {
        if (needsNotificationPermission()) {
            requestNotifications.value = true
            return
        }
        prepareAndRequestConsent()
    }

    private suspend fun prepareAndRequestConsent() {
        try {
            withTimeout(TranslatorConfig.MODEL_PREPARE_TIMEOUT_MS) {
                application.registry.prepare { message ->
                    dispatch(SessionEvent.ModelsProgress(message))
                    TranslatorRuntime.statusDetail.value = message
                }
            }
            dispatch(SessionEvent.ModelsReady)
            awaitingConsent.value = true
            TranslatorRuntime.statusDetail.value = "Chọn toàn bộ màn hình khi Android hỏi."
        } catch (cancelled: CancellationException) {
            if (cancelled is kotlinx.coroutines.TimeoutCancellationException) {
                dispatch(SessionEvent.ModelFailed(UserMessages.MODEL_TIMEOUT))
                TranslatorRuntime.statusDetail.value = UserMessages.MODEL_TIMEOUT
            } else {
                throw cancelled
            }
        } catch (error: Exception) {
            TranslatorRuntime.logger.error(LogTags.TRANSLATE, "prepare failed", error)
            val message = error.message ?: UserMessages.MODEL_FAILED
            dispatch(SessionEvent.ModelFailed(message))
            TranslatorRuntime.statusDetail.value = message
        }
    }

    private fun needsNotificationPermission(): Boolean {
        if (android.os.Build.VERSION.SDK_INT < 33) return false
        return androidx.core.content.ContextCompat.checkSelfPermission(
            application,
            android.Manifest.permission.POST_NOTIFICATIONS,
        ) != android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    fun consumeNotificationRequest() {
        requestNotifications.value = false
    }

    private fun dispatch(event: SessionEvent) {
        TranslatorRuntime.dispatch(event)
    }
}
