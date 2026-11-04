package io.jcz.predestination.exo

import android.content.Context
import java.net.URI

internal class WallSettings(context: Context) {
    private val preferences = context.getSharedPreferences("wall", Context.MODE_PRIVATE)

    data class Config(val url: String = "", val zoom: Float = 1f, val refreshSeconds: Int = 0)

    fun read() = Config(preferences.getString("url", "").orEmpty(),
        preferences.getFloat("zoom", 1f), preferences.getInt("refreshSeconds", 0))

    fun save(config: Config): Boolean = preferences.edit().putString("url", config.url)
        .putFloat("zoom", config.zoom).putInt("refreshSeconds", config.refreshSeconds).commit()

    companion object {
        fun parse(url: String, zoom: String, refresh: String): Config {
            val address = url.trim()
            require(address.length <= 4096) { "URL too long" }
            if (address.isNotEmpty()) {
                val parsed = URI(address)
                require(parsed.scheme?.lowercase() in listOf("http", "https") &&
                    !parsed.host.isNullOrEmpty() && parsed.rawUserInfo == null) { "Enter an HTTP(S) URL" }
            }
            val scale = zoom.toFloatOrNull()
            require(scale != null && scale.isFinite() && scale in 0.25f..5f) { "Zoom must be between 0.25 and 5" }
            val seconds = refresh.toIntOrNull()
            require(seconds != null && seconds in 0..86400) { "Refresh interval must be between 0 and 86400 seconds" }
            return Config(address, scale, seconds)
        }
    }
}
