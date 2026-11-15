package ru.yourok.torrserve.ui.fragments.qbit

import android.annotation.SuppressLint
import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.text.SpannableStringBuilder
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.Animation
import android.view.animation.AnimationUtils
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.view.ContextThemeWrapper
import androidx.appcompat.widget.TooltipCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.isGone
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.shape.CornerFamily
import com.google.android.material.shape.MaterialShapeDrawable
import com.google.android.material.shape.ShapeAppearanceModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.yourok.torrserve.R
import ru.yourok.torrserve.app.App
import ru.yourok.torrserve.ext.appendStyled
import ru.yourok.torrserve.ext.setTextIfChanged
import ru.yourok.torrserve.server.api.FileAction
import ru.yourok.torrserve.server.api.FilePriority
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
import ru.yourok.torrserve.server.api.itemMenuActions
import ru.yourok.torrserve.server.api.nextEpisodeIndex
import ru.yourok.torrserve.server.api.primaryTransferAction
import ru.yourok.torrserve.server.api.watchedToDelete
import ru.yourok.torrserve.server.api.WatchState
import ru.yourok.torrserve.settings.Settings
import ru.yourok.torrserve.ui.dialogs.PlayersDialog
import ru.yourok.torrserve.ui.fragments.TSFragment
import ru.yourok.torrserve.ui.widgets.PatchedListView
import ru.yourok.torrserve.utils.Format
import ru.yourok.torrserve.utils.Mime
import ru.yourok.torrserve.utils.ThemeUtil
import java.io.File
import java.util.Locale

/**
 * Files screen of a single item. The server sends the whole state over the
 * /items/{id}/events stream: there is no polling, merging or optimistic substitution here — a
 * snapshot arrives, it gets drawn. Before the first snapshot the header can be drawn from the
 * live row of the shared list (seed) — that is a server snapshot too, just without files.
 *
 * A button is shown if and only if its action is in the actions of the received object. The
 * screen draws no conclusions of its own about "is this allowed": an ambiguous file, a recheck
 * in progress, a playing video and an unavailable qBittorrent all look the same — the server
 * simply did not put the action in the list.
 */
class DownloadsFilesFragment : TSFragment() {

    companion object {
        /// How long the stream may take to come back up after returning to the screen before the
        /// header says "disconnected". On a TV reconnecting takes ~0.3 s.
        private const val RECONNECT_GRACE_MS = 2_000L

        /// The enter animation lasts ~0.3 s; if its end never arrives, the episodes are laid out
        /// after this timeout
        private const val ENTER_FALLBACK_MS = 1_000L

        fun newInstance(
            item: String,
            title: String,
            seed: Item? = null,
            prewarm: DetailsPrewarm? = null,
        ): DownloadsFilesFragment =
            DownloadsFilesFragment().apply {
                arguments = Bundle().apply {
                    putString("item", item)
                    putString("title", title)
                }
                this.seed = seed
                this.prewarm = prewarm
            }
    }

    /// Why we wait for an operation: the result of remove closes the screen, the rest just reports
    private enum class OpKind { GENERIC, REMOVE }

    private val filesAdapter = FilesAdapter()
    private var itemId = ""
    private var itemTitle = ""
    private lateinit var store: TorrmediaStore<Item>

    /// operations started by this screen (id -> why). Only for showing progress and outcome:
    /// the state arrives as a snapshot anyway.
    private val pending = LinkedHashMap<String, OpKind>()
    private var phase = ""

    /// the stream is currently broken: the snapshot on screen is still the best one known, but it
    /// must not be passed off as live — keeping quiet about a lost connection is lying about state
    private var offline = false
    private var lastOpenAt = 0L
    private var focusApplied = false
    private var filesShown = false
    private var reconnectCheck: Job? = null

