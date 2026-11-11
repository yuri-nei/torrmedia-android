package ru.yourok.torrserve.ui.fragments.qbit

import ru.yourok.torrserve.R
import ru.yourok.torrserve.app.App
import ru.yourok.torrserve.server.api.FileAction
import ru.yourok.torrserve.server.api.FilePriority
import ru.yourok.torrserve.server.api.FileStatus
import ru.yourok.torrserve.server.api.ItemAction
import ru.yourok.torrserve.server.api.ItemStatus
import ru.yourok.torrserve.server.models.torrent.Torrent
import ru.yourok.torrserve.utils.Format
import java.util.Locale


fun itemStatusLabel(status: ItemStatus): String = App.context.getString(
    when (status) {
        ItemStatus.DOWNLOADING -> R.string.torrmedia_status_downloading
        ItemStatus.SEEDING -> R.string.torrmedia_status_seeding
        ItemStatus.STOPPED -> R.string.torrmedia_status_stopped
        ItemStatus.CHECKING -> R.string.torrmedia_status_checking
        ItemStatus.ERROR -> R.string.torrmedia_status_error
        ItemStatus.SHELF -> R.string.torrmedia_status_shelf
        ItemStatus.UNKNOWN -> R.string.torrmedia_status_unknown
    }
)

fun fileStatusLabel(status: FileStatus): String = App.context.getString(
    when (status) {
        FileStatus.READY -> R.string.torrmedia_file_ready
        FileStatus.PARTIAL -> R.string.torrmedia_file_downloading
        FileStatus.SKIPPED -> R.string.torrmedia_file_skipped
        FileStatus.MISSING -> R.string.torrmedia_file_missing
        FileStatus.UNKNOWN -> R.string.torrmedia_status_unknown
    }
)

fun itemActionLabel(action: ItemAction): String = App.context.getString(
    when (action) {
        ItemAction.START -> R.string.torrmedia_action_start
        ItemAction.STOP -> R.string.torrmedia_action_stop
        ItemAction.RECHECK -> R.string.torrmedia_action_recheck
        ItemAction.DELETE_WATCHED -> R.string.torrmedia_action_delete_watched
        ItemAction.REMOVE -> R.string.torrmedia_action_remove
        ItemAction.REMOVE_WITH_DATA -> R.string.torrmedia_action_remove_with_data
        ItemAction.CLEAR_VIEWED -> R.string.torrmedia_action_clear_viewed
    }
)

fun fileActionLabel(action: FileAction): String = App.context.getString(
    when (action) {
        FileAction.PLAY -> R.string.torrmedia_action_play
        FileAction.DELETE -> R.string.torrmedia_action_delete
        FileAction.ENABLE -> R.string.torrmedia_action_enable
        FileAction.SET_PRIORITY -> R.string.torrmedia_action_set_priority
        FileAction.SET_VIEWED -> R.string.torrmedia_action_set_viewed
        FileAction.CLEAR_VIEWED -> R.string.torrmedia_action_clear_viewed
    }
)

fun filePriorityLabel(priority: FilePriority): String = App.context.getString(
    when (priority) {
        FilePriority.NORMAL -> R.string.torrmedia_priority_normal
        FilePriority.HIGH -> R.string.torrmedia_priority_high
        FilePriority.MAX -> R.string.torrmedia_priority_max
    }
)

fun mediaListSummary(t: Torrent, stale: Boolean): String {
    val parts = mutableListOf<String>()
    if (stale)
        parts += App.context.getString(R.string.torrmedia_disconnected).capitalized()

    var head = itemStatusLabel(t.torrmediaStatus).capitalized()
    val pct = if (t.torrent_size > 0) (t.loaded_size * 100 / t.torrent_size).toInt().coerceIn(0, 100) else 100
    if (pct < 100 && t.torrmediaStatus != ItemStatus.SHELF)
        head += " $pct%"
    parts += head

    if (t.torrmediaStatus == ItemStatus.DOWNLOADING && t.download_speed > 0)
        parts += "↓ " + Format.speedFmt(t.download_speed)
    if (t.torrmediaFilesTotal > 1)
        parts += App.context.getString(R.string.torrmedia_list_ready_fmt, t.torrmediaFilesReady, t.torrmediaFilesTotal)
    if (t.torrmediaFilesWatched > 0)
        parts += if (t.torrmediaFilesTotal > 1)
            App.context.getString(R.string.torrmedia_list_watched_fmt, t.torrmediaFilesWatched)
        else
            App.context.getString(R.string.torrmedia_list_watched)
    return parts.joinToString(" · ")
}

private fun String.capitalized(): String =
    replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }

fun operationPhaseLabel(phase: String?): String {
    if (phase.isNullOrBlank())
        return ""
    val res = when (phase.trim().lowercase()) {
        "waiting" -> R.string.torrmedia_phase_waiting
        "stopping" -> R.string.torrmedia_phase_stopping
        "trimming" -> R.string.torrmedia_phase_trimming
        "rechecking" -> R.string.torrmedia_phase_rechecking
        "restarting" -> R.string.torrmedia_phase_restarting
        "prioritizing" -> R.string.torrmedia_phase_prioritizing
        "enabling" -> R.string.torrmedia_phase_enabling
        "starting" -> R.string.torrmedia_phase_starting
        "deleting" -> R.string.torrmedia_phase_deleting
        "removing" -> R.string.torrmedia_phase_removing
        "adding" -> R.string.torrmedia_phase_adding
        else -> return phase
    }
    return App.context.getString(res)
}
