package ru.yourok.torrserve.server.api

import ru.yourok.torrserve.atv.Utils.isBrokenTCL
import ru.yourok.torrserve.utils.TlsSocketFactory
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection

internal object TorrmediaHttp {

    data class Response(val code: Int, val body: String)

    private const val CONNECT_TIMEOUT = 7_000

    private const val READ_TIMEOUT = 15_000

    private const val STREAM_READ_TIMEOUT = 45_000

    class Stream internal constructor(
        private val conn: HttpURLConnection,
        private val input: InputStream,
    ) {
        @Volatile
        private var closed = false

        fun read(onEvent: (SseEvent) -> Unit) {
            val parser = SseParser(onEvent)
            val reader = InputStreamReader(input, Charsets.UTF_8)
            val buf = CharArray(4096)
            while (true) {
                val n = try {
                    reader.read(buf)
                } catch (e: Exception) {
                    if (closed) return
                    throw e
                }
                if (n < 0)
                    return
                parser.feed(String(buf, 0, n))
            }
        }

        fun close() {
            closed = true
            runCatching { input.close() }
            runCatching { conn.disconnect() }
        }
    }

    fun request(method: String, url: String, token: String, body: String?): Response {
        val conn = open(url, token, READ_TIMEOUT)
        try {
            conn.requestMethod = method
            conn.setRequestProperty("Accept", "application/json")
            if (body != null) {
                val bytes = body.toByteArray(Charsets.UTF_8)
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                conn.setFixedLengthStreamingMode(bytes.size)
                val out = conn.outputStream
                try {
                    out.write(bytes)
                } finally {
                    out.close()
                }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            return Response(code, stream?.let { readAll(it) } ?: "")
        } finally {
            conn.disconnect()
        }
    }

    fun openStream(url: String, token: String): Stream {
        val conn = open(url, token, STREAM_READ_TIMEOUT)
        try {
            conn.requestMethod = "GET"
            conn.setRequestProperty("Accept", "text/event-stream")
            conn.setRequestProperty("Accept-Encoding", "identity")
            val code = conn.responseCode
            if (code !in 200..299)
                throw parseError(code, conn.errorStream?.let { readAll(it) } ?: "")
            return Stream(conn, conn.inputStream)
        } catch (e: Throwable) {
            conn.disconnect()
            throw e
        }
    }

    private fun open(url: String, token: String, readTimeout: Int): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = CONNECT_TIMEOUT
        conn.readTimeout = readTimeout
        conn.useCaches = false
        if (token.isNotEmpty())
            conn.setRequestProperty("Authorization", "Bearer $token")
        if (conn is HttpsURLConnection && !isBrokenTCL)
            conn.sslSocketFactory = TlsSocketFactory()
        return conn
    }

    private fun readAll(stream: InputStream): String {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        try {
            while (true) {
                val n = stream.read(buf)
                if (n < 0)
                    break
                out.write(buf, 0, n)
            }
        } finally {
            stream.close()
        }
        return out.toString("UTF-8")
    }
}
