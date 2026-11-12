package com.noxcrew.interfaces.view

import com.noxcrew.interfaces.InterfacesListeners
import com.noxcrew.interfaces.element.CompletedElement
import com.noxcrew.interfaces.exception.InterfacesExceptionContext
import com.noxcrew.interfaces.exception.InterfacesOperation
import com.noxcrew.interfaces.grid.GridPoint
import com.noxcrew.interfaces.interfaces.ContainerInterface
import com.noxcrew.interfaces.interfaces.PlayerInventoryType
import com.noxcrew.interfaces.inventory.CachedInterfacesInventory
import com.noxcrew.interfaces.inventory.ContainerInterfacesInventory
import com.noxcrew.interfaces.inventory.FakedContainerInterfacesInventory
import com.noxcrew.interfaces.pane.ContainerPane
import com.noxcrew.interfaces.transfer.InventorySession
import com.noxcrew.interfaces.utilities.TitleState
import io.papermc.paper.adventure.PaperAdventure
import net.kyori.adventure.text.Component
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.inventory.ChestMenu
import net.minecraft.world.inventory.MenuType
import org.bukkit.craftbukkit.entity.CraftPlayer
import org.bukkit.craftbukkit.event.CraftEventFactory
import org.bukkit.entity.Player
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack

/** Implements a container view. */
public class ContainerInterfaceView<I : ContainerInterface<I, P>, P : ContainerPane> internal constructor(
    player: Player,
    backing: I,
    parent: InterfaceView?,
) : AbstractInterfaceView<CachedInterfacesInventory, I, P>(
    player,
    backing,
    parent,
),
    InventoryHolder {
    private companion object {
        /** All possible menu types. */
        private val TYPES: List<MenuType<*>> = listOf(
            MenuType.GENERIC_9x1,
            MenuType.GENERIC_9x2,
            MenuType.GENERIC_9x3,
            MenuType.GENERIC_9x4,
            MenuType.GENERIC_9x5,
            MenuType.GENERIC_9x6,
        )
    }

    override val titleState: TitleState = TitleState(initialSupplier = backing.titleSupplier)

    private val managedSlots = linkedSetOf<GridPoint>()
    private val itemSession = InventorySession<Contents> { it.detached() }

    private data class Contents(val top: Map<GridPoint, ItemStack?>, val player: List<ItemStack?>) {
        fun detached(): Contents = Contents(top.mapValues { it.value?.clone() }, player.map { it?.clone() })
    }

    private fun contents(): Contents = Contents(managedContents(), player.inventory.contents.map { it?.clone() })

    /** Flushes actual mutations before another domain operation can read a stale offer, recipe, or storage snapshot. */
    public fun flushItemChanges() {
        if (!backing.builder.manageItems) return
        val change = itemSession.update(contents()) ?: return
        executeSync(InterfacesExceptionContext(player, InterfacesOperation.RUNNING_CLICK_HANDLER, this)) {
            if (change.before.top != change.after.top) {
                backing.builder.onItemsCommitted(this, change.before.top, change.after.top)
            }
            backing.builder.onItemsChanged(this)
        }
    }

    override fun onOpen() {
        super.onOpen()
        if (!itemSession.isOpen) {
            executeSync(InterfacesExceptionContext(player, InterfacesOperation.RUNNING_CLICK_HANDLER, this)) {
                backing.builder.onOpened(this)
            }
            itemSession.open(contents())
        }
    }

    /** Whether [slot] in the top inventory belongs to item storage rather than the UI renderer. */
    public fun isManagedSlot(slot: Int): Boolean = GridPoint.fromBukkitChestSlot(slot) in managedSlots

    /** A detached snapshot of all managed slots, including empty slots. Safe to use when saving on close. */
    public fun managedContents(): Map<GridPoint, ItemStack?> = managedSlots.associateWith {
        inventory?.get(it.x, it.y)?.clone()
    }

    /** Clears and returns the managed items exactly once; subsequent calls return an empty map. Call on the server thread. */
    public fun drainManagedItems(): Map<GridPoint, ItemStack> {
        check(org.bukkit.Bukkit.isPrimaryThread()) { "Managed items must be drained on the server thread" }
        val result = managedContents().mapNotNull { (point, stack) ->
            stack?.takeUnless { it.isEmpty }?.let { point to it }
        }.toMap()
        for (point in result.keys) inventory?.set(point.x, point.y, null)
        return result
    }

    /** Called before the old inventory is retired so a delayed coroutine cannot drain a subsequently reopened view. */
    internal fun returnManagedItems(reason: InventoryCloseEvent.Reason) {
        flushItemChanges()
        if (!itemSession.close()) return
        executeSync(InterfacesExceptionContext(player, InterfacesOperation.CLOSE_HANDLER, this)) {
            backing.builder.onClosed(reason, this)
        }
        if (!backing.builder.manageItems || !backing.builder.returnManagedItemsOnClose) return
        for (item in drainManagedItems().values) {
            for (leftover in player.inventory.addItem(item).values) {
                player.world.dropItemNaturally(player.location, leftover)
            }
        }
    }

    override fun drawManagedSlot(row: Int, column: Int, element: CompletedElement?): Boolean {
        if (!backing.builder.manageItems || row !in 0 until backing.rows) return false
        val point = GridPoint.at(row, column)
        if (point in managedSlots) return true
        if (element?.isSlotModifiable != true) return false
        managedSlots += point
        currentInventory.set(row, column, element.itemStack?.clone())
        return true
    }

    override fun createInventory(): CachedInterfacesInventory {
        val retained = managedContents()
        val created = if (backing.builder.playerInventoryType == PlayerInventoryType.FAKE) {
            FakedContainerInterfacesInventory(
                holder = this,
                player = player,
                rows = backing.rows,
                mapper = backing.mapper,
            )
        } else {
            ContainerInterfacesInventory(
                holder = this,
                player = player,
                title = titleState.current,
                rows = backing.rows,
                includesPlayerInventory = backing.includesPlayerInventory,
                mapper = backing.mapper,
                inventoryType = backing.builder.inventoryType,
            )
        }
        for ((point, item) in retained) created.set(point.x, point.y, item)
        return created
    }

    override fun openInventory() {
        if (backing.builder.playerInventoryType == PlayerInventoryType.FAKE) {
            val inventory = currentInventory as FakedContainerInterfacesInventory
            val nmsPlayer = (player as CraftPlayer).handle
            if (nmsPlayer.containerMenu !== nmsPlayer.inventoryMenu) {
                nmsPlayer.connection.handleContainerClose(
                    ServerboundContainerClosePacket(nmsPlayer.containerMenu.containerId),
                    org.bukkit.event.inventory.InventoryCloseEvent.Reason.OPEN_NEW,
                )
            }
            var container: AbstractContainerMenu = ChestMenu(
                TYPES[backing.rows - 1],
                nmsPlayer.nextContainerCounter(),
                inventory.playerInventory,
                inventory.chestInventory,
                backing.rows,
            )
            val result = CraftEventFactory.callInventoryOpenEventWithTitle(nmsPlayer, container)
            container = result.second ?: return

            // Inform the client to open up the screen, then initialize the container!
            if (!nmsPlayer.isImmobile) {
                nmsPlayer.connection.send(
                    ClientboundOpenScreenPacket(
                        container.containerId,
                        container.type,
                        PaperAdventure.asVanilla(result.first ?: title() ?: Component.empty()),
                    ),
                )
            }
            nmsPlayer.containerMenu = container
            nmsPlayer.initMenu(container)

            // Send the off-hand item in the custom inventory
            nmsPlayer.connection.send(
                ClientboundContainerSetSlotPacket(
                    nmsPlayer.inventoryMenu.containerId,
                    nmsPlayer.inventoryMenu.incrementStateId(),
                    net.minecraft.world.inventory.InventoryMenu.SHIELD_SLOT,
                    inventory.playerInventory.equipment.get(EquipmentSlot.OFFHAND),
                ),
            )
        } else {
            player.openInventory(getInventory())
        }

        // Mark down that we've finished rendering this menu
        InterfacesListeners.INSTANCE.completeRendering(player.uniqueId, this)
    }

    override fun getInventory(): Inventory = when (currentInventory) {
        is FakedContainerInterfacesInventory -> {
            (currentInventory as FakedContainerInterfacesInventory).bukkitInventory
        }

        is ContainerInterfacesInventory -> {
            (currentInventory as ContainerInterfacesInventory).chestInventory
        }

        else -> {
            throw IllegalStateException("Illegal inventory type ${currentInventory::class.java}")
        }
    }

    override fun isOpen(): Boolean = player.openInventory.topInventory.getHolder(false) == this
}
