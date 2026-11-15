package ru.yourok.torrserve.server.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parsing of text/event-stream and the reconnect backoff. Plain JVM, no Android, no sleeping.
 */
class TorrmediaStreamTest {

    private fun collect(vararg chunks: String): List<SseEvent> {
        val out = mutableListOf<SseEvent>()
        val parser = SseParser { out.add(it) }
        chunks.forEach { parser.feed(it) }
        return out
    }

    @Test
    fun `several events arrive in one read`() {
        val events = collect(
            "event: state\ndata: {\"items\":[]}\n\n" +
                    "event: ping\ndata: {}\n\n" +
                    "event: state\ndata: {\"items\":[1]}\n\n"
        )
        assertEquals(3, events.size)
        assertEquals("state", events[0].name)
        assertEquals("{\"items\":[]}", events[0].data)
        assertEquals("ping", events[1].name)
        assertEquals("{}", events[1].data)
        assertEquals("{\"items\":[1]}", events[2].data)
    }

    @Test
    fun `event name comes before data`() {
        val events = collect("event: operation\ndata: {\"id\":\"op_1\"}\n\n")
        assertEquals("operation", events[0].name)
        assertEquals("{\"id\":\"op_1\"}", events[0].data)
    }

    @Test
    fun `multiline data is joined with newlines`() {
        val events = collect("event: state\ndata: {\ndata: \"a\":1\ndata: }\n\n")
        assertEquals(1, events.size)
        assertEquals("{\n\"a\":1\n}", events[0].data)
    }

    @Test
    fun `comments and blank lines do not produce events`() {
        val events = collect(": keep-alive from a proxy\n\n\n: another\nevent: ping\ndata: {}\n\n")
        assertEquals(1, events.size)
        assertEquals("ping", events[0].name)
    }

    @Test
    fun `an event split across reads is not lost`() {
        // a socket read boundary does not match a line boundary
        val events = collect("event: sta", "te\ndata: {\"item", "s\":[]}\n", "\n")
        assertEquals(1, events.size)
        assertEquals("state", events[0].name)
        assertEquals("{\"items\":[]}", events[0].data)
    }

    @Test
    fun `an unterminated event is not delivered`() {
        // until a blank line arrives, the event does not count as received
        assertTrue(collect("event: state\ndata: {\"items\":[]}\n").isEmpty())
    }

    @Test
    fun `crlf and a missing space after the colon are handled`() {
        val events = collect("event:state\r\ndata:{\"a\":1}\r\n\r\n")
        assertEquals(1, events.size)
        assertEquals("state", events[0].name)
        assertEquals("{\"a\":1}", events[0].data)
    }

    @Test
    fun `only the first space after the colon is stripped`() {
        val events = collect("event: state\ndata:  x\n\n")
        assertEquals(" x", events[0].data)
    }

    @Test
    fun `data without an event name defaults to message`() {
        val events = collect("data: hi\n\n")
        assertEquals("message", events[0].name)
    }

    @Test
    fun `unknown fields are ignored`() {
        val events = collect("id: 42\nretry: 5000\nevent: ping\ndata: {}\n\n")
        assertEquals(1, events.size)
        assertEquals("ping", events[0].name)
    }

    // --- backoff --------------------------------------------------------------

    @Test
    fun `backoff grows exponentially and stops at the cap`() {
        // with no jitter it is the lower edge of the window: base * 2^n / 2
        val floors = (0..7).map { reconnectDelayMs(it, 0.0) }
        assertEquals(listOf(500L, 1000L, 2000L, 4000L, 8000L, 15000L, 15000L, 15000L), floors)
        // the cap is sane: even with maximum jitter the window stays within 30 s
        val ceils = (0..7).map { reconnectDelayMs(it, 0.999) }
        assertTrue(ceils.all { it < RECONNECT_CAP_MS })
        assertTrue(ceils.last() >= RECONNECT_CAP_MS / 2)
    }

    @Test
    fun `jitter spreads clients over the whole window`() {
        // identical backoff on every client means a service that has just come back up
        // is immediately knocked down again by all of them at once
        val low = reconnectDelayMs(3, 0.0)
        val high = reconnectDelayMs(3, 0.99)
        assertTrue(high > low)
        assertTrue(high < 2 * low)
    }

    @Test
    fun `out of range attempts and jitter are clamped`() {
        assertEquals(reconnectDelayMs(0, 0.0), reconnectDelayMs(-5, 0.0))
        assertTrue(reconnectDelayMs(3, -1.0) >= 4000L)
        assertTrue(reconnectDelayMs(3, 5.0) < 8000L)
    }
}
