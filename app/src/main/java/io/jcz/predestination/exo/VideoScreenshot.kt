package io.jcz.predestination.exo

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.SurfaceView
import java.io.ByteArrayOutputStream
import java.lang.ref.WeakReference
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Copies the video surface, before any TV-specific display post-processing; excludes OSD. */
internal object VideoScreenshot {
    private var surface = WeakReference<SurfaceView>(null)
    private val main = Handler(Looper.getMainLooper())
    private val busy = AtomicBoolean()
    private val encoder = java.util.concurrent.Executors.newSingleThreadExecutor()

    fun attach(view: SurfaceView?) { surface = WeakReference(view) }

    fun capture(): HttpResponse {
        if (!busy.compareAndSet(false, true)) return HttpResponse(409, "正在截屏，请稍后重试")
        val result = CompletableFuture<ByteArray>()
        main.post {
            var bitmap: Bitmap? = null
            try {
                val view = surface.get()
                check(view != null && view.isShown && view.holder.surface.isValid) { "视频画面不可用" }
                val bounds = view.holder.surfaceFrame
                check(bounds.width() > 0 && bounds.height() > 0 &&
                    bounds.width().toLong() * bounds.height() <= 3840L * 2160) { "画面尺寸不支持" }
                val target = Bitmap.createBitmap(bounds.width(), bounds.height(), Bitmap.Config.ARGB_8888)
                bitmap = target
                PixelCopy.request(view, target, { code ->
                    encoder.execute {
                        try {
                            check(code == PixelCopy.SUCCESS) { "画面复制失败，错误码 $code" }
                            val bytes = ByteArrayOutputStream().use { output ->
                                check(target.compress(Bitmap.CompressFormat.PNG, 100, output))
                                output.toByteArray()
                            }
                            result.complete(bytes)
                        } catch (error: Exception) {
                            result.completeExceptionally(error)
                        } finally {
                            target.recycle()
                            busy.set(false)
                        }
                    }
                }, main)
            } catch (error: Exception) {
                bitmap?.recycle()
                result.completeExceptionally(error)
                busy.set(false)
            }
        }
        return try {
            HttpResponse(binaryBody = result.get(15, TimeUnit.SECONDS), headers = mapOf(
                "Content-Type" to "image/png", "Cache-Control" to "no-store",
                "Content-Disposition" to "inline; filename=video-${System.currentTimeMillis()}.png",
                "X-Content-Type-Options" to "nosniff"
            ))
        } catch (_: Exception) {
            HttpResponse(503, "截屏失败：请确认视频正在前台播放，稍后重试。",
                mapOf("Content-Type" to "text/plain; charset=utf-8", "Cache-Control" to "no-store"))
        }
    }
}
