package io.jcz.predestination.exo

import android.app.Activity
import android.content.Context
import android.widget.Button
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.net.InetAddress

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class LanguageSettingsTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Before fun reset() {
        context.getSharedPreferences("language", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("admin", Context.MODE_PRIVATE).edit().clear().commit()
        RuntimeEnvironment.setQualifiers("en-rUS")
    }

    @Test fun followsSystemAndFallsBackToEnglish() {
        val settings = LanguageSettings(context)
        assertEquals("system", settings.read())
        assertEquals("Playing", context.tr(R.string.playing))
        RuntimeEnvironment.setQualifiers("zh-rCN")
        assertEquals("正在播放", context.tr(R.string.playing))
        RuntimeEnvironment.setQualifiers("zh-rTW")
        assertEquals("正在播放", context.tr(R.string.playing))
        RuntimeEnvironment.setQualifiers("fr-rFR")
        assertEquals("Playing", context.tr(R.string.playing))
    }

    @Test fun overridePersistsAndReturningToSystemUsesCurrentLocale() {
        RuntimeEnvironment.setQualifiers("zh-rCN")
        assertTrue(LanguageSettings(context).save("en"))
        assertEquals("en", LanguageSettings(context).read())
        assertEquals("Playing", context.tr(R.string.playing))
        assertTrue(LanguageSettings(context).save("zh"))
        RuntimeEnvironment.setQualifiers("en-rUS")
        assertEquals("正在播放", context.tr(R.string.playing))
        assertTrue(LanguageSettings(context).save("system"))
        assertEquals("Playing", context.tr(R.string.playing))
    }

    @Test fun formatsTranslationsAndRelabelsExistingOsdWithoutReplacingIt() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val osd = TvOsd(controller.get())
        val button = osd.addButton(R.string.play_pause) {}
        assertEquals("Play / Pause", button.text.toString())
        assertEquals("Buffering · 1.5 / 10 s", context.tr(R.string.buffering_progress, 1.5, 10.0))
        assertEquals("Wall Page in 1 second", context.quantityText(R.plurals.wall_countdown, 1))
        assertEquals("Wall Page in 2 seconds", context.quantityText(R.plurals.wall_countdown, 2))
        assertEquals("Volume gain · 1 channel", context.quantityText(R.plurals.gain_channels, 1))
        LanguageSettings(context).save("zh")
        osd.refreshLanguage()
        assertSame(button, osd.buttons.getChildAt(0))
        assertEquals("播放 / 暂停", (osd.buttons.getChildAt(0) as Button).text.toString())
        assertEquals("正在缓冲 · 1.5 / 10 秒", context.tr(R.string.buffering_progress, 1.5, 10.0))
        assertEquals("3 秒后展示 Wall Page", context.quantityText(R.plurals.wall_countdown, 3))
        controller.pause().stop().destroy()
    }

    @Test fun adminSettingRequiresAuthorizationAndUpdatesAllPages() {
        val base = "http://127.0.0.1:7777"
        val page = AdminPage(context)
        fun request(method: String = "GET", cookie: String = "", body: String = "", origin: String = base) =
            HttpRequest(method, "/admin", mapOf("host" to "127.0.0.1:7777", "cookie" to cookie,
                "origin" to origin, "content-type" to "application/x-www-form-urlencoded"),
                body.toByteArray(), InetAddress.getLoopbackAddress())
        assertEquals(403, page.handle(request("POST", body = "settings=language&language=zh"), base).status)
        assertEquals("system", LanguageSettings(context).read())
        val waiting = page.handle(request(), base)
        assertTrue(waiting.body.contains("Waiting for device authorization"))
        val claim = waiting.headers.getValue("Set-Cookie").substringBefore(';')
        page.resolve(checkNotNull(page.awaitingApproval()), true)
        val approved = page.handle(request(cookie = claim), base)
        val cookie = approved.headers.getValue("Set-Cookie").substringBefore(';')
        assertEquals(403, page.handle(request("POST", cookie, "settings=language&language=zh",
            "http://other.example"), base).status)
        assertEquals(400, page.handle(request("POST", cookie, "settings=language&language=invalid"), base).status)
        assertEquals("system", LanguageSettings(context).read())
        assertEquals(303, page.handle(request("POST", cookie, "settings=language&language=zh"), base).status)
        val chinese = page.handle(request(cookie = cookie), base).body
        assertTrue(chinese.contains("lang=\"zh\""))
        assertTrue(chinese.contains("管理员页面"))
        assertTrue(chinese.contains("value=\"zh\" selected"))
        assertTrue(chinese.contains("正在上传"))
        assertEquals(303, page.handle(request("POST", cookie, "settings=language&language=en"), base).status)
        val english = AdminPage(context).handle(request(cookie = cookie), base).body
        assertTrue(english.contains("lang=\"en\""))
        assertTrue(english.contains("Administration"))
        assertTrue(english.contains("Upload failed"))
        assertFalse(english.contains("正在"))
        assertEquals("Playing", context.tr(R.string.playing))
    }
}
