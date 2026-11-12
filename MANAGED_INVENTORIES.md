# Managed inventories

This fork adds an opt-in foundation for real item inventories. Legacy navigation menus keep their existing behavior.
The current slice handles ordinary chest storage; filtered input/output policies and transactional crafting are subsequent work.

```kotlin
buildChestInterface {
    rows = 3
    manageItems = true
    onItemsChanged = { view -> view.redrawComplete() }
    returnPlacedIntoInventoryOnClose()

    withTransform { pane, _ ->
        for (column in 0 until 9) {
            pane[1, column] = StaticElement(Drawable.empty(), isSlotModifiable = true)
        }
        pane[0, 0] = StaticElement(drawable(Material.DIAMOND))
    }
}
```

Declare item slots in a blocking transform. A slot becomes storage when first drawn with `isSlotModifiable = true`.
Its drawable supplies its initial contents only. The view then owns that slot for its lifetime, including after a transform
removes or replaces its element. Use a new view when changing the storage layout; never repurpose occupied storage as a button.
Storage elements do not run click callbacks: native clicks, dragging, hotbar/offhand swaps, and dropping remain native operations.
Buttons and protected previews continue to run normal library callbacks.

Shift-click uses a complete transfer plan: merge eligible stacks, then use empty eligible slots, respecting item and inventory limits.
Double-click collects eligible items without extracting identical display stacks. Transfers run at `HIGHEST` and respect earlier
plugin cancellation. A later plugin must not uncancel a protected or manually handled transfer.
Mixed drags touching a protected slot are rejected as one operation. Creative cloning and unknown inventory actions are rejected.
Player/combined/fake interfaces are intentionally excluded: use the default real player inventory beneath a chest interface.

`onItemsChanged` runs on the server thread on the next tick, once per affected view, after comparing actual contents.
It is a visible-view refresh hook, not a persistence or accounting hook. It does not run for a view that has closed.
`managedContents()` returns cloned slot contents for persistence; `drainManagedItems()` clears and returns contents on the server
thread for ownership handoff. Do not save/return the same items through both mechanisms.
For temporary item entry, `returnPlacedIntoInventoryOnClose()` drains slots and returns items, dropping inventory overflow at the
player. Managed returns run synchronously on actual close/switch/disconnect events before retiring the old inventory.
For persistent storage, omit that helper and persist contents through the application's close lifecycle.
The upstream coroutine-based close lifecycle still needs dedicated migration tests before using persistent storage in production.

## Validation

Run `./gradlew spotlessApply build`. Pure planner tests cover protected previews, full stacks, merge ordering, metadata identity,
filters, custom limits, and 5,000 deterministic randomized movement/collection scenarios with item conservation checks.
The planner's filter/capacity contracts are not yet exposed as managed-slot policies in the Bukkit adapter.

Run the examples plugin with `./gradlew :examples:runServer`, then open `/interfaces modifiable-area` on a disposable test server.
Its protected dirt previews deliberately match an item you can deposit. Each actual item change redraws the menu; the clock also
requests a redraw. Check:

- Left/right click, split stacks, drag across top/bottom inventories, and partially merge through shift-click in both directions.
- Double-click dirt while protected dirt previews are present; previews must remain intact.
- Full destinations, metadata-distinct items, custom stack limits, number keys, offhand swaps, and Q/Ctrl-Q.
- Redraw with occupied storage and clear a slot before redrawing; deposited items must survive and cleared slots must stay empty.
- Close with a full player inventory, reopen, disconnect, and switch menus; no item may disappear or be returned twice.
- Cancellation by a second plugin before `HIGHEST`; no transfer may happen for that event.

No production ChronoCore screen has been migrated yet. Crafting consumption/output transactions, filtered incubator slots,
backpack page persistence/locking, trade readiness/settlement, and committed accounting callbacks require their own integrations.
