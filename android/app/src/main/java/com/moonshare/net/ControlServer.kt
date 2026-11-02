package com.moonshare.net

import android.util.Log
import com.moonshare.protocol.LineReader
import com.moonshare.protocol.Protocol
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class ControlServer(
    private val port: Int,
    private val listener: Listener
) {
    interface Listener {
        fun authorize(request: HelloRequest): HelloDecision
        fun onCommand(sessionId: Long, command: JSONObject)
        fun onClosed(sessionId: Long)
        fun onServerError(message: String)
    }

    data class HelloRequest(
        val code: String,
        val viewer: String,
        val remoteAddress: InetAddress,
        val localAddress: InetAddress
    )

    sealed class HelloDecision {
        data class Ok(val sessionId: Long, val welcome: JSONObject) : HelloDecision()
        data class Error(val code: String, val message: String) : HelloDecision()
    }

    private val running = AtomicBoolean(false)
    private val clients = ConcurrentHashMap<Long, Client>()
    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var acceptThread: Thread? = null

    fun start(): Boolean {
        if (!running.compareAndSet(false, true)) return false
        return try {
            serverSocket = openServerSocket(port)
            acceptThread = Thread({ acceptLoop() }, "MoonShare-Control").also { it.start() }
            true
        } catch (t: Throwable) {
            running.set(false)
            listener.onServerError("无法监听控制端口 $port: ${t.message}")
            false
        }
    }

    fun stop() {
        running.set(false)
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        serverSocket = null
        for (client in clients.values) client.close("server-stop")
        clients.clear()
        acceptThread?.interrupt()
        acceptThread = null
    }

    fun isViewerConnected(): Boolean = clients.isNotEmpty()

    fun send(sessionId: Long, message: JSONObject): Boolean {
        val client = clients[sessionId] ?: return false
        return client.send(message.toString())
    }

    fun closeViewer(sessionId: Long) {
        clients[sessionId]?.close("close-viewer")
    }

    private fun acceptLoop() {
        while (running.get()) {
            try {
                val socket = serverSocket?.accept() ?: break
                socket.tcpNoDelay = true
                socket.keepAlive = true
                Thread({ handleSocket(socket) }, "MoonShare-Control-Client").start()
            } catch (t: Throwable) {
                if (running.get()) listener.onServerError("控制连接失败: ${t.message}")
                else break
            }
        }
    }

    private fun handleSocket(socket: Socket) {
        var client: Client? = null
        try {
            socket.soTimeout = 10_000
            val reader = LineReader(socket.getInputStream())
            val firstLine = reader.readLine() ?: run {
                socket.close()
                return
            }
            val hello = try {
                JSONObject(firstLine)
            } catch (_: Exception) {
                sendErrorAndClose(socket, "bad_request", "Malformed hello")
                return
            }

            if (hello.optString("t") != "hello" || hello.optInt("v", 0) != Protocol.VERSION) {
                sendErrorAndClose(socket, "unsupported_version", "MoonShare protocol v1 required")
                return
            }
            val code = hello.optString("code").trim()
            val viewerName = hello.optString("viewer", "PC").take(64)
            val decision = listener.authorize(
                HelloRequest(code, viewerName, socket.inetAddress, socket.localAddress)
            )
            when (decision) {
                is HelloDecision.Error -> {
                    sendErrorAndClose(socket, decision.code, decision.message)
                    return
                }

                is HelloDecision.Ok -> {
                    val newClient = Client(socket, decision.sessionId)
                    client = newClient
                    clients[decision.sessionId] = newClient
                    if (!newClient.send(decision.welcome.toString())) {
                        clients.remove(decision.sessionId)
                        listener.onClosed(decision.sessionId)
                        return
                    }
                    socket.soTimeout = 15_000
                    while (running.get() && !socket.isClosed) {
                        val line = try {
                            reader.readLine()
                        } catch (_: SocketTimeoutException) {
                            Log.i(TAG, "control idle timeout session=${decision.sessionId}")
                            break
                        }
                        if (line == null) {
                            Log.i(TAG, "control EOF session=${decision.sessionId}")
                            break
                        }
                        if (line.isBlank()) continue
                        try {
                            listener.onCommand(decision.sessionId, JSONObject(line))
                        } catch (_: Exception) {
                            // Ignore malformed command lines, but keep the control link alive.
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            if (running.get()) Log.w(TAG, "control client ended", t)
        } finally {
            val closed = client
            if (closed != null) {
                clients.remove(closed.sessionId)
                closed.close("handle-finally")
                listener.onClosed(closed.sessionId)
            } else {
                try {
                    socket.close()
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun sendErrorAndClose(socket: Socket, code: String, message: String) {
        try {
            val json = JSONObject()
                .put("t", "error")
                .put("code", code)
                .put("message", message)
            val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))
            writer.write(json.toString())
            writer.write("\n")
            writer.flush()
            // Give the peer a moment to read the error before TCP teardown; otherwise
            // some stacks surface an RST as a generic "connection aborted".
            Thread.sleep(80)
        } catch (_: Exception) {
        }
        try {
            socket.close()
        } catch (_: Exception) {
        }
    }

    private fun openServerSocket(port: Int): ServerSocket {
        // Bind the wildcard IPv6 address. On Android/Linux this listener is dual-stack,
        // so IPv4 viewers can connect as well. Fall back to the platform wildcard if
        // IPv6 is unavailable.
        return try {
            ServerSocket().apply {
                reuseAddress = true
                receiveBufferSize = 64 * 1024
                bind(InetSocketAddress(InetAddress.getByName("::"), port), 8)
            }
        } catch (_: Throwable) {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(port), 8)
            }
        }
    }

    private inner class Client(
        private val socket: Socket,
        val sessionId: Long
    ) {
        private val writeLock = Any()
        private var writer: BufferedWriter? = null
        private val closed = AtomicBoolean(false)

        init {
            try {
                writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))
            } catch (_: Exception) {
            }
        }

        fun send(text: String): Boolean {
            if (closed.get()) return false
            synchronized(writeLock) {
                return try {
                    val out = writer ?: return false
                    out.write(text)
                    out.write("\n")
                    out.flush()
                    true
                } catch (t: Exception) {
                    Log.w(TAG, "control send failed session=$sessionId", t)
                    close("send-failure")
                    false
                }
            }
        }

        fun close(reason: String) {
            if (!closed.compareAndSet(false, true)) return
            Log.w(TAG, "closing control client session=$sessionId reason=$reason")
            try {
                socket.close()
            } catch (_: Exception) {
            }
        }
    }

    companion object {
        private const val TAG = "MoonShareControl"
    }
}
