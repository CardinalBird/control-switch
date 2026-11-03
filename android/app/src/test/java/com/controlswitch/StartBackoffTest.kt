package com.controlswitch

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StartBackoffTest {
    @Test
    fun ownChurnKeepsTheMinuteAndARealConnectLiftsIt() {
        val backoff = StartBackoff()
        assertTrue(backoff.allows(0))

        backoff.failed(at = 1_000)
        assertFalse(backoff.allows(11_000))
        // The failed start's own hide and restore, read on the next tick: still waiting the minute.
        backoff.controllersChanged(at = 3_500)
        assertFalse(backoff.allows(11_000))
        assertTrue(backoff.allows(61_000))

        // A pad connecting later lifts the wait at once.
        backoff.failed(at = 70_000)
        backoff.controllersChanged(at = 80_000)
        assertTrue(backoff.allows(80_000))
    }
}
