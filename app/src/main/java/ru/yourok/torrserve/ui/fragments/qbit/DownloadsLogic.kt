package ru.yourok.torrserve.ui.fragments.qbit


enum class FileState { DOWNLOADED, PROGRESS, ENABLING, DELETING, SKIPPED, UNKNOWN }

object FileOwner {
    const val TORRENT = "torrent"
    const val NONE = "none"
    const val AMBIGUOUS = "ambiguous"
}

fun backendsValid(torrmediaFresh: Boolean, qbitAvailable: Boolean, qbitLookupOk: Boolean): Boolean =
    torrmediaFresh && qbitAvailable && qbitLookupOk

fun screenDataValid(
    needsQbit: Boolean,
    torrmediaFresh: Boolean,
    qbitAvailable: Boolean,
    qbitLookupOk: Boolean,
): Boolean = when {
    !torrmediaFresh -> false
    needsQbit -> qbitAvailable && qbitLookupOk
    else -> true
}

fun needsQbitBackend(qbitMode: Boolean, owners: List<String>): Boolean =
    qbitMode || owners.any { it != FileOwner.NONE }

fun fileReady(
    exactShelfSize: Long?,
    fallbackShelfSize: Long?,
    expectedSize: Long,
    qbitProgress: Double,
): Boolean = when {
    exactShelfSize != null -> exactShelfSize >= expectedSize
    else -> (fallbackShelfSize != null && fallbackShelfSize >= expectedSize) || qbitProgress >= 1.0
}

fun classifyFileState(
    dataValid: Boolean,
    ready: Boolean,
    skipped: Boolean, // qBit priority == 0
    deleting: Boolean,
    enabling: Boolean,
): FileState = when {
    !dataValid -> FileState.UNKNOWN
    deleting && (!skipped || ready) -> FileState.DELETING
    ready -> FileState.DOWNLOADED
    skipped -> if (enabling) FileState.ENABLING else FileState.SKIPPED
    else -> FileState.PROGRESS
}

fun destructiveAllowed(state: FileState, dataValid: Boolean, identified: Boolean): Boolean =
    dataValid && identified && state != FileState.UNKNOWN
