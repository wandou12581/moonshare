package com.moonshare

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.graphics.drawable.Icon
import android.os.SystemClock
import android.util.Log
import com.moonshare.capture.ScreenCapture
import com.moonshare.net.AudioStreamTransport
import com.moonshare.net.ControlServer
import com.moonshare.net.DiscoveryResponder
import com.moonshare.net.TcpVideoTransport
import com.moonshare.net.UdpVideoTransport
import com.moonshare.protocol.Protocol
import com.moonshare.protocol.StreamInfo
import com.moonshare.protocol.VideoFrame
import com.moonshare.util.NetworkUtils
import org.json.JSONObject
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class ScreenShareService : Service(), ScreenCapture.Listener, UdpVideoTransport.Listener {

    private val handler = Handler(Looper.getMainLooper())
    private val secureRandom = SecureRandom()
    private val stopping = AtomicBoolean(false)
    private val sessionLock = Any()
    private val failedAttempts = HashMap<String, AttemptWindow>()
    // TCP writes must never run on the main thread (Android StrictMode throws
    // NetworkOnMainThreadException, which used to close the control link after 1s).
    private val controlTx = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "MoonShare-ControlTx").apply { isDaemon = true }
    }

    private var projectionManager: MediaProjectionManager? = null
    private var projection: MediaProjection? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private var capture: ScreenCapture? = null
    private var controlServer: ControlServer? = null
    private var discovery: DiscoveryResponder? = null
    @Volatile private var transport: UdpVideoTransport? = null
    @Volatile private var tcpTransport: TcpVideoTransport? = null
    @Volatile private var audioTransport: AudioStreamTransport? = null

    private var pairingCode: String = ""
    @Volatile private var activeSessionId: Long? = null
    @Volatile private var activeViewerName: String? = null
    @Volatile private var activeViewerAddress: InetSocketAddress? = null
    @Volatile private var streamInfo: StreamInfo? = null

    private var lastStatsAt = 0L
    private var lastStatsFrames = 0L
    private var lastStatsBytes = 0L

    @Volatile private var starting = false

    private val statsRunnable = object : Runnable {
        override fun run() {
            publishStats()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        handler.postDelayed(statsRunnable, 1000)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSharing()
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_QUERY -> {
                broadcastState()
                return START_NOT_STICKY
            }

            ACTION_START -> {
                if (projection != null || starting) {
                    broadcastState()
                    return START_NOT_STICKY
                }
                starting = true
                startInForeground()
                if (stopping.get()) return START_NOT_STICKY
                isSharing = true
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                val resultData: Intent? = getProjectionResultData(intent)
                if (resultCode != Activity.RESULT_OK || resultData == null) {
                    fail("录屏授权无效，请重新开始共享")
                    return START_NOT_STICKY
                }
                beginSharing(resultCode, resultData)
                return START_NOT_STICKY
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @Suppress("DEPRECATION")
    private fun getProjectionResultData(intent: Intent): Intent? {
        return if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            intent.getParcelableExtra(EXTRA_RESULT_DATA)
        }
    }

    override fun onDestroy() {
        stopSharing()
        isSharing = false
        super.onDestroy()
    }

    private fun startInForeground() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    buildNotification("------", 0),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                )
            } else {
                startForeground(NOTIFICATION_ID, buildNotification("------", 0))
            }
        } catch (t: Throwable) {
            Log.e(TAG, "startForeground failed", t)
            starting = false
            fail("无法启动前台共享服务: ${t.message}")
        }
    }

    private fun beginSharing(resultCode: Int, resultData: Intent) {
        if (stopping.get()) return
        try {
            pairingCode = (secureRandom.nextInt(900_000) + 100_000).toString()
            val manager = projectionManager ?: throw IllegalStateException("MediaProjection unavailable")
            val mediaProjection = manager.getMediaProjection(resultCode, resultData)
                ?: throw IllegalStateException("MediaProjection permission was not granted")
            projection = mediaProjection

            val callback = object : MediaProjection.Callback() {
                override fun onStop() {
                    handler.post {
                        if (!stopping.get()) {
                            fail("系统已停止录屏")
                        }
                    }
                }
            }
            projectionCallback = callback
            mediaProjection.registerCallback(callback, handler)

            val newCapture = ScreenCapture(this, mediaProjection, this)
            capture = newCapture
            val info = newCapture.start() ?: throw IllegalStateException("H.264 encoder could not start")
            streamInfo = info

            val server = ControlServer(Protocol.TCP_PORT, controlListener)
            controlServer = server
            if (!server.start()) throw IllegalStateException("TCP port ${Protocol.TCP_PORT} is unavailable")

            discovery = DiscoveryResponder(Protocol.VIDEO_PORT + 1) {
                discoveryResponse()
            }.also { it.start() }

            starting = false
            updateNotification(0)
            broadcastState()
            Log.i(TAG, "sharing started ${info.width}x${info.height}@${info.fps}")
        } catch (t: Throwable) {
            starting = false
            Log.e(TAG, "beginSharing failed", t)
            fail(t.message ?: "无法开始共享")
        }
    }

    private fun fail(message: String) {
        if (!stopping.compareAndSet(false, true)) return
        starting = false
        sendViewerBye(message)
        controlTx.shutdown()
        handler.post {
            controlServer?.stop()
            controlServer = null
            discovery?.stop()
            discovery = null
            transport?.stop()
            transport = null
            tcpTransport?.stop()
            tcpTransport = null
            audioTransport?.stop()
            audioTransport = null
            capture?.stop()
            capture = null
            try {
                projectionCallback?.let { projection?.unregisterCallback(it) }
            } catch (_: Exception) {
            }
            try {
                projection?.stop()
            } catch (_: Exception) {
            }
            projection = null
            projectionCallback = null
            activeSessionId = null
            activeViewerName = null
            activeViewerAddress = null
            isSharing = false
            handler.removeCallbacks(statsRunnable)
            sendStateBroadcast(false, message)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun stopSharing() {
        if (!stopping.compareAndSet(false, true)) return
        starting = false
        sendViewerBye("sharing stopped")
        controlTx.shutdown()
        handler.removeCallbacks(statsRunnable)
        controlServer?.stop()
        controlServer = null
        discovery?.stop()
        discovery = null
        transport?.stop()
        transport = null
        tcpTransport?.stop()
        tcpTransport = null
        audioTransport?.stop()
        audioTransport = null
        capture?.stop()
        capture = null
        try {
            projectionCallback?.let { projection?.unregisterCallback(it) }
        } catch (_: Exception) {
        }
        try {
            projection?.stop()
        } catch (_: Exception) {
        }
        projection = null
        projectionCallback = null
        pairingCode = ""
        activeSessionId = null
        activeViewerName = null
        activeViewerAddress = null
        streamInfo = null
        isSharing = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        sendStateBroadcast(false, null)
    }

    // ---- Control channel ----

    private val controlListener = object : ControlServer.Listener {
        override fun authorize(request: ControlServer.HelloRequest): ControlServer.HelloDecision {
            synchronized(sessionLock) {
                val now = SystemClock.elapsedRealtime()
                val key = request.remoteAddress.hostAddress ?: "unknown"
                val window = failedAttempts[key]
                if (window != null && now - window.startedAt < 60_000 && window.count >= 5) {
                    return ControlServer.HelloDecision.Error(
                        "rate_limited",
                        "Too many pairing attempts. Wait a minute."
                    )
                }

                if (activeSessionId != null) {
                    return ControlServer.HelloDecision.Error("busy", "Another viewer is already connected")
                }

                if (!constantTimeCodeEquals(request.code, pairingCode)) {
                    val current = failedAttempts[key]
                    if (current == null || now - current.startedAt > 60_000) {
                        failedAttempts[key] = AttemptWindow(now, 1)
                    } else {
                        current.count++
                    }
                    return ControlServer.HelloDecision.Error("bad_code", "Pairing code is incorrect")
                }

                failedAttempts.remove(key)
                val info = streamInfo
                    ?: return ControlServer.HelloDecision.Error("server_error", "Stream is not ready")

                val session = Protocol.newSessionId().let { if (it == 0L) 1L else it }
                val token = Protocol.randomBytes(16)
                val newTransport = UdpVideoTransport(
                    session,
                    token,
                    Protocol.VIDEO_PORT,
                    request.remoteAddress,
                    request.localAddress,
                    this@ScreenShareService
                )
                if (!newTransport.start()) {
                    return ControlServer.HelloDecision.Error("server_error", "Video port is unavailable")
                }

                // Optional TCP fallback on the same port, used by the emulator and by
                // networks that drop the UDP return path. It only sends after a viewer
                // completes the authenticated TCP hello.
                val newTcp = TcpVideoTransport(session, token, Protocol.VIDEO_PORT, tcpListener)
                val tcpStarted = newTcp.start()
                if (!tcpStarted) {
                    Log.w(TAG, "TCP video fallback unavailable")
                }

                val newAudio = AudioStreamTransport(
                    session,
                    token,
                    Protocol.AUDIO_PORT,
                    projection,
                    audioListener
                )
                val audioStarted = newAudio.start()
                if (!audioStarted) {
                    Log.w(TAG, "audio transport unavailable")
                }

                transport?.stop()
                transport = newTransport
                tcpTransport?.stop()
                tcpTransport = if (tcpStarted) newTcp else null
                audioTransport?.stop()
                audioTransport = if (audioStarted) newAudio else null
                activeSessionId = session
                activeViewerName = request.viewer
                activeViewerAddress = null
                lastStatsAt = 0
                lastStatsFrames = 0
                lastStatsBytes = 0

                val welcome = JSONObject()
                    .put("t", "welcome")
                    .put("v", Protocol.VERSION)
                    .put("session", String.format("%016x", session))
                    .put("token", Protocol.hex(token))
                    .put("name", Build.MODEL ?: "Android")
                    .put("width", info.width)
                    .put("height", info.height)
                    .put("fps", info.fps)
                    .put("codec", info.codec)
                    .put("profile", info.profile)
                    .put("bitrate", info.bitrate)
                    .put("videoPort", Protocol.VIDEO_PORT)
                    .put("audioPort", Protocol.AUDIO_PORT)
                    .put("audio", Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                    .put("tcpPort", Protocol.TCP_PORT)

                handler.post {
                    updateNotification(0)
                    broadcastState()
                }
                Log.i(TAG, "viewer authorized: ${request.viewer} from ${request.remoteAddress.hostAddress}")
                return ControlServer.HelloDecision.Ok(session, welcome)
            }
        }

        override fun onCommand(sessionId: Long, command: JSONObject) {
            if (sessionId != activeSessionId) return
            when (command.optString("t")) {
                "ping" -> {
                    val pong = JSONObject()
                        .put("t", "pong")
                        .put("ts", command.optLong("ts", 0))
                    controlServer?.send(sessionId, pong)
                }

                // ScreenCapture will call setParameters on its own encoder thread.
                "keyframe" -> capture?.requestKeyFrame()
                "bye" -> controlServer?.closeViewer(sessionId)
            }
        }

        override fun onClosed(sessionId: Long) {
            synchronized(sessionLock) {
                if (activeSessionId != sessionId) return
                activeSessionId = null
                activeViewerName = null
                activeViewerAddress = null
                transport?.stop()
                transport = null
                tcpTransport?.stop()
                tcpTransport = null
                audioTransport?.stop()
                audioTransport = null
                lastStatsAt = 0
                handler.post {
                    updateNotification(0)
                    broadcastState()
                }
            }
        }

        override fun onServerError(message: String) {
            Log.w(TAG, message)
        }
    }

    private fun sendViewerBye(message: String) {
        val session = activeSessionId ?: return
        val server = controlServer ?: return
        try {
            val json = JSONObject()
                .put("t", "bye")
                .put("code", "sharing_stopped")
                .put("message", message)
            val future = controlTx.submit { server.send(session, json) }
            // Best effort: let the peer receive the reason before teardown, without
            // ever performing socket I/O on the main thread.
            future.get(250, TimeUnit.MILLISECONDS)
        } catch (_: Exception) {
        }
    }

    private fun constantTimeCodeEquals(a: String, b: String): Boolean {
        if (b.isEmpty()) return false
        return MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))
    }

    private fun discoveryResponse(): JSONObject? {
        val info = streamInfo ?: return null
        return try {
            JSONObject()
                .put("app", "MoonShare")
                .put("v", Protocol.VERSION)
                .put("name", Build.MODEL ?: "Android")
                .put("port", Protocol.TCP_PORT)
                .put("videoPort", Protocol.VIDEO_PORT)
                .put("sharing", true)
                .put("width", info.width)
                .put("height", info.height)
                .put("ips", org.json.JSONArray(NetworkUtils.discoveryIps()))
        } catch (_: Exception) {
            null
        }
    }

    // ---- Capture callbacks ----

    override fun onStreamInfo(info: StreamInfo) {
        streamInfo = info
        handler.post { broadcastState() }
    }

    override fun onFrame(frame: VideoFrame) {
        transport?.sendFrame(frame)
        tcpTransport?.sendFrame(frame)
    }

    override fun onCaptureError(message: String) {
        Log.e(TAG, message)
        handler.post { fail(message) }
    }

    // ---- UDP callbacks ----

    override fun onViewerReady(address: InetSocketAddress) {
        try {
            synchronized(sessionLock) {
                activeViewerAddress = address
            }
            // Ask for a key frame so a viewer that just joined does not wait for the
            // next scheduled IDR. ScreenCapture performs this on its encoder thread.
            capture?.requestKeyFrame()
            handler.post {
                updateNotification(1)
                broadcastState()
            }
            Log.i(TAG, "UDP viewer path ready: ${address.address.hostAddress}:${address.port}")
        } catch (t: Throwable) {
            Log.e(TAG, "onViewerReady failed", t)
        }
    }

    override fun onTransportError(message: String) {
        Log.w(TAG, message)
    }

    private val audioListener = object : AudioStreamTransport.Listener {
        override fun onAudioViewerReady(address: InetSocketAddress) {
            Log.i(TAG, "audio viewer ready: ${address.address.hostAddress}:${address.port}")
        }

        override fun onAudioError(message: String) {
            Log.w(TAG, message)
        }
    }

    private val tcpListener = object : TcpVideoTransport.Listener {
        override fun onTcpViewerReady(address: InetSocketAddress) {
            Log.i(TAG, "TCP video fallback viewer ready: ${address.address.hostAddress}:${address.port}")
            capture?.requestKeyFrame()
            handler.post {
                updateNotification(1)
                broadcastState()
            }
        }

        override fun onTcpTransportError(message: String) {
            Log.w(TAG, message)
        }
    }

    // ---- Stats / state ----

    private fun publishStats() {
        val session = activeSessionId ?: return
        val t = transport ?: return
        val stats = t.snapshot()
        val now = SystemClock.elapsedRealtime()
        if (lastStatsAt == 0L) {
            lastStatsAt = now
            lastStatsFrames = stats.framesSent
            lastStatsBytes = stats.bytesSent
            return
        }
        val elapsed = (now - lastStatsAt).coerceAtLeast(1) / 1000.0
        val fps = (stats.framesSent - lastStatsFrames) / elapsed
        val kbps = ((stats.bytesSent - lastStatsBytes) * 8.0 / 1000.0 / elapsed).toInt()
        lastStatsAt = now
        lastStatsFrames = stats.framesSent
        lastStatsBytes = stats.bytesSent

        val message = JSONObject()
            .put("t", "stats")
            .put("fps", (fps * 10).toInt() / 10.0)
            .put("kbps", kbps)
            .put("viewers", if (t.hasViewer()) 1 else 0)
        val server = controlServer
        try {
            controlTx.execute { server?.send(session, message) }
        } catch (_: RejectedExecutionException) {
            // Service is shutting down.
        }
    }

    private fun broadcastState() {
        val viewers = if (activeSessionId != null &&
            (transport?.hasViewer() == true || tcpTransport?.hasViewer() == true)
        ) 1 else 0
        sendStateBroadcast(true, null, viewers)
    }

    private fun sendStateBroadcast(running: Boolean, error: String?, viewers: Int = 0) {
        val intent = Intent(ACTION_STATE).apply {
            setPackage(packageName)
            putExtra(EXTRA_RUNNING, running)
            putExtra(EXTRA_CODE, pairingCode)
            putExtra(EXTRA_VIEWERS, viewers)
            putExtra(EXTRA_ADDRESSES, NetworkUtils.displayLines())
            putExtra(EXTRA_VIEWER_NAME, activeViewerName)
            putExtra(EXTRA_VIEWER_ADDRESS, activeViewerAddress?.address?.hostAddress)
            putExtra(EXTRA_ERROR, error)
            streamInfo?.let {
                putExtra(EXTRA_WIDTH, it.width)
                putExtra(EXTRA_HEIGHT, it.height)
            }
        }
        sendBroadcast(intent)
    }

    private fun updateNotification(viewers: Int) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        try {
            manager.notify(NOTIFICATION_ID, buildNotification(pairingCode.ifEmpty { "------" }, viewers))
        } catch (_: Exception) {
        }
    }

    private fun buildNotification(code: String, viewers: Int): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, ScreenShareService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val text = if (viewers > 0) getString(R.string.notif_text_live, code, viewers)
        else getString(R.string.notif_text, code)
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            Notification.Builder(this)
        }
        return builder
            .setSmallIcon(R.drawable.ic_logo)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(text)
            .setContentIntent(openIntent)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, R.drawable.ic_logo),
                    getString(R.string.notif_stop),
                    stopIntent
                ).build()
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notif_title)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private data class AttemptWindow(val startedAt: Long, var count: Int)

    companion object {
        private const val TAG = "MoonShareService"
        private const val CHANNEL_ID = "moonshare_screen"
        private const val NOTIFICATION_ID = 47800

        const val ACTION_START = "com.moonshare.action.START"
        const val ACTION_STOP = "com.moonshare.action.STOP"
        const val ACTION_QUERY = "com.moonshare.action.QUERY"
        const val ACTION_STATE = "com.moonshare.action.STATE"

        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_RUNNING = "running"
        const val EXTRA_CODE = "code"
        const val EXTRA_VIEWERS = "viewers"
        const val EXTRA_ADDRESSES = "addresses"
        const val EXTRA_VIEWER_NAME = "viewer_name"
        const val EXTRA_VIEWER_ADDRESS = "viewer_address"
        const val EXTRA_ERROR = "error"
        const val EXTRA_WIDTH = "width"
        const val EXTRA_HEIGHT = "height"

        @Volatile
        var isSharing: Boolean = false
            private set
    }
}
