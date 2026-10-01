package com.realtimemanga.translator.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Night = darkColorScheme(
    primary = Color(0xFFE7B15A),
    onPrimary = Color(0xFF1B1408),
    secondary = Color(0xFF9BB0C9),
    background = Color(0xFF0E1116),
    onBackground = Color(0xFFF4F1EA),
    surface = Color(0xFF171C24),
    onSurface = Color(0xFFF4F1EA),
    surfaceVariant = Color(0xFF222833),
    onSurfaceVariant = Color(0xFFB7C0CC),
    error = Color(0xFFE07A7A),
    outline = Color(0xFF3A4250),
)

@Composable
fun MangaTranslatorTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Night, content = content)
}
