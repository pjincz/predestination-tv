package io.jcz.predestination.exo

import android.content.Context
import android.content.pm.PackageManager
import java.io.File
import java.io.InputStream

internal class ApkUpdate(private val context: Context) {
    private val directory = File(context.cacheDir, "updates")
    val pending = File(directory, "update.apk")

    @Synchronized
    @Suppress("DEPRECATION")
    fun receive(input: InputStream, size: Int): HttpResponse {
        if (size !in 1..(128 * 1024 * 1024)) return HttpResponse(400, context.tr(R.string.apk_max_size))
        if (pending.exists()) return HttpResponse(409, context.tr(R.string.update_pending))
        directory.mkdirs()
        val temporary = File.createTempFile("upload-", ".apk", directory)
        try {
            temporary.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                val deadline = android.os.SystemClock.elapsedRealtime() + 180_000L
                var remaining = size
                while (remaining > 0) {
                    if (android.os.SystemClock.elapsedRealtime() >= deadline)
                        return HttpResponse(400, context.tr(R.string.upload_timeout))
                    val count = input.read(buffer, 0, minOf(remaining, buffer.size))
                    if (count < 0) return HttpResponse(400, context.tr(R.string.upload_incomplete))
                    output.write(buffer, 0, count)
                    remaining -= count
                }
            }
            val manager = context.packageManager
            val candidate = manager.getPackageArchiveInfo(temporary.path, PackageManager.GET_SIGNATURES)
            val installed = manager.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
            if (candidate == null || candidate.packageName != context.packageName ||
                candidate.versionCode < installed.versionCode ||
                candidate.signatures.isNullOrEmpty() ||
                candidate.signatures?.toSet() != installed.signatures?.toSet()) {
                return HttpResponse(400, context.tr(R.string.apk_mismatch))
            }
            synchronized(ApkUpdate::class.java) {
                if (pending.exists()) return HttpResponse(409, context.tr(R.string.update_pending))
                if (!temporary.renameTo(pending)) return HttpResponse(503, context.tr(R.string.update_save_error))
            }
            return HttpResponse(body = context.tr(R.string.update_received))
        } finally {
            temporary.delete()
        }
    }
}
