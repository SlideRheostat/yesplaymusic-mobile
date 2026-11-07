package com.litemusic.player

import android.app.Notification
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import androidx.annotation.RequiresApi
import com.litemusic.ImageLoader
import com.litemusic.R

/**
 * API 21+ 专属逻辑（MediaSession / MediaStyle 通知）全部隔离在本类。
 * PlayerService 仅在 SDK>=21 时加载本类，Android 4.4 上类永不被加载，
 * 避免 Dalvik/ART 类验证期引用不到 android.media.session.* 而崩溃。
 */
@RequiresApi(21)
object PlayerCompat21 {

    fun createSession(svc: PlayerService): Any {
        val ms = MediaSession(svc, "YesPlayMusic")
        ms.setCallback(object : MediaSession.Callback() {
            override fun onPlay() = svc.compatPlay()
            override fun onPause() = svc.compatPause()
            override fun onSkipToNext() = svc.compatNext()
            override fun onSkipToPrevious() = svc.compatPrev()
            override fun onSeekTo(pos: Long) = svc.compatSeek(pos.toInt())
            override fun onStop() = svc.compatStop()
        })
        ms.isActive = true
        return ms
    }

    fun updateMeta(svc: PlayerService, session: Any) {
        val ms = session as MediaSession
        val song = svc.currentSong
        val b = MediaMetadata.Builder()
        if (song != null) {
            b.putString(MediaMetadata.METADATA_KEY_TITLE, song.name)
            b.putString(MediaMetadata.METADATA_KEY_ARTIST, song.artists)
            b.putString(MediaMetadata.METADATA_KEY_ALBUM, song.album)
            b.putLong(
                MediaMetadata.METADATA_KEY_DURATION,
                if (svc.duration > 0) svc.duration.toLong() else song.duration
            )
        }
        svc.compatArt()?.let { b.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, it) }
        ms.setMetadata(b.build())
    }

    fun updateState(svc: PlayerService, session: Any) {
        val ms = session as MediaSession
        val actions = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
            PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_SKIP_TO_NEXT or
            PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_SEEK_TO or
            PlaybackState.ACTION_STOP
        val state = if (svc.playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED
        try {
            ms.setPlaybackState(
                PlaybackState.Builder().setActions(actions).setState(state, svc.compatPosition(), 1f).build()
            )
        } catch (_: Exception) {
        }
    }

    fun release(session: Any) {
        val ms = session as MediaSession
        try { ms.setCallback(null); ms.isActive = false; ms.release() } catch (_: Exception) {}
    }

    /** 为基础通知追加 MediaStyle、三键与异步封面 */
    fun decorate(svc: PlayerService, session: Any?, builder: Notification.Builder) {
        val ms = session as MediaSession?
        builder.setStyle(
            Notification.MediaStyle()
                .setMediaSession(ms?.sessionToken)
                .setShowActionsInCompactView(0, 1, 2)
        )
        addActions(svc, builder)
        builder.setDeleteIntent(svc.piCompat(PlayerService.ACTION_STOP))
        // 回调前确认仍是同一首歌，避免切歌后把上一首封面刷进新通知
        val songId = svc.currentSong?.id ?: -1
        ImageLoader.bitmap(svc.currentSong?.cover, 200) { bmp ->
            if (bmp != null && svc.compatIsInstance() && svc.currentSong?.id == songId) {
                svc.compatNotifWithArt(bmp)
            }
        }
    }

    fun buildWithArt(svc: PlayerService, session: Any?, large: Bitmap): Notification {
        val ms = session as MediaSession?
        val song = svc.currentSong
        val b = svc.compatBaseBuilder()
            .setLargeIcon(large)
            .setStyle(
                Notification.MediaStyle()
                    .setMediaSession(ms?.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
        b.setContentTitle(song?.name ?: "YesPlayMusic")
            .setContentText(song?.let { "${it.artists} - ${it.album}" } ?: svc.getString(R.string.notification_open_tip))
        addActions(svc, b)
        return b.build()
    }

    private fun addActions(svc: PlayerService, b: Notification.Builder) {
        b.addAction(Notification.Action(R.drawable.ic_prev, svc.getString(R.string.notif_prev), svc.piCompat(PlayerService.ACTION_PREV)))
        b.addAction(
            Notification.Action(
                if (svc.playing) R.drawable.ic_pause else R.drawable.ic_play,
                svc.getString(if (svc.playing) R.string.notif_pause else R.string.notif_play),
                svc.piCompat(PlayerService.ACTION_PLAY)
            )
        )
        b.addAction(Notification.Action(R.drawable.ic_next, svc.getString(R.string.notif_next), svc.piCompat(PlayerService.ACTION_NEXT)))
    }
}
