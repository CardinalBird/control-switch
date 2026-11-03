package com.controlswitch.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

class SelectStartBackTest {
    private val select = ControlRef.button(0x13a)
    private val start = ControlRef.button(0x13b)
    private val back = ControlRef.button(KEY_BACK)

    @Test
    fun `the setting adds Select + Start as Back`() {
        val mapping = ControllerMapping().withSelectStartBack(enabled = true)
        assertEquals(listOf(SELECT_START_BACK), mapping.chords)
    }

    @Test
    fun `a Back the user bound wins over the setting`() {
        val mine = Binding(ControlRef.button(0x13c), back)
        val mapping = ControllerMapping(listOf(mine)).withSelectStartBack(enabled = true)
        assertEquals(listOf(mine), mapping.bindings)
        assertEquals(emptyList<Chord>(), mapping.chords)
    }

    @Test
    fun `turning the setting off removes a saved Select + Start Back, in either order`() {
        val saved = ControllerMapping(chords = listOf(Chord(listOf(start, select), back)))
        assertEquals(emptyList<Chord>(), saved.withSelectStartBack(enabled = false).chords)
    }

    @Test
    fun `a saved Select + Start Back is not doubled when the setting is on`() {
        val saved = ControllerMapping(chords = listOf(Chord(listOf(select, start), back)))
        assertEquals(1, saved.withSelectStartBack(enabled = true).chords.size)
    }
}
