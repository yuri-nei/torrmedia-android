package ru.yourok.torrserve.server.api

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.jsoup.Connection
import org.jsoup.Jsoup
import ru.yourok.torrserve.R
import ru.yourok.torrserve.app.App
import ru.yourok.torrserve.atv.Utils.isBrokenTCL
import ru.yourok.torrserve.server.models.torrent.Torrent
import ru.yourok.torrserve.settings.Settings
import ru.yourok.torrserve.utils.TlsSocketFactory
import java.util.Locale

data class QbitTorrent(
    val hash: String = "",
    val name: String = "",
    val progress: Double = 0.0,
    val state: String = "",
    val category: String = "",
    val size: Long = 0,
    @SerializedName("save_path") val savePath: String = "",
    @SerializedName("content_path") val contentPath: String = "",
    @SerializedName("magnet_uri") val magnetUri: String = "",
    @SerializedName("added_on") val addedOn: Long = 0,
    val downloaded: Long = 0,
    val completed: Long = 0,
    @SerializedName("amount_left") val amountLeft: Long = 0,
    val dlspeed: Long = 0,
    val upspeed: Long = 0,
    val eta: Long = 0,
    val ratio: Double = 0.0,
    @SerializedName("num_seeds") val numSeeds: Int = 0,
    @SerializedName("num_complete") val numComplete: Int = 0,
    @SerializedName("num_leechs") val numLeechs: Int = 0,
    @SerializedName("num_incomplete") val numIncomplete: Int = 0,
)

data class QbitFile(
    val index: Int = -1,
    val name: String = "", // path relative to the torrent root
    val progress: Double = 0.0,
    val size: Long = 0,
    val priority: Int = 1,
)

/**
 * Minimal qBittorrent WebUI API v2 client (Qbit fork).
 * Live 5.2.3 behavior: successful login = HTTP 204 + SID cookie, bad credentials = 401,
 * stale/missing SID = 403. The SID cookie is cached and refreshed once on 401/403.
 */
object QbitApi {
    class QbitException(msg: String) : Exception(msg)

    /// Outcome of enableFile(), so the UI can say what actually happened
    enum class EnableResult { STARTED, STALLED, BUSY_CHECKING }

    data class EnableOutcome(val result: EnableResult, val warnings: List<String> = emptyList())

    private const val timeout = 7000 // in ms

    // qBittorrent 5.x names the session cookie QBT_SID_<port>, older builds used plain SID —
    // both the name and the value are taken from the login response, never hardcoded
    @Volatile
    private var sidName: String = ""

    @Volatile
    private var sid: String = ""

    @Volatile
    private var sidKey: String = ""

    private fun authKey(base: String): String =
        "$base|${Settings.getQbitUser()}|${Settings.getQbitPass().hashCode()}"

    @Synchronized
    private fun syncAuthKey(base: String) {
        val key = authKey(base)
        if (sidKey != key) {
            sidKey = key
            sidName = ""
            sid = ""
        }
    }

    private fun base(): String {
        val base = Settings.getQbitUrl()
        if (base.isEmpty())
            throw QbitException("qBittorrent URL is not set")
        return base
    }

    private fun connect(url: String, base: String): Connection {
        // qBittorrent with CSRFProtection rejects requests without a matching Referer/Origin
        val conn = Jsoup.connect(url)
            .ignoreHttpErrors(true)
            .ignoreContentType(true)
            .maxBodySize(0)
            .timeout(timeout)
            .header("Referer", base)
            .header("Origin", base)
        if (!isBrokenTCL)
            conn.sslSocketFactory(TlsSocketFactory())
        return conn
    }

    /// Bad credentials answer 401, so a 2xx here means the login was accepted.
    /// The SID cookie is dug out of the jar first and the raw Set-Cookie header second:
    /// qBit 5.x marks it HttpOnly/SameSite and it does not always reach jsoup's jar.
    @Synchronized
    private fun login(base: String): String {
        val resp = connect("$base/api/v2/auth/login", base)
            .data("username", Settings.getQbitUser())
            .data("password", Settings.getQbitPass())
            .method(Connection.Method.POST)
            .execute()
        if (resp.statusCode() == 401 || resp.statusCode() == 403)
            throw QbitException("login: wrong username/password (HTTP ${resp.statusCode()})")
        if (resp.statusCode() !in 200..299 || resp.body().startsWith("Fails"))
            throw QbitException("login: HTTP ${resp.statusCode()} ${resp.body().take(40)}")
        var name = ""
        var value = ""
        resp.cookies().entries.firstOrNull { it.key.contains("SID", true) }?.let {
            name = it.key
            value = it.value
        }
        if (value.isEmpty()) {
            // qBit marks the cookie HttpOnly/SameSite and jsoup's jar may miss it — read the raw header
            resp.headers("Set-Cookie").firstOrNull { it.substringBefore('=').contains("SID", true) }?.let { raw ->
                name = raw.substringBefore('=').trim()
                value = raw.substringAfter('=').substringBefore(';').trim()
            }
        }
        // an empty session is not fatal: the platform cookie handler may carry it,
        // and execAuthed still verifies with the real request
        sidName = name
        sid = value
        sidKey = authKey(base)
        return value
    }

