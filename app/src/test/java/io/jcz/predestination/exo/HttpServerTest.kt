package io.jcz.predestination.exo

import java.net.InetAddress
import java.net.Socket
import org.junit.Assert.*
import org.junit.Test

class HttpServerTest {
    @Test fun acceptsGenaMethodsAndUtf8Bodies() {
        val address = InetAddress.getLoopbackAddress()
        HttpServer(address) { request ->
            assertEquals("SUBSCRIBE", request.method)
            assertEquals("/avtransport/event", request.path)
            assertEquals("投屏", request.body.toString(Charsets.UTF_8))
            HttpResponse(headers = mapOf("SID" to "uuid:test"))
        }.use { server ->
            Socket(address, server.port).use { socket ->
                socket.soTimeout = 3000
                socket.getOutputStream().write(("SUBSCRIBE /avtransport/event HTTP/1.1\r\n" +
                    "Content-Length: 6\r\n\r\n投屏").toByteArray())
                val response = socket.getInputStream().bufferedReader().readText()
                assertTrue(response.startsWith("HTTP/1.1 200"))
                assertTrue(response.contains("SID: uuid:test"))
            }
        }
    }

    @Test fun rejectsOversizedAndAmbiguousRequests() {
        val address = InetAddress.getLoopbackAddress()
        HttpServer(address) { error("Rejected request reached handler") }.use { server ->
            listOf("Content-Length: 65537", "Content-Length: -1",
                "Content-Length: 0\r\nContent-Length: 1", "Transfer-Encoding: chunked").forEach { headers ->
                Socket(address, server.port).use { socket ->
                    socket.soTimeout = 3000
                    socket.getOutputStream().write("POST /control HTTP/1.1\r\n$headers\r\n\r\n".toByteArray())
                    assertTrue(socket.getInputStream().bufferedReader().readLine().startsWith("HTTP/1.1 400"))
                }
            }
        }
    }
}

