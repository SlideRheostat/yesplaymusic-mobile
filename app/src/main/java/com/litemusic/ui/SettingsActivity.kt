package com.litemusic.ui

import android.os.Bundle
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.litemusic.App
import com.litemusic.AudioCache
import com.litemusic.Prefs
import com.litemusic.R

/** 设置页：深色模式、音质、缓存歌曲上限 */
class SettingsActivity : AppCompatActivity() {

    private val levels = arrayOf("standard", "exhigh", "lossless")
    private val lyricSizes = intArrayOf(13, 15, 17, 19, 21)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        findViewById<ImageButton>(R.id.btn_back).setOnClickListener { finish() }

        val themeValue = findViewById<TextView>(R.id.theme_value)
        themeValue.text = themeLabel(Prefs.themeMode)
        findViewById<android.view.View>(R.id.row_theme).setOnClickListener { showThemeDialog(themeValue) }

        val qualityValue = findViewById<TextView>(R.id.quality_value)
        qualityValue.text = qualityLabel(Prefs.level)
        findViewById<android.view.View>(R.id.row_quality).setOnClickListener { showQualityDialog(qualityValue) }

        val cacheValue = findViewById<TextView>(R.id.cache_value)
        cacheValue.text = cacheLabel()
        findViewById<android.view.View>(R.id.row_cache).setOnClickListener { showCacheDialog(cacheValue) }

        val lyricSizeValue = findViewById<TextView>(R.id.lyric_size_value)
        lyricSizeValue.text = lyricSizeLabel()
        findViewById<android.view.View>(R.id.row_lyric_size).setOnClickListener { showLyricSizeDialog(lyricSizeValue) }
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
                Prefs.level = levels[which]
                valueView.text = qualityLabel(Prefs.level)
                dialog.dismiss()
            }
            .show()
    }

    private fun showCacheDialog(valueView: TextView) {
        val input = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(Prefs.cacheMB.toString())
            hint = getString(R.string.cache_limit_hint)
        }
        val container = FrameLayout(this).apply {
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(input, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
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
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
