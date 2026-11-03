package com.camnexus.rtsp

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

/**
 * 启动时恢复用户选择的界面语言。
 *
 * AppCompat 的自动存储只在部分场景生效，这里自己做一份持久化，保证冷启动也正确。
 */
class App : Application() {

    override fun onCreate() {
        super.onCreate()
        val saved = Prefs.language(this)
        if (saved.isEmpty()) return
        val current = AppCompatDelegate.getApplicationLocales().toLanguageTags()
        if (current.substringBefore('-') != saved.substringBefore('-')) {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(saved))
        }
    }
}
