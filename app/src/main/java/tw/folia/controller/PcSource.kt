package tw.folia.controller

import org.json.JSONObject
import java.net.Inet4Address
import java.net.HttpURLConnection
import java.net.NetworkInterface
import java.net.URL
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/**
 * 顯示來源「電腦」: finds the Windows 流詞 on the local network and checks that it is still there.
 * The PC app already serves its whole screen over the LAN; this app just opens that address.
 * Finding it needs no extra service on the PC: every address of the home network's /24 is asked
 * for the PC app's state page on its port (39863 and the few after it).
 */
object PcSource {
    const val DEFAULT_PORT = 39863
    private const val EXTRA_PORTS = 6

    class Found(val host: String, val port: Int, val name: String) {
        val addr get() = "$host:$port"
        val label get() = if (name.isNotBlank()) "$name（$addr）" else addr
    }

    /** "192.168.1.20" or "192.168.1.20:39864" → (host, port). */
    fun parseAddr(text: String): Pair<String, Int>? {
        val clean = text.trim().removePrefix("http://").trimEnd('/')
        if (clean.isEmpty()) return null
        val host = clean.substringBefore(':')
        val port = clean.substringAfter(':', "").toIntOrNull() ?: DEFAULT_PORT
        if (host.isBlank() || port !in 1024..65535) return null
        return host to port
    }

    /** Asks one address whether it is the PC app (blocking). */
    fun probe(host: String, port: Int, timeoutMs: Int): Found? {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL("http://$host:$port/api/state").openConnection() as HttpURLConnection).apply {
                connectTimeout = timeoutMs
                readTimeout = timeoutMs + 700
                setRequestProperty("User-Agent", "Flowrics")
            }
            if (connection.responseCode != 200) return null
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)
            if (json.optString("event") != "ControllerState") return null
            Found(host, port, json.optJSONObject("data")?.optString("host").orEmpty())
        } catch (e: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    fun probeAddr(addr: String, timeoutMs: Int): Found? {
        val (host, port) = parseAddr(addr) ?: return null
        return probe(host, port, timeoutMs)
    }

    /** This device's address on the Wi-Fi / LAN (the first three numbers are what gets scanned). */
    fun localAddress(): String? {
        val candidates = ArrayList<Pair<Int, String>>()
        try {
            for (nic in NetworkInterface.getNetworkInterfaces()) {
                if (!nic.isUp || nic.isLoopback) continue
                for (address in nic.inetAddresses) {
                    if (address !is Inet4Address || address.isLoopbackAddress || address.isLinkLocalAddress) continue
                    val rank = if (nic.name.startsWith("wlan") || nic.name.startsWith("eth")) 0 else 1
                    candidates.add(rank to (address.hostAddress ?: continue))
                }
            }
        } catch (e: Exception) {
        }
        return candidates.minByOrNull { it.first }?.second
    }

    /** Every PC app answering on this network (blocking, a few seconds). */
    fun scan(): List<Found> {
        val own = localAddress() ?: return emptyList()
        val prefix = own.substringBeforeLast('.') + "."
        val ownLast = own.substringAfterLast('.').toIntOrNull() ?: -1
        val pool = Executors.newFixedThreadPool(64)
        try {
            for (ports in listOf(listOf(DEFAULT_PORT), (1..EXTRA_PORTS).map { DEFAULT_PORT + it })) {
                val jobs = ArrayList<Callable<Found?>>()
                for (i in 1..254) {
                    if (i == ownLast) continue
                    for (port in ports) jobs.add(Callable { probe(prefix + i, port, 350) })
                }
                val found = pool.invokeAll(jobs).mapNotNull { runCatching { it.get() }.getOrNull() }
                if (found.isNotEmpty()) return found
            }
            return emptyList()
        } finally {
            pool.shutdownNow()
        }
    }
}
