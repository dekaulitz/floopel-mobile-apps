package com.floopel.traveler.platform.storage

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/** Android owns per-app language and persistence. Floopel's initial language is Indonesian. */
internal object TravelerLanguage {
    fun current(context: Context): String {
        val native = if (Build.VERSION.SDK_INT >= 33) context.getSystemService(LocaleManager::class.java).applicationLocales
            .takeUnless { it.isEmpty }?.get(0)?.language else null
        return native?.takeIf { it in setOf("id", "en") }
            ?: context.getSharedPreferences("traveler_language", Context.MODE_PRIVATE).getString("language", "id")
                ?.takeIf { it in setOf("id", "en") } ?: "id"
    }
    fun wrap(context: Context): Context {
        val language = current(context)
        Locale.setDefault(Locale.forLanguageTag(language))
        return context.createConfigurationContext(Configuration(context.resources.configuration).apply {
            setLocales(LocaleList.forLanguageTags(language))
        })
    }
    fun select(activity: Activity, language: String) {
        require(language in setOf("id", "en"))
        if (current(activity) == language) return
        activity.getSharedPreferences("traveler_language", Context.MODE_PRIVATE).edit().putString("language", language).apply()
        Locale.setDefault(Locale.forLanguageTag(language))
        if (Build.VERSION.SDK_INT >= 33) activity.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.forLanguageTags(language)
        else activity.recreate()
    }
}
