package io.jcz.predestination.exo

import android.os.Build
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

internal object GithubUpdates {
    private const val repo = "pjincz/predestination-tv"
    data class Release(val version: String, val url: String, val size: Int)

    private fun connection(url: String) = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 10_000
        readTimeout = 20_000
        setRequestProperty("User-Agent", "Predestination-TV")
    }

    fun latest(installed: String): Release? {
        val api = connection("https://api.github.com/repos/$repo/releases/latest")
        val json = try {
            if (api.responseCode == 404) return null
            JSONObject(api.inputStream.bufferedReader().use { it.readText() })
        } finally {
            api.disconnect()
        }
        val tag = json.getString("tag_name")
        val version = tag.removePrefix("v")
        if (!Regex("""\d+\.\d+\.\d+""").matches(version)) return null
        val current = installed.split('.').map { it.toIntOrNull() ?: return null }
        val next = version.split('.').map(String::toInt)
        if (current.size != next.size || next.indices.firstOrNull { next[it] != current[it] }
                ?.let { next[it] > current[it] } != true) return null
        val assets = json.getJSONArray("assets")
        for (abi in Build.SUPPORTED_ABIS) {
            val name = "predestination-tv-exo-$version-$abi.apk"
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                val url = "https://github.com/$repo/releases/download/$tag/$name"
                if (asset.optString("browser_download_url") == url && asset.optString("name") == name) {
                    val size = asset.getLong("size")
                    if (size in 1L..134217728L) return Release(version, url, size.toInt())
                }
            }
        }
        return null
    }

    fun download(release: Release, update: ApkUpdate): HttpResponse {
        val source = connection(release.url)
        return try {
            source.inputStream.use { update.receive(it, release.size) }
        } finally {
            source.disconnect()
        }
    }
}