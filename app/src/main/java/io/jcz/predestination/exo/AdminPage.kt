package io.jcz.predestination.exo

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.SystemClock
import java.security.MessageDigest
import android.util.Base64
import java.security.SecureRandom

private const val SESSION_COOKIE = "exo_admin_session"
private const val APPROVAL_COOKIE = "exo_admin_approval"
private const val SESSION_SECONDS = 8 * 60 * 60
private const val APPROVAL_SECONDS = 120

internal class AdminPage(private val context: Context) {
    private val preferences = context.getSharedPreferences("admin", Context.MODE_PRIVATE)
    private val update = ApkUpdate(context)
    private val bufferSettings = BufferSettings(context)
    private val wallSettings = WallSettings(context)
    private val languageSettings = LanguageSettings(context)
    private val activityManager = context.getSystemService(ActivityManager::class.java)
    private val appVersion = context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
    internal data class Approval(val id: String, val code: String, val peer: String)
    private data class Pending(val approval: Approval, val expires: Long, var approved: Boolean? = null, var sessionSeconds: Int = SESSION_SECONDS)

    private val random = SecureRandom()
    private var pending: Pending? = null

    private fun token() = Base64.encodeToString(ByteArray(32).also(random::nextBytes),
        Base64.NO_WRAP or Base64.NO_PADDING or Base64.URL_SAFE)

    private fun cookie(request: HttpRequest, name: String) =
        request.headers["cookie"].orEmpty().split(';')
            .map { it.trim() }.firstOrNull { it.startsWith("$name=") }?.substringAfter('=')

    private fun sessionDigest(token: String) = Base64.encodeToString(
        MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)

    private fun authenticated(request: HttpRequest): Boolean {
        val token = cookie(request, SESSION_COOKIE) ?: return false
        val digest = sessionDigest(token)
        return System.currentTimeMillis() < preferences.getLong("session:$digest", 0L)
    }

    @Synchronized
    fun awaitingApproval(): Approval? = pending?.takeIf {
        it.approved == null && SystemClock.elapsedRealtime() < it.expires
    }?.approval

    @Synchronized
    fun resolve(approval: Approval, approved: Boolean, remember: Boolean = false) {
        val current = pending ?: return
        if (awaitingApproval() != approval) return
        current.approved = approved
        if (remember && approved) current.sessionSeconds = 30 * 24 * 60 * 60
        if (remember && !approved) {
            preferences.edit().putLong("denied:${approval.peer}", System.currentTimeMillis() + 86_400_000L).apply()
        }
    }

    @Synchronized
    fun ignore(approval: Approval) {
        if (awaitingApproval() == approval) pending = null
    }

    private fun blocked(request: HttpRequest): Boolean {
        val key = "denied:${request.peer.hostAddress}"
        val until = preferences.getLong(key, 0)
        if (until > System.currentTimeMillis()) return true
        if (until != 0L) preferences.edit().remove(key).apply()
        return false
    }

    fun upload(request: HttpRequest, baseUrl: String, input: java.io.InputStream, size: Int): HttpResponse {
        if (request.method != "POST") return HttpResponse(405)
        val authorized = synchronized(this) {
            request.headers["host"] == baseUrl.removePrefix("http://") &&
                request.headers["origin"] == baseUrl && !blocked(request) && authenticated(request)
        }
        if (!authorized) return HttpResponse(403, context.tr(R.string.sign_in_again))
        if (request.headers["content-type"] != "application/vnd.android.package-archive")
            return HttpResponse(400, context.tr(R.string.choose_apk))
        AppLog.record("TvExoUpdate", "Upload started: $size bytes")
        val result = update.receive(input, size)
        AppLog.record("TvExoUpdate", "Upload result: ${result.status}")
        return result.copy(headers = mapOf("Content-Type" to "text/plain; charset=utf-8", "Cache-Control" to "no-store"))
    }

