package dev.lindroid.app.desktop

import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread

class RfbClient(
    private val host: InetAddress = InetAddress.getLoopbackAddress(),
    private val port: Int = VNC_PORT,
    private val password: String,
) {
    data class Rect(val x: Int, val y: Int, val w: Int, val h: Int)

    @Volatile
    var width = 0
        private set

    @Volatile
    var height = 0
        private set

    private val lock = Any()
    private var frame: IntArray = IntArray(0)
    private var socket: Socket? = null
    private var input: DataInputStream? = null
    private var output: DataOutputStream? = null
    private var reader: Thread? = null

    @Volatile
    private var running = false
    private var hextileBackground = 0xFF000000.toInt()
    private var hextileForeground = 0xFFFFFFFF.toInt()

    fun connect() {
        val socket = Socket()
        socket.connect(InetSocketAddress(host, port), 5_000)
        socket.tcpNoDelay = true
        val input = DataInputStream(socket.inputStream.buffered())
        val output = DataOutputStream(socket.outputStream.buffered())
        this.socket = socket
        this.input = input
        this.output = output

        val version = ByteArray(12)
        input.readFully(version)
        output.write(version)
        output.flush()

        val scheme = negotiateSecurity(input, output)
        if (scheme == SECURITY_VNC_AUTH) {
            val challenge = ByteArray(16)
            input.readFully(challenge)
            output.write(encryptChallenge(challenge, password))
            output.flush()
        }
        val result = input.readInt()
        check(result == 0) { "VNC server refused the password" }

        output.writeByte(1)
        output.flush()

        width = input.readUnsignedShort()
        height = input.readUnsignedShort()
        input.skipBytes(16)
        val nameLength = input.readInt()
        if (nameLength > 0) input.skipBytes(nameLength)
        check(width in 1..4096 && height in 1..4096) { "Bad framebuffer size ${width}x$height" }
        frame = IntArray(width * height)

        output.writeByte(0)
        output.writeByte(0)
        output.writeByte(0)
        output.writeByte(0)
        output.writeByte(32)
        output.writeByte(24)
        output.writeByte(0)
        output.writeByte(1)
        output.writeShort(255)
        output.writeShort(255)
        output.writeShort(255)
        output.writeByte(16)
        output.writeByte(8)
        output.writeByte(0)
        output.writeByte(0)
        output.writeByte(0)
        output.writeByte(0)
        output.flush()

        output.writeByte(2)
        output.writeByte(0)
        output.writeShort(3)
        output.writeInt(ENCODING_COPY_RECT)
        output.writeInt(ENCODING_HEXTILE)
        output.writeInt(ENCODING_RAW)
        output.flush()

        requestUpdate(incremental = false)
    }

    fun startLoop(onFrame: (Rect) -> Unit) {
        check(socket != null) { "Call connect first" }
        running = true
        reader = thread(name = "rfb-loop", isDaemon = true) {
            try {
                while (running) {
                    readServerMessage(onFrame)
                }
            } catch (error: Exception) {
                if (running) onFrame(Rect(0, 0, 0, 0))
            }
        }
    }

    fun snapshot(into: IntArray) {
        synchronized(lock) {
            frame.copyInto(into, 0, 0, minOf(frame.size, into.size))
        }
    }

    fun frameSize(): Int = synchronized(lock) { frame.size }

    fun sendPointer(x: Int, y: Int, buttons: Int) {
        val output = output ?: return
        synchronized(output) {
            output.writeByte(5)
            output.writeByte(buttons)
            output.writeShort(x.coerceIn(0, (width - 1).coerceAtLeast(0)))
            output.writeShort(y.coerceIn(0, (height - 1).coerceAtLeast(0)))
            output.flush()
        }
    }

    fun sendKey(keysym: Int, down: Boolean) {
        val output = output ?: return
        synchronized(output) {
            output.writeByte(4)
            output.writeByte(if (down) 1 else 0)
            output.writeShort(0)
            output.writeInt(keysym)
            output.flush()
        }
    }

    fun typeKey(keysym: Int) {
        sendKey(keysym, true)
        sendKey(keysym, false)
    }

    fun close() {
        running = false
        runCatching { socket?.close() }
        reader?.join(1000)
        socket = null
    }

    private fun negotiateSecurity(input: DataInputStream, output: DataOutputStream): Int {
        val count = input.readUnsignedByte()
        return if (count == 0) {
            val length = input.readInt()
            val reason = ByteArray(length)
            if (length > 0) input.readFully(reason)
            error("VNC server offers no security: ${String(reason)}")
        } else {
            val types = ByteArray(count)
            input.readFully(types)
            val scheme = when {
                types.contains(SECURITY_VNC_AUTH.toByte()) -> SECURITY_VNC_AUTH
                types.contains(SECURITY_NONE.toByte()) -> SECURITY_NONE
                else -> error("Unsupported VNC security types")
            }
            output.writeByte(scheme)
            output.flush()
            scheme
        }
    }

    private fun requestUpdate(incremental: Boolean) {
        val output = output ?: return
        synchronized(output) {
            output.writeByte(3)
            output.writeByte(if (incremental) 1 else 0)
            output.writeShort(0)
            output.writeShort(0)
            output.writeShort(width)
            output.writeShort(height)
            output.flush()
        }
    }

    private fun readServerMessage(onFrame: (Rect) -> Unit) {
        val input = input ?: return
        when (input.readUnsignedByte()) {
            0 -> {
                input.readByte()
                val rects = input.readUnsignedShort()
                var dirty: Rect? = null
                repeat(rects) {
                    val x = input.readUnsignedShort()
                    val y = input.readUnsignedShort()
                    val w = input.readUnsignedShort()
                    val h = input.readUnsignedShort()
                    val encoding = input.readInt()
                    when (encoding) {
                        ENCODING_RAW -> decodeRaw(input, x, y, w, h)
                        ENCODING_COPY_RECT -> decodeCopyRect(input, x, y, w, h)
                        ENCODING_HEXTILE -> decodeHextile(input, x, y, w, h)
                        else -> error("Unsupported encoding $encoding")
                    }
                    dirty = dirty?.union(Rect(x, y, w, h)) ?: Rect(x, y, w, h)
                }
                dirty?.let(onFrame)
                requestUpdate(incremental = true)
            }
            1 -> {
                input.readByte()
                val length = input.readUnsignedShort()
                input.skipBytes(length * 6)
            }
            2 -> Unit
            3 -> {
                val length = input.readInt()
                if (length > 0) input.skipBytes(length)
            }
            else -> error("Unknown VNC message")
        }
    }

    private fun decodeRaw(input: DataInputStream, x: Int, y: Int, w: Int, h: Int) {
        val bytes = ByteArray(w * h * 4)
        input.readFully(bytes)
        synchronized(lock) {
            var src = 0
            for (row in 0 until h) {
                val dst = (y + row) * width + x
                for (col in 0 until w) {
                    val r = bytes[src++].toInt() and 0xFF
                    val g = bytes[src++].toInt() and 0xFF
                    val b = bytes[src++].toInt() and 0xFF
                    src++
                    frame[dst + col] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
        }
    }

    private fun decodeCopyRect(input: DataInputStream, x: Int, y: Int, w: Int, h: Int) {
        val srcX = input.readUnsignedShort()
        val srcY = input.readUnsignedShort()
        synchronized(lock) {
            val copy = IntArray(w * h)
            for (row in 0 until h) {
                System.arraycopy(frame, (srcY + row) * width + srcX, copy, row * w, w)
            }
            for (row in 0 until h) {
                System.arraycopy(copy, row * w, frame, (y + row) * width + x, w)
            }
        }
    }

    private fun decodeHextile(input: DataInputStream, x: Int, y: Int, w: Int, h: Int) {
        val tilesX = (w + 15) / 16
        val tilesY = (h + 15) / 16
        for (tileY in 0 until tilesY) {
            for (tileX in 0 until tilesX) {
                val tx = x + tileX * 16
                val ty = y + tileY * 16
                val tw = minOf(16, x + w - tx)
                val th = minOf(16, y + h - ty)
                val subencoding = input.readUnsignedByte()
                if (subencoding == 1) {
                    decodeRaw(input, tx, ty, tw, th)
                } else {
                    if (subencoding and 0x02 != 0) hextileBackground = readPixel(input)
                    if (subencoding and 0x04 != 0) hextileForeground = readPixel(input)
                    synchronized(lock) {
                        fill(tx, ty, tw, th, hextileBackground)
                    }
                    if (subencoding and 0x08 != 0) {
                        val subrects = input.readUnsignedByte()
                        val coloured = subencoding and 0x10 != 0
                        repeat(subrects) {
                            val color = if (coloured) readPixel(input) else hextileForeground
                            val xy = input.readUnsignedByte()
                            val wh = input.readUnsignedByte()
                            val sx = xy shr 4
                            val sy = xy and 0x0F
                            val sw = (wh shr 4) + 1
                            val sh = (wh and 0x0F) + 1
                            synchronized(lock) {
                                fill(tx + sx, ty + sy, sw, sh, color)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun readPixel(input: DataInputStream): Int {
        val r = input.readUnsignedByte()
        val g = input.readUnsignedByte()
        val b = input.readUnsignedByte()
        input.readByte()
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun fill(x: Int, y: Int, w: Int, h: Int, color: Int) {
        for (row in 0 until h) {
            val dst = (y + row) * width + x
            for (col in 0 until w) {
                frame[dst + col] = color
            }
        }
    }

    private fun Rect.union(other: Rect): Rect {
        val x0 = minOf(x, other.x)
        val y0 = minOf(y, other.y)
        val x1 = maxOf(x + w, other.x + other.w)
        val y1 = maxOf(y + h, other.y + other.h)
        return Rect(x0, y0, x1 - x0, y1 - y0)
    }

    companion object {
        const val VNC_PORT = 5901
        private const val SECURITY_NONE = 1
        private const val SECURITY_VNC_AUTH = 2
        private const val ENCODING_RAW = 0
        private const val ENCODING_COPY_RECT = 1
        private const val ENCODING_HEXTILE = 5

        fun encryptChallenge(challenge: ByteArray, password: String): ByteArray {
            val keyBytes = ByteArray(8)
            val raw = password.toByteArray(Charsets.US_ASCII)
            System.arraycopy(raw, 0, keyBytes, 0, minOf(raw.size, 8))
            for (i in keyBytes.indices) {
                keyBytes[i] = mirrorBits(keyBytes[i].toInt()).toByte()
            }
            val cipher = Cipher.getInstance("DES/ECB/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, "DES"))
            return cipher.doFinal(challenge)
        }

        private fun mirrorBits(value: Int): Int {
            var v = value and 0xFF
            v = ((v and 0x55) shl 1) or ((v and 0xAA) ushr 1)
            v = ((v and 0x33) shl 2) or ((v and 0xCC) ushr 2)
            v = ((v and 0x0F) shl 4) or ((v and 0xF0) ushr 4)
            return v
        }
    }
}
