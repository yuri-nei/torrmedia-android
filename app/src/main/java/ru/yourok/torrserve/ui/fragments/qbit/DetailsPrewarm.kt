package ru.yourok.torrserve.ui.fragments.qbit

import android.os.Looper
import android.os.MessageQueue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import ru.yourok.torrserve.R

/**
 * An item screen inflated in advance, while the user is looking at the items list.
 *
 * On a TV, inflating the item screen's layout takes ~85 ms of main thread, and it used to
 * happen right on pressing OK: the picture froze all that time, and only then did the
 * transition animation start. Episode rows take another ~5 ms each, and their first layout
 * (6–7 rows at once) stalled the screen's fade-in.
 *
 * The work runs while the main thread is idle (IdleHandler), one unit at a time — the screen
 * layout, then the rows one by one — so as not to hold the thread for long at a stretch. It
 * inflates into the same container with the same activity inflater as the fragment does, so the
 * view is no different from one inflated in onCreateView. If it is not ready in time, the
 * screen is inflated on the press, as before.
 */
class DetailsPrewarm(
    private val inflater: LayoutInflater,
    private val container: ViewGroup,
) {
    var root: View? = null
        private set
    private val rows = ArrayList<View>(ROWS)
    private var idle: MessageQueue.IdleHandler? = null

    /// Schedules the inflation for when the main thread is idle. A repeated call does nothing.
    fun start() {
        if (idle != null || done())
            return
        val handler = MessageQueue.IdleHandler { step() }
        idle = handler
        Looper.myQueue().addIdleHandler(handler)
    }

    /// Drops unfinished work; whatever is already inflated stays and can still be used.
    fun cancel() {
        idle?.let { Looper.myQueue().removeIdleHandler(it) }
        idle = null
    }

    /**
     * Takes what was inflated: the screen view (if ready) and its rows. After this the object is
     * empty and does no work. The view is handed out only if it was inflated with the same context
     * as the fragment's inflater: otherwise the theme and the view factory could differ.
     */
    fun take(fragmentInflater: LayoutInflater): Pair<View, List<View>>? {
        cancel()
        val view = root ?: return null
        root = null
        if (view.parent != null || view.context !== fragmentInflater.context)
            return null
        val spare = rows.toList()
        rows.clear()
        return view to spare
    }

    private fun done() = root != null && rows.size >= ROWS

    /// One unit of work; true means work remains (the IdleHandler will be called again).
    private fun step(): Boolean {
        val view = root
        if (view == null) {
            root = inflater.inflate(R.layout.qbit_files_fragment, container, false)
        } else {
            // the parent is the list itself, so that the rows get its LayoutParams
            val list = view.findViewById<ViewGroup>(R.id.lvQbitFiles) ?: return finish()
            rows.add(inflater.inflate(R.layout.qbit_files_item, list, false))
        }
        return if (done()) finish() else true
    }

    private fun finish(): Boolean {
        idle = null
        return false
    }

    companion object {
        /// 6–7 rows are visible on a TV screen, plus one for measuring the height of the row above
        /// the selected one
        private const val ROWS = 8
    }
}
