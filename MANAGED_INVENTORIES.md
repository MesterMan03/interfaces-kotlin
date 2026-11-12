# Managed inventories

This fork adds an opt-in foundation for real item inventories. Legacy navigation menus keep their existing behavior.
Chest storage exposes per-slot filters, capacities and synchronous ownership/accounting hooks. Applications own crafting
and trade transactions; the adapter owns inventory movement.

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
Player/combined/fake interfaces are intentionally excluded: use the default real player inventory beneath a container interface.

`managedSlotPolicy` receives the view and Bukkit raw slot index. `ManagedSlotPolicy` defines `capacity`, `mayInsert`
and `mayExtract`; `LOCKED` prevents movement in both directions. Player slots follow the top inventory; offhand is
`topSize + 36`. Filters apply to shift-click, double-click, drag, pickup, placement, dropping and hotbar/offhand swaps.
`InterfacesListeners.itemTransferAllowed` supplies a plugin-wide item lock, including indirect collection.

`onItemsChanged` runs on the server thread after actual top/player changes. Next-tick notifications are coalesced;
`flushItemChanges()` also flushes synchronously before an interaction or close. `onItemsCommitted` receives detached
before/after managed contents for accounting. Baselines update before callbacks to prevent recursive double accounting.
`onOpened` runs before the initial baseline is captured, allowing persistent contents to be refreshed when reopening a view.
`onClosed` runs once per actual close/switch/disconnect, after flushing, synchronously on the server thread. Title
replacement for the same open view does not restart the ownership cycle. Legacy coroutine close handlers remain available
for non-owning behavior; do not use them to persist managed contents.

`managedContents()` returns cloned contents for persistence; `drainManagedItems()` clears and returns contents on the server
thread for ownership handoff. Persistent screens save and then drain in `onClosed`. For temporary deposits,
`returnPlacedIntoInventoryOnClose()` drains and returns items, dropping overflow. Never persist and return the same items.

`inventoryType` supports creatable native container types with flat slot coordinates (`slot / 9`, `slot % 9`). Non-chest
managed types require every top slot to be storage and the default policy; custom filters need a native slot adapter.
Native quick-move and collection delegate to the server to retain input/fuel/result-slot behavior. Protected native previews
(such as a dispenser preview) should leave `manageItems` disabled.

`onPlayerInventoryClick` selects a cloned stack from the player inventory without moving it in navigation menus.
Set `throttleClicks = false` when protected controls need immediate consecutive clicks; in-flight handlers remain guarded.

## Validation

Run `./gradlew spotlessApply build`. Pure planner tests cover protected previews, full stacks, merge ordering, metadata identity,
filters, custom limits, and 5,000 deterministic randomized movement/collection scenarios with item conservation checks.
Session tests cover close-before-notification, title replacement, reopen baselines, callback detachment and native slot bounds.

Run the examples plugin with `./gradlew :examples:runServer`, then open `/interfaces modifiable-area` on a disposable test server.
Its protected dirt previews deliberately match an item you can deposit. Each actual item change redraws the menu; the clock also
requests a redraw. Check:

- Left/right click, split stacks, drag across top/bottom inventories, and partially merge through shift-click in both directions.
- Double-click dirt while protected dirt previews are present; previews must remain intact.
- Full destinations, metadata-distinct items, custom stack limits, number keys, offhand swaps, and Q/Ctrl-Q.
- Redraw with occupied storage and clear a slot before redrawing; deposited items must survive and cleared slots must stay empty.
- Close with a full player inventory, reopen, disconnect, and switch menus; no item may disappear or be returned twice.
- Cancellation by a second plugin before `HIGHEST`; no transfer may happen for that event.

ChronoCore integrates backpacks, crafting, trading, incubators, guild personal containers and item previews with these APIs.
In-game verification of native events and domain transactions remains necessary before production deployment.
