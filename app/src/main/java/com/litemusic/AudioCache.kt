package com.litemusic

import android.content.Context
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
 */
object AudioCache {
    private val executor = Executors.newSingleThreadExecutor()
    private var dir: File? = null

    fun init(ctx: Context) {
        val d = File(ctx.cacheDir, "audio")
        d.mkdirs()
        // 清理上次未下完的临时文件
        d.listFiles()?.filter { it.name.endsWith(".tmp") }?.forEach { it.delete() }
        dir = d
    }

    /** 缓存键含音质等级，切换音质不会误用旧文件 */
    private fun fileFor(id: Long): File? = dir?.let { File(it, "$id-${Prefs.level}.aud") }

    /** 命中返回本地文件（并刷新 LRU 时间），未命中返回 null */
    fun cached(id: Long): File? =
        fileFor(id)?.takeIf { it.isFile && it.length() > 0 }
            ?.apply { setLastModified(System.currentTimeMillis()) }

    /** 设置页修改缓存上限后按新上限回收 */
    fun applyLimit() {
        executor.execute { trim() }
    }

    /**
     * 下载并入缓存。cb 在后台线程回调：成功为缓存文件，失败/取消为 null。
     * [isCancelled] 返回 true（如已切歌）时中止下载并删除临时文件，不浪费流量。
     */
    fun fetch(id: Long, url: String, isCancelled: () -> Boolean, cb: (File?) -> Unit) {
        executor.execute {
            val target = fileFor(id)
            if (target == null) {
                cb(null)
                return@execute
            }
            cached(id)?.let {
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
                    trim()
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

    /** 超出体积上限时按最久未使用淘汰 */
    private fun trim() {
        val d = dir ?: return
        val maxBytes = Prefs.cacheMB.coerceAtLeast(1) * 1024L * 1024L
        val files = d.listFiles()?.filter { it.isFile && !it.name.endsWith(".tmp") }
            ?.sortedByDescending { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        for (f in files.asReversed()) {
            if (total <= maxBytes) break
            val len = f.length()
            if (f.delete()) total -= len
        }
    }
}
