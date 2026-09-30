package com.xpl.a2uichat.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Light = lightColorScheme(
    primary = Color(0xFF006B5E), onPrimary = Color.White,
    primaryContainer = Color(0xFFD3F3E8), onPrimaryContainer = Color(0xFF004D43),
    secondary = Color(0xFF56665E), background = Color(0xFFF8FAF7),
    surface = Color(0xFFF8FAF7), surfaceContainer = Color(0xFFEEF3EF),
    surfaceContainerLow = Color(0xFFF0F5F1), surfaceContainerHigh = Color(0xFFE5ECE7),
    onSurface = Color(0xFF182B25), onSurfaceVariant = Color(0xFF62736B),
    outlineVariant = Color(0xFFDCE5DE),
)
private val Dark = darkColorScheme(
    primary = Color(0xFF8DD7C1), onPrimary = Color(0xFF00382D),
    primaryContainer = Color(0xFF164E40), onPrimaryContainer = Color(0xFFC6F4E4),
    background = Color(0xFF101B17), surface = Color(0xFF101B17),
    surfaceContainer = Color(0xFF1D2B24), surfaceContainerLow = Color(0xFF17251E),
    surfaceContainerHigh = Color(0xFF2A3830), onSurface = Color(0xFFE2EDE5),
    onSurfaceVariant = Color(0xFFA7BAB0), outlineVariant = Color(0xFF35473D),
)

@Composable
fun ChatTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}
