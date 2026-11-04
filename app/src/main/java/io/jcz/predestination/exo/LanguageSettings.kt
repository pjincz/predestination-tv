package io.jcz.predestination.exo

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import java.util.Locale

/** One device-wide language choice for both the player and its admin website. */
internal class LanguageSettings(private val context: Context) {
    private val preferences = context.getSharedPreferences("language", Context.MODE_PRIVATE)

    fun read(): String = preferences.getString("selection", "system") ?: "system"

    fun save(selection: String): Boolean {
        require(selection in setOf("system", "en", "zh"))
        return preferences.edit().putString("selection", selection).commit()
    }

    fun language(): String = resolve(read(), context.applicationContext.resources.configuration.locales[0].language)

    companion object {
        fun resolve(selection: String, systemLanguage: String): String = when (selection) {
            "en", "zh" -> selection
            else -> if (systemLanguage == "zh") "zh" else "en"
        }

        private var source: Resources? = null
        private var configuration: Configuration? = null
        private var translated: Resources? = null

        // A separate resource context avoids changing the player's configuration or restarting playback.
        @Synchronized
        fun resources(context: Context): Resources {
            val app = context.applicationContext
            val config = Configuration(app.resources.configuration).apply {
                setLocale(Locale.forLanguageTag(LanguageSettings(app).language()))
            }
            if (source !== app.resources || configuration != config) {
                source = app.resources
                configuration = config
                translated = app.createConfigurationContext(config).resources
            }
            return checkNotNull(translated)
        }
    }
}

internal fun Context.tr(@StringRes id: Int, vararg args: Any): String =
    LanguageSettings.resources(this).getString(id, *args)

internal fun Context.quantityText(@PluralsRes id: Int, count: Int): String =
    LanguageSettings.resources(this).getQuantityString(id, count, count)
