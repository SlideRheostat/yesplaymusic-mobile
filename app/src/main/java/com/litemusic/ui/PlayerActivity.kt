package com.litemusic.ui

import android.animation.ValueAnimator
import android.os.Bundle
import android.util.LruCache
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.animation.Interpolator
import android.view.animation.PathInterpolator
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.litemusic.Api
import com.litemusic.ImageLoader
import com.litemusic.LyricLine
import com.litemusic.Prefs
import com.litemusic.R
import com.litemusic.Song
import com.litemusic.player.PlayerService

class PlayerActivity : AppCompatActivity(), PlayerService.Listener {

    companion object {
        /** 歌词内存缓存（LRU，最多 50 首；线程安全），旋转屏幕/重进播放页免重复请求 */
        private val lyricCache = object : LruCache<Long, List<LyricLine>>(50) {}
    }

    private lateinit var cover: ImageView
    private lateinit var lyricScroll: ScrollView
    private lateinit var lyricContainer: LinearLayout
    private lateinit var songName: TextView
    private lateinit var songArtist: TextView
    private lateinit var seek: LinearProgressIndicator
    private lateinit var seekTouch: View
    private lateinit var timeCurrent: TextView
    private lateinit var timeTotal: TextView
    private lateinit var btnPlay: ImageButton
    private lateinit var btnMode: ImageButton
    private lateinit var btnLike: ImageButton
    private lateinit var btnTrans: TextView

