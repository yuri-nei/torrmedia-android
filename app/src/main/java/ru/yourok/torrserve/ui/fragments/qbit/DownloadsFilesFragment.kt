package ru.yourok.torrserve.ui.fragments.qbit

import android.annotation.SuppressLint
import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.view.ContextThemeWrapper
import androidx.appcompat.widget.TooltipCompat
import androidx.core.graphics.ColorUtils
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.shape.CornerFamily
import com.google.android.material.shape.MaterialShapeDrawable
import com.google.android.material.shape.ShapeAppearanceModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.yourok.torrserve.R
import ru.yourok.torrserve.app.App
import ru.yourok.torrserve.ext.append
import ru.yourok.torrserve.server.api.FileAction
import ru.yourok.torrserve.server.api.FileStatus
import ru.yourok.torrserve.server.api.Item
import ru.yourok.torrserve.server.api.ItemAction
import ru.yourok.torrserve.server.api.ItemStatus
import ru.yourok.torrserve.server.api.MediaFile
import ru.yourok.torrserve.server.api.MediaState
import ru.yourok.torrserve.server.api.TorrmediaApi
import ru.yourok.torrserve.server.api.TorrmediaStore
import ru.yourok.torrserve.server.api.Operation
import ru.yourok.torrserve.server.api.OperationState
import ru.yourok.torrserve.server.api.fileMenuActions
import ru.yourok.torrserve.server.api.primaryTransferAction
import ru.yourok.torrserve.server.api.removalActions
import ru.yourok.torrserve.server.api.WatchState
import ru.yourok.torrserve.settings.Settings
import ru.yourok.torrserve.ui.dialogs.PlayersDialog
import ru.yourok.torrserve.ui.fragments.TSFragment
import ru.yourok.torrserve.utils.Format
import ru.yourok.torrserve.utils.Mime
import ru.yourok.torrserve.utils.ThemeUtil
import java.io.File
import java.util.Locale

class DownloadsFilesFragment : TSFragment() {

    companion object {
        fun newInstance(item: String, title: String): DownloadsFilesFragment =
            DownloadsFilesFragment().apply {
                arguments = Bundle().apply {
                    putString("item", item)
                    putString("title", title)
                }
            }
    }

    private enum class OpKind { GENERIC, REMOVE }

    private val filesAdapter = FilesAdapter()
    private var itemId = ""
    private var itemTitle = ""
    private lateinit var store: TorrmediaStore<Item>

    private val pending = LinkedHashMap<String, OpKind>()
    private var phase = ""

