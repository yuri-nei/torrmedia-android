package ru.yourok.torrserve.server.api

import ru.yourok.torrserve.settings.Settings
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL

/**
 * torrmedia: connectivity self-test for the settings screen.
 * Reports raw TCP reachability separately from the HTTP layer, so a broken URL,
 * a blocked port and a bug in the API client can be told apart without adb.
 */
object NetDiag {

    fun run(): String {
        val sb = StringBuilder()
        report(sb, "qBittorrent", Settings.getQbitUrl())
        runCatching { sb.appendLine("  login: ${QbitApi.loginProbe()}") }
            .onFailure { sb.appendLine("  login: ${it.javaClass.simpleName}: ${it.message}") }
        runCatching {
            val list = QbitApi.listTorrents()
            sb.appendLine("  API: torrents/info OK, ${list.size} torrent(s)")
        }.onFailure { sb.appendLine("  API: ${it.javaClass.simpleName}: ${it.message}") }

        sb.appendLine()
        report(sb, "torrmedia", Settings.getTorrmediaUrl())
        runCatching {
            val items = TorrmediaApi.list()
            sb.appendLine("  API: /list OK, ${items.size} item(s)")
        }.onFailure { sb.appendLine("  API: ${it.javaClass.simpleName}: ${it.message}") }

        return sb.toString().trimEnd()
    }

    private fun report(sb: StringBuilder, title: String, base: String) {
        sb.appendLine("$title: \"$base\" (len=${base.length})")
        if (base.isEmpty()) {
            sb.appendLine("  URL is not set")
            return
        }
        val url = try {
            URL(base)
        } catch (e: Exception) {
            sb.appendLine("  bad URL: ${e.javaClass.simpleName}: ${e.message}")
            return
        }
        val host = url.host ?: ""
        val port = if (url.port > 0) url.port else url.defaultPort
        // host is spelled out char by char: catches invisible/cyrillic lookalikes typed on a TV
        sb.appendLine("  host=[$host] chars=${host.map { it.code }.joinToString(",")} port=$port")

        val started = System.currentTimeMillis()
        try {
            Socket().use { s ->
                s.connect(InetSocketAddress(host, port), 7000)
                sb.appendLine("  TCP: connected in ${System.currentTimeMillis() - started} ms (local ${s.localAddress?.hostAddress})")
            }
        } catch (e: Exception) {
            sb.appendLine("  TCP: ${e.javaClass.simpleName}: ${e.message} (${System.currentTimeMillis() - started} ms)")
        }
    }
}
