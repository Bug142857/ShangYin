package com.shangyin.app

import android.content.SharedPreferences
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.core.view.WindowCompat
import com.shangyin.app.ui.AppNav
import com.shangyin.app.ui.settings.SettingsStore
import com.shangyin.app.ui.theme.ShangYinTheme

class MainActivity : ComponentActivity() {

    private val spListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "theme") runOnUiThread { recreate() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SettingsStore.registerListener(spListener)
        enableEdgeToEdge()
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
