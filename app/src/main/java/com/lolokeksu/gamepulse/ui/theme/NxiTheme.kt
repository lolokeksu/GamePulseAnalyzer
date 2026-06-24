package com.lolokeksu.gamepulse.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val NxiDarkColorScheme = darkColorScheme(
    primary = NxiAccent,
    secondary = NxiAccentSecondary,
    tertiary = NxiWarning,
    background = NxiBackground,
    surface = NxiSurface,
    surfaceVariant = NxiSurfaceVariant,
    outline = NxiOutline,
    onBackground = NxiTextPrimary,
    onSurface = NxiTextPrimary,
    onSurfaceVariant = NxiTextSecondary,
    error = NxiError,
    onError = NxiTextPrimary
)

@Composable
fun NxiTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = NxiDarkColorScheme,
        content = content
    )
}
