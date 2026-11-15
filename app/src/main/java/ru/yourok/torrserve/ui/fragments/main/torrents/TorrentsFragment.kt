package ru.yourok.torrserve.ui.fragments.main.torrents

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.Animation
import android.view.animation.AnimationUtils
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.yourok.torrserve.R
import ru.yourok.torrserve.app.App
import ru.yourok.torrserve.atv.Utils
import ru.yourok.torrserve.ext.setTextIfChanged
import ru.yourok.torrserve.server.api.MediaState
import ru.yourok.torrserve.server.models.torrent.Torrent
import ru.yourok.torrserve.settings.Settings
import ru.yourok.torrserve.ui.activities.play.PlayActivity
import ru.yourok.torrserve.ui.fragments.TSFragment
import ru.yourok.torrserve.ui.fragments.qbit.DetailsPrewarm
import ru.yourok.torrserve.ui.fragments.qbit.DownloadsFilesFragment
import ru.yourok.torrserve.ui.widgets.PatchedListView
import ru.yourok.torrserve.utils.TorrentHelper


class TorrentsFragment : TSFragment() {

    private var torrentAdapter: TorrentsAdapter? = null
    private var sortMode: Boolean = Settings.sortTorrByTitle
    private var category = ""

    /// The item opened last: after "back" from its screen the remote's focus returns to it.
    /// The fragment in the back stack outlives its view, and so does this field.
    private var lastOpened: String? = null

    /// Focus is put on the list once per view lifetime — when it first has rows
    private var focusPlaced = false

    /// Library item screen inflated in advance (DetailsPrewarm): handed to the item on click
    private var prewarm: DetailsPrewarm? = null

    /**
     * The main screen's view survives going into an item and to other screens: the fragment lives
     * on in the back stack, and re-inflating the view on a TV takes ~75 ms (the search field alone
     * is 45 ms) plus inflating all the rows, all of it before the first frame of the return
     * animation: the picture froze for ~190 ms and the item screen's fade-out was skipped entirely.
     * A view handed out again keeps both the adapter with its rows and the scroll position. If the
     * old view is not yet detached from the container (came back mid exit animation), a new one is
     * inflated.
     */
    private var retainedView: View? = null

