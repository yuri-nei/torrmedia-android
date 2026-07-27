package ru.yourok.torrserve.ui.fragments.main.torrents

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import ru.yourok.torrserve.server.api.Api
import ru.yourok.torrserve.server.api.TorrmediaItem
import ru.yourok.torrserve.server.api.TorrmediaViewed
import ru.yourok.torrserve.server.api.TorrmediaWatch
import ru.yourok.torrserve.server.api.TorrmediaApi
import ru.yourok.torrserve.server.api.QbitApi
import ru.yourok.torrserve.server.api.QbitTorrent
import ru.yourok.torrserve.server.api.normalizeHash
import ru.yourok.torrserve.server.api.resolveSingleByHash
import ru.yourok.torrserve.server.api.toUiTorrent
import ru.yourok.torrserve.server.models.torrent.Torrent
import ru.yourok.torrserve.settings.Settings

class TorrentsViewModel : ViewModel() {
    private var isWork = Any()
    private var isUpdate = true
    var data: MutableLiveData<List<Torrent>>? = null

    private var torrmediaRaw: List<TorrmediaItem> = emptyList()
    private var viewedRaw: List<TorrmediaViewed> = emptyList()
    private var qbitRaw: List<QbitTorrent> = emptyList()

    private val qbitFileTotals = mutableMapOf<String, Int>()

    private val qbitFileTotalsFailedAt = mutableMapOf<String, Long>()
    private var downloadsCache: List<Torrent> = emptyList()
    private var remoteTick = 0

    private companion object {
        const val FILES_PARALLELISM = 4
        const val FILES_RETRY_DELAY = 60_000L // ms
    }

    private fun applyWatchSummary(
        ui: Torrent,
        itemPath: String,
        physicalPaths: Collection<String>,
        qbitTotal: Int,
    ) {
        val viewed = viewedRaw.filter { it.item == itemPath }
        ui.watchedFiles = viewed.count { it.watch == TorrmediaWatch.WATCHED }
        ui.startedFiles = viewed.count { it.watch == TorrmediaWatch.STARTED }
        ui.mediaFiles = maxOf(qbitTotal, (physicalPaths + viewed.map { it.path }).distinct().size)
        ui.mediaItemPath = itemPath
    }

    private suspend fun downloadItems(): List<Torrent> {
        if (remoteTick == 0) {
            if (Settings.getTorrmediaUrl().isNotEmpty()) {
                try {
                    torrmediaRaw = TorrmediaApi.list().items
                } catch (e: Exception) {
                    // keep the previous snapshot, torrmedia may be temporarily unreachable
                    Log.e("QbitFork", "torrmedia list failed: ${e.message}")
                }
                try {
                    viewedRaw = TorrmediaApi.listViewed()
                } catch (e: Exception) {
                    // Keep viewed history across a temporary torrmedia outage too.
                    Log.e("QbitFork", "torrmedia viewed failed: ${e.message}")
                }
            } else {
                torrmediaRaw = emptyList()
                viewedRaw = emptyList()
            }
            if (Settings.getQbitEnabled()) {
                try {
                    qbitRaw = QbitApi.listTorrents()
                } catch (e: Exception) {
                    // keep the previous snapshot, qBit may be temporarily unreachable
                    Log.e("QbitFork", "qbit list failed: ${e.message}")
                }
            } else {
                qbitRaw = emptyList()
                qbitFileTotals.clear()
            }

            val liveHashes = qbitRaw.map { it.hash }.toSet()
            qbitFileTotals.keys.retainAll(liveHashes)
            qbitFileTotalsFailedAt.keys.retainAll(liveHashes)
            fetchFileTotals()

            val shelf = torrmediaRaw.map { item ->
                val ui = item.toUiTorrent()
                val live = resolveSingleByHash(item.torrentHash, qbitRaw) { it.hash }
                live?.let { t ->
                    // the shelf row shows live download state instead of a second row for the torrent
                    ui.stat_string = t.state
                    ui.torrent_size = t.size
                    ui.loaded_size = t.completedBytes
                    ui.qbitMagnet = t.magnetUri
                }
                applyWatchSummary(ui, item.path, item.files.map { it.path }, live?.let { qbitFileTotals[it.hash] } ?: 0)
                ui
            }
            val shelfHashes = torrmediaRaw.map { normalizeHash(it.torrentHash) }.filter { it.isNotEmpty() }.toSet()
            val qbitOnly = qbitRaw
                .filter { t -> normalizeHash(t.hash) !in shelfHashes }
                .map { torrent ->
                    val ui = torrent.toUiTorrent()
                    applyWatchSummary(ui, torrent.itemPath, emptyList(), qbitFileTotals[torrent.hash] ?: 0)
                    ui
                }
            downloadsCache = shelf + qbitOnly
        }
        remoteTick = (remoteTick + 1) % 5
        return downloadsCache
    }

    private suspend fun fetchFileTotals() = coroutineScope {
        val now = System.currentTimeMillis()
        val pending = qbitRaw.filter { t ->
            (qbitFileTotals[t.hash] ?: 0) == 0 &&
                    now - (qbitFileTotalsFailedAt[t.hash] ?: 0L) >= FILES_RETRY_DELAY
        }
        if (pending.isEmpty())
            return@coroutineScope
        val gate = Semaphore(FILES_PARALLELISM)
        val results = pending.map { t ->
            async(Dispatchers.IO) {
                t.hash to gate.withPermit {
                    runCatching { QbitApi.getFiles(t.hash).count { it.priority != 0 } }
                }
            }
        }.awaitAll()
        results.forEach { (hash, res) ->
            res.onSuccess {
                qbitFileTotals[hash] = it
                qbitFileTotalsFailedAt.remove(hash)
            }.onFailure {
                // File count is immutable once metadata arrives; retry after the backoff.
                qbitFileTotalsFailedAt[hash] = now
                Log.e("QbitFork", "qbit files count failed: ${it.message}")
            }
        }
    }

    fun getData(): LiveData<List<Torrent>> {
        if (data == null) {
            data = MutableLiveData()
            update()
        }
        return data!!
    }

    override fun onCleared() {
        isWork = false
        super.onCleared()
    }

    fun setUpdate(update: Boolean) {
        isUpdate = update
    }

    private fun update() {
        viewModelScope.launch(Dispatchers.IO) {
            synchronized(isWork) {
                if (isWork == true)
                    return@launch
            }
            isWork = true
            while (isWork == true) {
                try {
                    if (!isUpdate) {
                        delay(1000)
                        continue
                    }
                    // downloads first: TorrServer being down must not hide the torrmedia/qBit side
                    val downloads = downloadItems()
                    val local = try {
                        Api.listTorrent()
                    } catch (e: Exception) {
                        Log.e("QbitFork", "torrserver list failed: ${e.message}")
                        emptyList()
                    }
                    val list = (local + downloads).sortedByDescending { it.timestamp }
                    val oldList = data?.value
                    if (oldList == null || list != oldList)
                        withContext(Dispatchers.Main) { data?.value = list }
                    delay(1000)
                } catch (e: Exception) {
                    delay(2000)
                    val fallback = downloadsCache
                    withContext(Dispatchers.Main) { data?.value = fallback }
                }
            }
        }
    }
}
