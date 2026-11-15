package ru.yourok.torrserve.server.api

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.util.Locale

/**
 * torrmedia contract models and their parsing. There are deliberately no Android dependencies
 * here: all of the parsing is covered by JVM tests (app/src/test/.../TorrmediaModelsTest.kt).
 *
 * The client knows nothing about the service internals: the contract carries no info hashes,
 * file indexes or on-disk paths, identifiers are opaque, and statuses arrive normalized.
 */

/// Item status vocabulary from the contract. UNKNOWN covers both "qBittorrent is unavailable"
/// from the server and an unrecognized value: in either case the state is undefined and we
/// offer no actions.
enum class ItemStatus { DOWNLOADING, SEEDING, STOPPED, CHECKING, ERROR, SHELF, UNKNOWN }

/// PARTIAL describes completeness, not activity: it is used both for a file that is
/// downloading and for a stub left over from a removed torrent. How complete it is comes
/// from progress; whether it is downloading at all comes from the item status.
enum class FileStatus { READY, PARTIAL, SKIPPED, MISSING, UNKNOWN }

/// Download order. "Do not download" is not part of it: excluding a file is delete and
/// bringing it back is enable; the server has separate mechanics for those.
enum class FilePriority(val wire: String) { NORMAL("normal"), HIGH("high"), MAX("max") }

/// watch: null | "started" | "watched"
enum class WatchState { NONE, STARTED, WATCHED }

enum class OperationState { QUEUED, RUNNING, DONE, FAILED, UNKNOWN }

/// Lifecycle of a draft: a torrent added only to learn its file list and not confirmed yet.
/// Until the draft is CONFIRMED, the item is not in the library.
enum class DraftStatus { RESOLVING, READY, CONFIRMING, CONFIRMED, DISCARDING, DISCARDED, FAILED, UNKNOWN }

enum class ItemAction { START, STOP, RECHECK, DELETE_WATCHED, REMOVE, REMOVE_WITH_DATA, CLEAR_VIEWED }

enum class FileAction { PLAY, DELETE, ENABLE, SET_PRIORITY, SET_VIEWED, CLEAR_VIEWED }

/// Contract error codes. CONTRACT_MISMATCH and NO_URL are client-side: no HTTP request was made.
enum class TorrmediaErrorCode {
    NOT_FOUND, AMBIGUOUS, BUSY, STREAMING, REFUSED, INSUFFICIENT_STORAGE,
    QBITTORRENT_UNAVAILABLE, DEADLINE, UNAUTHORIZED,
    CONTRACT_MISMATCH, NO_URL, UNKNOWN,
}

class TorrmediaException(
    message: String,
    val code: TorrmediaErrorCode = TorrmediaErrorCode.UNKNOWN,
) : Exception(message)

data class Transfer(
    val down: Long = 0,
    val up: Long = 0,
    val eta: Long? = null, // seconds, or null if unknown
    val seeds: Int = 0,
    val peers: Int = 0,
)

data class MediaFile(
    val id: String,
    val name: String,
    val path: String, // relative to the item, ONLY for displaying nesting
    val size: Long,
    val progress: Double,
    val status: FileStatus,
    val priority: FilePriority?, // null: file excluded, or no torrent behind the item
    val watch: WatchState,
    val position: Long, // byte offset to resume from
    val actions: Set<FileAction>,
)

data class Item(
    val id: String,
    val title: String,
    val status: ItemStatus,
    val progress: Double,
    val size: Long,
    val downloaded: Long,
    val filesTotal: Int,
    val filesReady: Int,
    val filesWatched: Int,
    val category: String?,
    val addedAt: Long,
    val transfer: Transfer?,
    val magnet: String?, // present in the list too: the client has nothing to build a link from
    val files: List<MediaFile>?, // null = no file list was sent (snapshot of the item list)
    val actions: Set<ItemAction>,
)

/// Body of /items and of the state events in the list stream
data class ItemsSnapshot(val items: List<Item>, val degraded: String?)

/// Space on the library partition. free/total are null if the server could not find them
/// out: showing zeros instead would be lying about an empty disk.
data class Storage(val free: Long?, val total: Long?, val reserve: Long)