    private fun exec(build: (String) -> Connection, s: String): Connection.Response {
        val conn = build(s)
        if (s.isNotEmpty() && sidName.isNotEmpty())
            conn.cookie(sidName, s)
        return conn.execute()
    }

    /// Runs a request with the cached SID, relogins once if the session is stale
    private fun execAuthed(base: String, build: (String) -> Connection): Connection.Response {
        syncAuthKey(base)
        var s = sid
        if (s.isEmpty())
            s = login(base)
        var resp = exec(build, s)
        if (resp.statusCode() == 401 || resp.statusCode() == 403) {
            sid = ""
            s = login(base)
            resp = exec(build, s)
            if (resp.statusCode() == 401 || resp.statusCode() == 403)
                throw QbitException("auth failed: HTTP ${resp.statusCode()} (check username/password)")
        }
        return resp
    }

    fun loginProbe(): String {
        val base = base()
        val resp = connect("$base/api/v2/auth/login", base)
            .data("username", Settings.getQbitUser())
            .data("password", Settings.getQbitPass())
            .method(Connection.Method.POST)
            .execute()
        val cookieName = resp.cookies().keys.firstOrNull { it.contains("SID", true) }
            ?: resp.headers("Set-Cookie").firstOrNull { it.substringBefore('=').contains("SID", true) }
                ?.substringBefore('=')?.trim() ?: "-"
        val credsSet = Settings.getQbitUser().isNotEmpty() && Settings.getQbitPass().isNotEmpty()
        return "HTTP ${resp.statusCode()}, cookie=[$cookieName], body=\"${resp.body().take(16)}\", " +
                "credentials=${if (credsSet) "set" else "empty"}"
    }

    /// Throws QbitException/network exceptions on failure. Call off the main thread.
    fun addTorrent(link: String, category: String) {
        val base = base()
        val resp = execAuthed(base) { _ ->
            val conn = connect("$base/api/v2/torrents/add", base)
                .data("urls", link)
                .method(Connection.Method.POST)
            if (category.isNotEmpty()) {
                conn.data("category", category)
                // The VPS default is manual torrent management. Without this flag qBit
                // records "Shows" but keeps save_path at the library root.
                conn.data("autoTMM", "true")
            }
            conn
        }
        if (resp.statusCode() !in 200..299 || resp.body().startsWith("Fails"))
            throw QbitException("add: HTTP ${resp.statusCode()} ${resp.body().take(80)}")
    }

    fun getCategories(): List<String> {
        val base = base()
        val resp = execAuthed(base) { _ ->
            connect("$base/api/v2/torrents/categories", base)
                .method(Connection.Method.GET)
        }
        if (resp.statusCode() !in 200..299)
            throw QbitException("categories: HTTP ${resp.statusCode()}")
        return Gson().fromJson(resp.body(), JsonObject::class.java)?.keySet()?.sorted() ?: emptyList()
    }

    /// All torrents known to qBittorrent
    fun listTorrents(): List<QbitTorrent> {
        val base = base()
        val resp = execAuthed(base) { _ ->
            connect("$base/api/v2/torrents/info", base)
                .method(Connection.Method.GET)
        }
        if (resp.statusCode() !in 200..299)
            throw QbitException("list: HTTP ${resp.statusCode()}")
        return Gson().fromJson(resp.body(), object : TypeToken<List<QbitTorrent>>() {}.type)
    }

    /// Files of one torrent (per-file progress included)
    fun getFiles(hash: String): List<QbitFile> {
        val base = base()
        val resp = execAuthed(base) { _ ->
            connect("$base/api/v2/torrents/files", base)
                .data("hash", hash)
                .method(Connection.Method.GET)
        }
        if (resp.statusCode() !in 200..299)
            throw QbitException("files: HTTP ${resp.statusCode()}")
        return Gson().fromJson(resp.body(), object : TypeToken<List<QbitFile>>() {}.type)
    }


    /// qBittorrent 5.x renamed pause/resume to stop/start; legacy names stay as a fallback
    fun pauseTorrent(hash: String) = switchTorrent(hash, "stop", "pause")

    fun resumeTorrent(hash: String) = switchTorrent(hash, "start", "resume")

    private fun switchTorrent(hash: String, action: String, legacy: String) {
        val base = base()
        var resp = execAuthed(base) { _ ->
            connect("$base/api/v2/torrents/$action", base)
                .data("hashes", hash)
                .method(Connection.Method.POST)
        }
        if (resp.statusCode() == 404 || resp.statusCode() == 405)
            resp = execAuthed(base) { _ ->
                connect("$base/api/v2/torrents/$legacy", base)
                    .data("hashes", hash)
                    .method(Connection.Method.POST)
            }
        if (resp.statusCode() !in 200..299)
            throw QbitException("$action: HTTP ${resp.statusCode()}")
    }

