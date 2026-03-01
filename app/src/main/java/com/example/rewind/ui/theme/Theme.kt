package com.example.rewind.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

// Theme.kt
private val DarkColors = darkColorScheme(
    background = androidx.compose.ui.graphics.Color(0xFF070812),
    surface = androidx.compose.ui.graphics.Color(0xFF0E1020),

    onBackground = androidx.compose.ui.graphics.Color(0xFFF2F3FF),
    onSurface = androidx.compose.ui.graphics.Color(0xFFF2F3FF),

    primary = androidx.compose.ui.graphics.Color(0xFF8B5CF6),
    onPrimary = androidx.compose.ui.graphics.Color(0xFF0B0B10),

    secondary = androidx.compose.ui.graphics.Color(0xFF22D3EE),
    onSecondary = androidx.compose.ui.graphics.Color(0xFF061015),

    outline = androidx.compose.ui.graphics.Color(0xFF2A2D48)
)

@Composable
fun RewindTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColors,
        typography = AppTypography,
        content = content
    )
}