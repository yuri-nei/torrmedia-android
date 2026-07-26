package ru.yourok.torrserve.server.api

import com.google.gson.JsonObject
import com.google.gson.JsonParser


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
    val ok: Boolean = true,
    val error: String = "",
    val warnings: List<String> = emptyList(),
)

fun parseTorrmediaResult(body: String): TorrmediaResult {
    val trimmed = body.trim()
    if (trimmed.isEmpty() || !trimmed.startsWith("{"))
        return TorrmediaResult()
    val obj: JsonObject = try {
        JsonParser.parseString(trimmed) as? JsonObject ?: return TorrmediaResult()
    } catch (e: Exception) {
        return TorrmediaResult()
    }
    val okEl = obj.get("ok")
    val ok = if (okEl == null || okEl.isJsonNull) true else runCatching { okEl.asBoolean }.getOrDefault(true)
    val error = obj.get("error")?.takeIf { !it.isJsonNull }?.let { runCatching { it.asString }.getOrDefault("") } ?: ""
    val warnings = obj.get("warnings")?.takeIf { it.isJsonArray }?.asJsonArray
        ?.mapNotNull { w -> runCatching { w.asString }.getOrNull() }
        ?.filter { it.isNotBlank() }
        ?: emptyList()
    return TorrmediaResult(ok, error, warnings)
}
