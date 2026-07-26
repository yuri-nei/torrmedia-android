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
import ru.yourok.torrserve.server.api.QbitApi
import ru.yourok.torrserve.server.models.torrent.Torrent
import ru.yourok.torrserve.settings.Settings
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

/// torrmedia: pick a category, push the link to remote qBittorrent and close (no player)
fun PlayActivity.qbitAndExit() {
    lifecycleScope.launch(Dispatchers.IO) {
        showProgress()
        val categories = QbitApi.getCategories()
        hideProgress()
        withContext(Dispatchers.Main) {
            val default = Settings.getQbitCategory()
            if (categories.isEmpty()) {
                // category list unavailable — send with the default one, no extra questions
                qbitSendAndExit(default)
            } else {
                var checked = categories.indexOf(default).coerceAtLeast(0)
                AlertDialog.Builder(this@qbitAndExit)
                    .setTitle(R.string.qbit_select_category)
                    .setSingleChoiceItems(categories.toTypedArray(), checked) { _, which -> checked = which }
                    .setPositiveButton(android.R.string.ok) { _, _ -> qbitSendAndExit(categories[checked]) }
                    .setNegativeButton(android.R.string.cancel) { _, _ -> finish() }
                    .setOnCancelListener { finish() }
                    .show()
            }
        }
    }
}

private fun PlayActivity.qbitSendAndExit(category: String) {
    lifecycleScope.launch(Dispatchers.IO) {
        showProgress()
        try {
            QbitApi.addTorrent(torrentLink, category)
            App.toast(R.string.qbit_sent)
        } catch (e: Exception) {
            App.toast(App.context.getString(R.string.qbit_send_error) + ": " + (e.message ?: ""), true)
        }
        withContext(Dispatchers.Main) { finish() }
    }
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