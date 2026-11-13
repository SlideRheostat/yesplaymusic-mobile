package com.litemusic

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * 音频磁盘缓存（LRU，上限由设置页"缓存体积上限(MB)"控制）。
 *
 * 修复异常流量核心：此前 MediaPlayer 直接流式播放网络 URL，切歌/循环/重播都会
 * 整首重新下载；播放失败时后台还会无限跳歌循环请求。现在每首歌只下载一次，
 * 命中缓存后完全零流量本地播放。
 *
 * 同时在音频文件旁保存同名的 .meta 元数据（歌名/歌手/专辑/封面等），
 * 供设置页"已缓存的歌"离线列表展示，无需联网即可还原歌曲信息。
 */
object AudioCache {
    private val executor = Executors.newSingleThreadExecutor()
    private var dir: File? = null

    /** 一个缓存音频文件；id 从文件名解析，level 为该文件缓存时的音质 */
    data class Entry(val file: File, val id: Long, val level: String)

    fun init(ctx: Context) {
        val d = File(ctx.cacheDir, "audio")
        d.mkdirs()
        // 清理上次未下完的临时文件，以及对应音频已不存在的孤儿元数据
        d.listFiles()?.forEach { f ->
            when {
                f.name.endsWith(".tmp") -> f.delete()
                f.name.endsWith(".meta") &&
                    !File(d, f.name.removeSuffix(".meta")).isFile -> f.delete()
            }
        }
        dir = d
    }

    /** 缓存键含音质等级，切换音质不会误用旧文件 */
    private fun fileFor(id: Long): File? = dir?.let { File(it, "$id-${Prefs.level}.aud") }

    /** 命中返回本地文件（并刷新 LRU 时间），未命中返回 null */
    fun cached(id: Long): File? =
        fileFor(id)?.takeIf { it.isFile && it.length() > 0 }
            ?.apply { setLastModified(System.currentTimeMillis()) }

    /** 任意音质等级的本地缓存（离线播放缓存列表时使用，不再重新下载） */
    fun cachedAny(id: Long): File? =
        entries().firstOrNull { it.id == id }?.file
            ?.apply { setLastModified(System.currentTimeMillis()) }

