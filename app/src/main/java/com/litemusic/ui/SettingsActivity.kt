package com.litemusic.ui

import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.litemusic.Api
import com.litemusic.App
import com.litemusic.AudioCache
import com.litemusic.ImageLoader
import com.litemusic.Prefs
import com.litemusic.R
import com.litemusic.player.PlayerService
import java.util.Locale

/** 设置页：账号、深色模式、音质、缓存歌曲上限、已缓存歌曲、播放页防熄屏 */
class SettingsActivity : AppCompatActivity() {

    private val levels = arrayOf("standard", "exhigh", "lossless")
    private val lyricSizes = intArrayOf(13, 15, 17, 19, 21)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        findViewById<ImageButton>(R.id.btn_back).setOnClickListener { finish() }

        bindAccount()

        val themeValue = findViewById<TextView>(R.id.theme_value)
        themeValue.text = themeLabel(Prefs.themeMode)
        findViewById<View>(R.id.row_theme).setOnClickListener { showThemeDialog(themeValue) }

        val qualityValue = findViewById<TextView>(R.id.quality_value)
        qualityValue.text = qualityLabel(Prefs.level)
        findViewById<View>(R.id.row_quality).setOnClickListener { showQualityDialog(qualityValue) }

        val cacheValue = findViewById<TextView>(R.id.cache_value)
        cacheValue.text = cacheLabel()
        findViewById<View>(R.id.row_cache).setOnClickListener { showCacheDialog(cacheValue) }

        val lyricSizeValue = findViewById<TextView>(R.id.lyric_size_value)
        lyricSizeValue.text = lyricSizeLabel()
        findViewById<View>(R.id.row_lyric_size).setOnClickListener { showLyricSizeDialog(lyricSizeValue) }

        findViewById<View>(R.id.row_cached).setOnClickListener {
            startActivity(CachedSongsActivity.launch(this))
        }

        val keepSwitch = findViewById<MaterialSwitch>(R.id.switch_keep_screen)
        keepSwitch.isChecked = Prefs.keepScreenOn
        findViewById<View>(R.id.row_keep_screen).setOnClickListener {
            Prefs.keepScreenOn = !Prefs.keepScreenOn
            keepSwitch.isChecked = Prefs.keepScreenOn
        }

        val fullscreenSwitch = findViewById<MaterialSwitch>(R.id.switch_fullscreen)
        fullscreenSwitch.isChecked = Prefs.playerFullscreen
        findViewById<View>(R.id.row_fullscreen).setOnClickListener {
            Prefs.playerFullscreen = !Prefs.playerFullscreen
            fullscreenSwitch.isChecked = Prefs.playerFullscreen
        }
    }

    override fun onResume() {
        super.onResume()
        // 播放过程中缓存会增长，返回设置页时刷新一次统计
        refreshCachedSummary()
    }

    /** 账号卡：登录后从“我的”页移入设置页顶部；未登录时保持“我的”页的登录引导 */
    private fun bindAccount() {
        val card = findViewById<View>(R.id.card_account)
        if (!Prefs.loggedIn) {
            card.visibility = View.GONE
            return
        }
        card.visibility = View.VISIBLE
        findViewById<TextView>(R.id.account_nickname).text =
            Prefs.nickname.ifEmpty { getString(R.string.user_default) }
        findViewById<TextView>(R.id.account_sub).text =
            getString(if (Prefs.vipType > 0) R.string.vip_member else R.string.logged_in)
        ImageLoader.load(findViewById<ImageView>(R.id.account_avatar), Prefs.avatar, 160)
        findViewById<View>(R.id.btn_logout).setOnClickListener {
            Api.logout {
                card.visibility = View.GONE
            }
        }
    }

    private fun refreshCachedSummary() {
        val value = findViewById<TextView>(R.id.cached_value)
        Thread {
            val (count, bytes) = AudioCache.summary()
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                value.text = if (count == 0) "" else getString(
                    R.string.cached_songs_summary, count, formatSize(bytes)
                )
            }
        }.start()
    }

    private fun formatSize(bytes: Long): String {
        val mb = bytes / 1024.0 / 1024.0
        return if (mb >= 1024) String.format(Locale.US, "%.1f GB", mb / 1024) else
            String.format(Locale.US, "%.0f MB", mb)
    }

    private fun themeLabel(mode: Int): String = getString(
        when (mode) {
            1 -> R.string.theme_light
            2 -> R.string.theme_dark
            else -> R.string.theme_system
        }
    )

    private fun qualityLabel(level: String): String = getString(
        when (level) {
            "exhigh" -> R.string.quality_high
            "lossless" -> R.string.quality_lossless
            else -> R.string.quality_low
        }
    )

    private fun cacheLabel(): String = "${Prefs.cacheMB} ${getString(R.string.cache_limit_unit)}"

    private fun lyricSizeLabel(): String = "${Prefs.lyricSize}sp"

    private fun showLyricSizeDialog(valueView: TextView) {
        val labels = lyricSizes.map { "${it}sp" }.toTypedArray()
        val current = lyricSizes.indexOf(Prefs.lyricSize).coerceAtLeast(0)
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.lyric_size))
            .setSingleChoiceItems(labels, current) { dialog, which ->
                Prefs.lyricSize = lyricSizes[which]
                valueView.text = lyricSizeLabel()
                dialog.dismiss()
            }
            .show()
    }

    private fun showThemeDialog(valueView: TextView) {
        val labels = arrayOf(
            getString(R.string.theme_system),
            getString(R.string.theme_light),
            getString(R.string.theme_dark),
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.theme_mode))
            .setSingleChoiceItems(labels, Prefs.themeMode) { dialog, which ->
                Prefs.themeMode = which
                valueView.text = themeLabel(which)
                dialog.dismiss()
                App.applyTheme()
            }
            .show()
    }

    private fun showQualityDialog(valueView: TextView) {
        val labels = arrayOf(
            getString(R.string.quality_low),
            getString(R.string.quality_high),
            getString(R.string.quality_lossless),
        )
        val current = levels.indexOf(Prefs.level).coerceAtLeast(0)
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.sound_quality))
            .setSingleChoiceItems(labels, current) { dialog, which ->
                val old = Prefs.level
                Prefs.level = levels[which]
                valueView.text = qualityLabel(Prefs.level)
                dialog.dismiss()
                // 当前已加载的歌曲不会中途换档，提示用户新音质从下一首开始
                if (old != levels[which] && PlayerService.instance?.currentSong != null) {
                    Toast.makeText(this, R.string.quality_next_track, Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    private fun showCacheDialog(valueView: TextView) {
        val density = resources.displayMetrics.density
        val pad = (20 * density).toInt()
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(Prefs.cacheMB.toString())
        }
        // 说明文字放在输入框下方，避免作为单行 hint 被截断
        val hint = TextView(this).apply {
            text = getString(R.string.cache_limit_hint)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(com.google.android.material.color.MaterialColors.getColor(
                this, com.google.android.material.R.attr.colorOnSurfaceVariant
            ))
            setPadding(0, (8 * density).toInt(), 0, 0)
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, (pad / 2), pad, 0)
            addView(input, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(hint, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.cache_limit))
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val v = input.text.toString().trim().toIntOrNull() ?: 0
                if (v <= 0) {
                    Toast.makeText(this, R.string.cache_limit_invalid, Toast.LENGTH_SHORT).show()
                } else {
                    Prefs.cacheMB = v
                    valueView.text = cacheLabel()
                    AudioCache.applyLimit()
                    refreshCachedSummary()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
