package ru.yourok.torrserve.ui.activities.main

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ru.yourok.torrserve.server.api.Health
import ru.yourok.torrserve.server.api.TorrmediaApi
import ru.yourok.torrserve.server.api.Storage

class TorrmediaHeaderTest {

    private val gib = 1L shl 30

    private fun health(vararg checks: Pair<String, String>, contract: Int = TorrmediaApi.CONTRACT) =
        Health("2026-07-28.9", contract, mapOf(*checks))

    @Test
    fun `link state comes from health checks`() {
        assertEquals(TorrmediaLink.ONLINE, linkOf(health("root" to "ok", "viewed" to "ok", "qbittorrent" to "ok")))
        // qBittorrent is a separate cause: the service is alive but has nothing to download with
        assertEquals(TorrmediaLink.QBIT_DOWN, linkOf(health("root" to "ok", "qbittorrent" to "connection refused")))
        assertEquals(TorrmediaLink.DEGRADED, linkOf(health("root" to "media root is not available", "qbittorrent" to "ok")))
        assertEquals(TorrmediaLink.MISMATCH, linkOf(health("root" to "ok", contract = TorrmediaApi.CONTRACT + 1)))
    }

    @Test
    fun `disk usage and level`() {
        val half = Storage(free = 50 * gib, total = 100 * gib, reserve = gib / 2)
        assertEquals(50, usedPercent(half))
        assertEquals(DiskLevel.OK, diskLevel(half))
        assertEquals(DiskLevel.LOW, diskLevel(Storage(8 * gib, 100 * gib, gib / 2)))
        assertEquals(DiskLevel.CRITICAL, diskLevel(Storage(2 * gib, 100 * gib, gib / 2)))
        // on a small partition the server reserve, not the percentage, decides criticality
        assertEquals(DiskLevel.CRITICAL, diskLevel(Storage(gib - 1, 20 * gib, gib / 2)))
    }

    @Test
    fun `unknown sizes are not zeros`() {
        assertNull(usedPercent(Storage(null, 100 * gib, 0)))
        assertNull(usedPercent(Storage(10 * gib, null, 0)))
        assertNull(diskLevel(Storage(10 * gib, 0, 0)))
    }

    @Test
    fun `host is shown without scheme`() {
        assertEquals("192.0.2.10:8091", displayHost("http://192.0.2.10:8091/"))
        assertEquals("torrmedia.example:443", displayHost("https://torrmedia.example:443"))
    }
}
