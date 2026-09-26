package ru.yourok.torrserve.ui.activities.main

import androidx.lifecycle.LiveData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import ru.yourok.torrserve.server.api.Health
import ru.yourok.torrserve.server.api.TorrmediaErrorCode
import ru.yourok.torrserve.server.api.TorrmediaException
import ru.yourok.torrserve.server.api.TorrmediaApi
import ru.yourok.torrserve.server.api.Storage
import ru.yourok.torrserve.settings.Settings


enum class TorrmediaLink {
    CONNECTING,
    ONLINE,
    QBIT_DOWN,
    DEGRADED,
    UNAUTHORIZED,
    MISMATCH,
    OFFLINE,
}

enum class DiskLevel { OK, LOW, CRITICAL }

data class TorrmediaHeader(
    val host: String,
    val link: TorrmediaLink,
    val version: String = "",
    val storage: Storage? = null,
)

fun linkOf(health: Health): TorrmediaLink = when {
    health.contract != TorrmediaApi.CONTRACT -> TorrmediaLink.MISMATCH
    health.checks["qbittorrent"]?.let { it != "ok" } == true -> TorrmediaLink.QBIT_DOWN
    health.checks.values.any { it != "ok" } -> TorrmediaLink.DEGRADED
    else -> TorrmediaLink.ONLINE
}

fun usedPercent(storage: Storage): Int? {
    val free = storage.free ?: return null
    val total = storage.total?.takeIf { it > 0 } ?: return null
    return ((total - free).coerceIn(0, total) * 100 / total).toInt()
}

fun diskLevel(storage: Storage): DiskLevel? {
    val free = storage.free ?: return null
    val total = storage.total?.takeIf { it > 0 } ?: return null
    return when {
        free <= storage.reserve * 2 || free * 100 < total * 3 -> DiskLevel.CRITICAL
        free * 100 < total * 10 -> DiskLevel.LOW
        else -> DiskLevel.OK
    }
}

fun displayHost(url: String): String =
    url.removePrefix("http://").removePrefix("https://").trimEnd('/')

class TorrmediaHeaderLiveData : LiveData<TorrmediaHeader>() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var job: Job? = null

    fun refresh() {
        wake.trySend(Unit)
    }

    override fun onActive() {
        if (job?.isActive == true)
            return
        if (value == null) {
            val url = Settings.getTorrmediaUrl()
            value = TorrmediaHeader(if (url.isEmpty()) "" else displayHost(url), TorrmediaLink.CONNECTING)
        }
        job = scope.launch {
            while (isActive) {
                postValue(fetch(value))
                withTimeoutOrNull(INTERVAL_MS) { wake.receive() }
            }
        }
    }

    override fun onInactive() {
        job?.cancel()
        job = null
    }

    fun close() = scope.cancel()

    private fun fetch(previous: TorrmediaHeader?): TorrmediaHeader {
        val url = Settings.getTorrmediaUrl()
        if (url.isEmpty())
            return TorrmediaHeader("", TorrmediaLink.OFFLINE)
        val host = displayHost(url)
        val health = try {
            TorrmediaApi.health()
        } catch (e: TorrmediaException) {
            val link = if (e.code == TorrmediaErrorCode.CONTRACT_MISMATCH) TorrmediaLink.MISMATCH else TorrmediaLink.OFFLINE
            return TorrmediaHeader(host, link)
        } catch (e: Exception) {
            return TorrmediaHeader(host, TorrmediaLink.OFFLINE)
        }
        val link = linkOf(health)
        if (link == TorrmediaLink.MISMATCH)
            return TorrmediaHeader(host, link, health.version)
        return try {
            TorrmediaHeader(host, link, health.version, TorrmediaApi.storage())
        } catch (e: TorrmediaException) {
            if (e.code == TorrmediaErrorCode.UNAUTHORIZED)
                TorrmediaHeader(host, TorrmediaLink.UNAUTHORIZED, health.version)
            else
                TorrmediaHeader(host, link, health.version, previous?.takeIf { it.host == host }?.storage)
        } catch (e: Exception) {
            TorrmediaHeader(host, link, health.version, previous?.takeIf { it.host == host }?.storage)
        }
    }

    private companion object {
        const val INTERVAL_MS = 20_000L
    }
}