/// A draft file. It has neither status nor progress: nothing is on disk yet, and the only
/// decision about it is whether to download it or not.
data class DraftFile(
    val id: String,
    val name: String,
    val path: String, // relative to the item, ONLY for displaying nesting
    val size: Long,
    val wanted: Boolean, // what the server suggests downloading
)

data class Draft(
    val id: String,
    val status: DraftStatus,
    val title: String,
    val size: Long,
    val category: String?,
    val item: String?, // item id, appears together with CONFIRMED
    val error: String?,
    val files: List<DraftFile>,
    val storage: Storage,
)

data class Operation(
    val id: String,
    val state: OperationState,
    val phase: String?, // waiting/stopping/trimming/rechecking/restarting or null
    val error: String?,
    // may be non-empty even on done: the operation succeeded, but with caveats
    val warnings: List<String>,
)

data class Health(val version: String, val contract: Int, val checks: Map<String, String>)

// --- parsing ----------------------------------------------------------------

private fun JsonElement?.obj(): JsonObject? = this?.takeIf { it.isJsonObject }?.asJsonObject

private fun JsonObject.field(name: String): JsonElement? = get(name)?.takeIf { !it.isJsonNull }

private fun JsonObject.str(name: String): String? =
    field(name)?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asString }.getOrNull() }

private fun JsonObject.longOrNull(name: String): Long? =
    field(name)?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asLong }.getOrNull() }

private fun JsonObject.long(name: String): Long = longOrNull(name) ?: 0L

private fun JsonObject.int(name: String): Int = longOrNull(name)?.toInt() ?: 0

private fun JsonObject.dbl(name: String): Double =
    field(name)?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asDouble }.getOrNull() } ?: 0.0

private fun JsonObject.arr(name: String): JsonArray? =
    field(name)?.takeIf { it.isJsonArray }?.asJsonArray

/// An unrecognized value maps to the fallback instead of failing the whole parse
private fun <T : Enum<T>> wireEnum(raw: String?, values: Array<T>, fallback: T): T {
    val key = raw?.trim()?.uppercase(Locale.ROOT) ?: return fallback
    return values.firstOrNull { it.name == key } ?: fallback
}

/// An unrecognized action is DROPPED: we must not show a button the server did not promise
private fun <T : Enum<T>> wireActions(arr: JsonArray?, values: Array<T>): Set<T> {
    if (arr == null)
        return emptySet()
    val out = LinkedHashSet<T>()
    for (el in arr) {
        val name = el?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asString }.getOrNull() }
            ?.trim()?.uppercase(Locale.ROOT) ?: continue
        values.firstOrNull { it.name == name }?.let { out.add(it) }
    }
    return out
}

private fun parseObject(body: String): JsonObject =
    runCatching { JsonParser.parseString(body).obj() }.getOrNull()
        ?: throw TorrmediaException("unexpected response \"${body.trim().take(60)}\"")

fun transferOf(o: JsonObject): Transfer = Transfer(
    down = o.long("down"),
    up = o.long("up"),
    eta = o.longOrNull("eta"),
    seeds = o.int("seeds"),
    peers = o.int("peers"),
)

fun fileOf(o: JsonObject): MediaFile = MediaFile(
    id = o.str("id") ?: "",
    name = o.str("name") ?: "",
    path = o.str("path") ?: "",
    size = o.long("size"),
    progress = o.dbl("progress"),
    status = wireEnum(o.str("status"), FileStatus.values(), FileStatus.UNKNOWN),
    // an unrecognized value becomes null: no point showing a priority we do not understand
    priority = o.str("priority")?.trim()?.let { raw -> FilePriority.values().firstOrNull { it.wire == raw } },
    watch = wireEnum(o.str("watch"), WatchState.values(), WatchState.NONE),
    position = o.long("position"),
    actions = wireActions(o.arr("actions"), FileAction.values()),
)

