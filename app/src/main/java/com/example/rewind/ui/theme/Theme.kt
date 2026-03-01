package com.example.rewind.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val DarkColors = darkColorScheme(
    background = BgTop,
    surface = SurfaceCard,            // <- cards feel richer than plain bg
    surfaceVariant = SurfaceCard2,     // <- for chips / containers if needed

    onBackground = TextStrong,
    onSurface = TextStrong,
    onSurfaceVariant = TextMuted,

    primary = AccentIdea,             // <- keep purple as primary
    onPrimary = Ink,

    secondary = AccentInstruction,
    onSecondary = Ink,

    outline = BorderSoft
)

@Composable
fun RewindTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColors,
        typography = AppTypography,
        content = content
    )
}