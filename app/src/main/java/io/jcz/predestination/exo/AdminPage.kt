package io.jcz.predestination.exo

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.SystemClock
import java.security.MessageDigest
import android.util.Base64
import java.security.SecureRandom

private const val SESSION_SECONDS = 8 * 60 * 60
private const val APPROVAL_SECONDS = 120

internal class AdminPage(private val context: Context) {
    private val preferences = context.getSharedPreferences("admin", Context.MODE_PRIVATE)
    private val update = ApkUpdate(context)
    private val activityManager = context.getSystemService(ActivityManager::class.java)
    private val appVersion = context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
    internal data class Approval(val id: String, val code: String, val peer: String)
    private data class Pending(val approval: Approval, val expires: Long, var approved: Boolean? = null)

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
        val token = cookie(request, "admin_session") ?: return false
        val digest = sessionDigest(token)
        return System.currentTimeMillis() < preferences.getLong("session:$digest", 0L)
    }

    @Synchronized
    fun awaitingApproval(): Approval? = pending?.takeIf {
        it.approved == null && SystemClock.elapsedRealtime() < it.expires
    }?.approval

    @Synchronized
    fun resolve(approval: Approval, approved: Boolean) {
        if (awaitingApproval() == approval) pending?.approved = approved
    }

    fun upload(request: HttpRequest, baseUrl: String, input: java.io.InputStream, size: Int): HttpResponse {
        if (request.method != "POST") return HttpResponse(405)
        val authorized = synchronized(this) {
            request.headers["host"] == baseUrl.removePrefix("http://") &&
                request.headers["origin"] == baseUrl && authenticated(request)
        }
        if (!authorized) return HttpResponse(403, "请重新登录")
        if (request.headers["content-type"] != "application/vnd.android.package-archive")
            return HttpResponse(400, "请选择 APK 文件")
        AppLog.record("TvExoUpdate", "Upload started: $size bytes")
        val result = update.receive(input, size)
        AppLog.record("TvExoUpdate", "Upload result: ${result.status}")
        return result.copy(headers = mapOf("Content-Type" to "text/plain; charset=utf-8", "Cache-Control" to "no-store"))
    }

    @Synchronized
    fun handle(request: HttpRequest, baseUrl: String): HttpResponse {
        if (request.headers["host"] != baseUrl.removePrefix("http://")) return page(403, "地址无效")
        if (request.method != "GET") return page(405, "请求方法不支持")
        if (request.headers["sec-fetch-site"] == "cross-site") return page(403, "请直接打开管理员地址")
        val now = SystemClock.elapsedRealtime()
        if (request.path == "/admin/logs") {
            if (!authenticated(request)) return page(403, "请先在电视上授权")
            return HttpResponse(body = AppLog.read(), headers = mapOf(
                "Content-Type" to "text/plain; charset=utf-8",
                "Cache-Control" to "no-store",
                "X-Content-Type-Options" to "nosniff",
                "Content-Security-Policy" to "default-src 'none'; frame-ancestors 'none'"
            ))
        }
        if (authenticated(request)) return dashboard()

        val claim = cookie(request, "admin_approval")
        val current = pending
        if (claim != null) {
            if (current == null || current.approval.id != claim || current.expires <= now ||
                current.approved == false) {
                return page(403, "授权已拒绝或过期", """<p><a href="/admin">重新请求授权</a></p>""").withCookie("admin_approval", "", 0)
            }
            if (current.approved == true) {
                val session = token()
                if (!saveSession(session)) {
                    return page(503, "无法保存授权，请重试")
                }
                pending = null
                return dashboard().withCookie("admin_session", session, SESSION_SECONDS)
            }
            return waiting(current.approval)
        }
        if (current != null && current.expires > now && current.approved != false)
            return page(409, "电视正在处理另一条授权请求")

        val approval = Approval(token(), (1000 + random.nextInt(9000)).toString(),
            request.peer.hostAddress.orEmpty())
        pending = Pending(approval, now + APPROVAL_SECONDS * 1000L)
        return waiting(approval).withCookie("admin_approval", approval.id, APPROVAL_SECONDS)
    }

    private fun saveSession(session: String): Boolean {
        val now = System.currentTimeMillis()
        val editor = preferences.edit()
        for ((key, value) in preferences.all) {
            if (key.startsWith("session:") && value is Long && value <= now) editor.remove(key)
        }
        return editor
            .putLong("session:${sessionDigest(session)}", now + SESSION_SECONDS * 1000L)
            .commit()
    }

    private fun waiting(approval: Approval) = page(200, "等待电视授权", """
        <p>请在电视上核对配对码 <strong>${approval.code}</strong>，然后用遥控器确认。</p>
        <script>setTimeout(() => location.reload(), 1500);</script>
    """)

    private fun HttpResponse.withCookie(name: String, value: String, seconds: Int) =
        copy(headers = headers + ("Set-Cookie" to "$name=$value; Path=/admin; HttpOnly; SameSite=Strict; Max-Age=$seconds"))

    private fun deviceInfo(): String {
        val memory = ActivityManager.MemoryInfo().also(activityManager::getMemoryInfo)
        val fields = listOf(
            "厂商" to Build.MANUFACTURER,
            "型号" to Build.MODEL,
            "Android" to "${Build.VERSION.RELEASE}（API ${Build.VERSION.SDK_INT}）",
            "设备支持的 ABI" to Build.SUPPORTED_ABIS.joinToString(", "),
            "系统内存" to "${memory.totalMem / (1024 * 1024)} MiB",
            "当前可用内存" to "${memory.availMem / (1024 * 1024)} MiB",
            "应用 Java 堆上限" to "${Runtime.getRuntime().maxMemory() / (1024 * 1024)} MiB",
            "内部存储可用空间" to "${context.cacheDir.usableSpace / (1024 * 1024)} MiB",
            "应用版本" to appVersion
        )
        return "<h2>电视信息</h2><table><tbody>" + fields.joinToString("") { (label, value) ->
            "<tr><th scope=\"row\">${xml(label)}</th><td>${xml(value)}</td></tr>"
        } + "</tbody></table>"
    }

    private fun dashboard() = page(200, "管理员页面", """
            ${deviceInfo()}
            <p><a href="/admin/logs" target="_blank" rel="noopener">查看运行日志</a></p>
            <h2>上传 APK 更新</h2>
            <input id="apk" type="file" accept=".apk">
            <button id="upload" type="button">上传</button>
            <p id="upload-status" role="status"></p>
            <script>
                document.getElementById("upload").onclick = async function () {
                    const file = document.getElementById("apk").files[0];
                    const status = document.getElementById("upload-status");
                    if (!file) { status.textContent = "请先选择 APK"; return; }
                    this.disabled = true;
                    status.textContent = "正在上传";
                    try {
                        const response = await fetch("/admin/update", {
                            method: "POST",
                            headers: {"Content-Type": "application/vnd.android.package-archive"},
                            body: file
                        });
                        status.textContent = (await response.text()) || "上传失败";
                    } catch {
                        status.textContent = "连接中断，请重试";
                    } finally {
                        this.disabled = false;
                    }
                };
            </script>
    """)

    private fun page(status: Int, title: String, content: String = "") = HttpResponse(
        status, """
        <!doctype html><html lang="zh-CN"><meta charset="utf-8">
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
