package com.moonshare.net

import android.os.SystemClock
import android.util.Log
import com.moonshare.protocol.Protocol
import com.moonshare.protocol.VideoFrame
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Binds the video UDP port in the same address family as the paired control client,
 * waits for the authenticated HELLO from the viewer, then fragments H.264 access units.
 *
 * The viewer must send first so the return path traverses its stateful firewall/NAT.
 * Keeping one viewer also means one UDP family at a time, which avoids platform-specific
 * dual-stack socket option differences.
 */
class UdpVideoTransport(
    private val sessionId: Long,
    private val token: ByteArray,
    private val port: Int,
    remoteAddress: InetAddress,
    localAddress: InetAddress,
    private val listener: Listener
) {
    interface Listener {
        fun onViewerReady(address: InetSocketAddress)
        fun onTransportError(message: String)
    }

    data class Stats(val framesSent: Long, val bytesSent: Long, val droppedFrames: Long)

    // Bind the UDP socket to the same local interface/address that carries the TCP
    // control connection. This avoids sending video through a VPN or the wrong NIC
    // on a multi-homed phone.
    private val bindAddress: InetAddress = normalizeLocalAddress(localAddress)
    private val ipv6: Boolean = bindAddress is Inet6Address
    private val remote = remoteAddress
    private val running = AtomicBoolean(false)
    private val queue = LinkedBlockingDeque<VideoFrame>(3)
    private val framesSent = AtomicLong(0)
    private val bytesSent = AtomicLong(0)
    private val droppedFrames = AtomicLong(0)

    @Volatile private var viewer: InetSocketAddress? = null
    @Volatile private var socket: DatagramSocket? = null
    @Volatile private var receiverThread: Thread? = null
    @Volatile private var senderThread: Thread? = null
    @Volatile private var lastHelloElapsed: Long = 0
    private var frameId: Int = 0

    fun start(): Boolean {
        if (!running.compareAndSet(false, true)) return false
        return try {
            socket = openSocket()
            receiverThread = Thread({ receiveLoop() }, "MoonShare-UdpRx").also { it.start() }
            senderThread = Thread({ sendLoop() }, "MoonShare-UdpTx").also {
                it.priority = Thread.NORM_PRIORITY + 1
                it.start()
            }
            true
        } catch (t: Throwable) {
            running.set(false)
            listener.onTransportError("无法监听视频端口 $port: ${t.message}")
            stop()
            false
        }
    }

    fun sendFrame(frame: VideoFrame) {
        if (!running.get() || viewer == null) return
        // Never drop encoded P-frames: later P-frames reference them, so dropping one
        // produces exactly the macroblock corruption users see on fast motion. The
        // encoder is configured for 30 fps; if a vendor ignores that, send every frame
        // and let the decoder keep the reference chain intact.
        if (queue.offerLast(frame)) return
        queue.pollFirst()
        droppedFrames.incrementAndGet()
        queue.offerLast(frame)
    }

    fun snapshot(): Stats = Stats(framesSent.get(), bytesSent.get(), droppedFrames.get())

    fun hasViewer(): Boolean = viewer != null

    fun viewerEndpoint(): InetSocketAddress? = viewer

    fun stop() {
        running.set(false)
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        socket = null
        receiverThread?.interrupt()
        senderThread?.interrupt()
        receiverThread = null
        senderThread = null
        viewer = null
        queue.clear()
    }

    private fun openSocket(): DatagramSocket {
        val udp = DatagramSocket(null)
        try {
            udp.reuseAddress = true
            udp.sendBufferSize = 1 shl 20
            udp.receiveBufferSize = 1 shl 20
            val address = if (bindAddress.isAnyLocalAddress) {
                if (ipv6) InetAddress.getByName("::") else InetAddress.getByName("0.0.0.0")
            } else {
                bindAddress
            }
            try {
                udp.bind(InetSocketAddress(address, port))
            } catch (t: Throwable) {
                // Some Android network stacks dislike binding to an address obtained
                // from a dual-stack accepted socket; fall back to the family wildcard.
                Log.w(TAG, "specific UDP bind $address failed: ${t.message}; using wildcard")
                val wildcard = if (ipv6) InetAddress.getByName("::") else InetAddress.getByName("0.0.0.0")
                udp.bind(InetSocketAddress(wildcard, port))
            }
            udp.soTimeout = 700
            Log.i(TAG, "UDP bound ${udp.localAddress.hostAddress}:${udp.localPort} (remote $remote)")
            return udp
        } catch (t: Throwable) {
            udp.close()
            throw t
        }
    }

    private fun receiveLoop() {
        val udp = socket ?: return
        val buffer = ByteArray(2048)
        while (running.get()) {
            try {
                val packet = DatagramPacket(buffer, buffer.size)
                udp.receive(packet)
                val hello = Protocol.parseUdpHello(packet.data, packet.length) ?: continue
                if (hello.sessionId != sessionId) continue
                if (!Protocol.constantTimeEquals(hello.token, token)) continue

                val endpoint = InetSocketAddress(packet.address, packet.port)
                val first = viewer == null
                viewer = endpoint
                lastHelloElapsed = SystemClock.elapsedRealtime()

                try {
                    val ack = Protocol.udpHelloAck(sessionId)
                    udp.send(DatagramPacket(ack, ack.size, packet.address, packet.port))
                } catch (_: Exception) {
                }
                if (first) {
                    Log.i(TAG, "viewer UDP path ready: ${endpoint.address.hostAddress}:${endpoint.port}")
                    listener.onViewerReady(endpoint)
                }
            } catch (_: SocketTimeoutException) {
                val noViewerFor = SystemClock.elapsedRealtime() - lastHelloElapsed
                if (viewer != null && lastHelloElapsed > 0 && noViewerFor > 12_000) {
                    // The TCP channel still owns teardown; just stop wasting bandwidth.
                    viewer = null
                }
            } catch (t: Throwable) {
                if (running.get()) Log.w(TAG, "udp rx: ${t.message}")
            }
        }
    }

    private fun sendLoop() {
        val datagram = ByteArray(Protocol.VIDEO_DATAGRAM_BYTES)
        while (running.get()) {
            val frame = try {
                queue.takeFirst()
            } catch (_: InterruptedException) {
                break
            }
            val endpoint = viewer ?: continue
            val udp = socket ?: continue
            val total = frame.data.size
            if (total <= 0 || total > Protocol.MAX_FRAME_BYTES) continue
            val count = (total + Protocol.VIDEO_PAYLOAD_BYTES - 1) / Protocol.VIDEO_PAYLOAD_BYTES
            if (count > 0xffff) continue
            if (framesSent.get() < 3L) {
                Log.i(TAG, "sending frame key=${frame.keyFrame} bytes=$total fragments=$count to $endpoint")
            }

            var sentBytes = 0
            var ok = true
            for (index in 0 until count) {
                val offset = index * Protocol.VIDEO_PAYLOAD_BYTES
                val length = minOf(Protocol.VIDEO_PAYLOAD_BYTES, total - offset)
                val header = Protocol.videoHeader(
                    sessionId = sessionId,
                    frameId = frameId,
                    fragmentIndex = index,
                    fragmentCount = count,
                    payloadLength = length,
                    keyFrame = frame.keyFrame,
                    presentationTimeUs = frame.presentationTimeUs
                )
                System.arraycopy(header, 0, datagram, 0, Protocol.VIDEO_HEADER_BYTES)
                System.arraycopy(frame.data, offset, datagram, Protocol.VIDEO_HEADER_BYTES, length)
                try {
                    udp.send(DatagramPacket(datagram, Protocol.VIDEO_HEADER_BYTES + length, endpoint))
                    sentBytes += Protocol.VIDEO_HEADER_BYTES + length
                } catch (t: Throwable) {
                    ok = false
                    if (running.get()) Log.w(TAG, "udp tx failed fragment=$index/$count", t)
                    break
                }
            }
            frameId = (frameId + 1) and 0x7fffffff
            if (ok) {
                framesSent.incrementAndGet()
                bytesSent.addAndGet(sentBytes.toLong())
            }
        }
    }

    companion object {
        private const val TAG = "MoonShareVideo"

        private fun normalizeLocalAddress(address: InetAddress): InetAddress {
            if (address is Inet6Address && isIpv4Mapped(address)) {
                return InetAddress.getByAddress(address.address.copyOfRange(12, 16))
            }
            return address
        }

        private fun isIpv4Mapped(address: Inet6Address): Boolean {
            val bytes = address.address
            if (bytes.size != 16) return false
            for (i in 0 until 10) if (bytes[i].toInt() != 0) return false
            return (bytes[10].toInt() and 0xff) == 0xff && (bytes[11].toInt() and 0xff) == 0xff
        }
    }
}
