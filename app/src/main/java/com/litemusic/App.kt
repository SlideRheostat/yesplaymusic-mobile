package com.litemusic

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        AudioCache.init(this)
        ImageLoader.init(this)
        applyTheme()
    }

    companion object {
        fun applyTheme() {
            AppCompatDelegate.setDefaultNightMode(
                when (Prefs.themeMode) {
                    1 -> AppCompatDelegate.MODE_NIGHT_NO
                    2 -> AppCompatDelegate.MODE_NIGHT_YES
                    else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                }
            )
        }
    }
}
