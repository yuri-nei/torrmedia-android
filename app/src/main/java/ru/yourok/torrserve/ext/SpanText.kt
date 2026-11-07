package ru.yourok.torrserve.ext

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.widget.TextView
import androidx.annotation.ColorInt

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

fun TextView.setTextIfChanged(value: CharSequence) {
    if (text.toString() != value.toString())
        text = value
}
