package com.shangyin.app

import com.shangyin.app.data.melon.MelonImageCipher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 吃瓜图床 AES 解密回归测试（v0.244，TDD）。
 *
 * 根因实证（0.244 会话）：okhttp 拿到的字节一直是**真 AES-128-CBC 密文**（16B 对齐、
 * PC/设备逐字节一致），0.242 逆向 zzz.js 时把 KEY/IV **装反**——decryptImage 实际
 * KEY=Utf8.parse(序列2)= "f5d965df75336270"、IV=Utf8.parse(序列1)= "97b60394abc2fbe1"
 * （混淆字符串数组经轮换，静态出现顺序不可信）。装反后 JVM BadPadding → 被误判为
 * 「指纹垃圾」→ 七轮 WebView 盲修。
 *
 * fixture = 设备黑匣子同款真实密文（pic.jtzatly.cn 封面，108736B，PC curl 逐字节复现），
 * 明文为合法 JPEG（FFD8FFDB 开头、FFD9 结尾、108733B）。
 */
class MelonImageCipherTest {

    private fun cipher(): ByteArray =
        javaClass.getResourceAsStream("/melon_cover1.cipher")!!.readBytes()

    private fun looksJpeg(b: ByteArray) = b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte()

    @Test
    fun `real cdn cipher decrypts with site's true key-iv orientation`() {
        val plain = MelonImageCipher.decrypt(cipher(), "f5d965df75336270", "97b60394abc2fbe1")
        assertNotNull("正确指派（KEY=f5d9… IV=97b6…）应解密成功", plain)
        plain!!
        assertEquals(108733, plain.size)
        assertEquals(true, looksJpeg(plain))
        assertEquals(0xD9, plain.last().toInt() and 0xFF)
    }

    @Test
    fun `reversed orientation must fail (guard against swapping back)`() {
        val plain = MelonImageCipher.decrypt(cipher(), "97b60394abc2fbe1", "f5d965df75336270")
        assertNull("KEY/IV 装反必须失败（0.242 的 bug 形态）", plain)
    }

    @Test
    fun `decryptAuto succeeds with hardcoded defaults`() {
        val plain = MelonImageCipher.decryptAuto(cipher())
        assertNotNull("默认（含双向尝试）解密应命中", plain)
        plain!!
        assertEquals(true, looksJpeg(plain))
    }

    @Test
    fun `decryptAuto survives swapped dynamic key pair (zzz js rotation guard)`() {
        // fetchKeyIv 按文件序取前两条（97b6…, f5d9…）——指派顺序随混淆轮换可能翻转，
        // decryptAuto 只需拿到 (k1, k2) 文件序，正反指派内部自动尝试
        val plain = MelonImageCipher.decryptAuto(cipher(), "97b60394abc2fbe1", "f5d965df75336270")
        assertNotNull("反向指派的动态密钥对也应命中", plain)
        plain!!
        assertEquals(true, looksJpeg(plain))
    }

    @Test
    fun `garbage input returns null`() {
        assertNull(MelonImageCipher.decrypt(ByteArray(64) { it.toByte() }))
    }
}
