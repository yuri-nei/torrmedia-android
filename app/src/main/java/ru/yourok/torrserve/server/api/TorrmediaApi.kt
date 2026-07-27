package ru.yourok.torrserve.server.api

import android.net.Uri
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.annotations.SerializedName
import org.jsoup.Connection
import org.jsoup.Jsoup
import ru.yourok.torrserve.atv.Utils.isBrokenTCL
import ru.yourok.torrserve.ui.fragments.qbit.FileOwner
import ru.yourok.torrserve.server.models.torrent.Torrent
import ru.yourok.torrserve.settings.Settings
import ru.yourok.torrserve.utils.TlsSocketFactory
import java.io.File

enum class TorrmediaWatch { NONE, STARTED, WATCHED }

private fun watchFrom(state: String): TorrmediaWatch = when (state) {
    "watched" -> TorrmediaWatch.WATCHED
    "started" -> TorrmediaWatch.STARTED
    else -> TorrmediaWatch.NONE
}

data class TorrmediaFile(
    val name: String = "",
    val path: String = "", // relative to the torrmedia root
    val size: Long = 0,
    val mtime: Long = 0,
    val owner: String = FileOwner.AMBIGUOUS,
    @SerializedName("torrent_hash") val torrentHash: String = "",
    @SerializedName("qbit_file_id") val qbitFileId: Int = -1,
    val watch: String = "",
    val pos: Long = 0,
) {
    val watchState: TorrmediaWatch
        get() = watchFrom(watch)
}

data class TorrmediaItem(
    val title: String = "",
    val path: String = "",
    @SerializedName("torrent_hash") val torrentHash: String = "",
    val files: List<TorrmediaFile> = emptyList(),
)

data class TorrmediaList(
    val items: List<TorrmediaItem> = emptyList(),
    @SerializedName("qbit_available") val qbitAvailable: Boolean = false,
)

data class TorrmediaViewed(
    val path: String = "",
    val item: String = "",
    val state: String = "",
    val pos: Long = 0,
    val size: Long = 0,
    val updated: Long = 0,
) {
    val watch: TorrmediaWatch
        get() = watchFrom(state)
}

private data class TorrmediaViewedResp(val items: List<TorrmediaViewed> = emptyList())

object TorrmediaApi {
    class TorrmediaException(msg: String, val statusCode: Int = 0) : Exception(msg)

    private const val timeout = 7000 // in ms

    private const val serverDeadline = 300_000
    private const val operationTimeout = 2 * serverDeadline + 60_000

    private fun base(): String {
        val base = Settings.getTorrmediaUrl()
        if (base.isEmpty())
            throw TorrmediaException("torrmedia URL is not set")
        return base
    }

    private fun connect(url: String, timeoutMs: Int = timeout): Connection {
        val conn = Jsoup.connect(url)
            .ignoreHttpErrors(true)
            .ignoreContentType(true)
            .maxBodySize(0)
            .timeout(timeoutMs)
        val token = Settings.getTorrmediaToken()
        if (token.isNotEmpty())
            conn.header("Authorization", "Bearer $token")
        if (!isBrokenTCL)
            conn.sslSocketFactory(TlsSocketFactory())
        return conn
    }

    const val CONTRACT = 1

    @Volatile
    private var verifiedBase: String? = null

    private fun ensureContract(base: String) {
        if (verifiedBase == base) return
        val resp = connect("$base/health").method(Connection.Method.GET).execute()
        if (resp.statusCode() !in 200..299)
            throw TorrmediaException("health: HTTP ${resp.statusCode()}", resp.statusCode())
        val contract = runCatching {
            JsonParser.parseString(resp.body()).asJsonObject.get("contract").asInt
        }.getOrElse {
            throw TorrmediaException("torrmedia did not report its contract version", resp.statusCode())
        }
        if (contract != CONTRACT)
            throw TorrmediaException(
                "torrmedia speaks contract $contract, this build expects $CONTRACT — update both",
                resp.statusCode()
            )
        verifiedBase = base
    }

