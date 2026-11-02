package com.moonshare.net

import android.util.Log
import com.moonshare.protocol.Protocol
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Answers IPv4 LAN discovery probes. IPv6 connections are supported by the control/video
 * sockets; IPv6 auto-discovery is best-effort and can be added without changing the wire format.
 */
class DiscoveryResponder(
    private val port: Int = Protocol.DISCOVERY_PORT,
    private val responseProvider: () -> JSONObject?
) {
    private val running = AtomicBoolean(false)
    @Volatile private var socket: DatagramSocket? = null
    @Volatile private var thread: Thread? = null

    fun start(): Boolean {
        if (!running.compareAndSet(false, true)) return false
        return try {
            val udp = DatagramSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress("0.0.0.0", port))
                soTimeout = 700
                broadcast = true
            }
            socket = udp
            thread = Thread({ loop(udp) }, "MoonShare-Discovery").also { it.start() }
            true
        } catch (t: Throwable) {
            running.set(false)
            Log.w(TAG, "discovery bind failed: ${t.message}")
            false
        }
    }

    fun stop() {
        running.set(false)
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        socket = null
        thread?.interrupt()
        thread = null
    }

    private fun loop(udp: DatagramSocket) {
        val buffer = ByteArray(512)
        while (running.get()) {
            try {
                val packet = DatagramPacket(buffer, buffer.size)
                udp.receive(packet)
                val text = String(packet.data, packet.offset, packet.length, Charsets.UTF_8).trim()
                if (text != Protocol.DISCOVER_REQUEST) continue
                val response = responseProvider()?.toString() ?: continue
                val bytes = response.toByteArray(Charsets.UTF_8)
                if (bytes.size > 1200) continue
                udp.send(DatagramPacket(bytes, bytes.size, packet.address, packet.port))
            } catch (_: SocketTimeoutException) {
                // Periodic wake-up so stop() is observed.
            } catch (t: Throwable) {
                if (running.get()) Log.w(TAG, "discovery loop: ${t.message}")
            }
        }
    }

    companion object {
        private const val TAG = "MoonShareDiscovery"
    }
}
