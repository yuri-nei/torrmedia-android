package ru.yourok.torrserve.ui.widgets

import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.util.AttributeSet
import android.view.View
import android.widget.ListView

/**
 * ListView for the remote: workarounds for two platform bugs, plus the focus behaviour people
 * expect on a TV. Used on all of the fork's lists — items, episodes and the add dialog.
 *
 * The scrollbar should show when the list is scrolled, not when it is re-laid out. Starting
 * with Android 7, ViewGroup.attachViewToParent() sends dispatchVisibilityAggregated() to the
 * parent itself, and View.onVisibilityAggregated(true) unconditionally calls
 * initialAwakenScrollBars() — showing the scrollbar for four times the usual duration. ListView
 * re-attaches rows via attachViewToParent on every layout pass, so any snapshot update (the
 * percentage of a downloading episode, the header text above the list) lit up the scrollbar
 * for about 2.5 seconds. With snapshots once a second it never faded out, with rare ones it
 * blinked. So we refuse to wake the scrollbar from inside a layout pass. Everything that
 * should show it happens outside layout: finger and D-pad scrolling (trackMotionScroll,
 * arrowScroll, pageScroll) comes from input handling, and the screen's first appearance
 * (onWindowVisibilityChanged) comes before performLayout.
 *
 * From above or below, focus enters the list on the row closest to the button it came from.
 * ListView.onFocusChanged finds that row via previouslyFocusedRect, but reads it only after
 * super. And starting with API 36.1, View.onFocusChanged calls requestRectangleOnScreen() on
 * gaining focus, and ViewRootImpl writes the list's window bounds into its mTempRect — the very
 * object it passed here as previouslyFocusedRect. Because of this, "down" from a button above
 * the list put focus on the bottom visible row. We pass a copy to super: ViewRootImpl's object
 * gets overwritten, while ListView reads ours.
 *
 * Sideways — from the menu on the left — focus returns to the list's selected row rather than
 * to the row vertically closest to the menu entry. Otherwise "right" from "Settings" landed in
 * the middle of the list, and "right" after launch, while focus was still on the whole menu
 * panel, landed on the row opposite the panel's center. Without a rectangle ListView keeps the
 * current selection, and if there was none, restores a sensible one itself
 * (resurrectSelection).
 */
class PatchedListView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.listViewStyle,
) : ListView(context, attrs, defStyleAttr) {

    override fun awakenScrollBars(startDelay: Int, invalidate: Boolean): Boolean {
        // onVisibilityAggregated appeared in Android 7: before that, re-layout did not wake the
        // scrollbar and there is nothing to fix (and isInLayout exists only since API 18).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInLayout)
            return false
        return super.awakenScrollBars(startDelay, invalidate)
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        val sideways = direction == View.FOCUS_LEFT || direction == View.FOCUS_RIGHT
        super.onFocusChanged(gainFocus, direction, if (sideways) null else previouslyFocusedRect?.let { Rect(it) })
    }

    /**
     * Selects a row so that one more row is visible above it, if there is one: that row shows what
     * came before (an episode already watched) and that the list does not start at the selected
     * row. At the end of a short list ListView itself does not scroll past the last row.
     */
    fun setSelectionShowingPrevious(position: Int) {
        if (position <= 0) {
            setSelection(0)
            return
        }
        val above = rowHeight(position - 1)
        if (above > 0)
            setSelectionFromTop(position, above + dividerHeight)
        else
            setSelection(position)
    }

    /// Row height: of a row already laid out, or, if there is none yet (data just arrived and
    /// layout is still ahead), measured in advance from the adapter's view.
    private fun rowHeight(position: Int): Int {
        getChildAt(position - firstVisiblePosition)?.height?.takeIf { it > 0 }?.let { return it }
        val adapter = adapter ?: return 0
        val width = width - paddingLeft - paddingRight
        if (width <= 0 || position !in 0 until adapter.count)
            return 0
        val row = adapter.getView(position, null, this)
        row.measure(
            MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
        )
        return row.measuredHeight
    }
}
