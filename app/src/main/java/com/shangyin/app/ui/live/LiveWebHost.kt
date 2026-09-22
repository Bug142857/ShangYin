package com.shangyin.app.ui.live

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
import com.shangyin.app.data.live.LiveWeb

/**
 * 直播用的常驻隐藏 WebView（挂在 AppNav 根节点，全 App 生命周期存活）。
 *
 * 1dp + alpha=0：不可见、不占位、不拦触摸，但页面照常加载与执行 JS。
 * 用途见 LiveWeb 注释：斗鱼的播放地址只有页面自己的 JS 能拿到（接口要 enc_data 签名），
 * 这里让页面自己取，我们被动接住；常驻可以减少每轮播放的页面加载等待。
 */
@Composable
fun LiveWebHost() {
    val holder = remember { mutableStateOf<WebView?>(null) }
    AndroidView(
        factory = { ctx ->
            WebView(ctx).apply {
                holder.value = this
                LiveWeb.attach(this)
            }
        },
        modifier = Modifier.size(1.dp).alpha(0f)
    )
    DisposableEffect(Unit) {
        onDispose {
            holder.value?.let { wv ->
                LiveWeb.detach(wv)
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
