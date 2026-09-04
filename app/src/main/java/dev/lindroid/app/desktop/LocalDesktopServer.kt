package dev.lindroid.app.desktop

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

internal class LocalDesktopServer(
    private val context: Context,
    private val scope: CoroutineScope,
) : Closeable {
    private val server = ServerSocket()
    private var acceptJob: Job? = null

    fun start() {
        if (acceptJob != null) return
        server.reuseAddress = true
        server.bind(InetSocketAddress(InetAddress.getLoopbackAddress(), HTTP_PORT))
        acceptJob = scope.launch(Dispatchers.IO) {
            while (!server.isClosed) {
                val client = runCatching { server.accept() }.getOrNull() ?: break
                launch { runCatching { handle(client) }.also { client.close() } }
            }
        }
    }

    private suspend fun handle(client: Socket) {
        client.tcpNoDelay = true
        val input = BufferedInputStream(client.getInputStream())
        val output = BufferedOutputStream(client.getOutputStream())
        val headerText = readHttpHeaders(input)
        val lines = headerText.split("\r\n")
        val request = lines.firstOrNull()?.split(' ') ?: return
        if (request.size < 2 || request[0] != "GET") return
        val headers = lines.drop(1).mapNotNull { line ->
            val separator = line.indexOf(':')
            if (separator <= 0) null else line.substring(0, separator).trim().lowercase() to line.substring(separator + 1).trim()
        }.toMap()

        if (headers["upgrade"]?.equals("websocket", ignoreCase = true) == true) {
            proxyWebSocket(input, output, headers, client)
        } else {
            serveAsset(request[1], output)
        }
    }

    private fun serveAsset(rawTarget: String, output: OutputStream) {
        val rawPath = rawTarget.substringBefore('?').substringBefore('#')
        val decoded = URLDecoder.decode(rawPath, StandardCharsets.UTF_8.name()).removePrefix("/")
        val path = decoded.ifBlank { "vnc.html" }
        if (path.split('/').any { it == ".." }) {
            writeHttpError(output, 403, "Forbidden")
            return
        }

        val bytes = runCatching { context.assets.open("novnc/$path").use { it.readBytes() } }.getOrElse {
            writeHttpError(output, 404, "Not found")
            return
        }
        val headers = buildString {
            append("HTTP/1.1 200 OK\r\n")
            append("Content-Type: ${mimeType(path)}\r\n")
            append("Content-Length: ${bytes.size}\r\n")
            append("Cache-Control: no-cache\r\n")
            append("X-Content-Type-Options: nosniff\r\n")
            append("Connection: close\r\n\r\n")
        }
        output.write(headers.toByteArray(StandardCharsets.US_ASCII))
        output.write(bytes)
        output.flush()
    }

    private suspend fun proxyWebSocket(
        browserInput: InputStream,
        browserOutput: OutputStream,
        headers: Map<String, String>,
        browser: Socket,
    ) {
        val key = headers["sec-websocket-key"] ?: return
        val accept = Base64.encodeToString(
            MessageDigest.getInstance("SHA-1").digest((key + WEB_SOCKET_GUID).toByteArray(StandardCharsets.US_ASCII)),
            Base64.NO_WRAP,
        )
        browserOutput.write(
            ("HTTP/1.1 101 Switching Protocols\r\n" +
                "Upgrade: websocket\r\n" +
                "Connection: Upgrade\r\n" +
                "Sec-WebSocket-Accept: $accept\r\n\r\n").toByteArray(StandardCharsets.US_ASCII),
        )
        browserOutput.flush()

        val vnc = Socket()
        vnc.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), VNC_PORT), 5_000)
        vnc.tcpNoDelay = true
        val outputLock = Any()

        try {
            coroutineScope {
                val browserToVnc = launch(Dispatchers.IO) {
                    val vncOutput = BufferedOutputStream(vnc.getOutputStream())
                    val fragmented = ByteArrayOutputStream()
                    while (true) {
                        val frame = readWebSocketFrame(browserInput)
                        when (frame.opcode) {
                            0x0 -> fragmented.write(frame.payload)
                            0x2 -> {
                                fragmented.reset()
                                fragmented.write(frame.payload)
                            }
                            0x8 -> break
                            0x9 -> synchronized(outputLock) {
                                writeWebSocketFrame(browserOutput, 0xA, frame.payload)
                            }
                        }
                        if (frame.fin && (frame.opcode == 0x0 || frame.opcode == 0x2)) {
                            vncOutput.write(fragmented.toByteArray())
                            vncOutput.flush()
                            fragmented.reset()
                        }
                    }
                }
                val vncToBrowser = launch(Dispatchers.IO) {
                    val buffer = ByteArray(16 * 1024)
                    val vncInput = BufferedInputStream(vnc.getInputStream())
                    while (true) {
                        val count = vncInput.read(buffer)
                        if (count < 0) break
                        synchronized(outputLock) {
                            writeWebSocketFrame(browserOutput, 0x2, buffer.copyOf(count))
                        }
                    }
                }
                browserToVnc.join()
                vnc.close()
                browser.close()
                vncToBrowser.cancelAndJoin()
            }
        } finally {
            vnc.close()
        }
    }

    private data class WebSocketFrame(val fin: Boolean, val opcode: Int, val payload: ByteArray)

    private fun readWebSocketFrame(input: InputStream): WebSocketFrame {
        val first = input.read().takeIf { it >= 0 } ?: throw EOFException()
        val second = input.read().takeIf { it >= 0 } ?: throw EOFException()
        val fin = first and 0x80 != 0
        val opcode = first and 0x0F
        val masked = second and 0x80 != 0
        var length = (second and 0x7F).toLong()
        if (length == 126L) length = readUnsigned(input, 2)
        if (length == 127L) length = readUnsigned(input, 8)
        require(length in 0..MAX_FRAME_SIZE) { "Invalid WebSocket frame size" }
        val mask = if (masked) input.readExact(4) else null
        val payload = input.readExact(length.toInt())
        if (mask != null) {
            payload.indices.forEach { index -> payload[index] = (payload[index].toInt() xor mask[index % 4].toInt()).toByte() }
        }
        return WebSocketFrame(fin, opcode, payload)
    }

    private fun writeWebSocketFrame(output: OutputStream, opcode: Int, payload: ByteArray) {
        output.write(0x80 or opcode)
        when {
            payload.size < 126 -> output.write(payload.size)
            payload.size <= 0xFFFF -> {
                output.write(126)
                output.write(ByteBuffer.allocate(2).order(ByteOrder.BIG_ENDIAN).putShort(payload.size.toShort()).array())
            }
            else -> {
                output.write(127)
                output.write(ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(payload.size.toLong()).array())
            }
        }
        output.write(payload)
        output.flush()
    }

    private fun readUnsigned(input: InputStream, bytes: Int): Long {
        var value = 0L
        repeat(bytes) { value = (value shl 8) or (input.read().takeIf { it >= 0 } ?: throw EOFException()).toLong() }
        return value
    }

    private fun InputStream.readExact(size: Int): ByteArray {
        val result = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val count = read(result, offset, size - offset)
            if (count < 0) throw EOFException()
            offset += count
        }
        return result
    }

    private fun readHttpHeaders(input: InputStream): String {
        val output = ByteArrayOutputStream()
        var matched = 0
        while (output.size() < MAX_HEADER_SIZE) {
            val value = input.read()
            if (value < 0) throw EOFException()
            output.write(value)
            matched = when {
                matched == 0 && value == '\r'.code -> 1
                matched == 1 && value == '\n'.code -> 2
                matched == 2 && value == '\r'.code -> 3
                matched == 3 && value == '\n'.code -> 4
                value == '\r'.code -> 1
                else -> 0
            }
            if (matched == 4) return output.toString(StandardCharsets.ISO_8859_1.name())
        }
        error("HTTP header is too large")
    }

    private fun writeHttpError(output: OutputStream, code: Int, message: String) {
        val body = message.toByteArray(StandardCharsets.UTF_8)
        output.write("HTTP/1.1 $code $message\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
        output.write(body)
        output.flush()
    }

    private fun mimeType(path: String) = when (path.substringAfterLast('.', "")) {
        "html" -> "text/html; charset=utf-8"
        "js" -> "text/javascript; charset=utf-8"
        "css" -> "text/css; charset=utf-8"
        "json" -> "application/json"
        "svg" -> "image/svg+xml"
        "png" -> "image/png"
        "woff2" -> "font/woff2"
        else -> "application/octet-stream"
    }

    override fun close() {
        server.close()
        acceptJob?.cancel()
        acceptJob = null
    }

    companion object {
        const val HTTP_PORT = 6080
        const val VNC_PORT = 5901
        private const val WEB_SOCKET_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
        private const val MAX_HEADER_SIZE = 32 * 1024
        private const val MAX_FRAME_SIZE = 16L * 1024L * 1024L
    }
}
