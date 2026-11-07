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
import com.google.android.material.snackbar.Snackbar
import com.litemusic.Api
import com.litemusic.Playlist
import com.litemusic.Prefs
import com.litemusic.R
import com.litemusic.Song
import com.litemusic.player.PlayerService

/** 歌单/每日推荐/我喜欢的 通用歌曲列表页 */
class PlaylistActivity : AppCompatActivity() {

    private var songs: List<Song> = emptyList()
    private lateinit var recycler: RecyclerView
    private lateinit var loading: LoadingBar
    private lateinit var titleView: TextView
    private lateinit var subtitleView: TextView
    private lateinit var btnSub: ImageButton
    private lateinit var mini: MiniPlayerController
    private var detail: Playlist? = null
    private var subBusy = false

    companion object {
        private const val EXTRA_TYPE = "type"   // 0 歌单 1 每日推荐 2 我喜欢的 3 专辑
        private const val EXTRA_ID = "id"
        private const val EXTRA_NAME = "name"
        private const val EXTRA_HIDE_SUB = "hideSub"

        fun launch(ctx: Context, id: Long, name: String, hideSub: Boolean = false): Intent {
            return Intent(ctx, PlaylistActivity::class.java)
                .putExtra(EXTRA_TYPE, 0).putExtra(EXTRA_ID, id).putExtra(EXTRA_NAME, name)
                .putExtra(EXTRA_HIDE_SUB, hideSub)
        }

        fun album(ctx: Context, id: Long, name: String): Intent {
            return Intent(ctx, PlaylistActivity::class.java)
                .putExtra(EXTRA_TYPE, 3).putExtra(EXTRA_ID, id).putExtra(EXTRA_NAME, name)
        }

        fun daily(ctx: Context): Intent {
            return Intent(ctx, PlaylistActivity::class.java)
                .putExtra(EXTRA_TYPE, 1).putExtra(EXTRA_NAME, ctx.getString(R.string.daily_recommend))
        }

        fun liked(ctx: Context): Intent {
            return Intent(ctx, PlaylistActivity::class.java)
                .putExtra(EXTRA_TYPE, 2).putExtra(EXTRA_NAME, ctx.getString(R.string.my_favorites))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_playlist)

        recycler = findViewById(R.id.recycler)
        loading = findViewById(R.id.progress)
        titleView = findViewById(R.id.title)
        subtitleView = findViewById(R.id.subtitle)

        mini = MiniPlayerController(this, findViewById(android.R.id.content))

        val type = intent.getIntExtra(EXTRA_TYPE, 0)
        val name = intent.getStringExtra(EXTRA_NAME) ?: ""
        titleView.text = name
        recycler.layoutManager = LinearLayoutManager(this)

        findViewById<ImageButton>(R.id.btn_back).setOnClickListener { finish() }
        btnSub = findViewById(R.id.btn_sub)
        btnSub.setOnClickListener { toggleSub() }
        findViewById<MaterialButton>(R.id.btn_play_all).setOnClickListener {
            if (songs.isEmpty()) {
                Toast.makeText(this, R.string.list_empty, Toast.LENGTH_SHORT).show()
            } else {
                PlayerService.play(this, songs, 0)
            }
        }

        loading.show()
        when (type) {
            1 -> Api.recommendSongs { r -> handle(r) }
            2 -> loadLiked()
            3 -> Api.albumDetail(intent.getLongExtra(EXTRA_ID, 0)) { r ->
                handle(r.map { it.second })
                r.getOrNull()?.let { (album, list) ->
                    detail = album
                    updateSubBtn()
                    titleView.text = album.name.ifEmpty { name }
                    subtitleView.text = listOfNotNull(
                        album.subtitle?.takeIf { it.isNotEmpty() },
                        getString(R.string.track_count, list.size),
                    ).joinToString(" · ")
                }
            }
            else -> Api.playlistDetail(intent.getLongExtra(EXTRA_ID, 0)) { r ->
                handle(r.map { it.second })
                r.getOrNull()?.let { (pl, list) ->
                    detail = pl
                    updateSubBtn()
                    subtitleView.text = getString(R.string.track_count, list.size)
                }
            }
        }
    }

    /** 歌单/专辑页显示收藏按钮（每日推荐、我喜欢的、以及指定隐藏的页面不显示） */
    private fun updateSubBtn() {
        val type = intent.getIntExtra(EXTRA_TYPE, 0)
        if (type != 0 && type != 3) return
        if (intent.getBooleanExtra(EXTRA_HIDE_SUB, false)) return
        btnSub.visibility = View.VISIBLE
        val sub = detail?.subscribed == true
        btnSub.setImageResource(if (sub) R.drawable.ic_heart_filled else R.drawable.ic_heart)
    }

    private fun toggleSub() {
        val d = detail ?: return
        val type = intent.getIntExtra(EXTRA_TYPE, 0)
        if (!Prefs.loggedIn) {
            Toast.makeText(this, R.string.not_logged_in, Toast.LENGTH_SHORT).show()
            return
        }
        if (subBusy) return
        subBusy = true
        val sub = !d.subscribed
        val cb: (Result<Boolean>) -> Unit = { r ->
            subBusy = false
            if (!isFinishing) {
                if (r.isSuccess) {
                    d.subscribed = sub
                    updateSubBtn()
                    Toast.makeText(this,
                        if (sub) R.string.subscribe_ok else R.string.unsubscribe_ok,
                        Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this,
                        r.exceptionOrNull()?.message ?: getString(R.string.subscribe_fail),
                        Toast.LENGTH_SHORT).show()
                }
            }
        }
        if (type == 3) Api.albumSub(d.id, sub, cb) else Api.playlistSubscribe(d.id, sub, cb)
    }

    override fun onStart() {
        super.onStart()
        mini.onStart()
    }

    override fun onStop() {
        mini.onStop()
        super.onStop()
    }

    override fun onDestroy() {
        loading.hide()
        super.onDestroy()
    }

    private fun loadLiked() {
        Api.likelist { r ->
            val ids = r.getOrNull()
            if (ids.isNullOrEmpty()) {
                if (r.isFailure) handle(r.map { emptyList<Song>() }) else handle(Result.success(emptyList()))
                return@likelist
            }
            Api.songDetail(ids.take(300)) { r2 -> handle(r2) }
        }
    }

    private fun handle(r: Result<List<Song>>) {
        if (isFinishing) return
        loading.hide()
        r.onSuccess {
            songs = it.filter { s -> s.id > 0 }
            subtitleView.text = if (subtitleView.text.isEmpty()) getString(R.string.track_count, songs.size) else subtitleView.text
            recycler.adapter = SongAdapter(songs) { index ->
                PlayerService.play(this, songs, index)
            }
        }.onFailure {
            Snackbar.make(recycler, it.message ?: getString(R.string.error_network), Snackbar.LENGTH_LONG).show()
        }
    }
}
