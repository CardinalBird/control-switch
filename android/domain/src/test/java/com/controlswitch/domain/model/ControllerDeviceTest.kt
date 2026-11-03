package com.controlswitch.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

class ControllerDeviceTest {
    private val handheld = ControllerDevice("Odin Controller", "/dev/input/event9", "g", controllerNumber = 1, isInternal = true)
    private val pro = ControllerDevice("Nintendo Switch Pro Controller", "/dev/input/event14", "g", controllerNumber = 2)
    private val xbox = ControllerDevice("Xbox Wireless Controller", "/dev/input/event12", "g", controllerNumber = 3)

    @Test
    fun `the mirrored pad is player 1 and the next pad is 2, whatever Android numbered it`() {
        // As on a Thor with two pads: Android counts the built-in pad, so the Xbox pad is its 3.
        val order = listOf(handheld, pro, xbox).playerOrder(mirroredPath = pro.path)
        assertEquals(listOf(pro to 1, xbox to 2), order)
    }

    @Test
    fun `the mirrored pad is listed first even when Android numbered it later`() {
        val order = listOf(handheld, pro, xbox).playerOrder(mirroredPath = xbox.path)
        assertEquals(listOf(xbox to 1, pro to 2), order)
    }

    @Test
    fun `with nothing mirrored the pads start at 2, the handheld being player 1`() {
        val order = listOf(handheld, xbox, pro).playerOrder(mirroredPath = null)
        assertEquals(listOf(pro to 2, xbox to 3), order)
    }
}
