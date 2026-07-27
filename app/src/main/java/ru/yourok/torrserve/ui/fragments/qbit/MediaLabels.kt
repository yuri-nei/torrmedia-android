package ru.yourok.torrserve.ui.fragments.qbit

import ru.yourok.torrserve.R
import ru.yourok.torrserve.app.App
import ru.yourok.torrserve.server.api.FileAction
import ru.yourok.torrserve.server.api.FileStatus
import ru.yourok.torrserve.server.api.ItemAction
import ru.yourok.torrserve.server.api.ItemStatus


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
        FileStatus.DOWNLOADING -> R.string.torrmedia_file_downloading
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
        ItemAction.REMOVE -> R.string.torrmedia_action_remove
        ItemAction.REMOVE_WITH_DATA -> R.string.torrmedia_action_remove_with_data
    }
)

fun fileActionLabel(action: FileAction): String = App.context.getString(
    when (action) {
        FileAction.PLAY -> R.string.torrmedia_action_play
        FileAction.DELETE -> R.string.torrmedia_action_delete
        FileAction.ENABLE -> R.string.torrmedia_action_enable
        FileAction.SET_VIEWED -> R.string.torrmedia_action_set_viewed
        FileAction.CLEAR_VIEWED -> R.string.torrmedia_action_clear_viewed
    }
)

fun operationPhaseLabel(phase: String?): String {
    if (phase.isNullOrBlank())
        return ""
    val res = when (phase.trim().lowercase()) {
        "waiting" -> R.string.torrmedia_phase_waiting
        "stopping" -> R.string.torrmedia_phase_stopping
        "trimming" -> R.string.torrmedia_phase_trimming
        "rechecking" -> R.string.torrmedia_phase_rechecking
        "restarting" -> R.string.torrmedia_phase_restarting
        else -> return phase
    }
    return App.context.getString(res)
}
