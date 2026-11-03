package io.jcz.predestination.exo

import org.junit.Assert.*
import org.junit.Test

class ReceiverRouterTest {
    private class Player : Renderer {
        val calls = mutableListOf<SoapAction>()

        override fun action(service: String, action: SoapAction): Map<String, String> {
            calls.add(action)
            return emptyMap()
        }

        override fun event(service: String) = emptyMap<String, String>()
    }

    private fun load(router: ReceiverRouter, uri: String = "http://192.168.1.2/music.mp3") {
        router.action(AV, SoapAction("SetAVTransportURI", mapOf("InstanceID" to "0",
            "CurrentURI" to uri, "CurrentURIMetaData" to "title")))
    }

    private fun play(router: ReceiverRouter) = router.action(AV,
        SoapAction("Play", mapOf("InstanceID" to "0", "Speed" to "1")))

    @Test fun advertisesRtspAndTransfersStreamUnchangedToPlayer() {
        var opens = 0
        val router = ReceiverRouter { opens++ }
        val sink = router.action(CM, SoapAction("GetProtocolInfo", emptyMap()))["Sink"]!!
        assertTrue(sink.split(',').contains("rtsp-rtp-udp:*:*:*"))
        assertEquals(sink, router.event(CM)["SinkProtocolInfo"])
        val uri = "rtsp://192.168.1.2:8554/live?channel=1"
        load(router, uri)
        play(router)
        assertEquals(1, opens)
        val player = Player()
        router.attach(player)
        assertEquals(listOf("SetAVTransportURI", "Play"), player.calls.map { it.name })
        assertEquals(uri, player.calls.first().args["CurrentURI"])
    }

    @Test fun backgroundCastWaitsForPlayerAndTransfersOnce() {
        var opens = 0
        val router = ReceiverRouter { opens++ }
        load(router)
        play(router)
        assertEquals(1, opens)
        assertEquals("STOPPED", router.action(AV, SoapAction("GetTransportInfo",
            mapOf("InstanceID" to "0")))["CurrentTransportState"])
        val player = Player()
        router.attach(player)
        router.attach(player)
        assertEquals(listOf("SetAVTransportURI", "Play"), player.calls.map { it.name })
    }

    @Test fun stopCancelsPendingPlayAndLatestUriWins() {
        val router = ReceiverRouter { }
        load(router)
        play(router)
        router.action(AV, SoapAction("Stop", mapOf("InstanceID" to "0")))
        load(router, "http://192.168.1.2/new.mp4")
        val player = Player()
        router.attach(player)
        assertEquals(1, player.calls.size)
        assertEquals("http://192.168.1.2/new.mp4", player.calls.single().args["CurrentURI"])
    }

    @Test fun resumeExistingPlayerDoesNotReloadUnlessNewCastArrived() {
        val router = ReceiverRouter { }
        val player = Player()
        router.attach(player)
        load(router)
        router.detach(player)
        router.attach(player)
        assertEquals(1, player.calls.size)
        router.detach(player)
        load(router, "http://192.168.1.2/new.mp4")
        play(router)
        router.attach(player)
        assertEquals(listOf("SetAVTransportURI", "SetAVTransportURI", "Play"), player.calls.map { it.name })
    }

    @Test fun invalidRequestsCannotLaunchPlayer() {
        var opens = 0
        val router = ReceiverRouter { opens++ }
        assertThrows(UpnpError::class.java) { play(router) }
        assertThrows(UpnpError::class.java) { load(router, "file:///private") }
        load(router)
        assertThrows(UpnpError::class.java) {
            router.action(AV, SoapAction("Play", mapOf("InstanceID" to "0", "Speed" to "2")))
        }
        assertEquals(0, opens)
    }
}
