package ru.yourok.torrserve.ui.activities.play

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.yourok.torrserve.R
import ru.yourok.torrserve.app.App
import ru.yourok.torrserve.server.api.Api
import ru.yourok.torrserve.server.api.TorrmediaApi
import ru.yourok.torrserve.server.api.OperationState
import ru.yourok.torrserve.server.api.Operation
import ru.yourok.torrserve.server.api.isMagnetLink
import ru.yourok.torrserve.server.models.torrent.Torrent
import java.util.Locale

fun PlayActivity.readArgs() {
    intent.data?.let {
        torrentLink = it.toString()
    }
    if (intent.action?.equals(Intent.ACTION_SEND) == true) {
        if (intent.getStringExtra(Intent.EXTRA_TEXT) != null)
            torrentLink = intent.getStringExtra(Intent.EXTRA_TEXT)!!
        if (intent.extras?.get(Intent.EXTRA_STREAM) != null)
            torrentLink = intent.extras?.get(Intent.EXTRA_STREAM)?.toString() ?: ""
    }

    intent?.extras?.apply {
        keySet().forEach { key ->
            when (key.lowercase(Locale.getDefault())) {
                "hash" -> torrentHash = this.getString(key) ?: ""
                "title" -> torrentTitle = this.getString(key) ?: ""
                "poster" -> torrentPoster = this.getString(key) ?: ""
                "category" -> torrentCategory = this.getString(key) ?: ""
                "data" -> torrentData = this.getString(key) ?: ""
                "fileindex" -> torrentFileIndex = this.getInt(key, -1)
                "save" -> torrentSave = this.getBoolean(key)
            }
        }
    }
}

fun PlayActivity.successful(intent: Intent) {
    setResult(AppCompatActivity.RESULT_OK, intent)
    finish()
}

fun PlayActivity.error(err: ReturnError) {
    lifecycleScope.launch(Dispatchers.Default) {
        val ret = Intent()
        ret.putExtra("errCode", err.errCode)
        ret.putExtra("errMessage", err.errMessage)
        setResult(AppCompatActivity.RESULT_CANCELED, ret)
        App.toast(err.errMessage, true)
        delay(App.LONG_TOAST_DURATION.toLong()) // as in toast duration
        if (err != ErrTorrServerNotResponding)
            finish()
    }
}

fun PlayActivity.addAndExit() {
    lifecycleScope.launch(Dispatchers.IO) {
        try {
            addTorrent(torrentHash, torrentLink, torrentTitle, torrentPoster, torrentCategory, torrentData, true)
        } catch (e: Exception) {
            error(ErrLoadTorrent)
            return@launch
        }
    }
    torrentHash = ""
    finish()
}

/// torrmedia: pick a category, push the magnet to torrmedia and close (no player)
fun PlayActivity.qbitAndExit() {
    lifecycleScope.launch(Dispatchers.IO) {
        showProgress()
        val categories = try {
            TorrmediaApi.categories()
        } catch (e: Exception) {
            hideProgress()
            App.toast(App.context.getString(R.string.qbit_categories_error) + ": " + (e.message ?: ""), true)
            withContext(Dispatchers.Main) { finish() }
            return@launch
        }
        hideProgress()
        withContext(Dispatchers.Main) {
            AlertDialog.Builder(this@qbitAndExit)
                .setTitle(R.string.qbit_send_confirm)
                .setMessage(torrentLink)
                .setPositiveButton(android.R.string.ok) { _, _ -> chooseCategoryAndExit(categories) }
                .setNegativeButton(android.R.string.cancel) { _, _ -> finish() }
                .setOnCancelListener { finish() }
                .show()
        }
    }
}

private fun PlayActivity.chooseCategoryAndExit(categories: List<String>) {
    if (categories.isEmpty()) {
        qbitSendAndExit("")
        return
    }
    var checked = 0
    AlertDialog.Builder(this)
        .setTitle(R.string.qbit_select_category)
        .setSingleChoiceItems(categories.toTypedArray(), checked) { _, which -> checked = which }
        .setPositiveButton(android.R.string.ok) { _, _ -> qbitSendAndExit(categories[checked]) }
        .setNegativeButton(android.R.string.cancel) { _, _ -> finish() }
        .setOnCancelListener { finish() }
        .show()
}

private fun PlayActivity.qbitSendAndExit(category: String) {
    lifecycleScope.launch(Dispatchers.IO) {
        showProgress()
        try {
            if (!isMagnetLink(torrentLink))
                throw IllegalArgumentException(App.context.getString(R.string.qbit_only_magnet))
            val op = TorrmediaApi.addItem(torrentLink.trim(), category)
            val outcome = awaitOperation(op)
            when (outcome.state) {
                OperationState.FAILED -> App.toast(
                    App.context.getString(R.string.qbit_send_error) +
                        (outcome.error?.let { ": $it" } ?: ""), true
                )

                else -> App.toast(R.string.qbit_sent)
            }
        } catch (e: Exception) {
            App.toast(App.context.getString(R.string.qbit_send_error) + ": " + (e.message ?: ""), true)
        }
        withContext(Dispatchers.Main) { finish() }
    }
}

private suspend fun awaitOperation(op: Operation): Operation {
    if (op.id.isEmpty())
        return op
    var current = op
    repeat(20) {
        if (current.state == OperationState.DONE || current.state == OperationState.FAILED ||
            current.state == OperationState.CANCELLED
        )
            return current
        delay(250)
        current = runCatching { TorrmediaApi.operation(op.id) }.getOrNull() ?: return current
    }
    return current
}

fun addTorrent(torrentHash: String, torrentLink: String, torrentTitle: String, torrentPoster: String, torrentCategory: String, torrentData: String, torrentSave: Boolean): Torrent? {
    return if (torrentHash.isNotEmpty()) {
        try {
            Api.getTorrent(torrentHash)
        } catch (e: Exception) {
            null
        }
    } else if (torrentLink.isNotEmpty()) {
        val scheme = Uri.parse(torrentLink).scheme
        if (ContentResolver.SCHEME_CONTENT == scheme || ContentResolver.SCHEME_FILE == scheme) {
            val fis = App.context.contentResolver.openInputStream(Uri.parse(torrentLink))
            fis?.let {
                try {
                    Api.uploadTorrent(fis, torrentTitle, torrentPoster, torrentCategory, torrentData, torrentSave)
                } catch (e: Exception) {
                    null
                }
            }
        } else if ("torrs" == scheme) {
            //TODO parse torrshash
            Api.addTorrent(torrentLink, torrentTitle, torrentPoster, torrentCategory, torrentData, torrentSave)
        } else try {
            Api.addTorrent(torrentLink, torrentTitle, torrentPoster, torrentCategory, torrentData, torrentSave)
        } catch (e: Exception) {
            null
        }
    } else
        null
}