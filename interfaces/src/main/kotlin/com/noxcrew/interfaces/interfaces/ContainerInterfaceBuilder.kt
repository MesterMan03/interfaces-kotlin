package com.noxcrew.interfaces.interfaces

import com.noxcrew.interfaces.pane.ContainerPane
import com.noxcrew.interfaces.view.ContainerInterfaceView
import net.kyori.adventure.text.Component
import org.bukkit.entity.Player

/** Assists in building a container interface. */
public abstract class ContainerInterfaceBuilder<P : ContainerPane, I : ContainerInterface<I, P>> : InterfaceBuilder<P, I>() {

    public companion object {
        /** The maximum number of rows for a default container GUI. */
        public const val MAX_NUMBER_OF_ROWS: Int = 6
    }

    /** A simple instance of a chest-based container. */
    public class Simple : ContainerInterfaceBuilder<ContainerPane, ContainerInterface.Simple>() {
        override fun build(): ContainerInterface.Simple = ContainerInterface.Simple(this)
    }

    /** Sets the amount of rows for this interface to use. */
    public var rows: Int = 1

    /** The type of player inventory being included. */
    public var playerInventoryType: PlayerInventoryType = PlayerInventoryType.DEFAULT

    /**
     * Owns the contents of modifiable top slots independently of transforms and routes shift/double clicks safely.
     * Only real chest inventories with an unmodified player inventory are supported. Disabled for legacy menus.
     */
    public var manageItems: Boolean = false

    /** Runs on the server thread after actual item changes, coalesced per tick, while this managed view is open. */
    public var onItemsChanged: (ContainerInterfaceView<*, *>) -> Unit = {}

    /** Supplies the title of this interface. */
    public var titleSupplier: (suspend (Player) -> Component?)? = null
}
