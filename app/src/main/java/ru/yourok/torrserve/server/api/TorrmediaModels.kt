package ru.yourok.torrserve.server.api

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.util.Locale


enum class ItemStatus { DOWNLOADING, SEEDING, STOPPED, CHECKING, ERROR, SHELF, UNKNOWN }

enum class FileStatus { READY, PARTIAL, SKIPPED, MISSING, UNKNOWN }

enum class FilePriority(val wire: String) { NORMAL("normal"), HIGH("high"), MAX("max") }

/// watch: null | "started" | "watched"
enum class WatchState { NONE, STARTED, WATCHED }

enum class OperationState { QUEUED, RUNNING, DONE, FAILED, UNKNOWN }

enum class DraftStatus { RESOLVING, READY, CONFIRMING, CONFIRMED, DISCARDING, DISCARDED, FAILED, UNKNOWN }

enum class ItemAction { START, STOP, RECHECK, DELETE_WATCHED, REMOVE, REMOVE_WITH_DATA, CLEAR_VIEWED }

enum class FileAction { PLAY, DELETE, ENABLE, SET_PRIORITY, SET_VIEWED, CLEAR_VIEWED }

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
    val eta: Long? = null,
    val seeds: Int = 0,
    val peers: Int = 0,
)

data class MediaFile(
    val id: String,
    val name: String,
    val path: String,
    val size: Long,
    val progress: Double,
    val status: FileStatus,
    val priority: FilePriority?,
    val watch: WatchState,
    val position: Long,
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
    val magnet: String?,
    val files: List<MediaFile>?,
    val actions: Set<ItemAction>,
)

data class ItemsSnapshot(val items: List<Item>, val degraded: String?)

data class Storage(val free: Long?, val total: Long?, val reserve: Long)

data class DraftFile(
    val id: String,
    val name: String,
    val path: String,
    val size: Long,
    val wanted: Boolean,
)

data class Draft(
    val id: String,
    val status: DraftStatus,
    val title: String,
    val size: Long,
    val category: String?,
    val item: String?,
    val error: String?,
    val files: List<DraftFile>,
    val storage: Storage,
)

data class Operation(
    val id: String,
    val state: OperationState,
    val phase: String?,
    val error: String?,
    val warnings: List<String>,
)

data class Health(val version: String, val contract: Int, val checks: Map<String, String>)


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

private fun <T : Enum<T>> wireEnum(raw: String?, values: Array<T>, fallback: T): T {
    val key = raw?.trim()?.uppercase(Locale.ROOT) ?: return fallback
    return values.firstOrNull { it.name == key } ?: fallback
}

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
    val contract = o.longOrNull("contract")?.toInt()
        ?: throw TorrmediaException("torrmedia did not report its contract version", code = TorrmediaErrorCode.CONTRACT_MISMATCH)
    return Health(o.str("version") ?: "", contract, checks)
}

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

fun isMagnetLink(link: String): Boolean = link.trim().startsWith("magnet:", true)


fun primaryTransferAction(actions: Set<ItemAction>): ItemAction? = when {
    ItemAction.STOP in actions -> ItemAction.STOP
    ItemAction.START in actions -> ItemAction.START
    else -> null
}

fun removalActions(actions: Set<ItemAction>): List<ItemAction> =
    listOf(ItemAction.REMOVE, ItemAction.REMOVE_WITH_DATA).filter { it in actions }

fun itemMenuActions(actions: Set<ItemAction>): List<ItemAction> =
    listOf(ItemAction.RECHECK, ItemAction.DELETE_WATCHED).filter { it in actions }

fun watchedToDelete(files: List<MediaFile>): Int =
    files.count { it.watch == WatchState.WATCHED && FileAction.DELETE in it.actions }

fun fileMenuActions(actions: Set<FileAction>): List<FileAction> =
    listOf(FileAction.ENABLE, FileAction.SET_PRIORITY, FileAction.SET_VIEWED, FileAction.CLEAR_VIEWED, FileAction.DELETE)
        .filter { it in actions }


fun selectedBytes(files: List<DraftFile>, selected: Set<String>): Long =
    files.filter { it.id in selected }.sumOf { it.size }

fun missingBytes(files: List<DraftFile>, selected: Set<String>, storage: Storage): Long? {
    val need = selectedBytes(files, selected)
    if (need == 0L)
        return 0
    val free = storage.free ?: return null
    return (need + storage.reserve - free).coerceAtLeast(0)
}
