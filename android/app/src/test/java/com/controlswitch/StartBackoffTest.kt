package com.controlswitch

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StartBackoffTest {
    private val handheld = "/dev/input/event9"
    private val pad = "/dev/input/event11"

    @Test
    fun ownHideAndRestoreKeepTheMinute() {
        val backoff = StartBackoff()
        assertTrue(backoff.allows(0))
        backoff.failed(now = 1_000, pads = setOf(handheld, pad))
        // The start's own churn: the pad missing for a tick, then back at the same path.
        backoff.seen(setOf(handheld))
        backoff.seen(setOf(handheld, pad))
        assertFalse(backoff.allows(11_000))
        assertTrue(backoff.allows(61_000))
    }

    @Test
    fun aPadThatWasNotThereLiftsTheWait() {
        val backoff = StartBackoff()
        // Failed with the pad mid-connect, not yet listed; it shows up a second later.
        backoff.failed(now = 1_000, pads = setOf(handheld))
        backoff.seen(setOf(handheld, pad))
        assertTrue(backoff.allows(2_000))
    }

    @Test
    fun theWaitIsLiftedOnceAndNotReArmedBySeeing() {
        val backoff = StartBackoff()
        backoff.failed(now = 1_000, pads = setOf(handheld))
        backoff.seen(setOf(handheld, pad))
        backoff.seen(setOf(handheld))
        assertTrue(backoff.allows(2_000))
    }
}
