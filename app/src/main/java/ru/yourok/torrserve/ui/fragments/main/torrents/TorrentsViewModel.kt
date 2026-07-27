package ru.yourok.torrserve.ui.fragments.main.torrents

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
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

class TorrentsViewModel : ViewModel() {

    private val store = TorrmediaStore.items()
    private val local = MutableStateFlow<List<Torrent>>(emptyList())

    val mediaState: StateFlow<MediaState<ItemsSnapshot>> get() = store.state

    var data: MutableLiveData<List<Torrent>>? = null
    private var isUpdate = true

    fun getData(): LiveData<List<Torrent>> {
        if (data == null) {
            data = MutableLiveData()
            start()
        }
        return data!!
    }

    fun setUpdate(update: Boolean) {
        isUpdate = update
        if (update)
            store.start(viewModelScope)
        else
            store.stop()
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
            combine(local, store.state) { locals, media ->
                val remote = (media as? MediaState.Ready)?.data?.items?.map { it.toUiTorrent() } ?: emptyList()
                (locals + remote).sortedByDescending { it.timestamp }
            }.collect { list ->
                withContext(Dispatchers.Main) { data?.value = list }
            }
        }
    }
}

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
