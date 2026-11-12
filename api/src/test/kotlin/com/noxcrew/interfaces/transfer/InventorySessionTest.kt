package com.noxcrew.interfaces.transfer

import com.noxcrew.interfaces.grid.mapping.ContainerGridMapper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

public class InventorySessionTest {
    @Test
    public fun `close before deferred notification still records the last transfer once`() {
        val session = InventorySession<Map<Int, Int>> { it.toMap() }
        assertTrue(session.open(mapOf(0 to 10)))
        val final = requireNotNull(session.update(mapOf(0 to 3)))
        assertEquals(mapOf(0 to 10), final.before)
        assertEquals(mapOf(0 to 3), final.after)
        assertTrue(session.close())
        assertFalse(session.close())
        assertNull(session.update(mapOf(0 to 3)))
    }

    @Test
    public fun `title recreation does not reset ownership and reopened views establish a fresh baseline`() {
        val session = InventorySession<Map<Int, Int>> { it.toMap() }
        session.open(mapOf(0 to 5))
        assertFalse(session.open(mapOf(0 to 99)))
        assertNull(session.update(mapOf(0 to 5)))
        assertTrue(session.close())
        assertTrue(session.open(mapOf(0 to 8)))
        assertEquals(mapOf(0 to 8), session.update(emptyMap())?.before)
    }

    @Test
    public fun `callbacks cannot mutate the baseline or cause recursive duplicate notification`() {
        val initial = mutableMapOf(0 to 10)
        val session = InventorySession<MutableMap<Int, Int>> { it.toMutableMap() }
        session.open(initial)
        initial[0] = 99
        val change = requireNotNull(session.update(mutableMapOf(0 to 4)))
        assertEquals(10, change.before[0])
        change.after[0] = 200
        assertNull(session.update(mutableMapOf(0 to 4)))
    }

    @Test
    public fun `native inventory mapping never enumerates nonexistent trailing chest slots`() {
        for (size in listOf(3, 5, 9, 27, 54)) {
            val slots = mutableListOf<Int>()
            ContainerGridMapper((size + 8) / 9, false, size).forEachInGrid { row, column -> slots += row * 9 + column }
            assertEquals((0 until size).toList(), slots)
        }
    }
}
