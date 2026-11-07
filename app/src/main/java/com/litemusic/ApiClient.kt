package com.litemusic

import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.util.concurrent.Executors
import javax.net.ssl.HttpsURLConnection

private const val TAG = "LiteMusic-API"

class ApiException(val code: Int, message: String) : Exception(message)

/**
 * eapi 请求封装（对照 NeteaseCloudMusicApi 4.32 util/request.js 的 eapi 分支，
 * 已在 Node 环境对真实接口验证通过）。
 */
object ApiClient {
    private const val BASE = "https://interface3.music.163.com/eapi/"
    private const val WEAPI_BASE = "https://music.163.com/weapi/"
    private const val DOMAIN = "https://music.163.com"
    private const val LINUXAPI_BASE = "https://music.163.com/api/linux/forward"
    private const val LINUX_KEY = "rFgB&h#%2?^eDg:Q"
    private const val LINUX_UA =
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/60.0.3112.90 Safari/537.36"
    private const val UA = "NeteaseMusic 9.0.90/5038 (iPhone; iOS 16.2; zh_CN)"
    // 官方 playlist_subscribe 模块收藏时强制携带的反作弊 token（NeteaseCloudMusicApi config.json）
    private const val CHECK_TOKEN =
        "9ca17ae2e6ffcda170e2e6ee8af14fbabdb988f225b3868eb2c15a879b9a83d274a790ac8ff54a97b889d5d42af0feaec3b92af58cff99c470a7eafd88f75e839a9ea7c14e909da883e83fb692a3abdb6b92adee9e"
    private const val WEAPI_UA =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36 Edg/124.0.0.0"

    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    fun get(path: String, data: JSONObject, antiCheat: Boolean = false, cb: (Result<JSONObject>) -> Unit) {
        executor.execute {
            val result = try {
                Result.success(doRequest(path, data, if (antiCheat) CHECK_TOKEN else null))
            } catch (e: Throwable) {
                Result.failure(e)
            }
            main.post { cb(result) }
        }
    }

    /**
     * linuxapi 通道（官方 Linux 桌面端 /api/linux/forward）：weapi 被风控返回空响应时使用，
     * 风控最宽松，适合关注/收藏等敏感写操作。
     */
    fun getLinuxApi(path: String, data: JSONObject, cb: (Result<JSONObject>) -> Unit) {
        executor.execute {
            val result = try {
                Result.success(doRequestLinux(path, data))
            } catch (e: Throwable) {
                Log.w(TAG, "linuxapi failed path=$path code=${(e as? ApiException)?.code} msg=${e.message}")
                Result.failure(e)
            }
            main.post { cb(result) }
        }
    }

    private fun doRequestLinux(path: String, data: JSONObject): JSONObject {
        val payload = JSONObject()
            .put("method", "POST")
            .put("url", "$DOMAIN/api/$path")
            .put("params", data)
        val eparams = aesEcbHex(payload.toString(), LINUX_KEY)
        val body = "eparams=" + URLEncoder.encode(eparams, "UTF-8")

        val conn = (java.net.URL(LINUXAPI_BASE).openConnection() as HttpsURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 20_000
            doOutput = true
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            setRequestProperty("User-Agent", LINUX_UA)
            setRequestProperty("Referer", "$DOMAIN/")
            val cookie = Prefs.weapiCookieHeader()
            if (cookie.isNotEmpty()) setRequestProperty("Cookie", cookie)
        }

        try {
            conn.outputStream.use { it.write(body.toByteArray()) }
            val code = conn.responseCode
            val stream = if (code >= 400) conn.errorStream else conn.inputStream
            val bytes = stream?.use { it.readBytes() } ?: ByteArray(0)
            val text = String(bytes, Charsets.UTF_8)
            if (text.isEmpty()) throw ApiException(-3, "空响应")
            return checkCode(JSONObject(text))
        } finally {
            conn.disconnect()
        }
    }

    private fun aesEcbHex(text: String, key: String): String {
        val c = javax.crypto.Cipher.getInstance("AES/ECB/PKCS5Padding")
        c.init(
            javax.crypto.Cipher.ENCRYPT_MODE,
            javax.crypto.spec.SecretKeySpec(key.toByteArray(), "AES")
        )
        val out = c.doFinal(text.toByteArray())
        val sb = StringBuilder(out.size * 2)
        for (b in out) sb.append(String.format("%02X", b))
        return sb.toString()
    }

