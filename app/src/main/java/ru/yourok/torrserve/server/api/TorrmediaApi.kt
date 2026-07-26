package ru.yourok.torrserve.server.api

import android.net.Uri
import com.google.gson.Gson
import org.jsoup.Connection
import org.jsoup.Jsoup
import ru.yourok.torrserve.atv.Utils.isBrokenTCL
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
    val watch: String = "",
    val pos: Long = 0,
) {
    val watchState: TorrmediaWatch
        get() = watchFrom(watch)
}

data class TorrmediaItem(
    val title: String = "",
    val path: String = "",
    val files: List<TorrmediaFile> = emptyList(),
)

private data class TorrmediaListResp(val items: List<TorrmediaItem> = emptyList())

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

/**
 * Client for the torrmedia service (docs/09) — the VPS-side gallery of downloaded files.
 * Contract: GET /list, GET /file?path (Range), DELETE /file?path,
 * DELETE /file?hash&id and GET /health. VPN-only.
 * All the qBittorrent single-file-deletion workarounds live on the server side, not here.
 */
object TorrmediaApi {
    class TorrmediaException(msg: String, val statusCode: Int = 0) : Exception(msg)

    private const val timeout = 7000 // in ms
    private const val operationTimeout = 11 * 60 * 1000

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
        if (!isBrokenTCL)
            conn.sslSocketFactory(TlsSocketFactory())
        return conn
    }

    fun list(): List<TorrmediaItem> {
        val resp = connect("${base()}/list")
            .method(Connection.Method.GET)
            .execute()
        if (resp.statusCode() !in 200..299)
            throw TorrmediaException("list: HTTP ${resp.statusCode()}")
        return Gson().fromJson(resp.body(), TorrmediaListResp::class.java)?.items ?: emptyList()
    }

    /// External-player URL: a real filename in the path gives every episode its own resume key.
    fun playUrl(path: String): String =
        "${base()}/file/${Uri.encode(File(path).name)}?path=${Uri.encode(path)}"

    /// Short API form stays separate: DELETE is intentionally not sent to the decorative alias.
    private fun fileUrl(path: String): String = "${base()}/file?path=${Uri.encode(path)}"

    /// Deletes one file (episode). The service itself untangles qBittorrent re-download logic.
    /// 404 counts as success: the file is gone either way.
    fun deleteFile(path: String) {
        val resp = connect(fileUrl(path), operationTimeout)
            .method(Connection.Method.DELETE)
            .execute()
        if (resp.statusCode() !in 200..299 && resp.statusCode() != 404)
            throw TorrmediaException("delete: HTTP ${resp.statusCode()} ${resp.body().take(120)}", resp.statusCode())
    }

    /// Marks an exact qBittorrent file unwanted and trims any partial/full data.
    /// Hash+ID works even before qBit creates the path and does not depend on category layout.
    fun deleteTorrentFile(hash: String, fileIndex: Int) {
        val url = "${base()}/file?hash=${Uri.encode(hash)}&id=$fileIndex"
        val resp = connect(url, operationTimeout)
            .method(Connection.Method.DELETE)
            .execute()
        if (resp.statusCode() !in 200..299)
            throw TorrmediaException("delete: HTTP ${resp.statusCode()} ${resp.body().take(120)}", resp.statusCode())
    }

    /// Re-enables one skipped qBittorrent file through the same serialized
    /// server-side workflow that owns DELETE. No qBit priority toggles on Android.
    fun enableFile(hash: String, fileIndex: Int) {
        val url = "${base()}/file/enable?hash=${Uri.encode(hash)}&id=$fileIndex"
        val resp = connect(url, operationTimeout)
            .method(Connection.Method.POST)
            .execute()
        if (resp.statusCode() !in 200..299)
            throw TorrmediaException("enable: HTTP ${resp.statusCode()} ${resp.body().take(120)}", resp.statusCode())
    }

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
        if (resp.statusCode() == 404)
            return
        if (resp.statusCode() !in 200..299)
            throw TorrmediaException("set viewed: HTTP ${resp.statusCode()} ${resp.body().take(120)}", resp.statusCode())
    }

    fun remViewed(path: String) {
        val resp = connect("${base()}/viewed?path=${Uri.encode(path)}")
            .method(Connection.Method.DELETE)
            .execute()
        if (resp.statusCode() == 404)
            return
        if (resp.statusCode() !in 200..299)
            throw TorrmediaException("remove viewed: HTTP ${resp.statusCode()} ${resp.body().take(120)}", resp.statusCode())
    }

    fun remViewedItem(item: String) {
        val resp = connect("${base()}/viewed?item=${Uri.encode(item)}")
            .method(Connection.Method.DELETE)
            .execute()
        if (resp.statusCode() == 404)
            return
        if (resp.statusCode() !in 200..299)
            throw TorrmediaException("remove viewed item: HTTP ${resp.statusCode()} ${resp.body().take(120)}", resp.statusCode())
    }

    fun health(): Boolean {
        return try {
            connect("${base()}/health").method(Connection.Method.GET).execute().statusCode() in 200..299
        } catch (e: Exception) {
            false
        }
    }
}

/// True when a qBittorrent torrent holds exactly this shelf item.
/// Primary: content_path ends with the item path; fallback: same folder name
/// (covers a torrmedia root that does not line up with the qBit save path).
fun matchesShelfItem(contentPath: String, itemPath: String): Boolean {
    val cp = contentPath.trimEnd('/')
    val ip = itemPath.trim('/')
    if (ip.isEmpty()) return false
    if (cp.endsWith("/$ip")) return true
    return cp.substringAfterLast('/') == ip.substringAfterLast('/')
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
)