    /// The item from the shared list's snapshot — the row that was just pressed: same server and
    /// same freshness, only the files are missing. While the item's stream is coming up (a few
    /// hundred milliseconds on a TV), the header is drawn from it — otherwise the screen would sit
    /// with just a title and "Loading", and then the header would grow by three rows and push the
    /// list down. The item's first snapshot replaces it. It does not survive the screen being
    /// recreated: then it is a normal load.
    private var seed: Item? = null

    /// Screen view and rows inflated ahead of time on the items list; picked up in onCreateView
    private var prewarm: DetailsPrewarm? = null

    /// Header colors. The theme does not change during the screen's lifetime, while a
    /// ContextThemeWrapper and attribute resolution on every snapshot is needless main-thread work.
    private val colors by lazy { HeaderColors() }

    private class HeaderColors {
        private val themed = ContextThemeWrapper(App.context, ThemeUtil.selectedTheme)
        private val bright = ThemeUtil.getColorFromAttr(themed, R.attr.colorBright)
        val label = ColorUtils.setAlphaComponent(bright, 180)
        val value = ColorUtils.setAlphaComponent(bright, 240)
        val accent = ThemeUtil.getColorFromAttr(themed, R.attr.colorPrimary)
    }

    /// The enter animation is running. Episodes are not laid out meanwhile (see render).
    private var entering = false
    private var renderDeferred = false

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        itemId = arguments?.getString("item") ?: ""
        itemTitle = arguments?.getString("title") ?: ""
        store = TorrmediaStore.item(itemId)
        val warm = prewarm?.take(inflater)
        prewarm = null
        if (warm != null) {
            filesAdapter.addSpareRows(warm.second)
            return warm.first
        }
        return inflater.inflate(R.layout.qbit_files_fragment, container, false)
    }

    /**
     * The enter animation is our own only to catch the moment it ends. The first layout of the
     * episode list costs ~100 ms of main thread on a TV, and if a snapshot arrived in the middle
     * of the screen fading in, the animation froze on a semi-transparent frame. The header is
     * already drawn by the time the animation starts (from the list row, or "Loading"), and the
     * episodes are laid out once the screen has faded in.
     * An animation that does not run to the end (the screen was closed midway) gets a fallback
     * timer. Its timeout is a generous constant: computeDurationHint() of an AnimationSet that has
     * not started yet returns 0 (durations are handed to the children only in initialize), and a
     * timer based on it cleared the flag right after startOffset — in the middle of the fade-in.
     */
    override fun onCreateAnimation(transit: Int, enter: Boolean, nextAnim: Int): Animation? {
        if (!enter || nextAnim == 0)
            return null
        val anim = AnimationUtils.loadAnimation(requireContext(), nextAnim)
        entering = true
        anim.setAnimationListener(object : Animation.AnimationListener {
            override fun onAnimationStart(animation: Animation) {}
            override fun onAnimationRepeat(animation: Animation) {}
            override fun onAnimationEnd(animation: Animation) = finishEntering()
        })
        view?.postDelayed({ finishEntering() }, ENTER_FALLBACK_MS)
        return anim
    }

    private fun finishEntering() {
        if (!entering)
            return
        entering = false
        if (renderDeferred) {
            renderDeferred = false
            render(store.state.value)
        }
    }

    /// The remote was on a row of the main list, and that row goes away with the old screen. If
    /// focus is not handed to anyone at that moment, Android gives it to the first focusable view
    /// in the window — the side menu header on the left — and it stays lit until the item's data
    /// arrives and focus lands on an episode. To keep focus from leaving the screen, the root of
    /// this screen holds it until the data arrives: with no highlight and only while nothing inside
    /// is focusable (afterDescendants), so afterwards it takes no part in remote navigation.
    /// Fragments support requestFocus from onViewCreated out of the box: the view is already in
    /// the container, and focus is restored after the transition animation.
    /// Nothing happens with touch: the root cannot take focus in touch mode.
    private fun holdFocus(root: View) {
        if (root !is ViewGroup)
            return
        root.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        root.isFocusable = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            root.defaultFocusHighlightEnabled = false
        root.requestFocus()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        focusApplied = false
        filesShown = false
        view.findViewById<TextView>(R.id.tvQbitTorrTitle)?.text = itemTitle
        view.findViewById<Button>(R.id.btnQbitDeleteWatched)?.let {
            TooltipCompat.setTooltipText(it, getString(R.string.torrmedia_action_delete_watched))
        }
        view.findViewById<Button>(R.id.btnQbitMore)?.let {
            TooltipCompat.setTooltipText(it, getString(R.string.torrmedia_action_more))
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
        holdFocus(view)

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
                // The screen also closes the stream itself: on pause (player, dialog) and when
                // going back. That is not a disconnect, and "disconnected" in the header would be a
                // false alarm on the last frames of leaving and until the reconnect after the
                // player. The offline mark is set anyway: it drives reconciling what was missed,
                // and if the stream never comes back up, the check from onResume will show
                // "disconnected".
                if (live || isResumed)
                    render(store.state.value)
                // Events that arrived during the disconnect never reached us — reconcile the
                // outcome of what was started before it.
                if (restored)
                    reconcilePending()
            }
        }
        // Start the stream right away, without waiting for onResume: on a TV noticeable time passes
        // between the two, and meanwhile the screen sits without files. A repeated start from
        // onResume does nothing, and after a pause (player, dialog) it brings the stream back up.
        store.start(viewLifecycleOwner.lifecycleScope)
    }

    override fun onResume() {
        super.onResume()
        store.start(viewLifecycleOwner.lifecycleScope)
        // While the stream is coming back up, "disconnected" is not shown (see the connected
        // collector). If it has not come up within a couple of seconds, that is a real disconnect
        // and the header must say so.
        reconnectCheck?.cancel()
        reconnectCheck = viewLifecycleOwner.lifecycleScope.launch {
            delay(RECONNECT_GRACE_MS)
            if (offline && isResumed)
                render(store.state.value)
        }
    }

    override fun onPause() {
        reconnectCheck?.cancel()
        store.stop()
        super.onPause()
    }

    override fun onDestroyView() {
        // the new view will start a new stream, and by then the list snapshot can no longer be
        // trusted
        seed = null
        prewarm = null
        entering = false
        renderDeferred = false
        super.onDestroyView()
    }

    // --- rendering ----------------------------------------------------------

    private fun render(state: MediaState<Item>) {
        val vi = view ?: return
        if (entering) {
            renderDeferred = true
            return
        }
        when (state) {
            is MediaState.Loading -> {
                val early = seed
                if (early != null) {
                    // header from the list row; files wait for their own stream (files == null)
                    renderItem(vi, early, preview = true)
                    return
                }
                // no state yet: an empty list here would falsely claim the item is empty
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
                // The item is gone: showing its files and buttons would be a lie.
                filesAdapter.update(emptyList())
                hideControls(vi)
                if (isAdded) {
                    App.toast(R.string.torrmedia_item_gone)
                    parentFragmentManager.popBackStack()
                }
            }

            is MediaState.Ready -> {
                seed = null
                renderItem(vi, state.data)
            }
        }
    }

    /// Header buttons are built from actions and disappear right under the remote's focus: while
    /// an operation runs the server allows nothing on the item, and after watched episodes are
    /// cleared the delete-watched button simply has nothing to do. Android gives the focus of a
    /// hidden view to the first focusable view in the window, i.e. the left menu, and the remote
    /// ends up on the other edge of the screen. So focus is moved into the episode list in
    /// advance — onto the same "next episode" as when the screen opens: after clearing watched
    /// episodes, that is the one to watch. An empty list does not take focus — then Android's
    /// behaviour stays.
    private fun View.hideKeepingFocus(vi: View, files: List<MediaFile>) {
        if (isGone)
            return
        if (hasFocus()) {
            val list = vi.findViewById<PatchedListView>(R.id.lvQbitFiles)
            if (list != null && list.requestFocus() && files.isNotEmpty())
                list.setSelectionShowingPrevious(nextEpisodeIndex(files))
        }
        visibility = View.GONE
    }

    private fun hideControls(vi: View) {
        vi.findViewById<LinearProgressIndicator>(R.id.pbQbitProgress)?.visibility = View.GONE
        for (id in intArrayOf(R.id.btnQbitTransfer, R.id.btnQbitDeleteWatched, R.id.btnQbitMore))
            vi.findViewById<View>(id)?.hideKeepingFocus(vi, emptyList())
        for (id in intArrayOf(R.id.tvQbitSpeed, R.id.tvQbitSeeds, R.id.tvQbitPeers, R.id.tvQbitEta, R.id.tvQbitCategory, R.id.tvQbitAdded))
            vi.findViewById<View>(id)?.visibility = View.GONE
    }

    private fun showStatus(text: String) {
        view?.findViewById<TextView>(R.id.tvQbitSummary)
            ?.setTextIfChanged(SpannableStringBuilder().appendStyled(text, colors.accent, bold = true))
    }

    /// preview — header from the list row (seed), until the item's own snapshot arrives.
    private fun renderItem(vi: View, item: Item, preview: Boolean = false) {
        val files = item.files ?: emptyList()
        filesAdapter.update(files)
        if (files.isNotEmpty() && !filesShown) {
            // The header is already in place and the list arrives after it — fade it in rather than
            // dropping it onto the screen whole in a single frame.
            filesShown = true
            vi.findViewById<View>(R.id.lvQbitFiles)?.apply {
                alpha = 0f
                animate().alpha(1f).setDuration(150).start()
            }
        }
        if (item.title.isNotBlank())
            vi.findViewById<TextView>(R.id.tvQbitTorrTitle)?.setTextIfChanged(item.title)

        val colors = colors

        fun chip(id: Int, label: String, value: String?) {
            vi.findViewById<TextView>(id)?.apply {
                if (value.isNullOrEmpty()) {
                    visibility = View.GONE
                    return@apply
                }
                visibility = View.VISIBLE
                setTextIfChanged(
                    SpannableStringBuilder()
                        .appendStyled("$label ", colors.label)
                        .appendStyled(value, colors.value, bold = true)
                )
            }
        }

        val pct = (item.progress * 100).toInt().coerceIn(0, 100)
        val running = pct < 100 || item.status == ItemStatus.CHECKING
        val summary = SpannableStringBuilder()
        // in preview the item's stream is still coming up — that is not a disconnect, and the list
        // row we draw from is live (otherwise seed would not have been handed over)
        if (offline && !preview)
            summary.appendStyled(getString(R.string.torrmedia_disconnected).uppercase(Locale.getDefault()) + " · ", colors.label, bold = true)
        summary.appendStyled(itemStatusLabel(item.status).uppercase(Locale.getDefault()), colors.accent, bold = true)
        if (phase.isNotEmpty())
            summary.appendStyled(" · $phase", colors.accent)
        if (running) {
            // an item stuck near 100% must not read as a flat "99%"
            val pctText = if (pct >= 99 && item.progress < 1.0) "%.1f%%".format(item.progress * 100) else "$pct%"
            summary.appendStyled(" · $pctText", colors.value, bold = true)
        }
        summary.appendStyled(" · ", colors.label)
        summary.appendStyled(
            if (running) "${Format.byteFmt(item.downloaded)} / ${Format.byteFmt(item.size)}"
            else Format.byteFmt(item.size),
            colors.value, bold = true
        )
        summary.appendStyled(" · ", colors.label)
        summary.appendStyled(getString(R.string.qbit_files_fmt, item.filesReady, item.filesTotal), colors.value)
        vi.findViewById<TextView>(R.id.tvQbitSummary)?.setTextIfChanged(summary)
        vi.findViewById<LinearProgressIndicator>(R.id.pbQbitProgress)?.apply {
            if (running) {
                visibility = View.VISIBLE
                setIndicatorColor(colors.accent)
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

        // Buttons are built strictly from actions. Nothing of the "maybe it is allowed" kind here.
        vi.findViewById<MaterialButton>(R.id.btnQbitTransfer)?.apply {
            val action = primaryTransferAction(item.actions)
            if (action == null) {
                hideKeepingFocus(vi, files)
                setOnClickListener(null)
            } else {
                visibility = View.VISIBLE
                setTextIfChanged(itemActionLabel(action))
                // the icon is a vector: load it and re-lay out the button only if it changed
                val icon = if (action == ItemAction.START) R.drawable.ic_qbit_resume else R.drawable.ic_qbit_pause
                if (getTag(R.id.btnQbitTransfer) != icon) {
                    setIconResource(icon)
                    setTag(R.id.btnQbitTransfer, icon)
                }
                setOnClickListener {
                    runOperation(OpKind.GENERIC) {
                        if (action == ItemAction.START) TorrmediaApi.startItem(itemId)
                        else TorrmediaApi.stopItem(itemId)
                    }
                }
            }
        }
        // The frequent action while watching a series is on a button; removing the item, rare and
        // irreversible, is in the "more" menu. The button shows how many episodes will go.
        vi.findViewById<MaterialButton>(R.id.btnQbitDeleteWatched)?.apply {
            if (ItemAction.DELETE_WATCHED !in item.actions) {
                hideKeepingFocus(vi, files)
                setOnClickListener(null)
            } else {
                visibility = View.VISIBLE
                val count = watchedToDelete(files)
                val label = if (count > 0) count.toString() else ""
                if (text.toString() != label) {
                    text = label
                    iconPadding = if (count > 0) Format.dp2px(6.0f) else 0
                }
                setOnClickListener { confirmDeleteWatched() }
            }
        }
        vi.findViewById<MaterialButton>(R.id.btnQbitMore)?.apply {
            val menu = itemMenuActions(item.actions)
            if (menu.isEmpty()) {
                hideKeepingFocus(vi, files)
                setOnClickListener(null)
            } else {
                visibility = View.VISIBLE
                setOnClickListener { showItemMenu(menu) }
            }
        }
        // In preview the buttons are visible but cannot be selected with the remote. Until the
        // snapshot, focus is held by the screen root (holdFocus), and after the enter animation
        // AndroidX gives it back via requestFocus on the root — which would pass it to the first
        // focusable button, "Start", instead of the episode to watch next.
        for (id in intArrayOf(R.id.btnQbitTransfer, R.id.btnQbitDeleteWatched, R.id.btnQbitMore))
            vi.findViewById<View>(id)?.isFocusable = !preview

        if (!focusApplied && files.isNotEmpty()) {
            vi.findViewById<PatchedListView>(R.id.lvQbitFiles)?.let { list ->
                // The focus the screen root held until the data arrived (holdFocus) moves to the
                // episode to watch next. If the remote was moved to something real in the
                // meantime (say, to "Stop"), leave it alone.
                val focused = vi.findFocus()
                if (focused == null || focused === vi)
                    list.requestFocus()
                list.setSelectionShowingPrevious(nextEpisodeIndex(files))
            }
            focusApplied = true
        }
    }

    // --- actions ------------------------------------------------------------

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

            // the server gave no action at all — nothing to tell the user beyond the status
            else -> App.toast(fileStatusLabel(file.status), true)
        }
    }

    /// The header's "more" menu. Like everything else, built from actions: if the server did not
    /// allow an action, there is no entry, and if none is left, the button is not shown at all.
    private fun showItemMenu(actions: List<ItemAction>) {
        val titles = actions.map { itemActionLabel(it) }.toTypedArray()
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.torrmedia_action_more)
            .setItems(titles) { _, which -> runItemAction(actions[which]) }
            .show()
    }

    private fun runItemAction(action: ItemAction) {
        when (action) {
            ItemAction.RECHECK -> runOperation(OpKind.GENERIC) { TorrmediaApi.recheckItem(itemId) }
            ItemAction.REMOVE, ItemAction.REMOVE_WITH_DATA -> confirmRemove(action)
            // The rest lives on its own buttons and never gets into this menu (itemMenuActions).
            else -> App.toast(R.string.torrmedia_action_unavailable, true)
        }
    }

    /// Only watched episodes are deleted; unfinished ones stay in place.
    /// The count comes from the same snapshot that is drawn: the server decides anyway.
    private fun confirmDeleteWatched() {
        // The header is still from the list row: no files yet, so no episode count to name.
        // The item's snapshot arrives within a split second; until then the button does nothing.
        val files = (store.state.value as? MediaState.Ready)?.data?.files ?: return
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.torrmedia_action_delete_watched)
            .setMessage(getString(R.string.torrmedia_delete_watched_confirm, watchedToDelete(files)))
            .setPositiveButton(android.R.string.ok) { _, _ ->
                runOperation(OpKind.GENERIC) { TorrmediaApi.deleteWatched(itemId) }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showFileMenu(file: MediaFile) {
        // the menu is built from actions: an entry the server did not allow does not appear
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

            FileAction.SET_PRIORITY -> choosePriority(file)

            FileAction.SET_VIEWED -> runViewed { TorrmediaApi.setViewed(itemId, file.id, WatchState.WATCHED) }

            FileAction.CLEAR_VIEWED -> runViewed { TorrmediaApi.clearViewed(itemId, file.id) }
        }
    }

    /// The current priority is checked in the list; picking it again sends nothing
    private fun choosePriority(file: MediaFile) {
        val options = FilePriority.values()
        val current = options.indexOf(file.priority)
        AlertDialog.Builder(requireContext())
            .setTitle(file.name)
            .setSingleChoiceItems(options.map { filePriorityLabel(it) }.toTypedArray(), current) { dialog, which ->
                dialog.dismiss()
                val chosen = options[which]
                if (chosen != file.priority)
                    runOperation(OpKind.GENERIC) { TorrmediaApi.setPriority(itemId, file.id, chosen) }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmRemove(action: ItemAction) {
        AlertDialog.Builder(requireContext())
            .setTitle(itemActionLabel(action))
            .setMessage(if (action == ItemAction.REMOVE_WITH_DATA) R.string.torrmedia_remove_with_data_confirm else R.string.torrmedia_remove_confirm)
            .setPositiveButton(android.R.string.ok) { _, _ -> remove(action) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun remove(action: ItemAction) {
        runOperation(OpKind.REMOVE) {
            TorrmediaApi.removeItem(itemId, action == ItemAction.REMOVE_WITH_DATA)
        }
    }

    /**
     * Mutation: a 202 with the operation comes back at once; progress and result arrive as
     * operation events. No optimistic state changes: the screen redraws from the next snapshot.
     */
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
                // A 202 response always carries queued: the server starts the work before writing
                // the response, and a short operation can finish before the client even learns
                // about it. So the outcome is not awaited as an event but reconciled explicitly.
                if (op.id.isNotEmpty())
                    reconcile(op.id)
            } catch (e: Exception) {
                App.toast(App.context.getString(R.string.qbit_send_error) + ": " + (e.message ?: ""), true)
                withContext(Dispatchers.Main) { syncProgress() }
            }
        }
    }

    /// Watch marks touch only the service's local state and respond with 204
    private fun runViewed(block: suspend () -> Unit) {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                block()
            } catch (e: Exception) {
                App.toast(App.context.getString(R.string.qbit_send_error) + ": " + (e.message ?: ""), true)
            }
        }
    }

    /**
     * Reconciles an operation's outcome. Needed twice: right after the 202 (a short operation
     * could finish before we subscribed) and after the connection is restored (events that
     * arrived during the disconnect never reached us). Cheaper than guessing by a timeout.
     */
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

    /// warnings can be non-empty even with done — they must not be swallowed
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

        /// Rows inflated ahead of time while the items list was on screen (DetailsPrewarm).
        /// The first list layout is 6–7 rows at once, and inflating one takes ~5 ms on a TV.
        private val spareRows = ArrayDeque<View>()

        fun addSpareRows(views: List<View>) {
            spareRows.addAll(views)
        }

        /// The theme does not change during the screen's lifetime: colors and the chip shape are
        /// computed once rather than for every row (on a TV that is another ~4 ms per row).
        private val style by lazy { RowStyle() }

        private class RowStyle {
            private val themed = ContextThemeWrapper(App.context, ThemeUtil.selectedTheme)
            val colorBright = ThemeUtil.getColorFromAttr(themed, R.attr.colorBright)
            val nameColor = ColorUtils.setAlphaComponent(colorBright, 250)
            val colorDone = ThemeUtil.getColorFromAttr(themed, R.attr.colorDone)
            val chipText = ThemeUtil.getColorFromAttr(themed, R.attr.colorSurface)
            val chipShape: ShapeAppearanceModel = ShapeAppearanceModel()
                .toBuilder()
                .setAllCorners(CornerFamily.ROUNDED, Format.dp2px(2.0f).toFloat())
                .build()
        }

        @SuppressLint("NotifyDataSetChanged")
        fun update(list: List<MediaFile>) {
            if (rows != list) {
                rows = list
                notifyDataSetChanged()
            }
        }

        @SuppressLint("SetTextI18n")
        override fun getView(position: Int, view: View?, parent: ViewGroup?): View {
            val vi = view ?: spareRows.removeFirstOrNull()
                ?: LayoutInflater.from(parent?.context).inflate(R.layout.qbit_files_item, parent, false)
            val row = rows[position]
            val style = style

            // styled after TorrentFilesAdapter: bright file name, chip-shaped size label
            vi.findViewById<TextView>(R.id.tvFileName)?.apply {
                setTextIfChanged(row.name)
                setTextColor(style.nameColor)
            }
            vi.findViewById<ImageView>(R.id.ivViewed)?.apply {
                visibility = if (row.watch == WatchState.NONE) View.GONE else View.VISIBLE
                alpha = if (row.watch == WatchState.STARTED) 0.45f else 1.0f
            }
            val chipColor = if (row.status == FileStatus.READY) style.colorDone else style.colorBright
            vi.findViewById<TextView>(R.id.tvFileSize)?.apply {
                val size = Format.byteFmt(row.size)
                setTextIfChanged(when (row.status) {
                    FileStatus.READY -> {
                        val resume = if (row.watch == WatchState.STARTED && row.size > 0 && row.position > 0) {
                            val percent = (row.position.toDouble() * 100 / row.size.toDouble()).toInt().coerceIn(1, 99)
                            " ● ≈$percent%"
                        } else ""
                        size + resume
                    }

                    FileStatus.PARTIAL -> "$size ● ${(row.progress * 100).toInt()}%"
                    else -> "$size ● ${fileStatusLabel(row.status)}"
                } + when (row.status) {
                    // normal priority is not labelled: otherwise the tag would hang on every row
                    FileStatus.READY -> ""
                    else -> row.priority?.takeIf { it != FilePriority.NORMAL }?.let { " ● ${filePriorityLabel(it)}" } ?: ""
                })
                // the chip is recreated only when its color changes
                if (getTag(R.id.tvFileSize) != chipColor) {
                    background = MaterialShapeDrawable(style.chipShape).apply {
                        fillColor = ColorStateList.valueOf(chipColor).withAlpha(240)
                        setStroke(2.0f, ColorStateList.valueOf(chipColor).withAlpha(255))
                    }
                    setTag(R.id.tvFileSize, chipColor)
                    setTextColor(style.chipText)
                }
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
