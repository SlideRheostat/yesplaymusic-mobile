package com.litemusic.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.litemusic.Api
import com.litemusic.ImageLoader
import com.litemusic.Prefs
import com.litemusic.R
import com.litemusic.Playlist

/**
 * 我的页：登录卡 + 我喜欢的音乐（独立行）+ 外观模式 + 可折叠的“我的歌单/我的专辑”分区。
 */
class MineFragment : Fragment() {

    private lateinit var recycler: RecyclerView
    private var playlists: List<Playlist> = emptyList()
    private var albums: List<Playlist> = emptyList()
    private var artists: List<Playlist> = emptyList()
    private var likedId = 0L
    private var playlistsExpanded = true
    private var albumsExpanded = true
    private var artistsExpanded = true
    private var albumsFailed = false
    private var albumsError: String? = null
    private var playlistsFailed = false
    private var artistsFailed = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        return inflater.inflate(R.layout.fragment_mine, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        recycler = view.findViewById(R.id.recycler)
        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.adapter = MineAdapter()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        rebuild()
        if (Prefs.loggedIn) {
            val load = {
                LikedStore.refresh { rebuild() }
                Api.userPlaylist(Prefs.userId) { r ->
                    if (!isAdded) return@userPlaylist
                    r.onSuccess { (id, list) ->
                        likedId = id
                        playlists = list
                        playlistsFailed = false
                        rebuild()
                    }.onFailure {
                        playlistsFailed = true
                        rebuild()
                    }
                }
                Api.albumSublist { r ->
                    if (!isAdded) return@albumSublist
                    r.onSuccess {
                        albums = it
                        albumsFailed = false
                        albumsError = null
                        rebuild()
                    }.onFailure {
                        albumsFailed = true
                        albumsError = it.message
                        rebuild()
                    }
                }
                Api.followedArtists(100, 0) { r ->
                    if (!isAdded) return@followedArtists
                    r.onSuccess {
                        artists = it
                        artistsFailed = false
                        rebuild()
                    }.onFailure {
                        artistsFailed = true
                        rebuild()
                    }
                }
            }
            // userId 缺失（老版本遗留）时先拉账号信息自愈，否则歌单/红心接口拿不到数据
            if (Prefs.userId == 0L) Api.account { load() } else load()
        } else {
            LikedStore.clear()
            playlists = emptyList()
            albums = emptyList()
            artists = emptyList()
            likedId = 0
            albumsFailed = false
            playlistsFailed = false
            artistsFailed = false
        }
    }

    private fun rebuild() {
        // 异步回调可能在 Fragment 已 detach 后到达，此时 getString 会抛 IllegalStateException
        if (!isAdded) return
        rows = buildRows()
        recycler.adapter?.notifyDataSetChanged()
    }

    // 行模型：type 0=头部 1=分区标题 2=条目 3=提示
    private inner class Row(val type: Int, val title: String = "", val item: Playlist? = null, val section: Int = 0)

    private var rows: List<Row> = emptyList()
    private val sectionPlaylist = 1
    private val sectionAlbum = 2
    private val sectionArtist = 3

    private fun buildRows(): List<Row> {
        val rows = ArrayList<Row>()
        rows.add(Row(0))
        if (playlists.isNotEmpty() || Prefs.loggedIn) {
            rows.add(Row(1, getString(R.string.my_playlists), section = sectionPlaylist))
            if (playlistsExpanded) {
                for (p in playlists) rows.add(Row(2, item = p))
                if (playlists.isEmpty()) {
                    rows.add(Row(3, title = if (playlistsFailed) getString(R.string.load_failed) else getString(R.string.no_playlists)))
                }
            }
        }
        if (albums.isNotEmpty() || Prefs.loggedIn) {
            rows.add(Row(1, getString(R.string.my_albums), section = sectionAlbum))
            if (albumsExpanded) {
                for (a in albums) rows.add(Row(2, item = a))
                if (albums.isEmpty()) {
                    rows.add(Row(3, title = if (albumsFailed) (albumsError ?: getString(R.string.load_failed)) else getString(R.string.no_albums)))
                }
            }
        }
        if (artists.isNotEmpty() || Prefs.loggedIn) {
            rows.add(Row(1, getString(R.string.followed_artists), section = sectionArtist))
            if (artistsExpanded) {
                for (a in artists) rows.add(Row(2, item = a, section = sectionArtist))
                if (artists.isEmpty()) {
                    rows.add(Row(3, title = if (artistsFailed) getString(R.string.load_failed) else getString(R.string.no_followed_artists)))
                }
            }
        }
        return rows
    }