    /// A torrent that already finished keeps reporting "nothing left to fetch" after a file is
    /// switched back on: it stops again right after every start. Only a data recheck makes qBit
    /// re-evaluate what is missing.
    private fun isStalled(torrent: QbitTorrent, files: List<QbitFile>): Boolean =
        !qbitIsChecking(torrent.state) && torrent.amountLeft == 0L &&
                files.any { it.priority != 0 && it.progress < 1.0 }

    suspend fun startTorrent(hash: String): Boolean = withContext(Dispatchers.IO) {
        resumeTorrent(hash)
        delay(1500) // qBit needs a moment before its counters can be trusted
        val torrent = listTorrents().find { it.hash == hash } ?: return@withContext false
        isStalled(torrent, getFiles(hash))
    }

    /// Switches a skipped file back on through torrmedia. DELETE and enable share one
    /// per-torrent server lock, so Android never races a recheck or toggles priorities.
    suspend fun enableFile(hash: String, fileIndex: Int): EnableOutcome = withContext(Dispatchers.IO) {
        try {
            EnableOutcome(EnableResult.STARTED, TorrmediaApi.enableFile(hash, fileIndex))
        } catch (e: TorrmediaApi.TorrmediaException) {
            if (e.statusCode == 409)
                EnableOutcome(EnableResult.BUSY_CHECKING)
            else
                throw e
        }
    }

    /// Force start ignores queueing and seeding limits — the limits are exactly what puts a
    /// "finished" torrent straight back to stopped, leaving a re-enabled file unfetched
    fun setForceStart(hash: String, value: Boolean) {
        val base = base()
        val resp = execAuthed(base) { _ ->
            connect("$base/api/v2/torrents/setForceStart", base)
                .data("hashes", hash)
                .data("value", value.toString())
                .method(Connection.Method.POST)
        }
        if (resp.statusCode() !in 200..299)
            throw QbitException("setForceStart: HTTP ${resp.statusCode()}")
    }

    /// Removes the torrent and (optionally) its files on disk
    fun deleteTorrent(hash: String, deleteFiles: Boolean = true) {
        val base = base()
        val resp = execAuthed(base) { _ ->
            connect("$base/api/v2/torrents/delete", base)
                .data("hashes", hash)
                .data("deleteFiles", deleteFiles.toString())
                .method(Connection.Method.POST)
        }
        if (resp.statusCode() !in 200..299)
            throw QbitException("delete: HTTP ${resp.statusCode()}")
    }

}

/// Human label for a qBittorrent torrent state; empty for a plain download.
/// Rehash/recheck is a distinct state family in qBit and must not read as "downloading".
fun qbitIsChecking(state: String): Boolean = state.startsWith("checking", true)

fun qbitIsPaused(state: String): Boolean =
    state.startsWith("paused", true) || state.startsWith("stopped", true)

fun qbitStateLabel(state: String): String = when {
    state.startsWith("checking", true) -> App.context.getString(R.string.qbit_state_checking)
    state.equals("moving", true) -> App.context.getString(R.string.qbit_state_moving)
    state.startsWith("paused", true) || state.startsWith("stopped", true) ->
        App.context.getString(R.string.qbit_state_paused)
    state.equals("error", true) || state.equals("missingFiles", true) ->
        App.context.getString(R.string.qbit_state_error)
    else -> ""
}

/// Detailed torrent header status. Completion and transfer activity remain
/// visible together ("ready · seeding stopped", rather than only "paused").
fun qbitHeaderStatusLabel(state: String): String {
    val normalized = state.lowercase(Locale.ROOT)
    val res = when {
        normalized == "stoppedup" || normalized == "pausedup" ->
            R.string.qbit_status_ready_stopped
        normalized == "uploading" || normalized == "forcedup" ->
            R.string.qbit_status_ready_seeding
        normalized == "stalledup" ->
            R.string.qbit_status_ready_waiting_peers
        normalized == "queuedup" ->
            R.string.qbit_status_ready_queued
        normalized == "stoppeddl" || normalized == "pauseddl" ->
            R.string.qbit_status_download_paused
        normalized == "downloading" || normalized == "forceddl" ->
            R.string.qbit_status_downloading
        normalized == "stalleddl" ->
            R.string.qbit_status_download_waiting_sources
        normalized == "queueddl" ->
            R.string.qbit_status_download_queued
        normalized == "metadl" ->
            R.string.qbit_status_fetching_metadata
        normalized == "allocating" ->
            R.string.qbit_status_allocating
        normalized.startsWith("checking") ->
            R.string.qbit_status_checking
        normalized == "moving" ->
            R.string.qbit_status_moving
        normalized == "missingfiles" ->
            R.string.qbit_status_files_missing
        normalized == "error" ->
            R.string.qbit_status_error
        else ->
            R.string.qbit_status_unknown
    }
    return App.context.getString(res)
}

/// Adapts a qBittorrent entry to the UI list model used across the app
fun QbitTorrent.toUiTorrent(): Torrent = Torrent(
    title = name,
    poster = null,
    category = category,
    data = null,
    timestamp = addedOn,
    name = name,
    hash = hash,
    stat = 0,
    stat_string = state,
    loaded_size = (size * progress).toLong(),
    torrent_size = size,
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
    src = "qbit",
    qbitHash = hash,
    qbitMagnet = magnetUri,
)
