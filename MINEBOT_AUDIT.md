# MineBot Audit Report

## What this repo does

This is a RusherHack plugin (Fabric mod for Minecraft) called MineBot. It automates mining a chosen ore using Baritone (a pathfinding/mining bot for Minecraft), deposits the ore in a chest, and repeats. It also optionally mines coal and puts it in a separate coal chest. Junk items are thrown away while mining to keep inventory space free for ore.

**Load assumption:** Single player running the mod in a Minecraft client with Baritone and RusherHack installed. The expected load is one person running this automation - it's a client-side Minecraft mod. Not a server or high-concurrency system.

---

## Findings

### Must Fix

1. **Baritone reflection breaks silently when Baritone updates** (`src/main/java/org/minebot/BaritoneBridge.java:48-87`)
   - **What this is:** BaritoneBridge uses reflection to find Baritone's internal API interfaces and methods at runtime.
   - **Problem:** The `findMethod` tries multiple fallback names, but if Baritone changes method signatures (e.g., `mine(int, String[])` → `mine(int, List<String>)`), the reflection succeeds but calls the wrong overload, or fails at runtime with `InvocationTargetException`. No version check exists.
   - **Fix:** Add a version check at startup. Read Baritone's version from its `baritone.mixin.json` or `fabric.mod.json` and verify compatibility. Fail fast with a clear message if versions don't match.
   - **If we skip it:** A Baritone update silently breaks mining/pathing with confusing errors like "Could not change Baritone's exploreForBlocks setting" or the bot just stands still.

2. **Stuck detection uses player eye position, not block position** (`src/main/java/org/minebot/MineBotModule.java:586-605, 771-790`)
   - **What this is:** The stuck check compares `mc.player.getEyePosition()` every 100 ticks (5 seconds) and expects ≥1 block movement.
   - **Problem:** Eye position includes head bobbing, sneaking, and pitch/yaw changes. A player standing on a block but looking around can move >1 block in eye position. Conversely, Baritone pathing through a 1-block gap makes minimal eye-position progress while actually moving.
   - **Fix:** Track `mc.player.blockPosition()` (or `getEyePosition().subtract(0, 1.62, 0)` for feet) instead. Compare block positions, not eye positions.
   - **If we skip it:** False stuck detections cancel pathing unnecessarily, or real stuck situations are missed.

3. **`waitForBaritoneCancel` blocks the game thread for up to 5 seconds** (`src/main/java/org/minebot/MineBotModule.java:381-400`)
   - **What this is:** During preflight, this method sleeps the main thread in 50ms increments while polling Baritone.
   - **Problem:** `Thread.sleep` on the main game thread freezes the entire Minecraft client (rendering, input, network) for up to 5 seconds. On a laggy server this is very noticeable.
   - **Fix:** Convert to a non-blocking state. Set a "waiting for Baritone" flag, return from `tick()`, and check again next tick. Or use Baritone's callback/event system if available.
   - **If we skip it:** The game visibly freezes for several seconds when enabling the module.

4. **No protection against chest inventory desync during deposit** (`src/main/java/org/minebot/MineBotModule.java:650-727`)
   - **What this is:** `tickDeposit` shift-clicks items into the chest one per tick with a configurable delay.
   - **Problem:** If the server rejects a shift-click (anti-cheat, lag, chest full), the client still thinks the item moved. The code only tracks click attempts per slot (max 2), but doesn't verify the item actually left the player's inventory. A desync means items stay in inventory, the bot thinks they're deposited, and on the next trip the inventory is still full → infinite loop or fail.
   - **Fix:** After each `quickMove`, verify the source slot is empty (or count decreased). If not, retry or fail. Add a max-total-items-deposited-per-visit guard.
   - **If we skip it:** On servers with container anti-cheat or lag, the bot deposits "ghost" items, returns to mine with full inventory, and either loops forever or stops with "inventory still nearly full."

