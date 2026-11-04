package io.jcz.predestination.exo

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors

internal object AudioOnlyMode {
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val provider = Uri.parse("content://com.tianci.provider")

    fun supported(context: Context): Boolean = runCatching {
        val info = context.packageManager.resolveContentProvider("com.tianci.provider", 0)
        info != null && info.enabled && info.exported
    }.getOrDefault(false)

    fun requested(context: Context) = context.getSharedPreferences("receiver", Context.MODE_PRIVATE)
        .getBoolean("direct-screen-off", false)

    fun set(context: Context, off: Boolean, complete: (Boolean) -> Unit = {}) {
        val app = context.applicationContext
        app.getSharedPreferences("receiver", Context.MODE_PRIVATE).edit()
            .putBoolean("direct-screen-off", off).apply()
        // Serialize off/on requests so a cast arriving during shutdown always restores last.
        worker.execute {
            val result = runCatching {
                val client = app.contentResolver.acquireUnstableContentProviderClient(provider)
                    ?: error("Audio-only provider unavailable")
                client.use {
                    val params = Bundle().apply {
                        putInt("tc_type", 2)
                        putByteArray("param", ("@str:type=SKY_CONFIG_SWITCH;name=SKY_CFG_TV_SOUND_SEPERATE;" +
                            "current=$off;switchtype=0;enable=true;start=0;end=0;").toByteArray(Charsets.UTF_8))
                    }
                    check(it.call("METHOD_SYSTEM_CMD_SET_CONFIG", null, params) != null)
                }
            }
            result.exceptionOrNull()?.let { android.util.Log.w("TvCast", "Direct screen switch failed", it) }
            main.post { complete(result.isSuccess) }
        }
    }

    fun restore(context: Context) { if (requested(context)) set(context, false) }

    fun enabled(context: Context) = context.getSharedPreferences("receiver", Context.MODE_PRIVATE)
        .getBoolean("idle-screen-off", true)

    fun toggle(context: Context): Boolean {
        val next = !enabled(context)
        context.getSharedPreferences("receiver", Context.MODE_PRIVATE).edit()
            .putBoolean("idle-screen-off", next).apply()
        return next
    }

}
