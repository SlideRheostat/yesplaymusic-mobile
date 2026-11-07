package com.litemusic.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.snackbar.Snackbar
import com.litemusic.Api
import com.litemusic.ImageLoader
import com.litemusic.Playlist
import com.litemusic.Prefs
import com.litemusic.R
import com.litemusic.Song
import com.litemusic.player.PlayerService

/** 歌手主页：热门歌曲 + 所有专辑（对照 YesPlayMusic 网页端歌手页） */
class ArtistActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_ID = "id"
        private const val EXTRA_NAME = "name"

        fun launch(ctx: Context, id: Long, name: String): Intent =
            Intent(ctx, ArtistActivity::class.java)
                .putExtra(EXTRA_ID, id).putExtra(EXTRA_NAME, name)

        /** 歌曲 → 歌手主页：单歌手直达，多歌手弹出选择 */
        fun openFromSong(ctx: Context, song: Song) {
            val list = song.artistList.filter { it.first > 0 && it.second.isNotEmpty() }
            if (list.isEmpty()) return
            if (list.size == 1) {
                ctx.startActivity(launch(ctx, list[0].first, list[0].second))
                return
            }
            MaterialAlertDialogBuilder(ctx)
                .setTitle(R.string.choose_artist)
                .setItems(list.map { it.second }.toTypedArray()) { _, i ->
                    ctx.startActivity(launch(ctx, list[i].first, list[i].second))
                }
                .show()
        }
    }

    private lateinit var recycler: RecyclerView
    private lateinit var progress: LinearProgressIndicator
    private lateinit var followBtn: MaterialButton
    private var artistId = 0L
    private var followed = false
    private var followBusy = false
    private var songs: List<Song> = emptyList()
    private var albums: List<Playlist> = emptyList()
    private var rows: List<Any> = emptyList()
    private var songsLoaded = false
    private var albumsLoaded = false
    private var songsExpanded = true
    private var albumsExpanded = true
    private var mini: MiniPlayerController? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_artist)

        val id = intent.getLongExtra(EXTRA_ID, 0)
        val name = intent.getStringExtra(EXTRA_NAME) ?: ""
        artistId = id
        findViewById<TextView>(R.id.artist_name).text = name
        findViewById<ImageButton>(R.id.btn_back).setOnClickListener { finish() }
        followBtn = findViewById(R.id.btn_follow)
        followBtn.setOnClickListener { toggleFollow() }

        recycler = findViewById(R.id.recycler)
        progress = findViewById(R.id.progress)
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = ArtistAdapter()
        progress.visibility = View.VISIBLE

        mini = MiniPlayerController(this, findViewById(android.R.id.content))

        Api.artistTopSongs(id) { r ->
            if (isFinishing) return@artistTopSongs
            songsLoaded = true
            r.onSuccess {
                songs = it
                rebuild()
            }.onFailure { if (!albumsLoaded) showError(it.message) }
            checkDone()
        }
        Api.artistAlbums(id) { r ->
            if (isFinishing) return@artistAlbums
            albumsLoaded = true
            r.onSuccess { (artist, list) ->
                albums = list
                findViewById<TextView>(R.id.artist_name).text = artist.name.ifEmpty { name }
                findViewById<TextView>(R.id.artist_sub).text = getString(
                    R.string.artist_stats,
                    artist.trackCount,
                    if (artist.albumCount > 0) artist.albumCount else list.size,
                )
                ImageLoader.load(findViewById(R.id.artist_pic), artist.cover, 120)
                rebuild()
            }.onFailure { if (!songsLoaded) showError(it.message) }
            checkDone()
        }
        // 关注态单独走 v1/artist/{id}（artist.followed 为登录态真实值）
        Api.artistFollowed(id) { r ->
            if (isFinishing) return@artistFollowed
            followed = r.getOrNull() ?: false
            updateFollowBtn()
        }
    }

    /** 关注/取关艺人（官方 artist_sub/artist_unsub），成功后切换按钮态 */
    private fun toggleFollow() {
        if (!Prefs.loggedIn) {
            Toast.makeText(this, R.string.not_logged_in, Toast.LENGTH_SHORT).show()
            return
        }
        if (followBusy) return
        followBusy = true
        followBtn.isEnabled = false
        val target = !followed
        Api.artistSub(artistId, target) { r ->
            if (isFinishing) return@artistSub
            followBusy = false
            if (r.isSuccess) {
                followed = target
            } else {
                Toast.makeText(this, r.exceptionOrNull()?.message ?: getString(R.string.action_failed), Toast.LENGTH_SHORT).show()
            }
            updateFollowBtn()
        }
    }

    private fun updateFollowBtn() {
        followBtn.visibility = View.VISIBLE
        followBtn.text = if (followed) getString(R.string.followed) else getString(R.string.follow)
        followBtn.isEnabled = true
    }

    override fun onStart() {
        super.onStart()
        mini?.onStart()
    }

    override fun onStop() {
        mini?.onStop()
        super.onStop()
    }

    private fun checkDone() {
        if (songsLoaded && albumsLoaded) progress.visibility = View.GONE
    }

    private fun rebuild() {
        val out = ArrayList<Any>()
        if (songs.isNotEmpty()) {
            out.add(Sec(getString(R.string.hot_songs), false))
            if (songsExpanded) out.addAll(songs)
        }
        if (albums.isNotEmpty()) {
            out.add(Sec(getString(R.string.all_albums), true))
            if (albumsExpanded) out.addAll(albums)
        }
        rows = out
        recycler.adapter?.notifyDataSetChanged()
    }

    private fun showError(msg: String?) {
        Snackbar.make(recycler, msg ?: getString(R.string.error_network), Snackbar.LENGTH_LONG).show()
    }

    // 行模型：Sec=可折叠分区标题  Song=歌曲  Playlist=专辑
    private class Sec(val title: String, val isAlbums: Boolean)

    private inner class ArtistAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        override fun getItemCount(): Int = rows.size

        override fun getItemViewType(position: Int): Int = when (rows[position]) {
            is Sec -> 0
            is Song -> 1
            else -> 2
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inf = LayoutInflater.from(parent.context)
            return when (viewType) {
                0 -> SectionVH(inf.inflate(R.layout.item_section_header, parent, false))
                else -> SongVH(inf.inflate(R.layout.item_song, parent, false))
            }
        }

        override fun onBindViewHolder(h: RecyclerView.ViewHolder, pos: Int) {
            when (val row = rows[pos]) {
                is Sec -> (h as SectionVH).bind(row)
                is Song -> (h as SongVH).bindSong(row)
                is Playlist -> (h as SongVH).bindAlbum(row)
            }
        }
    }

    private inner class SectionVH(v: View) : RecyclerView.ViewHolder(v) {
        private val title: TextView = v.findViewById(R.id.section_title)
        private val count: TextView = v.findViewById(R.id.section_count)
        private val chevron: ImageView = v.findViewById(R.id.section_chevron)

        fun bind(sec: Sec) {
            val expanded = if (sec.isAlbums) albumsExpanded else songsExpanded
            title.text = sec.title
            count.text = if (sec.isAlbums) "${albums.size}" else "${songs.size}"
            chevron.visibility = View.VISIBLE
            chevron.animate().cancel()
            chevron.animate()
                .rotation(if (expanded) 0f else -90f)
                .setDuration(250)
                .setInterpolator(android.view.animation.PathInterpolator(0.05f, 0.7f, 0.1f, 1f))
                .start()
            itemView.setOnClickListener {
                if (sec.isAlbums) albumsExpanded = !albumsExpanded else songsExpanded = !songsExpanded
                rebuild()
            }
        }
    }

    private inner class SongVH(v: View) : RecyclerView.ViewHolder(v) {
        private val cover: ImageView = v.findViewById(R.id.cover)
        private val name: TextView = v.findViewById(R.id.name)
        private val artist: TextView = v.findViewById(R.id.artist)
        private val albumColumn: TextView? = v.findViewById(R.id.album_column)
        private val like: ImageButton = v.findViewById(R.id.btn_like)

        fun bindSong(song: Song) {
            name.text = song.name
            // 横屏布局有独立专辑列；竖屏布局没有该列，保持原来的合并文案
            if (albumColumn != null) {
                artist.text = song.artists
                if (song.album.isEmpty()) {
                    albumColumn.visibility = View.GONE
                } else {
                    albumColumn.visibility = View.VISIBLE
                    albumColumn.text = song.album
                    // 横屏专辑列：点击仍播放歌曲，长按进入该专辑
                    albumColumn.setOnClickListener {
                        val p = bindingAdapterPosition
                        if (p != RecyclerView.NO_POSITION) playSongRow(p)
                    }
                    albumColumn.setOnLongClickListener {
                        if (song.albumId > 0) {
                            it.context.startActivity(
                                PlaylistActivity.album(it.context, song.albumId, song.album)
                            )
                            true
                        } else false
                    }
                }
            } else {
                artist.text = "${song.artists} - ${song.album}"
            }
            ImageLoader.load(cover, song.cover, 120)
            like.visibility = View.VISIBLE
            like.tag = song.id
            like.setImageResource(if (LikedStore.contains(song.id)) R.drawable.ic_heart_filled else R.drawable.ic_heart)
            like.setOnClickListener { toggleLike(song, like) }
            // 长按弹出全部歌手/作者列表，点选进入歌手主页
            artist.setOnClickListener(null)
            itemView.setOnLongClickListener {
                openFromSong(itemView.context, song)
                true
            }
            itemView.setOnClickListener {
                val p = bindingAdapterPosition
                if (p != RecyclerView.NO_POSITION) playSongRow(p)
            }
        }

        fun bindAlbum(album: Playlist) {
            name.text = album.name
            artist.text = album.subtitle ?: ""
            ImageLoader.load(cover, album.cover, 120)
            like.visibility = View.GONE
            albumColumn?.visibility = View.GONE
            artist.setOnClickListener(null)
            itemView.setOnLongClickListener(null)
            itemView.setOnClickListener {
                startActivity(PlaylistActivity.album(this@ArtistActivity, album.id, album.name))
            }
        }
    }

    /** 歌曲行位置 → 播放 songs 中对应下标；歌曲行紧跟其分区标题行之后 */
    private fun playSongRow(rowPos: Int) {
        val idx = songIndexOf(rowPos)
        if (idx in songs.indices) PlayerService.play(this, songs, idx)
    }

    /** 歌曲行位置 → songs 下标（第 0 行是"热门歌曲"分区标题） */
    private fun songIndexOf(rowPos: Int): Int = rowPos - 1

    private fun toggleLike(song: Song, btn: ImageButton) {
        if (!Prefs.loggedIn) {
            Toast.makeText(this, R.string.not_logged_in, Toast.LENGTH_SHORT).show()
            return
        }
        val now = !LikedStore.contains(song.id)
        Api.like(song.id, now) { r ->
            if (r.isSuccess) {
                LikedStore.setLiked(song.id, now)
                // 行可能已被复用为别的歌，只更新仍绑定这首歌的按钮图标
                if (btn.tag == song.id) {
                    btn.setImageResource(if (now) R.drawable.ic_heart_filled else R.drawable.ic_heart)
                }
            } else {
                Toast.makeText(this, r.exceptionOrNull()?.message, Toast.LENGTH_SHORT).show()
            }
        }
    }
}
