package com.realtimemanga.translator

import android.Manifest
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.ViewModelProvider
import com.realtimemanga.translator.ui.HomeScreen
import com.realtimemanga.translator.ui.HomeViewModel
import com.realtimemanga.translator.ui.MangaTranslatorTheme

class MainActivity : ComponentActivity() {
    private val viewModel: HomeViewModel by lazy {
        ViewModelProvider(
            this,
            ViewModelProvider.AndroidViewModelFactory.getInstance(application),
        )[HomeViewModel::class.java]
    }

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        viewModel.onProjectionResult(result.resultCode, result.data)
    }

    private val notificationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        viewModel.onNotificationResult()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MangaTranslatorTheme {
                HomeScreen(
                    viewModel = viewModel,
                    onLaunchProjection = {
                        val manager = getSystemService(MediaProjectionManager::class.java)
                        projectionLauncher.launch(manager.createScreenCaptureIntent())
                    },
                    onRequestNotifications = {
                        notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    },
                    onRequestOverlay = {
                        startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:$packageName"),
                            ),
                        )
                    },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.onResumeCheck()
    }
}
