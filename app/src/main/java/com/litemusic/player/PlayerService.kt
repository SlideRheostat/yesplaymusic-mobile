package com.litemusic.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.widget.Toast
import com.litemusic.Api
import com.litemusic.AudioCache
import com.litemusic.ImageLoader
import com.litemusic.Prefs
import com.litemusic.R
import com.litemusic.Song
import com.litemusic.ui.PlayerActivity

/**
 * 后台播放服务：MediaPlayer + 前台通知 + 播放队列。
 * UI 通过静态 instance 与监听器交互（单进程，简单可靠）。
 */
class PlayerService : Service() {

    interface Listener {
        fun onSongChanged(song: Song?)
        fun onPlayStateChanged(playing: Boolean)
        fun onProgress(positionMs: Int, durationMs: Int)
    }

    companion object {
        const val ACTION_PLAY = "litemusic.PLAY"
        const val ACTION_PAUSE = "litemusic.PAUSE"
        const val ACTION_PREV = "litemusic.PREV"
        const val ACTION_NEXT = "litemusic.NEXT"
        const val ACTION_STOP = "litemusic.STOP"
        const val MODE_ORDER = 0
        const val MODE_LOOP_ONE = 1
        const val MODE_SHUFFLE = 2

        var instance: PlayerService? = null
        val listeners = ArrayList<Listener>()

        fun ensureStarted(ctx: Context) {
            val i = Intent(ctx, PlayerService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(i)
            } else {
                ctx.startService(i)
            }
        }

        @JvmStatic
        private var pending: Pair<List<Song>, Int>? = null

        /** 待播放队列是否来自“已缓存的歌”（优先任意音质等级的本地文件） */
        @JvmStatic
        private var pendingCached = false

        /** UI 统一入口：播放某列表的第 index 首（服务未起则先起再播） */
        fun play(ctx: Context, songs: List<Song>, index: Int) {
            val s = instance
            if (s != null) {
                s.setQueue(songs, index)
            } else {
                pendingCached = false
                pending = songs to index
                ensureStarted(ctx)
            }
        }

        /** 播放缓存列表：优先本地文件，没有对应文件时才走网络补下 */
        fun playCached(ctx: Context, songs: List<Song>, index: Int) {
            val s = instance
            if (s != null) {
                s.setQueue(songs, index, cached = true)
            } else {
                pendingCached = true
                pending = songs to index
                ensureStarted(ctx)
            }
        }
    }

    private var mp: MediaPlayer? = null
    private val handler = Handler(Looper.getMainLooper())
    private val audio by lazy { getSystemService(Context.AUDIO_SERVICE) as AudioManager }

    var queue: List<Song> = emptyList()
        private set
    var index: Int = -1
        private set
    var mode: Int = Prefs.playMode.coerceIn(MODE_ORDER, MODE_SHUFFLE)
        private set
    var playing: Boolean = false
        private set
    var duration: Int = 0
        private set

    /**
     * 当前播放位置（毫秒），供迷你播放条进度环等 UI 读取。
     * 暂停/停止时直接返回缓存值，避免在长时间暂停后从 UI 线程调用
     * MediaPlayer.currentPosition 阻塞主线程导致界面卡死。
     */
    private var lastPosition: Int = 0
    val position: Int
        get() = if (playing) {
            try {
                mp?.currentPosition?.also { lastPosition = it } ?: lastPosition
            } catch (e: Exception) {
                lastPosition
            }
        } else {
            lastPosition
        }

    val currentSong: Song? get() = queue.getOrNull(index)

    private var loadToken = 0
    private var lastNotifPlaying: Boolean? = null

    /** 当前播放会话是否已上报听歌打卡，避免切歌与播完回调重复上报 */
    private var scrobbled = false

    /** 当前队列来自“已缓存的歌”：优先播放任意音质等级的本地缓存文件 */
    private var preferCached = false

    /**
     * 连续播放失败计数。达到队列长度后停止跳歌，
     * 防止整队不可播（VIP/无版权/接口异常）时后台无限循环请求、跑掉巨额流量。
     * 任一首成功开播即清零。
     */
    private var consecutiveFailures = 0

    // ---- 系统媒体会话（锁屏/通知栏/耳机按键统一控制；仅 API 21+，实例见 PlayerCompat21） ----
    private var session21: Any? = null
    private var sessionArt: Bitmap? = null
    private val nm by lazy { getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager }

