package com.realtimemanga.translator.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.realtimemanga.translator.domain.SessionState
import com.realtimemanga.translator.domain.SourceMode
import com.realtimemanga.translator.domain.statusDetail
import com.realtimemanga.translator.domain.statusLabel

@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onLaunchProjection: () -> Unit,
    onRequestNotifications: () -> Unit,
    onRequestOverlay: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val mode by viewModel.sourceMode.collectAsState()
    val detail by viewModel.statusDetail.collectAsState()
    val showPrivacy by viewModel.showPrivacy.collectAsState()
    val requestOverlay by viewModel.requestOverlay.collectAsState()
    val requestNotifications by viewModel.requestNotifications.collectAsState()
    val awaitingConsent by viewModel.awaitingConsent.collectAsState()

    androidx.compose.runtime.LaunchedEffect(requestOverlay) {
        if (requestOverlay) {
            viewModel.consumeOverlayRequest()
            onRequestOverlay()
        }
    }
    androidx.compose.runtime.LaunchedEffect(requestNotifications) {
        if (requestNotifications) {
            viewModel.consumeNotificationRequest()
            onRequestNotifications()
        }
    }
    androidx.compose.runtime.LaunchedEffect(awaitingConsent) {
        if (awaitingConsent) {
            viewModel.consumeConsent()
            onLaunchProjection()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 24.dp, vertical = 28.dp),
    ) {
        Text(
            text = "Manga Translator",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = "Dịch truyện trực tiếp màn hình",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(28.dp))
        StatusCard(state = state, detail = detail.ifBlank { state.statusDetail() })
        Spacer(Modifier.height(28.dp))
        Text("NGUỒN", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SourceChip("AUTO", mode == SourceMode.AUTO) { viewModel.setSourceMode(SourceMode.AUTO) }
            SourceChip("EN", mode == SourceMode.EN) { viewModel.setSourceMode(SourceMode.EN) }
            SourceChip("KR", mode == SourceMode.KR) { viewModel.setSourceMode(SourceMode.KR) }
        }
        Spacer(Modifier.height(24.dp))
        Text("ĐÍCH", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Tiếng Việt", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(24.dp))
        Text("CHẾ ĐỘ", color = MaterialTheme.colorScheme.onSurfaceVariant)
        ModeRow(selected = false, enabled = false, label = "Tự động khi cuộn (V2)")
        ModeRow(selected = true, enabled = true, label = "Thủ công")
        Spacer(Modifier.height(28.dp))
        val running = state == SessionState.Ready || state == SessionState.Paused ||
            state == SessionState.Capturing || state == SessionState.Recognizing ||
            state == SessionState.Translating
        Button(
            onClick = { if (running) viewModel.stop() else viewModel.onStartClicked() },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (running) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            ),
        ) {
            Text(if (running) "DỪNG" else "BẮT ĐẦU DỊCH")
        }
        Spacer(Modifier.height(16.dp))
        Text(
            text = "Miễn phí · Trên máy",
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Ứng dụng xử lý hình ảnh màn hình để nhận dạng và dịch văn bản. Ảnh được xử lý trên máy và không được tải lên server.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
    }

    if (showPrivacy) {
        AlertDialog(
            onDismissRequest = viewModel::dismissPrivacy,
            title = { Text("Quyền riêng tư") },
            text = {
                Text("Ứng dụng xử lý hình ảnh màn hình để nhận dạng và dịch văn bản. Ảnh không được tải lên server và không được lưu mặc định.")
            },
            confirmButton = { TextButton(onClick = viewModel::acceptPrivacy) { Text("Tôi hiểu") } },
            dismissButton = { TextButton(onClick = viewModel::dismissPrivacy) { Text("Huỷ") } },
        )
    }
}

@Composable
private fun StatusCard(state: SessionState, detail: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("TRẠNG THÁI", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Spacer(
                Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(statusColor(state)),
            )
            Text(state.statusLabel(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Medium)
        }
        Spacer(Modifier.height(8.dp))
        Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SourceChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(label) })
}

@Composable
private fun ModeRow(selected: Boolean, enabled: Boolean, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        RadioButton(
            selected = selected,
            onClick = null,
            enabled = enabled,
            colors = RadioButtonDefaults.colors(selectedColor = MaterialTheme.colorScheme.primary),
        )
        Text(label, color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun statusColor(state: SessionState): Color = when (state) {
    SessionState.Ready, SessionState.Idle -> Color(0xFF7DCEA0)
    SessionState.Paused, is SessionState.PreparingModels, SessionState.RequestingPermission -> Color(0xFFE7B15A)
    is SessionState.Failed -> Color(0xFFE07A7A)
    else -> Color(0xFF8EB4FF)
}
