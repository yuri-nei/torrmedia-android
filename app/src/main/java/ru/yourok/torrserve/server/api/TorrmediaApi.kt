package ru.yourok.torrserve.server.api

import android.net.Uri
import com.google.gson.JsonObject
import ru.yourok.torrserve.settings.Settings

object TorrmediaApi {

    const val CONTRACT = 2

    private fun base(): String {
        val url = Settings.getTorrmediaUrl()
        if (url.isEmpty())
            throw TorrmediaException("torrmedia URL is not set", code = TorrmediaErrorCode.NO_URL)
        return url
    }

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


    fun items(): ItemsSnapshot = parseItems(call("GET", "/items"))

    fun item(id: String): Item = parseItem(call("GET", "/items/${seg(id)}"))

    fun operation(id: String): Operation = parseOperation(call("GET", "/operations/${seg(id)}"))


    fun health(): Health = parseHealth(call("GET", "/health", extraOk = setOf(503)))

    fun ensureContract(): Health {
        val health = health()
        if (health.contract != CONTRACT)
            throw TorrmediaException(
                "torrmedia speaks contract ${health.contract}, this build expects $CONTRACT — update both",
                code = TorrmediaErrorCode.CONTRACT_MISMATCH,
            )
        return health
    }

    fun categories(): List<String> = parseCategories(call("GET", "/categories"))


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

    fun removeItem(item: String, deleteFiles: Boolean): Operation =
        operationCall("DELETE", "/items/${seg(item)}" + if (deleteFiles) "?delete_files=true" else "")

    fun deleteFile(item: String, file: String): Operation =
        operationCall("DELETE", "/items/${seg(item)}/files/${seg(file)}")

    fun enableFile(item: String, file: String): Operation =
        operationCall("POST", "/items/${seg(item)}/files/${seg(file)}/enable")


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


    fun streamUrl(item: String, file: String, name: String): String =
        "${base()}/items/${seg(item)}/files/${seg(file)}/stream/${Uri.encode(name)}"


    internal fun openStream(path: String): TorrmediaHttp.Stream =
        TorrmediaHttp.openStream(base() + path, Settings.getTorrmediaToken())

    internal const val LIST_EVENTS = "/events"

    internal fun itemEvents(item: String): String = "/items/${seg(item)}/events"
}
