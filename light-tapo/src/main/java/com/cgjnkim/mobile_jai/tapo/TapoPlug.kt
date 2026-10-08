package com.cgjnkim.mobile_jai.tapo

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException

/**
 * A TP-Link Tapo smart plug (P110 on the rig) switched over its local KLAP API, used to
 * switch a lamp as the white light of a flash comparison.
 *
 * The plug is reached over Wi-Fi, and not necessarily the phone's main Wi-Fi: unbound, it
 * runs its own access point (`Tapo_Plug_XXXX`, plug at 192.168.0.1) that the phone joins as
 * a second Wi-Fi beside the lab's. Android routes by network rather than by address, so
 * every socket here is bound to the [Network] the plug was found on; a plain socket to
 * 192.168.0.1 would leave by the main Wi-Fi.
 *
 * HTTP is spoken by hand over that socket. The plug only speaks plain HTTP, which the
 * platform's HttpURLConnection refuses without a cleartext exception for the whole app.
 *
 * Blocking calls belong off the main thread.
 */
class TapoPlug private constructor(
    val network: Network,
    val address: Inet4Address,
    val model: String,
    val mac: String,
    private val port: Int,
) : AutoCloseable {

    @Volatile var isOn = false
        private set

    private var session: Klap? = null
    private var cookie: String? = null
    private val lock = Any()

    override fun toString() = "Tapo $model $mac @ ${address.hostAddress}"

    /** Switches the plug, and returns whether it is now in that state. */
    fun set(on: Boolean): Boolean = synchronized(lock) {
        request("""{"method":"set_device_info","params":{"device_on":$on}}""")
        isOn = on
        true
    }

    /** Asks the plug whether it is on. */
    fun refresh(): Boolean = synchronized(lock) {
        isOn = DEVICE_ON.find(request("""{"method":"get_device_info"}"""))?.groupValues?.get(1) == "true"
        isOn
    }

    override fun close() = synchronized(lock) {
        session = null
        cookie = null
    }

    // ---- KLAP ---------------------------------------------------------------------------

    /**
     * Sends one request, handshaking first if there is no session. A failed request is
     * retried once on a fresh session: the plug forgets sessions when it reboots, and after
     * 24 hours.
     */
    private fun request(json: String): String {
        repeat(2) { attempt ->
            try {
                val k = session ?: handshake()
                val (body, seq) = k.encrypt(json)
                val answer = k.decrypt(post("/app/request?seq=$seq", body), seq)
                val code = ERROR_CODE.find(answer)?.groupValues?.get(1)?.toInt() ?: 0
                if (code != 0) throw IOException("plug answered error_code $code to $json")
                return answer
            } catch (e: IOException) {
                session = null
                if (attempt == 1) throw e
                Log.w(TAG, "retrying on a new session: ${e.message}")
            }
        }
        throw IllegalStateException()
    }

    private fun handshake(): Klap {
        cookie = null
        val local = Klap.newSeed()
        val answer = post("/app/handshake1", local)
        val auth = Klap.match(local, answer, Klap.DEFAULT_CREDENTIALS.map { (u, p) -> Klap.authHash(u, p) })
            ?: throw IOException("$this is bound to a Tapo account; only an unbound plug is supported")
        val k = Klap(local, answer.copyOf(16), auth)
        post("/app/handshake2", k.handshake2())
        session = k
        return k
    }

    /** One HTTP/1.1 POST over a socket bound to [network]; returns the body of a 200. */
    private fun post(path: String, body: ByteArray): ByteArray {
        network.socketFactory.createSocket().use { s ->
            s.connect(InetSocketAddress(address, port), TIMEOUT_MS)
            s.soTimeout = TIMEOUT_MS
            val head = buildString {
                append("POST $path HTTP/1.1\r\n")
                append("Host: ${address.hostAddress}\r\n")
                append("Content-Type: application/octet-stream\r\n")
                append("Content-Length: ${body.size}\r\n")
                cookie?.let { append("Cookie: $it\r\n") }
                append("Connection: close\r\n\r\n")
            }
            s.getOutputStream().apply { write(head.toByteArray()); write(body); flush() }

            val input = s.getInputStream()
            val status = readLine(input)
            val code = status.split(' ').getOrNull(1)?.toIntOrNull() ?: throw IOException("bad status: $status")
            var length = -1
            while (true) {
                val line = readLine(input)
                if (line.isEmpty()) break
                val (name, value) = line.split(':', limit = 2).let { it[0].trim().lowercase() to it.getOrElse(1) { "" }.trim() }
                if (name == "content-length") length = value.toInt()
                if (name == "set-cookie") cookie = value.substringBefore(';')
            }
            // Not readNBytes: that is API 33.
            val out = if (length < 0) input.readBytes() else ByteArray(length).also { b ->
                var off = 0
                while (off < length) {
                    val n = input.read(b, off, length - off)
                    if (n < 0) throw IOException("connection closed mid-body")
                    off += n
                }
            }
            if (code != 200) throw IOException("$path -> HTTP $code")
            return out
        }
    }

    private fun readLine(input: InputStream): String {
        val b = ByteArrayOutputStream()
        while (true) {
            val c = input.read()
            if (c < 0) throw IOException("connection closed mid-header")
            if (c == '\n'.code) break
            if (c != '\r'.code) b.write(c)
        }
        return b.toString(Charsets.US_ASCII.name())
    }

    /** What a plug says about itself in answer to the UDP 20002 probe. */
    data class Discovery(val type: String, val model: String, val mac: String, val encryption: String, val port: Int)

    companion object {
        private const val TAG = "TapoPlug"
        private const val DISCOVERY_PORT = 20002
        private const val TIMEOUT_MS = 2000
        private const val DISCOVERY_WAIT_MS = 800L

        /** The plain discovery probe python-kasa sends to UDP 20002: a 16-byte header, no body. */
        private val PROBE = byteArrayOf(2, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0x46, 0x3c, 0xb5.toByte(), 0xd3.toByte())

        /** Where an unbound plug sits on its own access point. */
        private val AP_ADDRESS = InetAddress.getByName("192.168.0.1") as Inet4Address

        private val DEVICE_ON = Regex(""""device_on"\s*:\s*(true|false)""")
        private val ERROR_CODE = Regex(""""error_code"\s*:\s*(-?\d+)""")

        /**
         * Whether the last [find] was refused the Wi-Fi networks by a VPN: the fix is to
         * exclude the app from the VPN (Tailscale: Settings, Split tunneling).
         */
        @Volatile var blockedByVpn = false
            private set

        /**
         * Looks for a Tapo plug on every Wi-Fi network the phone is on, returning the first
         * that answers. Each network is asked by broadcast (a plug bound to a router) and at
         * 192.168.0.1 (an unbound plug's own access point).
         */
        fun find(context: Context): TapoPlug? {
            blockedByVpn = false
            val cm = context.getSystemService(ConnectivityManager::class.java)
            @Suppress("DEPRECATION")
            val wifi = cm.allNetworks.filter {
                cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            }
            for (n in wifi) {
                try {
                    discover(n)?.let { return it }
                } catch (e: IOException) {
                    // EPERM: a VPN that covers this app and does not allow bypassing (Tailscale
                    // does not) forbids binding to any other network, the plug's included.
                    if (e.message?.contains("EPERM") == true) blockedByVpn = true
                    Log.w(TAG, "discovery on $n: ${e.message}")
                }
            }
            return null
        }

        private fun discover(network: Network): TapoPlug? {
            DatagramSocket(null).use { s ->
                s.broadcast = true
                s.bind(InetSocketAddress(0))
                network.bindSocket(s)
                for (to in listOf(InetAddress.getByName("255.255.255.255"), AP_ADDRESS)) {
                    runCatching { s.send(DatagramPacket(PROBE, PROBE.size, to, DISCOVERY_PORT)) }
                }
                val deadline = System.nanoTime() + DISCOVERY_WAIT_MS * 1_000_000
                val buf = ByteArray(4096)
                while (true) {
                    val left = (deadline - System.nanoTime()) / 1_000_000
                    if (left <= 0) return null
                    s.soTimeout = left.toInt().coerceAtLeast(1)
                    val p = DatagramPacket(buf, buf.size)
                    try {
                        s.receive(p)
                    } catch (_: SocketTimeoutException) {
                        return null
                    }
                    if (p.length <= 16) continue
                    val reply = parseDiscovery(String(buf, 16, p.length - 16)) ?: continue
                    if (!reply.type.contains("PLUG")) continue
                    if (reply.encryption != "KLAP") {
                        Log.w(TAG, "${reply.model} speaks ${reply.encryption}, not KLAP")
                        continue
                    }
                    val from = p.address as? Inet4Address ?: continue
                    Log.i(TAG, "found ${reply.model} ${reply.mac} @ ${from.hostAddress} on $network")
                    return TapoPlug(network, from, reply.model, reply.mac, reply.port)
                }
            }
        }

        /** The JSON of a discovery reply, read with regexes: org.json is a stub in unit tests. */
        fun parseDiscovery(json: String): Discovery? {
            fun str(k: String) = Regex(""""$k"\s*:\s*"([^"]*)"""").find(json)?.groupValues?.get(1)
            val type = str("device_type") ?: return null
            return Discovery(
                type = type,
                model = str("device_model") ?: "?",
                mac = str("mac") ?: "?",
                encryption = str("encrypt_type") ?: "?",
                port = Regex(""""http_port"\s*:\s*(\d+)""").find(json)?.groupValues?.get(1)?.toInt() ?: 80,
            )
        }
    }
}
