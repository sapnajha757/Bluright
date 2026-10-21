package com.bluright.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val DarkColorScheme = darkColorScheme(
    primary = CyanPrimary,
    onPrimary = NavyDarkBackground,
    primaryContainer = NavyCard,
    onPrimaryContainer = CyanPrimary,
    secondary = TealAccent,
    onSecondary = NavyDarkBackground,
    background = NavyDarkBackground,
    onBackground = TextPrimary,
    surface = NavyDarkSurface,
    onSurface = TextPrimary,
    surfaceVariant = NavyCard,
    onSurfaceVariant = TextSecondary,
    error = PrivacyDanger,
    onError = TextPrimary
)

@Composable
fun BlurightTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = Typography,
        content = content
    )
}