5. **`testChestPermission` always returns true, defeating its purpose** (`src/main/java/org/minebot/MineBotModule.java:1083-1085`)
   - **What this is:** Preflight calls this to verify chest access before starting.
   - **Problem:** It's hardcoded to `return true` with a comment "On anarchy servers (9b9t) there is no protection." But the preflight still calls it and would fail if it returned false.
   - **Fix:** Either implement a real permission check (attempt a dummy interaction and catch the rejection), or remove the check and the calls to it. Don't leave dead code that pretends to validate.
   - **If we skip it:** On protected servers, the bot walks to the chest, tries to open it, fails silently, and eventually times out with a confusing "stuck" message.

### Should Fix

6. **`hasItemsFor` doesn't scan shulker boxes despite comment claiming it does** (`src/main/java/org/minebot/MineBotModule.java:898-910`)
   - **What this is:** The method checks if the player has items a chest would accept, to decide whether to visit that chest.
   - **Problem:** The comment says "Includes H6: recursive scanning of shulker boxes in inventory" but the code only iterates slots 0-35 (main + hotbar). Shulker box contents are not scanned.
   - **Fix:** Either implement recursive shulker box scanning (using `ItemStack.getTag().getCompound("BlockEntityTag").getList("Items")`), or delete the misleading comment.
   - **If we skip it:** The bot skips a chest thinking it has nothing to deposit, but items inside shulker boxes never get deposited. Inventory stays full, bot eventually fails.

7. **Junk dropping while mining doesn't respect `JunkDropInterval` correctly** (`src/main/java/org/minebot/MineBotModule.java:496-503`)
   - **What this is:** Junk is dropped every `JunkDropInterval` ticks during mining.
   - **Problem:** The cooldown decrements once per tick in `tickMining`, but `tickMining` runs once per game tick only when `state == MINING`. If the bot is in another state (walking, depositing), the cooldown doesn't tick down. When it returns to MINING, the cooldown may be stale.
   - **Fix:** Move the cooldown decrement outside the state switch, or use a wall-clock `Instant` like the timeout tracking does.
   - **If we skip it:** Junk dropping becomes irregular - sometimes too frequent (spam), sometimes too rare (inventory fills up).

8. **`parseList` allows empty strings from trailing commas, creating empty entries in sets** (`src/main/java/org/minebot/MineBotModule.java:1133-1145`)
   - **What this is:** Parses comma-separated settings like `JunkItems`.
   - **Problem:** `"a, b, "` splits to `["a", " b", ""]`, trims to `["a", "b", ""]`, and adds `""` to the set. An empty string in `junkSet` or `oreItems` matches nothing but wastes lookup time and confuses debugging.
   - **Fix:** Add `if (s.isEmpty()) continue;` after trimming.
   - **If we skip it:** Harmless but sloppy. Empty strings in sets are a code smell.

9. **`validateCustomBlocksAndItems` builds full registry sets every preflight** (`src/main/java/org/minebot/MineBotModule.java:427-470`)
   - **What this is:** Validates custom block/item names against Minecraft registries.
   - **Problem:** Streams over `BuiltInRegistries.BLOCK.keySet()` and `ITEM.keySet()` every time the module enables. These registries have ~2000 entries each. Acceptable for one-time preflight, but the resulting `HashSet` is thrown away after validation.
   - **Fix:** Cache the valid block/item name sets in static fields, or skip validation entirely (Baritone will fail at runtime with a clear error if a block name is wrong).
   - **If we skip it:** Minor startup lag (~5-10ms). Not a real problem, just unnecessary work.

10. **`clickAttempts` uses UI slot index but `accepts` uses container slot** (`src/main/java/org/minebot/MineBotModule.java:686-690`)
    - **What this is:** Tracks how many times a slot was clicked to detect stuck deposits.
    - **Problem:** Line 686 uses `slot.index` (UI slot, varies by container type), but line 679 passes `slot.getContainerSlot()` (player inventory index 0-35) to `accepts`. The map key and the acceptance logic use different slot numbering.
    - **Fix:** Use `slot.getContainerSlot()` consistently for the `clickAttempts` map key, since that's what `accepts` expects.
    - **If we skip it:** Stuck detection miscounts. A slot might be marked "stuck" after 1 click (different UI index) or allowed 3+ clicks (same container slot, different UI index).

