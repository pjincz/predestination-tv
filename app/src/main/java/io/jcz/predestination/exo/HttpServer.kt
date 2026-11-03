package io.jcz.predestination.exo

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

internal data class HttpRequest(
    val method: String, val path: String, val headers: Map<String, String>,
    val body: ByteArray, val peer: InetAddress
)

internal data class HttpResponse(
    val status: Int = 200, val body: String = "", val headers: Map<String, String> = emptyMap()
)

/** Small, bounded HTTP/1.1 endpoint; supports the UPnP SUBSCRIBE and UNSUBSCRIBE methods. */
internal class HttpServer(
    address: InetAddress,
    preferredPort: Int = 0,
    private val upload: ((HttpRequest, InputStream, Int) -> HttpResponse)? = null,
    private val handler: (HttpRequest) -> HttpResponse
) : AutoCloseable {
    private val socket = ServerSocket().apply {
        reuseAddress = true
        try {
            bind(InetSocketAddress(address, preferredPort), 16)
        } catch (error: java.net.BindException) {
            if (preferredPort == 0) throw error
            bind(InetSocketAddress(address, 0), 16)
        }
    }
    val port: Int get() = socket.localPort
    private val clients = java.util.concurrent.ConcurrentHashMap.newKeySet<Socket>()
    private val workers = ThreadPoolExecutor(4, 4, 0, TimeUnit.SECONDS, ArrayBlockingQueue(16))
    private val acceptor = Thread({
        while (!socket.isClosed) {
            val client = try { socket.accept() } catch (_: Exception) { break }
            clients.add(client)
            try {
                workers.execute {
                    client.use {
                        var phase = "请求行"
                        try {
                            client.soTimeout = 3000
                            val input = client.getInputStream().buffered()
                            val start = line(input, 2048).split(' ')
                            require(start.size == 3 && start[2] in listOf("HTTP/1.0", "HTTP/1.1"))
                            phase = "请求头"
                            val headers = linkedMapOf<String, String>()
                            var bytes = 0
                            while (true) {
                                val line = line(input, 8192)
                                bytes += line.length + 2
                                require(bytes <= 8192)
                                if (line.isEmpty()) break
                                require(':' in line)
                                val key = line.substringBefore(':').lowercase(java.util.Locale.ROOT)
                                val value = line.substringAfter(':').trim()
                                val previous = headers[key]
                                require(previous == null || key == "cookie")
                                headers[key] = if (previous == null) value else "$previous; $value"
                            }
                            require("transfer-encoding" !in headers)
                            phase = "请求正文"
                            val size = headers["content-length"]?.toInt() ?: 0
                            if (start[1] == "/admin/update" && upload != null) {
                                val request = HttpRequest(start[0], start[1], headers, byteArrayOf(), client.inetAddress)
                                write(client, upload(request, input, size))
                                return@execute
                            }
                            require(size in 0..65536)
                            val body = ByteArray(size)
                            var offset = 0
                            while (offset < size) {
                                val count = input.read(body, offset, size - offset)
                                require(count > 0)
                                offset += count
                            }
                            val request = HttpRequest(start[0], start[1], headers, body, client.inetAddress)
                            phase = "处理请求"
                            write(client, handler(request))
                        } catch (error: Exception) {
                            if (phase == "请求行" && error is java.net.SocketTimeoutException)
                                return@execute
                            val status = if (phase == "处理请求") 500 else 400
                            val detail = if (error is java.net.SocketTimeoutException) "读取超时" else "格式无效或处理失败"
                            runCatching {
                                write(client, HttpResponse(status, "$phase：$detail",
                                    mapOf("Content-Type" to "text/plain; charset=utf-8")))
                            }
                        } finally {
                            clients.remove(client)
                        }
                    }
                }
            } catch (_: java.util.concurrent.RejectedExecutionException) {
                clients.remove(client)
                client.close()
            }
        }
    }, "dlna-http").apply { isDaemon = true; start() }

    override fun close() {
        socket.close()
        clients.forEach { runCatching { it.close() } }
        workers.shutdownNow()
    }

    companion object {
        private fun line(input: InputStream, limit: Int): String {
            val buffer = ByteArrayOutputStream()
            while (buffer.size() < limit) {
                val next = input.read()
                require(next >= 0)
                if (next == 10) return buffer.toString("US-ASCII").removeSuffix("\r")
                buffer.write(next)
            }
            error("Header too long")
        }

        private fun write(socket: Socket, response: HttpResponse) {
            val body = response.body.toByteArray(Charsets.UTF_8)
            val reason = when (response.status) {
                200 -> "OK"
                303 -> "See Other"
                400 -> "Bad Request"
                403 -> "Forbidden"
                404 -> "Not Found"
                405 -> "Method Not Allowed"
                409 -> "Conflict"
                412 -> "Precondition Failed"
                503 -> "Service Unavailable"
                else -> "Internal Server Error"
            }
            val headers = "HTTP/1.1 ${response.status} $reason\r\n" +
                (if (response.headers.keys.none { it.equals("Content-Type", true) })
                    "Content-Type: text/xml; charset=\"utf-8\"\r\n" else "") +
                "Content-Length: ${body.size}\r\n" +
                "Connection: close\r\nServer: Android/1.0 UPnP/1.0 PredestinationTV/0.1\r\n" +
                response.headers.entries.joinToString("") { "${it.key}: ${it.value}\r\n" } + "\r\n"
            socket.getOutputStream().apply { write(headers.toByteArray(Charsets.US_ASCII)); write(body); flush() }
        }
    }
}
