package com.shangyin.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.shangyin.app.ui.AppNav
import com.shangyin.app.ui.settings.SettingsStore
import com.shangyin.app.ui.theme.ShangYinTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            var forceDark by remember { mutableStateOf(SettingsStore.isDark) }
            val dark = forceDark ?: isSystemInDarkTheme()

            // 状态栏/导航栏图标颜色跟随主题：深色→白色图标，浅色→黑色图标
            val insets = WindowCompat.getInsetsController(window, window.decorView)
            LaunchedEffect(dark) {
                insets.isAppearanceLightStatusBars = !dark
                insets.isAppearanceLightNavigationBars = !dark
            }

            // 监听主题变化
            LaunchedEffect(Unit) {
                while (true) {
                    // 每秒检查一次主题设置是否变化（简单够用）
                    kotlinx.coroutines.delay(1000)
                    val current = SettingsStore.isDark
                    if (current != forceDark) forceDark = current
                }
            }

            ShangYinTheme(forceDark = dark) {
                AppNav(onThemeChanged = {
                    forceDark = SettingsStore.isDark
                })
            }
        }
    }
}