    @Synchronized
    fun handle(request: HttpRequest, baseUrl: String): HttpResponse {
        if (request.headers["host"] != baseUrl.removePrefix("http://")) return page(403, context.tr(R.string.invalid_address))
        if (blocked(request)) return page(403, context.tr(R.string.admin_access_denied)).withCookie(APPROVAL_COOKIE, "", 0)
        if (request.path == "/admin" && request.method == "POST") return saveSettings(request, baseUrl)
        if (request.method != "GET") return page(405, context.tr(R.string.method_unsupported))
        if (request.headers["sec-fetch-site"] == "cross-site") return page(403, context.tr(R.string.open_admin_directly))
        val now = SystemClock.elapsedRealtime()
        if (request.path == "/admin/logs") {
            if (!authenticated(request)) return page(403, context.tr(R.string.authorize_first))
            return HttpResponse(body = AppLog.read(), headers = mapOf(
                "Content-Type" to "text/plain; charset=utf-8",
                "Cache-Control" to "no-store",
                "X-Content-Type-Options" to "nosniff",
                "Content-Security-Policy" to "default-src 'none'; frame-ancestors 'none'"
            ))
        }
        if (authenticated(request)) return dashboard()

        val claim = cookie(request, APPROVAL_COOKIE)
        val current = pending
        if (claim != null) {
            if (current == null || current.approval.id != claim || current.expires <= now ||
                current.approved == false) {
                return page(403, context.tr(R.string.approval_expired), """<p><a href="/admin">${xml(context.tr(R.string.request_approval_again))}</a></p>""").withCookie(APPROVAL_COOKIE, "", 0)
            }
            if (current.approved == true) {
                val session = token()
                if (!saveSession(session, current.sessionSeconds)) {
                    return page(503, context.tr(R.string.approval_save_error))
                }
                pending = null
                return dashboard().withCookie(SESSION_COOKIE, session, current.sessionSeconds)
            }
            return waiting(current.approval)
        }
        if (current != null && current.expires > now && current.approved != false)
            return page(409, context.tr(R.string.approval_busy))

        val approval = Approval(token(), (1000 + random.nextInt(9000)).toString(),
            request.peer.hostAddress.orEmpty())
        pending = Pending(approval, now + APPROVAL_SECONDS * 1000L)
        return waiting(approval).withCookie(APPROVAL_COOKIE, approval.id, APPROVAL_SECONDS)
    }

    private fun saveSession(session: String, seconds: Int): Boolean {
        val now = System.currentTimeMillis()
        val editor = preferences.edit()
        for ((key, value) in preferences.all) {
            if (key.startsWith("session:") && value is Long && value <= now) editor.remove(key)
        }
        return editor
            .putLong("session:${sessionDigest(session)}", now + seconds * 1000L)
            .commit()
    }

    private fun waiting(approval: Approval) = page(200, context.tr(R.string.waiting_approval), """
        <p>${xml(context.tr(R.string.check_pairing_code))}<strong>${approval.code}</strong>${xml(context.tr(R.string.confirm_pairing_code))}</p>
        <script>setTimeout(() => location.reload(), 1500);</script>
    """)

    private fun HttpResponse.withCookie(name: String, value: String, seconds: Int) =
        copy(headers = headers + ("Set-Cookie" to "$name=$value; Path=/admin; HttpOnly; SameSite=Strict; Max-Age=$seconds"))

