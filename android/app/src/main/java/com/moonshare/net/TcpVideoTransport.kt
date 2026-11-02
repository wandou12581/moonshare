package com.moonshare.net

import android.util.Log
import com.moonshare.protocol.Protocol
import com.moonshare.protocol.VideoFrame
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Debug / firewall fallback: the same H.264 access units over one TCP connection.
 *
 * The emulator (and some locked-down networks) cannot route the UDP return path, but
 * `adb forward tcp:47801 tcp:47801` can carry this stream. The real phone path remains
 * UDP; this listener sends nothing until a viewer completes the 32-byte HELLO.
 */
class TcpVideoTransport(
    private val sessionId: Long,
    private val token: ByteArray,
    private val port: Int,
    private val listener: Listener
) {
    interface Listener {
        fun onTcpViewerReady(address: InetSocketAddress)
        fun onTcpTransportError(message: String)
    }

    private val running = AtomicBoolean(false)
    private val queue = LinkedBlockingDeque<VideoFrame>(3)
    private val framesSent = java.util.concurrent.atomic.AtomicLong(0)

    @Volatile private var server: ServerSocket? = null
    @Volatile private var client: Socket? = null
    @Volatile private var input: InputStream? = null
    @Volatile private var output: OutputStream? = null
    @Volatile private var acceptThread: Thread? = null
    @Volatile private var senderThread: Thread? = null
    @Volatile private var viewer: InetSocketAddress? = null

    fun start(): Boolean {
        if (!running.compareAndSet(false, true)) return false
        return try {
            val socket = ServerSocket()
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(InetAddress.getByName("0.0.0.0"), port), 4)
            socket.soTimeout = 700
            server = socket
            acceptThread = Thread({ acceptLoop(socket) }, "MoonShare-TcpVideo").also { it.start() }
            Log.i(TAG, "TCP video fallback listening on 0.0.0.0:$port")
            true
        } catch (t: Throwable) {
            running.set(false)
            listener.onTcpTransportError("TCP 视频端口 $port 不可用: ${t.message}")
            stop()
            false
        }
    }

    fun hasViewer(): Boolean = viewer != null

    fun sendFrame(frame: VideoFrame) {
        if (!running.get() || viewer == null) return
        if (queue.offerLast(frame)) return
        queue.pollFirst()
        queue.offerLast(frame)
    }

    fun stop() {
        running.set(false)
        try {
            client?.close()
        } catch (_: Exception) {
        }
        client = null
        try {
            server?.close()
        } catch (_: Exception) {
        }
        server = null
        acceptThread?.interrupt()
        senderThread?.interrupt()
        acceptThread = null
        senderThread = null
        viewer = null
        input = null
        output = null
        queue.clear()
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (running.get()) {
            try {
                val accepted = socket.accept()
                accepted.tcpNoDelay = true
                accepted.soTimeout = 8_000
                val hello = ByteArray(32)
                readFully(accepted.getInputStream(), hello)
                val parsed = Protocol.parseUdpHello(hello, hello.size)
                if (parsed == null || parsed.sessionId != sessionId ||
                    !Protocol.constantTimeEquals(parsed.token, token)
                ) {
                    Log.w(TAG, "TCP video hello rejected from ${accepted.remoteSocketAddress}")
                    accepted.close()
                    continue
                }
                accepted.soTimeout = 0
                client = accepted
                input = BufferedInputStream(accepted.getInputStream())
                output = BufferedOutputStream(accepted.getOutputStream())
                viewer = accepted.remoteSocketAddress as? InetSocketAddress
                Log.i(TAG, "TCP video viewer ready: ${accepted.remoteSocketAddress}")
                listener.onTcpViewerReady(viewer ?: InetSocketAddress("0.0.0.0", 0))
                senderThread = Thread({ sendLoop() }, "MoonShare-TcpVideoTx").also { it.start() }
                return
            } catch (_: SocketTimeoutException) {
                // keep listening
            } catch (t: Throwable) {
                if (running.get()) Log.w(TAG, "TCP video accept: ${t.message}")
            }
        }
    }

    private fun sendLoop() {
        val out = output ?: return
        try {
            while (running.get()) {
                val frame = queue.takeFirst()
                val data = frame.data
                if (data.isEmpty()) continue
                val header = ByteArray(13)
                Protocol.putInt(header, 0, data.size)
                header[4] = if (frame.keyFrame) 1 else 0
                Protocol.putLong(header, 5, frame.presentationTimeUs)
                out.write(header)
                out.write(data)
                out.flush()
                val count = framesSent.incrementAndGet()
                if (count == 1L) {
                    Log.i(TAG, "TCP video stream started, key=${frame.keyFrame} bytes=${data.size}")
                }
            }
        } catch (_: InterruptedException) {
        } catch (t: Throwable) {
            if (running.get()) {
                Log.w(TAG, "TCP video send failed", t)
                listener.onTcpTransportError("TCP 视频发送失败: ${t.message}")
            }
            stop()
        }
    }

    private fun readFully(stream: InputStream, target: ByteArray) {
        var offset = 0
        while (offset < target.size) {
            val read = stream.read(target, offset, target.size - offset)
            if (read < 0) throw EOFException("TCP video hello closed")
            offset += read
        }
    }

    companion object {
        private const val TAG = "MoonShareTcpVideo"
    }
}
