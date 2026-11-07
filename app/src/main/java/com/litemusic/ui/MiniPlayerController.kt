package com.litemusic.ui

import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.view.Gravity
import android.view.View
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.litemusic.ImageLoader
import com.litemusic.R
import com.litemusic.Song
import com.litemusic.player.PlayerService

/**
 * 迷你播放条控制器：主页面（底栏上方）与次级页面（屏幕底部）共用。
 * 宿主 Activity 需在 onStart/onStop 调用 onStart()/onStop()。
 */
class MiniPlayerController(activity: Activity, root: View) : PlayerService.Listener {

    private val card: MaterialCardView = root.findViewById(R.id.mini_player)
    private val content: LinearLayout = root.findViewById(R.id.mini_content)
    private val textColumn: LinearLayout = root.findViewById(R.id.mini_text)
    private val cover: ImageView = root.findViewById(R.id.mini_cover)
    private val title: TextView = root.findViewById(R.id.mini_title)
    private val artist: TextView = root.findViewById(R.id.mini_artist)
    private val play: MaterialButton = root.findViewById(R.id.mini_play)
    private val prev: MaterialButton = root.findViewById(R.id.mini_prev)
    private val next: MaterialButton = root.findViewById(R.id.mini_next)
    private val progressRing: MiniProgressRing = root.findViewById(R.id.mini_progress)

    private val decelerate = PathInterpolator(0.05f, 0.7f, 0.1f, 1f)
    /** 已显示的播放态，避免重复设置图标 */
    private var shownPlaying: Boolean? = null

    init {
        adaptForLandscape()
        card.setOnClickListener {
            activity.startActivity(Intent(activity, PlayerActivity::class.java))
        }
        play.setOnClickListener { PlayerService.instance?.togglePlay() }
        prev.setOnClickListener { PlayerService.instance?.prev() }
        next.setOnClickListener { PlayerService.instance?.next() }
    }

    /**
     * 横屏：迷你播放条按内容收窄并贴右下角；竖屏保持原有的整行宽度与位置。
     * 标题列原本用 weight 撑满整行，横屏改为 wrap_content 后宽度随文字自适应。
     */
    private fun adaptForLandscape() {
        if (card.resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE) return

        (card.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
            lp.width = FrameLayout.LayoutParams.WRAP_CONTENT
            lp.gravity = Gravity.END or Gravity.BOTTOM
            card.layoutParams = lp
        }
        (content.layoutParams as? LinearLayout.LayoutParams)?.let { lp ->
            lp.width = LinearLayout.LayoutParams.WRAP_CONTENT
            content.layoutParams = lp
        }
        (textColumn.layoutParams as? LinearLayout.LayoutParams)?.let { lp ->
            lp.width = LinearLayout.LayoutParams.WRAP_CONTENT
            lp.weight = 0f
            textColumn.layoutParams = lp
        }
        val maxTextWidth = (200 * card.resources.displayMetrics.density).toInt()
        title.maxWidth = maxTextWidth
        artist.maxWidth = maxTextWidth
    }

    fun onStart() {
        PlayerService.listeners.add(this)
        sync()
    }

    fun onStop() {
        PlayerService.listeners.remove(this)
    }

    private fun sync() {
        val s = PlayerService.instance
        val song = s?.currentSong
        if (song == null) {
            // 无歌时同时重置图标态，避免下次显示同一状态时不刷新
            shownPlaying = null
            card.visibility = View.GONE
            return
        }
        if (card.visibility != View.VISIBLE) {
            card.visibility = View.VISIBLE
            // M3 emphasized decelerate：向上浮入
            card.animate().cancel()
            card.alpha = 0f
            card.translationY = 24f * card.resources.displayMetrics.density
            card.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(250)
                .setInterpolator(decelerate)
                .start()
        }
        title.text = song.name
        artist.text = song.artists
        ImageLoader.load(cover, song.cover, 120)
        applyPlayIcon(s.playing)
        // 切歌/回到前台/拖动进度后同步真实进度（不补间，立即落在正确位置）
        progressRing.setProgress(
            if (s.duration > 0) s.position.toFloat() / s.duration else 0f,
            animated = false,
        )
    }

    private fun applyPlayIcon(playing: Boolean) {
        if (shownPlaying == playing) return
        shownPlaying = playing
        play.setIconResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
    }

    override fun onSongChanged(song: Song?) {
        (card.context as? Activity)?.runOnUiThread { sync() }
    }

    override fun onPlayStateChanged(playing: Boolean) {
        (card.context as? Activity)?.runOnUiThread { sync() }
    }

    /** 进度每 500ms 回调一次；自动起播不一定触发 onPlayStateChanged，这里兜底同步图标与进度环 */
    override fun onProgress(positionMs: Int, durationMs: Int) {
        val s = PlayerService.instance ?: return
        (card.context as? Activity)?.runOnUiThread {
            applyPlayIcon(s.playing)
            progressRing.setProgress(
                if (durationMs > 0) positionMs.toFloat() / durationMs else 0f,
            )
        }
    }
}
