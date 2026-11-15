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

/**
 * The torrmedia block in the side menu header: address, connection state and disk space.
 * The server describes itself through /health and /storage; the client only picks which
 * of the states to show.
 */

enum class TorrmediaLink {
    CONNECTING, // the first response has not arrived yet
    ONLINE,
    QBIT_DOWN, // the service itself is alive, but qBittorrent does not respond
    DEGRADED, // something else among the checks is failing (library root, watch marks)
    UNAUTHORIZED,
    MISMATCH, // the APK and the server are not a matching pair
    OFFLINE,
}

enum class DiskLevel { OK, LOW, CRITICAL }

data class TorrmediaHeader(
    val host: String, // "": no URL is set, the block is not shown at all
    val link: TorrmediaLink,
    val version: String = "",
    val storage: Storage? = null, // null: space is unknown
)

fun linkOf(health: Health): TorrmediaLink = when {
    health.contract != TorrmediaApi.CONTRACT -> TorrmediaLink.MISMATCH
    health.checks["qbittorrent"]?.let { it != "ok" } == true -> TorrmediaLink.QBIT_DOWN
    health.checks.values.any { it != "ok" } -> TorrmediaLink.DEGRADED
    else -> TorrmediaLink.ONLINE
}

/// Used space as a percentage; null if the server does not know the partition size
fun usedPercent(storage: Storage): Int? {
    val free = storage.free ?: return null
    val total = storage.total?.takeIf { it > 0 } ?: return null
    return ((total - free).coerceIn(0, total) * 100 / total).toInt()
}

/**
 * How alarming the disk space is. Critical: free space is under twice the reserve the
 * server keeps untouched (past that not a single episode fits anymore), or under 3% of
 * the partition; low: under 10%.
 */
fun diskLevel(storage: Storage): DiskLevel? {
    val free = storage.free ?: return null
    val total = storage.total?.takeIf { it > 0 } ?: return null
    return when {
        free <= storage.reserve * 2 || free * 100 < total * 3 -> DiskLevel.CRITICAL
        free * 100 < total * 10 -> DiskLevel.LOW
        else -> DiskLevel.OK
    }
}

/// Address without the scheme, like the TorrServer one on the line above
fun displayHost(url: String): String =
    url.removePrefix("http://").removePrefix("https://").trimEnd('/')

/**
 * Polls torrmedia only while someone is looking at the data (the activity is on screen):
 * the TV has no use for background traffic through the VPN. refresh() wakes the poll early:
 * when the menu opens, the numbers must be fresh, not twenty seconds old.
 */
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
        // unlike /health, /storage requires the token: a refusal here is about access
        return try {
            TorrmediaHeader(host, link, health.version, TorrmediaApi.storage())
        } catch (e: TorrmediaException) {
            if (e.code == TorrmediaErrorCode.UNAUTHORIZED)
                TorrmediaHeader(host, TorrmediaLink.UNAUTHORIZED, health.version)
            else // space was briefly unavailable: old numbers are better than none
                TorrmediaHeader(host, link, health.version, previous?.takeIf { it.host == host }?.storage)
        } catch (e: Exception) {
            TorrmediaHeader(host, link, health.version, previous?.takeIf { it.host == host }?.storage)
        }
    }

    private companion object {
        const val INTERVAL_MS = 20_000L
    }
}
