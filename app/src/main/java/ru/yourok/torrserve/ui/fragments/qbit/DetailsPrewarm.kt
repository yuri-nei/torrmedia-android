package ru.yourok.torrserve.ui.fragments.qbit

import android.os.Looper
import android.os.MessageQueue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import ru.yourok.torrserve.R

class DetailsPrewarm(
    private val inflater: LayoutInflater,
    private val container: ViewGroup,
) {
    var root: View? = null
        private set
    private val rows = ArrayList<View>(ROWS)
    private var idle: MessageQueue.IdleHandler? = null

    fun start() {
        if (idle != null || done())
            return
        val handler = MessageQueue.IdleHandler { step() }
        idle = handler
        Looper.myQueue().addIdleHandler(handler)
    }

    fun cancel() {
        idle?.let { Looper.myQueue().removeIdleHandler(it) }
        idle = null
    }

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

    private fun step(): Boolean {
        val view = root
        if (view == null) {
            root = inflater.inflate(R.layout.qbit_files_fragment, container, false)
        } else {
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
        private const val ROWS = 8
    }
}
