package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val SayonDarkColorScheme = darkColorScheme(
    primary = IndigoPrimary,
    onPrimary = Color.White,
    secondary = CyanAccent,
    onSecondary = DarkSlate950,
    tertiary = VioletAccent,
    background = DarkSlate950,
    surface = DarkSlate900,
    onBackground = TextPrimary,
    onSurface = TextPrimary,
    surfaceVariant = DarkSlate800,
    onSurfaceVariant = TextSecondary,
    outline = DarkSlate700
)

private val SayonLightColorScheme = lightColorScheme(
    primary = IndigoPrimary,
    onPrimary = Color.White,
    secondary = CyanAccent,
    onSecondary = Color.White,
    tertiary = VioletAccent,
    background = Color(0xFFF8FAFC),
    surface = Color.White,
    onBackground = DarkSlate950,
    onSurface = DarkSlate950,
    surfaceVariant = Color(0xFFF1F5F9),
    onSurfaceVariant = DarkSlate700,
    outline = Color(0xFFCBD5E1)
)

@Composable
fun SayonAiTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    // Sayon AI is designed primarily with an ultra-sleek dark aesthetic
    val colorScheme = if (darkTheme) SayonDarkColorScheme else SayonDarkColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
