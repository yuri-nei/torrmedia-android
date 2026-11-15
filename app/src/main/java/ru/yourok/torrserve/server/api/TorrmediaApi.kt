package ru.yourok.torrserve.server.api

import android.net.Uri
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import ru.yourok.torrserve.settings.Settings

/**
 * Client for the torrmedia contract (API.md), the app's single entry point to the server.
 * The client never needs the qBittorrent address or credentials for anything.
 *
 * Commands are REST (resource in the path, verb as the HTTP method), state is SSE (see
 * TorrmediaStore), video is a plain GET with Range. A response is read by its HTTP code: 2xx is
 * data, anything else is {error, code}. All calls are blocking; call them from an IO thread.
 */
object TorrmediaApi {

    /**
     * Contract number this client understands: a handshake with the server, not an API version.
     * All development before the release is contract 1: the number is pinned at 1 and is NOT
     * bumped when the contract changes. Client and server ship together, and a mismatched number
     * would break the already installed APK.
     */
    const val CONTRACT = 1

    private fun base(): String {
        val url = Settings.getTorrmediaUrl()
        if (url.isEmpty())
            throw TorrmediaException("torrmedia URL is not set", code = TorrmediaErrorCode.NO_URL)
        return url
    }

    /**
     * Identifiers are opaque and, per the contract, contain no characters that need
     * escaping. We validate instead of "fixing": garbage put into the path would reach
     * someone else's resource, and percent-encoding would break the comparison on the server.
     */
    private fun seg(id: String): String {
        if (id.isEmpty() || id.any { it.isWhitespace() || it in "/?#%" })
            throw TorrmediaException("bad identifier", code = TorrmediaErrorCode.NOT_FOUND)
        return id
    }

    private fun call(method: String, path: String, body: String? = null, extraOk: Set<Int> = emptySet()): String {
        val resp = TorrmediaHttp.request(method, base() + path, Settings.getTorrmediaToken(), body)
        if (resp.code !in 200..299 && resp.code !in extraOk)
            throw parseError(resp.code, resp.body)
        return resp.body
    }

    private fun operationCall(method: String, path: String, body: String? = null): Operation =
        parseOperation(call(method, path, body))

    // --- state --------------------------------------------------------------

    /// One-off item list snapshot: the first frame, and the fallback if the stream did not come up
    fun items(): ItemsSnapshot = parseItems(call("GET", "/items"))

    /// A single item: additionally carries magnet and files
    fun item(id: String): Item = parseItem(call("GET", "/items/${seg(id)}"))

    fun operation(id: String): Operation = parseOperation(call("GET", "/operations/${seg(id)}"))

    // --- service ------------------------------------------------------------

    /// 503: the service is alive but one of its checks is failing; the body is still valid,
    /// and the contract number can and must be read from it
    fun health(): Health = parseHealth(call("GET", "/health", extraOk = setOf(503)))

    /// Contract handshake on connect. A mismatch means refusing to work, not "hope it works".
    fun ensureContract(): Health {
        val health = health()
        if (health.contract != CONTRACT)
            throw TorrmediaException(
                "torrmedia speaks contract ${health.contract}, this build expects $CONTRACT — update both",
                code = TorrmediaErrorCode.CONTRACT_MISMATCH,
            )
        return health
    }

    /// An error comes back as a code, not as an empty list: otherwise on an auth failure the
    /// item would silently end up in the default category
    fun categories(): List<String> = parseCategories(call("GET", "/categories"))

    /// Free space on the server, for screens without a draft: a draft has its own storage field
    fun storage(): Storage = parseStorage(call("GET", "/storage"))

    // --- draft: torrent contents before downloading -------------------------

    /**
     * Creates a draft: the torrent is added to qBittorrent to learn its file list and is
     * paused before it starts downloading the content. It is not in the library until the
     * user confirms the selection; discarding removes it together with its data.
     */
    fun createDraft(magnet: String, category: String): Draft {
        val body = JsonObject()
        body.addProperty("magnet", magnet)
        if (category.isNotEmpty())
            body.addProperty("category", category)
        return parseDraft(call("POST", "/drafts", body.toString()))
    }

    fun draft(id: String): Draft = parseDraft(call("GET", "/drafts/${seg(id)}"))

