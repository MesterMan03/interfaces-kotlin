package com.noxcrew.interfaces.transfer

/** A detached before/after snapshot of committed inventory contents. */
public data class InventoryChange<T>(public val before: T, public val after: T)

/**
 * Tracks one actual open/close cycle, independently of rendering and delayed callbacks.
 * [copy] must detach mutable stack values so consumers cannot change the stored baseline.
 */
public class InventorySession<T : Any>(private val copy: (T) -> T) {
    private var contents: T? = null

    /** Whether this view currently owns an open inventory session. */
    public val isOpen: Boolean get() = contents != null

    /** Starts a session; replacement inventories for an already open view do not start another session. */
    public fun open(initial: T): Boolean {
        if (isOpen) return false
        contents = copy(initial)
        return true
    }

    /** Records actual changes before calling domain handlers, preventing recursive or delayed duplicate notification. */
    public fun update(current: T): InventoryChange<T>? {
        val before = contents ?: return null
        if (before == current) return null
        contents = copy(current)
        return InventoryChange(copy(before), copy(current))
    }

    /** Ends the current session exactly once. A later open establishes a new baseline. */
    public fun close(): Boolean {
        if (!isOpen) return false
        contents = null
        return true
    }
}
