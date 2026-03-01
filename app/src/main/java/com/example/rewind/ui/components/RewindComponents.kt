package com.example.rewind.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.rewind.ui.theme.Hairline
import com.example.rewind.ui.theme.TextMuted
import com.example.rewind.ui.theme.TextStrong

private val Squircle = RoundedCornerShape(20.dp) // softer than 12dp, not a “default” look

@Composable
fun RewindPrimaryButton(
    text: String,
    onClick: () -> Unit,
    accent: Color,
    modifier: Modifier = Modifier
) {
    val grad = Brush.horizontalGradient(listOf(accent, accent.copy(alpha = 0.75f)))

    Box(
        modifier = modifier
            .height(52.dp)
            .clip(Squircle)
            .background(grad)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = Color(0xFF0B0B10),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun RewindGhostButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .height(52.dp)
            .clip(Squircle)
            .border(0.5.dp, Hairline, Squircle)
            .background(Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text = text, style = MaterialTheme.typography.labelLarge, color = TextStrong)
    }
}

@Composable
fun RewindChip(
    text: String,
    selected: Boolean,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(14.dp) // not the same rounding as cards/buttons

    val bg = if (selected) accent.copy(alpha = 0.20f) else Color.Transparent
    val stroke = if (selected) accent.copy(alpha = 0.55f) else Hairline
    val fg = if (selected) TextStrong else TextMuted

    Box(
        modifier = modifier
            .clip(shape)
            .border(0.5.dp, stroke, shape)
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text = text, style = MaterialTheme.typography.labelLarge, color = fg)
    }
}