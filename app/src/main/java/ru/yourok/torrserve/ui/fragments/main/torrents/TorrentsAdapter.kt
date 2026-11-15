package ru.yourok.torrserve.ui.fragments.main.torrents


import android.graphics.drawable.Drawable
import android.text.SpannableString
import android.text.Spanned
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import androidx.fragment.app.FragmentActivity
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.BitmapTransitionOptions
import com.bumptech.glide.load.resource.bitmap.RoundedCorners
import com.bumptech.glide.request.RequestOptions
import ru.yourok.torrserve.R
import ru.yourok.torrserve.ext.setTextIfChanged
import ru.yourok.torrserve.server.models.torrent.Torrent
import ru.yourok.torrserve.ui.fragments.qbit.mediaListSummary
import ru.yourok.torrserve.settings.Settings
import ru.yourok.torrserve.utils.CImageSpan
import ru.yourok.torrserve.utils.Format
import ru.yourok.torrserve.utils.SpanFormat
import java.util.Locale


class TorrentsAdapter(private val activity: FragmentActivity) : BaseAdapter() {
    val list = mutableListOf<Torrent>()

    /// No connection to the library: its rows are stale, and actions on them must not be
    /// offered — the server would reject them anyway, and the user would take them as working.
    private var mediaStale = false

    fun setMediaStale(stale: Boolean) {
        if (mediaStale == stale)
            return
        mediaStale = stale
        notifyDataSetChanged()
    }

    fun isStale(torrent: Torrent): Boolean = mediaStale && torrent.src == "torrmedia"

    /// List rows are rebound in full on any change and every time the screen comes back; on a
    /// TV binding cost ~5 ms per row. Date and size are formatted (SimpleDateFormat,
    /// String.format) once per value.
    private val dateText = HashMap<Long, String>()
    private val sizeText = HashMap<Long, String>()

    fun update(list: List<Torrent>) {
        if (this.list.size != list.size) {
            this.list.clear()
            this.list.addAll(list)
            notifyDataSetChanged()
        } else {
            var changed = false
            for (i in list.indices) {
                if (this.list[i] != list[i]) {
                    this.list[i] = list[i]
                    changed = true
                }
            }
            if (changed)
                notifyDataSetChanged()
        }
    }

    override fun getView(position: Int, view: View?, parent: ViewGroup?): View {
        val vi = view ?: LayoutInflater.from(parent?.context).inflate(R.layout.torrent_item, parent, false)

        val category = list[position].category ?: ""
        var title = list[position].title
        val poster = list[position].poster
        val hash = list[position].hash
        val size = list[position].torrent_size
        val addTime = list[position].timestamp
        var addStr = ""

        if (addTime > 0)
            addStr = dateText.getOrPut(addTime) { Format.sDateFmt(addTime) }

        if (title.isBlank())
            title = list[position].name

        val ivPoster = vi.findViewById<ImageView>(R.id.ivPoster)
        if (poster.isNullOrBlank() || !Settings.showCover()) {
            ivPoster?.visibility = View.GONE
            ivPoster?.setTag(R.id.ivPoster, null)
        } else
            ivPoster?.let {
                it.visibility = View.VISIBLE
                // the same poster is already set (or loading) in this view — no new request needed
                if (it.getTag(R.id.ivPoster) == poster)
                    return@let
                it.setTag(R.id.ivPoster, poster)
                Glide.with(activity)
                    .asBitmap()
                    .load(poster)
                    .centerCrop()
                    //.placeholder(ColorDrawable(0x3c000000))
                    .apply(RequestOptions.bitmapTransform(RoundedCorners(5)))
                    .transition(BitmapTransitionOptions.withCrossFade())
                    .into(it)
            }

        val tvName = vi.findViewById<TextView>(R.id.tvTorrName)
        val nameKey = "$category\u0000$title"
        if (tvName != null && tvName.getTag(R.id.tvTorrName) == nameKey) {
            // same title with the same category icon — do not rebuild
        } else if (category.isNotBlank()) {
            tvName?.setTag(R.id.tvTorrName, nameKey)
            val sIcon = SpannableString(" ")
            val cDrawable: Drawable? = when {
                category.contains("movie", true) -> AppCompatResources.getDrawable(vi.context, R.drawable.round_movie_24)
                category.contains("tv", true) -> AppCompatResources.getDrawable(vi.context, R.drawable.round_live_tv_24)
                category.contains("show", true) -> AppCompatResources.getDrawable(vi.context, R.drawable.round_live_tv_24)
                category.contains("music", true) -> AppCompatResources.getDrawable(vi.context, R.drawable.round_music_note_24)
                category.contains("other", true) -> AppCompatResources.getDrawable(vi.context, R.drawable.round_more_horiz_24)
                else -> null
            }
            if (cDrawable == null)
                tvName?.text = "${category.uppercase()} ● $title"
            else {
                cDrawable.setBounds(0, 0, cDrawable.intrinsicWidth, cDrawable.intrinsicHeight)
                val span = CImageSpan(cDrawable)
                sIcon.setSpan(span, 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                tvName?.text = SpanFormat.format("%s $title", sIcon)
            }
        } else {
            tvName?.setTag(R.id.tvTorrName, nameKey)
            tvName?.text = title
        }

        vi.findViewById<TextView>(R.id.tvTorrHash)?.setTextIfChanged(when (list[position].src) {
            // torrmedia row: the status arrives normalized; the id is opaque, not needed on screen
            // download server row: the id is opaque, not needed on screen — a summary instead
            "torrmedia" -> mediaListSummary(list[position], isStale(list[position]))

            else -> hash.uppercase(Locale.getDefault())
        })
        val td = vi.findViewById<TextView>(R.id.tvTorrDate)
        val ts = vi.findViewById<TextView>(R.id.tvTorrSize)
        if (addStr.isNotEmpty()) {
            td?.setTextIfChanged(addStr)
            td?.visibility = View.VISIBLE
        } else
            td?.visibility = View.GONE

        if (size > 0.0) {
            ts?.setTextIfChanged(sizeText.getOrPut(size) { Format.byteFmt(size) })
            ts?.visibility = View.VISIBLE
        } else
            ts?.visibility = View.GONE

        return vi
    }

    override fun getItem(p0: Int): Any? {
        if (p0 < 0 || p0 >= list.size)
            return null
        return list[p0]
    }

    override fun getItemId(p0: Int): Long {
        return p0.toLong()
    }

    override fun getCount(): Int = list.size
}
