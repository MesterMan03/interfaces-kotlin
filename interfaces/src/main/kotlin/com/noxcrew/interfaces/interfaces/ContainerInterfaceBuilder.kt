package com.noxcrew.interfaces.interfaces

import com.noxcrew.interfaces.grid.GridPoint
import com.noxcrew.interfaces.inventory.ManagedSlotPolicy
import com.noxcrew.interfaces.pane.ContainerPane
import com.noxcrew.interfaces.view.ContainerInterfaceView
import net.kyori.adventure.text.Component
import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.inventory.InventoryType
import org.bukkit.inventory.ItemStack

/** Assists in building a container interface. */
public abstract class ContainerInterfaceBuilder<P : ContainerPane, I : ContainerInterface<I, P>> : InterfaceBuilder<P, I>() {

    public companion object {
        internal val DEFAULT_SLOT_POLICY: (ContainerInterfaceView<*, *>, Int) -> ManagedSlotPolicy = { _, _ -> ManagedSlotPolicy.OPEN }

        /** The maximum number of rows for a default container GUI. */
        public const val MAX_NUMBER_OF_ROWS: Int = 6
    }

    /** A simple instance of a chest-based container. */
    public class Simple : ContainerInterfaceBuilder<ContainerPane, ContainerInterface.Simple>() {
        override fun build(): ContainerInterface.Simple = ContainerInterface.Simple(this)
    }

    /** Sets the amount of rows for this interface to use. */
    public var rows: Int = 1

    /** A native creatable inventory type; chest inventories use [rows], other types use their native slot count. */
    public var inventoryType: InventoryType = InventoryType.CHEST

    /** The type of player inventory being included. */
    public var playerInventoryType: PlayerInventoryType = PlayerInventoryType.DEFAULT

    /**
     * Owns the contents of modifiable top slots independently of transforms and routes shift/double clicks safely.
     * Requires a real container with an unmodified player inventory. Disabled for legacy menus.
     * Non-chest native types require all slots to be storage and use native transfer rules without custom slot policies.
     */
    public var manageItems: Boolean = false

    /** Runs on the server thread after actual item changes, coalesced per tick, while this managed view is open. */
    public var onItemsChanged: (ContainerInterfaceView<*, *>) -> Unit = {}

    /** Main-thread ownership hooks for each actual open/close cycle, including switches and disconnects. */
    public var onOpened: (ContainerInterfaceView<*, *>) -> Unit = {}
    public var onClosed: (InventoryCloseEvent.Reason, ContainerInterfaceView<*, *>) -> Unit = { _, _ -> }

    /** Detached managed-slot changes, flushed before another interaction or close even if the next-tick task has not run. */
    public var onItemsCommitted: (ContainerInterfaceView<*, *>, Map<GridPoint, ItemStack?>, Map<GridPoint, ItemStack?>) -> Unit = {
            _,
            _,
            _
        ->
    }

    /** Per-slot rules. Raw slots run through the top inventory then the player inventory; offhand is top size + 36. */
    public var managedSlotPolicy: (ContainerInterfaceView<*, *>, Int) -> ManagedSlotPolicy = DEFAULT_SLOT_POLICY

    /** Selects an item in the real player inventory without moving it; this click is always cancelled. */
    public var onPlayerInventoryClick: ((ContainerInterfaceView<*, *>, Int, ItemStack?, ClickType) -> Unit)? = null

    /** Supplies the title of this interface. */
    public var titleSupplier: (suspend (Player) -> Component?)? = null
}