    private val ticker = object : Runnable {
        override fun run() {
            val p = mp
            if (p != null && playing) {
                val pos = try { p.currentPosition } catch (e: Exception) { lastPosition }
                lastPosition = pos
                for (l in ArrayList(listeners)) l.onProgress(pos, duration)
                handler.postDelayed(this, 500)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        createChannel()
        initMediaSession()
        startForegroundCompat(buildNotification())
        registerNoisy()
        pending?.let {
            pending = null
            setQueue(it.first, it.second, pendingCached)
            pendingCached = false
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY -> togglePlay()
            ACTION_PAUSE -> pause()
            ACTION_PREV -> prev()
            ACTION_NEXT -> next()
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scrobbleCurrent(completed = false)
        handler.removeCallbacks(ticker)
        try { unregisterReceiver(noisyReceiver) } catch (_: Exception) {}
        mp?.release()
        mp = null
        try { abandonAudioFocus() } catch (_: Exception) {}
        val s = session21
        if (s != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            PlayerCompat21.release(s)
        }
        session21 = null
        instance = null
        super.onDestroy()
    }

    // ---------- 对外播放控制 ----------

    fun setQueue(songs: List<Song>, startIndex: Int, cached: Boolean = false) {
        if (songs.isEmpty()) return
        // 新队列替换当前播放前，先结算上一首的播放记录
        scrobbleCurrent(completed = false)
        queue = songs
        preferCached = cached
        consecutiveFailures = 0
        val i = startIndex.coerceIn(0, songs.size - 1)
        index = i
        playCurrent()
    }

    fun togglePlay() {
        if (mp == null || duration == 0) {
            if (currentSong != null) {
                // 用户主动重新播放：给队列再一次机会
                consecutiveFailures = 0
                playCurrent()
            }
            return
        }
        if (playing) pause() else resume()
    }

    fun resume() {
        val p = mp ?: return
        try {
            p.start()
            playing = true
            notifyState()
            startTicker()
        } catch (e: Exception) {
        }
    }

    fun pause() {
        val p = mp ?: return
        try {
            p.pause()
            playing = false
            // 立即停止进度轮询；暂停后不再触碰 MediaPlayer
            handler.removeCallbacks(ticker)
            notifyState()
        } catch (e: Exception) {
        }
    }

    fun seekTo(ms: Int) {
        val p = mp ?: return
        try {
            p.seekTo(ms)
            lastPosition = ms
            updateSessionState()
            for (l in ArrayList(listeners)) l.onProgress(ms, duration)
        } catch (e: Exception) {
        }
    }

    fun next() {
        if (queue.isEmpty()) return
        scrobbleCurrent(completed = false)
        index = when (mode) {
            MODE_SHUFFLE -> randomIndex()
            else -> (index + 1) % queue.size
        }
        playCurrent()
    }

    fun prev() {
        if (queue.isEmpty()) return
        scrobbleCurrent(completed = false)
        index = when (mode) {
            MODE_SHUFFLE -> randomIndex()
            else -> if (index - 1 < 0) queue.size - 1 else index - 1
        }
        playCurrent()
    }

    fun cycleMode(): Int {
        mode = (mode + 1) % 3
        Prefs.playMode = mode
        return mode
    }

    // ---------- 内部 ----------

    private fun randomIndex(): Int {
        if (queue.size <= 1) return 0
        var r = index
        while (r == index) r = (Math.random() * queue.size).toInt()
        return r
    }

    private fun playCurrent() {
        val song = currentSong ?: return
        val token = ++loadToken
        scrobbled = false
        playing = false
        duration = 0
        lastPosition = 0
        // 强制刷新通知：暂停状态切歌时 lastNotifPlaying 未变化，不重置会保留旧歌名
        lastNotifPlaying = null
        handler.removeCallbacks(ticker)
        mp?.let { old ->
            mp = null
            handler.post { try { old.release() } catch (_: Exception) {} }
        }
        for (l in ArrayList(listeners)) l.onSongChanged(song)
        notifyState()
        sessionArt = null
        updateSessionMeta()
        ImageLoader.bitmap(song.cover, 400) { bmp ->
            if (bmp != null && currentSong?.id == song.id) {
                sessionArt = bmp
                updateSessionMeta()
            }
        }

        // 命中音频缓存：零流量直接本地播放，不再请求播放地址；
        // 缓存列表来的队列允许使用任意音质等级的本地文件（离线可播）
        val local = if (preferCached) AudioCache.cachedAny(song.id) else AudioCache.cached(song.id)
        local?.let { file ->
            beginPlayback(file.absolutePath, song, token)
            return
        }

        Api.songUrl(song.id) { r ->
            if (token != loadToken) return@songUrl
            val url = r.getOrNull()
            if (url.isNullOrEmpty()) {
                val msg = if (song.needVip && !Prefs.loggedIn) getString(R.string.vip_login_required)
                else if (song.needVip) getString(R.string.vip_no_permission)
                else null
                failCurrent(song, token, msg)
                return@songUrl
            }
            // 先下载入磁盘缓存再播放：同一首歌此后永久零流量，
            // 也避免失败循环时对同一 URL 反复流式下载
            AudioCache.fetch(song, url, { token != loadToken }) { file ->
                handler.post {
                    if (token != loadToken) return@post
                    if (file == null) {
                        failCurrent(song, token, null)
                    } else {
                        beginPlayback(file.absolutePath, song, token)
                    }
                }
            }
        }
    }

    /**
     * 上报当前歌曲的播放记录（最近播放 + 听歌排行）。
     * 切歌、退出服务时传 completed=false 使用当前播放位置；自然播完传 true 使用整首时长。
     * 同一首歌只上报一次，失败由 ApiClient 静默处理。
     */
    private fun scrobbleCurrent(completed: Boolean) {
        if (!Prefs.loggedIn || scrobbled) return
        val song = currentSong ?: return
        val seconds = if (completed) {
            (if (duration > 0) duration else song.duration.toInt()) / 1000
        } else {
            position / 1000
        }
        if (seconds < 1) return
        scrobbled = true
        Api.scrobble(song.id, seconds)
    }

    /**
     * 播放失败统一处理：未达上限则延时跳下一首；
     * 连续失败达到队列长度（整队都不可播）时停止，杜绝后台死循环跑流量。
     */
    private fun failCurrent(song: Song, token: Int, reason: String?) {
        consecutiveFailures++
        if (consecutiveFailures >= queue.size.coerceAtLeast(1)) {
            Toast.makeText(this, R.string.play_fail_all, Toast.LENGTH_SHORT).show()
            playing = false
            notifyState()
            return
        }
        Toast.makeText(this, reason ?: getString(R.string.play_skip), Toast.LENGTH_SHORT).show()
        // 失败歌曲不留在索引上，直接顺延；由 next() 统一切歌，单曲循环下也不会重复递进
        handler.postDelayed({ if (token == loadToken) next() }, 1500)
    }

    private fun beginPlayback(path: String, song: Song, token: Int) {
        val p = MediaPlayer()
        mp = p
        try {
            p.setAudioStreamType(AudioManager.STREAM_MUSIC)
            p.setOnPreparedListener {
                if (token != loadToken) return@setOnPreparedListener
                if (!requestAudioFocus()) {
                    // 焦点被通话等占用，稍后用户可手动播放
                    playing = false
                    notifyState()
                    return@setOnPreparedListener
                }
                it.start()
                duration = it.duration
                lastPosition = 0
                playing = true
                consecutiveFailures = 0
                notifyState()
                startTicker()
                updateNotif()
            }
            p.setOnCompletionListener {
                if (token != loadToken) return@setOnCompletionListener
                scrobbleCurrent(completed = true)
                if (mode == MODE_LOOP_ONE) {
                    // 单曲循环：本次已按整首时长上报，重新开始新一轮
                    scrobbled = false
                    lastPosition = 0
                    try { p.seekTo(0); p.start() } catch (_: Exception) {}
                } else {
                    next()
                }
            }
            p.setOnErrorListener { _, what, extra ->
                if (token != loadToken) return@setOnErrorListener true
                Toast.makeText(this, getString(R.string.play_error, what, extra), Toast.LENGTH_SHORT).show()
                playing = false
                notifyState()
                true
            }
            p.setDataSource(path)
            p.prepareAsync()
        } catch (e: Exception) {
            Toast.makeText(this, R.string.play_cannot_open, Toast.LENGTH_SHORT).show()
            try { p.release() } catch (_: Exception) {}
            if (mp === p) mp = null
            playing = false
            notifyState()
        }
    }

    private fun notifyState() {
        for (l in ArrayList(listeners)) l.onPlayStateChanged(playing)
        updateSessionState()
        updateNotif()
    }

    // ---------- 系统媒体会话 ----------

    private fun initMediaSession() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            session21 = PlayerCompat21.createSession(this)
        }
    }

    private fun updateSessionMeta() {
        val s = session21 ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) PlayerCompat21.updateMeta(this, s)
    }

