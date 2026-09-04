package com.shangyin.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowCompat
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

            val insets = WindowCompat.getInsetsController(window, window.decorView)
            LaunchedEffect(dark) {
                insets.isAppearanceLightStatusBars = !dark
                insets.isAppearanceLightNavigationBars = !dark
            }

            // 主题监听
            LaunchedEffect(Unit) {
                while (true) {
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

    override fun onBackPressed() {
        // 导航过渡期间忽略系统返回连点，避免白屏
        if (!com.shangyin.app.ui.NavGuard.allow()) return
        super.onBackPressed()
    }
}
