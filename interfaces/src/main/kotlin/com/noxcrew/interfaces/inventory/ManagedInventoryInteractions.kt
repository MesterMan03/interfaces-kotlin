package com.noxcrew.interfaces.inventory

import com.noxcrew.interfaces.InterfacesListeners
import com.noxcrew.interfaces.transfer.InventoryTransfers
import com.noxcrew.interfaces.transfer.TransferSlot
import com.noxcrew.interfaces.transfer.TransferStack
import com.noxcrew.interfaces.view.ContainerInterfaceView
import org.bukkit.Bukkit
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryAction
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.inventory.InventoryType
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.Plugin

/** Routes transfers for real chest inventories without letting display elements become transfer targets. */
internal class ManagedInventoryInteractions(private val plugin: Plugin) {
    private val pendingUpdates = mutableSetOf<ContainerInterfaceView<*, *>>()

    /** Handles item movement; protected button clicks remain the responsibility of the normal click handler. */
    fun click(event: InventoryClickEvent, view: ContainerInterfaceView<*, *>) {
        queueUpdate(view)
        when (event.action) {
            InventoryAction.MOVE_TO_OTHER_INVENTORY -> {
                // Native furnaces, hoppers and similar menus must retain their own input/fuel/result slot rules.
                if (view.backing.builder.inventoryType != InventoryType.CHEST) {
                    if ((0 until event.view.topInventory.size).any { !view.isManagedSlot(it) } ||
                        event.currentItem?.let { !policy(view, event.rawSlot).mayExtract(it) } == true
                    ) {
                        event.isCancelled = true
                    }
                    return
                }
                event.isCancelled = true
                val size = event.view.topInventory.size
                val source = event.rawSlot
                if (source !in 0 until size + 36) return
                val targets = if (source < size) (size until size + 36).reversed() else (0 until size).toList()
                val changes = InventoryTransfers.move(slots(view), source, targets, ItemStack::isSimilar)
                apply(event, changes)
            }

            InventoryAction.COLLECT_TO_CURSOR -> {
                if (view.backing.builder.inventoryType != InventoryType.CHEST) {
                    // Preserve native result-slot pickup rules rather than emulating them with raw slot writes.
                    if ((0 until event.view.topInventory.size).any { !view.isManagedSlot(it) } ||
                        (0 until event.view.topInventory.size + 36).any { slot ->
                            event.view.getItem(slot)?.let { it.isSimilar(event.cursor) && !policy(view, slot).mayExtract(it) } == true
                        }
                    ) {
                        event.isCancelled = true
                    }
                    return
                }
                event.isCancelled = true
                val cursor = event.cursor.asTransferStack() ?: return
                val result = InventoryTransfers.collect(slots(view), cursor, ItemStack::isSimilar)
                apply(event, result.changes)
                event.whoClicked.setItemOnCursor(result.cursor.asItemStack())
            }

            InventoryAction.PLACE_ALL, InventoryAction.PLACE_SOME, InventoryAction.PLACE_ONE -> {
                val item = event.cursor
                val policy = policy(view, event.rawSlot)
                if (!policy.mayInsert(item)) {
                    event.isCancelled = true
                    return
                }
                if (policy.capacity < minOf(item.maxStackSize, inventoryLimit(event, event.rawSlot))) {
                    event.isCancelled = true
                    val current = event.currentItem?.takeUnless { it.isEmpty }
                    if (current != null && !current.isSimilar(item)) return
                    val moved = minOf(
                        item.amount,
                        if (event.action == InventoryAction.PLACE_ONE) 1 else Int.MAX_VALUE,
                        (policy.capacity - (current?.amount ?: 0)).coerceAtLeast(0),
                    )
                    if (moved == 0) return
                    event.currentItem = item.clone().also { it.amount = (current?.amount ?: 0) + moved }
                    event.whoClicked.setItemOnCursor(item.clone().also { it.amount -= moved })
                }
            }

            InventoryAction.PICKUP_ALL, InventoryAction.PICKUP_SOME, InventoryAction.PICKUP_HALF,
            InventoryAction.PICKUP_ONE, InventoryAction.DROP_ALL_SLOT, InventoryAction.DROP_ONE_SLOT -> {
                val item = event.currentItem ?: return
                if (!policy(view, event.rawSlot).mayExtract(item)) event.isCancelled = true
            }

            InventoryAction.SWAP_WITH_CURSOR -> {
                val current = event.currentItem ?: ItemStack.empty()
                val incoming = event.cursor
                val policy = policy(view, event.rawSlot)
                if (!policy.mayExtract(current) ||
                    !policy.mayInsert(incoming) ||
                    incoming.amount > policy.capacity
                ) {
                    event.isCancelled = true
                }
            }

            InventoryAction.HOTBAR_SWAP, InventoryAction.HOTBAR_MOVE_AND_READD -> {
                val topSize = event.view.topInventory.size
                val offhand = event.click == ClickType.SWAP_OFFHAND
                val target = if (offhand) topSize + 36 else topSize + 27 + event.hotbarButton
                if (!offhand && event.hotbarButton !in 0..8) {
                    event.isCancelled = true
                    return
                }
                val incoming = if (offhand) view.player.inventory.itemInOffHand else view.player.inventory.getItem(event.hotbarButton)
                val outgoing = event.currentItem
                val sourcePolicy = policy(view, event.rawSlot)
                val targetPolicy = policy(view, target)
                if ((
                        incoming != null &&
                            !incoming.isEmpty &&
                            (
                                !sourcePolicy.mayInsert(incoming) ||
                                    !targetPolicy.mayExtract(incoming) ||
                                    incoming.amount > sourcePolicy.capacity
                                )
                        ) ||
                    (
                        outgoing != null &&
                            !outgoing.isEmpty &&
                            (
                                !sourcePolicy.mayExtract(outgoing) ||
                                    !targetPolicy.mayInsert(outgoing) ||
                                    outgoing.amount > targetPolicy.capacity
                                )
                        )
                ) {
                    event.isCancelled = true
                }
            }

            // Creative cloning is not an item transfer. It is deliberately unavailable in managed inventories.
            InventoryAction.CLONE_STACK, InventoryAction.UNKNOWN -> event.isCancelled = true
            else -> Unit
        }
    }

