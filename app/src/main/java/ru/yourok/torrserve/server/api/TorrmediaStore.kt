package ru.yourok.torrserve.server.api

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Screen state. LOADING means the stream has not come up yet: there is NO state, and the screen
 * must show loading rather than an empty list (an empty list is also a claim about the world).
 */
sealed class MediaState<out T> {
    object Loading : MediaState<Nothing>()
    data class Ready<T>(val data: T) : MediaState<T>()

    /// The server sent null into the stream: the object is gone. The details screen must
    /// close rather than keep showing stale files and actions as current.
    object Removed : MediaState<Nothing>()

    /// fatal: there is nothing left to retry (contract numbers differ, no URL is set).
    /// code is kept so the screen can tell "the object is gone" from "no connection":
    /// reconnecting to something that no longer exists is pointless.
    data class Failed(
        val message: String,
        val fatal: Boolean,
        val code: TorrmediaErrorCode? = null,
    ) : MediaState<Nothing>()
}

/**
 * State store on top of SSE. The server itself sends a complete snapshot on every change:
 * the client does not poll, merge or reconcile, and an incoming snapshot replaces the state
 * ENTIRELY. There are no deltas, no sequence numbers and no resume.
 *
 * Lives as long as its screen: start() when shown, stop() when leaving.
 */
class TorrmediaStore<T> private constructor(
    /// the path is computed lazily: an empty URL or an invalid identifier must become a
    /// Failed state, not an exception from the constructor in the middle of onCreateView
    private val path: () -> String,
    private val parse: (String) -> T,
) {
    private val _state = MutableStateFlow<MediaState<T>>(MediaState.Loading)
    val state: StateFlow<MediaState<T>> = _state

    /// Progress and results of operations. replay=0: once the screen is closed there is
    /// nothing to show them on.
    private val _operations = MutableSharedFlow<Operation>(
        replay = 0, extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val operations: SharedFlow<Operation> = _operations

    /// Whether the stream is connected right now. On a disconnect we keep the snapshot (it is
    /// still the best one known), but the screen must be able to say there is no connection.
    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected

    private val attempt = AtomicInteger(0)

    /// Generation of the socket owner. An old worker that managed to open its connection after
    /// stop() must not overwrite the new worker's slot: the next stop() would then be unable
    /// to close the live socket, and it would linger until the read timeout.
    private val generation = AtomicInteger(0)
    private class OwnedStream(val value: TorrmediaHttp.Stream)
    private val stream = AtomicReference<OwnedStream?>(null)
    private var job: Job? = null

    fun start(scope: CoroutineScope) {
        if (job?.isActive == true)
            return
        generation.incrementAndGet()
        job = scope.launch(Dispatchers.IO) { loop() }
    }

    fun stop() {
        generation.incrementAndGet()
        job?.cancel()
        job = null
        closeStream(null)
        _connected.value = false
    }

    /// Closes the current socket; with only != null, only if it belongs to that owner.
    private fun closeStream(only: OwnedStream?) {
        while (true) {
            val current = stream.get() ?: return
            if (only != null && current !== only)
                return
            if (stream.compareAndSet(current, null)) {
                runCatching { current.value.close() }
                return
            }
        }
    }

    private suspend fun loop() {
        while (coroutineContext.isActive) {
            try {
                // the contract handshake runs on every connect: the server may have been
                // updated while the screen was open
                TorrmediaApi.ensureContract()
                consume()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                val code = (e as? TorrmediaException)?.code
                if (code == TorrmediaErrorCode.CONTRACT_MISMATCH || code == TorrmediaErrorCode.NO_URL) {
                    _connected.value = false
                    _state.value = MediaState.Failed(e.message ?: "contract mismatch", fatal = true)
                    return
                }
                if (_state.value !is MediaState.Ready<*>)
                    _state.value = MediaState.Failed(e.message ?: e.toString(), fatal = false, code = code)
            }
            _connected.value = false
            val tries = attempt.get()
            if (tries < 100) // reconnectDelayMs applies the cap, the counter need not grow
                attempt.incrementAndGet()
            delay(reconnectDelayMs(tries, Math.random()))
        }
    }

    /**
     * Reading the stream runs on its own OS thread: a blocking socket read ignores coroutine
     * cancellation, and only closing the connection can wake it up.
     */
    private suspend fun consume() = suspendCancellableCoroutine<Unit> { cont ->
        val mine = generation.get()
        val mineOwner = AtomicReference<OwnedStream?>(null)
        cont.invokeOnCancellation { mineOwner.get()?.let { closeStream(it) } }
        val worker = Thread({
            var opened: TorrmediaHttp.Stream? = null
            var owner: OwnedStream? = null
            try {
                opened = TorrmediaApi.openStream(path())
                // A quick check before the CAS catches an ordinary stop during open.
                if (!cont.isActive || generation.get() != mine) {
                    opened.close()
                    if (cont.isActive)
                        cont.resume(Unit)
                    return@Thread
                }
                val candidate = OwnedStream(opened)
                if (!stream.compareAndSet(null, candidate)) {
                    opened.close()
                    if (cont.isActive)
                        cont.resume(Unit)
                    return@Thread
                }
                owner = candidate
                mineOwner.set(candidate)
                // Stop/start could have happened between the previous check and the CAS.
                // After registering, check the generation again and remove only our own
                // owner, without touching the new stream.
                if (!cont.isActive || generation.get() != mine) {
                    closeStream(candidate)
                    if (cont.isActive)
                        cont.resume(Unit)
                    return@Thread
                }
                opened.read { event ->
                    if (cont.isActive && generation.get() == mine)
                        onEvent(event)
                }
                if (cont.isActive)
                    cont.resume(Unit)
            } catch (e: Throwable) {
                if (cont.isActive)
                    cont.resumeWithException(e)
            } finally {
                owner?.let { closeStream(it) }
                    ?: opened?.let { runCatching { it.close() } }
            }
        }, "torrmedia-sse")
        worker.isDaemon = true
        worker.start()
    }

    private fun onEvent(event: SseEvent) {
        when (event.name) {
            "state" -> {
                _connected.value = true
                attempt.set(0)
                if (event.data.trim() == "null") {
                    // The item was removed: showing its files and actions any longer is a lie.
                    _state.value = MediaState.Removed
                    return
                }
                val snapshot = runCatching { parse(event.data) }.getOrNull()
                if (snapshot == null) {
                    // Failing to parse means the contract is out of sync, not an empty
                    // state. Staying silent is not an option: the screen will show an error.
                    _state.value = MediaState.Failed("bad state payload", fatal = false)
                    return
                }
                _state.value = MediaState.Ready(snapshot)
            }

            "operation" -> runCatching { parseOperation(event.data) }.getOrNull()
                ?.let { _operations.tryEmit(it) }

            // ping carries nothing by itself; the point is that the read did not hit the
            // timeout: the stream's readTimeout is the "server is alive" timer
            "ping" -> _connected.value = true
        }
    }

    companion object {
        fun items(): TorrmediaStore<ItemsSnapshot> =
            TorrmediaStore({ TorrmediaApi.LIST_EVENTS }, { parseItems(it) })

        fun item(id: String): TorrmediaStore<Item> =
            TorrmediaStore({ TorrmediaApi.itemEvents(id) }, { parseItem(it) })

        fun draft(id: String): TorrmediaStore<Draft> =
            TorrmediaStore({ TorrmediaApi.draftEvents(id) }, { parseDraft(it) })
    }
}
