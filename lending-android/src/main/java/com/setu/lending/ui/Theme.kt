package com.setu.lending.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Brand palette
val Brand = Color(0xFF0D9488)
val BrandDark = Color(0xFF0F766E)
val Ink = Color(0xFF0F172A)
val Sub = Color(0xFF64748B)
val Line = Color(0xFFE5E7EB)
val Soft = Color(0xFFF8FAFC)

// Siri-inspired assistant accents
val Accent1 = Color(0xFF6366F1)
val Accent2 = Color(0xFF8B5CF6)
val Accent3 = Color(0xFF22D3EE)
val Accent4 = Color(0xFFEC4899)

private val LightColors = lightColorScheme(
    primary = Brand,
    onPrimary = Color.White,
    secondary = Accent1,
    background = Color(0xFFEEF2F6),
    surface = Color.White,
    onSurface = Ink,
)

private val DarkColors = darkColorScheme(
    primary = Brand,
    onPrimary = Color.White,
    secondary = Accent1,
    background = Color(0xFF0B1220),
    surface = Color(0xFF111827),
    onSurface = Color(0xFFE5E7EB),
)

@Composable
fun SetuTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content
    )
}