    private fun getBody(op: String, path: String): String {
        val base = base()
        ensureContract(base)
        val resp = connect("$base$path")
            .method(Connection.Method.GET)
            .execute()
        if (resp.statusCode() !in 200..299) {
            val error = parseTorrmediaResult(resp.body()).error
            throw TorrmediaException(
                "$op: HTTP ${resp.statusCode()} ${error.ifEmpty { resp.body().take(120) }}",
                resp.statusCode()
            )
        }
        return resp.body()
    }

    private fun post(op: String, path: String): List<String> {
        val base = base()
        ensureContract(base)
        val resp = connect("$base$path", operationTimeout)
            .method(Connection.Method.POST)
            .execute()
        return checkResult(op, resp)
    }

    fun list(): TorrmediaList {
        val base = base()
        ensureContract(base)
        val resp = connect("$base/list")
            .method(Connection.Method.GET)
            .execute()
        if (resp.statusCode() !in 200..299)
            throw TorrmediaException("list: HTTP ${resp.statusCode()}", resp.statusCode())
        return Gson().fromJson(resp.body(), TorrmediaList::class.java) ?: TorrmediaList()
    }

    private fun checkResult(op: String, resp: Connection.Response, okCodes: Set<Int> = emptySet()): List<String> {
        if (resp.statusCode() in okCodes)
            return emptyList()
        val result = parseTorrmediaResult(resp.body())
        if (resp.statusCode() !in 200..299)
            throw TorrmediaException(
                "$op: HTTP ${resp.statusCode()} ${result.error.ifEmpty { resp.body().take(120) }}",
                resp.statusCode()
            )
        if (!result.ok)
            throw TorrmediaException("$op: ${result.error.ifEmpty { "server reported failure" }}", resp.statusCode())
        return result.warnings
    }

    /// External-player URL: a real filename in the path gives every episode its own resume key.
    fun playUrl(path: String): String =
        "${base()}/file/${Uri.encode(File(path).name)}?path=${Uri.encode(path)}"

    /// Short API form stays separate: DELETE is intentionally not sent to the decorative alias.
    private fun fileUrl(path: String): String = "${base()}/file?path=${Uri.encode(path)}"

    /// Deletes one file (episode). The service itself untangles qBittorrent re-download logic.
    /// 404 counts as success: the file is gone either way. Returns server warnings, if any.
    fun deleteFile(path: String): List<String> {
        val resp = connect(fileUrl(path), operationTimeout)
            .method(Connection.Method.DELETE)
            .execute()
        return checkResult("delete", resp, setOf(404))
    }

    /// Marks an exact qBittorrent file unwanted and trims any partial/full data.
    /// Hash+ID works even before qBit creates the path and does not depend on category layout.
    fun deleteTorrentFile(hash: String, fileIndex: Int): List<String> {
        val url = "${base()}/file?hash=${Uri.encode(hash)}&id=$fileIndex"
        val resp = connect(url, operationTimeout)
            .method(Connection.Method.DELETE)
            .execute()
        return checkResult("delete", resp)
    }

    /// Re-enables one skipped qBittorrent file through the same serialized
    /// server-side workflow that owns DELETE. No qBit priority toggles on Android.
    fun enableFile(hash: String, fileIndex: Int): List<String> {
        val url = "${base()}/file/enable?hash=${Uri.encode(hash)}&id=$fileIndex"
        val resp = connect(url, operationTimeout)
            .method(Connection.Method.POST)
            .execute()
        return checkResult("enable", resp)
    }

    fun torrents(): List<QbitTorrent> = parseTorrentList(getBody("torrents", "/torrents"))

    fun torrent(hash: String): TorrmediaTorrentDetail =
        parseTorrentDetail(getBody("torrent", "/torrent?hash=${Uri.encode(hash)}"))

    fun categories(): List<String> = parseCategories(getBody("categories", "/categories"))

