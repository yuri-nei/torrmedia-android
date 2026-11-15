package ru.yourok.torrserve.ui.fragments.main.update.server

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerVersionTest {
    @Test
    fun matrixReleasesAreInstalled() {
        assertTrue(isSupportedServerVersion("MatriX.150"))
        assertTrue(isSupportedServerVersion("MatriX.147.2"))
    }

    @Test
    fun otherLinesAndBrokenFeedsAreNot() {
        assertFalse(isSupportedServerVersion("Silo.2"))
        assertFalse(isSupportedServerVersion(""))
        assertFalse(isSupportedServerVersion(null))
    }
}