    private fun saveSettings(request: HttpRequest, baseUrl: String): HttpResponse {
        if (request.headers["origin"] != baseUrl || !authenticated(request))
            return page(403, context.tr(R.string.authorize_first))
        if (request.headers["content-type"]?.substringBefore(';') != "application/x-www-form-urlencoded" ||
            request.body.size > 16384) return page(400, context.tr(R.string.invalid_settings))
        val fields = runCatching {
            request.body.toString(Charsets.UTF_8).split('&').associate {
                java.net.URLDecoder.decode(it.substringBefore('='), "UTF-8") to
                    java.net.URLDecoder.decode(it.substringAfter('=', ""), "UTF-8")
            }
        }.getOrElse { return page(400, context.tr(R.string.invalid_settings)) }
        if (fields["settings"] == "language") {
            val selection = fields["language"]
            if (selection !in setOf("system", "en", "zh"))
                return page(400, context.tr(R.string.invalid_settings))
            if (!languageSettings.save(checkNotNull(selection)))
                return page(503, context.tr(R.string.save_error))
            return HttpResponse(303, headers = mapOf("Location" to "/admin", "Cache-Control" to "no-store"))
        }
        if (fields["settings"] == "wall") {
            val config = runCatching {
                WallSettings.parse(fields["url"].orEmpty(), fields["zoom"].orEmpty(),
                    fields["refreshSeconds"].orEmpty())
            }.getOrElse { return page(400, context.tr(R.string.invalid_wall_settings)) }
            if (!wallSettings.save(config)) return page(503, context.tr(R.string.save_error))
            return HttpResponse(303, headers = mapOf("Location" to "/admin", "Cache-Control" to "no-store"))
        }
        val memory = fields["memoryMiB"]?.toIntOrNull()
        val seconds = fields["seconds"]?.toIntOrNull()
        if (memory == null || memory !in BufferSettings.memoryRange ||
            seconds == null || seconds !in BufferSettings.durationRange)
            return page(400, context.tr(R.string.buffer_out_of_range))
        if (!bufferSettings.save(BufferSettings.Limits(memory, seconds))) return page(503, context.tr(R.string.save_error))
        return HttpResponse(303, headers = mapOf("Location" to "/admin", "Cache-Control" to "no-store"))
    }

    private fun bufferForm(): String {
        val limits = bufferSettings.read()
        val memory = BufferSettings.memoryRange
        val duration = BufferSettings.durationRange
        return """
            <h2>${xml(context.tr(R.string.buffer_heading))}</h2>
            <form action="/admin" method="post">
                <p><label>${xml(context.tr(R.string.buffer_memory))} <input name="memoryMiB" type="number" required
                    min="${memory.first}" max="${memory.last}" value="${limits.memoryMiB}"> MiB</label></p>
                <p><label>${xml(context.tr(R.string.buffer_duration))} <input name="seconds" type="number" required
                    min="${duration.first}" max="${duration.last}" value="${limits.seconds}"> ${xml(context.tr(R.string.seconds))}</label></p>
                <button type="submit">${xml(context.tr(R.string.save_buffer))}</button>
                <p>${xml(context.tr(R.string.next_playback))}</p>
            </form>
        """.trimIndent()
    }

    private fun deviceInfo(): String {
        val memory = ActivityManager.MemoryInfo().also(activityManager::getMemoryInfo)
        val fields = listOf(
            context.tr(R.string.manufacturer) to Build.MANUFACTURER,
            context.tr(R.string.model) to Build.MODEL,
            "Android" to "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            context.tr(R.string.supported_abis) to Build.SUPPORTED_ABIS.joinToString(", "),
            context.tr(R.string.system_memory) to "${memory.totalMem / (1024 * 1024)} MiB",
            context.tr(R.string.available_memory) to "${memory.availMem / (1024 * 1024)} MiB",
            context.tr(R.string.java_heap) to "${Runtime.getRuntime().maxMemory() / (1024 * 1024)} MiB",
            context.tr(R.string.available_storage) to "${context.cacheDir.usableSpace / (1024 * 1024)} MiB",
            context.tr(R.string.app_version) to appVersion
        )
        return "<h2>${xml(context.tr(R.string.device_info))}</h2><table><tbody>" + fields.joinToString("") { (label, value) ->
            "<tr><th scope=\"row\">${xml(label)}</th><td>${xml(value)}</td></tr>"
        } + "</tbody></table>"
    }

