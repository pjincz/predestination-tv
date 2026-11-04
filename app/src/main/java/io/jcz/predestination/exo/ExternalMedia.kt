package io.jcz.predestination.exo

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.IntentCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata

internal object ExternalMedia {
    fun isOpen(intent: Intent) = intent.action in listOf(
        Intent.ACTION_VIEW, Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE)

    fun uris(intent: Intent): List<Uri> {
        val candidates = when (intent.action) {
            Intent.ACTION_VIEW -> listOfNotNull(intent.data)
            Intent.ACTION_SEND -> listOfNotNull(
                IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE -> IntentCompat.getParcelableArrayListExtra(
                intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            else -> emptyList()
        }
        return candidates.map { it.normalizeScheme() }.filter {
            when (it.scheme) {
                "content" -> !it.authority.isNullOrEmpty()
                "file" -> it.path?.startsWith('/') == true
                "http", "https", "rtsp" -> runCatching { Protocol.mediaUri(it.toString()) }.isSuccess
                else -> false
            }
        }
    }

    fun items(context: Context, intent: Intent): List<MediaItem> = uris(intent).map { uri ->
        val title = if (uri.scheme == "content") runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME),
                null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull() else null
        MediaItem.Builder().setUri(uri)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(title ?: uri.lastPathSegment).build())
            .build()
    }
}