    private fun updateSessionState() {
        val s = session21 ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) PlayerCompat21.updateState(this, s)
    }

    private fun startTicker() {
        handler.removeCallbacks(ticker)
        handler.postDelayed(ticker, 500)
    }

    // ---------- 通知栏 ----------

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel("playback", getString(R.string.notification_channel_playback), NotificationManager.IMPORTANCE_LOW)
            ch.setShowBadge(false)
            nm.createNotificationChannel(ch)
        }
    }

    private fun startForegroundCompat(n: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(1, n)
        }
    }

    private fun pi(action: String): PendingIntent {
        val i = Intent(this, PlayerService::class.java).setAction(action)
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags = flags or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getService(this, action.hashCode(), i, flags)
    }

    private fun contentIntent(): PendingIntent {
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags = flags or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getActivity(this, 0, Intent(this, PlayerActivity::class.java), flags)
    }

    /** 基础通知（API 1 可用字段）；21+ 的 MediaStyle/按钮由 PlayerCompat21 追加 */
    internal fun compatBaseBuilder(): Notification.Builder {
        val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(this, "playback")
        else
            @Suppress("DEPRECATION") Notification.Builder(this)
        b.setSmallIcon(R.drawable.ic_music_note)
            .setContentIntent(contentIntent())
            .setOngoing(true)
        return b
    }

    private fun buildNotification(): Notification {
        val song = currentSong
        val builder = compatBaseBuilder()
            .setContentTitle(song?.name ?: "YesPlayMusic")
            .setContentText(song?.let { "${it.artists} - ${it.album}" } ?: getString(R.string.notification_open_tip))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            PlayerCompat21.decorate(this, session21, builder)
        }
        return builder.build()
    }

    private fun updateNotif(large: Bitmap? = null) {
        if (lastNotifPlaying == playing && large == null) return
        lastNotifPlaying = playing
        if (large != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            nm.notify(1, PlayerCompat21.buildWithArt(this, session21, large))
            return
        }
        nm.notify(1, buildNotification())
    }

    // ---- PlayerCompat21 桥接（不暴露 API 21+ 类型） ----

    internal fun piCompat(action: String): PendingIntent = pi(action)
    internal fun compatArt(): Bitmap? = sessionArt
    internal fun compatIsInstance(): Boolean = instance === this
    internal fun compatNotifWithArt(bmp: Bitmap) { updateNotif(bmp) }
    internal fun compatPosition(): Long = position.toLong()
    internal fun compatPlay() {
        if (playing) return
        if (mp != null && duration > 0) resume()
        else if (currentSong != null) {
            // 用户主动重新播放：给队列再一次机会
            consecutiveFailures = 0
            playCurrent()
        }
    }
    internal fun compatPause() { if (playing) pause() }
    internal fun compatNext() { next() }
    internal fun compatPrev() { prev() }
    internal fun compatSeek(ms: Int) { seekTo(ms) }
    internal fun compatStop() { stopSelf() }

    // ---------- 杂项 ----------

    /**
     * 焦点监听必须是单例：若每次请求都传新 lambda，切歌时系统会向旧 lambda 发
     * LOSS_TRANSIENT，导致刚 start 的新播放器被立即 pause。
     */
    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            -> if (playing) pause()
        }
    }

    private fun requestAudioFocus(): Boolean {
        @Suppress("DEPRECATION")
        return audio.requestAudioFocus(
            focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN,
        ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    @Suppress("DEPRECATION")
    private fun abandonAudioFocus() {
        audio.abandonAudioFocus(focusListener)
    }

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) pause()
        }
    }

    private fun registerNoisy() {
        try {
            registerReceiver(noisyReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
        } catch (_: Exception) {}
    }
}
