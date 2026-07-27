package ru.yourok.torrserve.ui.fragments.qbit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadsLogicTest {

    @Test
    fun `list failure makes every row unknown`() {
        val state = classifyFileState(
            dataValid = backendsValid(torrmediaFresh = false, qbitAvailable = true, qbitLookupOk = true),
            ready = false,
            skipped = false,
            deleting = false,
            enabling = false,
        )
        assertEquals(FileState.UNKNOWN, state)
        assertFalse(destructiveAllowed(state, dataValid = false, identified = true))
    }

    @Test
    fun `qbit unavailable blocks destructive actions`() {
        assertFalse(backendsValid(torrmediaFresh = true, qbitAvailable = false, qbitLookupOk = true))
        assertFalse(backendsValid(torrmediaFresh = true, qbitAvailable = true, qbitLookupOk = false))
        val state = classifyFileState(
            dataValid = backendsValid(torrmediaFresh = true, qbitAvailable = false, qbitLookupOk = true),
            ready = true,
            skipped = false,
            deleting = false,
            enabling = false,
        )
        assertEquals(FileState.UNKNOWN, state)
        assertFalse(destructiveAllowed(state, dataValid = false, identified = true))
    }

    @Test
    fun `ready file is downloaded only when both backends are valid`() {
        val valid = backendsValid(torrmediaFresh = true, qbitAvailable = true, qbitLookupOk = true)
        assertTrue(valid)
        val state = classifyFileState(valid, ready = true, skipped = false, deleting = false, enabling = false)
        assertEquals(FileState.DOWNLOADED, state)
        assertTrue(destructiveAllowed(state, dataValid = true, identified = true))
    }

    @Test
    fun `unidentified target blocks destructive actions even on fresh data`() {
        val state = classifyFileState(true, ready = false, skipped = false, deleting = false, enabling = false)
        assertEquals(FileState.PROGRESS, state)
        assertFalse(destructiveAllowed(state, dataValid = true, identified = false))
    }

    @Test
    fun `skipped and optimistic states`() {
        assertEquals(
            FileState.SKIPPED,
            classifyFileState(true, ready = false, skipped = true, deleting = false, enabling = false)
        )
        assertEquals(
            FileState.ENABLING,
            classifyFileState(true, ready = false, skipped = true, deleting = false, enabling = true)
        )
        assertEquals(
            FileState.DELETING,
            classifyFileState(true, ready = true, skipped = false, deleting = true, enabling = false)
        )
        assertEquals(
            FileState.SKIPPED,
            classifyFileState(true, ready = false, skipped = true, deleting = true, enabling = false)
        )
    }

    @Test
    fun shelfOnlyReleaseStaysUsableWithoutQbit() {
        val owners = listOf(FileOwner.NONE, FileOwner.NONE)
        assertFalse(needsQbitBackend(qbitMode = false, owners = owners))
        assertTrue(
            screenDataValid(
                needsQbit = false, torrmediaFresh = true,
                qbitAvailable = false, qbitLookupOk = false,
            )
        )
    }

    @Test
    fun torrentBackedReleaseStillRequiresQbit() {
        val owners = listOf(FileOwner.NONE, FileOwner.TORRENT)
        assertTrue(needsQbitBackend(qbitMode = false, owners = owners))
        assertFalse(
            screenDataValid(
                needsQbit = true, torrmediaFresh = true,
                qbitAvailable = false, qbitLookupOk = false,
            )
        )
    }

    @Test
    fun staleShelfIsNeverValidEvenWithoutQbit() {
        assertFalse(
            screenDataValid(
                needsQbit = false, torrmediaFresh = false,
                qbitAvailable = true, qbitLookupOk = true,
            )
        )
    }

    @Test
    fun ambiguousOwnerForcesQbitBackedPath() {
        assertTrue(needsQbitBackend(qbitMode = false, owners = listOf(FileOwner.AMBIGUOUS)))
    }

    @Test
    fun serverReadyDrivesTheFileState() {
        assertEquals(
            FileState.DOWNLOADED,
            classifyFileState(dataValid = true, ready = true, skipped = false, deleting = false, enabling = false),
        )
        assertEquals(
            FileState.PROGRESS,
            classifyFileState(dataValid = true, ready = false, skipped = false, deleting = false, enabling = false),
        )
        assertEquals(
            FileState.UNKNOWN,
            classifyFileState(dataValid = false, ready = true, skipped = false, deleting = false, enabling = false),
        )
        assertFalse(destructiveAllowed(FileState.UNKNOWN, dataValid = false, identified = true))
    }
}
