package com.noxcrew.interfaces.inventory

import com.noxcrew.interfaces.transfer.InventoryTransfers
import com.noxcrew.interfaces.transfer.TransferSlot
import com.noxcrew.interfaces.transfer.TransferStack
import com.noxcrew.interfaces.view.ContainerInterfaceView
import org.bukkit.Bukkit
import org.bukkit.event.inventory.InventoryAction
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.Plugin

/** Routes transfers for real chest inventories without letting display elements become transfer targets. */
internal class ManagedInventoryInteractions(private val plugin: Plugin) {
    private val pendingUpdates = mutableMapOf<ContainerInterfaceView<*, *>, List<ItemStack?>>()

    /** Handles item movement; protected button clicks remain the responsibility of the normal click handler. */
    fun click(event: InventoryClickEvent, view: ContainerInterfaceView<*, *>) {
        queueUpdate(view)
        when (event.action) {
            InventoryAction.MOVE_TO_OTHER_INVENTORY -> {
                event.isCancelled = true
                val size = event.view.topInventory.size
                val source = event.rawSlot
                if (source !in 0 until size + 36) return
                val targets = if (source < size) (size until size + 36).reversed() else (0 until size).toList()
                val changes = InventoryTransfers.move(slots(view), source, targets, ItemStack::isSimilar)
                apply(event, changes)
            }

            InventoryAction.COLLECT_TO_CURSOR -> {
                event.isCancelled = true
                val cursor = event.cursor.asTransferStack() ?: return
                val result = InventoryTransfers.collect(slots(view), cursor, ItemStack::isSimilar)
                apply(event, result.changes)
                event.whoClicked.setItemOnCursor(result.cursor.asItemStack())
            }

            // Creative cloning is not an item transfer. It is deliberately unavailable in managed inventories.
            InventoryAction.CLONE_STACK, InventoryAction.UNKNOWN -> event.isCancelled = true
            else -> Unit
        }
    }

    fun drag(event: InventoryDragEvent, view: ContainerInterfaceView<*, *>) {
        if (event.rawSlots.any { it < event.view.topInventory.size && !view.isManagedSlot(it) }) {
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
                capacity = if (rawSlot < size) inventory.topInventory.maxStackSize else inventory.bottomInventory.maxStackSize,
                mayExtract = editable,
                accepts = { editable },
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

    private fun snapshot(view: ContainerInterfaceView<*, *>): List<ItemStack?> = buildList {
        addAll(view.managedContents().values)
        addAll(view.player.inventory.contents.map { it?.clone() })
    }

    private fun queueUpdate(view: ContainerInterfaceView<*, *>) {
        if (view in pendingUpdates) return
        pendingUpdates[view] = snapshot(view)
        Bukkit.getScheduler().runTask(
            plugin,
            Runnable {
                val before = pendingUpdates.remove(view) ?: return@Runnable
                if (!view.isOpen() || before == snapshot(view)) return@Runnable
                view.executeSync(
                    com.noxcrew.interfaces.exception.InterfacesExceptionContext(
                        view.player,
                        com.noxcrew.interfaces.exception.InterfacesOperation.RUNNING_CLICK_HANDLER,
                        view,
                    ),
                ) {
                    view.backing.builder.onItemsChanged(view)
                }
            }
        )
    }
}
