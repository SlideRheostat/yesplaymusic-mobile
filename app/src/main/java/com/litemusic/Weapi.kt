package com.litemusic

import android.util.Base64
import java.security.KeyFactory
import java.security.SecureRandom
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 网易云 weapi 加密（对照 NeteaseCloudMusicApi util/crypto.js 的 weapi 实现）。
 * 账号敏感接口（收藏/喜欢列表/用户歌单/账号信息）走 weapi 可避开 eapi 风控。
 */
object Weapi {
    private const val IV = "0102030405060708"
    private const val PRESET = "0CoJUm6Qyw8W8jud"
    private const val B62 = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
    private const val PUB_B64 =
        "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQDgtQn2JZ34ZC28NWYpAUd98iZ37BUrX/aKzmFbt7clFSs6sXqHauqKWqdtLkF2KexO40H1YTX8z2lSgBBOAxLsvaklV8k4cBFK9snQXE9/DDaFt6Rr7iVZMldczhC0JNgTz+SHXT6CBHuX3e9SdB1Ua44oncaTWz7OBGLbCiK45wIDAQAB"

    /** 返回 params 与 encSecKey */
    fun encrypt(text: String): Pair<String, String> {
        val rnd = SecureRandom()
        val sb = StringBuilder(16)
        repeat(16) { sb.append(B62[rnd.nextInt(62)]) }
        val secret = sb.toString()
        val params = aesCbc(aesCbc(text, PRESET), secret)
        val encSecKey = rsa(secret.reversed())
        return params to encSecKey
    }

    private fun aesCbc(text: String, key: String): String {
        val c = Cipher.getInstance("AES/CBC/PKCS5Padding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key.toByteArray(), "AES"), IvParameterSpec(IV.toByteArray()))
        return Base64.encodeToString(c.doFinal(text.toByteArray()), Base64.NO_WRAP)
    }

    private fun rsa(text: String): String {
        val der = Base64.decode(PUB_B64, Base64.NO_WRAP)
        val pub = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(der))
        val c = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        c.init(Cipher.ENCRYPT_MODE, pub)
        val bytes = c.doFinal(text.toByteArray())
        val s = StringBuilder(bytes.size * 2)
        for (b in bytes) s.append(String.format("%02x", b))
        return s.toString()
    }
}
