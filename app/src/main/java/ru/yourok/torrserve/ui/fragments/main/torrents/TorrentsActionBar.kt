package ru.yourok.torrserve.ui.fragments.main.torrents

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.view.ActionMode
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.widget.AbsListView
import android.widget.AbsListView.MultiChoiceModeListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import ru.yourok.torrserve.BuildConfig
import ru.yourok.torrserve.R
import ru.yourok.torrserve.app.App
import androidx.appcompat.app.AlertDialog
import ru.yourok.torrserve.server.api.Api
import ru.yourok.torrserve.server.api.ItemAction
import ru.yourok.torrserve.server.api.TorrmediaApi
import ru.yourok.torrserve.server.api.OperationState
import ru.yourok.torrserve.server.models.torrent.Torrent
import ru.yourok.torrserve.ui.activities.play.PlayActivity
import ru.yourok.torrserve.utils.TorrentHelper
import ru.yourok.torrserve.utils.TorrentHelper.getTorrentMagnet
import kotlin.concurrent.thread


class TorrentsActionBar(private val listView: AbsListView) : MultiChoiceModeListener {

    override fun onItemCheckedStateChanged(actionMode: ActionMode, i: Int, l: Long, b: Boolean) {
        when (val selectedCount = listView.checkedItemCount) {
            0 -> actionMode.subtitle = null
            else -> actionMode.title = selectedCount.toString()
        }
    }

    override fun onCreateActionMode(actionMode: ActionMode, menu: Menu?): Boolean {
        val inflater: MenuInflater = actionMode.menuInflater
        inflater.inflate(R.menu.torrents_action_menu, menu)
        return true
    }

    override fun onPrepareActionMode(actionMode: ActionMode?, menu: Menu?): Boolean {
        return false
    }

    override fun onActionItemClicked(actionMode: ActionMode, item: MenuItem): Boolean {
        val selected = selectedItems
        when (item.itemId) {
            R.id.itemOpenWith -> {
                val magnetUris = mutableListOf<Uri>()
                selected.filter { getTorrentMagnet(it).isNotEmpty() }.forEach {
                    val magnet = getTorrentMagnet(it)
                    if (magnet.isNotEmpty()) {
                        magnetUris.add(Uri.parse(magnet))
                    }
                }
                if (magnetUris.isNotEmpty()) {
                    val magnetIntent = Intent()
//                    if (magnetUris.size == 1) {
                    val uri = magnetUris.first()
                    magnetIntent.apply {
                        action = Intent.ACTION_VIEW
                        data = uri
                        addCategory(Intent.CATEGORY_DEFAULT)
                        addCategory(Intent.CATEGORY_BROWSABLE)
                    }
//                    } else {
//                        val arrayList = arrayListOf<Uri>()
//                        arrayList.addAll(magnetUris)
//                        magnetIntent.apply {
//                            action = Intent.ACTION_SEND_MULTIPLE
//                            putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayList)
//                            addCategory(Intent.CATEGORY_BROWSABLE)
//                        }
//                    }
                    val chooser = Intent.createChooser(magnetIntent, App.context.getString(R.string.open_with)).apply {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                            val excludedComponentNames = arrayOf(
                                ComponentName(BuildConfig.APPLICATION_ID, PlayActivity::class.java.name)
                            )
                            putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, excludedComponentNames)
                        }
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }

                    if (magnetIntent.resolveActivity(App.context.packageManager) != null) {
                        App.context.startActivity(chooser)
                    } else { // Handle the case where no activity can handle the intent
                        App.toast(R.string.error_app_not_found, true)
                    }

                }
            }

            R.id.itemShareMagnet -> {
                val msg = selected.filter { getTorrentMagnet(it).isNotEmpty() }.joinToString("\n\n") { getTorrentMagnet(it) }
                if (msg.isNotEmpty()) {
                    val share = Intent().apply {
                        action = Intent.ACTION_SEND
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, msg)
                    }

                    val shareIntent = Intent.createChooser(share, null)
                    shareIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    App.context.startActivity(shareIntent)
                }
            }

            R.id.itemCopyMagnet -> {
                val msg = selected.filter { getTorrentMagnet(it).isNotEmpty() }.joinToString("\n\n") { getTorrentMagnet(it) }
                if (msg.isNotEmpty()) {
                    val clipboard = App.context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = ClipData.newPlainText("magnets", msg)
                    clipboard.setPrimaryClip(clip)
                    App.toast(App.context.getString(R.string.copy_to_clipboard))
                }
            }

            R.id.itemRemoveTorrent -> {
                val remote = selected.filter { it.src != null }
                val local = selected.filter { it.src == null }
                if (remote.isNotEmpty()) {
                    AlertDialog.Builder(listView.context)
                        .setTitle(R.string.torrmedia_action_remove_with_data)
                        .setMessage(R.string.torrmedia_remove_with_data_confirm)
                        .setPositiveButton(android.R.string.ok) { _, _ ->
                            thread {
                                removeLocal(local)
                                remote.forEach { torr -> removeRemote(torr) }
                            }
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                } else {
                    thread { removeLocal(local) }
                }
            }

            R.id.itemRemoveViewed -> {
                thread {
                    selected.forEach {
                        try {
                            if (it.src == "torrmedia")
                                TorrmediaApi.clearViewedItem(it.hash)
                            else
                                Api.remViewed(it.hash)
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                }
            }

            R.id.itemShowInfo -> {
                selected.filter { it.src == null }.forEach {
                    CoroutineScope(Dispatchers.IO).launch {
                        val torrent: Torrent = TorrentHelper.waitFiles(it.hash) ?: let {
                            return@launch
                        }
                        TorrentHelper.showFFPInfo(listView.context, "", torrent)
                    }
                }
            }
        }
        actionMode.finish()
        return false
    }

    override fun onDestroyActionMode(actionMode: ActionMode?) {
    }

    private fun removeLocal(local: List<Torrent>) {
        local.forEach {
            try {
                Api.remTorrent(it.hash)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun removeRemote(torr: Torrent) {
        try {
            val withData = ItemAction.REMOVE_WITH_DATA in torr.torrmediaActions
            if (!withData && ItemAction.REMOVE !in torr.torrmediaActions) {
                App.toast(App.context.getString(R.string.torrmedia_action_unavailable), true)
                return
            }
            val op = TorrmediaApi.removeItem(torr.hash, withData)
            if (op.id.isNotEmpty())
                reportOperationOutcome(op.id)
        } catch (e: Exception) {
            App.toast(App.context.getString(R.string.qbit_send_error) + ": " + (e.message ?: ""), true)
        }
    }

    private fun reportOperationOutcome(id: String) {
        thread {
            val op = runCatching { TorrmediaApi.operation(id) }.getOrNull() ?: return@thread
            when (op.state) {
                OperationState.FAILED -> App.toast(
                    App.context.getString(R.string.torrmedia_op_failed) +
                        (op.error?.let { ": $it" } ?: ""), true
                )

                OperationState.DONE -> for (warning in op.warnings)
                    App.toast(warning, true)

                else -> {}
            }
        }
    }

    private val selectedItems: List<Torrent>
        get() {
            val selectedFiles: MutableList<Torrent> = ArrayList()
            val sparseBooleanArray = listView.checkedItemPositions
            for (i in 0 until sparseBooleanArray.size()) {
                if (sparseBooleanArray.valueAt(i)) {
                    val selectedItem = listView.getItemAtPosition(sparseBooleanArray.keyAt(i)) as Torrent?
                    if (selectedItem != null) {
                        selectedFiles.add(selectedItem)
                    }
                }
            }
            return selectedFiles
        }

}
