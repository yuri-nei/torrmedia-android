package ru.yourok.torrserve.server.api

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken


fun normalizeHash(hash: String): String = hash.trim().lowercase()

fun <T> resolveSingleByHash(hash: String, candidates: List<T>, hashOf: (T) -> String): T? {
    val want = normalizeHash(hash)
    if (want.isEmpty())
        return null
    var found: T? = null
    for (c in candidates) {
        if (normalizeHash(hashOf(c)) != want)
            continue
        if (found != null)
            return null
        found = c
    }
    return found
}

data class FileOpSnapshot(
    val hash: String,
    val qbitFileId: Int,
    val path: String,
    val size: Long,
)

fun snapshotStillValid(snapshot: FileOpSnapshot, current: FileOpSnapshot?): Boolean {
    if (current == null)
        return false
    if (snapshot.path != current.path)
        return false
    if (snapshot.hash.isEmpty() || snapshot.qbitFileId < 0)
        return current.hash.isEmpty() && current.qbitFileId < 0
    return normalizeHash(snapshot.hash) == normalizeHash(current.hash) &&
            snapshot.qbitFileId == current.qbitFileId
}

data class TorrmediaResult(
    val ok: Boolean = false,
    val error: String = "",
    val warnings: List<String> = emptyList(),
)

fun parseTorrmediaResult(body: String): TorrmediaResult {
    val trimmed = body.trim()
    if (trimmed.isEmpty() || !trimmed.startsWith("{"))
        return TorrmediaResult(ok = false, error = "empty response body")
    val obj: JsonObject = try {
        JsonParser.parseString(trimmed) as? JsonObject
            ?: return TorrmediaResult(ok = false, error = "response is not a JSON object")
    } catch (e: Exception) {
        return TorrmediaResult(ok = false, error = "malformed response: ${e.message ?: "bad JSON"}")
    }
    val okEl = obj.get("ok")
    if (okEl == null || okEl.isJsonNull)
        return TorrmediaResult(ok = false, error = "response has no ok field")
    val ok = runCatching { okEl.asBoolean }.getOrDefault(false)
    val error = obj.get("error")?.takeIf { !it.isJsonNull }?.let { runCatching { it.asString }.getOrDefault("") } ?: ""
    val warnings = obj.get("warnings")?.takeIf { it.isJsonArray }?.asJsonArray
        ?.mapNotNull { w -> runCatching { w.asString }.getOrNull() }
        ?.filter { it.isNotBlank() }
        ?: emptyList()
    return TorrmediaResult(ok, error, warnings)
}


data class TorrmediaTorrentDetail(
    val torrent: QbitTorrent = QbitTorrent(),
    val files: List<QbitFile> = emptyList(),
)

private val torrmediaGson = Gson()

private fun requireOkObject(op: String, body: String): JsonObject {
    val result = parseTorrmediaResult(body)
    if (!result.ok)
        throw TorrmediaApi.TorrmediaException("$op: ${result.error.ifEmpty { "server reported failure" }}")
    return runCatching { JsonParser.parseString(body.trim()) as? JsonObject }.getOrNull()
        ?: throw TorrmediaApi.TorrmediaException("$op: unexpected response \"${body.trim().take(60)}\"")
}

fun parseTorrentList(body: String): List<QbitTorrent> {
    val obj = requireOkObject("torrents", body)
    val available = obj.get("qbit_available")?.takeIf { !it.isJsonNull }
        ?.let { runCatching { it.asBoolean }.getOrDefault(false) } ?: false
    if (!available)
        throw TorrmediaApi.TorrmediaException("torrents: qBittorrent is unavailable")
    val arr = obj.get("torrents")?.takeIf { it.isJsonArray }?.asJsonArray ?: return emptyList()
    return torrmediaGson.fromJson(arr, object : TypeToken<List<QbitTorrent>>() {}.type) ?: emptyList()
}

fun parseTorrentDetail(body: String): TorrmediaTorrentDetail {
    val obj = requireOkObject("torrent", body)
    val torrent = torrmediaGson.fromJson(obj, QbitTorrent::class.java) ?: QbitTorrent()
    val files: List<QbitFile> = obj.get("files")?.takeIf { it.isJsonArray }?.asJsonArray
        ?.let { torrmediaGson.fromJson(it, object : TypeToken<List<QbitFile>>() {}.type) }
        ?: emptyList()
    return TorrmediaTorrentDetail(torrent, files)
}

fun parseCategories(body: String): List<String> {
    val obj = requireOkObject("categories", body)
    val arr = obj.get("categories")?.takeIf { it.isJsonArray }?.asJsonArray ?: return emptyList()
    return arr.mapNotNull { el ->
        when {
            el.isJsonObject -> el.asJsonObject.get("name")?.takeIf { !it.isJsonNull }
                ?.let { runCatching { it.asString }.getOrNull() }

            el.isJsonPrimitive -> runCatching { el.asString }.getOrNull()
            else -> null
        }
    }.filter { it.isNotBlank() }.distinct().sorted()
}

fun isMagnetLink(link: String): Boolean = link.trim().startsWith("magnet:", true)