    /// The screen's enter animation is running: list updates wait for its end (see updateList)
    private var entering = false
    private var listDeferred = false
    private var staleDeferred: Boolean? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        retainedView?.takeIf { it.parent == null }?.let { return it }
        val vi = inflater.inflate(R.layout.main_fragment, container, false)
        torrentAdapter = TorrentsAdapter(requireActivity())
        vi.findViewById<ListView>(R.id.lvTorrents)?.let { lvTorrents ->
            lvTorrents.adapter = torrentAdapter
            lvTorrents.emptyView = vi.findViewById(R.id.empty_view)
            lvTorrents.setOnItemClickListener { _, _, i, _ ->
                val torr = torrentAdapter?.getItem(i) as Torrent
                lastOpened = torr.hash
                if (torr.src == "torrmedia") { // hash carries the item's opaque id from the contract
                    val seed = (viewModel as? TorrentsViewModel)?.liveItem(torr.hash)
                    val warm = prewarm
                    prewarm = null
                    DownloadsFilesFragment.newInstance(torr.hash, torr.title.ifBlank { torr.name }, seed, warm)
                        .show(requireActivity(), R.id.container, true)
                    return@setOnItemClickListener
                }
                val intent = Intent(App.context, PlayActivity::class.java)
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                intent.action = Intent.ACTION_VIEW
                intent.putExtra("hash", torr.hash)
                intent.putExtra("title", torr.title)
                torr.category?.let { if (it.isNotBlank()) intent.putExtra("category", it) }
                intent.putExtra("poster", torr.poster)
                intent.putExtra("action", "play")
                App.context.startActivity(intent)
            }
            lvTorrents.choiceMode = ListView.CHOICE_MODE_MULTIPLE_MODAL
            lvTorrents.setMultiChoiceModeListener(TorrentsActionBar(lvTorrents))
        }
        // the listener lives together with the view: in onViewCreated it would be added again every
        // time the retained view comes back
        setupSearch(vi)
        retainedView = vi
        return vi
    }

    // --- search -------------------------------------------------------------

    /// Matched and total in the current category — for the active filter chip
    private var searchShown = 0
    private var searchTotal = 0

    /**
     * "Back" while the search field is open closes it: otherwise, with the keyboard hidden, it went
     * to the activity, and on the main screen that means exiting. Called from MainActivity's "back"
     * handler — an OnBackPressedCallback of our own does not work here: at launch the fragment
     * registers it before the activity does, so the activity's callback gets "back" first.
     */
    fun closeSearchOnBack(): Boolean {
        if (view?.findViewById<View>(R.id.tilSearch)?.visibility != View.VISIBLE)
            return false
        closeSearch(focusList = true)
        return true
    }

    private fun setupSearch(vi: View) {
        val et = vi.findViewById<EditText>(R.id.etSearch) ?: return
        et.doAfterTextChanged { updateList() }
        et.setOnEditorActionListener { _, actionId, event ->
            // "Done" on the on-screen keyboard or Enter on a hardware one (then IME_NULL arrives)
            val enter = event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
            if (actionId != EditorInfo.IME_ACTION_DONE && !enter)
                return@setOnEditorActionListener false
            closeSearch(focusList = true)
            true
        }
        // left the field with the remote (down, into the list): the field hides, the query stays on
        // the chip
        et.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus)
                closeSearch(focusList = false)
        }
        vi.findViewById<View>(R.id.btnSearchEdit)?.setOnClickListener { openSearch() }
        vi.findViewById<View>(R.id.btnSearchClear)?.setOnClickListener {
            et.setText("")
            closeSearch(focusList = true)
        }
    }

    /**
     * Opens the search field above the list together with the keyboard — from the "Search" menu
     * entry or the remote's search button. The field does not live on screen: on a TV it took up
     * almost a list row, and with a remote it is rarely used.
     */
    fun openSearch() {
        val vi = view ?: return
        val et = vi.findViewById<EditText>(R.id.etSearch) ?: return
        vi.findViewById<View>(R.id.tilSearch)?.visibility = View.VISIBLE
        et.requestFocus()
        et.setSelection(et.text.length)
        // hide the chip after moving focus: hidden while holding focus, it would hand focus to the
        // first focusable view in the window, i.e. the left menu
        vi.findViewById<View>(R.id.searchChip)?.visibility = View.GONE
        // on a TV, focus in the field does not bring up the keyboard by itself — only pressing OK
        // on it does
        et.post {
            val imm = et.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(et, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun closeSearch(focusList: Boolean) {
        val vi = view ?: return
        val til = vi.findViewById<View>(R.id.tilSearch) ?: return
        if (til.visibility != View.VISIBLE)
            return
        vi.findViewById<EditText>(R.id.etSearch)?.let { et ->
            val imm = et.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.hideSoftInputFromWindow(et.windowToken, 0)
        }
        // focus goes to the list first: a hidden field holding focus would hand it to the first
        // focusable view in the window, i.e. the left menu
        if (focusList)
            vi.findViewById<View>(R.id.lvTorrents)?.requestFocus()
        til.visibility = View.GONE
        updateSearchChip()
    }

    private fun searchQuery(): String =
        view?.findViewById<EditText>(R.id.etSearch)?.text?.toString()?.trim().orEmpty()

    /// The query chip is visible while a query is set and the field is closed
    private fun updateSearchChip() {
        val vi = view ?: return
        val query = searchQuery()
        val editing = vi.findViewById<View>(R.id.tilSearch)?.visibility == View.VISIBLE
        val chip = vi.findViewById<View>(R.id.searchChip) ?: return
        if (query.isEmpty() || editing) {
            if (chip.hasFocus())
                vi.findViewById<View>(R.id.lvTorrents)?.requestFocus()
            chip.visibility = View.GONE
            return
        }
        vi.findViewById<TextView>(R.id.btnSearchEdit)
            ?.setTextIfChanged(getString(R.string.search_filter_fmt, query, searchShown, searchTotal))
        chip.visibility = View.VISIBLE
    }

    /**
     * The enter animation is our own only to catch the moment it ends: a list update in the middle
     * of it rebinds all visible rows (~30 ms on a TV) and drops frames. So while the screen fades
     * in, changes wait and are then applied all at once. The first fill of an empty list does not
     * wait — otherwise an empty screen would fade in.
     * The fallback timer is for an animation that did not run to the end.
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
        staleDeferred?.let { torrentAdapter?.setMediaStale(it) }
        staleDeferred = null
        if (listDeferred) {
            listDeferred = false
            updateList()
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        focusPlaced = false
        category = arguments?.getString("category") ?: ""
        viewModel = ViewModelProvider(this)[TorrentsViewModel::class.java]
        val model = viewModel as TorrentsViewModel
        model.getData().observe(viewLifecycleOwner) { updateList() }

        // Subscribe to the library state: an empty list and "the stream has not come up yet" are
        // different things and must not look the same to the user.
        val emptyView = view.findViewById<TextView>(R.id.empty_view)
        viewLifecycleOwner.lifecycleScope.launch {
            model.mediaState.combine(model.mediaConnected) { state, connected ->
                when {
                    state is MediaState.Loading -> getString(R.string.torrmedia_loading)
                    state is MediaState.Failed -> state.message
                    // There is a snapshot but no connection: the rows may be stale, and keeping
                    // quiet about it is not an option — the user would take them as current.
                    !connected -> getString(R.string.torrmedia_disconnected)
                    else -> getString(R.string.no_torrents)
                }
            }.collect { emptyView?.text = it }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            model.mediaConnected.collect { connected ->
                if (entering)
                    staleDeferred = !connected
                else
                    torrentAdapter?.setMediaStale(!connected)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        (viewModel as TorrentsViewModel).setUpdate(true)
    }

    override fun onPause() {
        super.onPause()
        if (isViewModelInitialized())
            (viewModel as TorrentsViewModel).setUpdate(false)
    }

    fun sort(mode: Boolean = sortMode) {
        val list = torrentAdapter!!.list
        if (list.isNotEmpty()) {
            sortMode = !mode
            Settings.set("sort_torrents", sortMode)
            updateList()
            App.toast(if (sortMode) R.string.sort_by_name else R.string.sort_by_date)
            activity?.findViewById<ListView>(R.id.lvTorrents)?.apply {
                this.setSelection(0)
                requestFocus()
            }
        }
    }

    suspend fun filter(cat: String = "") = withContext(Dispatchers.Main) {
        category = cat
        updateList()
    }


    private fun updateList() {
        if (entering && torrentAdapter?.list?.isNotEmpty() == true) {
            listDeferred = true
            return
        }
        val query = searchQuery()
        val inCategory = (viewModel as TorrentsViewModel).getData().value.orEmpty().filter {
            if (category == "uncategorized") it.category.isNullOrBlank()
            else category.isBlank() || it.category?.contains(category, true) == true
        }
        val list = inCategory.filter {
            query.isEmpty() || it.title.orEmpty().contains(query, true) || it.name.orEmpty().contains(query, true)
        }
        torrentAdapter?.update(if (sortMode) list.sortedBy { it.title } else list.sortedByDescending { it.timestamp })
        searchShown = list.size
        searchTotal = inCategory.size
        updateSearchChip()
        placeInitialFocus()
        schedulePrewarm()
    }

    /// Inflates the item screen in advance if the list has library items: on pressing OK,
    /// inflating it on a TV cost ~85 ms of frozen picture before the animation.
    private fun schedulePrewarm() {
        if (prewarm != null)
            return
        val vi = view ?: return
        if (torrentAdapter?.list.orEmpty().none { it.src == "torrmedia" })
            return
        val container = activity?.findViewById<ViewGroup>(R.id.container) ?: return
        val warm = DetailsPrewarm(requireActivity().layoutInflater, container)
        prewarm = warm
        // Not right away: the screen's first frames and first remote presses matter more, and
        // nobody is likely to open an item sooner than half a second after the list appears.
        vi.postDelayed({ if (prewarm === warm) warm.start() }, 500)
    }

    override fun onDestroyView() {
        prewarm?.cancel()
        prewarm = null
        entering = false
        listDeferred = false
        staleDeferred = null
        super.onDestroyView()
    }

    companion object {
        /// The enter animation is ~0.3 s; if it does not finish, changes are applied after this
        /// timeout
        private const val ENTER_FALLBACK_MS = 1_000L
    }

    /**
     * Where the remote lands when the list fills for the first time: on the first item, and after
     * "back" from an item screen, on that same item. Without this, at launch focus went to the
     * whole side menu panel on the left (the list is empty at start, and requestFocus from the
     * layout is lost), and after "back" to the side menu header: Android gives focus to the first
     * focusable view in the window. If the remote is already inside this screen (for example, in
     * search), it is left alone. With touch, requestFocus on the list does nothing.
     */
    private fun placeInitialFocus() {
        if (focusPlaced)
            return
        val vi = view ?: return
        val items = torrentAdapter?.list.orEmpty()
        if (items.isEmpty())
            return
        focusPlaced = true
        if (vi.findFocus() != null)
            return
        val list = vi.findViewById<PatchedListView>(R.id.lvTorrents) ?: return
        if (!list.requestFocus())
            return
        val index = lastOpened?.let { hash -> items.indexOfFirst { it.hash == hash } } ?: -1
        list.setSelectionShowingPrevious(index.coerceAtLeast(0))
    }

    fun onKeyUp(keyCode: Int): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_INFO,
            KeyEvent.KEYCODE_MENU,
            KeyEvent.KEYCODE_BUTTON_X -> {
                return true
            }
        }
        return false
    }

    @SuppressLint("NotifyDataSetChanged")
    fun onKeyDown(keyCode: Int): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_SEARCH -> {
                openSearch()
                return true
            }

            KeyEvent.KEYCODE_INFO -> {
                activity?.currentFocus?.let {
                    it.findViewById<ListView>(R.id.lvTorrents)?.let { lv ->
                        val itemPosition = lv.selectedItemPosition
                        if (itemPosition in torrentAdapter!!.list.indices) {
                            torrentAdapter!!.list[itemPosition].let {
                                if (it.src != null) // FFP info is a TorrServer-only feature
                                    return@let
                                lifecycleScope.launch(Dispatchers.IO) {
                                    val torrent = TorrentHelper.waitFiles(it.hash) ?: let {
                                        return@launch
                                    }
                                    TorrentHelper.showFFPInfo(lv.context, "", torrent)
                                }
                            }
                        }
                    }
                }
                return true
            }

            KeyEvent.KEYCODE_MENU,
            KeyEvent.KEYCODE_BUTTON_X -> {
                sort(sortMode)
                if (Utils.isTvBox()) return true
            }

        }
        return false
    }
}
