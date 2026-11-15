package ru.yourok.torrserve.server.api

import ru.yourok.torrserve.settings.Settings
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL

/**
 * torrmedia: connectivity self-test for the settings screen.
 * Reports raw TCP reachability separately from the HTTP layer, so a broken URL,
 * a blocked port and a bug in the API client can be told apart without adb.
 *
 * There is nothing to check besides torrmedia: the fork has no other network peer.
 * The report contains no credentials and cannot contain any.
 */
object NetDiag {

    fun run(): String {
        val sb = StringBuilder()
        report(sb, "torrmedia", Settings.getTorrmediaUrl())
        sb.appendLine("  token: ${if (Settings.getTorrmediaToken().isNotEmpty()) "set" else "not set"}")
        runCatching {
            val health = TorrmediaApi.health()
            val checks = health.checks.entries.joinToString(", ") { "${it.key}=${it.value}" }
            sb.appendLine("  API: /health OK, version=${health.version} contract=${health.contract}")
            if (health.contract != TorrmediaApi.CONTRACT)
                sb.appendLine("  CONTRACT MISMATCH: this build expects ${TorrmediaApi.CONTRACT}")
            if (checks.isNotEmpty())
                sb.appendLine("  checks: $checks")
        }.onFailure { sb.appendLine("  API: /health ${it.javaClass.simpleName}: ${it.message}") }
        runCatching {
            val snapshot = TorrmediaApi.items()
            sb.appendLine("  API: /items OK, ${snapshot.items.size} item(s)")
            snapshot.degraded?.let { sb.appendLine("  degraded: $it") }
        }.onFailure { sb.appendLine("  API: /items ${it.javaClass.simpleName}: ${it.message}") }

        return sb.toString().trimEnd()
    }

    private fun report(sb: StringBuilder, title: String, base: String) {
        // the full URL is not written to the report: it goes both to the dialog and
        // (previously) to logcat, so only host:port is printed below
        sb.appendLine("$title:")
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
        // NB: Closeable.use() requires API 19, but the fork keeps minSdk 16, so close manually
        val socket = Socket()
        try {
            socket.connect(InetSocketAddress(host, port), 7000)
            sb.appendLine("  TCP: connected in ${System.currentTimeMillis() - started} ms (local ${socket.localAddress?.hostAddress})")
        } catch (e: Exception) {
            sb.appendLine("  TCP: ${e.javaClass.simpleName}: ${e.message} (${System.currentTimeMillis() - started} ms)")
        } finally {
            try {
                socket.close()
            } catch (e: Exception) {
                // already closed / never connected
            }
        }
    }
}