    /// Download the selection. The server rejects an empty list: in qBittorrent a torrent
    /// without a single selected file counts as completed.
    fun confirmDraft(id: String, files: List<String>, category: String): Draft {
        val body = JsonObject()
        val array = JsonArray()
        files.forEach { array.add(seg(it)) }
        body.add("files", array)
        if (category.isNotEmpty())
            body.addProperty("category", category)
        return parseDraft(call("POST", "/drafts/${seg(id)}/confirm", body.toString()))
    }

    /// Discard: the torrent is removed along with whatever got downloaded during selection
    fun discardDraft(id: String): Draft = parseDraft(call("DELETE", "/drafts/${seg(id)}"))

    // --- commands -----------------------------------------------------------

    /// The server accepts only magnet: links; an http(s) link it would fetch from its own network
    fun addItem(magnet: String, category: String): Operation {
        val body = JsonObject()
        body.addProperty("magnet", magnet)
        if (category.isNotEmpty())
            body.addProperty("category", category)
        return operationCall("POST", "/items", body.toString())
    }

    fun startItem(item: String): Operation = operationCall("POST", "/items/${seg(item)}/start")

    fun stopItem(item: String): Operation = operationCall("POST", "/items/${seg(item)}/stop")

    fun recheckItem(item: String): Operation = operationCall("POST", "/items/${seg(item)}/recheck")

    /// delete_files is a separate explicit flag so that a stray request cannot wipe the media
    /// library. The modifier goes in the query: not every HTTP client sends a body with DELETE.
    fun removeItem(item: String, deleteFiles: Boolean): Operation =
        operationCall("DELETE", "/items/${seg(item)}" + if (deleteFiles) "?delete_files=true" else "")

    /// To the client it is "delete an episode"; what that becomes in qBittorrent is not its concern
    fun deleteFile(item: String, file: String): Operation =
        operationCall("DELETE", "/items/${seg(item)}/files/${seg(file)}")

    /// All watched episodes at once. watched=true is a mandatory contract flag: without it the
    /// request would read as "delete all files", and there is no such action.
    fun deleteWatched(item: String): Operation =
        operationCall("DELETE", "/items/${seg(item)}/files?watched=true")

    fun enableFile(item: String, file: String): Operation =
        operationCall("POST", "/items/${seg(item)}/files/${seg(file)}/enable")

    /// File download order. It touches qBittorrent, so it is an operation, even though it
    /// usually finishes within a fraction of a second.
    fun setPriority(item: String, file: String, priority: FilePriority): Operation {
        val body = JsonObject()
        body.addProperty("priority", priority.wire)
        return operationCall("PUT", "/items/${seg(item)}/files/${seg(file)}/priority", body.toString())
    }

    // --- watch marks (204, local state, no operation created) -----------------

    fun setViewed(item: String, file: String, state: WatchState) {
        if (state == WatchState.NONE) {
            clearViewed(item, file)
            return
        }
        val body = JsonObject()
        body.addProperty("state", if (state == WatchState.STARTED) "started" else "watched")
        call("PUT", "/items/${seg(item)}/files/${seg(file)}/viewed", body.toString())
    }

    fun clearViewed(item: String, file: String) {
        call("DELETE", "/items/${seg(item)}/files/${seg(file)}/viewed")
    }

    fun clearViewedItem(item: String) {
        call("DELETE", "/items/${seg(item)}/viewed")
    }

    // --- video --------------------------------------------------------------

    /**
     * Link for an external player. {name} is decorative: the server does not parse it, but
     * players key the saved playback position on the name in the URL; without it all episodes
     * would merge into one.
     */
    fun streamUrl(item: String, file: String, name: String): String =
        "${base()}/items/${seg(item)}/files/${seg(file)}/stream/${Uri.encode(name)}"

    // --- event stream -------------------------------------------------------

    internal fun openStream(path: String): TorrmediaHttp.Stream =
        TorrmediaHttp.openStream(base() + path, Settings.getTorrmediaToken())

    /// Item list stream
    internal const val LIST_EVENTS = "/events"

    /// Single-item stream: a snapshot with files plus the operation events of that item
    internal fun itemEvents(item: String): String = "/items/${seg(item)}/events"

    /// Draft stream: the file list arrives in it by itself once the server gets the metadata
    internal fun draftEvents(draft: String): String = "/drafts/${seg(draft)}/events"
}