    private var lyrics: List<LyricLine> = emptyList()
    private val lyricRows = ArrayList<LinearLayout>()
    private var lyricScrollAnim: ValueAnimator? = null
    private var seekAnim: ValueAnimator? = null
    private var lyricShown = false
    private var currentLyricIndex = -1
    private var userSeeking = false
    private var songId: Long = -1
    private var playIconState: Boolean? = null
    private var lyricLoading = false
    private lateinit var lyricEmpty: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)

        cover = findViewById(R.id.cover)
        lyricScroll = findViewById(R.id.lyric_scroll)
        lyricContainer = findViewById(R.id.lyric_container)
        lyricEmpty = findViewById(R.id.lyric_empty)
        songName = findViewById(R.id.song_name)
        songArtist = findViewById(R.id.song_artist)
        seek = findViewById(R.id.seek)
        seekTouch = findViewById(R.id.seek_touch)
        timeCurrent = findViewById(R.id.time_current)
        timeTotal = findViewById(R.id.time_total)
        btnPlay = findViewById(R.id.btn_play)
        btnMode = findViewById(R.id.btn_mode)
        btnLike = findViewById(R.id.btn_like)
        btnTrans = findViewById(R.id.btn_trans_toggle)

        findViewById<ImageButton>(R.id.btn_back).setOnClickListener { finish() }
        btnPlay.setOnClickListener { PlayerService.instance?.togglePlay() }
        findViewById<ImageButton>(R.id.btn_prev).setOnClickListener { PlayerService.instance?.prev() }
        findViewById<ImageButton>(R.id.btn_next).setOnClickListener { PlayerService.instance?.next() }
        // 点击封面切换歌词界面（仅竖屏）；歌词界面点空白处返回
        cover.setOnClickListener { if (!isLand) toggleLyric() }
        btnTrans.setOnClickListener { onTransClick() }
        updateTransBtn()
        syncLyricVisibility()
        // 点击歌手名进入歌手主页（多歌手时弹出选择）
        songArtist.setOnClickListener {
            PlayerService.instance?.currentSong?.let { ArtistActivity.openFromSong(this, it) }
        }
        btnMode.setOnClickListener {
            val s = PlayerService.instance ?: return@setOnClickListener
            updateModeIcon(s.cycleMode())
            bounce(btnMode)
        }
        btnLike.setOnClickListener { toggleLike() }

        // M3 进展指标 + 透明触摸层：拖动时实时预览进度与时间文案，松手后跳转播放位置
        seekTouch.setOnTouchListener { v, e ->
            val max = seek.max.coerceAtLeast(1)
            val frac = (e.x / v.width.coerceAtLeast(1)).coerceIn(0f, 1f)
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    userSeeking = true
                    seekAnim?.cancel()
                    seek.setProgressCompat((frac * max).toInt(), false)
                    val dur = PlayerService.instance?.duration ?: 0
                    timeCurrent.text = formatTime((frac * dur).toLong())
                    true
                }
                MotionEvent.ACTION_UP -> {
                    seek.setProgressCompat((frac * max).toInt(), false)
                    userSeeking = false
                    PlayerService.instance?.let { s -> s.seekTo((frac * s.duration).toInt()) }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    userSeeking = false
                    true
                }
                else -> false
            }
        }
    }

    override fun onStart() {
        super.onStart()
        PlayerService.listeners.add(this)
        val s = PlayerService.instance
        if (s == null) {
            finish()
            return
        }
        bindSong(s.currentSong)
        syncPlaybackPosition(s)
        setPlayIcon(s.playing, animate = false)
        updateModeIcon(s.mode)
    }

    override fun onStop() {
        PlayerService.listeners.remove(this)
        super.onStop()
    }

    /** 暂停时没有进度回调，进入页面需要主动同步一次已缓存的播放位置 */
    private fun syncPlaybackPosition(s: PlayerService) {
        val pos = s.position
        timeCurrent.text = formatTime(pos.toLong())
        if (s.duration > 0) {
            val max = seek.max.coerceAtLeast(1)
            val target = (pos.toLong() * max / s.duration).toInt().coerceIn(0, max)
            seek.setProgressCompat(target, false)
        }
    }

    private fun bindSong(song: Song?) {
        if (song == null) return
        songName.text = song.name
        songArtist.text = song.artists
        ImageLoader.load(cover, song.cover, 600)
        seekAnim?.cancel()
        seek.setProgressCompat(0, false)
        timeTotal.text = formatTime(song.duration)
        btnLike.setImageResource(
            if (LikedStore.contains(song.id)) R.drawable.ic_heart_filled else R.drawable.ic_heart
        )
        if (song.id != songId) {
            songId = song.id
            loadLyric(song.id)
        }
    }

    private fun loadLyric(id: Long) {
        lyrics = emptyList()
        currentLyricIndex = -1
        lyricLoading = true
        buildLyricRows()
        updateLyricEmpty()
        // 命中内存缓存：旋转屏幕/重进播放页不再重复请求
        lyricCache.get(id)?.let {
            lyricLoading = false
            applyLyrics(it)
            return
        }
        Api.lyric(id) { r ->
            if (isFinishing || isDestroyed) return@lyric
            lyricLoading = false
            r.onSuccess {
                lyricCache.put(id, it)
                applyLyrics(it)
            }.onFailure {
                syncLyricVisibility()
            }
        }
    }

    private fun applyLyrics(list: List<LyricLine>) {
        lyrics = list
        buildLyricRows()
        applyTransFlags()
        syncLyricVisibility()
    }

    /** 整表构建歌词行（源码 lyrics.vue 同款结构：行=原文+译文，active 放大、其余缩小） */
    private fun buildLyricRows() {
        lyricScrollAnim?.cancel()
        lyricContainer.removeAllViews()
        lyricRows.clear()
        // 去掉首尾留白：首行贴顶、末行贴底（触顶/触底），横竖屏行为一致
        lyricContainer.setPadding(0, 0, 0, 0)
        currentLyricIndex = -1
        val density = resources.displayMetrics.density
        val padH = (18 * density).toInt()
        val padV = (12 * density).toInt()
        val mainColor = themeColor(com.google.android.material.R.attr.colorOnSurface)
        val transColor = themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant)
        for (line in lyrics) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.VERTICAL
            row.gravity = Gravity.CENTER_HORIZONTAL
            row.setPadding(padH, padV, padH, padV)
            row.alpha = 0.58f
            row.scaleX = 0.95f
            row.scaleY = 0.95f
            val main = TextView(this)
            main.gravity = Gravity.CENTER
            main.maxLines = 2
            main.ellipsize = android.text.TextUtils.TruncateAt.END
            main.textSize = Prefs.lyricSize.toFloat()
            main.setTextColor(mainColor)
            main.text = line.text
            val trans = TextView(this)
            trans.gravity = Gravity.CENTER
            trans.maxLines = 2
            trans.textSize = Prefs.lyricSize * 0.925f
            trans.setTextColor(transColor)
            row.addView(main, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            row.addView(trans, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            val seekListener = View.OnClickListener {
                PlayerService.instance?.seekTo(line.time.toInt())
            }
            main.setOnClickListener(seekListener)
            trans.setOnClickListener(seekListener)
            // 行内空白（文字两侧）点击返回封面
            row.setOnClickListener { if (!isLand && lyricShown) toggleLyric() }
            lyricRows.add(row)
            lyricContainer.addView(row, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
    }

    private val isLand: Boolean
        get() = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE

    private fun updateLyricEmpty() {
        // 歌词模式下无歌词才提示；此时 lyricScroll 因无内容而 GONE，不能以它是否可见为判据
        val lyricMode = isLand || lyricShown
        lyricEmpty.visibility =
            if (lyricMode && !lyricLoading && lyrics.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun toggleLyric() {
        if (isLand) return
        lyricShown = !lyricShown
        syncLyricVisibility()
    }

    /** 竖屏：封面/歌词二选一；横屏：封面、歌词、进度同屏。无翻译/注音的歌曲隐藏"译" */
    private fun syncLyricVisibility() {
        val hasAux = lyrics.any { it.trans != null || it.roma != null }
        if (isLand) {
            cover.visibility = View.VISIBLE
            lyricScroll.visibility = if (lyrics.isNotEmpty()) View.VISIBLE else View.GONE
            songName.visibility = View.VISIBLE
            songArtist.visibility = View.VISIBLE
            btnTrans.visibility = if (hasAux) View.VISIBLE else View.GONE
        } else {
            cover.visibility = if (lyricShown) View.GONE else View.VISIBLE
            lyricScroll.visibility = if (lyricShown && lyrics.isNotEmpty()) View.VISIBLE else View.GONE
            songName.visibility = if (lyricShown) View.GONE else View.VISIBLE
            songArtist.visibility = if (lyricShown) View.GONE else View.VISIBLE
            btnTrans.visibility = if (lyricShown && hasAux) View.VISIBLE else View.GONE
        }
        updateLyricEmpty()
    }

    /** 日语歌：翻译与注音二选一；非日语歌：直接切换中文翻译显示 */
    private fun onTransClick() {
        val hasTrans = lyrics.any { it.trans != null }
        val hasRoma = lyrics.any { it.roma != null }
        if (!hasRoma) {
            if (!hasTrans) return
            Prefs.auxMode =
                if (Prefs.auxMode == Prefs.AUX_TRANS) Prefs.AUX_NONE else Prefs.AUX_TRANS
            applyTransFlags()
            return
        }

        val modes = ArrayList<Int>(3)
        val labels = ArrayList<String>(3)
        modes.add(Prefs.AUX_NONE)
        labels.add(getString(R.string.opt_none))
        if (hasTrans) {
            modes.add(Prefs.AUX_TRANS)
            labels.add(getString(R.string.opt_trans))
        }
        if (hasRoma) {
            modes.add(Prefs.AUX_ROMA)
            labels.add(getString(R.string.opt_roma))
        }
        if (labels.size <= 1) return

        val current = modes.indexOf(Prefs.auxMode).coerceAtLeast(0)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.trans_dialog_title)
            .setSingleChoiceItems(labels.toTypedArray(), current) { dialog, which ->
                Prefs.auxMode = modes[which]
                applyTransFlags()
                dialog.dismiss()
            }
            .show()
    }

    private fun applyTransFlags() {
        val mode = Prefs.auxMode
        for (i in lyrics.indices) {
            val line = lyrics[i]
            val row = lyricRows.getOrNull(i) ?: continue
            val trans = row.getChildAt(1) as TextView
            val text = when (mode) {
                Prefs.AUX_TRANS -> line.trans
                Prefs.AUX_ROMA -> line.roma
                else -> null
            }
            if (text.isNullOrEmpty()) {
                trans.visibility = View.GONE
            } else {
                trans.visibility = View.VISIBLE
                trans.text = text
            }
        }
        updateTransBtn()
    }

    private fun updateTransBtn() {
        val hasTrans = lyrics.any { it.trans != null }
        val hasRoma = lyrics.any { it.roma != null }
        val on = when (Prefs.auxMode) {
            Prefs.AUX_TRANS -> hasTrans
            Prefs.AUX_ROMA -> hasRoma
            else -> false
        }
        btnTrans.alpha = if (on) 1f else 0.45f
    }

    private fun toggleLike() {
        val s = PlayerService.instance ?: return
        val song = s.currentSong ?: return
        if (!Prefs.loggedIn) {
            Toast.makeText(this, R.string.not_logged_in, Toast.LENGTH_SHORT).show()
            return
        }
        val now = !LikedStore.contains(song.id)
        Api.like(song.id, now) { r ->
            if (r.isSuccess) {
                LikedStore.setLiked(song.id, now)
                // 回调期间可能已切歌/退出，只有仍展示这首歌时才更新按钮状态
                val s2 = PlayerService.instance
                if (!isFinishing && !isDestroyed && s2?.currentSong?.id == song.id) {
                    btnLike.setImageResource(if (now) R.drawable.ic_heart_filled else R.drawable.ic_heart)
                    bounce(btnLike)
                }
            } else {
                Toast.makeText(this, r.exceptionOrNull()?.message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun updateModeIcon(mode: Int) {
        btnMode.setImageResource(
            when (mode) {
                PlayerService.MODE_LOOP_ONE -> R.drawable.ic_repeat_one
                PlayerService.MODE_SHUFFLE -> R.drawable.ic_shuffle
                else -> R.drawable.ic_repeat
            }
        )
    }

    // ---------- Listener ----------

    override fun onSongChanged(song: Song?) {
        runOnUiThread { bindSong(song) }
    }

    override fun onPlayStateChanged(playing: Boolean) {
        runOnUiThread {
            setPlayIcon(playing, animate = true)
            if (!playing) PlayerService.instance?.let { syncPlaybackPosition(it) }
        }
    }

    /** M3 图标切换：先 90ms 加速淡出，换图后 180ms 减速淡入 */
    private fun setPlayIcon(playing: Boolean, animate: Boolean) {
        if (playIconState == playing) return
        playIconState = playing
        val res = if (playing) R.drawable.ic_pause else R.drawable.ic_play
        btnPlay.animate().cancel()
        if (!animate) {
            btnPlay.setImageResource(res)
            btnPlay.alpha = 1f
            return
        }
        btnPlay.animate().alpha(0f).setDuration(90)
            .setInterpolator(emphasizedAccelerate())
            .withEndAction {
                btnPlay.setImageResource(res)
                btnPlay.animate().alpha(1f).setDuration(180)
                    .setInterpolator(emphasizedDecelerate()).start()
            }.start()
    }

    /** M3 按压反馈：控件自 0.82 缩放回弹到原尺寸 */
    private fun bounce(v: View) {
        v.animate().cancel()
        v.scaleX = 0.82f
        v.scaleY = 0.82f
        v.animate().scaleX(1f).scaleY(1f).setDuration(250)
            .setInterpolator(emphasizedDecelerate()).start()
    }

    override fun onProgress(positionMs: Int, durationMs: Int) {
        runOnUiThread {
            if (userSeeking) return@runOnUiThread
            timeCurrent.text = formatTime(positionMs.toLong())
            if (durationMs > 0) {
                val max = seek.max.coerceAtLeast(1)
                val target = (positionMs.toLong() * max / durationMs).toInt().coerceIn(0, max)
                animateSeek(target)
            }
            // 自动起播不一定回调 onPlayStateChanged，随进度兜底同步图标
            PlayerService.instance?.let { if (it.playing != playIconState) setPlayIcon(it.playing, false) }
            // 横屏歌词常显，同样需要追踪高亮
            if (lyricShown || isLand) updateLyricPosition(positionMs)
        }
    }

    /** 进度条连贯前进：在两次 500ms 进度回调之间做线性补间，避免"一节一节"的段落感 */
    private fun animateSeek(target: Int) {
        val from = seek.progress
        if (from == target) return
        seekAnim?.cancel()
        seekAnim = ValueAnimator.ofInt(from, target).apply {
            duration = 500
            interpolator = linearInterpolator()
            addUpdateListener { seek.setProgressCompat(it.animatedValue as Int, false) }
            start()
        }
    }

    private fun updateLyricPosition(pos: Int) {
        if (lyrics.isEmpty()) return
        var lo = 0
        var hi = lyrics.size - 1
        var idx = -1
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            if (lyrics[mid].time <= pos) {
                idx = mid
                lo = mid + 1
            } else hi = mid - 1
        }
        if (idx != currentLyricIndex) {
            val prev = currentLyricIndex
            currentLyricIndex = idx
            setLineActive(prev, false)
            setLineActive(idx, true)
            scrollToLine(idx)
        }
    }

    /** 高亮：active 行放大到 1.0 / 透明度 0.88，其余缩到 0.95 / 0.58（源码同款曲线） */
    private fun setLineActive(idx: Int, active: Boolean) {
        val row = lyricRows.getOrNull(idx) ?: return
        val s = if (active) 1f else 0.95f
        row.animate()
            .scaleX(s).scaleY(s)
            .alpha(if (active) 0.88f else 0.58f)
            .setDuration(300)
            .setInterpolator(emphasizedDecelerate())
            .start()
    }

    /** 当前行滚动到视口中央；目标限制在 [0, 最大可滚距离]，横竖屏一致的触顶/触底行为 */
    private fun scrollToLine(idx: Int) {
        val row = lyricRows.getOrNull(idx) ?: return
        if (lyricScroll.height <= 0 || lyricContainer.height <= 0) return
        val target = row.top + row.height / 2 - lyricScroll.height / 2
        val maxScroll = (lyricContainer.height - lyricScroll.height).coerceAtLeast(0)
        val clamped = target.coerceIn(0, maxScroll)
        val from = lyricScroll.scrollY
        if (Math.abs(clamped - from) < 2) return
        lyricScrollAnim?.cancel()
        lyricScrollAnim = ValueAnimator.ofInt(from, clamped).apply {
            duration = 500
            interpolator = lyricInterpolator()
            addUpdateListener { lyricScroll.scrollTo(0, it.animatedValue as Int) }
            start()
        }
    }

    /** 歌词滚动沿用原有曲线（源码同款缓动） */
    private fun lyricInterpolator(): Interpolator =
        PathInterpolator(0.25f, 0.46f, 0.45f, 0.94f)

    /** 线性插值（匀速，用于进度条连贯补间） */
    private fun linearInterpolator(): Interpolator =
        PathInterpolator(0f, 0f, 1f, 1f)

    /** M3 Emphasized decelerate（长时动效入场曲线） */
    private fun emphasizedDecelerate(): Interpolator =
        PathInterpolator(0.05f, 0.7f, 0.1f, 1f)

    /** M3 Emphasized accelerate（长时动效退场曲线） */
    private fun emphasizedAccelerate(): Interpolator =
        PathInterpolator(0.3f, 0f, 0.8f, 0.15f)

    private fun themeColor(attr: Int): Int {
        val tv = TypedValue()
        theme.resolveAttribute(attr, tv, true)
        return tv.data
    }

    private fun formatTime(ms: Long): String {
        val total = ms / 1000
        return String.format("%02d:%02d", total / 60, total % 60)
    }
}
