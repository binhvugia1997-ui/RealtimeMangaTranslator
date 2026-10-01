package com.realtimemanga.translator.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.realtimemanga.translator.MainActivity
import com.realtimemanga.translator.MangaTranslatorApp
import com.realtimemanga.translator.R
import com.realtimemanga.translator.capture.ProjectionLostException
import com.realtimemanga.translator.capture.SingleFrameCapturer
import com.realtimemanga.translator.capture.realScreenSize
import com.realtimemanga.translator.domain.LogTags
import com.realtimemanga.translator.domain.SessionEvent
import com.realtimemanga.translator.domain.SessionState
import com.realtimemanga.translator.domain.SourceMode
import com.realtimemanga.translator.domain.TranslatorConfig
import com.realtimemanga.translator.domain.UserMessages
import com.realtimemanga.translator.domain.statusDetail
import com.realtimemanga.translator.overlay.FloatingActions
import com.realtimemanga.translator.overlay.OverlayController
import com.realtimemanga.translator.pipeline.ManualTranslationPipeline
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicBoolean

class TranslatorService : Service(), FloatingActions {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val released = AtomicBoolean(false)
    private var foregroundStarted = false
    private var projection: MediaProjection? = null
    private var capturer: SingleFrameCapturer? = null
    private var overlay: OverlayController? = null
    private var pipelineJob: Job? = null
    private var pendingPause = false
    private lateinit var pipeline: ManualTranslationPipeline

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val app = application as MangaTranslatorApp
        val logger = TranslatorRuntime.logger
        pipeline = ManualTranslationPipeline(
            latinOcr = app.registry.latinOcr(),
            koreanOcr = app.registry.koreanOcr(),
            router = app.router,
            translator = app.registry.translator(),
            cache = app.cache,
            logger = logger,
        )
        overlay = OverlayController(this, this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Android 14 requires the mediaProjection foreground type before getMediaProjection().
        if (!foregroundStarted) startAsForeground()
        when (intent?.action) {
            ACTION_START -> handleStart(intent)
            ACTION_PAUSE -> onPauseResume()
            ACTION_RESUME -> resume()
            ACTION_TRANSLATE -> onTranslate()
            ACTION_STOP, null -> stopEverything()
            else -> Unit
        }
        return START_NOT_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        overlay?.onConfigurationChanged()
        TranslatorRuntime.overlayItems.value = emptyList()
        TranslatorRuntime.statusDetail.value = UserMessages.ROTATED
        overlay?.refreshStatus()
    }

    override fun onDestroy() {
        TranslatorRuntime.service = null
        scope.coroutineContext[Job]?.cancel()
        super.onDestroy()
    }

    override fun onTranslate() {
        translateNow()
    }

    override fun onPauseResume() {
        if (TranslatorRuntime.state.value == SessionState.Paused) resume() else pause()
    }

    override fun onSourceMode(mode: SourceMode) {
        TranslatorRuntime.sourceMode.value = mode
        (application as MangaTranslatorApp).prefs.sourceMode = mode
    }

    override fun onToggleHide() {
        val hidden = !TranslatorRuntime.translationsHidden.value
        TranslatorRuntime.translationsHidden.value = hidden
        overlay?.setTranslationsVisible(!hidden && TranslatorRuntime.overlayItems.value.isNotEmpty())
    }

    override fun onOpenSettings() {
        startActivity(
            Intent(this, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP,
            ),
        )
    }

    override fun onStop() {
        stopEverything()
    }

    override fun isPaused(): Boolean = TranslatorRuntime.state.value == SessionState.Paused

    override fun translationsHidden(): Boolean = TranslatorRuntime.translationsHidden.value

    override fun sourceMode(): SourceMode = TranslatorRuntime.sourceMode.value

    override fun statusText(): String = TranslatorRuntime.statusDetail.value

