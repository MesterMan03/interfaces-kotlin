package com.noxcrew.interfaces.inventory

import org.bukkit.inventory.ItemStack

/** Rules applied to every route into and out of a real item slot, including the player inventory. */
public class ManagedSlotPolicy(
    public val capacity: Int = Int.MAX_VALUE,
    public val mayInsert: (ItemStack) -> Boolean = { true },
    public val mayExtract: (ItemStack) -> Boolean = { true },
) {
    init {
        require(capacity > 0) { "Slot capacity must be positive" }
    }

    public companion object {
        /** Unrestricted storage, subject to the item's and inventory's actual maximum stack sizes. */
        public val OPEN: ManagedSlotPolicy = ManagedSlotPolicy()

        /** A slot whose real contents cannot be moved by any player inventory gesture. */
        public val LOCKED: ManagedSlotPolicy = ManagedSlotPolicy(mayInsert = { false }, mayExtract = { false })
    }
}