fun itemOf(o: JsonObject): Item = Item(
    id = o.str("id") ?: "",
    title = o.str("title") ?: "",
    status = wireEnum(o.str("status"), ItemStatus.values(), ItemStatus.UNKNOWN),
    progress = o.dbl("progress"),
    size = o.long("size"),
    downloaded = o.long("downloaded"),
    filesTotal = o.int("files_total"),
    filesReady = o.int("files_ready"),
    filesWatched = o.int("files_watched"),
    category = o.str("category"),
    addedAt = o.long("added_at"),
    transfer = o.field("transfer").obj()?.let { transferOf(it) },
    magnet = o.str("magnet"),
    files = o.arr("files")?.mapNotNull { el -> el.obj()?.let { fileOf(it) } },
    actions = wireActions(o.arr("actions"), ItemAction.values()),
)

fun operationOf(o: JsonObject): Operation = Operation(
    id = o.str("id") ?: "",
    state = wireEnum(o.str("state"), OperationState.values(), OperationState.UNKNOWN),
    phase = o.str("phase"),
    error = o.str("error"),
    warnings = o.arr("warnings")
        ?.mapNotNull { runCatching { it.asString }.getOrNull() }
        ?.filter { it.isNotBlank() }
        ?: emptyList(),
)

private fun JsonObject.nullableLong(name: String): Long? = longOrNull(name)

fun storageOf(o: JsonObject?): Storage {
    if (o == null)
        return Storage(null, null, 0)
    return Storage(
        free = o.nullableLong("free"),
        total = o.nullableLong("total"),
        reserve = o.long("reserve"),
    )
}

fun draftFileOf(o: JsonObject): DraftFile = DraftFile(
    id = o.str("id") ?: "",
    name = o.str("name") ?: "",
    path = o.str("path") ?: "",
    size = o.long("size"),
    // A missing field means "not selected": the client never makes up the selection itself
    wanted = o.field("wanted")?.takeIf { it.isJsonPrimitive }
        ?.let { runCatching { it.asBoolean }.getOrNull() } ?: false,
)

fun draftOf(o: JsonObject): Draft = Draft(
    id = o.str("id") ?: "",
    status = wireEnum(o.str("status"), DraftStatus.values(), DraftStatus.UNKNOWN),
    title = o.str("title") ?: "",
    size = o.long("size"),
    category = o.str("category"),
    item = o.str("item"),
    error = o.str("error"),
    files = o.arr("files")?.mapNotNull { el -> el.obj()?.let { draftFileOf(it) } } ?: emptyList(),
    storage = storageOf(o.field("storage").obj()),
)

fun parseDraft(body: String): Draft = draftOf(parseObject(body))

fun parseStorage(body: String): Storage = storageOf(parseObject(body))

fun parseItems(body: String): ItemsSnapshot {
    val o = parseObject(body)
    val items = o.arr("items")?.mapNotNull { el -> el.obj()?.let { itemOf(it) } } ?: emptyList()
    val degraded = o.field("degraded")?.let { el ->
        if (el.isJsonPrimitive) runCatching { el.asString }.getOrNull() else el.toString()
    }
    return ItemsSnapshot(items, degraded)
}

fun parseItem(body: String): Item = itemOf(parseObject(body))

fun parseOperation(body: String): Operation = operationOf(parseObject(body))

fun parseCategories(body: String): List<String> {
    val o = parseObject(body)
    val arr = o.arr("categories") ?: return emptyList()
    return arr.mapNotNull { el -> el.obj()?.let { it.str("name") } }
        .filter { it.isNotBlank() }
        .distinct()
        .sorted()
}

fun parseHealth(body: String): Health {
    val o = parseObject(body)
    val checks = LinkedHashMap<String, String>()
    o.field("checks").obj()?.entrySet()?.forEach { (k, v) ->
        checks[k] = runCatching { v.asString }.getOrNull() ?: v.toString()
    }
    // contract is required: a server that does not state it is not one we can talk to
    val contract = o.longOrNull("contract")?.toInt()
        ?: throw TorrmediaException("torrmedia did not report its contract version", code = TorrmediaErrorCode.CONTRACT_MISMATCH)
    return Health(o.str("version") ?: "", contract, checks)
}