    private var offline = false
    private var lastOpenAt = 0L
    private var focusApplied = false

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        itemId = arguments?.getString("item") ?: ""
        itemTitle = arguments?.getString("title") ?: ""
        store = TorrmediaStore.item(itemId)
        return inflater.inflate(R.layout.qbit_files_fragment, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.findViewById<TextView>(R.id.tvQbitTorrTitle)?.text = itemTitle
        view.findViewById<Button>(R.id.btnQbitDeleteTorrent)?.let {
            TooltipCompat.setTooltipText(it, getString(R.string.torrmedia_action_remove))
        }
        view.findViewById<Button>(R.id.btnQbitRecheck)?.let {
            TooltipCompat.setTooltipText(it, getString(R.string.torrmedia_action_recheck))
        }
        view.findViewById<ListView>(R.id.lvQbitFiles)?.apply {
            adapter = filesAdapter
            setOnItemClickListener { _, _, position, _ ->
                (filesAdapter.getItem(position) as MediaFile?)?.let { onFileClick(it) }
            }
            setOnItemLongClickListener { _, _, position, _ ->
                (filesAdapter.getItem(position) as MediaFile?)?.let { showFileMenu(it) }
                true
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            store.state.collect { render(it) }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            store.operations.collect { onOperation(it) }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            store.connected.collect { live ->
                val restored = offline && live
                offline = !live
                render(store.state.value)
                if (restored)
                    reconcilePending()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        store.start(viewLifecycleOwner.lifecycleScope)
    }

    override fun onPause() {
        store.stop()
        super.onPause()
    }


    private fun render(state: MediaState<Item>) {
        val vi = view ?: return
        when (state) {
            is MediaState.Loading -> {
                filesAdapter.update(emptyList())
                showStatus(getString(R.string.torrmedia_loading))
                hideControls(vi)
            }

            is MediaState.Failed -> {
                filesAdapter.update(emptyList())
                showStatus(state.message)
                hideControls(vi)
            }

            is MediaState.Removed -> {
                filesAdapter.update(emptyList())
                hideControls(vi)
                if (isAdded) {
                    App.toast(R.string.torrmedia_item_gone)
                    parentFragmentManager.popBackStack()
                }
            }

            is MediaState.Ready -> renderItem(vi, state.data)
        }
    }

    private fun hideControls(vi: View) {
        vi.findViewById<LinearProgressIndicator>(R.id.pbQbitProgress)?.visibility = View.GONE
        vi.findViewById<View>(R.id.btnQbitPause)?.visibility = View.GONE
        vi.findViewById<View>(R.id.btnQbitRecheck)?.visibility = View.GONE
        vi.findViewById<View>(R.id.btnQbitDeleteTorrent)?.visibility = View.GONE
        for (id in intArrayOf(R.id.tvQbitSpeed, R.id.tvQbitSeeds, R.id.tvQbitPeers, R.id.tvQbitEta, R.id.tvQbitCategory, R.id.tvQbitAdded))
            vi.findViewById<View>(id)?.visibility = View.GONE
    }

    private fun showStatus(text: String) {
        val themedContext = ContextThemeWrapper(App.context, ThemeUtil.selectedTheme)
        val accentColor = ThemeUtil.getColorFromAttr(themedContext, R.attr.colorPrimary)
        view?.findViewById<TextView>(R.id.tvQbitSummary)?.apply {
            this.text = ""
            append(text, accentColor, true)
        }
    }

    private fun renderItem(vi: View, item: Item) {
        val files = item.files ?: emptyList()
        filesAdapter.update(files)
        if (item.title.isNotBlank())
            vi.findViewById<TextView>(R.id.tvQbitTorrTitle)?.text = item.title

        val themedContext = ContextThemeWrapper(App.context, ThemeUtil.selectedTheme)
        val labelColor = ColorUtils.setAlphaComponent(ThemeUtil.getColorFromAttr(themedContext, R.attr.colorBright), 180)
        val valueColor = ColorUtils.setAlphaComponent(ThemeUtil.getColorFromAttr(themedContext, R.attr.colorBright), 240)
        val accentColor = ThemeUtil.getColorFromAttr(themedContext, R.attr.colorPrimary)

        fun chip(id: Int, label: String, value: String?) {
            vi.findViewById<TextView>(id)?.apply {
                if (value.isNullOrEmpty()) {
                    visibility = View.GONE
                    return@apply
                }
                visibility = View.VISIBLE
                text = ""
                append("$label ", labelColor)
                append(value, valueColor, true)
            }
        }

        val pct = (item.progress * 100).toInt().coerceIn(0, 100)
        val running = pct < 100 || item.status == ItemStatus.CHECKING
        vi.findViewById<TextView>(R.id.tvQbitSummary)?.apply {
            text = ""
            if (offline)
                append(getString(R.string.torrmedia_disconnected).uppercase(Locale.getDefault()) + " · ", labelColor, true)
            append(itemStatusLabel(item.status).uppercase(Locale.getDefault()), accentColor, true)
            if (phase.isNotEmpty())
                append(" · $phase", accentColor)
            if (running) {
                val pctText = if (pct >= 99 && item.progress < 1.0) "%.1f%%".format(item.progress * 100) else "$pct%"
                append(" · $pctText", valueColor, true)
            }
            append(" · ", labelColor)
            append(
                if (running) "${Format.byteFmt(item.downloaded)} / ${Format.byteFmt(item.size)}"
                else Format.byteFmt(item.size),
                valueColor, true
            )
            append(" · ", labelColor)
            append(getString(R.string.qbit_files_fmt, item.filesReady, item.filesTotal), valueColor)
        }
        vi.findViewById<LinearProgressIndicator>(R.id.pbQbitProgress)?.apply {
            if (running) {
                visibility = View.VISIBLE
                setIndicatorColor(accentColor)
                setProgressCompat(pct, true)
            } else
                visibility = View.GONE
        }

        val transfer = item.transfer
        chip(
            R.id.tvQbitSpeed, getString(R.string.qbit_speed),
            transfer?.takeIf { it.down > 0 || it.up > 0 }
                ?.let { "↓${Format.speedFmt(it.down.toDouble())} ↑${Format.speedFmt(it.up.toDouble())}" }
        )
        chip(R.id.tvQbitSeeds, getString(R.string.seeds), transfer?.seeds?.toString())
        chip(R.id.tvQbitPeers, getString(R.string.peers), transfer?.peers?.toString())
        chip(R.id.tvQbitEta, getString(R.string.qbit_eta), transfer?.eta?.takeIf { it > 0 }?.let { Format.durFmtS(it.toDouble()) })
        chip(R.id.tvQbitCategory, getString(R.string.torrent_category), item.category?.takeIf { it.isNotBlank() })
        chip(R.id.tvQbitAdded, getString(R.string.qbit_added), item.addedAt.takeIf { it > 0 }?.let { Format.sDateFmt(it) })

        vi.findViewById<MaterialButton>(R.id.btnQbitPause)?.apply {
            val action = primaryTransferAction(item.actions)
            if (action == null) {
                visibility = View.GONE
                setOnClickListener(null)
            } else {
                visibility = View.VISIBLE
                text = itemActionLabel(action)
                setIconResource(if (action == ItemAction.START) R.drawable.ic_qbit_resume else R.drawable.ic_qbit_pause)
                setOnClickListener {
                    runOperation(OpKind.GENERIC) {
                        if (action == ItemAction.START) TorrmediaApi.startItem(itemId)
                        else TorrmediaApi.stopItem(itemId)
                    }
                }
            }
        }
        vi.findViewById<MaterialButton>(R.id.btnQbitRecheck)?.apply {
            if (ItemAction.RECHECK in item.actions) {
                visibility = View.VISIBLE
                setOnClickListener { runOperation(OpKind.GENERIC) { TorrmediaApi.recheckItem(itemId) } }
            } else {
                visibility = View.GONE
                setOnClickListener(null)
            }
        }
        vi.findViewById<MaterialButton>(R.id.btnQbitDeleteTorrent)?.apply {
            val removals = removalActions(item.actions)
            if (removals.isEmpty()) {
                visibility = View.GONE
                setOnClickListener(null)
            } else {
                visibility = View.VISIBLE
                setOnClickListener { confirmRemove(removals) }
            }
        }

        if (!focusApplied && files.isNotEmpty()) {
            val started = files.indexOfFirst { it.watch == WatchState.STARTED }
            val untouched = files.indexOfFirst { it.watch == WatchState.NONE }
            val position = when {
                started >= 0 -> started
                untouched >= 0 -> untouched
                else -> 0
            }
            vi.findViewById<ListView>(R.id.lvQbitFiles)?.setSelection(position)
            focusApplied = true
        }
    }


    private fun onFileClick(file: MediaFile) {
        when {
            FileAction.PLAY in file.actions -> {
                val now = SystemClock.elapsedRealtime()
                if (now - lastOpenAt >= 1500) {
                    lastOpenAt = now
                    openExternal(file)
                }
            }

            FileAction.ENABLE in file.actions ->
                runOperation(OpKind.GENERIC) { TorrmediaApi.enableFile(itemId, file.id) }

            else -> App.toast(fileStatusLabel(file.status), true)
        }
    }

    private fun showFileMenu(file: MediaFile) {
        val actions = fileMenuActions(file.actions)
        if (actions.isEmpty()) {
            App.toast(fileStatusLabel(file.status), true)
            return
        }
        val titles = actions.map { fileActionLabel(it) }.toTypedArray()
        AlertDialog.Builder(requireContext())
            .setTitle(file.name)
            .setItems(titles) { _, which -> runFileAction(file, actions[which]) }
            .show()
    }

    private fun runFileAction(file: MediaFile, action: FileAction) {
        when (action) {
            FileAction.PLAY -> openExternal(file)

            FileAction.DELETE -> AlertDialog.Builder(requireContext())
                .setTitle(R.string.torrmedia_action_delete)
                .setMessage("${file.name}\n\n${getString(R.string.qbit_delete_file_confirm)}")
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    runOperation(OpKind.GENERIC) { TorrmediaApi.deleteFile(itemId, file.id) }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()

            FileAction.ENABLE -> runOperation(OpKind.GENERIC) { TorrmediaApi.enableFile(itemId, file.id) }

            FileAction.SET_VIEWED -> runViewed { TorrmediaApi.setViewed(itemId, file.id, WatchState.WATCHED) }

            FileAction.CLEAR_VIEWED -> runViewed { TorrmediaApi.clearViewed(itemId, file.id) }
        }
    }

    private fun confirmRemove(removals: List<ItemAction>) {
        if (removals.size == 1) {
            val action = removals[0]
            AlertDialog.Builder(requireContext())
                .setTitle(itemActionLabel(action))
                .setMessage(if (action == ItemAction.REMOVE_WITH_DATA) R.string.torrmedia_remove_with_data_confirm else R.string.torrmedia_remove_confirm)
                .setPositiveButton(android.R.string.ok) { _, _ -> remove(action) }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            return
        }
        val titles = removals.map { itemActionLabel(it) }.toTypedArray()
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.torrmedia_action_remove)
            .setItems(titles) { _, which -> confirmRemove(listOf(removals[which])) }
            .show()
    }

    private fun remove(action: ItemAction) {
        runOperation(OpKind.REMOVE) {
            TorrmediaApi.removeItem(itemId, action == ItemAction.REMOVE_WITH_DATA)
        }
    }

    private fun runOperation(kind: OpKind, block: suspend () -> Operation) {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            showProgress()
            try {
                val op = block()
                withContext(Dispatchers.Main) {
                    if (op.id.isNotEmpty()) {
                        pending[op.id] = kind
                        phase = operationPhaseLabel(op.phase)
                    }
                }
                if (op.id.isNotEmpty())
                    reconcile(op.id)
            } catch (e: Exception) {
                App.toast(App.context.getString(R.string.qbit_send_error) + ": " + (e.message ?: ""), true)
                withContext(Dispatchers.Main) { syncProgress() }
            }
        }
    }

    private fun runViewed(block: suspend () -> Unit) {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                block()
            } catch (e: Exception) {
                App.toast(App.context.getString(R.string.qbit_send_error) + ": " + (e.message ?: ""), true)
            }
        }
    }

    private fun reconcile(id: String) {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val op = runCatching { TorrmediaApi.operation(id) }.getOrNull() ?: return@launch
            withContext(Dispatchers.Main) { onOperation(op) }
        }
    }

    private fun reconcilePending() {
        for (id in pending.keys.toList())
            reconcile(id)
    }

    private fun onOperation(op: Operation) {
        val kind = pending[op.id] ?: return
        when (op.state) {
            OperationState.QUEUED, OperationState.RUNNING -> {
                phase = operationPhaseLabel(op.phase)
                render(store.state.value)
            }

            OperationState.DONE -> {
                pending.remove(op.id)
                phase = ""
                App.toast(R.string.torrmedia_op_done)
                showWarnings(op.warnings)
                syncProgress()
                if (kind == OpKind.REMOVE && isAdded)
                    parentFragmentManager.popBackStack()
            }

            OperationState.CANCELLED -> {
                pending.remove(op.id)
                phase = ""
                render(store.state.value)
            }

            OperationState.FAILED -> {
                pending.remove(op.id)
                phase = ""
                App.toast(
                    App.context.getString(R.string.torrmedia_op_failed) + (op.error?.let { ": $it" } ?: ""), true
                )
                showWarnings(op.warnings)
                syncProgress()
            }

            OperationState.UNKNOWN -> {}
        }
    }

    private fun syncProgress() {
        viewLifecycleOwner.lifecycleScope.launch {
            if (pending.isEmpty()) hideProgress() else showProgress()
        }
    }

    private fun showWarnings(warnings: List<String>) {
        if (warnings.isNotEmpty())
            App.toast(warnings.joinToString("\n"), true)
    }

    private fun openExternal(file: MediaFile) {
        val url = try {
            TorrmediaApi.streamUrl(itemId, file.id, file.name)
        } catch (e: Exception) {
            App.toast(R.string.torrmedia_not_set, true)
            return
        }
        val intent = Intent(Intent.ACTION_VIEW)
        intent.setDataAndType(Uri.parse(url), Mime.getMimeType(file.name))
        // Keep explicit player titles in addition to the filename carried by the play URL.
        // MX reads "title", Vimu reads "forcename".
        val epTitle = File(file.name).nameWithoutExtension
        intent.putExtra("title", epTitle)
        intent.putExtra("forcename", epTitle)
        intent.putExtra("forcedirect", true)
        intent.putExtra("forceresume", true)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val player = Settings.getPlayer()
        if (player.isEmpty()) {
            // the app's native picker (icons + "make default" switch), same as the TorrServer flow
            PlayersDialog.show(requireContext(), intent) { pkg -> startPlayer(intent, pkg) }
        } else
            startPlayer(intent, player)
    }

    private fun startPlayer(intent: Intent, player: String) {
        if (player.isNotEmpty() && player != "0") {
            intent.`package` = player
            if (intent.resolveActivity(App.context.packageManager) == null)
                intent.`package` = null
        }
        try {
            App.context.startActivity(intent)
        } catch (e: Exception) {
            App.toast(R.string.error_app_not_found, true)
        }
    }

    private class FilesAdapter : BaseAdapter() {
        private var rows: List<MediaFile> = emptyList()

        @SuppressLint("NotifyDataSetChanged")
        fun update(list: List<MediaFile>) {
            if (rows != list) {
                rows = list
                notifyDataSetChanged()
            }
        }

        @SuppressLint("SetTextI18n")
        override fun getView(position: Int, view: View?, parent: ViewGroup?): View {
            val vi = view ?: LayoutInflater.from(parent?.context).inflate(R.layout.qbit_files_item, parent, false)
            val row = rows[position]

            // styled after TorrentFilesAdapter: bright file name, chip-shaped size label
            val themedContext = ContextThemeWrapper(App.context, ThemeUtil.selectedTheme)
            val colorBright = ThemeUtil.getColorFromAttr(themedContext, R.attr.colorBright)
            val nameColor = ColorUtils.setAlphaComponent(colorBright, 250)
            val radius = Format.dp2px(2.0f).toFloat()
            val shapeAppearanceModel = ShapeAppearanceModel()
                .toBuilder()
                .setAllCorners(CornerFamily.ROUNDED, radius)
                .build()

            vi.findViewById<TextView>(R.id.tvFileName)?.apply {
                text = ""
                append(row.name, nameColor)
            }
            vi.findViewById<ImageView>(R.id.ivViewed)?.apply {
                visibility = if (row.watch == WatchState.NONE) View.GONE else View.VISIBLE
                alpha = if (row.watch == WatchState.STARTED) 0.45f else 1.0f
            }
            val chipColor = if (row.status == FileStatus.READY)
                ThemeUtil.getColorFromAttr(themedContext, R.attr.colorDone)
            else
                colorBright
            vi.findViewById<TextView>(R.id.tvFileSize)?.apply {
                val size = Format.byteFmt(row.size)
                text = when (row.status) {
                    FileStatus.READY -> {
                        val resume = if (row.watch == WatchState.STARTED && row.size > 0 && row.position > 0) {
                            val percent = (row.position.toDouble() * 100 / row.size.toDouble()).toInt().coerceIn(1, 99)
                            " ● ≈$percent%"
                        } else ""
                        size + resume
                    }

                    FileStatus.PARTIAL -> "$size ● ${(row.progress * 100).toInt()}%"
                    else -> "$size ● ${fileStatusLabel(row.status)}"
                }
                val shapeDrawable = MaterialShapeDrawable(shapeAppearanceModel)
                shapeDrawable.fillColor = ColorStateList.valueOf(chipColor).withAlpha(240)
                shapeDrawable.setStroke(2.0f, ColorStateList.valueOf(chipColor).withAlpha(255))
                background = shapeDrawable
                setTextColor(ThemeUtil.getColorFromAttr(themedContext, R.attr.colorSurface))
            }
            vi.alpha = when (row.status) {
                FileStatus.READY -> 1.0f
                FileStatus.SKIPPED -> 0.4f
                else -> 0.6f
            }
            return vi
        }

        override fun getItem(p0: Int): Any? {
            if (p0 < 0 || p0 >= rows.size)
                return null
            return rows[p0]
        }

        override fun getItemId(p0: Int): Long = p0.toLong()

        override fun getCount(): Int = rows.size
    }
}
