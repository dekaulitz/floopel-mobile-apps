package com.floopel.traveler

import android.app.Application
import com.floopel.traveler.platform.storage.TravelerLanguage
import java.util.Locale

class TravelerApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val language = TravelerLanguage.current(this)
        Locale.setDefault(Locale.forLanguageTag(language))
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            val manager = getSystemService(android.app.LocaleManager::class.java)
            if (manager.applicationLocales.isEmpty) manager.applicationLocales = android.os.LocaleList.forLanguageTags(language)
        }
    }
}
