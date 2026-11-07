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

    private var lastOpened: String? = null

    private var focusPlaced = false

    private var prewarm: DetailsPrewarm? = null

    private var retainedView: View? = null

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
                if (torr.src == "torrmedia") {
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
        setupSearch(vi)
        retainedView = vi
        return vi
    }


    private var searchShown = 0
    private var searchTotal = 0

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
            val enter = event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
            if (actionId != EditorInfo.IME_ACTION_DONE && !enter)
                return@setOnEditorActionListener false
            closeSearch(focusList = true)
            true
        }
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

    fun openSearch() {
        val vi = view ?: return
        val et = vi.findViewById<EditText>(R.id.etSearch) ?: return
        vi.findViewById<View>(R.id.tilSearch)?.visibility = View.VISIBLE
        et.requestFocus()
        et.setSelection(et.text.length)
        vi.findViewById<View>(R.id.searchChip)?.visibility = View.GONE
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
        if (focusList)
            vi.findViewById<View>(R.id.lvTorrents)?.requestFocus()
        til.visibility = View.GONE
        updateSearchChip()
    }

    private fun searchQuery(): String =
        view?.findViewById<EditText>(R.id.etSearch)?.text?.toString()?.trim().orEmpty()

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

        val emptyView = view.findViewById<TextView>(R.id.empty_view)
        viewLifecycleOwner.lifecycleScope.launch {
            model.mediaState.combine(model.mediaConnected) { state, connected ->
                when {
                    state is MediaState.Loading -> getString(R.string.torrmedia_loading)
                    state is MediaState.Failed -> state.message
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
        if (list.size > 0) {
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

    private fun schedulePrewarm() {
        if (prewarm != null)
            return
        val vi = view ?: return
        if (torrentAdapter?.list.orEmpty().none { it.src == "torrmedia" })
            return
        val container = activity?.findViewById<ViewGroup>(R.id.container) ?: return
        val warm = DetailsPrewarm(requireActivity().layoutInflater, container)
        prewarm = warm
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
        private const val ENTER_FALLBACK_MS = 1_000L
    }

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
