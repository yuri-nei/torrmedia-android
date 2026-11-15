package ru.yourok.torrserve.ext

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.widget.TextView
import androidx.annotation.ColorInt

/// A piece of text with its own color (and weight) — the whole string is built before it is set
fun SpannableStringBuilder.appendStyled(
    text: String,
    @ColorInt color: Int,
    bold: Boolean = false,
): SpannableStringBuilder {
    if (text.isEmpty())
        return this
    val start = length
    append(text)
    setSpan(ForegroundColorSpan(color), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    if (bold)
        setSpan(StyleSpan(Typeface.BOLD), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    return this
}

/**
 * Sets the text in one assignment, and only if it changed. On a weak TV every text set means a
 * new layout, and TextView.append (ext) on top of that switches the view to an editable buffer
 * with DynamicLayout and lays the text out for every piece: the item screen header of ~20
 * pieces cost 40–60 ms of main thread and stalled animations. Identical text here also means
 * identical styles — the caller derives them from the same state as the words.
 */
fun TextView.setTextIfChanged(value: CharSequence) {
    if (text.toString() != value.toString())
        text = value
}