    /**
     * weapi 通道，现仅用于内部会话恢复（refreshCsrf）。
     *
     * 业务接口（含收藏 radio/like 等写操作）已全部改走 eapi（get）：
     * 实测部分网络环境下 weapi（music.163.com 域）会被风控返回空响应，
     * 而 eapi（interface3，iPhone 客户端通道）稳定可用。
     * weapi 失败要么走内置的会话恢复（doRequestWeapi 内部），要么直接把真实错误透出。
     */
    fun getWeapi(path: String, data: JSONObject, cb: (Result<JSONObject>) -> Unit) {
        executor.execute {
            val result = try {
                Result.success(doRequestWeapi(path, JSONObject(data.toString())))
            } catch (e: Throwable) {
                Log.w(TAG, "weapi failed path=$path code=${(e as? ApiException)?.code} msg=${e.message}")
                Result.failure(e)
            }
            main.post { cb(result) }
        }
    }

    // 会话恢复节流：避免失败时反复重试触发服务端 429"操作频繁"限流
    private var lastRecoveryAt = 0L

    private fun allowRecovery(): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastRecoveryAt < 15_000) return false
        lastRecoveryAt = now
        return true
    }

    private fun doRequestWeapi(path: String, data: JSONObject): JSONObject {
        // weapi 写操作要求 Cookie 中的 __csrf 与表单 csrf_token 一致；缺失时先补齐会话
        ensureCsrf()
        return try {
            postWeapi(path, data)
        } catch (e: ApiException) {
            // 可恢复的会话失效：空响应(-3)、登录状态过期(305/301)、风控(-2/含"风险"字样)
            val recoverable = e.code == -3 || e.code == 305 || e.code == 301 || e.code == -2 ||
                (e.message?.contains("登录状态已过期") == true) ||
                (e.message?.contains("风险") == true)
            if (recoverable && path != "w/nuser/account/get" && allowRecovery()) {
                if (Prefs.csrf.isEmpty()) seedSiteCookies()
                if (refreshCsrf()) postWeapi(path, data) else throw e
            } else {
                throw e
            }
        }
    }

    /**
     * 确保持有可用的 __csrf。
     * 本应用登录走 eapi（interface3），其下发的 __csrf 不参与 web 会话，因此登录后 csrf 一直为空；
     * weapi 写操作（如关注歌手）会因 csrf_token 与 Cookie 不一致被服务端直接返回空响应。
     * 这里携带当前 MUSIC_U 请求一次 music.163.com，让服务端下发与本会话匹配的 __csrf。
     */
    private fun ensureCsrf() {
        if (Prefs.csrf.isNotEmpty()) return
        seedSiteCookies()
        if (Prefs.csrf.isEmpty()) refreshCsrf()
    }

    /** GET 首页以获取 __csrf + NMTID/_ntes_nuid 等站点 cookie（携带 MUSIC_U 让 csrf 绑定当前会话） */
    private fun seedSiteCookies() {
        try {
            val conn = (java.net.URL("https://music.163.com/").openConnection() as HttpsURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10_000
                readTimeout = 10_000
                setRequestProperty("User-Agent", WEAPI_UA)
                setRequestProperty("Referer", "https://music.163.com/")
                val cookie = Prefs.weapiCookieHeader()
                if (cookie.isNotEmpty()) setRequestProperty("Cookie", cookie)
            }
            conn.inputStream.use { it.readBytes() }
            captureCookies(conn, acceptCsrf = true)
            conn.disconnect()
        } catch (e: Throwable) {
            Log.w(TAG, "seedSiteCookies failed: ${e.message}")
        }
    }

    /** 用账号接口重新触发 Set-Cookie，服务器自然轮换与 MUSIC_U 绑定的 __csrf */
    private fun refreshCsrf(): Boolean {
        val before = Prefs.csrf
        return try {
            postWeapi("w/nuser/account/get", JSONObject())
            Prefs.csrf.isNotEmpty()
        } catch (e: Throwable) {
            Log.w(TAG, "refreshCsrf failed: ${e.message}")
            Prefs.csrf.isNotEmpty() && Prefs.csrf != before
        }
    }

    private fun postWeapi(path: String, data: JSONObject): JSONObject {
        data.put("csrf_token", Prefs.csrf)
        data.put("e_r", false)
        val (params, encSecKey) = Weapi.encrypt(data.toString())
        val body = "params=" + URLEncoder.encode(params, "UTF-8") +
            "&encSecKey=" + URLEncoder.encode(encSecKey, "UTF-8")

        val conn = (java.net.URL(WEAPI_BASE + path).openConnection() as HttpsURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 20_000
            doOutput = true
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            setRequestProperty("User-Agent", WEAPI_UA)
            setRequestProperty("Referer", "https://music.163.com/")
            val cookie = Prefs.weapiCookieHeader()
            if (cookie.isNotEmpty()) setRequestProperty("Cookie", cookie)
        }

        try {
            conn.outputStream.use { it.write(body.toByteArray()) }
            val code = conn.responseCode
            val stream = if (code >= 400) conn.errorStream else conn.inputStream
            val bytes = stream?.use { it.readBytes() } ?: ByteArray(0)
            captureCookies(conn, acceptCsrf = true)
            val text = String(bytes, Charsets.UTF_8)
            if (text.isEmpty()) throw ApiException(-3, "空响应")
            return checkCode(JSONObject(text))
        } finally {
            conn.disconnect()
        }
    }

    private fun checkCode(json: JSONObject): JSONObject {
        val code = json.optInt("code", 200)
        if (code != 200 && code != 201 && code != 800 && code != 801 && code != 802 && code != 803 && code != 302) {
            throw ApiException(code, json.optString("message").ifEmpty { json.optString("msg") }.ifEmpty { "接口错误($code)" })
        }
        return json
    }

    private fun doRequest(path: String, data: JSONObject, antiCheatToken: String? = null): JSONObject {
        val header = JSONObject()
        header.put("osver", "16.2")
        header.put("deviceId", Prefs.deviceId)
        header.put("os", "iphone")
        header.put("appver", "9.0.90")
        header.put("versioncode", "140")
        header.put("mobilename", "iPhone 13 Pro")
        header.put("buildver", (System.currentTimeMillis() / 1000).toString())
        header.put("resolution", "1080x1920")
        header.put("__csrf", Prefs.csrf)
        header.put("channel", "distribution")
        header.put("requestId", "${System.currentTimeMillis()}_${(Math.random() * 1000).toInt()}")
        if (!antiCheatToken.isNullOrEmpty()) header.put("X-antiCheatToken", antiCheatToken)
        if (Prefs.loggedIn) {
            header.put("MUSIC_U", Prefs.musicU)
        } else {
            header.put("MUSIC_A", Prefs.musicA)
        }

        data.put("header", header)
        data.put("e_r", false)

        val uri = "/api/$path"
        val enc = Eapi.encrypt(uri, data.toString())
        val body = "params=" + URLEncoder.encode(enc, "UTF-8")

        val conn = (java.net.URL(BASE + path).openConnection() as HttpsURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 20_000
            doOutput = true
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            setRequestProperty("User-Agent", UA)
            setRequestProperty("Referer", "https://music.163.com/")
            val cookieStr = header.keys().asSequence()
                .joinToString("; ") { k ->
                    java.net.URLEncoder.encode(k, "UTF-8") + "=" +
                        java.net.URLEncoder.encode(header.getString(k), "UTF-8")
                }
            setRequestProperty("Cookie", cookieStr)
        }

        try {
            conn.outputStream.use { it.write(body.toByteArray()) }
            val code = conn.responseCode
            val stream = if (code >= 400) conn.errorStream else conn.inputStream
            val bytes = stream?.use { it.readBytes() } ?: ByteArray(0)
            // eapi(interface3) 域下发的 __csrf 与 web 会话不绑定，只认 music.163.com 的
            captureCookies(conn, acceptCsrf = false)
            var text = String(bytes, Charsets.UTF_8)
            if (text.isEmpty()) throw ApiException(-3, "空响应")
            // 若服务器仍返回密文（非 '{' 开头），尝试解密
            if (!text.trimStart().startsWith("{")) {
                try {
                    text = Eapi.decrypt(bytesToHex(bytes).uppercase())
                } catch (e: Exception) {
                    throw ApiException(-4, "响应解析失败")
                }
            }
            return checkCode(JSONObject(text))
        } finally {
            conn.disconnect()
        }
    }

    private fun captureCookies(conn: HttpURLConnection, acceptCsrf: Boolean) {
        val setCookies = try {
            val fields = conn.headerFields ?: return
            // 头名称大小写在不同机型/服务端可能不同，忽略大小写查找
            val key = fields.keys.firstOrNull { it != null && it.equals("Set-Cookie", true) } ?: return
            fields[key] ?: return
        } catch (_: Throwable) {
            return  // cookie 捕获失败不影响请求本身
        }
        for (c in setCookies) {
            val pair = c.substringBefore(';').trim()
            if (pair.isEmpty() || !pair.contains('=')) continue
            val k = pair.substringBefore('=').trim()
            val v = pair.substringAfter('=').trim()
            if (k == "__csrf" && !acceptCsrf) continue
            Prefs.saveCookie(k, v)
        }
    }

    private fun bytesToHex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) sb.append(String.format("%02x", b))
        return sb.toString()
    }
}
