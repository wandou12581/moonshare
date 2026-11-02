package com.moonshare.net

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Process
import android.util.Log
import com.moonshare.protocol.Protocol
import java.io.BufferedOutputStream
import java.io.EOFException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Playback audio for the viewer, carried over a dedicated TCP stream.
 *
 * TCP is intentional: a lost PCM packet is an audible pop, while a TCP retransmit
 * costs at most a few milliseconds on a LAN. Android's playback-capture API only
 * exposes apps that opted into capture (media/game/unknown); calls are never captured.
 */
class AudioStreamTransport(
    private val sessionId: Long,
    private val token: ByteArray,
    private val port: Int,
    private val projection: MediaProjection?,
    private val listener: Listener
) {
    interface Listener {
        fun onAudioViewerReady(address: InetSocketAddress)
        fun onAudioError(message: String)
    }

    private val running = AtomicBoolean(false)
    @Volatile private var server: ServerSocket? = null
    @Volatile private var client: Socket? = null
    @Volatile private var acceptThread: Thread? = null
    @Volatile private var audioThread: Thread? = null
    @Volatile private var record: AudioRecord? = null

    fun start(): Boolean {
        if (!running.compareAndSet(false, true)) return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Log.w(TAG, "playback capture requires Android 10")
            running.set(false)
            return false
        }
        return try {
            val socket = ServerSocket()
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(InetAddress.getByName("0.0.0.0"), port), 4)
            socket.soTimeout = 700
            server = socket
            acceptThread = Thread({ acceptLoop(socket) }, "MoonShare-AudioRx").also {
                it.isDaemon = true
                it.start()
            }
            Log.i(TAG, "audio listener on 0.0.0.0:$port")
            true
        } catch (t: Throwable) {
            running.set(false)
            listener.onAudioError("音频端口 $port 不可用: ${t.message}")
            stop()
            false
        }
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
        acceptThread = null
        try {
            record?.stop()
        } catch (_: Exception) {
        }
        try {
            record?.release()
        } catch (_: Exception) {
        }
        record = null
        audioThread?.interrupt()
        audioThread = null
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (running.get()) {
            try {
                val accepted = socket.accept()
                accepted.tcpNoDelay = true
                accepted.soTimeout = 8_000
                val hello = ByteArray(Protocol.UDP_HELLO_BYTES)
                readFully(accepted.getInputStream(), hello)
                val parsed = Protocol.parseUdpHello(hello, hello.size)
                if (parsed == null || parsed.sessionId != sessionId ||
                    !Protocol.constantTimeEquals(parsed.token, token)
                ) {
                    Log.w(TAG, "audio hello rejected from ${accepted.remoteSocketAddress}")
                    accepted.close()
                    continue
                }
                accepted.soTimeout = 0
                client = accepted
                val address = accepted.remoteSocketAddress as? InetSocketAddress
                Log.i(TAG, "audio viewer ready: ${accepted.remoteSocketAddress}")
                listener.onAudioViewerReady(address ?: InetSocketAddress("0.0.0.0", 0))
                audioThread = Thread({ captureLoop(accepted) }, "MoonShare-AudioTx").also {
                    it.isDaemon = true
                    it.start()
                }
                return
            } catch (_: SocketTimeoutException) {
                // keep listening for the viewer
            } catch (t: Throwable) {
                if (running.get()) Log.w(TAG, "audio accept: ${t.message}")
            }
        }
    }

    private fun captureLoop(socket: Socket) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        var audio: AudioRecord? = null
        try {
            if (projection == null) {
                listener.onAudioError("没有可用的 MediaProjection，无法采集声音")
                return
            }
            val captureConfig = AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
            val format = AudioFormat.Builder()
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .build()
            val minBuffer = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_STEREO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(4096)
            val recorder = AudioRecord.Builder()
                .setAudioPlaybackCaptureConfig(captureConfig)
                .setAudioFormat(format)
                .setBufferSizeInBytes(minBuffer * 2)
                .build()
            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                recorder.release()
                throw IllegalStateException("AudioRecord 初始化失败，请检查麦克风权限")
            }
            audio = recorder
            record = recorder
            recorder.startRecording()
            val out = BufferedOutputStream(socket.getOutputStream(), 16 * 1024)
            val pcm = ByteArray(FRAME_BYTES)
            var frames = 0L
            Log.i(TAG, "audio capture started: ${SAMPLE_RATE}Hz stereo PCM16")
            while (running.get() && !socket.isClosed) {
                val read = recorder.read(pcm, 0, pcm.size, AudioRecord.READ_BLOCKING)
                if (read <= 0) continue
                val header = ByteBuffer.allocate(12)
                    .putInt(read)
                    .putLong(System.nanoTime() / 1000)
                    .array()
                out.write(header)
                out.write(pcm, 0, read)
                out.flush()
                frames++
                if (frames == 1L) Log.i(TAG, "first PCM frame: $read bytes")
            }
        } catch (t: Throwable) {
            if (running.get()) {
                Log.w(TAG, "audio capture failed", t)
                listener.onAudioError("音频采集失败: ${t.message ?: t.javaClass.simpleName}")
            }
        } finally {
            try {
                audio?.stop()
            } catch (_: Exception) {
            }
            try {
                audio?.release()
            } catch (_: Exception) {
            }
            if (record === audio) record = null
            try {
                socket.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun readFully(stream: InputStream, target: ByteArray) {
        var offset = 0
        while (offset < target.size) {
            val read = stream.read(target, offset, target.size - offset)
            if (read < 0) throw EOFException("audio hello closed")
            offset += read
        }
    }

    companion object {
        private const val TAG = "MoonShareAudio"
        private const val SAMPLE_RATE = 48_000
        private const val FRAME_BYTES = 3_840 // 20 ms, stereo, PCM16
    }
}
