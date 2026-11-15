package ru.yourok.torrserve.ui.fragments.qbit

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.CheckBox
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.graphics.ColorUtils
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.yourok.torrserve.R
import ru.yourok.torrserve.app.App
import ru.yourok.torrserve.ext.append
import ru.yourok.torrserve.server.api.Draft
import ru.yourok.torrserve.server.api.DraftFile
import ru.yourok.torrserve.server.api.DraftStatus
import ru.yourok.torrserve.server.api.TorrmediaErrorCode
import ru.yourok.torrserve.server.api.MediaState
import ru.yourok.torrserve.server.api.TorrmediaApi
import ru.yourok.torrserve.server.api.TorrmediaStore
import ru.yourok.torrserve.server.api.missingBytes
import ru.yourok.torrserve.server.api.selectedBytes
import ru.yourok.torrserve.settings.Settings
import ru.yourok.torrserve.ui.activities.main.MainActivity
import ru.yourok.torrserve.ui.fragments.TSFragment
import ru.yourok.torrserve.utils.Format
import ru.yourok.torrserve.utils.ThemeUtil
import kotlin.concurrent.thread

/**
 * The add-item window: the torrent's file list before the download starts.
 *
 * A magnet link does not carry the file list; only the network knows it. So the server
 * creates a draft: the torrent is added to qBittorrent, fetches the metadata and is paused
 * without downloading anything. All this time it is not in the library, and discarding
 * removes it together with whatever has already landed on disk.
 *
 * The screen decides nothing for the server: the file list, the preselection and the free
 * space arrive in the draft snapshot. The only state of its own here is which checkboxes
 * the user cleared.
 */
class AddTorrentFragment : TSFragment() {

    companion object {
        fun newInstance(magnet: String): AddTorrentFragment =
            AddTorrentFragment().apply {
                arguments = Bundle().apply { putString("magnet", magnet) }
            }
    }

    private val filesAdapter = FilesAdapter()
    private var magnet = ""
    private var draftId = ""
    private var store: TorrmediaStore<Draft>? = null
    private var draft: Draft? = null

    /// Selected files. Initialized from the server's preselection exactly once: the next
    /// snapshot must not bring back checkboxes the user cleared.
    private var selected: MutableSet<String>? = null
    private var category = ""
    private var categories: List<String> = emptyList()

    /// The item is created (or the draft is already discarded): nothing to discard on close.
    private var settled = false
    private var closed = false

    /// "Download and open": once the item exists, show its screen in the main window
    private var openAfter = false

    /// What to do when finished: close the window. Our activity is a dialog, and there is
    /// nowhere to go back to after adding.
    var onDone: (() -> Unit)? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        magnet = arguments?.getString("magnet") ?: ""
        return inflater.inflate(R.layout.qbit_add_fragment, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.findViewById<TextView>(R.id.tvAddTitle)?.text = getString(R.string.torrmedia_add_resolving)
        view.findViewById<ListView>(R.id.lvAddFiles)?.apply {
            adapter = filesAdapter
            setOnItemClickListener { _, _, position, _ ->
                (filesAdapter.getItem(position) as DraftFile?)?.let { toggle(it) }
            }
        }
        view.findViewById<MaterialButton>(R.id.btnAddSelectAll)?.setOnClickListener { selectAll() }
        view.findViewById<View>(R.id.rowAddCategory)?.setOnClickListener { chooseCategory() }
        view.findViewById<MaterialButton>(R.id.btnAddDownload)?.setOnClickListener { download(false) }
        view.findViewById<MaterialButton>(R.id.btnAddOpen)?.setOnClickListener { download(true) }
        view.findViewById<MaterialButton>(R.id.btnAddCancel)?.setOnClickListener { cancel() }

        loadCategories()
        createDraft()
    }

