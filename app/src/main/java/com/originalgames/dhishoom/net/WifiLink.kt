package com.originalgames.dhishoom.net

import android.content.Context
import android.net.wifi.WifiManager
import java.io.Closeable
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Local Wi-Fi / hotspot transport. No internet server is ever contacted: the
 * host advertises itself with a UDP broadcast beacon on the local subnet, the
 * client listens for that beacon to discover the host's address automatically,
 * then the two connect over a plain TCP socket. This works whether both phones
 * share a Wi-Fi network or one phone runs a hotspot the other joined.
 */
object WifiLink {

    private const val TCP_PORT = 48010
    private const val UDP_PORT = 48011
    private const val BEACON = "DHISHOOM-HOST"
    private const val CONNECT_TIMEOUT_MS = 4000

    /** Host: beacon on UDP, accept one TCP client, hand back a [NetLink]. */
    @Suppress("UNUSED_PARAMETER") // kept for call-site symmetry with join()/Bluetooth
    fun host(
        context: Context,
        onState: (NetState) -> Unit,
        onLink: (NetLink) -> Unit
    ): Closeable {
        val cancelled = AtomicBoolean(false)
        var server: ServerSocket? = null
        var beacon: DatagramSocket? = null

        onState(NetState.LISTENING)

        thread(isDaemon = true, name = "wifi-host") {
            try {
                val srv = ServerSocket(TCP_PORT)
                server = srv

                // Broadcast our presence until someone connects.
                val bcn = DatagramSocket().apply { broadcast = true }
                beacon = bcn
                val payload = "$BEACON:$TCP_PORT".toByteArray()
                thread(isDaemon = true, name = "wifi-beacon") {
                    val addr = InetAddress.getByName("255.255.255.255")
                    while (!cancelled.get()) {
                        try {
                            bcn.send(DatagramPacket(payload, payload.size, addr, UDP_PORT))
                        } catch (_: Throwable) {
                        }
                        try { Thread.sleep(600) } catch (_: Throwable) { break }
                    }
                }

                val socket = srv.accept()
                cancelled.set(true)
                try { bcn.close() } catch (_: Throwable) {}

                socket.tcpNoDelay = true
                val link = StreamLink(socket.getInputStream(), socket.getOutputStream()) {
                    try { socket.close() } catch (_: Throwable) {}
                }
                link.onState = onState
                onLink(link)
                link.start()
            } catch (_: Throwable) {
                if (!cancelled.get()) onState(NetState.FAILED)
            }
        }

        return Closeable {
            cancelled.set(true)
            try { server?.close() } catch (_: Throwable) {}
            try { beacon?.close() } catch (_: Throwable) {}
        }
    }

    /** Client: listen for the host beacon, then TCP-connect to it. */
    fun join(
        context: Context,
        onState: (NetState) -> Unit,
        onLink: (NetLink) -> Unit
    ): Closeable {
        val cancelled = AtomicBoolean(false)
        var udp: DatagramSocket? = null
        var tcp: Socket? = null
        var lock: WifiManager.MulticastLock? = null

        onState(NetState.CONNECTING)

        thread(isDaemon = true, name = "wifi-join") {
            try {
                // Some devices need a multicast lock to receive broadcast packets.
                lock = try {
                    val wifi = context.applicationContext
                        .getSystemService(Context.WIFI_SERVICE) as? WifiManager
                    wifi?.createMulticastLock("dhishoom")?.apply { setReferenceCounted(false); acquire() }
                } catch (_: Throwable) { null }

                val sock = DatagramSocket(null).apply {
                    reuseAddress = true
                    broadcast = true
                    soTimeout = 1000
                    bind(InetSocketAddress(UDP_PORT))
                }
                udp = sock

                var hostAddr: InetAddress? = null
                var hostPort = TCP_PORT
                val buf = ByteArray(64)
                while (!cancelled.get() && hostAddr == null) {
                    try {
                        val pkt = DatagramPacket(buf, buf.size)
                        sock.receive(pkt)
                        val text = String(pkt.data, 0, pkt.length)
                        if (text.startsWith(BEACON)) {
                            hostAddr = pkt.address
                            hostPort = text.substringAfter(":", "$TCP_PORT").toIntOrNull() ?: TCP_PORT
                        }
                    } catch (_: java.net.SocketTimeoutException) {
                        // keep waiting for the next beacon
                    }
                }
                try { sock.close() } catch (_: Throwable) {}
                try { lock?.release() } catch (_: Throwable) {}

                val target = hostAddr
                if (cancelled.get() || target == null) {
                    if (!cancelled.get()) onState(NetState.FAILED)
                    return@thread
                }

                val socket = Socket()
                tcp = socket
                socket.connect(InetSocketAddress(target, hostPort), CONNECT_TIMEOUT_MS)
                socket.tcpNoDelay = true

                val link = StreamLink(socket.getInputStream(), socket.getOutputStream()) {
                    try { socket.close() } catch (_: Throwable) {}
                }
                link.onState = onState
                onLink(link)
                link.start()
            } catch (_: Throwable) {
                if (!cancelled.get()) onState(NetState.FAILED)
            }
        }

        return Closeable {
            cancelled.set(true)
            try { udp?.close() } catch (_: Throwable) {}
            try { tcp?.close() } catch (_: Throwable) {}
            try { lock?.release() } catch (_: Throwable) {}
        }
    }

    /** This device's local IPv4 address (for showing on the host screen). */
    fun localIp(): String? {
        return try {
            NetworkInterface.getNetworkInterfaces().toList()
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { !it.isLoopbackAddress && it.address.size == 4 && it.isSiteLocalAddress }
                ?.hostAddress
        } catch (_: Throwable) {
            null
        }
    }
}