    private inner class MineAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount(): Int = rows.size
        override fun getItemViewType(position: Int): Int = rows[position].type

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inf = LayoutInflater.from(parent.context)
            return when (viewType) {
                0 -> HeaderVH(inf.inflate(R.layout.item_mine_header, parent, false))
                1 -> SectionVH(inf.inflate(R.layout.item_section_header, parent, false))
                3 -> HintVH(inf.inflate(R.layout.item_hint, parent, false) as android.widget.TextView)
                else -> RowVH(inf.inflate(R.layout.item_song, parent, false))
            }
        }

        override fun onBindViewHolder(h: RecyclerView.ViewHolder, pos: Int) {
            val row = rows[pos]
            when (h) {
                is HeaderVH -> bindHeader(h)
                is SectionVH -> bindSection(h, row)
                is HintVH -> {
                    h.text.text = row.title
                    h.text.setOnClickListener { refresh() }
                }
                is RowVH -> bindItem(h, row)
            }
        }
    }

    private class HintVH(val text: android.widget.TextView) : RecyclerView.ViewHolder(text)

    private fun bindHeader(h: HeaderVH) {
        val loggedIn = Prefs.loggedIn
        // 登录后账号信息与退出登录已移至设置页顶部，“我的”页不再重复展示
        h.cardUser.visibility = if (loggedIn) View.GONE else View.VISIBLE
        if (!loggedIn) {
            h.nickname.text = getString(R.string.login)
            h.sub.text = getString(R.string.login_tip)
            h.action.text = getString(R.string.login)
            h.action.setOnClickListener { startActivity(Intent(requireContext(), LoginActivity::class.java)) }
            h.avatar.setImageResource(R.drawable.ic_person)
        }
        h.favoritesRow.visibility = if (loggedIn) View.VISIBLE else View.GONE
        h.favCount.text = if (loggedIn) "${LikedStore.size()}" else ""
        h.favoritesRow.setOnClickListener {
            if (likedId > 0) startActivity(
                PlaylistActivity.launch(
                    requireContext(), likedId, getString(R.string.my_favorites), hideSub = true
                )
            ) else startActivity(PlaylistActivity.liked(requireContext()))
        }
        h.settingsRow.setOnClickListener { startActivity(Intent(requireContext(), SettingsActivity::class.java)) }
    }

    private fun bindSection(h: SectionVH, row: Row) {
        h.title.text = row.title
        val expanded = when (row.section) {
            sectionPlaylist -> playlistsExpanded
            sectionAlbum -> albumsExpanded
            else -> artistsExpanded
        }
        val count = when (row.section) {
            sectionPlaylist -> playlists.size
            sectionAlbum -> albums.size
            else -> artists.size
        }
        h.count.text = if (count > 0) "$count" else ""
        h.chevron.animate().cancel()
        h.chevron.animate()
            .rotation(if (expanded) 0f else -90f)
            .setDuration(250)
            .setInterpolator(android.view.animation.PathInterpolator(0.05f, 0.7f, 0.1f, 1f))
            .start()
        h.root.setOnClickListener {
            when (row.section) {
                sectionPlaylist -> playlistsExpanded = !playlistsExpanded
                sectionAlbum -> albumsExpanded = !albumsExpanded
                else -> artistsExpanded = !artistsExpanded
            }
            rebuild()
        }
    }

    private fun bindItem(h: RowVH, row: Row) {
        val p = row.item!!
        h.name.text = p.name
        if (row.section == sectionArtist) {
            h.artist.text = if (p.albumCount > 0) getString(R.string.album_count, p.albumCount)
            else getString(R.string.artist)
        } else if (p.subtitle != null) {
            h.artist.text = "${p.subtitle} · ${getString(R.string.track_count, p.trackCount)}"
        } else {
            h.artist.text = getString(R.string.playlist_meta, p.trackCount, HomeFragment.formatCount(p.playCount))
        }
        ImageLoader.load(h.cover, p.cover, 120)
        h.like.visibility = View.GONE
        h.itemView.setOnClickListener {
            val ctx = requireContext()
            when (row.section) {
                sectionArtist -> startActivity(ArtistActivity.launch(ctx, p.id, p.name))
                else -> startActivity(if (p.subtitle != null) PlaylistActivity.album(ctx, p.id, p.name)
                else PlaylistActivity.launch(ctx, p.id, p.name))
            }
        }
    }

    private class HeaderVH(v: View) : RecyclerView.ViewHolder(v) {
        val cardUser: View = v.findViewById(R.id.card_user)
        val avatar: ImageView = v.findViewById(R.id.avatar)
        val nickname: TextView = v.findViewById(R.id.nickname)
        val sub: TextView = v.findViewById(R.id.sub)
        val action: MaterialButton = v.findViewById(R.id.btn_action)
        val favoritesRow: LinearLayout = v.findViewById(R.id.row_favorites)
        val favCount: TextView = v.findViewById(R.id.fav_count)
        val settingsRow: View = v.findViewById(R.id.row_settings)
    }

    private class SectionVH(v: View) : RecyclerView.ViewHolder(v) {
        val root: LinearLayout = v.findViewById(R.id.section_root)
        val title: TextView = v.findViewById(R.id.section_title)
        val count: TextView = v.findViewById(R.id.section_count)
        val chevron: ImageView = v.findViewById(R.id.section_chevron)
    }

    private class RowVH(v: View) : RecyclerView.ViewHolder(v) {
        val cover: ImageView = v.findViewById(R.id.cover)
        val name: TextView = v.findViewById(R.id.name)
        val artist: TextView = v.findViewById(R.id.artist)
        val like: ImageView = v.findViewById(R.id.btn_like)
    }
}
