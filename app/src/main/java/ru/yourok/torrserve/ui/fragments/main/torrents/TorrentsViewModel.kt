package ru.yourok.torrserve.ui.fragments.main.torrents

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.yourok.torrserve.server.api.Api
import ru.yourok.torrserve.server.api.Item
import ru.yourok.torrserve.server.api.ItemsSnapshot
import ru.yourok.torrserve.server.api.MediaState
import ru.yourok.torrserve.server.api.TorrmediaStore
import ru.yourok.torrserve.server.models.torrent.Torrent

/**
 * The list: TorrServer rows + torrmedia rows.
 *
 * torrmedia is not polled at all — the server itself sends a ready snapshot over the /events
 * stream on every change. TorrServer has no event stream of its own, so its list is still read
 * by polling; that is the only timer left here.
 */
/// How long the library list stream stays open after leaving the main screen
private const val STREAM_LINGER_MS = 30_000L

class TorrentsViewModel : ViewModel() {

    private val store = TorrmediaStore.items()
    private val local = MutableStateFlow<List<Torrent>>(emptyList())

    /// the library state, separate from the list: the screen must be able to show "the stream
    /// did not come up"
    val mediaState: StateFlow<MediaState<ItemsSnapshot>> get() = store.state

    /// The library is connected right now. On a disconnect the last snapshot stays on screen —
    /// it is still the best one known — but it must not be passed off as current: the rows and
    /// their actions may be stale.
    val mediaConnected: StateFlow<Boolean> get() = store.connected

    /// The item from the list's last snapshot, only while connected: a stale row must not be
    /// passed off as the item's state even for half a second.
    fun liveItem(id: String): Item? {
        if (!store.connected.value)
            return null
        return (store.state.value as? MediaState.Ready)?.data?.items?.firstOrNull { it.id == id }
    }

    var data: MutableLiveData<List<Torrent>>? = null
    private var isUpdate = true

    fun getData(): LiveData<List<Torrent>> {
        if (data == null) {
            data = MutableLiveData()
            start()
        }
        return data!!
    }

    /// The library stream is kept open for a while after leaving the screen: going into an item
    /// and back is a matter of seconds, and reconnecting on return marked the rows "disconnected"
    /// and rebound them mid animation. Gone for long (player) — the stream is closed.
    private var linger: Job? = null

    fun setUpdate(update: Boolean) {
        isUpdate = update
        linger?.cancel()
        linger = null
        if (update)
            store.start(viewModelScope)
        else
            linger = viewModelScope.launch {
                delay(STREAM_LINGER_MS)
                store.stop()
            }
    }

    override fun onCleared() {
        store.stop()
        super.onCleared()
    }

    private fun start() {
        store.start(viewModelScope)
        viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                if (isUpdate) {
                    local.value = try {
                        Api.listTorrent()
                    } catch (e: Exception) {
                        Log.e("QbitFork", "torrserver list failed: ${e.message}")
                        emptyList()
                    }
                }
                delay(1000)
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            combine(local, store.state, store.connected) { locals, media, connected ->
                val remote = (media as? MediaState.Ready)?.data?.items
                    ?.map { it.toUiTorrent() }
                    // No connection to the library: the snapshot stays on screen (it is still
                    // the best one known), but no actions may be offered from it. They are
                    // cleared here, in the only place the list is assembled, so that no consumer
                    // can show a button from stale data.
                    ?.map { if (connected) it else it.copy(torrmediaActions = emptySet()) }
                    ?: emptyList()
                // one list ordered by freshness; items without a date go last
                (locals + remote).sortedByDescending { it.timestamp }
            }.collect { list ->
                withContext(Dispatchers.Main) { data?.value = list }
            }
        }
    }
}

/// A torrmedia item in the shared list model. hash carries the opaque id from the contract:
/// it must not be parsed, compared piece by piece or constructed.
fun Item.toUiTorrent(): Torrent = Torrent(
    title = title,
    poster = null,
    category = category,
    data = null,
    timestamp = addedAt,
    name = title,
    hash = id,
    stat = 0,
    stat_string = "",
    loaded_size = downloaded,
    torrent_size = size,
    preloaded_bytes = 0,
    preload_size = 0,
    download_speed = transfer?.down?.toDouble() ?: 0.0,
    upload_speed = transfer?.up?.toDouble() ?: 0.0,
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
    src = "torrmedia",
    torrmediaFilesReady = filesReady,
    torrmediaFilesTotal = filesTotal,
    torrmediaFilesWatched = filesWatched,
    torrmediaStatus = status,
    torrmediaActions = actions,
    torrmediaMagnet = magnet ?: "",
)