11. **No test for `hasItemsFor` shulker box claim or `validateCustomBlocksAndItems`** (`src/test/java/org/minebot/MineBotModuleLogicTest.java`)
    - **What this is:** The test file covers parsing, junk logic, ore items, mine names, accepts, describeTarget.
    - **Problem:** The two most complex helper methods (`hasItemsFor` with its false shulker claim, and `validateCustomBlocksAndItems` with registry streaming) have zero tests. `tickDeposit` desync logic is also untested.
    - **Fix:** Add tests for `hasItemsFor` (with/without shulker boxes), `validateCustomBlocksAndItems` (valid/invalid names), and a test that simulates a failed shift-click to verify desync handling.
    - **If we skip it:** Bugs in these areas (findings 4, 6, 9) won't be caught by tests.

### Nice to Have

12. **Duplicate stuck detection logic in `tickToChest` and `tickToMine`** (`src/main/java/org/minebot/MineBotModule.java:584-605, 770-791`)
    - **What this is:** Both methods have nearly identical 25-line stuck detection blocks.
    - **Fix:** Extract to `private boolean checkStuck(BlockPos target, String label)` returning true if stuck and handled.
    - **Savings:** ~20 lines, single source of truth for stuck thresholds.

13. **`OreType` enum duplicates block/item names for deepslate variants** (`src/main/java/org/minebot/OreType.java:12-20`)
    - **What this is:** Each ore lists both regular and deepslate variants explicitly.
    - **Fix:** Could derive deepslate names programmatically (`"deepslate_" + baseName`), but only saves ~10 lines and adds complexity. Not worth it unless more variants are added.

14. **`BaritoneBridge.load` tries 3 classloaders sequentially on every `available()` call** (`src/main/java/org/minebot/BaritoneBridge.java:245-263`)
    - **What this is:** Lazy initialization of reflection targets.
    - **Problem:** The first call to `available()` tries up to 3 classloaders × ~15 classes = 45 `Class.forName` calls. Subsequent calls return early due to `ready` flag, but the first call is slow.
    - **Fix:** Cache the successful classloader after first success, or parallelize the loader attempts.
    - **If we skip it:** One-time ~50-100ms delay on first enable. Acceptable.

15. **`write_gradle.bat` exists but does nothing useful** (`write_gradle.bat:1-30`)
    - **What this is:** A Windows batch file that appears to rewrite `build.gradle`.
    - **Problem:** It contains hardcoded paths and seems to be a one-time setup script left in the repo. Not used by the build.
    - **Fix:** Delete it.
    - **Savings:** One dead file.

---

## Verdict

**Healthy with caveats.** The core mining/deposit loop is solid and well-structured with good state machine design. The reflection bridge to Baritone is the main architectural risk - it's fragile by nature but mitigated by trying multiple method name fallbacks. The critical issues are the main-thread blocking in preflight (finding 3) and the deposit desync risk (finding 4). Both are fixable with ~20 lines each.

---

## Lean: -47 lines, -1 dependency possible

- Delete `write_gradle.bat` (~30 lines)
- Remove `testChestPermission` and its calls (~15 lines)
- Deduplicate stuck detection (~20 lines, but adds 1 helper method = net -17 lines)
- **Total removable: ~47 lines**
- **Dependencies:** Already minimal. Only RusherHack API, JUnit, Mockito. No bloat.

---

## Not Checked

- **Runtime behavior:** Cannot run the mod (needs Minecraft client, Fabric, Baritone, RusherHack).
- **Baritone API compatibility:** Reflection targets assumed correct for current Baritone version.
- **Multiplayer server interactions:** Anti-cheat, container sync, protection plugins - only inferred from code.
- **Custom ore mode:** Not exercised by tests beyond basic parsing.
- **Dimension change handling:** Logic exists but untested (Overworld/Nether/End transitions).