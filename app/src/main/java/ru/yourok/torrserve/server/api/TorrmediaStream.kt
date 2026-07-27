package ru.yourok.torrserve.server.api

import kotlin.math.min


data class SseEvent(val name: String, val data: String)

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
        if (line[0] == ':')
            return
        val colon = line.indexOf(':')
        val field = if (colon < 0) line else line.substring(0, colon)
        var value = if (colon < 0) "" else line.substring(colon + 1)
        if (value.startsWith(" "))
            value = value.substring(1)
        when (field) {
            "event" -> name = value
            "data" -> data.append(value).append('\n')
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

fun reconnectDelayMs(attempt: Int, jitter: Double): Long {
    val steps = min(attempt.coerceAtLeast(0), 5)
    val capped = min(RECONNECT_BASE_MS shl steps, RECONNECT_CAP_MS)
    val half = capped / 2
    return half + (half * jitter.coerceIn(0.0, 0.999)).toLong()
}