    fun addTorrent(magnet: String, category: String): List<String> {
        var path = "/add?magnet=${Uri.encode(magnet)}"
        if (category.isNotEmpty())
            path += "&category=${Uri.encode(category)}"
        return post("add", path)
    }

    fun startTorrent(hash: String): List<String> = post("start", "/start?hash=${Uri.encode(hash)}")

    fun stopTorrent(hash: String): List<String> = post("stop", "/stop?hash=${Uri.encode(hash)}")

    fun recheckTorrent(hash: String): List<String> = post("recheck", "/recheck?hash=${Uri.encode(hash)}")

    fun removeTorrent(hash: String, deleteFiles: Boolean): List<String> =
        post("remove", "/remove?hash=${Uri.encode(hash)}&delete_files=$deleteFiles")

    fun listViewed(item: String? = null): List<TorrmediaViewed> {
        val suffix = item?.let { "?item=${Uri.encode(it)}" } ?: ""
        val resp = connect("${base()}/viewed$suffix")
            .method(Connection.Method.GET)
            .execute()
        if (resp.statusCode() == 404)
            return emptyList()
        if (resp.statusCode() !in 200..299)
            throw TorrmediaException("viewed: HTTP ${resp.statusCode()}", resp.statusCode())
        return Gson().fromJson(resp.body(), TorrmediaViewedResp::class.java)?.items ?: emptyList()
    }

    fun setViewed(path: String, state: TorrmediaWatch = TorrmediaWatch.WATCHED) {
        if (state == TorrmediaWatch.NONE) {
            remViewed(path)
            return
        }
        val value = if (state == TorrmediaWatch.STARTED) "started" else "watched"
        val resp = connect("${base()}/viewed?path=${Uri.encode(path)}&state=$value")
            .method(Connection.Method.POST)
            .execute()
        checkResult("set viewed", resp, setOf(404))
    }

    fun remViewed(path: String) {
        val resp = connect("${base()}/viewed?path=${Uri.encode(path)}")
            .method(Connection.Method.DELETE)
            .execute()
        checkResult("remove viewed", resp, setOf(404))
    }

    fun remViewedItem(item: String) {
        val resp = connect("${base()}/viewed?item=${Uri.encode(item)}")
            .method(Connection.Method.DELETE)
            .execute()
        checkResult("remove viewed item", resp, setOf(404))
    }

    fun health(): Boolean {
        return try {
            connect("${base()}/health").method(Connection.Method.GET).execute().statusCode() in 200..299
        } catch (e: Exception) {
            false
        }
    }
}

/// Adapts a torrmedia item to the UI list model (hash carries the item path)
fun TorrmediaItem.toUiTorrent(): Torrent = Torrent(
    title = title.ifBlank { path },
    poster = null,
    category = null,
    data = null,
    timestamp = files.maxOfOrNull { it.mtime } ?: 0,
    name = title.ifBlank { path },
    hash = path.ifEmpty { title },
    stat = 0,
    stat_string = "",
    loaded_size = files.sumOf { it.size },
    torrent_size = files.sumOf { it.size },
    preloaded_bytes = 0,
    preload_size = 0,
    download_speed = 0.0,
    upload_speed = 0.0,
    total_peers = 0,
    pending_peers = 0,
    active_peers = 0,
    connected_seeders = 0,
    half_open_peers = 0,
    bytes_written = 0,
    bytes_written_data = 0,
    bytes_read = 0,
    bytes_read_data = 0,
    bytes_read_useful_data = 0,
    chunks_written = 0,
    chunks_read = 0,
    chunks_read_useful = 0,
    chunks_read_wasted = 0,
    pieces_dirtied_good = 0,
    pieces_dirtied_bad = 0,
    duration_seconds = null,
    bit_rate = null,
    file_stats = null,
    src = "media",
    watchedFiles = files.count { it.watchState == TorrmediaWatch.WATCHED },
    startedFiles = files.count { it.watchState == TorrmediaWatch.STARTED },
    mediaFiles = files.size,
    mediaItemPath = path,
    qbitHash = torrentHash,
)
