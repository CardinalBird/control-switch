package com.controlswitch

import com.controlswitch.domain.model.PadBattery
import org.junit.Assert.assertEquals
import org.junit.Test

class LowBatteryTest {
    @Test
    fun warnsOncePerLowSpellAndAgainAfterACharge() {
        val warned = mutableSetOf<String>()
        val low = mapOf("Pro" to PadBattery(level = 1, charging = false), "8BitDo" to PadBattery(level = 3, charging = false))
        assertEquals(setOf("Pro"), newlyLow(low, warned))
        // Still low: no second warning.
        assertEquals(emptySet<String>(), newlyLow(low, warned))
        // Plugged in: not a warning, and the spell is over.
        assertEquals(emptySet<String>(), newlyLow(mapOf("Pro" to PadBattery(level = 1, charging = true)), warned))
        // Low again later: warned again. A pad with no reading never is.
        assertEquals(setOf("Pro"), newlyLow(mapOf("Pro" to PadBattery(level = 0, charging = false)), warned))
        assertEquals(emptySet<String>(), newlyLow(emptyMap(), warned))
        assertEquals(emptySet<String>(), warned)
    }
}
