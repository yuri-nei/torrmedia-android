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

sealed class MediaState<out T> {
    object Loading : MediaState<Nothing>()
    data class Ready<T>(val data: T) : MediaState<T>()

    object Removed : MediaState<Nothing>()

    data class Failed(val message: String, val fatal: Boolean) : MediaState<Nothing>()
}

class TorrmediaStore<T> private constructor(
    private val path: () -> String,
    private val parse: (String) -> T,
) {
    private val _state = MutableStateFlow<MediaState<T>>(MediaState.Loading)
    val state: StateFlow<MediaState<T>> = _state

    private val _operations = MutableSharedFlow<Operation>(
        replay = 0, extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val operations: SharedFlow<Operation> = _operations

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected

    private val attempt = AtomicInteger(0)

    private val generation = AtomicInteger(0)
    private val stream = AtomicReference<Pair<Int, TorrmediaHttp.Stream>?>(null)
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

    private fun closeStream(only: Int?) {
        while (true) {
            val current = stream.get() ?: return
            if (only != null && current.first != only)
                return
            if (stream.compareAndSet(current, null)) {
                runCatching { current.second.close() }
                return
            }
        }
    }

    private suspend fun loop() {
        while (coroutineContext.isActive) {
            try {
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
                    _state.value = MediaState.Failed(e.message ?: e.toString(), fatal = false)
            }
            _connected.value = false
            val tries = attempt.get()
            if (tries < 100)
                attempt.incrementAndGet()
            delay(reconnectDelayMs(tries, Math.random()))
        }
    }

    private suspend fun consume() = suspendCancellableCoroutine<Unit> { cont ->
        val mine = generation.get()
        cont.invokeOnCancellation { closeStream(mine) }
        val worker = Thread({
            var opened: TorrmediaHttp.Stream? = null
            try {
                opened = TorrmediaApi.openStream(path())
                if (!cont.isActive || generation.get() != mine) {
                    opened.close()
                    if (cont.isActive)
                        cont.resume(Unit)
                    return@Thread
                }
                stream.set(mine to opened)
                opened.read { event -> onEvent(event) }
                if (cont.isActive)
                    cont.resume(Unit)
            } catch (e: Throwable) {
                if (cont.isActive)
                    cont.resumeWithException(e)
            } finally {
                opened?.let { runCatching { it.close() } }
                closeStream(mine)
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
                    _state.value = MediaState.Removed
                    return
                }
                val snapshot = runCatching { parse(event.data) }.getOrNull()
                if (snapshot == null) {
                    _state.value = MediaState.Failed("bad state payload", fatal = false)
                    return
                }
                _state.value = MediaState.Ready(snapshot)
            }

            "operation" -> runCatching { parseOperation(event.data) }.getOrNull()
                ?.let { _operations.tryEmit(it) }

            "ping" -> _connected.value = true
        }
    }

    companion object {
        fun items(): TorrmediaStore<ItemsSnapshot> =
            TorrmediaStore({ TorrmediaApi.LIST_EVENTS }, { parseItems(it) })

        fun item(id: String): TorrmediaStore<Item> =
            TorrmediaStore({ TorrmediaApi.itemEvents(id) }, { parseItem(it) })
    }
}
