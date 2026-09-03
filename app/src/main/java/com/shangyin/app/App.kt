package com.shangyin.app

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.shangyin.app.data.Repo
import com.shangyin.app.ui.settings.SettingsStore
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class App : Application(), ImageLoaderFactory {
    override fun onCreate() {
        super.onCreate()
        Repo.init(this)
        SettingsStore.init(this)
    }

    /**
     * 自定义 Coil 图片加载器：为 doubanio.com 域名图片添加 Referer 头，
     * 规避豆瓣图片防盗链导致 418 / 403。
     */
    override fun newImageLoader(): ImageLoader {
        val client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val req = chain.request()
                val host = req.url.host
                val newReq = if (host.endsWith("doubanio.com") || host.endsWith("douban.com")) {
                    req.newBuilder()
                        .header("Referer", "https://m.douban.com/")
                        .header(
                            "User-Agent",
                            "Mozilla/5.0 (iPhone; CPU iPhone OS 16_0 like Mac OS X) " +
                                "AppleWebKit/605.1.15 (KHTML, like Gecko) " +
                                "Version/16.0 Mobile/15E148 Safari/604.1"
                        )
                        .build()
                } else req
                chain.proceed(newReq)
            }
            .build()
        return ImageLoader.Builder(this)
            .okHttpClient(client)
            .crossfade(true)
            .build()
    }
}
