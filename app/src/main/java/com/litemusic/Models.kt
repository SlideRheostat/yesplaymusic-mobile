package com.litemusic

import org.json.JSONArray
import org.json.JSONObject

data class Song(
    val id: Long,
    val name: String,
    val artists: String,
    val artistId: Long,       // 首位歌手 id（歌手主页入口）
    val artistList: List<Pair<Long, String>> = emptyList(),  // 全部歌手 (id, 名称)
    val album: String,
    val albumId: Long,
    val duration: Long,      // 毫秒
    val cover: String?,
    val fee: Int,            // 0免费 1VIP 2付费 3低音质免费
    val offline: Boolean,    // 已下架
) {
    val needVip: Boolean get() = fee == 1 || fee == 4
    val playable: Boolean get() = !offline
}

data class Playlist(
    val id: Long,
    val name: String,
    val cover: String?,
    val playCount: Long,
    val trackCount: Int,
    val subtitle: String? = null,
    var subscribed: Boolean = false,
    val albumCount: Int = 0,
)

data class LyricLine(val time: Long, val text: String, val trans: String? = null, val roma: String? = null)

object Parser {
    fun song(o: JSONObject): Song {
        val ar = o.optJSONArray("ar") ?: o.optJSONArray("artists")
        var arNames = ""
        var arId = 0L
        val arList = ArrayList<Pair<Long, String>>()
        if (ar != null) {
            for (i in 0 until ar.length()) {
                if (i > 0) arNames += "/"
                val a = ar.optJSONObject(i)
                val aName = a?.optString("name") ?: ""
                val aId = a?.optLong("id") ?: 0L
                arNames += aName
                if (i == 0) arId = aId
                if (aId > 0 && aName.isNotEmpty()) arList.add(aId to aName)
            }
        }
        val al = o.optJSONObject("al") ?: o.optJSONObject("album")
        val priv = o.optJSONObject("privilege")
        val st = priv?.optInt("st", 0) ?: o.optInt("st", 0)
        return Song(
            id = o.optLong("id"),
            name = o.optString("name"),
            artists = arNames,
            artistId = arId,
            artistList = arList,
            album = al?.optString("name") ?: "",
            albumId = al?.optLong("id") ?: 0,
            duration = if (o.has("dt")) o.optLong("dt") else o.optLong("duration"),
            cover = al?.optString("picUrl")?.takeIf { it.isNotEmpty() },
            fee = priv?.optInt("fee", -1)?.takeIf { it >= 0 } ?: o.optInt("fee", 0),
            offline = st == -200 || st == -101,
        )
    }

    fun songs(arr: JSONArray?): List<Song> {
        if (arr == null) return emptyList()
        val out = ArrayList<Song>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val s = song(o)
            if (s.id > 0) out.add(s)
        }
        return out
    }

    fun playlist(o: JSONObject): Playlist {
        return Playlist(
            id = o.optLong("id"),
            name = o.optString("name"),
            cover = o.optString("picUrl").takeIf { it.isNotEmpty() }
                ?: o.optString("coverImgUrl").takeIf { it.isNotEmpty() },
            playCount = o.optLong("playCount"),
            trackCount = o.optInt("trackCount", o.optInt("trackNumber", 0)),
            subscribed = o.optBoolean("subscribed", o.optBoolean("sub", false)),
        )
    }

    fun playlists(arr: JSONArray?): List<Playlist> {
        if (arr == null) return emptyList()
        val out = ArrayList<Playlist>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val p = playlist(o)
            if (p.id > 0 && p.name.isNotEmpty()) out.add(p)
        }
        return out
    }
}