    /** 全部缓存文件，按最近使用时间倒序 */
    fun entries(): List<Entry> {
        val d = dir ?: return emptyList()
        return d.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".aud") && it.length() > 0 }
            ?.mapNotNull { f ->
                val base = f.name.removeSuffix(".aud")
                val dash = base.indexOf('-')
                if (dash <= 0) return@mapNotNull null
                val id = base.substring(0, dash).toLongOrNull() ?: return@mapNotNull null
                Entry(f, id, base.substring(dash + 1))
            }
            ?.sortedByDescending { it.file.lastModified() }
            ?: emptyList()
    }

    /** 缓存歌曲数量与总占用字节数 */
    fun summary(): Pair<Int, Long> {
        val d = dir ?: return 0 to 0L
        val files = d.listFiles()?.filter { it.isFile && it.name.endsWith(".aud") } ?: return 0 to 0L
        return files.size to files.sumOf { it.length() }
    }

    private fun metaFile(f: File) = File(f.parentFile, f.name + ".meta")

    /** 读取缓存文件旁保存的歌曲元数据；老版本缓存没有元数据时返回 null */
    fun readMeta(f: File): Song? {
        val mf = metaFile(f)
        if (!mf.isFile) return null
        return try {
            val o = JSONObject(mf.readText())
            val artists = ArrayList<Pair<Long, String>>()
            val arr = o.optJSONArray("artistList")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val a = arr.optJSONArray(i) ?: continue
                    val aid = a.optLong(0)
                    val aname = a.optString(1)
                    if (aid > 0 && aname.isNotEmpty()) artists.add(aid to aname)
                }
            }
            Song(
                id = o.optLong("id"),
                name = o.optString("name"),
                artists = o.optString("artists"),
                artistId = o.optLong("artistId"),
                artistList = artists,
                album = o.optString("album"),
                albumId = o.optLong("albumId"),
                duration = o.optLong("duration"),
                cover = o.optString("cover").takeIf { it.isNotEmpty() },
                fee = o.optInt("fee"),
                offline = false,
            ).takeIf { it.id > 0 }
        } catch (_: Exception) {
            null
        }
    }

    private fun writeMeta(f: File, song: Song) {
        try {
            val o = JSONObject()
                .put("id", song.id)
                .put("name", song.name)
                .put("artists", song.artists)
                .put("artistId", song.artistId)
                .put("album", song.album)
                .put("albumId", song.albumId)
                .put("duration", song.duration)
                .put("cover", song.cover ?: "")
                .put("fee", song.fee)
            val arr = JSONArray()
            for (a in song.artistList) arr.put(JSONArray().put(a.first).put(a.second))
            o.put("artistList", arr)
            metaFile(f).writeText(o.toString())
        } catch (_: Exception) {
        }
    }

    /** 为某首歌的全部缓存文件补写元数据（老的、无元数据的缓存文件补齐信息时使用） */
    fun writeMetaFor(id: Long, song: Song) {
        for (e in entries()) {
            if (e.id == id) writeMeta(e.file, song)
        }
    }

    /** 设置页修改缓存上限后按新上限回收 */
    fun applyLimit() {
        executor.execute { trim() }
    }

    /**
     * 下载并入缓存。cb 在后台线程回调：成功为缓存文件，失败/取消为 null。
     * [isCancelled] 返回 true（如已切歌）时中止下载并删除临时文件，不浪费流量。
     */
    fun fetch(song: Song, url: String, isCancelled: () -> Boolean, cb: (File?) -> Unit) {
        val id = song.id
        executor.execute {
            val target = fileFor(id)
            if (target == null) {
                cb(null)
                return@execute
            }
            cached(id)?.let {
                if (!metaFile(it).isFile) writeMeta(it, song)
                cb(it)
                return@execute
            }
            val tmp = File(target.parent, target.name + ".tmp")
            var conn: HttpURLConnection? = null
            try {
                conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 15_000
                conn.readTimeout = 30_000
                conn.instanceFollowRedirects = true
                conn.setRequestProperty("User-Agent", "Mozilla/5.0")
                val code = conn.responseCode
                if (code !in 200..299) {
                    cb(null)
                    return@execute
                }
                conn.inputStream.use { input ->
                    FileOutputStream(tmp).use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            if (isCancelled()) {
                                tmp.delete()
                                cb(null)
                                return@execute
                            }
                            val n = input.read(buf)
                            if (n <= 0) break
                            out.write(buf, 0, n)
                        }
                    }
                }
                if (isCancelled()) {
                    tmp.delete()
                    cb(null)
                    return@execute
                }
                if (tmp.length() > 0 && tmp.renameTo(target)) {
                    writeMeta(target, song)
                    // 不淘汰刚下载成功的文件，否则缓存上限过小时会删掉待播放文件导致播放失败
                    trim(keep = target)
                    cb(target)
                } else {
                    tmp.delete()
                    cb(null)
                }
            } catch (e: Exception) {
                tmp.delete()
                cb(null)
            } finally {
                try { conn?.disconnect() } catch (_: Exception) {}
            }
        }
    }

    /**
     * 超出体积上限时按最久未使用淘汰。
     * [keep] 为刚下载完成、即将交给播放器的文件，即使单曲超过上限也不能删除；
     * 此时缓存最多临时超出上限一首歌的体积，后续新歌下载时会继续回收旧文件。
     */
    private fun trim(keep: File? = null) {
        val d = dir ?: return
        val maxBytes = Prefs.cacheMB.coerceAtLeast(1) * 1024L * 1024L
        val files = d.listFiles()?.filter { it.isFile && it.name.endsWith(".aud") }
            ?.sortedByDescending { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        for (f in files.asReversed()) {
            if (total <= maxBytes) break
            if (keep != null && f.absolutePath == keep.absolutePath) continue
            val len = f.length()
            if (f.delete()) {
                total -= len
                if (f.name.endsWith(".aud")) metaFile(f).delete()
            }
        }
    }
}
