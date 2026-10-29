# MineBot (RusherHack Plugin)

Mines the ore you pick with Baritone. When the inventory is nearly full it walks to your ore chest, deposits, walks back to where it was mining and repeats. Optionally it also mines coal and puts it in a separate coal chest. Junk is thrown away while mining so each run carries as much ore as possible.

## Install

1. Build the JAR: `./gradlew build`
2. Copy `build/libs/minebot-<version>.jar` to your `rusherhack/plugins/` folder
3. Restart Minecraft or reload plugins

## Use

1. Look at the ore chest, run `*minechest ore`
2. Coal only: look at the coal chest, run `*minechest coal`
3. Open the MineBot module, pick the **Ore**, turn on **MineCoal** if wanted, enable the module. Pickaxe in the hotbar.
4. `*minechest` alone shows the status. `*minebot` is the module's own settings command that RusherHack creates.

## Settings

| Setting | Default | Description |
|---|---|---|
| Ore | Copper | Copper, Iron, Gold, Diamond, Redstone, Lapis, Emerald, Quartz, AncientDebris, Custom |
| CustomBlocks / CustomItems | empty | Only used when Ore is Custom. Comma separated, e.g. `ancient_debris` |
| OreChestSet + OreChestX/Y/Z | off | Set with `*minechest ore`, or type the coordinates and tick OreChestSet |
| MineCoal | off | Also mine coal. Coal goes to the coal chest, never the ore chest |
| CoalChestSet + CoalChestX/Y/Z | off | Set with `*minechest coal` |
| DepositEverything | off | Everything from the main inventory goes in the ore chest (coal still goes to the coal chest) |
| DropJunk | on | Throw JunkItems away while mining and at the chest |
| JunkItems | cobblestone, cobbled_deepslate, dirt, gravel, tuff, andesite, diorite, granite, netherrack, dripstone_block, pointed_dripstone, calcite, flint | Only these are ever thrown. Your ore and coal are always protected |
| KeepBuildingBlocks | on | Keeps your biggest stack of cobblestone/dirt/netherrack so Baritone can bridge and pillar |
| JunkDelay | 20 | Ticks between throws while mining |
| ExploreForOre | off | Off: Baritone stops when no ore is nearby. On: it wanders off looking for ore |
| MinFreeSlots | 3 | Go to the chest when fewer free slots than this |
| TransferDelay | 3 | Ticks between chest clicks |
| TimeoutSeconds | 300 | Max for each walking/chest step (not mining) |
| ReturnToMine | on | Walk back to where mining stopped |
| BlockBreakBlacklist | chest, trapped_chest, hopper, furnace, blast_furnace, smoker, barrel, dropper, dispenser, shulker_box | Blocks Baritone will never break |

## Stops on its own when

- You die
- A chest is full
- No ore is found nearby (ExploreForOre off)
- It cannot reach or open a chest
- Inventory is still nearly full after a trip (add the leftovers to JunkItems, or use DepositEverything)
- Baritone is not found

## Features

- **Baritone integration** — Uses reflection for version-independent Baritone API access
- **KillAura compatible** — Pauses mining when attacked so KillAura can equip sword
- **Chest protection** — Block break blacklist prevents Baritone from breaking containers
- **Desync recovery** — Detects and recovers from container desync during deposit
- **Non-blocking** — No main-thread freezes during Baritone operations

## Build Reference

- Minecraft 1.21.11
- Fabric Loader 0.16.9
- Fabric Loom 1.9-SNAPSHOT
- RusherHack API 1.21.11-SNAPSHOT
- Java 21

## Commands

| Command | Description |
|---|---|
| `*minechest ore` | Set ore chest at crosshair |
| `*minechest coal` | Set coal chest at crosshair |
| `*minechest` | Show current chest positions |
| `*minebot` | Open module settings |