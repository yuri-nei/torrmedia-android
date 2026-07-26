package ru.yourok.torrserve.server.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShelfLogicTest {

    private data class Torr(val hash: String, val contentPath: String)

    // --- B1 -----------------------------------------------------------------

    @Test
    fun `same basename in different categories does not match`() {
        val torrents = listOf(
            Torr("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "/data/movies/Foo"),
            Torr("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", "/data/shows/Foo"),
        )
        val resolved = resolveSingleByHash("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", torrents) { it.hash }
        assertEquals("/data/shows/Foo", resolved?.contentPath)
    }

    @Test
    fun `unknown owner never matches by name`() {
        val torrents = listOf(Torr("aaaa", "/data/movies/Foo"))
        assertNull(resolveSingleByHash("", torrents) { it.hash })
    }

    @Test
    fun `missing torrent gives no candidate`() {
        val torrents = listOf(Torr("aaaa", "/data/movies/Foo"))
        assertNull(resolveSingleByHash("cccc", torrents) { it.hash })
    }

    @Test
    fun `ambiguous candidates give no match`() {
        val torrents = listOf(Torr("aaaa", "/data/movies/Foo"), Torr("AAAA", "/data/shows/Foo"))
        assertNull(resolveSingleByHash("aaaa", torrents) { it.hash })
    }

    @Test
    fun `hash comparison is case insensitive`() {
        val torrents = listOf(Torr("AE7DEF00", "/data/shows/Foo"))
        assertEquals("/data/shows/Foo", resolveSingleByHash("ae7def00", torrents) { it.hash }?.contentPath)
    }

    // --- B3 -----------------------------------------------------------------

    @Test
    fun `snapshot stays valid when nothing changed`() {
        val snap = FileOpSnapshot("ae7def", 3, "shows/Foo/e01.mkv", 100)
        assertTrue(snapshotStillValid(snap, snap.copy(size = 200)))
    }

    @Test
    fun `changed file index cancels the operation`() {
        val snap = FileOpSnapshot("ae7def", 3, "shows/Foo/e01.mkv", 100)
        assertFalse(snapshotStillValid(snap, snap.copy(qbitFileId = 4)))
    }

    @Test
    fun `changed torrent hash cancels the operation`() {
        val snap = FileOpSnapshot("ae7def", 3, "shows/Foo/e01.mkv", 100)
        assertFalse(snapshotStillValid(snap, snap.copy(hash = "bbbbbb")))
    }

    @Test
    fun `vanished file cancels the operation`() {
        val snap = FileOpSnapshot("ae7def", 3, "shows/Foo/e01.mkv", 100)
        assertFalse(snapshotStillValid(snap, null))
    }

    @Test
    fun `shelf only file must not gain a torrent owner`() {
        val snap = FileOpSnapshot("", -1, "shows/Foo/e01.mkv", 100)
        assertTrue(snapshotStillValid(snap, snap))
        assertFalse(snapshotStillValid(snap, FileOpSnapshot("ae7def", 3, "shows/Foo/e01.mkv", 100)))
    }

    // --- C4 -----------------------------------------------------------------

    @Test
    fun `ok false is an error even on HTTP 200`() {
        val result = parseTorrmediaResult("""{"ok": false, "error": "torrent is locked"}""")
        assertFalse(result.ok)
        assertEquals("torrent is locked", result.error)
    }

    @Test
    fun `warnings are parsed and not swallowed`() {
        val result = parseTorrmediaResult("""{"ok": true, "deleted": ["a"], "warnings": ["partfile kept", ""]}""")
        assertTrue(result.ok)
        assertEquals(listOf("partfile kept"), result.warnings)
    }

    @Test
    fun `empty or non json body counts as success`() {
        assertTrue(parseTorrmediaResult("").ok)
        assertTrue(parseTorrmediaResult("OK").ok)
        assertTrue(parseTorrmediaResult("{}").ok)
    }
}