    private fun wallForm(): String {
        val config = wallSettings.read()
        return """
            <h2>Wall Page</h2>
            <form action="/admin" method="post">
                <input type="hidden" name="settings" value="wall">
                <p><label>URL <input name="url" type="url" maxlength="4096"
                    value="${xml(config.url)}" placeholder="https://example.com"></label></p>
                <p><label>${xml(context.tr(R.string.zoom))} <input name="zoom" type="number" min="0.25" max="5"
                    step="0.01" required value="${config.zoom}"></label></p>
                <p><label>${xml(context.tr(R.string.refresh_interval))} <input name="refreshSeconds" type="number" min="0"
                    max="86400" required value="${config.refreshSeconds}"> ${xml(context.tr(R.string.refresh_unit))}</label></p>
                <button type="submit">${xml(context.tr(R.string.save_wall))}</button>
                <p>${xml(context.tr(R.string.wall_help))}</p>
                <p>${xml(context.tr(R.string.wall_navigation))}</p>
            </form>
        """.trimIndent()
    }

    private fun languageForm(): String {
        val selected = languageSettings.read()
        val choices = listOf("system" to context.tr(R.string.language_system), "en" to "English", "zh" to "中文")
        val options = choices.joinToString("") { (value, label) ->
            """<option value="$value"${if (value == selected) " selected" else ""}>${xml(label)}</option>"""
        }
        return """
            <h2>${xml(context.tr(R.string.language))}</h2>
            <form action="/admin" method="post">
                <input type="hidden" name="settings" value="language">
                <label>${xml(context.tr(R.string.language))} <select name="language">$options</select></label>
                <button type="submit">${xml(context.tr(R.string.save_language))}</button>
                <p>${xml(context.tr(R.string.language_help))}</p>
            </form>
        """.trimIndent()
    }

    private fun dashboard() = page(200, context.tr(R.string.admin_title), """
            ${languageForm()}
            ${deviceInfo()}
            ${wallForm()}
            ${bufferForm()}
            <p><a href="/admin/logs" target="_blank" rel="noopener">${xml(context.tr(R.string.view_logs))}</a></p>
            <h2>${xml(context.tr(R.string.upload_update))}</h2>
            <input id="apk" type="file" accept=".apk">
            <button id="upload" type="button">${xml(context.tr(R.string.upload))}</button>
            <p id="upload-status" role="status"></p>
            <script>
                document.getElementById("upload").onclick = async function () {
                    const file = document.getElementById("apk").files[0];
                    const status = document.getElementById("upload-status");
                    if (!file) { status.textContent = ${org.json.JSONObject.quote(context.tr(R.string.select_apk_first))}; return; }
                    this.disabled = true;
                    status.textContent = ${org.json.JSONObject.quote(context.tr(R.string.uploading))};
                    try {
                        const response = await fetch("/admin/update", {
                            method: "POST",
                            headers: {"Content-Type": "application/vnd.android.package-archive"},
                            body: file
                        });
                        status.textContent = (await response.text()) || ${org.json.JSONObject.quote(context.tr(R.string.upload_failed))};
                    } catch {
                        status.textContent = ${org.json.JSONObject.quote(context.tr(R.string.connection_lost))};
                    } finally {
                        this.disabled = false;
                    }
                };
            </script>
    """)

    private fun page(status: Int, title: String, content: String = "") = HttpResponse(
        status, """
        <!doctype html><html lang="${LanguageSettings(context).language()}"><meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <title>$title · Predestination TV Exo</title>
        <style>
            table { border-collapse: collapse; }
            th, td { border: 1px solid #888; padding: 6px 10px; text-align: left; }
        </style>
        <main><h1>$title</h1>$content</main></html>
        """.trimIndent(), mapOf(
            "Content-Type" to "text/html; charset=utf-8",
            "Cache-Control" to "no-store",
            "Content-Security-Policy" to "default-src 'none'; form-action 'self'; frame-ancestors 'none'; connect-src 'self'; style-src 'unsafe-inline'; script-src 'unsafe-inline'",
            "X-Content-Type-Options" to "nosniff"
        )
    )
}
