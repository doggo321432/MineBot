# MineBot (RusherHack plugin)

Mines the ore you pick with Baritone. When the inventory is nearly full it walks to your ore chest, deposits,
walks back to where it was mining and repeats. Optionally it also mines coal and puts it in a separate coal chest.
Junk is thrown away while mining so each run carries as much ore as possible.

## Installing over CopperBot

1. Delete the old CopperBot jar from `rusherhack/plugins/`.
2. In your existing project, delete `src/main/java/org/copperbot`, then copy this zip's `src` folder over yours.
3. In `gradle.properties` set `plugin_name = minebot`. Keep your own working build files.
4. Build, put `minebot-1.0.0.jar` (name follows plugin_name/plugin_version) in `rusherhack/plugins/`.

Chest positions and settings from CopperBot are not carried over, set them again.

## Use

1. Look at the ore chest, run `*minechest ore`
2. Coal only: look at the coal chest, run `*minechest coal`
3. Open the MineBot module, pick the **Ore**, turn on **MineCoal** if wanted, enable the module. Pickaxe in the hotbar.
4. `*minechest` alone shows the status. `*minebot` is the module's own settings command that RusherHack creates.

Why `minechest` and not `minebot`: RusherHack already makes a command named after every module. Two commands
with one name hide each other, which is why `*copperbot setchest` was missing.

## Settings

| Setting | Default | |
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

## Stops on its own when

you die, a chest is full, no ore is found nearby (ExploreForOre off), it cannot reach or open a chest, the
inventory is still nearly full after a trip (add the leftovers to JunkItems, or use DepositEverything), or Baritone
is not found.

## Build reference (from ContainerTweaks, a RusherHack plugin that supports 1.21.11)

loom plugin `net.fabricmc.fabric-loom-remap` version `1.16-SNAPSHOT`, mappings Mojang + Parchment `2025.12.20`,
dependency `org.rusherhack:rusherhack-api:1.21.11-SNAPSHOT`.
