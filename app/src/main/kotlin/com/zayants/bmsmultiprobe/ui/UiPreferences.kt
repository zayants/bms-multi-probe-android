package com.zayants.bmsmultiprobe.ui

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import com.zayants.bmsmultiprobe.R
import java.util.Locale

/** Presentation preferences never restart the BLE service or change transport settings. */
object UiPreferences {
    private fun prefs(context: Context) = context.getSharedPreferences("appearance", Context.MODE_PRIVATE)
    fun language(context: Context): String = prefs(context).getString("language", "system") ?: "system"
    fun theme(context: Context): String = prefs(context).getString("theme", "dark") ?: "dark"
    fun setLanguage(context: Context, value: String) { prefs(context).edit().putString("language", value).apply() }
    fun setTheme(context: Context, value: String) { prefs(context).edit().putString("theme", value).apply() }

    fun wrap(context: Context, language: String = language(context)): Context {
        val config = Configuration(context.resources.configuration)
        if (language != "system") {
            val locale = Locale.forLanguageTag(language)
            config.setLocales(LocaleList(locale))
            config.setLayoutDirection(locale)
        }
        config.uiMode = (config.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
            if (theme(context) == "light") Configuration.UI_MODE_NIGHT_NO else Configuration.UI_MODE_NIGHT_YES
        return context.createConfigurationContext(config)
    }

    fun languageTags(context: Context): Array<String> = context.resources.getStringArray(R.array.language_tags)
}
