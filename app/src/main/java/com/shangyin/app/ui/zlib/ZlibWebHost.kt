package com.shangyin.app.ui.zlib

import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.shangyin.app.data.zlib.ZlibWeb

/**
 * Z-Library 接口用的常驻隐藏 WebView（挂在 AppNav 根节点，全 App 生命周期存活）。
 *
 * 1dp + alpha=0：完全不可见、不占位、不拦截触摸，但页面照常加载与执行 JS。
 * 之所以必须常驻：DiamWall 的验证 cookie 只有 5 分钟有效期，页面活着才能自动续期；
 * 每次进图书页再新建 WebView 会重新触发整套挑战（慢且容易失败）。
 */
@Composable
fun ZlibWebHost() {
    val holder = remember { mutableStateOf<WebView?>(null) }
    AndroidView(
        factory = { ctx ->
            WebView(ctx).apply {
                holder.value = this
                ZlibWeb.attach(this)
            }
        },
        modifier = Modifier.size(1.dp).alpha(0f)
    )
    DisposableEffect(Unit) {
        onDispose {
            holder.value?.let { wv ->
                ZlibWeb.detach(wv)
                wv.stopLoading()
                wv.settings.javaScriptEnabled = false
                wv.clearHistory()
                (wv.parent as? ViewGroup)?.removeView(wv)
                wv.destroy()
            }
            holder.value = null
        }
    }
}
