package com.shangyin.app

import com.shangyin.app.data.komiic.KomiicClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [rewriteImageRequest] 回归测试（TDD 试点，v0.222 轮引入）。
 *
 * 核心回归点（v0.221 教训）：吃瓜图床 Referer 必须来自「调用方传入的最近成功镜像」，
 * 而不是持久化存储的旧镜像地址——旧 bug 的语义错位就在这里。
 *
 * 期望值全部用独立字面量或跨组件共享常量（KomiicClient.CHROME_UA 是客户端 API 请求与
 * 图片请求共用同一 UA 的契约），不复算实现逻辑，防止同义反复。
 * KomiicClient.CHROME_UA 是 const（编译期内联），访问不触发任何 Android 依赖。
 */
class ImageRequestRewriteTest {

    private fun get(url: String) = Request.Builder().url(url).build()

    // ---- 吃瓜图床分支（v0.221 回归核心）----

    @Test
    fun melon_image_referer_uses_caller_provided_recent_base() {
        val req = get("https://pic.jtzatly.cn/img/2026/10/x.jpg")
        val out = rewriteImageRequest(req, melonRefererBase = "https://branch.upsqllhj.cc")
        assertEquals("https://branch.upsqllhj.cc/", out.header("Referer"))
        assertEquals(
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/120.0.0.0 Mobile Safari/537.36",
            out.header("User-Agent")
        )
    }

    @Test
    fun melon_referer_base_trailing_slash_is_normalized() {
        val req = get("https://pic.ndhixj.cn/a.jpg")
        val out = rewriteImageRequest(req, melonRefererBase = "https://abuse.upsqllhj.cc/")
        assertEquals("https://abuse.upsqllhj.cc/", out.header("Referer"))
    }

    @Test
    fun non_pic_host_gets_no_melon_headers() {
        // pic.example.com 是多级域名，不匹配 pic.{词}.cn 模式，必须原样透传
        val req = get("https://pic.example.com/a.jpg")
        val out = rewriteImageRequest(req, melonRefererBase = "https://branch.upsqllhj.cc")
        assertNull(out.header("Referer"))
        assertNull(out.header("User-Agent"))
    }

    // ---- 豆瓣防盗链分支 ----

    @Test
    fun douban_image_gets_mobile_referer_and_iphone_ua() {
        val req = get("https://img1.doubanio.com/view/photo/m/public/p1.webp")
        val out = rewriteImageRequest(req)
        assertEquals("https://m.douban.com/", out.header("Referer"))
        assertEquals(
            "Mozilla/5.0 (iPhone; CPU iPhone OS 16_0 like Mac OS X) " +
                "AppleWebKit/605.1.15 (KHTML, like Gecko) " +
                "Version/16.0 Mobile/15E148 Safari/604.1",
            out.header("User-Agent")
        )
    }

    @Test
    fun douban_main_site_also_matched() {
        val req = get("https://www.douban.com/pic/a.jpg")
        val out = rewriteImageRequest(req)
        assertEquals("https://m.douban.com/", out.header("Referer"))
    }

    // ---- Komiic 章节图防盗链分支 ----

    @Test
    fun komiic_chapter_image_builds_referer_from_fragment() {
        val req = get("https://komiic.com/api/image/1234#c/9001/77")
        val out = rewriteImageRequest(req)
        assertEquals("https://komiic.com/comic/9001/chapter/77", out.header("Referer"))
        assertEquals(KomiicClient.CHROME_UA, out.header("User-Agent"))
    }

    @Test
    fun komiic_mirror_domain_also_matched() {
        val req = get("https://komiic.cc/api/image/5678#c/42/7")
        val out = rewriteImageRequest(req)
        assertEquals("https://komiic.cc/comic/42/chapter/7", out.header("Referer"))
    }

    @Test
    fun komiic_api_without_image_path_passes_through() {
        val req = get("https://komiic.com/api/query")
        val out = rewriteImageRequest(req)
        assertNull(out.header("Referer"))
        assertNull(out.header("User-Agent"))
    }

    // ---- 无关请求透传 ----

    @Test
    fun unrelated_request_passes_through_unchanged() {
        val req = get("https://example.com/a.jpg")
        val out = rewriteImageRequest(req)
        assertEquals(req.url, out.url)
        assertNull(out.header("Referer"))
        assertNull(out.header("User-Agent"))
    }
}