/// The error body is {error, code}. A non-JSON body (captive portal, proxy) is an error too,
/// but it has no code.
fun parseError(httpCode: Int, body: String): TorrmediaException {
    val o = runCatching { JsonParser.parseString(body).obj() }.getOrNull()
    val raw = o?.str("code") ?: ""
    val text = o?.str("error")?.takeIf { it.isNotBlank() } ?: body.trim().take(120)
    val code = wireEnum(raw, TorrmediaErrorCode.values(), TorrmediaErrorCode.UNKNOWN)
    return TorrmediaException(
        if (text.isEmpty()) "HTTP $httpCode" else "HTTP $httpCode: $text",
        code,
    )
}

/// POST /items accepts only magnet: links; the server rejects http(s).
fun isMagnetLink(link: String): Boolean = link.trim().startsWith("magnet:", true)

// --- choosing actions -------------------------------------------------------
//
// Everything the screen shows comes strictly from the actions of the received object. The
// client draws no "is this allowed" conclusions of its own: an unavailable qBittorrent, a
// check in progress, a playing video and an ambiguous file all look the same: the action
// is simply missing from the list. The functions are pure and covered by tests.

/// The primary header button. The server does not send both at once, but if it does,
/// we show stop: stopping is reversible, starting the item is not.
fun primaryTransferAction(actions: Set<ItemAction>): ItemAction? = when {
    ItemAction.STOP in actions -> ItemAction.STOP
    ItemAction.START in actions -> ItemAction.START
    else -> null
}

/// Entries of the header's "more" menu. This is for what is needed rarely and costs a lot:
/// recheck rehashes the whole item, removal takes the item away completely. Start/stop and
/// deleting watched episodes (frequent while watching a series) are separate buttons and are
/// not duplicated here. Removal entries go in order of increasing destructiveness, the
/// irreversible one last, so it is not hit while aiming at the neighbouring entry.
fun itemMenuActions(actions: Set<ItemAction>): List<ItemAction> =
    listOf(ItemAction.RECHECK, ItemAction.REMOVE, ItemAction.REMOVE_WITH_DATA).filter { it in actions }

/// The episode that gets list focus: the first started one, otherwise the first untouched
/// one, otherwise the first. The remote then lands right on what to watch next, both when the
/// screen opens and when a header button disappeared from under the focus (for example,
/// after clearing watched).
fun nextEpisodeIndex(files: List<MediaFile>): Int {
    val started = files.indexOfFirst { it.watch == WatchState.STARTED }
    if (started >= 0)
        return started
    val untouched = files.indexOfFirst { it.watch == WatchState.NONE }
    return if (untouched >= 0) untouched else 0
}

/// How many episodes delete_watched will remove. Counted from the same snapshot that is drawn:
/// the server decides anyway, this number is only for the confirmation dialog.
fun watchedToDelete(files: List<MediaFile>): Int =
    files.count { it.watch == WatchState.WATCHED && FileAction.DELETE in it.actions }

/// Long-press menu entries for a file: play is the normal tap and is not duplicated in the menu
fun fileMenuActions(actions: Set<FileAction>): List<FileAction> =
    listOf(FileAction.ENABLE, FileAction.SET_PRIORITY, FileAction.SET_VIEWED, FileAction.CLEAR_VIEWED, FileAction.DELETE)
        .filter { it in actions }

// --- file selection dialog -------------------------------------------------
//
// The arithmetic lives outside the fragment: it is about meaning, not widgets, and it is
// easier to verify with tests than by eye on a TV.

fun selectedBytes(files: List<DraftFile>, selected: Set<String>): Long =
    files.filter { it.id in selected }.sumOf { it.size }

/**
 * How many bytes are missing to download the selection: the selected size plus the reserve
 * the server keeps free, minus the free space. 0 means it fits.
 * null means the server does not know the free space, so there is no shortfall to talk about.
 */
fun missingBytes(files: List<DraftFile>, selected: Set<String>, storage: Storage): Long? {
    val need = selectedBytes(files, selected)
    // Nothing selected: the item is created stopped, there is nothing to download, and space
    // does not matter; otherwise on an almost full disk we would warn about a shortfall for
    // an empty selection.
    if (need == 0L)
        return 0
    val free = storage.free ?: return null
    return (need + storage.reserve - free).coerceAtLeast(0)
}
