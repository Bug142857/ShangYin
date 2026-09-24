package com.shangyin.app

import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.core.view.WindowCompat
import com.shangyin.app.ui.AppNav
import com.shangyin.app.ui.NavRestore
import com.shangyin.app.ui.settings.SettingsStore
import com.shangyin.app.ui.theme.ShangYinTheme

class MainActivity : ComponentActivity() {

    private val spListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "theme") runOnUiThread { recreate() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 点「播放通知」把 App 唤到前台（含进程/Activity 被回收后重建）时打个标记，
        // AppNav 首次组合时会读它并跳回上次的页面（见 NavRestore / AppNav）
        if (intent?.getBooleanExtra(NavRestore.EXTRA_FROM_NOTIFICATION, false) == true) {
            NavRestore.markFromNotification(this)
        }
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

    /**
     * singleTask + 通知 Intent 带 NEW_TASK/SINGLE_TOP 时，点通知会走这里而不是重建：
     * 此时 Activity 与 Compose 导航栈都还在，**不做任何跳转就停留在最小化前的那一页**，
     * 正是需求要的效果。因此这里只更新 intent，并清掉「来自通知」标记
     * （Compose 侧不会再有冷启动去消费它，留着会污染下一次冷启动）。
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(NavRestore.EXTRA_FROM_NOTIFICATION, false)) {
            NavRestore.clearFromNotification(this)
        }
    }

    override fun onDestroy() {
        SettingsStore.unregisterListener(spListener)
        super.onDestroy()
    }
}
