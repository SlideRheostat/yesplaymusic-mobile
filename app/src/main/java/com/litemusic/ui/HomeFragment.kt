package com.litemusic.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.snackbar.Snackbar
import com.litemusic.Api
import com.litemusic.ImageLoader
import com.litemusic.Prefs
import com.litemusic.R
import com.litemusic.Playlist

class HomeFragment : Fragment() {

    private var playlists: List<Playlist> = emptyList()
    private var squareOffset = 0
    private var squareMore = true
    private var loadingMore = false
    private lateinit var recycler: RecyclerView
    private var spanCount = 2

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        return inflater.inflate(R.layout.fragment_home, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        recycler = view.findViewById(R.id.recycler)
        // 按屏幕宽度智能决定每行推荐卡片数（约 180dp 一张），避免平板横屏"大字报"
        val widthDp = resources.displayMetrics.widthPixels / resources.displayMetrics.density
        spanCount = (widthDp / 180).toInt().coerceAtLeast(2)
        val lm = GridLayoutManager(requireContext(), spanCount)
        lm.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            // 头部横幅与底部"加载更多"按钮独占整行；卡片密度仍由 spanCount 控制
            override fun getSpanSize(position: Int): Int =
                if (position == 0 || position == (recycler.adapter?.itemCount ?: 0) - 1) spanCount else 1
        }
        recycler.layoutManager = lm
        recycler.adapter = HomeAdapter()
        load()
    }

    /** 首屏：个性化推荐（该接口无分页，重复调用同一批） */
    private fun load() {
        Api.personalized(30) { r ->
            if (!isAdded) return@personalized
            r.onSuccess { list ->
                squareOffset = 0
                squareMore = true
                playlists = list
                recycler.adapter?.notifyDataSetChanged()
            }.onFailure {
                Snackbar.make(recycler, it.message ?: getString(R.string.error_network), Snackbar.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * 点击底部"加载更多"：从歌单广场按 offset 拉取下一页并**追加**到列表尾部
     * （已有条目顺序不动）；按 id 去重防重复卡片；offset 按服务器实际返回条数推进。
     * 按钮三态：加载更多 / 加载中… / 没有更多了（服务端 more=false 时禁用）。
     */
    private fun loadMore() {
        if (loadingMore || !squareMore) return
        loadingMore = true
        refreshFooter()
        Api.playlistSquare(30, squareOffset) { r ->
            if (!isAdded) return@playlistSquare
            loadingMore = false
            r.onSuccess { (list, more) ->
                squareOffset += list.size
                squareMore = more
                val existing = playlists.mapTo(HashSet()) { it.id }
                val fresh = list.filterNot { existing.contains(it.id) }
                if (fresh.isNotEmpty()) {
                    val start = playlists.size + 1 // 插到头部横幅之后、footer 之前
                    playlists = playlists + fresh
                    recycler.adapter?.notifyItemRangeInserted(start, fresh.size)
                }
                refreshFooter()
            }.onFailure {
                refreshFooter()
                Snackbar.make(recycler, it.message ?: getString(R.string.error_network), Snackbar.LENGTH_SHORT).show()
            }
        }
    }

    private fun refreshFooter() {
        val adapter = recycler.adapter ?: return
        if (adapter.itemCount > 0) adapter.notifyItemChanged(adapter.itemCount - 1)
    }

    private inner class HomeAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount(): Int = 1 + playlists.size + 1 // 头部 + 歌单 + 加载更多

        override fun getItemViewType(position: Int): Int = when (position) {
            0 -> 0
            itemCount - 1 -> 2
            else -> 1
        }

        override fun onCreateViewHolder(group: ViewGroup, type: Int): RecyclerView.ViewHolder {
            val inf = LayoutInflater.from(group.context)
            return when (type) {
                0 -> HeaderVH(inf.inflate(R.layout.item_home_header, group, false))
                2 -> LoadMoreVH(inf.inflate(R.layout.item_load_more, group, false))
                else -> PlaylistVH(inf.inflate(R.layout.item_playlist, group, false))
            }
        }

        override fun onBindViewHolder(h: RecyclerView.ViewHolder, pos: Int) {
            if (h is HeaderVH) {
                h.cardDaily.setOnClickListener {
                    if (!Prefs.loggedIn) {
                        Toast.makeText(requireContext(), R.string.not_logged_in, Toast.LENGTH_SHORT).show()
                        startActivity(Intent(requireContext(), LoginActivity::class.java))
                    } else {
                        startActivity(PlaylistActivity.daily(requireContext()))
                    }
                }
            } else if (h is LoadMoreVH) {
                val b = h.button
                when {
                    loadingMore -> { b.text = getString(R.string.loading); b.isEnabled = false }
                    !squareMore -> { b.text = getString(R.string.no_more); b.isEnabled = false }
                    else -> { b.text = getString(R.string.load_more); b.isEnabled = true }
                }
                b.setOnClickListener { loadMore() }
            } else if (h is PlaylistVH) {
                val p = playlists[pos - 1]
                h.name.text = p.name
                h.count.text = formatCount(p.playCount)
                ImageLoader.load(h.cover, p.cover, 300)
                h.itemView.setOnClickListener {
                    startActivity(PlaylistActivity.launch(requireContext(), p.id, p.name))
                }
            }
        }
    }

    private class HeaderVH(v: View) : RecyclerView.ViewHolder(v) {
        val cardDaily: MaterialCardView = v.findViewById(R.id.card_daily)
    }

    private class LoadMoreVH(v: View) : RecyclerView.ViewHolder(v) {
        val button: MaterialButton = v.findViewById(R.id.btn_load_more)
    }

    private class PlaylistVH(v: View) : RecyclerView.ViewHolder(v) {
        val cover: android.widget.ImageView = v.findViewById(R.id.cover)
        val name: TextView = v.findViewById(R.id.name)
        val count: TextView = v.findViewById(R.id.count)
    }

    companion object {
        fun formatCount(n: Long): String = when {
            n >= 100_000_000 -> String.format("%.1f亿", n / 100_000_000.0)
            n >= 10_000 -> "${n / 10_000}万"
            else -> n.toString()
        }
    }
}
