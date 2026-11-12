package com.noxcrew.interfaces.interfaces

import com.noxcrew.interfaces.grid.mapping.ContainerGridMapper
import com.noxcrew.interfaces.pane.ContainerPane
import com.noxcrew.interfaces.view.ContainerInterfaceView
import com.noxcrew.interfaces.view.InterfaceView
import net.kyori.adventure.text.Component
import org.bukkit.entity.Player
import org.bukkit.event.inventory.InventoryType

/** An interface that uses a container and possibly the player's inventory below. */
public abstract class ContainerInterface<I : ContainerInterface<I, P>, P : ContainerPane> internal constructor(
    override val builder: ContainerInterfaceBuilder<P, I>,
) : Interface<I, P>, TitledInterface {

    init {
        require(builder.inventoryType.isCreatable) { "Inventory type must be creatable" }
        require(!builder.manageItems || builder.playerInventoryType == PlayerInventoryType.DEFAULT) {
            "Managed items require a real chest inventory and the unmodified player inventory"
        }
        require(builder.inventoryType == InventoryType.CHEST || builder.playerInventoryType == PlayerInventoryType.DEFAULT) {
            "Native non-chest inventories require the real player inventory"
        }
        require(
            builder.inventoryType == InventoryType.CHEST ||
                !builder.manageItems ||
                builder.managedSlotPolicy === ContainerInterfaceBuilder.DEFAULT_SLOT_POLICY
        ) {
            "Filtered native inventories require a native menu adapter; only unrestricted native containers are supported"
        }
    }

    /** A simple instance of a container interface. */
    public class Simple(builder: ContainerInterfaceBuilder.Simple) :
        ContainerInterface<Simple, ContainerPane>(builder) {
        override fun createPane(): ContainerPane = ContainerPane(rows, includesPlayerInventory)
    }

    override val titleSupplier: (suspend (Player) -> Component?)? = builder.titleSupplier
    override val rows: Int = if (builder.inventoryType == InventoryType.CHEST) builder.rows else (builder.inventoryType.defaultSize + 8) / 9

    /** Actual top slot count; native inventories need not have a multiple of nine slots. */
    public val size: Int = if (builder.inventoryType == InventoryType.CHEST) rows * 9 else builder.inventoryType.defaultSize
    override val includesPlayerInventory: Boolean = builder.playerInventoryType.includesPlayerInventory
    override val mapper: ContainerGridMapper = ContainerGridMapper(rows, includesPlayerInventory, size)

    override suspend fun open(player: Player, parent: InterfaceView?, reload: Boolean): ContainerInterfaceView<I, P> {
        val view = ContainerInterfaceView(player, this as I, parent)
        view.open(reload)
        return view
    }
}
