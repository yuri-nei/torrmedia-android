package ru.yourok.torrserve.server.api

import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import ru.yourok.torrserve.R
import ru.yourok.torrserve.app.App
import ru.yourok.torrserve.server.models.torrent.Torrent
import java.util.Locale

data class QbitTorrent(
    val hash: String = "",
    val name: String = "",
    val progress: Double = 0.0,
    val state: String = "",
    val category: String = "",
    val size: Long = 0,
    @SerializedName("item_path") val itemPath: String = "",
    @SerializedName("force_start") val forceStart: Boolean = false,
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
) {
    val completedBytes: Long
        get() = if (completed > 0) completed else (size * progress).toLong()
}

data class QbitFile(
    @SerializedName("id") val index: Int = -1,
    val name: String = "", // path relative to the torrent root
    val path: String = "",
    val progress: Double = 0.0,
    val size: Long = 0,
    val priority: Int = 1,
    val ready: Boolean? = null,
)

object QbitApi {
    class QbitException(msg: String, val statusCode: Int = 0) : Exception(msg)

    /// Outcome of enableFile(), so the UI can say what actually happened
    enum class EnableResult { STARTED, STALLED, BUSY_CHECKING }

    data class EnableOutcome(val result: EnableResult, val warnings: List<String> = emptyList())

    private inline fun <T> torrmedia(block: () -> T): T = try {
        block()
    } catch (e: TorrmediaApi.TorrmediaException) {
        throw QbitException(e.message ?: "torrmedia error", e.statusCode)
    }

    fun addTorrent(link: String, category: String): List<String> {
        if (!isMagnetLink(link))
            throw QbitException(App.context.getString(R.string.qbit_only_magnet))
        return torrmedia { TorrmediaApi.addTorrent(link.trim(), category) }
    }

    fun getCategories(): List<String> = torrmedia { TorrmediaApi.categories() }

    /// All torrents known to qBittorrent
    fun listTorrents(): List<QbitTorrent> = torrmedia { TorrmediaApi.torrents() }

    /// Files of one torrent (per-file progress included)
    fun getFiles(hash: String): List<QbitFile> = torrmedia { TorrmediaApi.torrent(hash).files }


    fun pauseTorrent(hash: String): List<String> = torrmedia { TorrmediaApi.stopTorrent(hash) }

    fun resumeTorrent(hash: String): List<String> = torrmedia { TorrmediaApi.startTorrent(hash) }

    /// A torrent that already finished keeps reporting "nothing left to fetch" after a file is
    /// switched back on: it stops again right after every start. Only a data recheck makes qBit
    /// re-evaluate what is missing.
    private fun isStalled(torrent: QbitTorrent, files: List<QbitFile>): Boolean =
        !qbitIsChecking(torrent.state) && torrent.amountLeft == 0L &&
                files.any { it.priority != 0 && it.progress < 1.0 }

    suspend fun startTorrent(hash: String): Boolean = withContext(Dispatchers.IO) {
        resumeTorrent(hash)
        delay(1500)
        val torrent = listTorrents().find { normalizeHash(it.hash) == normalizeHash(hash) }
            ?: return@withContext false
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
                throw QbitException(e.message ?: "enable failed", e.statusCode)
        }
    }

    fun deleteTorrent(hash: String, deleteFiles: Boolean = true): List<String> =
        torrmedia { TorrmediaApi.removeTorrent(hash, deleteFiles) }
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
