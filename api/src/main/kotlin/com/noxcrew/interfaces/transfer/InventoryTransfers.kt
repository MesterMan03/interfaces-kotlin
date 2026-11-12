package com.noxcrew.interfaces.transfer

/** An immutable stack description. [item] retains the platform's complete item identity, including metadata. */
public data class TransferStack<T>(public val item: T, public val amount: Int, public val maxStackSize: Int) {
    init {
        require(amount > 0) { "Stack amount must be positive" }
        require(maxStackSize > 0) { "Maximum stack size must be positive" }
    }
}

/** A snapshot of one slot and its insertion, extraction, and capacity rules. */
public data class TransferSlot<T>(
    public val stack: TransferStack<T>? = null,
    public val capacity: Int = 64,
    public val mayExtract: Boolean = true,
    public val accepts: (TransferStack<T>) -> Boolean = { true },
) {
    init {
        require(capacity > 0) { "Slot capacity must be positive" }
    }
}

/** A collection operation's changed slots and resulting cursor. Neither input stacks nor slots are mutated. */
public data class CollectionTransfer<T>(
    public val changes: Map<Int, TransferStack<T>?>,
    public val cursor: TransferStack<T>,
)

/**
 * Plans transfers before the platform applies them. Protected slots are skipped rather than blocking unrelated slots.
 * Callers must apply a plan synchronously against the same snapshot, and must not also execute the original transfer.
 */
public object InventoryTransfers {
    /** Moves [source] into [targets], merging compatible stacks before using empty slots, in the supplied order. */
    public fun <T> move(
        slots: List<TransferSlot<T>>,
        source: Int,
        targets: Iterable<Int>,
        similar: (T, T) -> Boolean,
    ): Map<Int, TransferStack<T>?> {
        val sourceSlot = slots[source]
        val stack = sourceSlot.stack ?: return emptyMap()
        if (!sourceSlot.mayExtract) return emptyMap()

        val destinations = targets.distinct().filter { it != source }
        require(destinations.all { it in slots.indices }) { "Target outside inventory" }
        val changes = linkedMapOf<Int, TransferStack<T>?>()
        var remaining = stack.amount
        for (empty in listOf(false, true)) {
            for (index in destinations) {
                if (remaining == 0) break
                val slot = slots[index]
                val current = changes.getOrElse(index) { slot.stack }
                if ((current == null) != empty) continue
                val incoming = stack.copy(amount = remaining)
                if (!slot.accepts(incoming)) continue
                if (current != null && !similar(current.item, stack.item)) continue
                val limit = minOf(slot.capacity, stack.maxStackSize, current?.maxStackSize ?: stack.maxStackSize)
                val moved = minOf(remaining, (limit - (current?.amount ?: 0)).coerceAtLeast(0))
                if (moved == 0) continue
                changes[index] = (current ?: stack).copy(amount = (current?.amount ?: 0) + moved)
                remaining -= moved
            }
        }
        if (remaining != stack.amount) changes[source] = if (remaining > 0) stack.copy(amount = remaining) else null
        return changes
    }

    /** Collects matching items into [cursor], considering partial stacks before full stacks as vanilla does. */
    public fun <T> collect(slots: List<TransferSlot<T>>, cursor: TransferStack<T>, similar: (T, T) -> Boolean,): CollectionTransfer<T> {
        val changes = linkedMapOf<Int, TransferStack<T>?>()
        var amount = cursor.amount
        for (full in listOf(false, true)) {
            for ((index, slot) in slots.withIndex()) {
                if (amount >= cursor.maxStackSize) break
                val stack = slot.stack ?: continue
                if (!slot.mayExtract || !similar(cursor.item, stack.item)) continue
                if ((stack.amount >= stack.maxStackSize) != full) continue
                val moved = minOf(stack.amount, cursor.maxStackSize - amount)
                amount += moved
                changes[index] = if (stack.amount > moved) stack.copy(amount = stack.amount - moved) else null
            }
        }
        return CollectionTransfer(changes, cursor.copy(amount = amount))
    }
}
