package io.jcz.predestination.exo

import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/** Created only while visible; persistent WebView storage retains site login state. */
internal class WallPage(context: Context) : FrameLayout(context) {
    private var web: WebView? = null
    private val handler = Handler(Looper.getMainLooper())
    private var refreshMillis = 0L
    val active get() = web != null
    private val refresh = object : Runnable {
        override fun run() {
            web?.reload() ?: return
            if (refreshMillis > 0) handler.postDelayed(this, refreshMillis)
        }
    }

    init {
        visibility = GONE
        setBackgroundColor(Color.BLACK)
        ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or
                WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime())
            setPadding(safe.left, safe.top, safe.right, safe.bottom)
            insets
        }
    }

    @android.annotation.SuppressLint("SetJavaScriptEnabled")
    fun show(config: WallSettings.Config) {
        if (active) return
        val view = WebView(context)
        web = view
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            useWideViewPort = true
            setSupportZoom(false)
            mediaPlaybackRequiresUserGesture = true
        }
        view.setInitialScale((config.zoom * 100).toInt())
        view.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                request.url.scheme?.lowercase() !in listOf("http", "https")

            override fun onPageFinished(view: WebView, url: String) {
                CookieManager.getInstance().flush()
            }
        }
        addView(view, LayoutParams(-1, -1))
        visibility = VISIBLE
        view.requestFocus()
        ViewCompat.requestApplyInsets(this)
        view.loadUrl(config.url)
        refreshMillis = config.refreshSeconds * 1000L
        if (refreshMillis > 0) handler.postDelayed(refresh, refreshMillis)
    }

    fun goBack(): Boolean {
        val view = web ?: return false
        if (!view.canGoBack()) return false
        view.goBack()
        return true
    }

    fun hide() {
        handler.removeCallbacks(refresh)
        val view = web
        web = null
        visibility = GONE
        if (view != null) {
            view.stopLoading()
            CookieManager.getInstance().flush()
            removeView(view)
            view.destroy()
        }
    }
}
