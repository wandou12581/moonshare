package com.moonshare.protocol

import java.io.ByteArrayOutputStream
import java.security.SecureRandom

/**
 * Wire constants shared by the control, discovery and video paths.
 * Keep these values in sync with docs/PROTOCOL.md and the PC viewer.
 */
object Protocol {
    const val VERSION = 1

    const val TCP_PORT = 47800
    const val VIDEO_PORT = 47801
    const val DISCOVERY_PORT = 47802
    const val AUDIO_PORT = 47804

    const val MAX_CONTROL_LINE = 8 * 1024
    const val MAX_FRAME_BYTES = 4 * 1024 * 1024
    const val VIDEO_DATAGRAM_BYTES = 1200
    const val VIDEO_HEADER_BYTES = 36
    const val VIDEO_PAYLOAD_BYTES = VIDEO_DATAGRAM_BYTES - VIDEO_HEADER_BYTES

    const val DISCOVER_REQUEST = "MOONSHARE/1 DISCOVER"

    const val UDP_HELLO_BYTES = 32
    const val UDP_HELLO = 1
    const val UDP_HELLO_ACK = 2

    private val random = SecureRandom()

    fun newSessionId(): Long = random.nextLong()

    fun randomBytes(size: Int): ByteArray = ByteArray(size).also { random.nextBytes(it) }

    fun hex(bytes: ByteArray): String {
        val out = CharArray(bytes.size * 2)
        val digits = "0123456789abcdef"
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xff
            out[i * 2] = digits[v ushr 4]
            out[i * 2 + 1] = digits[v and 0x0f]
        }
        return String(out)
    }

    fun parseHex(value: String, expectedBytes: Int): ByteArray? {
        if (value.length != expectedBytes * 2) return null
        val out = ByteArray(expectedBytes)
        for (i in 0 until expectedBytes) {
            val hi = Character.digit(value[i * 2], 16)
            val lo = Character.digit(value[i * 2 + 1], 16)
            if (hi < 0 || lo < 0) return null
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    /** Builds one 36-byte MSV1 fragment header. */
    fun videoHeader(
        sessionId: Long,
        frameId: Int,
        fragmentIndex: Int,
        fragmentCount: Int,
        payloadLength: Int,
        keyFrame: Boolean,
        presentationTimeUs: Long
    ): ByteArray {
        require(payloadLength in 0..VIDEO_PAYLOAD_BYTES)
        require(fragmentCount in 1..0xffff)
        require(fragmentIndex in 0 until fragmentCount)
        val header = ByteArray(VIDEO_HEADER_BYTES)
        header[0] = 'M'.code.toByte()
        header[1] = 'S'.code.toByte()
        header[2] = 'V'.code.toByte()
        header[3] = '1'.code.toByte()
        header[4] = VERSION.toByte()
        header[5] = if (keyFrame) 0x01 else 0x00
        putShort(header, 6, VIDEO_HEADER_BYTES)
        putLong(header, 8, sessionId)
        putInt(header, 16, frameId)
        putShort(header, 20, fragmentIndex)
        putShort(header, 22, fragmentCount)
        putShort(header, 24, payloadLength)
        putShort(header, 26, 0)
        putLong(header, 28, presentationTimeUs)
        return header
    }

    /** Parses an incoming MSU1 HELLO. Returns null when the packet is malformed. */
    fun parseUdpHello(packet: ByteArray, length: Int): UdpHello? {
        if (length != UDP_HELLO_BYTES) return null
        if (packet[0] != 'M'.code.toByte() || packet[1] != 'S'.code.toByte() ||
            packet[2] != 'U'.code.toByte() || packet[3] != '1'.code.toByte()
        ) return null
        if (packet[4].toInt() and 0xff != VERSION) return null
        if (packet[5].toInt() and 0xff != UDP_HELLO) return null
        val session = getLong(packet, 8)
        val token = packet.copyOfRange(16, 32)
        return UdpHello(session, token)
    }

    fun udpHelloAck(sessionId: Long): ByteArray {
        val out = ByteArray(UDP_HELLO_BYTES)
        out[0] = 'M'.code.toByte()
        out[1] = 'S'.code.toByte()
        out[2] = 'U'.code.toByte()
        out[3] = '1'.code.toByte()
        out[4] = VERSION.toByte()
        out[5] = UDP_HELLO_ACK.toByte()
        putLong(out, 8, sessionId)
        return out
    }

    fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
        return diff == 0
    }

    fun putShort(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value ushr 8).toByte()
        target[offset + 1] = value.toByte()
    }

    fun putInt(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value ushr 24).toByte()
        target[offset + 1] = (value ushr 16).toByte()
        target[offset + 2] = (value ushr 8).toByte()
        target[offset + 3] = value.toByte()
    }

    fun putLong(target: ByteArray, offset: Int, value: Long) {
        target[offset] = (value ushr 56).toByte()
        target[offset + 1] = (value ushr 48).toByte()
        target[offset + 2] = (value ushr 40).toByte()
        target[offset + 3] = (value ushr 32).toByte()
        target[offset + 4] = (value ushr 24).toByte()
        target[offset + 5] = (value ushr 16).toByte()
        target[offset + 6] = (value ushr 8).toByte()
        target[offset + 7] = value.toByte()
    }

    fun getLong(source: ByteArray, offset: Int): Long {
        var value = 0L
        for (i in 0 until 8) value = (value shl 8) or (source[offset + i].toLong() and 0xff)
        return value
    }

    fun getInt(source: ByteArray, offset: Int): Int {
        var value = 0
        for (i in 0 until 4) value = (value shl 8) or (source[offset + i].toInt() and 0xff)
        return value
    }

    fun getShort(source: ByteArray, offset: Int): Int =
        ((source[offset].toInt() and 0xff) shl 8) or (source[offset + 1].toInt() and 0xff)
}

data class UdpHello(val sessionId: Long, val token: ByteArray)

/** One encoded H.264 access unit. */
data class VideoFrame(
    val data: ByteArray,
    val keyFrame: Boolean,
    val presentationTimeUs: Long
)

data class StreamInfo(
    val width: Int,
    val height: Int,
    val fps: Int,
    val bitrate: Int,
    val codec: String = "h264",
    val profile: String = "baseline"
)

/** Bounded line reader so a hostile control peer cannot allocate unbounded memory. */
class LineReader(private val input: java.io.InputStream, private val maxBytes: Int = Protocol.MAX_CONTROL_LINE) {
    fun readLine(): String? {
        val buffer = ByteArrayOutputStream(minOf(256, maxBytes))
        while (true) {
            val b = input.read()
            if (b < 0) {
                if (buffer.size() == 0) return null
                return buffer.toString("UTF-8")
            }
            if (b == '\n'.code) return buffer.toString("UTF-8")
            if (b != '\r'.code) {
                buffer.write(b)
                if (buffer.size() > maxBytes) throw java.io.IOException("control line too long")
            }
        }
    }
}

fun ByteArray.startsWithAscii(text: String): Boolean {
    if (size < text.length) return false
    for (i in text.indices) if (this[i] != text[i].code.toByte()) return false
    return true
}
