package com.moonshare.capture

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.os.Build
import android.util.DisplayMetrics
import android.util.Log
import android.view.Surface
import android.view.WindowManager
import com.moonshare.protocol.StreamInfo
import com.moonshare.protocol.VideoFrame
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * H.264 surface encoder fed by a MediaProjection virtual display.
 *
 * We deliberately never create an input channel: the virtual display is output-only.
 */
class ScreenCapture(
    private val context: Context,
    private val projection: MediaProjection,
    private val listener: Listener
) {
    interface Listener {
        fun onStreamInfo(info: StreamInfo)
        fun onFrame(frame: VideoFrame)
        fun onCaptureError(message: String)
    }

    private val running = AtomicBoolean(false)
    private val keyFrameRequested = AtomicBoolean(false)

    @Volatile private var codec: MediaCodec? = null
    @Volatile private var inputSurface: Surface? = null
    @Volatile private var virtualDisplay: VirtualDisplay? = null
    @Volatile private var gate: GlFrameGate? = null
    @Volatile private var encoderInputSurface: Surface? = null
    @Volatile private var encoderThread: Thread? = null

    private var spsPps: ByteArray? = null
    private var encodedFrames: Long = 0

    fun start(): StreamInfo? {
        if (!running.compareAndSet(false, true)) return null
        return try {
            val size = chooseSize()
            val info = StreamInfo(
                width = size.first,
                height = size.second,
                fps = TARGET_FPS,
                bitrate = chooseBitrate(size.first, size.second),
                codec = "h264",
                profile = "baseline"
            )
            startEncoder(info)
            listener.onStreamInfo(info)
            info
        } catch (t: Throwable) {
            running.set(false)
            stopInternal()
            listener.onCaptureError(t.message ?: t.javaClass.simpleName)
            null
        }
    }

    fun requestKeyFrame() {
        keyFrameRequested.set(true)
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) {
            stopInternal()
            return
        }
        stopInternal()
    }

    private fun startEncoder(info: StreamInfo) {
        val displayMetrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        val display = (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay
        @Suppress("DEPRECATION")
        display.getRealMetrics(displayMetrics)

        // Keep the format deliberately minimal. Several vendor encoders accept
        // CBR/latency/max-B-frames at configure time and then fail or crash when the
        // first surface frame arrives. One-second IDR + SPS/PPS on every key frame is
        // enough for recovery, so the optional low-latency knobs are not worth the risk.
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, info.width, info.height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, info.bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, info.fps)
            setInteger(MediaFormat.KEY_CAPTURE_RATE, info.fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
            // Hint for encoders that otherwise encode at the display refresh rate.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                setInteger(MediaFormat.KEY_OPERATING_RATE, info.fps)
            }
            // Proper pre-encode cap. Never drop already-encoded P-frames (that breaks
            // the H.264 reference chain and produces macroblock corruption).
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                setInteger(MediaFormat.KEY_MAX_FPS_TO_ENCODER, info.fps)
            }
        }

        val encoder = try {
            createAndConfigure(format)
        } catch (first: Throwable) {
            // A few vendors reject profile/level or low-latency keys. Retry with a minimal format.
            val fallback = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, info.width, info.height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, info.bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, info.fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            try {
                createAndConfigure(fallback)
            } catch (second: Throwable) {
                second.addSuppressed(first)
                throw second
            }
        }

        codec = encoder
        val surface = encoder.createInputSurface()
        encoderInputSurface = surface
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                surface.setFrameRate(
                    info.fps.toFloat(),
                    Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE
                )
            } catch (_: Exception) {
            }
        }
        encoder.start()

        // Render through a GL gate so a static screen still produces frames, and so the
        // encoder sees a clean 30 fps sequence instead of the display refresh rate.
        val newGate = try {
            GlFrameGate(info.width, info.height, surface).also {
                if (!it.start()) it.stop()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "GL gate unavailable: ${t.message}")
            null
        }
        gate = newGate
        val displaySurface = newGate?.outputSurface() ?: surface
        if (newGate == null) inputSurface = surface

        virtualDisplay = projection.createVirtualDisplay(
            "MoonShare",
            info.width,
            info.height,
            displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            displaySurface,
            null,
            null
        ) ?: throw IllegalStateException("系统未能创建虚拟显示")

        encoderThread = Thread({ drainEncoder(encoder) }, "MoonShare-Encoder").also {
            it.priority = Thread.NORM_PRIORITY + 1
            it.start()
        }
    }

    private fun createAndConfigure(format: MediaFormat): MediaCodec {
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        return try {
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder
        } catch (t: Throwable) {
            encoder.release()
            throw t
        }
    }

    private fun drainEncoder(encoder: MediaCodec) {
        val bufferInfo = MediaCodec.BufferInfo()
        try {
            while (running.get()) {
                if (keyFrameRequested.compareAndSet(true, false)) {
                    val params = android.os.Bundle().apply {
                        putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
                    }
                    try {
                        encoder.setParameters(params)
                    } catch (_: Exception) {
                    }
                }

                val index = try {
                    encoder.dequeueOutputBuffer(bufferInfo, 10_000)
                } catch (t: Throwable) {
                    if (running.get()) listener.onCaptureError("编码器读取失败: ${t.message}")
                    break
                }

                when {
                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        readCodecConfig(encoder.outputFormat)
                    }

                    index >= 0 -> {
                        try {
                            val outputBuffer = encoder.getOutputBuffer(index)
                            if (outputBuffer != null && bufferInfo.size > 0) {
                                val data = ByteArray(bufferInfo.size)
                                outputBuffer.position(bufferInfo.offset)
                                outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                                outputBuffer.get(data)
                                val isConfig = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                                if (isConfig) {
                                    spsPps = data
                                } else if (data.isNotEmpty()) {
                                    val flaggedKey = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
                                    // Some vendor encoders emit an IDR slice without setting the
                                    // MediaCodec key-frame flag. Check only the first VCL NAL;
                                    // scanning all slice bytes produces false positives.
                                    val keyFrame = flaggedKey || firstVclIsIdr(data)
                                    val payload = if (keyFrame) withCodecConfig(data) else data
                                    encodedFrames++
                                    if (encodedFrames == 1L) {
                                        Log.i(TAG, "encoder output started, key=$keyFrame bytes=${payload.size}")
                                    }
                                    listener.onFrame(
                                        VideoFrame(
                                            data = payload,
                                            keyFrame = keyFrame,
                                            presentationTimeUs = bufferInfo.presentationTimeUs
                                        )
                                    )
                                }
                            }
                        } finally {
                            try {
                                encoder.releaseOutputBuffer(index, false)
                            } catch (_: Exception) {
                            }
                        }
                    }
                }
            }
        } finally {
            running.set(false)
        }
    }

    private fun readCodecConfig(format: MediaFormat) {
        val parts = ArrayList<ByteArray>(2)
        for (name in listOf("csd-0", "csd-1")) {
            val buffer = format.getByteBuffer(name) ?: continue
            val copy = buffer.duplicate()
            val bytes = ByteArray(copy.remaining())
            copy.get(bytes)
            if (bytes.isNotEmpty()) parts.add(bytes)
        }
        if (parts.isNotEmpty()) {
            val total = parts.sumOf { it.size }
            val merged = ByteArray(total)
            var offset = 0
            for (part in parts) {
                System.arraycopy(part, 0, merged, offset, part.size)
                offset += part.size
            }
            spsPps = merged
        }
    }

    /** Every IDR must carry SPS/PPS so a viewer that joined mid-stream can decode it. */
    private fun withCodecConfig(frame: ByteArray): ByteArray {
        val config = spsPps ?: return frame
        if (containsSps(frame)) return frame
        val out = ByteArray(config.size + frame.size)
        System.arraycopy(config, 0, out, 0, config.size)
        System.arraycopy(frame, 0, out, config.size, frame.size)
        return out
    }

    private fun firstVclIsIdr(data: ByteArray): Boolean {
        var i = 0
        while (i + 3 < data.size) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte() && data[i + 2] == 1.toByte()) {
                val type = data[i + 3].toInt() and 0x1f
                if (type == 1) return false
                if (type == 5) return true
                i += 3
            } else {
                i++
            }
        }
        return false
    }

    private fun containsSps(data: ByteArray): Boolean = containsNalType(data, 7)

    private fun containsNalType(data: ByteArray, nalType: Int): Boolean {
        var i = 0
        while (i + 3 < data.size) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte() && data[i + 2] == 1.toByte()) {
                if ((data[i + 3].toInt() and 0x1f) == nalType) return true
                i += 3
            } else {
                i++
            }
        }
        return false
    }

    private fun chooseSize(): Pair<Int, Int> {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        val display = (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay
        @Suppress("DEPRECATION")
        display.getRealMetrics(metrics)

        var width = metrics.widthPixels
        var height = metrics.heightPixels
        if (width <= 0 || height <= 0) {
            width = 1280
            height = 720
        }
        val longEdge = maxOf(width, height)
        if (longEdge > MAX_LONG_EDGE) {
            val scale = MAX_LONG_EDGE.toFloat() / longEdge.toFloat()
            width = (width * scale).toInt()
            height = (height * scale).toInt()
        }
        width = (width / 2) * 2
        height = (height / 2) * 2
        return width.coerceAtLeast(320) to height.coerceAtLeast(240)
    }

    private fun chooseBitrate(width: Int, height: Int): Int {
        val pixels = width.toLong() * height.toLong()
        // Screen content is high-entropy (text + scrolling UI). 0.10 bit/pixel/frame
        // (~5 Mbps at 864x1920/30) visibly smears on fast motion. Use 0.35 bpp and a
        // 30 Mbps ceiling; VBR can still spend less while the screen is static.
        val estimate = (pixels * TARGET_FPS * 0.50).toLong()
        return estimate.coerceIn(6_000_000L, 40_000_000L).toInt()
    }

    private fun stopInternal() {
        try {
            virtualDisplay?.release()
        } catch (_: Exception) {
        }
        virtualDisplay = null

        gate?.stop()
        gate = null

        val thread = encoderThread
        encoderThread = null
        if (thread != null && thread !== Thread.currentThread()) {
            try {
                thread.join(700)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }

        try {
            inputSurface?.release()
        } catch (_: Exception) {
        }
        inputSurface = null
        encoderInputSurface?.release()
        encoderInputSurface = null

        val encoder = codec
        codec = null
        if (encoder != null) {
            try {
                encoder.stop()
            } catch (_: Exception) {
            }
            try {
                encoder.release()
            } catch (_: Exception) {
            }
        }
    }

    companion object {
        private const val TAG = "MoonShareCapture"
        private const val TARGET_FPS = 30
        private const val MAX_LONG_EDGE = 1920
    }
}
