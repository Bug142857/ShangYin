package com.shangyin.app

import com.shangyin.app.data.download.VideoDownloadManager
import com.shangyin.app.data.download.parseHlsPlaylist
import com.shangyin.app.data.komiic.KomiicClient
import com.shangyin.app.data.melon.adoptedBase
import com.shangyin.app.data.melon.demoted
import com.shangyin.app.data.melon.extractMelonPicHosts
import com.shangyin.app.data.melon.mergePicHosts
import okhttp3.Request
import org.junit.Assert.assertArrayEquals
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

    // ---- 吃瓜图床 fallback 域名替换（v0.222：设备直连图床不可达 → 换域名重试，路径通用）----

    @Test
    fun swap_pic_host_keeps_path_and_query() {
        val req = get("https://pic.jtzatly.cn/upload_01/x/2026.jpeg?v=2")
        val out = swapMelonPicHost(req, "pic.ndhixj.cn")
        assertEquals("pic.ndhixj.cn", out!!.url.host)
        assertEquals("/upload_01/x/2026.jpeg", out.url.encodedPath)
        assertEquals("2", out.url.queryParameter("v"))
    }

    @Test
    fun swap_pic_host_rejects_non_pic_host() {
        // pic.example.com 不匹配 pic.{词}.cn 模式，绝不能被换域（防误伤其它站点图片）
        val req = get("https://pic.example.com/a.jpg")
        assertNull(swapMelonPicHost(req, "pic.ndhixj.cn"))
    }

    @Test
    fun extract_pic_hosts_from_html_dedup() {
        // 站点实测（2026-10-15）图床 URL 均为完整 https://（loadBannerDirect / data-* 属性）
        val html = """
            <script>loadBannerDirect('https://pic.jtzatly.cn/a.jpeg', 1)</script>
            <img data-x="https://pic.jtzatly.cn/b.png" src="https://pic.ndhixj.cn/c.gif">
            <p>https://pic.jtzatly.cn/a.jpeg</p>
        """.trimIndent()
        assertEquals(listOf("pic.jtzatly.cn", "pic.ndhixj.cn"), extractMelonPicHosts(html))
    }

    @Test
    fun extract_pic_hosts_ignores_non_pic_and_plain_words() {
        val html = "https://cdn.example.com/a.jpg pic.fake.cn img.picabc.com https://pic.abc.cn:8080/x.jpg"
        // pic.fake.cn 无协议不算；img.picabc.com 非 pic.{词}.cn 形态；pic.abc.cn 带端口路径也提取 host
        assertEquals(listOf("pic.abc.cn"), extractMelonPicHosts(html))
    }

    @Test
    fun merge_pic_hosts_puts_new_found_first_and_dedups() {
        val merged = mergePicHosts(
            existing = listOf("pic.ndhixj.cn", "pic.old.cn"),
            found = listOf("pic.jtzatly.cn", "pic.ndhixj.cn")
        )
        assertEquals(listOf("pic.jtzatly.cn", "pic.ndhixj.cn", "pic.old.cn"), merged)
    }

    @Test
    fun merge_pic_hosts_capped_and_keeps_existing_when_nothing_found() {
        val merged = mergePicHosts(
            existing = listOf("pic.a.cn", "pic.b.cn", "pic.c.cn", "pic.d.cn", "pic.e.cn"),
            found = listOf("pic.f.cn")
        )
        // 上限 5：新发现的最前，末尾旧的被挤掉
        assertEquals(listOf("pic.f.cn", "pic.a.cn", "pic.b.cn", "pic.c.cn", "pic.d.cn"), merged)
        assertEquals(listOf("pic.a.cn"), mergePicHosts(listOf("pic.a.cn"), emptyList()))
    }

    // ---- 镜像 301 轮换采纳（v0.223：设备每请求吃 5.6s 301 跳转的根因修复）----

    @Test
    fun adoptedBase_redirect_adopted() {
        // 2026-10-16 实测：branch.upsqllhj.cc 301 → branch.ftnrqerm.cc（跳转 TTFB 5.6s）
        val b = adoptedBase(
            requestedUrl = "https://branch.upsqllhj.cc/page/2/",
            finalUrl = "https://branch.ftnrqerm.cc/page/2/",
            landingUrl = "https://www.ipqegzvg.cc/"
        )
        assertEquals("https://branch.ftnrqerm.cc", b)
    }

    @Test
    fun adoptedBase_landing_redirect_ignored() {
        // 着陆页跳转到哪都不采纳（着陆页不是镜像本体）
        assertNull(
            adoptedBase(
                requestedUrl = "https://www.ipqegzvg.cc/",
                finalUrl = "https://somewhere-else.cc/",
                landingUrl = "https://www.ipqegzvg.cc/"
            )
        )
    }

    @Test
    fun adoptedBase_no_redirect_returns_null() {
        assertNull(
            adoptedBase(
                requestedUrl = "https://branch.ftnrqerm.cc/page/2/",
                finalUrl = "https://branch.ftnrqerm.cc/page/2/",
                landingUrl = "https://www.ipqegzvg.cc/"
            )
        )
    }

    @Test
    fun adoptedBase_invalid_url_returns_null() {
        assertNull(adoptedBase("::::", "https://a.cc/", "https://b.cc/"))
        assertNull(adoptedBase("https://a.cc/", "::::", "https://b.cc/"))
    }

    // ---- 图床域名降级（v0.223：失败的图床域名移到池尾，避免每张图都从死域名开始）----

    @Test
    fun demoted_moves_host_to_tail() {
        assertEquals(listOf("b", "c", "a"), demoted(listOf("a", "b", "c"), "a"))
        // 不在池中的域名不新增（降级只重排池内已有项）
        assertEquals(listOf("a", "b", "c"), demoted(listOf("a", "b", "c"), "z"))
        assertEquals(emptyList<String>(), demoted(emptyList(), "a"))
    }

    // ---- HLS 解析（v0.225 视频下载：结构化清单 + AES-128 解密）----

    @Test
    fun parse_hls_playlist_segments_and_key() {
        // 结构来自 2026-10-16 实测的吃瓜视频清单（AES-128 + 显式 IV + 绝对切片地址）
        val m3u8 = """
            #EXTM3U
            #EXT-X-WJ-T:-0
            #EXT-X-VERSION:3
            #EXT-X-KEY:METHOD=AES-128,URI="https://mts.hhjd.mobi/videos5/x/crypt.key?auth_key=1",IV=0x84c4e341735f20781f6ebf73a7de1aad
            #EXT-X-MEDIA-SEQUENCE:0
            #EXT-X-TARGETDURATION:5
            #EXTINF:5.000,
            https://mts.hhjd.mobi/videos5/x/v0.ts?auth_key=a
            #EXTINF:5.000,
            https://mts.hhjd.mobi/videos5/x/v1.ts?auth_key=b
            #EXT-X-ENDLIST
        """.trimIndent()
        val p = parseHlsPlaylist(m3u8, "https://hls.tpbbwcc.cn/videos5/x/index.m3u8")
        assertEquals(2, p.segments.size)
        assertEquals("https://mts.hhjd.mobi/videos5/x/crypt.key?auth_key=1", p.key?.uri)
        assertEquals("0x84c4e341735f20781f6ebf73a7de1aad", p.key?.ivHex)
    }

    @Test
    fun parse_hls_playlist_without_key_has_null_key() {
        val p = parseHlsPlaylist("#EXTM3U\n#EXTINF:9.5,\nseg-1.ts", "https://a.cc/v.m3u8")
        assertNull(p.key)
        assertEquals(listOf("https://a.cc/seg-1.ts"), p.segments)
    }

    @Test
    fun parse_hls_playlist_relative_segments_resolve() {
        val p = parseHlsPlaylist(
            "#EXTM3U\n#EXTINF:9.0,\n./seg-2.ts\n#EXTINF:8.0,\nhttps://cdn.other.cc/seg-3.ts",
            "https://a.cc/hls/video/index.m3u8?sign=abc"
        )
        assertEquals(
            listOf("https://a.cc/hls/video/seg-2.ts", "https://cdn.other.cc/seg-3.ts"),
            p.segments
        )
    }

    @Test
    fun aes_decrypt_roundtrip() {
        val key = "0123456789abcdef".toByteArray() // 16B
        val ivHex = "0x84c4e341735f20781f6ebf73a7de1aad"
        // 加密与解密必须用同一 IV：由 ivHex 构造加密端 IV（模拟站点切片加密）
        val ivBytes = ByteArray(16).also { b ->
            val h = ivHex.removePrefix("0x")
            for (i in 0 until 16) b[i] = ((h[i * 2].digitToInt(16) shl 4) or h[i * 2 + 1].digitToInt(16)).toByte()
        }
        val plain = ByteArray(188 * 4) { (it % 251).toByte() } // 模拟 TS 切片（188 倍数）
        val c = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding")
        c.init(
            javax.crypto.Cipher.ENCRYPT_MODE,
            javax.crypto.spec.SecretKeySpec(key, "AES"),
            javax.crypto.spec.IvParameterSpec(ivBytes)
        )
        val encrypted = c.doFinal(plain)
        // 显式 IV 解密还原
        val decrypted = VideoDownloadManager.aesDecrypt(encrypted, key, ivHex, 0)
        assertArrayEquals(plain, decrypted)
    }

    @Test
    fun aes_decrypt_default_iv_uses_sequence() {
        // 无显式 IV：按 HLS 规范 IV = 64bit 媒体序号大端（sequence=3 → 高 8 字节最后一位 0x03）
        val key = "0123456789abcdef".toByteArray()
        val seqIv = ByteArray(16).also { it[15] = 3 }
        val c = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding")
        c.init(
            javax.crypto.Cipher.ENCRYPT_MODE,
            javax.crypto.spec.SecretKeySpec(key, "AES"),
            javax.crypto.spec.IvParameterSpec(seqIv)
        )
        val encrypted = c.doFinal(ByteArray(376) { 7 })
        val decrypted = VideoDownloadManager.aesDecrypt(encrypted, key, null, 3)
        assertArrayEquals(ByteArray(376) { 7 }, decrypted)
    }
}
