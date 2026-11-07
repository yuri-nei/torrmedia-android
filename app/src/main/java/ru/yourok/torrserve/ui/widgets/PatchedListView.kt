package ru.yourok.torrserve.ui.widgets

import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.util.AttributeSet
import android.view.View
import android.widget.ListView

class PatchedListView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.listViewStyle,
) : ListView(context, attrs, defStyleAttr) {

    override fun awakenScrollBars(startDelay: Int, invalidate: Boolean): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInLayout)
            return false
        return super.awakenScrollBars(startDelay, invalidate)
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        val sideways = direction == View.FOCUS_LEFT || direction == View.FOCUS_RIGHT
        super.onFocusChanged(gainFocus, direction, if (sideways) null else previouslyFocusedRect?.let { Rect(it) })
    }

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