    fun drag(event: InventoryDragEvent, view: ContainerInterfaceView<*, *>) {
        view.flushItemChanges()
        if (event.newItems.any { (slot, item) ->
                val policy = policy(view, slot)
                (slot < event.view.topInventory.size && !view.isManagedSlot(slot)) ||
                    !policy.mayInsert(item) ||
                    item.amount > policy.capacity
            }
        ) {
            event.isCancelled = true
            return
        }
        queueUpdate(view)
    }

    private fun slots(view: ContainerInterfaceView<*, *>): List<TransferSlot<ItemStack>> {
        val inventory = view.player.openInventory
        val size = inventory.topInventory.size
        return List(size + 36) { rawSlot ->
            val editable = rawSlot >= size || view.isManagedSlot(rawSlot)
            TransferSlot(
                stack = inventory.getItem(rawSlot).asTransferStack(),
                capacity = minOf(
                    policy(view, rawSlot).capacity,
                    if (rawSlot < size) inventory.topInventory.maxStackSize else inventory.bottomInventory.maxStackSize,
                ),
                mayExtract = editable && inventory.getItem(rawSlot)?.let { policy(view, rawSlot).mayExtract(it) } != false,
                accepts = { editable && policy(view, rawSlot).mayInsert(it.asItemStack()) },
            )
        }
    }

    private fun apply(event: InventoryClickEvent, changes: Map<Int, TransferStack<ItemStack>?>) {
        for ((slot, stack) in changes) event.view.setItem(slot, stack?.asItemStack())
    }

    private fun ItemStack?.asTransferStack(): TransferStack<ItemStack>? = this
        ?.takeUnless { it.isEmpty }
        ?.let { TransferStack(it.clone(), it.amount, it.maxStackSize) }

    private fun TransferStack<ItemStack>.asItemStack(): ItemStack = item.clone().also { it.amount = amount }

    private fun policy(view: ContainerInterfaceView<*, *>, slot: Int): ManagedSlotPolicy {
        val configured = view.backing.builder.managedSlotPolicy(view, slot)
        return ManagedSlotPolicy(
            capacity = configured.capacity,
            mayInsert = { InterfacesListeners.itemTransferAllowed(it) && configured.mayInsert(it) },
            mayExtract = { InterfacesListeners.itemTransferAllowed(it) && configured.mayExtract(it) },
        )
    }

    private fun inventoryLimit(event: InventoryClickEvent, slot: Int): Int =
        if (slot < event.view.topInventory.size) event.view.topInventory.maxStackSize else event.view.bottomInventory.maxStackSize

    private fun queueUpdate(view: ContainerInterfaceView<*, *>) {
        if (!pendingUpdates.add(view)) return
        Bukkit.getScheduler().runTask(
            plugin,
            Runnable {
                pendingUpdates.remove(view)
                if (view.isOpen()) view.flushItemChanges()
            }
        )
    }
}
