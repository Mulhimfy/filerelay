package com.mulhimfy.filerelay

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF0B6BCB), onPrimary = Color.White,
    primaryContainer = Color(0xFFD5E3FF), onPrimaryContainer = Color(0xFF001B3D),
    secondary = Color(0xFF00897B), onSecondary = Color.White,
    secondaryContainer = Color(0xFFCFE5FF), onSecondaryContainer = Color(0xFF001D33),
    background = Color(0xFFF8F9FC), onBackground = Color(0xFF191C20),
    surface = Color(0xFFF8F9FC), onSurface = Color(0xFF191C20),
    surfaceVariant = Color(0xFFE0E3EB), onSurfaceVariant = Color(0xFF44474F),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF2F4F9),
    surfaceContainer = Color(0xFFECEEF4), surfaceContainerHigh = Color(0xFFE6E8EE),
    surfaceContainerHighest = Color(0xFFE0E2E9),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFA7C8FF), onPrimary = Color(0xFF003063),
    primaryContainer = Color(0xFF00468B), onPrimaryContainer = Color(0xFFD5E3FF),
    secondary = Color(0xFF7FD8CB), onSecondary = Color(0xFF003731),
    secondaryContainer = Color(0xFF1F3A57), onSecondaryContainer = Color(0xFFCFE5FF),
    background = Color(0xFF111318), onBackground = Color(0xFFE2E2E9),
    surface = Color(0xFF111318), onSurface = Color(0xFFE2E2E9),
    surfaceVariant = Color(0xFF43474E), onSurfaceVariant = Color(0xFFC4C6D0),
    surfaceContainerLowest = Color(0xFF0C0E13), surfaceContainerLow = Color(0xFF191C20),
    surfaceContainer = Color(0xFF1D2024), surfaceContainerHigh = Color(0xFF282A2F),
    surfaceContainerHighest = Color(0xFF33353A),
)

@Composable
fun RelayTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors, content = content)
}
