package tw.folia.controller

import com.sun.net.httpserver.HttpServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.InetSocketAddress

class PcSourceTest {
    @Test
    fun parsesAddresses() {
        assertEquals("192.168.1.20" to 39863, PcSource.parseAddr("192.168.1.20"))
        assertEquals("192.168.1.20" to 39864, PcSource.parseAddr(" http://192.168.1.20:39864/ "))
        assertNull(PcSource.parseAddr(""))
        assertNull(PcSource.parseAddr("pc:80"))
    }

    @Test
    fun recognisesThePcApp() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/state") { ex ->
            val body = """{"event":"ControllerState","data":{"host":"MY-PC","version":"1.0.1"}}""".toByteArray()
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        server.createContext("/other") { ex ->
            ex.sendResponseHeaders(404, -1)
            ex.close()
        }
        server.start()
        try {
            val port = server.address.port
            val found = PcSource.probe("127.0.0.1", port, 1000)
            assertNotNull(found)
            assertEquals("MY-PC", found!!.name)
            assertEquals("127.0.0.1:$port", found.addr)
            assertNull(PcSource.probe("127.0.0.1", 1, 300))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun ignoresOtherWebServers() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            val body = "hello".toByteArray()
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            assertNull(PcSource.probe("127.0.0.1", server.address.port, 1000))
        } finally {
            server.stop(0)
        }
    }
}
