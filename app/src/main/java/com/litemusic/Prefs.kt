package com.litemusic

import android.content.Context
import android.content.SharedPreferences
import java.util.UUID

/** 轻量偏好存储：登录态、设备标识、用户资料 */
object Prefs {
    private lateinit var sp: SharedPreferences

    fun init(ctx: Context) {
        sp = ctx.getSharedPreferences("lite", Context.MODE_PRIVATE)
        if (deviceId.isEmpty()) {
            deviceId = randomHex()
        }
        if (musicA.isEmpty()) {
            musicA = randomHex()
        }
        // web 会话访客标识需保持稳定：每次请求随机生成会被服务端视为新会话，
        // 更容易触发风控与"登录状态已过期"
        if (ntesNuid.isEmpty()) {
            ntesNuid = randomHex()
            ntesNnid = "$ntesNuid,${System.currentTimeMillis()}"
        }
        if (nmtid.isEmpty()) {
            nmtid = randomHex()
        }
        if (wnmcid.isEmpty()) {
            wnmcid = newWnmcid()
        }
    }

    private fun randomHex(): String = UUID.randomUUID().toString().replace("-", "")

    var deviceId: String
        get() = sp.getString("deviceId", "") ?: ""
        set(v) { sp.edit().putString("deviceId", v).apply() }

    var musicA: String
        get() = sp.getString("musicA", "") ?: ""
        set(v) { sp.edit().putString("musicA", v).apply() }

    private var ntesNuid: String
        get() = sp.getString("ntesNuid", "") ?: ""
        set(v) { sp.edit().putString("ntesNuid", v).apply() }

    private var ntesNnid: String
        get() = sp.getString("ntesNnid", "") ?: ""
        set(v) { sp.edit().putString("ntesNnid", v).apply() }

    private var nmtid: String
        get() = sp.getString("nmtid", "") ?: ""
        set(v) { sp.edit().putString("nmtid", v).apply() }

    private var wnmcid: String
        get() = sp.getString("wnmcid", "") ?: ""
        set(v) { sp.edit().putString("wnmcid", v).apply() }

    var musicU: String
        get() = sp.getString("musicU", "") ?: ""
        set(v) { sp.edit().putString("musicU", v).apply() }

    var csrf: String
        get() = sp.getString("csrf", "") ?: ""
        set(v) { sp.edit().putString("csrf", v).apply() }

    var userId: Long
        get() = sp.getLong("userId", 0)
        set(v) { sp.edit().putLong("userId", v).apply() }

    var nickname: String
        get() = sp.getString("nickname", "") ?: ""
        set(v) { sp.edit().putString("nickname", v).apply() }

    var avatar: String
        get() = sp.getString("avatar", "") ?: ""
        set(v) { sp.edit().putString("avatar", v).apply() }

    var vipType: Int
        get() = sp.getInt("vipType", 0)
        set(v) { sp.edit().putInt("vipType", v).apply() }

    /** 音质：standard=128k exhigh=320k lossless=无损（服务端仅支持这三档；旧值 higher 归一化为 standard） */
    var level: String
        get() {
            val v = sp.getString("level", "standard") ?: "standard"
            return if (v == "higher") "standard" else v
        }
        set(v) { sp.edit().putString("level", v).apply() }

    /** 缓存体积上限（MB），按体积淘汰缓存歌曲 */
    var cacheMB: Int
        get() = sp.getInt("cacheMB", 256)
        set(v) { sp.edit().putInt("cacheMB", v).apply() }

    /** 歌词是否显示翻译 */
    var showTrans: Boolean
        get() = sp.getBoolean("showTrans", true)
        set(v) { sp.edit().putBoolean("showTrans", v).apply() }

    /** 歌词是否显示平假名注音（日语歌曲） */
    var showRoma: Boolean
        get() = sp.getBoolean("showRoma", true)
        set(v) { sp.edit().putBoolean("showRoma", v).apply() }

    /** 歌词字号（sp） */
    var lyricSize: Int
        get() = sp.getInt("lyricSize", 15)
        set(v) { sp.edit().putInt("lyricSize", v).apply() }

    /** 播放模式记忆：0顺序 1单曲 2随机 */
    var playMode: Int
        get() = sp.getInt("playMode", 0)
        set(v) { sp.edit().putInt("playMode", v).apply() }

    /** 外观模式：0=跟随系统 1=浅色 2=深色 */
    var themeMode: Int
        get() = sp.getInt("themeMode", 0)
        set(v) { sp.edit().putInt("themeMode", v).apply() }

    val loggedIn: Boolean get() = musicU.isNotEmpty()

    fun clearLogin() {
        sp.edit().remove("musicU").remove("csrf").remove("userId")
            .remove("nickname").remove("avatar").remove("vipType")
            .remove("extraCookies").apply()
    }

    // ---- weapi 所需完整 cookie（官方做法：全量保存、全量回传） ----

    private var extraCookies: String
        get() = sp.getString("extraCookies", "") ?: ""
        set(v) { sp.edit().putString("extraCookies", v).apply() }

    fun saveCookie(k: String, v: String) {
        if (v.isEmpty() || v == "null") return
        when (k) {
            "MUSIC_U" -> musicU = v
            "__csrf" -> csrf = v
            "__NMTID_0" -> putExtra("NMTID", v)
            // 官方做法：登录/响应下发的全部 cookie 都持久化（result.cookie.join(';')），
            // 只挑几个键会丢失服务器轮换的会话 cookie，导致频繁"登录状态已过期"
            else -> putExtra(k, v)
        }
    }

    private fun putExtra(k: String, v: String) {
        val obj = try { org.json.JSONObject(extraCookies) } catch (e: Exception) { org.json.JSONObject() }
        obj.put(k, v)
        extraCookies = obj.toString()
    }

    /** weapi 请求的 Cookie 头：MUSIC_U + __csrf + 官方 web 会话必备字段（缺省会返回空响应） */
    fun weapiCookieHeader(): String {
        val map = LinkedHashMap<String, String>()
        if (musicU.isNotEmpty()) map["MUSIC_U"] = musicU
        if (csrf.isNotEmpty()) map["__csrf"] = csrf
        // 对照 @neteaseapireborn/api util/request.js processCookieObject 的必备字段
        map["__remember_me"] = "true"
        map["ntes_kaola_ad"] = "1"
        map["_ntes_nuid"] = ntesNuid
        map["_ntes_nnid"] = ntesNnid
        map["WNMCID"] = wnmcid
        map["WEVNSM"] = "1.0.0"
        map["osver"] = "Microsoft-Windows-10-Professional-build-19045-64bit"
        map["deviceId"] = deviceId
        map["os"] = "pc"
        map["channel"] = "netease"
        map["appver"] = "3.1.17.204416"
        map["NMTID"] = nmtid
        // 登录/响应下发的其余 cookie 一并回传（不覆盖上面已计算的必备字段）
        try {
            val obj = org.json.JSONObject(extraCookies)
            for (k in obj.keys()) {
                if (!map.containsKey(k)) map[k] = obj.getString(k)
            }
        } catch (_: Exception) {}
        return map.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    private fun newWnmcid(): String {
        val letters = "abcdefghijklmnopqrstuvwxyz"
        val rnd = java.util.Random()
        val sb = StringBuilder(6)
        repeat(6) { sb.append(letters[rnd.nextInt(letters.length)]) }
        return "$sb.${System.currentTimeMillis()}.01.0"
    }
}
