package com.noxcrew.interfaces.transfer

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

public class InventoryTransfersTest {
    private data class Item(val type: String, val metadata: String = "")

    private fun stack(amount: Int, item: Item = Item("stone"), limit: Int = 64) = TransferStack(item, amount, limit)

    @Test
    public fun `shift transfer skips protected previews and full stacks and merges before empty slots`() {
        val slots = listOf(
            TransferSlot(stack(50)),
            TransferSlot(stack(1), mayExtract = false, accepts = { false }),
            TransferSlot(stack(64)),
            TransferSlot<Item>(),
            TransferSlot(stack(60)),
        )
        val changes = InventoryTransfers.move(slots, 0, listOf(1, 2, 3, 4), Item::equals)
        assertNull(changes[0])
        assertEquals(46, changes[3]?.amount)
        assertEquals(64, changes[4]?.amount)
        assertTrue(1 !in changes && 2 !in changes)
        assertEquals(50, slots[0].stack?.amount)
    }

    @Test
    public fun `filtered slots custom limits and metadata are respected`() {
        val item = Item("stone", "tracked:123")
        val slots = listOf(
            TransferSlot(stack(40, item, 16)),
            TransferSlot(stack(1, Item("stone", "tracked:456"))),
            TransferSlot<Item>(capacity = 5),
            TransferSlot<Item>(accepts = { it.item.type == "diamond" }),
            TransferSlot<Item>(),
        )
        val changes = InventoryTransfers.move(slots, 0, listOf(1, 2, 3, 4, 4, 0), Item::equals)
        assertEquals(19, changes[0]?.amount)
        assertEquals(5, changes[2]?.amount)
        assertEquals(16, changes[4]?.amount)
        assertTrue(1 !in changes && 3 !in changes)
        assertTrue(changes.values.filterNotNull().all { it.item == item })
    }

    @Test
    public fun `blocked extraction and full inventory produce no changes`() {
        val blocked = listOf(TransferSlot(stack(10), mayExtract = false), TransferSlot())
        assertTrue(InventoryTransfers.move(blocked, 0, listOf(1), Item::equals).isEmpty())
        assertTrue(InventoryTransfers.move(listOf(TransferSlot(stack(10)), TransferSlot(stack(64))), 0, listOf(1), Item::equals).isEmpty())
    }

    @Test
    public fun `collection ignores protected previews and collects partial stacks first`() {
        val slots = listOf(
            TransferSlot(stack(64)),
            TransferSlot(stack(20), mayExtract = false),
            TransferSlot(stack(10)),
            TransferSlot(stack(10, Item("stone", "different"))),
        )
        val result = InventoryTransfers.collect(slots, stack(50), Item::equals)
        assertEquals(64, result.cursor.amount)
        assertEquals(60, result.changes[0]?.amount)
        assertTrue(2 in result.changes)
        assertNull(result.changes[2])
        assertTrue(1 !in result.changes && 3 !in result.changes)
    }

    @Test
    public fun `random transfers conserve each item identity and never modify protected slots`() {
        val random = Random(84372)
        repeat(5000) {
            val slots = List(12) {
                val limit = listOf(1, 16, 64, 99).random(random)
                val stack = if (random.nextBoolean()) {
                    null
                } else {
                    stack(
                        random.nextInt(1, limit + 1),
                        Item(if (random.nextBoolean()) "stone" else "diamond", random.nextInt(3).toString()),
                        limit,
                    )
                }
                val protected = random.nextInt(4) == 0
                TransferSlot(stack, listOf(1, 16, 64).random(random), !protected) { !protected }
            }
            val changes = InventoryTransfers.move(slots, random.nextInt(slots.size), slots.indices.shuffled(random), Item::equals)
            val after = slots.mapIndexed { index, slot -> if (index in changes) changes[index] else slot.stack }
            fun counts(stacks: List<TransferStack<Item>?>) = stacks.filterNotNull()
                .groupBy { it.item }.mapValues { (_, values) -> values.sumOf { it.amount } }
            assertEquals(counts(slots.map { it.stack }), counts(after))
            for ((index, value) in changes) {
                val before = slots[index].stack
                if (value != null && value.amount > (before?.amount ?: 0)) {
                    assertTrue(slots[index].accepts(value))
                    assertTrue(value.amount <= minOf(value.maxStackSize, slots[index].capacity))
                } else {
                    assertTrue(slots[index].mayExtract)
                }
            }
            val cursor = stack(random.nextInt(1, 65))
            val collected = InventoryTransfers.collect(slots, cursor, Item::equals)
            val remaining = slots.mapIndexed { index, slot -> if (index in collected.changes) collected.changes[index] else slot.stack }
            assertEquals(counts(slots.map { it.stack } + cursor), counts(remaining + collected.cursor))
            assertTrue(collected.cursor.amount <= cursor.maxStackSize)
            assertTrue(collected.changes.keys.all { slots[it].mayExtract })
        }
    }
}
