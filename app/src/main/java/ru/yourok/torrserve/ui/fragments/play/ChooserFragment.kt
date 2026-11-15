package ru.yourok.torrserve.ui.fragments.play

import android.os.Bundle
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.yourok.torrserve.R
import ru.yourok.torrserve.ext.commitFragment
import ru.yourok.torrserve.server.local.TorrService
import ru.yourok.torrserve.settings.Settings
import ru.yourok.torrserve.ui.activities.play.Play
import ru.yourok.torrserve.ui.activities.play.PlayActivity
import ru.yourok.torrserve.ui.activities.play.players.Players
import ru.yourok.torrserve.ui.fragments.TSFragment


/**
 * What to do with a link opened from another app (Lampa and the like).
 *
 * The two destinations are large tiles: watch now through TorrServer, or download with the
 * server's qBittorrent. Adding to the TorrServer list without playing and a one-off player are
 * rare and sit in a quiet row below. Instead of a "remember" switch, focus starts on the action
 * chosen last time: one OK repeats it, and the other destination is still one step away.
 * Skipping the window altogether is a setting.
 */
class ChooserFragment : TSFragment() {

    companion object {
        // Action codes, shared with PlayActivity and the stored default
        const val ADD_PLAY = 2
        const val ADD = 3
        const val QBIT = 4
    }

    fun show(activity: PlayActivity, forceChoose: Boolean, onChoose: (Int) -> Unit) {
        // 1 (play without saving) has no tile any more but is still honoured as a stored default
        val saveChoose = Settings.getChooserAction()
        if (!forceChoose && saveChoose in 1..3) {
            onChoose(saveChoose)
            return
        }

        this.onResult = {
            val action = (it as Int)
            if (action in 1..4)
                onChoose(action)

            activity.commitFragment {
                detach(this@ChooserFragment)
            }
        }
        show(activity, R.id.info_container)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val vi = inflater.inflate(R.layout.chooser_fragment, container, false)
        TorrService.start()
        return vi
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val link = (activity as? PlayActivity)?.torrentLink ?: ""
        val scheme = link.toUri().scheme?.lowercase() ?: ""
        // the contract accepts only magnet: — showing the tile for http(s) would mean
        // promising a rejection
        val qbit = Settings.getTorrmediaUrl().isNotEmpty() && scheme == "magnet"

        view.findViewById<View>(R.id.cvAddPlay)?.setOnClickListener { choose(ADD_PLAY) }
        view.findViewById<View>(R.id.cvQbit)?.apply {
            if (qbit)
                visibility = View.VISIBLE
            setOnClickListener { choose(QBIT) }
        }
        view.findViewById<View>(R.id.cvAdd)?.setOnClickListener { choose(ADD) }
        view.findViewById<View>(R.id.cvPlayer)?.setOnClickListener { choosePlayer() }

        showTitle()
        showPlayer(Settings.getPlayer())

        val target = when (Settings.getChooserLastAction()) {
            QBIT -> if (qbit) R.id.cvQbit else R.id.cvAddPlay
            ADD -> R.id.cvAdd
            else -> R.id.cvAddPlay
        }
        // After layout: a request made while inflating is lost, and the system would then
        // give focus to whatever it finds first.
        view.post { view.findViewById<View>(target)?.requestFocus() }
    }

    override fun onDestroyView() {
        activity?.findViewById<TextView>(R.id.info_title)?.apply {
            setText(R.string.app_name)
            maxLines = Int.MAX_VALUE
            ellipsize = null
        }
        super.onDestroyView()
    }

    private fun choose(action: Int) {
        // the download choice is remembered for focus only: it always needs the file list
        Settings.setChooserLastAction(action)
        lifecycleScope.launch {
            onResult?.invoke(action)
        }
    }

    /// The window is titled with the torrent the other app sent, not with the app's name
    private fun showTitle() {
        val title = (activity as? PlayActivity)?.torrentTitle?.trim().orEmpty()
        if (title.isEmpty())
            return
        activity?.findViewById<TextView>(R.id.info_title)?.apply {
            text = title
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
    }

    /// "Player: <name>" for the player the next playback will use
    private fun showPlayer(pkg: String) {
        lifecycleScope.launch {
            val name = when (pkg) {
                "" -> getString(R.string.torrmedia_chooser_player_ask)
                "0" -> getString(R.string.torrmedia_chooser_player_system)
                else -> withContext(Dispatchers.IO) { Players.getList() }
                    .firstOrNull { it.first == pkg }?.second ?: pkg
            }
            view?.findViewById<TextView>(R.id.tvNamePlayer)?.text =
                getString(R.string.torrmedia_chooser_player_fmt, name)
        }
    }

    private fun choosePlayer() {
        lifecycleScope.launch {
            val list = withContext(Dispatchers.IO) { Players.getList() }
            AlertDialog.Builder(requireContext())
                .setTitle(getString(R.string.select_player))
                .setItems(list.map { it.second }.toTypedArray()) { _, which ->
                    Play.tmpPlayerPackage = list[which].first
                    showPlayer(list[which].first)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }
}
