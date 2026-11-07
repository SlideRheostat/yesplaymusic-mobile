package com.litemusic.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import com.litemusic.Api
import com.litemusic.ImageLoader
import com.litemusic.Prefs
import com.litemusic.R
import com.litemusic.Song

/** 通用歌曲列表适配器（搜索/歌单/收藏共用） */
class SongAdapter(
    private val songs: List<Song>,
    private val onClick: (Int) -> Unit,
) : RecyclerView.Adapter<SongAdapter.VH>() {

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val cover: ImageView = v.findViewById(R.id.cover)
        val name: TextView = v.findViewById(R.id.name)
        val artist: TextView = v.findViewById(R.id.artist)
        val like: ImageButton = v.findViewById(R.id.btn_like)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        return VH(LayoutInflater.from(parent.context).inflate(R.layout.item_song, parent, false))
    }

    override fun getItemCount(): Int = songs.size

    override fun onBindViewHolder(h: VH, position: Int) {
        val song = songs[position]
        h.itemView.tag = song.id
        h.name.text = song.name
        // 横屏布局有独立专辑列：歌手留在歌名下方，专辑放入右列；竖屏仍合并为一行
        val albumColumn = h.itemView.findViewById<TextView>(R.id.album_column)
        if (albumColumn != null) {
            h.artist.text = song.artists
            if (song.album.isEmpty()) {
                albumColumn.visibility = View.GONE
            } else {
                albumColumn.visibility = View.VISIBLE
                albumColumn.text = song.album
                // 横屏专辑列：点击仍播放歌曲，长按进入该专辑
                albumColumn.setOnClickListener {
                    val p = h.bindingAdapterPosition
                    if (p != RecyclerView.NO_POSITION) onClick(p)
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
            h.artist.text = "${song.artists} - ${song.album}"
        }
        ImageLoader.load(h.cover, song.cover, 120)
        h.itemView.setOnClickListener {
            val p = h.bindingAdapterPosition
            if (p != RecyclerView.NO_POSITION) onClick(p)
        }
        // 长按歌曲弹出全部歌手/作者列表，点选进入歌手主页
        h.itemView.setOnLongClickListener {
            val p = h.bindingAdapterPosition
            if (p != RecyclerView.NO_POSITION) {
                ArtistActivity.openFromSong(h.itemView.context, songs[p])
            }
            true
        }
        h.like.setImageResource(
            if (LikedStore.contains(song.id)) R.drawable.ic_heart_filled else R.drawable.ic_heart
        )
        h.like.setOnClickListener {
            if (!Prefs.loggedIn) {
                Toast.makeText(h.itemView.context, R.string.not_logged_in, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            // 以最新收藏状态取反：连续点击时不能沿用 bind 时的旧快照
            val now = !LikedStore.contains(song.id)
            Api.like(song.id, now) { r ->
                if (r.isSuccess) {
                    LikedStore.setLiked(song.id, now)
                    // ViewHolder 可能已被复用，仅当仍绑定这首歌时才更新图标
                    if (h.itemView.tag == song.id) {
                        h.like.setImageResource(if (now) R.drawable.ic_heart_filled else R.drawable.ic_heart)
                    }
                    Toast.makeText(h.itemView.context,
                        if (now) R.string.favorited else R.string.unfavorited, Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(h.itemView.context, r.exceptionOrNull()?.message, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
