package com.shangyin.app

import android.content.SharedPreferences
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.shangyin.app.ui.AppNav
import com.shangyin.app.ui.settings.SettingsStore
import com.shangyin.app.ui.theme.ShangYinTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val spListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "theme") runOnUiThread { recreate() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SettingsStore.registerListener(spListener)
        enableEdgeToEdge()
        // 音乐功能已移除（v2.9.0）：启动时清理音乐条目与音乐清单（幂等）
        lifecycleScope.launch {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { com.shangyin.app.data.Repo.purgeMusicData() }
            }
        }
        setContent {
            val forceDark = SettingsStore.isDark ?: isSystemInDarkTheme()

            val insets = WindowCompat.getInsetsController(window, window.decorView)
            LaunchedEffect(forceDark) {
                insets.isAppearanceLightStatusBars = !forceDark
                insets.isAppearanceLightNavigationBars = !forceDark
            }

            ShangYinTheme(forceDark = forceDark) {
                AppNav(onThemeChanged = { recreate() })
            }
        }
    }

    override fun onDestroy() {
        SettingsStore.unregisterListener(spListener)
        super.onDestroy()
    }
}
