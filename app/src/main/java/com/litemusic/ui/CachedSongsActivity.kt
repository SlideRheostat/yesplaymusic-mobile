package com.litemusic.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.litemusic.Api
import com.litemusic.AudioCache
import com.litemusic.R
import com.litemusic.Song
import com.litemusic.player.PlayerService
import java.util.Locale

/**
 * 已缓存的歌：列出磁盘音频缓存，支持直接离线播放。
 *
 * 老版本的缓存文件没有元数据，打开页面时按 id 补查一次歌曲信息并写回；
 * 补查失败时用占位信息展示，但仍可播放本地文件。
 */
class CachedSongsActivity : AppCompatActivity() {

    companion object {
        fun launch(ctx: Context): Intent = Intent(ctx, CachedSongsActivity::class.java)
    }

    private lateinit var recycler: RecyclerView
    private lateinit var emptyTip: TextView
    private lateinit var subtitle: TextView
    private lateinit var loading: LoadingBar
    private lateinit var playAll: MaterialButton
    private lateinit var mini: MiniPlayerController

    private var songs: List<Song> = emptyList()
    private var unknownIds: List<Long> = emptyList()
    private var totalBytes = 0L
    private var firstResume = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_cached_songs)

        recycler = findViewById(R.id.recycler)
        emptyTip = findViewById(R.id.empty_tip)
        subtitle = findViewById(R.id.subtitle)
        loading = findViewById(R.id.progress)
        playAll = findViewById(R.id.btn_play_all)

        recycler.layoutManager = LinearLayoutManager(this)
        findViewById<ImageButton>(R.id.btn_back).setOnClickListener { finish() }
        playAll.setOnClickListener {
            if (songs.isEmpty()) {
                Toast.makeText(this, R.string.cached_songs_empty, Toast.LENGTH_SHORT).show()
            } else {
                PlayerService.playCached(this, songs, 0)
            }
        }

        mini = MiniPlayerController(this, findViewById(android.R.id.content))
        loading.show()
        reload()
    }

    override fun onStart() {
        super.onStart()
        mini.onStart()
    }

    override fun onResume() {
        super.onResume()
        // 从播放页返回时缓存可能已变化，重新统计一次
        if (firstResume) firstResume = false else reload()
    }

    override fun onStop() {
        mini.onStop()
        super.onStop()
    }

    override fun onDestroy() {
        loading.hide()
        super.onDestroy()
    }

    private fun reload() {
        loading.show()
        Thread {
            val entries = AudioCache.entries()
            val known = LinkedHashMap<Long, Song>()
            val unknown = ArrayList<Long>()
            for (e in entries) {
                val meta = AudioCache.readMeta(e.file)
                if (meta != null) {
                    if (!known.containsKey(meta.id)) known[meta.id] = meta
                } else if (!unknown.contains(e.id)) {
                    unknown.add(e.id)
                }
            }
            val bytes = AudioCache.summary().second
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                songs = known.values.toList()
                unknownIds = unknown
                totalBytes = bytes
                render()
                resolveUnknown()
            }
        }.start()
    }

    private fun render() {
        val count = songs.size + unknownIds.size
        subtitle.text = getString(R.string.cached_songs_summary, count, formatSize(totalBytes))
        emptyTip.visibility = if (count == 0) View.VISIBLE else View.GONE
        playAll.isEnabled = count > 0
        recycler.adapter = SongAdapter(songs, showLike = false) { index ->
            PlayerService.playCached(this, songs, index)
        }
        if (unknownIds.isEmpty()) loading.hide()
    }

    /** 老缓存文件没有元数据：按 id 补查歌曲信息并写回 .meta，下次打开直接可用 */
    private fun resolveUnknown() {
        val ids = unknownIds
        if (ids.isEmpty()) {
            loading.hide()
            return
        }
        Api.songDetail(ids.take(300)) { r ->
            if (isFinishing) return@songDetail
            val found = r.getOrNull().orEmpty()
            for (s in found) AudioCache.writeMetaFor(s.id, s)

            val merged = LinkedHashMap<Long, Song>()
            for (s in songs) merged[s.id] = s
            for (s in found) merged.putIfAbsent(s.id, s)
            // 查询失败也不能丢文件：用占位歌曲保证列表里仍可播放
            for (id in ids) {
                if (!merged.containsKey(id)) {
                    merged[id] = Song(
                        id = id,
                        name = getString(R.string.unknown_song),
                        artists = getString(R.string.unknown_song_artist),
                        artistId = 0,
                        album = "",
                        albumId = 0,
                        duration = 0,
                        cover = null,
                        fee = 0,
                        offline = false,
                    )
                }
            }
            songs = merged.values.toList()
            unknownIds = emptyList()
            render()
        }
    }

    private fun formatSize(bytes: Long): String {
        val mb = bytes / 1024.0 / 1024.0
        return if (mb >= 1024) String.format(Locale.US, "%.1f GB", mb / 1024) else
            String.format(Locale.US, "%.0f MB", mb)
    }
}
