package com.litemusic

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * 极简图片加载器（无第三方依赖，针对低配设备优化）：
 * - LruCache 内存缓存
 * - 磁盘缓存（修复异常流量：此前无磁盘缓存，进程重启/滚动/每次切歌的
 *   锁屏通知封面都会重复下载原图，且每张图解码要开两条连接）
 * - 单连接下载（字节入内存后二次解码，不再重复请求）
 * - 按需降采样解码
 * - 网易云 CDN param 后缀压缩（所有入口统一应用）
 */
object ImageLoader {
    private const val DISK_MAX_BYTES = 96L * 1024 * 1024

    private val runtime = Runtime.getRuntime()
    private val cache = object : LruCache<String, Bitmap>((runtime.maxMemory() / 1024 / 8).toInt().coerceAtLeast(4096)) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }
    private val executor = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())
    private var diskDir: File? = null

    fun init(ctx: Context) {
        diskDir = File(ctx.cacheDir, "images").apply { mkdirs() }
    }

    /** 网易云 CDN 压缩后缀：按目标尺寸请求小图，避免拉原图 */
    private fun sizedUrl(url: String, targetPx: Int): String {
        if (!url.contains("music.126.net") && !url.contains("music.163.net")) return url
        val sep = if (url.contains("?")) "&" else "?"
        return url + sep + "param=" + targetPx + "y" + targetPx + "q75"
    }

    fun load(iv: ImageView, url: String?, targetPx: Int) {
        iv.tag = url
        if (url.isNullOrEmpty()) { iv.setImageDrawable(null); return }
        val sized = sizedUrl(url, targetPx)

        val cached = cache.get(sized)
        if (cached != null) { iv.setImageBitmap(cached); return }

        // 未命中缓存：先清空旧图，避免复用视图时闪现上一首歌/歌单的封面
        iv.setImageDrawable(null)

        executor.execute {
            val bmp = decode(sized, targetPx)
            main.post {
                if (iv.tag == sized || iv.tag == url) {
                    if (bmp != null) {
                        cache.put(sized, bmp)
                        iv.setImageBitmap(bmp)
                    }
                }
            }
        }
    }

    fun bitmap(url: String?, targetPx: Int, cb: (Bitmap?) -> Unit) {
        if (url.isNullOrEmpty()) { main.post { cb(null) }; return }
        val sized = sizedUrl(url, targetPx)

        val cached = cache.get(sized)
        if (cached != null) { main.post { cb(cached) }; return }

        executor.execute {
            val bmp = decode(sized, targetPx)
            if (bmp != null) cache.put(sized, bmp)
            main.post { cb(bmp) }
        }
    }

    /** 单连接下载一次，边界探测与降采样解码都在内存字节上完成，不再二次请求 */
    private fun decode(url: String, targetPx: Int): Bitmap? {
        val fromDisk = diskRead(url)
        val bytes = fromDisk ?: download(url) ?: return null
        val bmp = try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0) null
            else {
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= targetPx) sample *= 2
                val opts = BitmapFactory.Options().apply { inSampleSize = sample }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            }
        } catch (e: Exception) {
            null
        }
        // 磁盘文件残缺/损坏时删掉，下次重新下载而不是永远解不出来
        if (bmp == null && fromDisk != null) {
            try { diskFile(url)?.delete() } catch (_: Exception) {}
        }
        return bmp
    }

    private fun download(url: String): ByteArray? {
        var conn: HttpURLConnection? = null
        try {
            conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "Mozilla/5.0")
            val code = conn.responseCode
            if (code !in 200..299) return null
            val bytes = conn.inputStream.use { it.readBytes() }
            if (bytes.isEmpty()) return null
            diskWrite(url, bytes)
            return bytes
        } catch (e: Exception) {
            return null
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }

    // ---- 磁盘缓存（键=URL 的 MD5，按最后访问时间的 LRU 淘汰，上限 DISK_MAX_BYTES） ----

    private fun diskFile(url: String): File? = diskDir?.let { File(it, Eapi.md5Hex(url)) }

    private fun diskRead(url: String): ByteArray? {
        val f = diskFile(url) ?: return null
        if (!f.isFile || f.length() <= 0) return null
        f.setLastModified(System.currentTimeMillis())
        return try { f.readBytes() } catch (e: Exception) { null }
    }

    private fun diskWrite(url: String, bytes: ByteArray) {
        val f = diskFile(url) ?: return
        try {
            f.writeBytes(bytes)
            trimDisk()
        } catch (_: Exception) {
        }
    }

    private fun trimDisk() {
        val d = diskDir ?: return
        val files = d.listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        for (f in files.asReversed()) {
            if (total <= DISK_MAX_BYTES) break
            val len = f.length()
            if (f.delete()) total -= len
        }
    }
}
