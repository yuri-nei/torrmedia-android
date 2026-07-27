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
    fun `response without ok is a failure, not an optimistic success`() {
        assertFalse(parseTorrmediaResult("").ok)
        assertFalse(parseTorrmediaResult("OK").ok)
        assertFalse(parseTorrmediaResult("{}").ok)
        assertTrue(parseTorrmediaResult("""{"ok":true}""").ok)
        assertFalse(parseTorrmediaResult("""{"ok":false,"error":"boom"}""").ok)
        assertEquals("boom", parseTorrmediaResult("""{"ok":false,"error":"boom"}""").error)
    }


    private val torrentsBody = """
        {"ok":true,"qbit_available":true,"torrents":[
          {"hash":"AE7D01","name":"Release","state":"stoppedUP","progress":1.0,
           "category":"Shows","save_path":"/var/media/library/shows","content_path":"/x",
           "size":31069126553,"amount_left":0,"force_start":false,
           "item_path":"shows/Release","completed":31069126553,
           "magnet_uri":"magnet:?xt=urn:btih:AE7D01&tr=http://tracker/announce",
           "dlspeed":0,"upspeed":0,"eta":8640000,"ratio":1.5,
           "num_seeds":1,"num_complete":2,"num_leechs":0,"num_incomplete":0,
           "added_on":1700000000,"completion_on":1700000001}]}
    """.trimIndent()

    @Test
    fun `torrent list maps the contract fields`() {
        val list = parseTorrentList(torrentsBody)
        assertEquals(1, list.size)
        val t = list[0]
        assertEquals("AE7D01", t.hash)
        assertEquals("Release", t.name)
        assertEquals("stoppedUP", t.state)
        assertEquals("Shows", t.category)
        assertEquals(31069126553L, t.size)
        assertEquals(0L, t.amountLeft)
        assertFalse(t.forceStart)
        assertEquals("shows/Release", t.itemPath)
    }

    @Test
    fun `header stats and magnet come straight from the contract`() {
        val t = parseTorrentList(torrentsBody)[0]
        assertEquals(31069126553L, t.completed)
        assertTrue(t.magnetUri.startsWith("magnet:?"))
    }

    @Test
    fun `torrents error is never an empty list`() {
        assertThrows { parseTorrentList("""{"ok":false,"error":"qbit refused"}""") }
        assertThrows { parseTorrentList("""{"ok":true,"qbit_available":false,"torrents":[]}""") }
        assertThrows { parseTorrentList("""{"ok":true,"torrents":[]}""") }
        assertThrows { parseTorrentList("<html>login</html>") }
    }

    @Test
    fun `ok false carries the server text`() {
        val e = runCatching { parseTorrentList("""{"ok":false,"error":"qbit refused"}""") }.exceptionOrNull()
        assertTrue(e is TorrmediaApi.TorrmediaException)
        assertTrue(e?.message?.contains("qbit refused") == true)
    }

    @Test
    fun `torrent detail maps files with qbit ids`() {
        val detail = parseTorrentDetail(
            """
            {"ok":true,"hash":"AE7D01","name":"Release","state":"downloading","progress":0.5,
             "category":"Shows","item_path":"shows/Release","force_start":true,
             "files":[{"id":0,"name":"release/e01.mkv","path":"shows/Release/e01.mkv",
                       "size":123,"priority":1,"progress":1.0,"ready":true},
                      {"id":4,"name":"release/e02.mkv","path":"","size":456,
                       "priority":0,"progress":0.0,"ready":false}]}
            """.trimIndent()
        )
        assertEquals("AE7D01", detail.torrent.hash)
        assertEquals("shows/Release", detail.torrent.itemPath)
        assertTrue(detail.torrent.forceStart)
        assertEquals(2, detail.files.size)
        assertEquals(0, detail.files[0].index)
        assertEquals(4, detail.files[1].index)
        assertEquals("shows/Release/e01.mkv", detail.files[0].path)
        assertEquals(true, detail.files[0].ready)
        assertEquals(false, detail.files[1].ready)
        assertEquals("", detail.files[1].path)
        assertEquals(0, detail.files[1].priority)
    }

    @Test
    fun `torrent detail without files is not an error`() {
        val detail = parseTorrentDetail("""{"ok":true,"hash":"AE7D01","name":"Release"}""")
        assertEquals("AE7D01", detail.torrent.hash)
        assertTrue(detail.files.isEmpty())
    }

    @Test
    fun `categories are sorted names`() {
        val cats = parseCategories(
            """
            {"ok":true,"categories":[{"name":"Shows","save_path":"/var/media/library/shows"},
                                     {"name":"Movies","save_path":"/var/media/library/movies"}]}
            """.trimIndent()
        )
        assertEquals(listOf("Movies", "Shows"), cats)
    }

    @Test
    fun `categories error is not an empty list`() {
        assertThrows { parseCategories("""{"ok":false,"error":"unauthorized"}""") }
        assertThrows { parseCategories("Unauthorized") }
        assertEquals(emptyList<String>(), parseCategories("""{"ok":true,"categories":[]}"""))
    }

    @Test
    fun `only magnet links are accepted for add`() {
        assertTrue(isMagnetLink("magnet:?xt=urn:btih:ae7d"))
        assertTrue(isMagnetLink("  MAGNET:?xt=urn:btih:ae7d"))
        assertFalse(isMagnetLink("https://tracker.example/torrent.torrent"))
        assertFalse(isMagnetLink("content://downloads/1"))
        assertFalse(isMagnetLink(""))
    }

    private fun assertThrows(block: () -> Unit) {
        val e = runCatching { block() }.exceptionOrNull()
        assertTrue("expected an exception", e != null)
    }

}
