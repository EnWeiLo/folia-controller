package tw.folia.controller

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.ServerSocket
import kotlin.concurrent.thread

class PcSourceTest {
    /** A one-purpose web server: answers every request with [body]. */
    private class FakeServer(private val body: String, private val status: String = "200 OK") : AutoCloseable {
        private val socket = ServerSocket(0, 10, java.net.InetAddress.getByName("127.0.0.1"))
        val port get() = socket.localPort

        init {
            thread(isDaemon = true) {
                while (!socket.isClosed) {
                    try {
                        socket.accept().use { client ->
                            val reader = client.getInputStream().bufferedReader()
                            while (true) {
                                val line = reader.readLine() ?: break
                                if (line.isEmpty()) break
                            }
                            val bytes = body.toByteArray()
                            client.getOutputStream().apply {
                                write("HTTP/1.1 $status\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                                write(bytes)
                                flush()
                            }
                        }
                    } catch (e: Exception) {
                    }
                }
            }
        }

        override fun close() = socket.close()
    }

    @Test
    fun parsesAddresses() {
        assertEquals("192.168.1.20" to 39863, PcSource.parseAddr("192.168.1.20"))
        assertEquals("192.168.1.20" to 39864, PcSource.parseAddr(" http://192.168.1.20:39864/ "))
        assertNull(PcSource.parseAddr(""))
        assertNull(PcSource.parseAddr("pc:80"))
    }

    @Test
    fun recognisesThePcApp() {
        FakeServer("""{"event":"ControllerState","data":{"host":"MY-PC","version":"1.0.1"}}""").use { server ->
            val found = PcSource.probe("127.0.0.1", server.port, 1000)
            assertNotNull(found)
            assertEquals("MY-PC", found!!.name)
            assertEquals("127.0.0.1:${server.port}", found.addr)
        }
        assertNull(PcSource.probe("127.0.0.1", 1, 300))
    }

    @Test
    fun ignoresOtherWebServers() {
        FakeServer("hello").use { server ->
            assertNull(PcSource.probe("127.0.0.1", server.port, 1000))
        }
        FakeServer("{}", "404 Not Found").use { server ->
            assertNull(PcSource.probe("127.0.0.1", server.port, 1000))
        }
    }
}
