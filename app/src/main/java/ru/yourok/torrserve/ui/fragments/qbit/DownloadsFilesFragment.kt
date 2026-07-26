package ru.yourok.torrserve.ui.fragments.qbit

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
import android.content.res.ColorStateList
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.yourok.torrserve.R
import ru.yourok.torrserve.app.App
import ru.yourok.torrserve.ext.append
import ru.yourok.torrserve.server.api.TorrmediaApi
import ru.yourok.torrserve.server.api.TorrmediaWatch
import ru.yourok.torrserve.server.api.matchesShelfItem
import ru.yourok.torrserve.server.api.QbitApi
import ru.yourok.torrserve.server.api.QbitTorrent
import ru.yourok.torrserve.server.api.qbitHeaderStatusLabel
import ru.yourok.torrserve.server.api.qbitIsChecking
import ru.yourok.torrserve.server.api.qbitIsPaused
import ru.yourok.torrserve.server.api.qbitStateLabel
import ru.yourok.torrserve.settings.Settings
import ru.yourok.torrserve.ui.dialogs.PlayersDialog
import ru.yourok.torrserve.ui.fragments.TSFragment
import ru.yourok.torrserve.utils.Format
import ru.yourok.torrserve.utils.Mime
import ru.yourok.torrserve.utils.ThemeUtil
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class DownloadsFilesFragment : TSFragment() {

    companion object {
        const val MODE_MEDIA = "media"
        const val MODE_QBIT = "qbit"

        fun newMedia(path: String, title: String) = newInstance(MODE_MEDIA, path, title)
        fun newQbit(hash: String, title: String) = newInstance(MODE_QBIT, hash, title)

        private fun newInstance(mode: String, id: String, title: String): DownloadsFilesFragment {
            return DownloadsFilesFragment().apply {
                arguments = Bundle().apply {
                    putString("mode", mode)
                    putString("id", id)
                    putString("title", title)
                }
            }
        }
    }

    /// ENABLING/DELETING are optimistic states shown while the server applies the request
    private enum class State { DOWNLOADED, PROGRESS, ENABLING, DELETING, SKIPPED }

    private enum class HeaderAction(
        val textRes: Int,
        val iconRes: Int,
        val startsTransfer: Boolean,
    ) {
        START_DOWNLOAD(R.string.qbit_action_resume_download, R.drawable.ic_qbit_resume, true),
        STOP_DOWNLOAD(R.string.qbit_action_pause_download, R.drawable.ic_qbit_pause, false),
        STOP_GENERIC(R.string.qbit_action_pause, R.drawable.ic_qbit_pause, false),
        START_SEEDING(R.string.qbit_action_start_seeding, R.drawable.ic_qbit_resume, true),
        STOP_SEEDING(R.string.qbit_action_stop_seeding, R.drawable.ic_qbit_pause, false);

        fun opposite(): HeaderAction = when (this) {
            START_DOWNLOAD -> STOP_DOWNLOAD
            STOP_DOWNLOAD, STOP_GENERIC -> START_DOWNLOAD
            START_SEEDING -> STOP_SEEDING
            STOP_SEEDING -> START_SEEDING
        }
    }

    private data class Row(
        val name: String,
        val torrmediaPath: String, // torrmedia rel path (real when on disk, constructed otherwise)
        val size: Long,
        val pct: Int,
        val state: State,
        val qbitIndex: Int, // -1 when the torrent is gone from qBittorrent
        val label: String = "",
        val checking: Boolean = false, // torrent is being rehashed right now
        val watch: TorrmediaWatch = TorrmediaWatch.NONE,
        val pos: Long = 0,
    )

    private val filesAdapter = FilesAdapter()
    private var mode = MODE_MEDIA
    private var id = ""
    private var torrTitle = ""
    private var torrHash = "" // resolved live qBit torrent (if any)
    private var torrItemPath = "" // torrmedia item key, retained even for deleted/skipped files
    private var torrState = ""
    private var qbitLookupAvailable = false
    private val activeActions = AtomicInteger(0)
    private val busy: Boolean
        get() = activeActions.get() > 0 // keep optimistic controls while any request is in flight
    private var lastRows: List<Row> = emptyList()
    private val enabling = mutableSetOf<Int>() // file indexes tapped but not yet confirmed by qBit
    private val deleting = ConcurrentHashMap.newKeySet<Int>()
    private var lastOpenAt = 0L
    private var focusApplied = false
    private var pollJob: Job? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        mode = arguments?.getString("mode") ?: MODE_MEDIA
        id = arguments?.getString("id") ?: ""
        torrTitle = arguments?.getString("title") ?: ""
        return inflater.inflate(R.layout.qbit_files_fragment, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.findViewById<TextView>(R.id.tvQbitTorrTitle)?.text = torrTitle
        // pause/resume is wired in showInfo(): its label depends on the live torrent state
        view.findViewById<Button>(R.id.btnQbitDeleteTorrent)?.apply {
            TooltipCompat.setTooltipText(this, getString(R.string.qbit_delete_torrent))
            setOnClickListener {
                AlertDialog.Builder(requireContext())
                    .setTitle(R.string.qbit_delete_torrent)
                    .setMessage(R.string.qbit_delete_torrent_confirm)
                    .setPositiveButton(android.R.string.ok) { _, _ -> deleteAll() }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }
        view.findViewById<ListView>(R.id.lvQbitFiles)?.apply {
            adapter = filesAdapter
            setOnItemClickListener { _, _, position, _ ->
                val row = filesAdapter.getItem(position) as Row? ?: return@setOnItemClickListener
                when (row.state) {
                    State.DOWNLOADED -> {
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastOpenAt >= 1500) {
                            lastOpenAt = now
                            openExternal(row)
                        }
                    }

                    State.PROGRESS -> confirmCancelDownload(row)

                    State.SKIPPED -> resumeDownload(row)

                    State.ENABLING, State.DELETING -> {} // already on its way
                }
            }
            setOnItemLongClickListener { _, _, position, _ ->
                val row = filesAdapter.getItem(position) as Row? ?: return@setOnItemLongClickListener true
                when (row.state) {
                    State.DOWNLOADED -> showDownloadedMenu(row)

                    State.PROGRESS -> confirmCancelDownload(row)

                    State.SKIPPED, State.ENABLING, State.DELETING -> {}
                }
                true
            }
        }
    }

    override fun onResume() {
        super.onResume()
        pollJob = lifecycleScope.launch(Dispatchers.IO) {
            var first = true
            while (isActive) {
                load(quiet = !first)
                first = false
                delay(5000) // keep per-file progress fresh while the screen is open
            }
        }
    }

    override fun onPause() {
        pollJob?.cancel()
        pollJob = null
        super.onPause()
    }

    private suspend fun load(quiet: Boolean) {
        if (!quiet) showProgress()
        try {
            var torrmediaAvailable = true
            val torrmediaItems = try {
                TorrmediaApi.list()
            } catch (e: Exception) {
                torrmediaAvailable = false
                emptyList()
            }
            // qBit powers the "in progress"/"skipped" states — never swallow its failure silently
            var qbitProblem = ""
            val torrents = if (Settings.getQbitEnabled()) {
                try {
                    QbitApi.listTorrents().also { qbitLookupAvailable = true }
                } catch (e: Exception) {
                    qbitLookupAvailable = false
                    // include the dialed URL: separates "wrong address in settings" from "blocked port"
                    qbitProblem = "${e.message ?: e.toString()} @ ${Settings.getQbitUrl()}"
                    Log.e("QbitFork", "files screen: qbit list failed: $qbitProblem")
                    emptyList()
                }
            } else {
                qbitLookupAvailable = false
                qbitProblem = "qBittorrent is off in settings"
                emptyList()
            }

            val torrent: QbitTorrent? = when (mode) {
                MODE_QBIT -> torrents.find { it.hash == id }
                else -> torrents.find { matchesShelfItem(it.contentPath, id) }
            }
            if (torrent == null && qbitProblem.isEmpty() && torrents.isNotEmpty())
                qbitProblem = "no qBit torrent for \"$id\""
            if (!quiet && qbitProblem.isNotEmpty())
                App.toast("qBit: $qbitProblem", true)
            Log.i("QbitFork", "files screen: mode=$mode id=$id torrents=${torrents.size} matched=${torrent?.name ?: "-"}")
            torrHash = torrent?.hash ?: ""
            torrState = torrent?.state ?: ""
            val torrmediaItem = when (mode) {
                MODE_MEDIA -> torrmediaItems.find { it.path == id }
                else -> torrent?.let { t -> torrmediaItems.find { matchesShelfItem(t.contentPath, it.path) } }
            }
            val torrmediaFiles = torrmediaItem?.files ?: emptyList()
            val allTorrmediaFiles = torrmediaItems.flatMap { it.files }
            torrItemPath = when {
                torrmediaItem != null -> torrmediaItem.path
                mode == MODE_MEDIA -> id
                torrent != null -> {
                    val category = torrent.savePath.trimEnd('/').substringAfterLast('/')
                    val content = torrent.contentPath.trimEnd('/').substringAfterLast('/')
                    listOf(category, content).filter { it.isNotEmpty() }.joinToString("/")
                }
                else -> ""
            }
            val viewedByPath = if (torrItemPath.isNotEmpty()) {
                try {
                    TorrmediaApi.listViewed(torrItemPath).associateBy { it.path }
                } catch (e: Exception) {
                    emptyMap()
                }
            } else {
                emptyMap()
            }

            val rows = if (torrent != null) {
                val qFiles = QbitApi.getFiles(torrent.hash)
                val saveLeaf = torrent.savePath.trimEnd('/').substringAfterLast('/')
                val qRows = qFiles.map { f ->
                    // Automatic management normally produces shows/<qBit name>. A torrent
                    // previously added in manual mode can instead sit directly under the
                    // torrmedia root, so check both exact layouts.
                    val qbitName = f.name.trimStart('/')
                    val itemLeaf = torrItemPath.substringAfterLast('/')
                    val itemPath = when {
                        torrItemPath.isEmpty() -> ""
                        qbitName == itemLeaf || qbitName.startsWith("$itemLeaf/") ->
                            "$saveLeaf/$qbitName"
                        else -> "$torrItemPath/$qbitName"
                    }
                    val pathCandidates = listOf(itemPath, "$saveLeaf/$qbitName", qbitName)
                        .filter { it.isNotEmpty() }
                        .map { it.trimStart('/') }
                        .distinct()
                    val torrmediaMatch = torrmediaFiles.find { it.path.endsWith("/${f.name}") || it.path == f.name }
                        ?: allTorrmediaFiles.find { it.path in pathCandidates }
                    val viewedMatch = pathCandidates.firstNotNullOfOrNull { viewedByPath[it] }
                    // the shelf decides, not qBit's piece map (it calls deleted files complete until
                    // a recheck, docs/09) — but a file qBit just created is on disk yet still empty,
                    // so the size on disk must also have caught up with the expected one
                    val ready = torrmediaMatch != null && torrmediaMatch.size >= f.size
                    val state = when {
                        f.index in deleting && (f.priority != 0 || ready) -> State.DELETING
                        ready -> State.DOWNLOADED
                        f.priority == 0 -> {
                            deleting.remove(f.index)
                            if (f.index in enabling) State.ENABLING else State.SKIPPED
                        }
                        else -> State.PROGRESS
                    }
                    Row(
                        name = File(f.name).name,
                        torrmediaPath = torrmediaMatch?.path ?: pathCandidates.first(),
                        size = if (ready && torrmediaMatch != null) torrmediaMatch.size else f.size, // unfinished rows show the target size
                        pct = (f.progress * 100).toInt(),
                        state = state,
                        qbitIndex = f.index,
                        label = qbitStateLabel(torrent.state),
                        checking = qbitIsChecking(torrent.state),
                        watch = torrmediaMatch?.watchState ?: viewedMatch?.watch ?: TorrmediaWatch.NONE,
                        pos = torrmediaMatch?.pos ?: viewedMatch?.pos ?: 0,
                    )
                }
                // files that exist on the shelf but are unknown to the live torrent (edge case)
                val extras = torrmediaFiles.filter { mf -> qFiles.none { qf -> mf.path.endsWith("/${qf.name}") } }
                    .map {
                        Row(
                            it.name.ifBlank { File(it.path).name },
                            it.path,
                            it.size,
                            100,
                            State.DOWNLOADED,
                            -1,
                            watch = if (it.watchState != TorrmediaWatch.NONE) it.watchState
                            else viewedByPath[it.path]?.watch ?: TorrmediaWatch.NONE,
                            pos = it.pos.takeIf { pos -> pos > 0 } ?: viewedByPath[it.path]?.pos ?: 0,
                        )
                    }
                qRows + extras
            } else {
                // torrent is gone from qBittorrent — plain shelf view, everything playable
                torrmediaFiles.map {
                    Row(
                        it.name.ifBlank { File(it.path).name },
                        it.path,
                        it.size,
                        100,
                        State.DOWNLOADED,
                        -1,
                        watch = if (it.watchState != TorrmediaWatch.NONE) it.watchState
                        else viewedByPath[it.path]?.watch ?: TorrmediaWatch.NONE,
                        pos = it.pos.takeIf { pos -> pos > 0 } ?: viewedByPath[it.path]?.pos ?: 0,
                    )
                }
            }
            lastRows = rows
            withContext(Dispatchers.Main) {
                filesAdapter.update(rows)
                showInfo(torrent, rows)
                if (!focusApplied && torrmediaAvailable && rows.isNotEmpty()) {
                    val started = rows.indexOfFirst { it.watch == TorrmediaWatch.STARTED }
                    val untouched = rows.indexOfFirst { it.watch == TorrmediaWatch.NONE }
                    val position = when {
                        started >= 0 -> started
                        untouched >= 0 -> untouched
                        else -> 0
                    }
                    view?.findViewById<ListView>(R.id.lvQbitFiles)?.setSelection(position)
                    focusApplied = true
                }
            }
        } catch (e: Exception) {
            if (!quiet)
                App.toast(App.context.getString(R.string.qbit_send_error) + ": " + (e.message ?: ""), true)
        }
        if (!quiet) hideProgress()
    }

    /// Torrent card in the header, laid out like the TorrServer info card (docs/08 parity):
    /// every value comes from the same torrents/info call, no extra endpoints
    private fun showInfo(torrent: QbitTorrent?, rows: List<Row>) {
        val vi = view ?: return
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

        val done = rows.count { it.state == State.DOWNLOADED }
        val total = torrent?.size ?: rows.sumOf { it.size }
        // "completed" counts the selected files only; "downloaded" includes deselected leftovers
        // and can exceed the torrent size, which is where the >100% came from
        val ready = torrent?.completed ?: rows.filter { it.state == State.DOWNLOADED }.sumOf { it.size }
        val pct = ((torrent?.progress ?: 1.0) * 100).toInt().coerceIn(0, 100)
        val checking = torrent != null && qbitIsChecking(torrent.state)
        val inProgress = torrent != null && pct < 100 && !checking

        // headline: state · percent · bytes · files, state highlighted with the accent colour
        // qBit keeps "completed" and zero amount_left after a skipped file is switched back on
        val staleComplete = torrent != null && !checking && torrent.amountLeft == 0L &&
                rows.any { it.state == State.PROGRESS }
        val status = when {
            torrent == null -> getString(R.string.qbit_shelf_only)
            staleComplete -> getString(R.string.qbit_status_download_waiting_sources)
            else -> qbitHeaderStatusLabel(torrent.state)
        }
        vi.findViewById<TextView>(R.id.tvQbitSummary)?.apply {
            text = ""
            append(status.uppercase(Locale.getDefault()), accentColor, true)
            if (inProgress || checking) {
                // a torrent stuck just below 100% (boundary pieces of a deleted file) must not
                // read as a flat "99%" — one decimal shows it is really almost done
                val progress = torrent?.progress ?: 0.0
                val pctText = if (pct >= 99 && progress < 1.0) "%.1f%%".format(progress * 100) else "$pct%"
                append(" \u00B7 $pctText", valueColor, true)
            }
            // while rechecking, progress/completed describe the check itself — showing them as
            // "downloaded of total" reads like the data vanished, so only the percent is kept
            if (!checking) {
                append(" \u00B7 ", labelColor)
                val readyStr = Format.byteFmt(ready)
                val totalStr = Format.byteFmt(total)
                val left = torrent?.amountLeft ?: 0
                append(
                    when {
                        !inProgress -> totalStr
                        // "52,2 GB / 52,2 GB" tells nothing when only a few megabytes are missing
                        readyStr == totalStr && left > 0 -> getString(R.string.qbit_left_fmt, Format.byteFmt(left))
                        else -> "$readyStr / $totalStr"
                    },
                    valueColor, true
                )
            }
            append(" \u00B7 ", labelColor)
            append(getString(R.string.qbit_files_fmt, done, rows.size), valueColor)
        }
        vi.findViewById<LinearProgressIndicator>(R.id.pbQbitProgress)?.apply {
            if (inProgress || checking) { // a recheck has real progress too — show it
                visibility = View.VISIBLE
                setIndicatorColor(accentColor)
                setProgressCompat(pct, true)
            } else
                visibility = View.GONE
        }

        chip(
            R.id.tvQbitSpeed, getString(R.string.qbit_speed),
            torrent?.takeIf { it.dlspeed > 0 || it.upspeed > 0 }
                ?.let { "\u2193${Format.speedFmt(it.dlspeed.toDouble())} \u2191${Format.speedFmt(it.upspeed.toDouble())}" }
        )
        chip(R.id.tvQbitSeeds, getString(R.string.seeds), torrent?.let { "${it.numSeeds} (${it.numComplete})" })
        chip(R.id.tvQbitPeers, getString(R.string.peers), torrent?.let { "${it.numLeechs} (${it.numIncomplete})" })
        // qBit reports 8640000 (100 days) when the ETA is unknown
        chip(
            R.id.tvQbitEta, getString(R.string.qbit_eta),
            torrent?.takeIf { it.eta in 1 until 8640000 }?.let { Format.durFmtS(it.eta.toDouble()) }
        )
        chip(R.id.tvQbitCategory, getString(R.string.torrent_category), torrent?.category?.takeIf { it.isNotBlank() })
        chip(R.id.tvQbitAdded, getString(R.string.qbit_added), torrent?.addedOn?.takeIf { it > 0 }?.let { Format.sDateFmt(it) })

        if (busy) // an optimistic label is on screen, do not overwrite it mid-flight
            return
        // The main action names what it controls: downloading and seeding are
        // deliberately distinct. Processing/error states are not toggleable.
        vi.findViewById<MaterialButton>(R.id.btnQbitPause)?.apply {
            if (torrent == null) {
                visibility = View.GONE
                return@apply
            }
            visibility = View.VISIBLE
            // A re-enabled file can briefly coexist with qBit's old "complete"
            // upload state. In that recovery window the action must still start
            // downloading, not merely start seeding.
            val action = if (staleComplete && qbitIsPaused(torrent.state))
                HeaderAction.START_DOWNLOAD
            else
                headerActionFor(torrent.state)
            val blockedText = blockedActionText(torrent.state)
            setText(action?.textRes ?: blockedText)
            isEnabled = action != null
            if (action == null) {
                icon = null
                setOnClickListener(null)
            } else {
                setIconResource(action.iconRes)
                setOnClickListener { switchTransfer(action) }
            }
        }
        vi.findViewById<Button>(R.id.btnQbitDeleteTorrent)?.isEnabled =
            !checking && torrent?.state?.equals("moving", true) != true
    }

    /// Runs a qBit command with instant UI feedback: actions lock, the progress bar runs, and the
    /// state is re-read with backoff until `settled` confirms it — qBit applies commands with a lag.
    private fun runQbitAction(
        settled: (() -> Boolean)? = null,
        onFailure: (() -> Unit)? = null,
        action: suspend () -> Unit,
    ) {
        activeActions.incrementAndGet()
        lifecycleScope.launch(Dispatchers.IO) {
            setActionsEnabled(false)
            showProgress()
            try {
                action()
            } catch (e: Exception) {
                onFailure?.invoke()
                App.toast(App.context.getString(R.string.qbit_send_error) + ": " + (e.message ?: ""), true)
            }
            for (wait in longArrayOf(300, 500, 800, 1200)) {
                delay(wait)
                load(quiet = true)
                if (settled?.invoke() == true) break
            }
            enabling.clear()
            val lastAction = activeActions.decrementAndGet() == 0
            load(quiet = true) // the last one repaints buttons and rows with the real state
            if (lastAction) {
                hideProgress()
                setActionsEnabled(true)
            }
        }
    }

    private suspend fun setActionsEnabled(enabled: Boolean) = withContext(Dispatchers.Main) {
        view?.findViewById<Button>(R.id.btnQbitPause)?.isEnabled =
            enabled && headerActionFor(torrState) != null
        view?.findViewById<Button>(R.id.btnQbitDeleteTorrent)?.isEnabled =
            enabled && !qbitIsChecking(torrState) && !torrState.equals("moving", true)
    }

    private fun headerActionFor(state: String): HeaderAction? =
        when (state.lowercase(Locale.ROOT)) {
            "stoppeddl", "pauseddl" -> HeaderAction.START_DOWNLOAD
            "downloading", "forceddl", "stalleddl", "queueddl", "allocating" ->
                HeaderAction.STOP_DOWNLOAD
            "metadl" -> HeaderAction.STOP_GENERIC
            "stoppedup", "pausedup" -> HeaderAction.START_SEEDING
            "uploading", "forcedup", "stalledup", "queuedup" -> HeaderAction.STOP_SEEDING
            else -> null
        }

    private fun blockedActionText(state: String): Int =
        when {
            qbitIsChecking(state) -> R.string.qbit_action_checking
            state.equals("moving", true) -> R.string.qbit_action_moving
            else -> R.string.qbit_action_unavailable
        }

    private fun switchTransfer(action: HeaderAction) {
        val hash = torrHash
        if (hash.isEmpty())
            return
        // Flip to the expected opposite action immediately, while the API applies
        // the command and polling confirms the actual state.
        val optimistic = action.opposite()
        view?.findViewById<MaterialButton>(R.id.btnQbitPause)?.apply {
            setText(optimistic.textRes)
            setIconResource(optimistic.iconRes)
        }
        runQbitAction(settled = {
            headerActionFor(torrState)?.startsTransfer != action.startsTransfer
        }) {
            when (action) {
                HeaderAction.START_DOWNLOAD -> if (QbitApi.startTorrent(hash))
                    App.toast(R.string.qbit_stalled_warn, true)
                HeaderAction.START_SEEDING -> QbitApi.resumeTorrent(hash)
                HeaderAction.STOP_DOWNLOAD, HeaderAction.STOP_GENERIC, HeaderAction.STOP_SEEDING -> {
                    QbitApi.setForceStart(hash, false)
                    QbitApi.pauseTorrent(hash)
                }
            }
        }
    }

    private fun confirmCancelDownload(row: Row) {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.qbit_cancel_download)
            .setMessage("${row.name}\n\n${getString(R.string.qbit_cancel_download_confirm)}")
            .setPositiveButton(android.R.string.ok) { _, _ -> queueFileDelete(row) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showDownloadedMenu(row: Row) {
        val watchAction = if (row.watch == TorrmediaWatch.NONE)
            getString(R.string.qbit_mark_watched)
        else
            getString(R.string.qbit_remove_watched)
        AlertDialog.Builder(requireContext())
            .setTitle(row.name)
            .setItems(arrayOf(watchAction, getString(R.string.qbit_delete_file))) { _, which ->
                when (which) {
                    0 -> toggleViewed(row)
                    1 -> confirmDeleteFile(row)
                }
            }
            .show()
    }

    private fun confirmDeleteFile(row: Row) {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.qbit_delete_file)
            .setMessage("${row.name}\n\n${getString(R.string.qbit_delete_file_confirm)}")
            .setPositiveButton(android.R.string.ok) { _, _ -> deleteFile(row) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun toggleViewed(row: Row) {
        lifecycleScope.launch(Dispatchers.IO) {
            showProgress()
            try {
                if (row.watch == TorrmediaWatch.NONE)
                    TorrmediaApi.setViewed(row.torrmediaPath)
                else
                    TorrmediaApi.remViewed(row.torrmediaPath)
                load(quiet = true)
            } catch (e: Exception) {
                App.toast(App.context.getString(R.string.qbit_send_error) + ": " + (e.message ?: ""), true)
            }
            hideProgress()
        }
    }

    private fun resumeDownload(row: Row) {
        val hash = torrHash
        if (hash.isEmpty() || row.qbitIndex < 0)
            return
        if (qbitIsChecking(torrState)) { // qBit would ignore the request anyway — say so at once
            App.toast(R.string.qbit_resume_failed, true)
            return
        }
        // paint the row as "enabling" before the first request leaves the device
        enabling.add(row.qbitIndex)
        filesAdapter.update(lastRows.map { if (it.qbitIndex == row.qbitIndex) it.copy(state = State.ENABLING) else it })
        runQbitAction(settled = { lastRows.find { it.qbitIndex == row.qbitIndex }?.state == State.PROGRESS }) {
            when (QbitApi.enableFile(hash, row.qbitIndex)) {
                QbitApi.EnableResult.STARTED -> App.toast(R.string.qbit_resume_started)
                QbitApi.EnableResult.STALLED -> App.toast(R.string.qbit_stalled_warn, true)
                QbitApi.EnableResult.BUSY_CHECKING -> App.toast(R.string.qbit_resume_failed, true)
            }
        }
    }

    private fun deleteFile(row: Row) {
        queueFileDelete(row)
    }

    private fun queueFileDelete(row: Row) {
        val hash = torrHash
        val exactQbitFile = hash.isNotEmpty() && row.qbitIndex >= 0
        if (exactQbitFile) {
            deleting.add(row.qbitIndex)
            lastRows = lastRows.map {
                if (it.qbitIndex == row.qbitIndex) it.copy(state = State.DELETING) else it
            }
            filesAdapter.update(lastRows)
        }
        runQbitAction(
            settled = {
                if (exactQbitFile)
                    lastRows.find { it.qbitIndex == row.qbitIndex }?.state == State.SKIPPED
                else
                    lastRows.none { it.torrmediaPath == row.torrmediaPath && it.state == State.DOWNLOADED }
            },
            onFailure = {
                if (exactQbitFile)
                    deleting.remove(row.qbitIndex)
            },
        ) {
            if (exactQbitFile)
                TorrmediaApi.deleteTorrentFile(hash, row.qbitIndex)
            else
                TorrmediaApi.deleteFile(row.torrmediaPath)
            App.toast(R.string.qbit_deleted)
        }
    }

    /// live torrent -> one qBit call (with data); shelf-only item -> file-by-file via torrmedia
    private fun deleteAll() {
        lifecycleScope.launch(Dispatchers.IO) {
            showProgress()
            try {
                val itemPath = torrItemPath.ifEmpty { if (mode == MODE_MEDIA) id else "" }
                if (torrHash.isNotEmpty()) {
                    QbitApi.deleteTorrent(torrHash, true)
                } else {
                    if (Settings.getQbitEnabled() && !qbitLookupAvailable)
                        throw IllegalStateException("qBittorrent lookup is unavailable")
                    val item = TorrmediaApi.list().find { it.path == id }
                    item?.files?.forEach { TorrmediaApi.deleteFile(it.path) }
                }
                if (itemPath.isNotEmpty())
                    TorrmediaApi.remViewedItem(itemPath)
                App.toast(R.string.qbit_deleted)
                withContext(Dispatchers.Main) {
                    parentFragmentManager.popBackStack()
                }
            } catch (e: Exception) {
                App.toast(App.context.getString(R.string.qbit_send_error) + ": " + (e.message ?: ""), true)
            }
            hideProgress()
        }
    }

    private fun openExternal(row: Row) {
        val url = try {
            TorrmediaApi.playUrl(row.torrmediaPath)
        } catch (e: Exception) {
            App.toast(R.string.torrmedia_not_set, true)
            return
        }
        val intent = Intent(Intent.ACTION_VIEW)
        intent.setDataAndType(Uri.parse(url), Mime.getMimeType(row.name))
        // Keep explicit player titles in addition to the filename carried by the play URL.
        // MX reads "title", Vimu reads "forcename".
        val epTitle = File(row.name).nameWithoutExtension
        intent.putExtra("title", epTitle)
        intent.putExtra("forcename", epTitle)
        intent.putExtra("forcedirect", true)
        intent.putExtra("forceresume", true)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val player = Settings.getPlayer()
        if (player.isEmpty()) {
            // the app's native picker (icons + "make default" switch), same as the TorrServer flow
            PlayersDialog.show(requireContext(), intent) { pkg ->
                startPlayer(intent, pkg)
            }
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
        private var rows: List<Row> = emptyList()

        @SuppressLint("NotifyDataSetChanged")
        fun update(list: List<Row>) {
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
                visibility = if (row.watch == TorrmediaWatch.NONE) View.GONE else View.VISIBLE
                alpha = if (row.watch == TorrmediaWatch.STARTED) 0.45f else 1.0f
            }
            // ready episodes carry a green chip (theme colour) instead of a check mark
            val chipColor = if (row.state == State.DOWNLOADED)
                ThemeUtil.getColorFromAttr(themedContext, R.attr.colorDone)
            else
                colorBright
            vi.findViewById<TextView>(R.id.tvFileSize)?.apply {
                text = when (row.state) {
                    // a rechecked episode keeps its green chip and stays playable,
                    // the check progress is just shown next to the size
                    State.DOWNLOADED ->
                        if (row.checking && row.pct < 100)
                            "${Format.byteFmt(row.size)} ● ${row.label} ${row.pct}%"
                        else {
                            val playbackPosition = if (row.watch == TorrmediaWatch.STARTED && row.size > 0 && row.pos > 0) {
                                val percent = (row.pos.toDouble() * 100 / row.size.toDouble()).toInt().coerceIn(1, 99)
                                " ● ≈$percent%"
                            } else ""
                            Format.byteFmt(row.size) + playbackPosition
                        }
                    State.PROGRESS ->
                        if (row.label.isEmpty()) "${Format.byteFmt(row.size)} ● ${row.pct}%"
                        else "${Format.byteFmt(row.size)} ● ${row.label} ${row.pct}%"
                    State.ENABLING -> "${Format.byteFmt(row.size)} ● ${vi.context.getString(R.string.qbit_enabling)}"
                    State.DELETING -> "${Format.byteFmt(row.size)} ● ${vi.context.getString(R.string.qbit_deleting)}"
                    State.SKIPPED -> "${Format.byteFmt(row.size)} ● ${vi.context.getString(R.string.qbit_skipped)}"
                }
                val shapeDrawable = MaterialShapeDrawable(shapeAppearanceModel)
                shapeDrawable.fillColor = ColorStateList.valueOf(chipColor).withAlpha(240)
                shapeDrawable.setStroke(2.0f, ColorStateList.valueOf(chipColor).withAlpha(255))
                background = shapeDrawable
                setTextColor(ThemeUtil.getColorFromAttr(themedContext, R.attr.colorSurface))
            }
            vi.alpha = when (row.state) {
                State.DOWNLOADED -> 1.0f
                State.PROGRESS, State.ENABLING, State.DELETING -> 0.6f
                State.SKIPPED -> 0.4f
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
