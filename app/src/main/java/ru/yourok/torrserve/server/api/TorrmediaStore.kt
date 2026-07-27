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
    private val stream = AtomicReference<TorrmediaHttp.Stream?>(null)
    private var job: Job? = null

    fun start(scope: CoroutineScope) {
        if (job?.isActive == true)
            return
        job = scope.launch(Dispatchers.IO) { loop() }
    }

    fun stop() {
        job?.cancel()
        job = null
        stream.getAndSet(null)?.let { runCatching { it.close() } }
        _connected.value = false
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
        cont.invokeOnCancellation { stream.getAndSet(null)?.let { runCatching { it.close() } } }
        val worker = Thread({
            var opened: TorrmediaHttp.Stream? = null
            try {
                opened = TorrmediaApi.openStream(path())
                stream.set(opened)
                if (!cont.isActive) {
                    opened.close()
                    return@Thread
                }
                opened.read { event -> onEvent(event) }
                if (cont.isActive)
                    cont.resume(Unit)
            } catch (e: Throwable) {
                if (cont.isActive)
                    cont.resumeWithException(e)
            } finally {
                opened?.let { runCatching { it.close() } }
                stream.compareAndSet(opened, null)
            }
        }, "torrmedia-sse")
        worker.isDaemon = true
        worker.start()
    }

    private fun onEvent(event: SseEvent) {
        when (event.name) {
            "state" -> {
                val snapshot = runCatching { parse(event.data) }.getOrNull() ?: return
                _state.value = MediaState.Ready(snapshot)
                _connected.value = true
                attempt.set(0)
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
