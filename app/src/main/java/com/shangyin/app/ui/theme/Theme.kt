package com.shangyin.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val Green = Color(0xFF1B5E4A)
private val GreenDark = Color(0xFF7FD8BC)

private val LightColors = lightColorScheme(
    primary = Green,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB7E8D6),
    onPrimaryContainer = Color(0xFF002017),
    secondary = Color(0xFF4B635A),
    tertiary = Color(0xFF3E6373)
)

private val DarkColors = darkColorScheme(
    primary = GreenDark,
    onPrimary = Color(0xFF00382A),
    primaryContainer = Color(0xFF00513E),
    onPrimaryContainer = Color(0xFF96F1D3)
)

/**
 * @param forceDark true=强制深色, false=强制浅色, null=跟随系统
 */
@Composable
fun ShangYinTheme(
    forceDark: Boolean? = null,
    content: @Composable () -> Unit
) {
    val systemDark = isSystemInDarkTheme()
    val dark = forceDark ?: systemDark
    val context = LocalContext.current
    val colors = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
        if (dark) DarkColors else LightColors
    }
    MaterialTheme(colorScheme = colors, content = content)
}
