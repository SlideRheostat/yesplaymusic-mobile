package com.litemusic

import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * 网易云 eapi 加密（对照 NeteaseCloudMusicApi util/crypto.js 的 eapi 实现）
 * 已在 Node 环境对真实接口验证通过。
 */
object Eapi {
    private const val KEY = "e82ckenh8dichen8"

    /** 加密请求：返回 params 十六进制串 */
    fun encrypt(url: String, text: String): String {
        val message = "nobody${url}use${text}md5forencrypt"
        val digest = md5Hex(message)
        val data = "$url-36cd479b6b5-$text-36cd479b6b5-$digest"
        return aesEcb(data, Cipher.ENCRYPT_MODE).uppercase()
    }

    /** 解密响应（e_r 为 true 时服务器返回密文） */
    fun decrypt(hexCipher: String): String {
        val bytes = hexToBytes(hexCipher)
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(KEY.toByteArray(), "AES"))
        return String(cipher.doFinal(bytes))
    }

    private fun aesEcb(text: String, mode: Int): String {
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(mode, SecretKeySpec(KEY.toByteArray(), "AES"))
        val bytes = cipher.doFinal(text.toByteArray())
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) sb.append(String.format("%02x", b))
        return sb.toString()
    }

    private fun hexToBytes(hex: String): ByteArray {
        val out = ByteArray(hex.length / 2)
        for (i in out.indices) {
            out[i] = hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return out
    }

    fun md5Hex(s: String): String {
        val d = MessageDigest.getInstance("MD5").digest(s.toByteArray())
        val sb = StringBuilder()
        for (b in d) sb.append(String.format("%02x", b))
        return sb.toString()
    }
}
