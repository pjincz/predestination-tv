package io.jcz.predestination.exo

import android.util.Log
import android.net.Network

import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.Socket
import java.net.URI
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal interface Renderer {
    fun action(service: String, action: SoapAction): Map<String, String>
    fun event(service: String): Map<String, String>
}

/** Owned by the receiver service (or diagnostic screen); recreated when the LAN address changes. */
internal class DlnaReceiver(
    private val address: Inet4Address, private val id: String,
    private val name: String, private val renderer: Renderer, private val network: Network?, private val admin: AdminPage
) : AutoCloseable {
    private val timer = Executors.newSingleThreadScheduledExecutor()
    private val subscriptions = ConcurrentHashMap<String, Subscription>()
    private val http: HttpServer = HttpServer(address, preferredPort = 7777, upload = { request, input, size ->
        if (closed) HttpResponse(503) else admin.upload(request, baseUrl, input, size)
    }, handler = ::request)
    private val group = InetAddress.getByName("239.255.255.250")
    private val networkInterface = NetworkInterface.getByInetAddress(address)
    private val ssdp = try {
        MulticastSocket(null).also { socket ->
            try {
                socket.reuseAddress = true
                socket.bind(InetSocketAddress(1900))
                network?.bindSocket(socket)
                socket.timeToLive = 2
                socket.networkInterface = networkInterface
                socket.joinGroup(InetSocketAddress(group, 1900), networkInterface)
            } catch (error: Exception) { socket.close(); throw error }
        }
    } catch (error: Exception) { http.close(); timer.shutdownNow(); throw error }
    private val targets = listOf("upnp:rootdevice", id, DEVICE, AV, RC, CM)
    private val location = "http://${address.hostAddress}:${http.port}/description.xml"
    val baseUrl get() = "http://${address.hostAddress}:${http.port}"
    private val services = mapOf("avtransport" to AV, "rendering" to RC, "connection" to CM)
    @Volatile private var closed = false
    private val pending = java.util.concurrent.atomic.AtomicInteger()

    private data class Subscription(
        val service: String, val callback: URI, val peer: InetAddress,
        var expires: Long, var sequence: Long = 0, var last: String = ""
    )

    init {
        Log.i("TvCast", "Receiver listening at $location")
        Thread({ discover() }, "dlna-ssdp").apply { isDaemon = true; start() }
        timer.scheduleWithFixedDelay({ runCatching { announce("ssdp:alive") } }, 0, 120, TimeUnit.SECONDS)
        timer.scheduleWithFixedDelay({ runCatching { notifySubscribers() } }, 1, 1, TimeUnit.SECONDS)
    }

    private fun usn(target: String) = if (target == id) id else "$id::$target"

    private fun discover() {
        while (!closed) {
            val packet = DatagramPacket(ByteArray(8192), 8192)
            try {
                ssdp.receive(packet)
                val text = String(packet.data, packet.offset, packet.length, Charsets.US_ASCII)
                if (!text.startsWith("M-SEARCH * HTTP/1.1\r\n")) continue
                val headers = text.lineSequence().drop(1).filter { ':' in it }.associate {
                    it.substringBefore(':').lowercase(java.util.Locale.ROOT) to it.substringAfter(':').trim()
                }
                if (!headers["man"].equals("\"ssdp:discover\"", true)) continue
                val target = headers["st"] ?: continue
                val matches = if (target == "ssdp:all") targets else targets.filter { it == target }
                if (matches.isEmpty() || packet.address == address) continue
                val delay = (headers["mx"]?.toIntOrNull() ?: 1).coerceIn(1, 3) * 1000L
                // A bounded number of pending discovery replies avoids a multicast flood queue.
                if (pending.incrementAndGet() > 32) { pending.decrementAndGet(); continue }
                timer.schedule({
                    try {
                        matches.forEach {
                            val response = "HTTP/1.1 200 OK\r\nCACHE-CONTROL: max-age=300\r\n" +
                                "EXT:\r\nLOCATION: $location\r\nST: $it\r\nUSN: ${usn(it)}\r\n" +
                                "SERVER: Android/1.0 UPnP/1.0 PredestinationTV/0.1\r\n\r\n"
                            send(response, packet.address, packet.port)
                        }
                    } catch (_: Exception) { } finally { pending.decrementAndGet() }
                }, kotlin.random.Random.nextLong(delay), TimeUnit.MILLISECONDS)
            } catch (_: Exception) {
                if (!closed) runCatching { Thread.sleep(100) }
            }
        }
    }

    private fun send(text: String, target: InetAddress = group, port: Int = 1900) {
        val bytes = text.toByteArray(Charsets.US_ASCII)
        ssdp.send(DatagramPacket(bytes, bytes.size, target, port))
    }

    private fun announce(kind: String) {
        targets.forEach {
            send("NOTIFY * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nNT: $it\r\n" +
                "NTS: $kind\r\nUSN: ${usn(it)}\r\nLOCATION: $location\r\n" +
                "CACHE-CONTROL: max-age=300\r\nSERVER: Android/1.0 UPnP/1.0 PredestinationTV/0.1\r\n\r\n")
        }
    }

    private fun description() = "<?xml version=\"1.0\"?>" +
        "<root xmlns=\"urn:schemas-upnp-org:device-1-0\"><specVersion><major>1</major><minor>0</minor>" +
        "</specVersion><device><deviceType>$DEVICE</deviceType><friendlyName>${xml(name)}</friendlyName>" +
        "<manufacturer>Predestination</manufacturer><modelName>ExoPlayer Android TV</modelName>" +
        "<UDN>$id</UDN><serviceList>" + services.entries.joinToString("") { (path, type) ->
            val short = type.split(':')[3]
            "<service><serviceType>$type</serviceType><serviceId>urn:upnp-org:serviceId:$short</serviceId>" +
                "<SCPDURL>/$path/scpd.xml</SCPDURL><controlURL>/$path/control</controlURL>" +
                "<eventSubURL>/$path/event</eventSubURL></service>"
        } + "</serviceList></device></root>"

    private fun request(request: HttpRequest): HttpResponse {
        Log.d("TvCast", "${request.peer.hostAddress} ${request.method} ${request.path.substringBefore('?')}")
        if (closed) return HttpResponse(503)
        if (request.path == "/admin" || request.path == "/admin/logs") return admin.handle(request, baseUrl)
        if (request.method == "GET" && request.path == "/description.xml")
            return HttpResponse(body = description())
        val parts = request.path.trim('/').split('/')
        val service = services[parts.firstOrNull()] ?: return HttpResponse(404)
        if (parts.size != 2) return HttpResponse(404)
        return when {
            request.method == "GET" && parts[1] == "scpd.xml" -> HttpResponse(body = Protocol.scpd(service))
            request.method == "POST" && parts[1] == "control" -> {
                try {
                    val action = try {
                        Protocol.parse(request.body, service, request.headers["soapaction"].orEmpty())
                    } catch (error: Exception) {
                        Log.w("TvCast", "SOAP parse ${error.javaClass.simpleName}: " +
                            error.stackTrace.take(5).joinToString(" <- "))
                        throw UpnpError(402, "Invalid Args")
                    }
                    Log.i("TvCast", "SOAP ${action.name} received")
                    android.util.Log.d("DLNA", "${request.peer.hostAddress} ${action.name}")
                    Protocol.validate(service, action)
                    val result = renderer.action(service, action)
                    if (service == AV && action.name == "SetAVTransportURI") {
                        val uri = action.args.getValue("CurrentURI")
                        if (uri.isNotEmpty()) {
                            val safeUrl = uri.substringBefore('?').substringBefore('#')
                            AppLog.record("TvExoCast", "Sender=${request.peer.hostAddress} url=$safeUrl")
                        }
                    }
                    HttpResponse(body = Protocol.response(service, action.name, result))
                } catch (error: UpnpError) {
                    android.util.Log.d("DLNA", "Rejected ${error.code}: ${error.message}")
                    Log.w("TvCast", "SOAP rejected: ${error.message}")
                    HttpResponse(500, Protocol.fault(error))
                } catch (error: Exception) {
                    android.util.Log.d("DLNA", "Failed: ${error.javaClass.simpleName}: ${error.message}")
                    Log.w("TvCast", "SOAP failed: ${error.javaClass.simpleName}")
                    HttpResponse(500, Protocol.fault(UpnpError(501, "Action Failed")))
                }
            }
            parts[1] == "event" -> subscribe(request, service)
            else -> HttpResponse(405)
        }
    }

    @Synchronized private fun subscribe(request: HttpRequest, service: String): HttpResponse {
        val sid = request.headers["sid"]
        val old = subscriptions[sid.orEmpty()]
        if (request.method == "UNSUBSCRIBE") {
            if (old == null || old.service != service || old.peer != request.peer)
                return HttpResponse(412)
            subscriptions.remove(sid)
            return HttpResponse()
        }
        if (request.method != "SUBSCRIBE") return HttpResponse(405)
        val expires = System.nanoTime() + TimeUnit.SECONDS.toNanos(300)
        if (sid != null) {
            if (old == null || old.service != service || old.peer != request.peer ||
                "callback" in request.headers || "nt" in request.headers) return HttpResponse(412)
            old.expires = expires
            return HttpResponse(headers = mapOf("SID" to sid, "TIMEOUT" to "Second-300"))
        }
        if (request.headers["nt"] != "upnp:event" || subscriptions.size >= 16) return HttpResponse(412)
        val callback = runCatching { URI(request.headers["callback"].orEmpty().removeSurrounding("<", ">")) }
            .getOrNull() ?: return HttpResponse(412)
        // A controller may subscribe only its own HTTP endpoint, never arbitrary LAN services.
        if (callback.scheme != "http" || callback.userInfo != null || callback.fragment != null ||
            callback.host != request.peer.hostAddress || callback.port !in 1..65535) return HttpResponse(412)
        val newSid = "uuid:${UUID.randomUUID()}"
        subscriptions[newSid] = Subscription(service, callback, request.peer, expires)
        return HttpResponse(headers = mapOf("SID" to newSid, "TIMEOUT" to "Second-300"))
    }

    private fun notifySubscribers() {
        val events = mutableMapOf<String, String>()
        subscriptions.entries.forEach { (sid, sub) ->
            if (System.nanoTime() >= sub.expires) { subscriptions.remove(sid); return@forEach }
            val event = events.getOrPut(sub.service) {
                renderer.event(sub.service).entries.joinToString("") {
                    "<e:property><${it.key}>${xml(it.value)}</${it.key}></e:property>"
                }
            }
            if (event == sub.last) return@forEach
            try {
                val body = ("<?xml version=\"1.0\"?><e:propertyset xmlns:e=\"urn:schemas-upnp-org:event-1-0\">" +
                    "$event</e:propertyset>").toByteArray(Charsets.UTF_8)
                Socket().use { socket ->
                    network?.bindSocket(socket)
                    socket.connect(InetSocketAddress(sub.peer, sub.callback.port), 500)
                    socket.soTimeout = 500
                    val path = sub.callback.rawPath.ifEmpty { "/" } +
                        (sub.callback.rawQuery?.let { "?$it" } ?: "")
                    val header = "NOTIFY $path HTTP/1.1\r\nHOST: ${sub.callback.rawAuthority}\r\n" +
                        "CONTENT-TYPE: text/xml; charset=\"utf-8\"\r\nCONTENT-LENGTH: ${body.size}\r\n" +
                        "NT: upnp:event\r\nNTS: upnp:propchange\r\nSID: $sid\r\nSEQ: ${sub.sequence}\r\n" +
                        "CONNECTION: close\r\n\r\n"
                    socket.getOutputStream().apply { write(header.toByteArray()); write(body); flush() }
                    val response = socket.getInputStream().bufferedReader().readLine().orEmpty()
                    if (!response.startsWith("HTTP/1.1 200") && !response.startsWith("HTTP/1.0 200"))
                        error("Notification refused")
                }
                sub.last = event
                sub.sequence = if (sub.sequence == 0xffffffffL) 1 else sub.sequence + 1
            } catch (_: Exception) { subscriptions.remove(sid) }
        }
    }

    override fun close() {
        closed = true
        runCatching { announce("ssdp:byebye") }
        ssdp.close()
        http.close()
        subscriptions.clear()
        timer.shutdownNow()
    }
}


