package ru.yourok.torrserve.server.api

import kotlin.math.min

/**
 * Parsing of text/event-stream and computing the pause before a reconnect.
 * There are deliberately no Android dependencies here: both are covered by JVM tests
 * (app/src/test/.../TorrmediaStreamTest.kt).
 */

data class SseEvent(val name: String, val data: String)

/**
 * Line-based SSE parser. It is fed arbitrary chunks of the stream: a socket read boundary
 * does not match a line boundary, so a tail without a newline stays in the buffer until the
 * next feed(). An event is emitted on an empty line, and a single read may bring any number
 * of them.
 */
class SseParser(private val onEvent: (SseEvent) -> Unit) {
    private val pending = StringBuilder()
    private val data = StringBuilder()
    private var name = ""

    fun feed(chunk: String) {
        pending.append(chunk)
        while (true) {
            val nl = pending.indexOf("\n")
            if (nl < 0)
                break
            var line = pending.substring(0, nl)
            pending.delete(0, nl + 1)
            if (line.endsWith("\r"))
                line = line.substring(0, line.length - 1)
            consume(line)
        }
    }

    private fun consume(line: String) {
        if (line.isEmpty()) {
            dispatch()
            return
        }
        if (line[0] == ':') // a comment: proxies use these to keep the connection alive
            return
        val colon = line.indexOf(':')
        val field = if (colon < 0) line else line.substring(0, colon)
        var value = if (colon < 0) "" else line.substring(colon + 1)
        if (value.startsWith(" ")) // exactly one space after the colon is syntax, not data
            value = value.substring(1)
        when (field) {
            "event" -> name = value
            "data" -> data.append(value).append('\n')
            // the contract does not use id or retry
        }
    }

    private fun dispatch() {
        if (name.isEmpty() && data.isEmpty())
            return
        val payload = if (data.isEmpty()) "" else data.substring(0, data.length - 1)
        val event = SseEvent(name.ifEmpty { "message" }, payload)
        name = ""
        data.setLength(0)
        onEvent(event)
    }
}

const val RECONNECT_BASE_MS = 1000L
const val RECONNECT_CAP_MS = 30_000L

/**
 * Pause before attempt #attempt (0 is the first one after a disconnect). Exponential backoff
 * with a cap and jitter: without jitter all clients come back at the same moment and finish
 * off a service that has just come back up. The function is pure, so the test neither sleeps
 * nor guesses.
 *
 * jitter is expected in [0,1) (usually Math.random()); out-of-range values are clamped.
 */
fun reconnectDelayMs(attempt: Int, jitter: Double): Long {
    val steps = min(attempt.coerceAtLeast(0), 5)
    val capped = min(RECONNECT_BASE_MS shl steps, RECONNECT_CAP_MS)
    val half = capped / 2
    return half + (half * jitter.coerceIn(0.0, 0.999)).toLong()
}