    private fun handleStart(intent: Intent) {
        if (projection != null) {
            dispatch(SessionEvent.SessionStarted)
            overlay?.show()
            TranslatorRuntime.service = this
            return
        }
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val data = intent.captureConsent()
        if (!intent.hasExtra(EXTRA_RESULT_CODE) || data == null) {
            fail(UserMessages.PROJECTION_EXPIRED)
            stopEverything()
            return
        }
        try {
            val manager = getSystemService(MediaProjectionManager::class.java)
            val created = manager.getMediaProjection(resultCode, data)
            if (created == null) {
                fail(UserMessages.PROJECTION_EXPIRED)
                stopEverything()
                return
            }
            created.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    if (!released.get()) {
                        TranslatorRuntime.statusDetail.value = UserMessages.PROJECTION_STOPPED
                        dispatch(SessionEvent.ProjectionStopped(UserMessages.PROJECTION_STOPPED))
                        stopEverything()
                    }
                }
            }, Handler(Looper.getMainLooper()))
            projection = created
            capturer = SingleFrameCapturer(
                projection = created,
                windowManager = getSystemService(WINDOW_SERVICE) as android.view.WindowManager,
                displayMetrics = resources.displayMetrics,
                logger = TranslatorRuntime.logger,
            )
            TranslatorRuntime.service = this
            overlay?.show()
            dispatch(SessionEvent.SessionStarted)
            TranslatorRuntime.statusDetail.value = "Sẵn sàng. Bấm Dịch ngay."
            overlay?.refreshStatus()
        } catch (error: RuntimeException) {
            TranslatorRuntime.logger.error(LogTags.CAPTURE, "getMediaProjection failed", error)
            fail(UserMessages.PROJECTION_EXPIRED)
            stopEverything()
        }
    }

    private fun translateNow() {
        val current = capturer
        if (current == null || projection == null) {
            TranslatorRuntime.statusDetail.value = UserMessages.NO_SESSION
            overlay?.refreshStatus()
            return
        }
        if (pipelineJob?.isActive == true) {
            TranslatorRuntime.statusDetail.value = UserMessages.BUSY
            overlay?.refreshStatus()
            return
        }
        pipelineJob = scope.launch {
            try {
                if (TranslatorRuntime.state.value == SessionState.Paused) {
                    dispatch(SessionEvent.Resume)
                }
                overlay?.closeMenu()
                overlay?.setTranslationsVisible(false)
                // Let SurfaceFlinger drop the previous overlay before the next frame is read.
                delay(TranslatorConfig.OVERLAY_HIDE_SETTLE_MS)
                dispatch(SessionEvent.CaptureStarted)
                val bitmap = withTimeout(TranslatorConfig.PIPELINE_TIMEOUT_MS) {
                    current.captureFrame()
                }
                val (screenWidth, screenHeight) = windowManagerScreen()
                val output = withContext(Dispatchers.Default) {
                    withTimeout(TranslatorConfig.PIPELINE_TIMEOUT_MS) {
                        pipeline.run(
                            bitmap = bitmap,
                            screenWidth = screenWidth,
                            screenHeight = screenHeight,
                            mode = TranslatorRuntime.sourceMode.value,
                            excludeScreen = overlay?.buttonScreenBounds(),
                            onPhase = { event -> dispatch(event) },
                        )
                    }
                }
                TranslatorRuntime.overlayItems.value = output.items
                overlay?.render(output.items)
                val show = !TranslatorRuntime.translationsHidden.value && output.items.isNotEmpty()
                overlay?.setTranslationsVisible(show)
                TranslatorRuntime.statusDetail.value = output.detail
                if (pendingPause) {
                    pendingPause = false
                    pause()
                } else if (TranslatorRuntime.state.value !is SessionState.Failed) {
                    dispatch(SessionEvent.PipelineFinished)
                }
                overlay?.refreshStatus()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (lost: ProjectionLostException) {
                fail(lost.message ?: UserMessages.PROJECTION_STOPPED)
                stopEverything()
            } catch (error: Exception) {
                TranslatorRuntime.logger.error(LogTags.CAPTURE, "pipeline failed", error)
                TranslatorRuntime.statusDetail.value = error.message ?: UserMessages.CAPTURE_TIMEOUT
                overlay?.setTranslationsVisible(false)
                if (projection != null && !released.get()) {
                    dispatch(SessionEvent.PipelineFinished)
                }
                overlay?.refreshStatus()
            }
        }
    }

    private fun pause() {
        if (pipelineJob?.isActive == true) {
            pendingPause = true
            return
        }
        dispatch(SessionEvent.Pause)
        overlay?.setTranslationsVisible(false)
        TranslatorRuntime.statusDetail.value = UserMessages.PAUSED
        overlay?.refreshStatus()
    }

    private fun resume() {
        pendingPause = false
        dispatch(SessionEvent.Resume)
        if (!TranslatorRuntime.translationsHidden.value) {
            overlay?.setTranslationsVisible(TranslatorRuntime.overlayItems.value.isNotEmpty())
        }
        TranslatorRuntime.statusDetail.value = TranslatorRuntime.state.value.statusDetail()
        overlay?.refreshStatus()
    }

    private fun stopEverything() {
        if (!released.compareAndSet(false, true)) return
        pipelineJob?.cancel()
        dispatch(SessionEvent.Stop)
        overlay?.hide()
        capturer?.release()
        capturer = null
        try {
            projection?.stop()
        } catch (_: RuntimeException) {
        }
        projection = null
        TranslatorRuntime.overlayItems.value = emptyList()
        TranslatorRuntime.service = null
        dispatch(SessionEvent.Stopped)
        TranslatorRuntime.statusDetail.value = "Sẵn sàng"
        if (foregroundStarted) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            foregroundStarted = false
        }
        stopSelf()
    }

    private fun fail(message: String) {
        TranslatorRuntime.statusDetail.value = message
        dispatch(SessionEvent.Failed(message))
    }

    private fun dispatch(event: SessionEvent) {
        TranslatorRuntime.dispatch(event)
    }

    private fun startAsForeground() {
        createChannel()
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        foregroundStarted = true
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Manga Translator",
            NotificationManager.IMPORTANCE_LOW,
        )
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, TranslatorService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val open = PendingIntent.getActivity(
            this,
            2,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_translate)
            .setContentTitle("Manga Translator đang hoạt động")
            .setContentText("Đang xử lý màn hình trên máy. Bấm để mở app.")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(0, "Dừng", stop)
            .build()
    }

    private fun windowManagerScreen(): Pair<Int, Int> {
        val wm = getSystemService(WINDOW_SERVICE) as android.view.WindowManager
        return wm.realScreenSize()
    }

    private fun Intent.captureConsent(): Intent? {
        return if (Build.VERSION.SDK_INT >= 33) {
            getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra(EXTRA_DATA)
        }
    }

    companion object {
        const val ACTION_START = "com.realtimemanga.translator.START"
        const val ACTION_STOP = "com.realtimemanga.translator.STOP"
        const val ACTION_PAUSE = "com.realtimemanga.translator.PAUSE"
        const val ACTION_RESUME = "com.realtimemanga.translator.RESUME"
        const val ACTION_TRANSLATE = "com.realtimemanga.translator.TRANSLATE"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "result_data"
        private const val CHANNEL_ID = "translator_status"
        private const val NOTIFICATION_ID = 42

        fun start(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, TranslatorService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_DATA, data)
            }
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }
    }
}
