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
 * 可选强调色（v0.304：主题管理页选色后立即生效）。
 * key = "dynamic" 表示跟随系统动态取色（Android 12+ 默认行为）。
 */
data class AccentOption(
    val key: String,
    val label: String,
    val lightPrimary: Color,
    val lightContainer: Color,
    val onLightContainer: Color,
    val darkPrimary: Color,
    val darkContainer: Color,
    val onDarkContainer: Color
)

val ACCENT_OPTIONS = listOf(
    AccentOption(
        "dynamic", "跟随系统", Green, Color(0xFFB7E8D6), Color(0xFF002017),
        GreenDark, Color(0xFF00513E), Color(0xFF96F1D3)
    ),
    AccentOption(
        "green", "墨绿", Color(0xFF1B5E4A), Color(0xFFB7E8D6), Color(0xFF002017),
        Color(0xFF7FD8BC), Color(0xFF00513E), Color(0xFF96F1D3)
    ),
    AccentOption(
        "purple", "紫", Color(0xFF6750A4), Color(0xFFEADDFF), Color(0xFF21005D),
        Color(0xFFD0BCFF), Color(0xFF4F378B), Color(0xFFEADDFF)
    ),
    AccentOption(
        "blue", "蓝", Color(0xFF1565C0), Color(0xFFD3E4FF), Color(0xFF001D36),
        Color(0xFF9FC9FF), Color(0xFF00497D), Color(0xFFD3E4FF)
    ),
    AccentOption(
        "cyan", "青", Color(0xFF00696D), Color(0xFF9CF1F4), Color(0xFF002020),
        Color(0xFF80D5D8), Color(0xFF004F52), Color(0xFF9CF1F4)
    ),
    AccentOption(
        "orange", "橙", Color(0xFF8B5000), Color(0xFFFFDDB8), Color(0xFF2D1600),
        Color(0xFFFFB870), Color(0xFF6A3900), Color(0xFFFFDDB8)
    ),
    AccentOption(
        "pink", "粉", Color(0xFF8E4585), Color(0xFFFFD7F2), Color(0xFF3A0030),
        Color(0xFFFFADEB), Color(0xFF6B2A63), Color(0xFFFFD7F2)
    )
)

fun accentLabel(key: String): String =
    ACCENT_OPTIONS.firstOrNull { it.key == key }?.label ?: "跟随系统"

/**
 * @param forceDark true=强制深色, false=强制浅色, null=跟随系统
 * @param accentKey 强调色 key（见 [ACCENT_OPTIONS]）；"dynamic" = 系统动态取色
 */
@Composable
fun ShangYinTheme(
    forceDark: Boolean? = null,
    accentKey: String = "dynamic",
    content: @Composable () -> Unit
) {
    val systemDark = isSystemInDarkTheme()
    val dark = forceDark ?: systemDark
    val context = LocalContext.current
    val accent = ACCENT_OPTIONS.firstOrNull { it.key == accentKey }

    // 选了具体颜色就用它覆盖 primary；"跟随系统" 且系统支持动态取色时仍走动态色
    val colors = when {
        accent == null || accent.key == "dynamic" -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            } else {
                if (dark) DarkColors else LightColors
            }
        }
        dark -> DarkColors.copy(
            primary = accent.darkPrimary,
            onPrimary = Color(0xFF00201A),
            primaryContainer = accent.darkContainer,
            onPrimaryContainer = accent.onDarkContainer
        )
        else -> LightColors.copy(
            primary = accent.lightPrimary,
            onPrimary = Color.White,
            primaryContainer = accent.lightContainer,
            onPrimaryContainer = accent.onLightContainer
        )
    }
    MaterialTheme(colorScheme = colors, content = content)
}