    override fun onDestroy() {
        // The user left without confirming: the draft keeps a live torrent in qBittorrent,
        // and there is no point waiting for the server timeout to remove it. The work runs
        // on a plain thread: lifecycleScope is already cancelled by this point.
        val id = draftId
        if (!settled && id.isNotEmpty())
            thread { runCatching { TorrmediaApi.discardDraft(id) } }
        super.onDestroy()
    }

    // --- draft --------------------------------------------------------------

    private fun createDraft() {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            showProgress()
            try {
                // No category on creation: it can be chosen later, and an abandoned
                // draft should not get the chance to leave a trace anywhere.
                val created = TorrmediaApi.createDraft(magnet, "")
                withContext(Dispatchers.Main) {
                    if (!isAdded)
                        return@withContext
                    draftId = created.id
                    render(created)
                    val opened = TorrmediaStore.draft(created.id)
                    store = opened
                    viewLifecycleOwner.lifecycleScope.launch {
                        opened.state.collect { onState(it) }
                    }
                    opened.start(viewLifecycleOwner.lifecycleScope)
                }
            } catch (e: Exception) {
                hideProgress()
                fail(e.message ?: e.toString())
            }
        }
    }

    private fun loadCategories() {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val list = runCatching { TorrmediaApi.categories() }.getOrNull() ?: return@launch
            withContext(Dispatchers.Main) {
                categories = list
                // The last chosen category is preset if it still exists on the
                // server: the user changes the category list, not us.
                val remembered = Settings.getTorrmediaCategory()
                if (category.isEmpty() && remembered in list)
                    category = remembered
                renderCurrent()
            }
        }
    }

    private fun onState(state: MediaState<Draft>) {
        when (state) {
            is MediaState.Loading -> {}
            is MediaState.Removed -> fail(getString(R.string.torrmedia_add_failed))
            // The draft is gone (the service was restarted, or it expired): nothing to
            // wait for, reconnecting to an object that does not exist is pointless.
            is MediaState.Failed ->
                if (state.fatal || state.code == TorrmediaErrorCode.NOT_FOUND) fail(state.message)
            is MediaState.Ready -> render(state.data)
        }
    }

    private fun render(current: Draft) {
        // the confirmation and the stream's snapshot both report CONFIRMED: act on it once
        if (closed)
            return
        draft = current
        if (selected == null && current.files.isNotEmpty())
            selected = current.files.filter { it.wanted }.map { it.id }.toMutableSet()
        if (current.category != null && category.isEmpty())
            category = current.category

        when (current.status) {
            DraftStatus.CONFIRMED -> {
                settled = true
                val item = current.item
                if (openAfter && !item.isNullOrEmpty())
                    openItem(item, current.title)
                else
                    App.toast(R.string.torrmedia_add_sent)
                close()
                return
            }

            DraftStatus.FAILED -> {
                settled = true // the server already removed the torrent, or it was never created
                fail(current.error ?: getString(R.string.torrmedia_add_failed))
                return
            }

            DraftStatus.DISCARDED, DraftStatus.DISCARDING -> {
                settled = true
                close()
                return
            }

            else -> {}
        }
        renderCurrent()
    }

    private fun renderCurrent() {
        val vi = view ?: return
        val current = draft ?: return
        val chosen = selected ?: emptySet<String>()
        val busy = current.status != DraftStatus.READY

        vi.findViewById<TextView>(R.id.tvAddTitle)?.text = when {
            current.title.isNotEmpty() -> current.title
            else -> getString(R.string.torrmedia_add_resolving)
        }
        filesAdapter.update(current.files, chosen)

        val themedContext = ContextThemeWrapper(App.context, ThemeUtil.selectedTheme)
        val labelColor = ColorUtils.setAlphaComponent(
            ThemeUtil.getColorFromAttr(themedContext, R.attr.colorBright), 180
        )
        val valueColor = ColorUtils.setAlphaComponent(
            ThemeUtil.getColorFromAttr(themedContext, R.attr.colorBright), 240
        )
        val accentColor = ThemeUtil.getColorFromAttr(themedContext, R.attr.colorPrimary)
        val alertColor = ThemeUtil.getColorFromAttr(themedContext, R.attr.colorError)

        vi.findViewById<TextView>(R.id.tvAddSummary)?.apply {
            text = ""
            if (busy) {
                append(statusLabel(current.status), accentColor, true)
                append(" · ", labelColor)
            }
            append(
                getString(R.string.torrmedia_add_selected_fmt, chosen.size, current.files.size),
                valueColor, true
            )
            append(" · ", labelColor)
            append(Format.byteFmt(selectedBytes(current.files, chosen)), valueColor, true)
            val free = current.storage.free
            if (free != null) {
                append(" · ", labelColor)
                val total = current.storage.total
                append(
                    if (total != null && total > 0)
                        getString(R.string.torrmedia_add_free_fmt, Format.byteFmt(free), Format.byteFmt(total))
                    else
                        getString(R.string.torrmedia_add_free_short_fmt, Format.byteFmt(free)),
                    valueColor
                )
            }
            val missing = missingBytes(current.files, chosen, current.storage) ?: 0
            if (missing > 0) {
                append(" · ", labelColor)
                append(getString(R.string.torrmedia_add_missing_fmt, Format.byteFmt(missing)), alertColor, true)
            }
        }

        vi.findViewById<View>(R.id.rowAddCategory)?.apply {
            isEnabled = !busy && categories.isNotEmpty()
            isFocusable = isEnabled
            alpha = if (isEnabled) 1.0f else 0.5f
        }
        vi.findViewById<TextView>(R.id.tvAddCategoryValue)?.text =
            if (category.isEmpty()) getString(R.string.torrmedia_add_category_none) else category
        vi.findViewById<MaterialButton>(R.id.btnAddSelectAll)?.apply {
            isEnabled = !busy && current.files.isNotEmpty()
            text = if (chosen.size == current.files.size && current.files.isNotEmpty())
                getString(R.string.torrmedia_add_select_none) else getString(R.string.torrmedia_add_select_all)
        }
        // An empty selection means "add for later": the item is created stopped, and the
        // wanted episodes are enabled later from its screen. So the button is always enabled.
        vi.findViewById<MaterialButton>(R.id.btnAddDownload)?.isEnabled = !busy
        vi.findViewById<MaterialButton>(R.id.btnAddOpen)?.isEnabled = !busy

        viewLifecycleOwner.lifecycleScope.launch {
            if (busy) showProgress() else hideProgress()
        }
    }

    private fun statusLabel(status: DraftStatus): String = when (status) {
        DraftStatus.RESOLVING -> getString(R.string.torrmedia_add_resolving)
        DraftStatus.CONFIRMING -> getString(R.string.torrmedia_add_starting)
        DraftStatus.DISCARDING -> getString(R.string.torrmedia_add_cancelling)
        else -> getString(R.string.torrmedia_loading)
    }

    // --- selection ----------------------------------------------------------

    private fun toggle(file: DraftFile) {
        val chosen = selected ?: return
        if (!chosen.remove(file.id))
            chosen.add(file.id)
        renderCurrent()
    }

    private fun selectAll() {
        val current = draft ?: return
        val chosen = selected ?: return
        if (chosen.size == current.files.size)
            chosen.clear()
        else
            chosen.addAll(current.files.map { it.id })
        renderCurrent()
    }

    private fun chooseCategory() {
        if (categories.isEmpty())
            return
        val titles = (listOf(getString(R.string.torrmedia_add_category_none)) + categories).toTypedArray()
        val checked = categories.indexOf(category) + 1
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.torrmedia_add_category)
            .setSingleChoiceItems(titles, checked) { dialog, which ->
                category = if (which == 0) "" else categories[which - 1]
                Settings.setTorrmediaCategory(category)
                dialog.dismiss()
                renderCurrent()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // --- confirm and discard ------------------------------------------------

    private fun download(open: Boolean) {
        val current = draft ?: return
        // Nothing selected: the item is created stopped and there is nothing to download,
        // so there is no space to check and nothing to confirm here.
        val chosen = selected?.toList().orEmpty()
        // The server does not refuse for lack of space, on purpose: the user decides.
        // But silently starting a download that will not fit would be dishonest.
        val missing = missingBytes(current.files, chosen.toSet(), current.storage) ?: 0
        if (missing > 0) {
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.torrmedia_add_download)
                .setMessage(getString(R.string.torrmedia_add_overflow_confirm, Format.byteFmt(missing)))
                .setPositiveButton(android.R.string.ok) { _, _ -> confirm(chosen, open) }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            return
        }
        confirm(chosen, open)
    }

    private fun confirm(files: List<String>, open: Boolean) {
        val id = draftId
        if (id.isEmpty())
            return
        openAfter = open
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            showProgress()
            try {
                val updated = TorrmediaApi.confirmDraft(id, files, category)
                withContext(Dispatchers.Main) { if (isAdded) render(updated) }
            } catch (e: Exception) {
                hideProgress()
                withContext(Dispatchers.Main) {
                    App.toast(
                        App.context.getString(R.string.torrmedia_add_failed) + ": " + (e.message ?: ""), true
                    )
                }
            }
        }
    }

    private fun cancel() {
        if (closed)
            return
        val id = draftId
        settled = true // we send the discard ourselves, no need to repeat it in onDestroy
        if (id.isNotEmpty())
            thread { runCatching { TorrmediaApi.discardDraft(id) } }
        close()
    }

    /// The new item's screen in the main window: no need to leave the other app and find it by hand
    private fun openItem(item: String, title: String) {
        val intent = Intent(App.context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(MainActivity.EXTRA_TORRMEDIA_ITEM, item)
            .putExtra(MainActivity.EXTRA_TORRMEDIA_TITLE, title)
        App.context.startActivity(intent)
    }

    /// Show the reason and leave. Also called from a background thread, so closing the
    /// window is moved to the main thread.
    private fun fail(message: String) {
        App.toast(App.context.getString(R.string.torrmedia_add_failed) + ": " + message, true)
        close()
    }

    private fun close() {
        if (closed)
            return
        closed = true
        store?.stop()
        val activity = activity ?: return
        activity.runOnUiThread {
            lifecycleScope.launch { hideProgress() }
            onDone?.invoke()
        }
    }

    // --- list ---------------------------------------------------------------

    private class FilesAdapter : BaseAdapter() {
        private var rows: List<DraftFile> = emptyList()
        private var chosen: Set<String> = emptySet()

        @SuppressLint("NotifyDataSetChanged")
        fun update(list: List<DraftFile>, selected: Set<String>) {
            if (rows != list || chosen != selected) {
                rows = list
                chosen = selected.toSet()
                notifyDataSetChanged()
            }
        }

        override fun getView(position: Int, view: View?, parent: ViewGroup?): View {
            val vi = view ?: LayoutInflater.from(parent?.context)
                .inflate(R.layout.qbit_add_item, parent, false)
            val row = rows[position]

            val themedContext = ContextThemeWrapper(App.context, ThemeUtil.selectedTheme)
            val colorBright = ThemeUtil.getColorFromAttr(themedContext, R.attr.colorBright)
            val wanted = row.id in chosen

            vi.findViewById<CheckBox>(R.id.cbFileWanted)?.isChecked = wanted
            vi.findViewById<TextView>(R.id.tvFileName)?.apply {
                text = ""
                // Show the whole path: torrents can contain folders, and "sample.mkv"
                // from extras must be told apart from an episode with the same name.
                append(row.path.ifEmpty { row.name }, ColorUtils.setAlphaComponent(colorBright, 250))
            }
            vi.findViewById<TextView>(R.id.tvFileSize)?.text = Format.byteFmt(row.size)
            vi.alpha = if (wanted) 1.0f else 0.5f
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
