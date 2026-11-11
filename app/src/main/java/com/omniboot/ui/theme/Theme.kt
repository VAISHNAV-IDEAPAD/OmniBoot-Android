package com.omniboot.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = CyanAccent,
    onPrimary = Color.Black,
    primaryContainer = CardNavy,
    onPrimaryContainer = CyanAccent,
    secondary = BlueAccent,
    background = DeepNavy,
    surface = SurfaceNavy,
    surfaceVariant = CardNavy,
    onBackground = TextPrimary,
    onSurface = TextPrimary,
    outline = BorderNavy,
    error = RedDanger
)

@Composable
fun OmniBootTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        content = content
    )
}
