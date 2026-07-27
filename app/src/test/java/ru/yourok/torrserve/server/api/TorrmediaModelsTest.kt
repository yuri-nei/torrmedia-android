package ru.yourok.torrserve.server.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TorrmediaModelsTest {

    private val listBody = """
        {"items":[
          {"id":"p:c2hvd3MvUmVsZWFzZQ","title":"Игра престолов.S07","status":"seeding",
           "progress":1.0,"size":52430118982,"downloaded":52430118982,
           "files_total":7,"files_watched":3,"files_ready":5,"category":"Shows","added_at":1785000000,
           "transfer":{"down":0,"up":0,"eta":null,"ratio":0.0055,"seeds":1,"peers":0},
           "actions":["stop","recheck","remove","remove_with_data"]}
        ],"degraded":null}
    """.trimIndent()

    @Test
    fun `list snapshot maps the contract fields`() {
        val snapshot = parseItems(listBody)
        assertNull(snapshot.degraded)
        assertEquals(1, snapshot.items.size)
        val item = snapshot.items[0]
        assertEquals("p:c2hvd3MvUmVsZWFzZQ", item.id)
        assertEquals("Игра престолов.S07", item.title)
        assertEquals(ItemStatus.SEEDING, item.status)
        assertEquals(52430118982L, item.size)
        assertEquals(52430118982L, item.downloaded)
        assertEquals(7, item.filesTotal)
        assertEquals(5, item.filesReady)
        assertEquals("Shows", item.category)
        assertEquals(1785000000L, item.addedAt)
        assertEquals(1, item.transfer?.seeds)
        assertNull(item.transfer?.eta)
        assertEquals(
            setOf(ItemAction.STOP, ItemAction.RECHECK, ItemAction.REMOVE, ItemAction.REMOVE_WITH_DATA),
            item.actions
        )
        assertNull(item.files)
        assertNull(item.magnet)
    }

    @Test
    fun `detail snapshot carries magnet and files`() {
        val item = parseItem(
            """
            {"id":"p:abc","title":"Release","status":"downloading","progress":0.5,
             "size":100,"downloaded":50,"files_total":2,"files_watched":3,"files_ready":1,
             "category":null,"added_at":1785000000,"magnet":"magnet:?xt=urn:btih:ae7d&tr=http://tr/a",
             "transfer":{"down":1024,"up":0,"eta":600,"ratio":0.0,"seeds":3,"peers":1},
             "files":[
               {"id":"ZTAxLm1rdg","name":"e01.mkv","path":"Release/e01.mkv","size":3221225472,
                "progress":1.0,"status":"ready","watch":"watched","position":1610612736,
                "actions":["play","delete","set_viewed","clear_viewed"]},
               {"id":"ZTAyLm1rdg","name":"e02.mkv","path":"Release/e02.mkv","size":10,
                "progress":0.0,"status":"skipped","watch":null,"position":0,
                "actions":["enable"]}
             ],
             "actions":["stop"]}
            """.trimIndent()
        )
        assertEquals(ItemStatus.DOWNLOADING, item.status)
        assertTrue(item.magnet?.startsWith("magnet:?") == true)
        assertNull(item.category)
        assertEquals(600L, item.transfer?.eta)
        val files = item.files ?: emptyList()
        assertEquals(2, files.size)
        assertEquals("ZTAxLm1rdg", files[0].id)
        assertEquals("Release/e01.mkv", files[0].path)
        assertEquals(FileStatus.READY, files[0].status)
        assertEquals(WatchState.WATCHED, files[0].watch)
        assertEquals(1610612736L, files[0].position)
        assertEquals(WatchState.NONE, files[1].watch)
        assertEquals(FileStatus.SKIPPED, files[1].status)
        assertEquals(setOf(FileAction.ENABLE), files[1].actions)
    }

    @Test
    fun `unknown status does not break parsing`() {
        val item = parseItem(
            """{"id":"p:abc","title":"X","status":"teleporting","progress":0.0,
                "files":[{"id":"f","name":"a.mkv","status":"materialising","actions":["fly","play"]}],
                "actions":["stop","levitate"]}"""
        )
        assertEquals(ItemStatus.UNKNOWN, item.status)
        assertEquals(FileStatus.UNKNOWN, item.files?.get(0)?.status)
        assertEquals(setOf(ItemAction.STOP), item.actions)
        assertEquals(setOf(FileAction.PLAY), item.files?.get(0)?.actions)
    }

    @Test
    fun `missing fields fall back without throwing`() {
        val snapshot = parseItems("""{"items":[{"id":"p:abc"}]}""")
        val item = snapshot.items[0]
        assertEquals("", item.title)
        assertEquals(ItemStatus.UNKNOWN, item.status)
        assertEquals(0L, item.size)
        assertNull(item.transfer)
        assertTrue(item.actions.isEmpty())
        assertTrue(parseItems("{}").items.isEmpty())
    }

    @Test
    fun `non json body is an error, not an empty snapshot`() {
        val e = runCatching { parseItems("<html>login</html>") }.exceptionOrNull()
        assertTrue(e is TorrmediaException)
    }


    @Test
    fun `error body becomes a typed exception`() {
        val e = parseError(409, """{"error":"file is being streamed right now","code":"streaming"}""")
        assertEquals(TorrmediaErrorCode.STREAMING, e.code)
        assertEquals("streaming", e.rawCode)
        assertEquals(409, e.httpCode)
        assertTrue(e.message?.contains("file is being streamed right now") == true)
    }

    @Test
    fun `every contract code is recognised`() {
        val codes = mapOf(
            "not_found" to TorrmediaErrorCode.NOT_FOUND,
            "ambiguous" to TorrmediaErrorCode.AMBIGUOUS,
            "busy" to TorrmediaErrorCode.BUSY,
            "streaming" to TorrmediaErrorCode.STREAMING,
            "refused" to TorrmediaErrorCode.REFUSED,
            "insufficient_storage" to TorrmediaErrorCode.INSUFFICIENT_STORAGE,
            "qbittorrent_unavailable" to TorrmediaErrorCode.QBITTORRENT_UNAVAILABLE,
            "deadline" to TorrmediaErrorCode.DEADLINE,
            "unauthorized" to TorrmediaErrorCode.UNAUTHORIZED,
        )
        for ((wire, expected) in codes)
            assertEquals(wire, expected, parseError(400, """{"error":"x","code":"$wire"}""").code)
    }

    @Test
    fun `unknown or absent code stays unknown but keeps the text`() {
        val unknown = parseError(500, """{"error":"boom","code":"kaboom"}""")
        assertEquals(TorrmediaErrorCode.UNKNOWN, unknown.code)
        assertEquals("kaboom", unknown.rawCode)
        val html = parseError(502, "<html>Bad Gateway</html>")
        assertEquals(TorrmediaErrorCode.UNKNOWN, html.code)
        assertTrue(html.message?.contains("502") == true)
    }


    @Test
    fun `operation maps state, phase and warnings`() {
        val op = parseOperation(
            """
            {"id":"op_4f2b91","kind":"file.delete","item":"p:abc","file":"ZTAxLm1rdg",
             "state":"running","phase":"trimming","started_at":1785000000,
             "finished_at":null,"error":null,"warnings":[],"result":null}
            """.trimIndent()
        )
        assertEquals("op_4f2b91", op.id)
        assertEquals(OperationState.RUNNING, op.state)
        assertEquals("trimming", op.phase)
        assertNull(op.finishedAt)
        assertNull(op.error)
        assertTrue(op.warnings.isEmpty())
    }

    @Test
    fun `warnings survive a successful operation`() {
        val op = parseOperation(
            """{"id":"op_1","state":"done","warnings":["данные освобождены, торрент не перезапущен",""]}"""
        )
        assertEquals(OperationState.DONE, op.state)
        assertEquals(listOf("данные освобождены, торрент не перезапущен"), op.warnings)
    }

    @Test
    fun `unknown operation state is not a success`() {
        val op = parseOperation("""{"id":"op_1","state":"melting"}""")
        assertEquals(OperationState.UNKNOWN, op.state)
    }

    // --- health / categories --------------------------------------------------

    @Test
    fun `health carries the contract version`() {
        val health = parseHealth(
            """{"version":"2026-07-28.1","contract":2,
                "checks":{"root":"ok","viewed":"ok","qbittorrent":"ok"}}"""
        )
        assertEquals(2, health.contract)
        assertEquals("2026-07-28.1", health.version)
        assertEquals("ok", health.checks["qbittorrent"])
    }

    @Test
    fun `health without a contract version is refused`() {
        val e = runCatching { parseHealth("""{"version":"x"}""") }.exceptionOrNull()
        assertEquals(TorrmediaErrorCode.CONTRACT_MISMATCH, (e as? TorrmediaException)?.code)
    }

    @Test
    fun `categories are sorted names`() {
        assertEquals(
            listOf("Movies", "Shows"),
            parseCategories("""{"categories":[{"name":"Shows"},{"name":"Movies"}]}""")
        )
        assertEquals(emptyList<String>(), parseCategories("""{"categories":[]}"""))
    }


    @Test
    fun `action missing from actions is never offered`() {
        val downloading = setOf(ItemAction.STOP, ItemAction.RECHECK)
        assertEquals(ItemAction.STOP, primaryTransferAction(downloading))
        assertEquals(emptyList<ItemAction>(), removalActions(downloading))

        val checking = emptySet<ItemAction>()
        assertNull(primaryTransferAction(checking))
        assertEquals(emptyList<ItemAction>(), removalActions(checking))

        val stopped = setOf(ItemAction.START, ItemAction.REMOVE, ItemAction.REMOVE_WITH_DATA)
        assertEquals(ItemAction.START, primaryTransferAction(stopped))
        assertEquals(listOf(ItemAction.REMOVE, ItemAction.REMOVE_WITH_DATA), removalActions(stopped))

        assertEquals(emptyList<FileAction>(), fileMenuActions(setOf(FileAction.PLAY)))
        assertEquals(
            listOf(FileAction.SET_VIEWED, FileAction.DELETE),
            fileMenuActions(setOf(FileAction.PLAY, FileAction.DELETE, FileAction.SET_VIEWED))
        )
    }

    @Test
    fun `unknown status offers nothing on its own`() {
        val item = parseItem("""{"id":"p:abc","status":"unknown","actions":[]}""")
        assertEquals(ItemStatus.UNKNOWN, item.status)
        assertNull(primaryTransferAction(item.actions))
        assertTrue(removalActions(item.actions).isEmpty())
    }

    @Test
    fun `only magnet links are accepted for add`() {
        assertTrue(isMagnetLink("magnet:?xt=urn:btih:ae7d"))
        assertTrue(isMagnetLink("  MAGNET:?xt=urn:btih:ae7d"))
        assertFalse(isMagnetLink("https://tracker.example/torrent.torrent"))
        assertFalse(isMagnetLink(""))
    }

    @Test
    fun `partial file status is understood`() {
        val json = """{"id":"a","title":"R","status":"stopped","progress":0.5,"size":10,
                       "downloaded":5,"files_total":1,"files_ready":0,"files_watched":0,
                       "category":null,"magnet":null,"added_at":0,
                       "transfer":{"down":0,"up":0,"eta":null,"ratio":0,"seeds":0,"peers":0},
                       "actions":[],
                       "files":[{"id":"f","name":"e01.mkv","path":"e01.mkv","size":10,
                                 "progress":0.5,"status":"partial","watch":null,
                                 "position":0,"actions":[]}]}"""
        val item = parseItem(json)
        assertEquals(FileStatus.PARTIAL, item.files!![0].status)
    }

    @Test
    fun `unknown file status does not break parsing`() {
        val json = """{"id":"a","title":"R","status":"stopped","progress":1.0,"size":1,
                       "downloaded":1,"files_total":1,"files_ready":1,"files_watched":0,
                       "category":null,"magnet":null,"added_at":0,
                       "transfer":{"down":0,"up":0,"eta":null,"ratio":0,"seeds":0,"peers":0},
                       "actions":[],
                       "files":[{"id":"f","name":"e.mkv","path":"e.mkv","size":1,
                                 "progress":1.0,"status":"something-new","watch":null,
                                 "position":0,"actions":["play","warp_drive"]}]}"""
        val file = parseItem(json).files!![0]
        assertEquals(FileStatus.UNKNOWN, file.status)
        assertEquals(setOf(FileAction.PLAY), file.actions)
    }

    @Test
    fun `cancelled operation state is understood`() {
        val op = parseOperation("""{"id":"op_1","kind":"file.delete","item":"i","file":null,
                                    "state":"cancelled","phase":null,"started_at":0,
                                    "finished_at":1,"error":null,"warnings":[],"result":null}""")
        assertEquals(OperationState.CANCELLED, op.state)
        assertEquals(null, op.error)
    }
}
