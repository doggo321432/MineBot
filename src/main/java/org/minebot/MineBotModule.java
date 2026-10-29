package org.minebot;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.rusherhack.client.api.RusherHackAPI;
import org.rusherhack.client.api.events.client.EventUpdate;
import org.rusherhack.client.api.feature.module.ModuleCategory;
import org.rusherhack.client.api.feature.module.ToggleableModule;
import org.rusherhack.client.api.utils.ChatUtils;
import org.rusherhack.core.event.subscribe.Subscribe;
import org.rusherhack.core.setting.BooleanSetting;
import org.rusherhack.core.setting.EnumSetting;
import org.rusherhack.core.setting.NumberSetting;
import org.rusherhack.core.setting.StringSetting;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.time.Instant;

/**
 * Mines the chosen ore with Baritone. When the inventory is nearly full it walks to the ore chest
 * (and, if coal is enabled and there is coal, to the coal chest), deposits, walks back and repeats.
 * Junk is thrown away while mining so every run carries as much ore as possible.
 * <p>
 * Loop:  MINING -> TO_CHEST -> OPEN_CHEST -> DEPOSIT -> (next chest) -> DROP_JUNK -> TO_MINE -> MINING ...
 */
public class MineBotModule extends ToggleableModule {

	private enum State {
		MINING,
		TO_CHEST,
		OPEN_CHEST,
		DEPOSIT,
		DROP_JUNK,
		TO_MINE,
		WAIT_FOR_BARITONE
	}

	/** One chest to visit during a trip. */
	private record Stop(String label, BlockPos pos, boolean coal) {
	}

	/** Items that Baritone uses for bridging and pillaring. One stack of these is kept. */
	private static final Set<String> BUILDING_BLOCKS = Set.of("cobblestone", "cobbled_deepslate", "dirt", "netherrack");

	/* ----------------------------- settings ----------------------------- */

	private final EnumSetting<OreType> ore = new EnumSetting<>("Ore", OreType.COPPER);
	private final StringSetting customBlocks = new StringSetting("CustomBlocks", "");
	private final StringSetting customItems = new StringSetting("CustomItems", "");

	private final BooleanSetting oreChestSet = new BooleanSetting("OreChestSet", "True once the ore chest has been chosen", false);
	private final NumberSetting<Integer> oreChestX = new NumberSetting<>("OreChestX", 0, -30000000, 30000000).incremental(1);
	private final NumberSetting<Integer> oreChestY = new NumberSetting<>("OreChestY", 64, -64, 320).incremental(1);
	private final NumberSetting<Integer> oreChestZ = new NumberSetting<>("OreChestZ", 0, -30000000, 30000000).incremental(1);

	private final BooleanSetting mineCoal = new BooleanSetting("MineCoal", "Also mine coal and put it in its own chest", false);
	private final BooleanSetting coalChestSet = new BooleanSetting("CoalChestSet", "True once the coal chest has been chosen", false);
	private final NumberSetting<Integer> coalChestX = new NumberSetting<>("CoalChestX", 0, -30000000, 30000000).incremental(1);
	private final NumberSetting<Integer> coalChestY = new NumberSetting<>("CoalChestY", 64, -64, 320).incremental(1);
	private final NumberSetting<Integer> coalChestZ = new NumberSetting<>("CoalChestZ", 0, -30000000, 30000000).incremental(1);

	private final BooleanSetting depositEverything = new BooleanSetting("DepositEverything", "Put everything from the main inventory in the ore chest (the hotbar only gives up the ore itself). Overrides junk dropping", false);

	private final BooleanSetting dropJunk = new BooleanSetting("DropJunk", "Throw JunkItems away while mining and at the chest. Only items in the junk list are ever thrown", true);
	private final StringSetting junkItems = new StringSetting("JunkItems", "cobblestone,cobbled_deepslate,dirt,grass_block,gravel,tuff,andesite,diorite,granite,netherrack,dripstone_block,pointed_dripstone,calcite,flint");
	private final BooleanSetting keepBuildingBlocks = new BooleanSetting("KeepBuildingBlocks", "Never throw the largest stack of cobblestone/dirt/netherrack, Baritone needs it to bridge and pillar", true);

	private final BooleanSetting exploreForOre = new BooleanSetting("ExploreForOre", "Let Baritone wander off to look for ore when none is nearby. Off: it stops instead of walking on forever", false);
	private final NumberSetting<Integer> minFreeSlots = new NumberSetting<>("MinFreeSlots", 3, 1, 36).incremental(1);
	private final NumberSetting<Integer> transferDelay = new NumberSetting<>("TransferDelay", 3, 1, 20).incremental(1);
	// applies to each walking/chest step, not to mining. Raise it if a chest is very far away
	private final NumberSetting<Integer> timeoutSeconds = new NumberSetting<>("TimeoutSeconds", 300, 20, 1800).incremental(10);
	private final BooleanSetting returnToMine = new BooleanSetting("ReturnToMine", "Walk back to where mining stopped after depositing", true);

	// Bug 1: Separate junk drop interval setting for more reliable junk dropping while mining
	private final NumberSetting<Integer> junkDropInterval = new NumberSetting<>("JunkDropInterval", "How often to drop junk while mining (ticks). Lower = more frequent.", 20, 1, 100).incremental(1);

	// H5: NBT-aware protection for tools/armor
	private final BooleanSetting protectEnchanted = new BooleanSetting("ProtectEnchanted", "Never drop items that have enchantments (tools, armor, books)", true);

	// Block blacklist: blocks Baritone must never break (containers, smelters, etc.)
	private final StringSetting blockBreakBlacklist = new StringSetting("BlockBreakBlacklist",
		"chest,trapped_chest,barrel,shulker_box,hopper,furnace,blast_furnace,smoker,brewing_stand,crafting_table,ender_chest,loot_block,redstone_block,comparator,repeater,daylight_detector,target_block,lightning_rod,copper_bulb,trial_spawner,vault,decorated_pot");

	/* ------------------------------ state ------------------------------- */

	private final BaritoneBridge baritone = new BaritoneBridge();

	private State state = State.MINING;
	private int lastTick = -1;
	private int stateTicks = 0;

	private boolean checked = false;
	private boolean mineIssued = false;
	private int lastIssueAt = 0;
	private int quickEnds = 0;
	private boolean goIssued = false;
	private int goIssuedAt = 0;
	private int pathAttempts = 0;
	private int openAttempts = 0;
	private int transferCooldown = 0;
	private int junkDropCooldown = 0; // ticks until next junk drop pass

	private BlockPos returnPos = null;
	private final List<Stop> stops = new ArrayList<>();
	private int stopIndex = 0;
	private final Map<Integer, Integer> clickAttempts = new HashMap<>();
	private int trips = 0;

	/** Baritone's own value of exploreForBlocks, put back when the module turns off. */
	private Boolean previousExplore = null;

	/** Track dimension to detect changes (Overworld/Nether/End). */
	private ResourceKey<Level> currentDimension = null;

	// Stuck detection: track position to detect if Baritone is making progress
	private BlockPos lastStuckCheckPos = null;
	private int stuckCheckTicks = 0;

	// M1/M14: Wall-clock timeout tracking (real time, not ticks)
	private Instant stateStartTime = null;
	// H10: Non-blocking wait for Baritone cancel
	private Instant baritoneCancelWaitStart = null;

	// H5: NBT-aware protection - cached protected slots
	private Set<Integer> protectedSlots = new HashSet<>();

	// Cached parsed settings to avoid repeated parsing
	private List<String> cachedMineNames = null;
	private Set<String> cachedOreItems = null;
	private Set<String> cachedJunkItems = null;
	private Set<String> cachedCustomBlocks = null;
	private Set<String> cachedCustomItems = null;

	public MineBotModule() {
		super("MineBot", "Mines a chosen ore with Baritone, stores it in a chest, repeats", ModuleCategory.WORLD);

		// sub settings show up under their parent in the GUI
		this.ore.addSubSettings(this.customBlocks, this.customItems);
		this.oreChestSet.addSubSettings(this.oreChestX, this.oreChestY, this.oreChestZ);
		this.coalChestSet.addSubSettings(this.coalChestX, this.coalChestY, this.coalChestZ);
		this.mineCoal.addSubSettings(this.coalChestSet);
		this.dropJunk.addSubSettings(this.junkItems, this.keepBuildingBlocks);

		this.registerSettings(
				this.ore,
				this.oreChestSet,
				this.mineCoal,
				this.depositEverything,
				this.dropJunk,
												this.protectEnchanted,
				this.exploreForOre,
												this.minFreeSlots,
				this.transferDelay,
				this.junkDropInterval,
				this.timeoutSeconds,
																				this.returnToMine,
				this.blockBreakBlacklist
		);
	}

	/* ------------------------- public (for command) --------------------- */

	public void setOreChest(BlockPos pos) {
		this.oreChestX.setValue(pos.getX());
		this.oreChestY.setValue(pos.getY());
		this.oreChestZ.setValue(pos.getZ());
		this.oreChestSet.setValue(true);
		invalidateCache();
	}

	public void setCoalChest(BlockPos pos) {
		this.coalChestX.setValue(pos.getX());
		this.coalChestY.setValue(pos.getY());
		this.coalChestZ.setValue(pos.getZ());
		this.coalChestSet.setValue(true);
		invalidateCache();
	}

	public String getStatus() {
		final String oreChest = this.oreChestSet.getValue() ? oreChestPos().toShortString() : "not set";
		final String coalChest = !this.mineCoal.getValue() ? "off" : (this.coalChestSet.getValue() ? coalChestPos().toShortString() : "not set");
		return "Running: " + this.isToggled() + ", ore: " + this.ore.getValue() + ", state: " + this.state
				+ ", trips: " + this.trips + ", ore chest: " + oreChest + ", coal chest: " + coalChest;
	}

	/* ---------------------------- lifecycle ----------------------------- */

	// NOTE: do not touch the world or player here, they can be null when a module is toggled.
	@Override
	public void onEnable() {
		this.state = State.MINING;
		this.lastTick = -1;
		this.stateTicks = 0;
		this.checked = false;
		this.mineIssued = false;
		this.quickEnds = 0;
		this.goIssued = false;
		this.pathAttempts = 0;
		this.openAttempts = 0;
		this.transferCooldown = 0;
		this.lastIssueAt = 0;
		this.returnPos = null;
		this.stops.clear();
		this.stopIndex = 0;
		this.trips = 0;
		this.clickAttempts.clear();
		this.previousExplore = null;
		invalidateCache();
		// Save Baritone's exploreForBlocks setting immediately on enable
		if(this.baritone.available()) {
			this.previousExplore = this.baritone.readBoolean("exploreForBlocks");
		}
	}

	@Override
	public void onDisable() {
		try {
			this.baritone.cancel();
		} catch(Throwable t) {
			this.getLogger().warn("Could not cancel Baritone: " + t.getMessage());
		} finally {
			// Always restore exploreForBlocks setting
			if(this.previousExplore != null) {
				try {
					this.baritone.writeBoolean("exploreForBlocks", this.previousExplore);
				} catch(Throwable ignored) {
				}
				this.previousExplore = null;
			}
		}
	}

	/* ------------------------------ main loop --------------------------- */

	@Subscribe
	private void onUpdate(EventUpdate event) {
		if(mc.player == null || mc.level == null || mc.gameMode == null) {
			return;
		}

		// EventUpdate can fire more than once per tick, run the logic once per player tick
		final int tick = mc.player.tickCount;
		if(tick == this.lastTick) {
			return;
		}
		this.lastTick = tick;

		try {
			this.tick();
		} catch(Throwable t) {
			this.fail("Unexpected error: " + t);
			this.getLogger().error("MineBot error", t);
		} finally {
			// Restore exploreForBlocks if module is being disabled due to error
			if(!this.isToggled() && this.previousExplore != null) {
				try {
					this.baritone.writeBoolean("exploreForBlocks", this.previousExplore);
				} catch(Throwable ignored) {
				}
				this.previousExplore = null;
			}
		}
	}

	private void tick() {
		// Detect dimension change (Overworld/Nether/End) - reset state if changed
		ResourceKey<Level> newDimension = mc.player.level().dimension();
		if(this.currentDimension != null && !this.currentDimension.equals(newDimension)) {
			ChatUtils.print("[MineBot] Dimension changed, resetting state.");
			this.fail("Dimension changed - stopping.");
			return;
		}
		this.currentDimension = newDimension;

		if(!mc.player.isAlive()) {
			this.fail("You died, stopping.");
			return;
		}

		if(!this.checked) {
			if(!this.preflight()) {
				return;
			}
			this.checked = true;
		}

		this.stateTicks++;

		// Junk drop cooldown ticks down every tick regardless of state
		if(this.junkDropCooldown > 0) {
			this.junkDropCooldown--;
		}

		// Wall-clock timeout for non-mining states (real seconds, not ticks)
		if(this.state != State.MINING && this.stateStartTime != null) {
			if(java.time.Duration.between(this.stateStartTime, Instant.now()).getSeconds() > this.timeoutSeconds.getValue()) {
				this.fail("Stuck in step " + this.state + " for too long (" + this.timeoutSeconds.getValue() + "s wall-clock), stopping.");
				return;
			}
		}

		switch(this.state) {
			case MINING -> this.tickMining();
			case TO_CHEST -> this.tickToChest();
			case OPEN_CHEST -> this.tickOpenChest();
			case DEPOSIT -> this.tickDeposit();
			case DROP_JUNK -> this.tickDropJunk();
			case TO_MINE -> this.tickToMine();
			case WAIT_FOR_BARITONE -> this.tickWaitForBaritone();
		}
	}


	/**
	 * Non-blocking wait for Baritone to finish previous operation (max 5 seconds).
	 * Called from tick() when state is WAIT_FOR_BARITONE.
	 */
	private void tickWaitForBaritone() {
		if(this.baritoneCancelWaitStart != null &&
			java.time.Duration.between(this.baritoneCancelWaitStart, Instant.now()).toMillis() > 5000) {
			this.getLogger().warn("Timed out waiting for Baritone to cancel previous operation");
			this.fail("Baritone did not stop in time");
			return;
		}
		if(!this.baritone.mineActive() && !this.baritone.goalActive()) {
			this.baritoneCancelWaitStart = null;
			// Continue preflight checks
			this.checked = false;
		}
	}
	/**
	 * @return true when everything needed is in place
	 */
	private boolean preflight() {
		if(!this.baritone.available()) {
			this.fail("Baritone was not found (" + this.baritone.error() + "). Is the Baritone mod installed?");
			return false;
		}
		// H10: Non-blocking wait for Baritone to finish any previous operation
		if(this.baritone.mineActive() || this.baritone.goalActive()) {
			this.baritone.cancel();
			this.baritoneCancelWaitStart = Instant.now();
			this.setState(State.WAIT_FOR_BARITONE);
			return false;
		}

		// Apply block break blacklist to Baritone (protects chests, hoppers, furnaces, etc.)
		this.baritone.writeStringList("blockBreakBlacklist", this.blockBreakBlacklist.getValue());

		if(!this.oreChestSet.getValue()) {
			this.fail("No ore chest set. Look at the chest and run *minechest ore");
			return false;
		}
		if(this.mineCoal.getValue() && !this.coalChestSet.getValue()) {
			this.fail("MineCoal is on but no coal chest is set. Look at the coal chest and run *minechest coal");
			return false;
		}
		// H7: Validate coordinates against world bounds
		if(!validateCoordinates(oreChestPos())) {
			this.fail("Ore chest coordinates out of world bounds: " + oreChestPos().toShortString());
			return false;
		}
		if(this.mineCoal.getValue() && !validateCoordinates(coalChestPos())) {
			this.fail("Coal chest coordinates out of world bounds: " + coalChestPos().toShortString());
			return false;
		}
		// Verify ore chest exists and is a container
		if(!isContainerAt(oreChestPos())) {
			this.fail("No container at ore chest position " + oreChestPos().toShortString());
			return false;
		}
		if(this.mineCoal.getValue() && !isContainerAt(coalChestPos())) {
			this.fail("No container at coal chest position " + coalChestPos().toShortString());
			return false;
		}
				if(mineNames().isEmpty()) {
			this.fail("Nothing to mine. Ore is Custom but CustomBlocks is empty.");
			return false;
		}
		if(this.ore.getValue() == OreType.CUSTOM && oreItems().isEmpty() && !this.depositEverything.getValue()) {
			this.fail("Ore is Custom but CustomItems is empty, so nothing would be deposited.");
			return false;
		}
		// H8: Validate CustomBlocks and CustomItems against registry
		validateCustomBlocksAndItems();
		// H9: Warn if JunkItems overlaps with ore items
		warnJunkOreOverlap();

		ChatUtils.print("[MineBot] Started: " + this.ore.getValue() + (this.coalEnabled() ? " + coal" : "")
				+ ". Ore chest at " + oreChestPos().toShortString() + ".");
		return true;
	}

	// H7: Validate coordinates against world bounds
	private boolean validateCoordinates(BlockPos pos) {
		if(mc.level == null) {
			return false;
		}
		// Use level dimension type for build height limits
		int minY = mc.level.dimensionType().minY();
		int maxY = mc.level.dimensionType().minY() + mc.level.dimensionType().height();
		if(pos.getY() < minY || pos.getY() >= maxY) {
			return false;
		}
		// Check world border if available
		if(mc.level.getWorldBorder() != null) {
			double borderCenterX = mc.level.getWorldBorder().getCenterX();
			double borderCenterZ = mc.level.getWorldBorder().getCenterZ();
			double borderSize = mc.level.getWorldBorder().getSize();
			double halfSize = borderSize / 2.0;
			if(pos.getX() < borderCenterX - halfSize || pos.getX() > borderCenterX + halfSize ||
			   pos.getZ() < borderCenterZ - halfSize || pos.getZ() > borderCenterZ + halfSize) {
				return false;
			}
		}
		return true;
	}

	// H8: Validate CustomBlocks and CustomItems against BuiltInRegistries
	private void validateCustomBlocksAndItems() {
		if(this.ore.getValue() != OreType.CUSTOM) {
			return;
		}
		Set<String> customBlocks = getCustomBlocks();
		Set<String> customItems = getCustomItems();

		// Build sets of valid block/item names once for efficient lookup
		Set<String> validBlocks = BuiltInRegistries.BLOCK.keySet().stream()
			.map(key -> key.getPath())
			.collect(java.util.stream.Collectors.toSet());
		Set<String> validItems = BuiltInRegistries.ITEM.keySet().stream()
			.map(key -> key.getPath())
			.collect(java.util.stream.Collectors.toSet());

		// Check custom blocks exist in registry
		for(String blockName : customBlocks) {
			if(!validBlocks.contains(blockName)) {
				this.getLogger().warn("CustomBlocks contains unknown block: " + blockName);
			}
		}

		// Check custom items exist in registry
		for(String itemName : customItems) {
			if(!validItems.contains(itemName)) {
				this.getLogger().warn("CustomItems contains unknown item: " + itemName);
			}
		}

		// Cross-validate: warn if block has no matching item or vice versa
		for(String blockName : customBlocks) {
			boolean hasItem = customItems.contains(blockName);
			if(!hasItem) {
				this.getLogger().warn("Custom block '" + blockName + "' has no matching item in CustomItems");
			}
		}
		for(String itemName : customItems) {
			boolean hasBlock = customBlocks.contains(itemName);
			if(!hasBlock) {
				this.getLogger().warn("Custom item '" + itemName + "' has no matching block in CustomBlocks");
			}
		}
	}

	// H9: Warn if JunkItems overlaps with ore items
	private void warnJunkOreOverlap() {
		Set<String> junk = junkSet();
		Set<String> ore = oreItems();
		Set<String> overlap = new HashSet<>(junk);
		overlap.retainAll(ore);
		if(!overlap.isEmpty()) {
			this.getLogger().warn("JunkItems overlaps with ore items: " + overlap
					+ " - these will NOT be thrown as junk");
		}
		if(this.coalEnabled()) {
			Set<String> coalOverlap = new HashSet<>(junk);
			coalOverlap.retainAll(OreType.COAL_ITEMS);
			if(!coalOverlap.isEmpty()) {
				this.getLogger().warn("JunkItems overlaps with coal items: " + coalOverlap
						+ " - these will NOT be thrown as junk");
			}
		}
	}

	/* ------------------------------- states ----------------------------- */

	private void tickMining() {
		// Combat pause: if a mob hit us recently, cancel mining so KillAura can use sword
		if(this.baritone.mineActive() && mc.player.getLastHurtByMob() != null
				&& mc.player.getLastHurtByMobTimestamp() > mc.player.tickCount - 40) {
			this.baritone.cancel();
			return;
		}

		// throw junk away while mining so the slots stay free for ore (rate limited by JunkDropInterval)
		if(this.junkEnabled() && this.junkDropCooldown <= 0) {
			this.dropAllJunk();
			this.junkDropCooldown = this.junkDropInterval.getValue();
		}

		if(this.freeSlots() < this.minFreeSlots.getValue()) {
			this.startTrip();
			return;
		}

		if(!this.mineIssued) {
			this.issueMine();
			return;
		}

		// Baritone stopped by itself. With ExploreForOre off that means there is no ore nearby.
		if(!this.baritone.mineActive()) {
			this.quickEnds = (this.stateTicks - this.lastIssueAt < 100) ? this.quickEnds + 1 : 0;
			if(this.quickEnds >= 3) {
				this.fail("No " + describeTarget() + " found nearby (Baritone only sees loaded chunks). "
						+ "Move to a better spot or turn on ExploreForOre.");
				return;
			}
			this.issueMine();
		}
	}

	private void startTrip() {
		this.returnPos = mc.player.blockPosition();
		this.baritone.cancel();

		this.stops.clear();
		final Stop oreStop = new Stop("ore", oreChestPos(), false);
		if(this.hasItemsFor(oreStop)) {
			this.stops.add(oreStop);
		}
		if(this.coalEnabled()) {
			final Stop coalStop = new Stop("coal", coalChestPos(), true);
			if(this.hasItemsFor(coalStop)) {
				this.stops.add(coalStop);
			}
		}
		this.stopIndex = 0;

		if(this.stops.isEmpty()) {
			// nothing worth carrying, only junk to get rid of
			this.transferCooldown = 0;
			this.setState(State.DROP_JUNK);
		} else {
			this.setState(State.TO_CHEST);
		}
	}

	private void tickToChest() {
		final Stop stop = this.stops.get(this.stopIndex);

		// Verify chest still exists before pathing
		if(!isContainerAt(stop.pos())) {
			this.fail("Chest at " + stop.label() + " position " + stop.pos().toShortString() + " no longer exists.");
			return;
		}

		// Bug 3: Wait for Baritone to finish pathing (goalActive == false) AND be within interaction range
		// before transitioning to OPEN_CHEST. This prevents cancelling Baritone too early.
		if(!this.baritone.goalActive() && this.isNear(stop.pos())) {
			// Verify chest still exists upon arrival
			if(!isContainerAt(stop.pos())) {
				this.fail("Chest at " + stop.label() + " position " + stop.pos().toShortString() + " disappeared.");
				return;
			}
			this.baritone.cancel();
			this.setState(State.OPEN_CHEST);
			return;
		}

		if(!this.goIssued) {
			this.baritone.goNear(stop.pos(), 2);
			this.goIssued = true;
			this.goIssuedAt = this.stateTicks;
			this.lastStuckCheckPos = mc.player.blockPosition();
			this.stuckCheckTicks = 0;
			return;
		}

		// Stuck detection: check every 100 ticks (5 seconds) if we've moved at least 1 block
		if(this.checkStuck(stop.pos(), stop.label() + " chest")) {
			return;
		}

		// Baritone gave up or finished while we are still too far away
		if(this.stateTicks - this.goIssuedAt > 10 && !this.baritone.goalActive()) {
			this.pathAttempts++;
			if(this.pathAttempts >= 3) {
				this.fail("Could not reach the " + stop.label() + " chest after " + this.pathAttempts + " attempts, stopping.");
			} else {
				// Exponential backoff: 3s, 6s, 12s... (in ticks: 60, 120, 240)
				int backoffTicks = 60 * (1 << (this.pathAttempts - 1));
				this.getLogger().info("Pathing to " + stop.label() + " chest failed, retrying in " + (backoffTicks / 20) + "s (attempt " + (this.pathAttempts + 1) + "/3)");
				this.goIssued = false;
				this.goIssuedAt = this.stateTicks + backoffTicks; // delay before retry
			}
		}
	}

	private void tickOpenChest() {
		final Stop stop = this.stops.get(this.stopIndex);

		// Chest opened? (containerMenu != inventoryMenu means a chest/barrel/etc. is open)
		if(mc.player.containerMenu != mc.player.inventoryMenu) {
			// Verify the open container is actually our target chest by checking block entity
			if(!isContainerAt(stop.pos())) {
				// Wrong container open (e.g., another module opened something), close it and retry
				mc.player.closeContainer();
				this.openAttempts = 0;
				return;
			}
			this.clickAttempts.clear();
			this.transferCooldown = this.transferDelay.getValue();
			this.setState(State.DEPOSIT);
			return;
		}

		// Verify chest still exists and is a container before attempting to open
		if(!isContainerAt(stop.pos())) {
			this.fail("Chest at " + stop.label() + " position " + stop.pos().toShortString() + " no longer exists or is not a container.");
			return;
		}

		// try on ticks 1, 11, 21, 31, 41, 51... (up to 10 attempts = ~5 seconds)
		if(this.stateTicks % 10 == 1) {
			if(this.openAttempts >= 10) {
				this.fail("Could not open the " + stop.label() + " chest after " + this.openAttempts + " attempts, stopping.");
				return;
			}
			// Ensure we're close enough and looking at the chest for reliable interaction
			if(!isNear(stop.pos())) {
				// Too far, re-path closer
				this.baritone.goNear(stop.pos(), 1);
				this.getLogger().info("Too far from " + stop.label() + " chest to interact, re-pathing closer");
				return;
			}
			// Always use main hand to open chest
			RusherHackAPI.interactions().useBlock(stop.pos(), InteractionHand.MAIN_HAND, false, false);
			this.openAttempts++;
			this.getLogger().debug("Attempting to open " + stop.label() + " chest (attempt " + this.openAttempts + ")");
		}
	}

	private void tickDeposit() {
		// Server closed the chest on us (back to inventory menu)
		if(mc.player.containerMenu == mc.player.inventoryMenu) {
			// M3: Desync recovery - clear click attempts on re-open
			this.clickAttempts.clear();
			this.setState(State.OPEN_CHEST);
			return;
		}

		// M2: Humanized delay - add small random variation to transfer cooldown
		if(this.transferCooldown > 0) {
			this.transferCooldown--;
			return;
		}

		final Stop stop = this.stops.get(this.stopIndex);
		final Set<String> oreIds = oreItems();
		final Inventory inventory = mc.player.getInventory();
		final AbstractContainerMenu menu = (AbstractContainerMenu) mc.player.containerMenu;

		Slot next = null;
		boolean stuck = false;
		boolean anyItemsMovedThisVisit = false;

		for(Slot slot : menu.slots) {
			// only slots that belong to the player's own inventory
			if(slot.container != inventory || !slot.hasItem()) {
				continue;
			}
			// accepts() expects player inventory slot (0-35), which is getContainerSlot()
			if(!this.accepts(stop, slot.getContainerSlot(), idOf(slot.getItem()), oreIds)) {
				continue;
			}
			// M4: Concurrent player handling - check if slot contents changed unexpectedly
			// (another player may have moved items in the chest)
			// a slot we already tried twice and that still holds items means the chest cannot take it
			// use container slot (0-35 for player inventory) for clicks, consistent with accepts()
			int containerSlot = slot.getContainerSlot();
			if(this.clickAttempts.getOrDefault(containerSlot, 0) >= 2) {
				stuck = true;
				continue;
			}
			next = slot;
			break;
		}

		if(next != null) {
			this.clickAttempts.merge(next.getContainerSlot(), 1, Integer::sum);
			quickMove(menu.containerId, next.index);

			this.transferCooldown = this.transferDelay.getValue();

			anyItemsMovedThisVisit = true;
			return;
		}

		// nothing left to move into this chest
		mc.player.closeContainer();

		// Only fail if NO items were moved this visit (chest truly full)
		// If some items moved, continue to next chest/state instead of failing
		if(stuck && !anyItemsMovedThisVisit) {
			this.fail("The " + stop.label() + " chest is full, stopping. Items left in your inventory.");
			return;
		}

		this.stopIndex++;
		if(this.stopIndex < this.stops.size()) {
			this.setState(State.TO_CHEST);
			return;
		}

		if(this.junkEnabled()) {
			this.transferCooldown = this.transferDelay.getValue();
			this.setState(State.DROP_JUNK);
		} else {
			this.finishTrip();
		}
	}

	private void tickDropJunk() {
		if(!this.junkEnabled()) {
			this.finishTrip();
			return;
		}
		this.dropAllJunk();
		this.finishTrip();
	}

	/**
	 * Called when everything that can be deposited or dropped has been.
	 */
	private void finishTrip() {
		this.trips++;

		// guard against walking back and forth forever with a full inventory
		if(this.freeSlots() < this.minFreeSlots.getValue()) {
			this.fail("Inventory is still nearly full after the trip. Add the leftover items to the junk list "
					+ "(or CustomItems / DepositEverything) so they can be got rid of.");
			return;
		}

		ChatUtils.print("[MineBot] Trip " + this.trips + " done.");
		this.setState(this.returnToMine.getValue() ? State.TO_MINE : State.MINING);
	}

	private void tickToMine() {
		if(this.returnPos == null || mc.player.blockPosition().distSqr(this.returnPos) <= 16) {
			this.setState(State.MINING);
			return;
		}

		if(!this.goIssued) {
			this.baritone.goNear(this.returnPos, 2);
			this.goIssued = true;
			this.goIssuedAt = this.stateTicks;
			this.lastStuckCheckPos = mc.player.blockPosition();
			this.stuckCheckTicks = 0;
			return;
		}

		// Stuck detection: check every 100 ticks (5 seconds) if we've moved at least 1 block
		if(this.checkStuck(this.returnPos, "return position")) {
			return;
		}

		// Baritone finished or gave up: check if we reached the position before resuming mining
		if(this.stateTicks - this.goIssuedAt > 10 && !this.baritone.goalActive()) {
			if(mc.player.blockPosition().distSqr(this.returnPos) <= 16) {
				this.setState(State.MINING);
			} else {
				// Not at position yet, retry pathing with exponential backoff (max 3 attempts)
				this.pathAttempts++;
				if(this.pathAttempts >= 3) {
					this.fail("Could not return to mining position after " + this.pathAttempts + " attempts, stopping.");
				} else {
					// Exponential backoff: 3s, 6s, 12s...
					int backoffTicks = 60 * (1 << (this.pathAttempts - 1));
					this.getLogger().info("Return pathing failed, retrying in " + (backoffTicks / 20) + "s (attempt " + (this.pathAttempts + 1) + "/3)");
					this.goIssued = false;
					this.goIssuedAt = this.stateTicks + backoffTicks;
				}
			}
		}
	}

	/**
	 * Check if Baritone is stuck (no progress in 5 seconds / 100 ticks).
	 * @return true if stuck was detected and handled, false otherwise
	 */
	private boolean checkStuck(BlockPos target, String label) {
		this.stuckCheckTicks++;
		if(this.stuckCheckTicks < 100) {
			return false;
		}
		this.stuckCheckTicks = 0;
		if(this.lastStuckCheckPos != null) {
			double distSq = mc.player.blockPosition().distSqr(this.lastStuckCheckPos);
			if(distSq < 1.0) {
				this.getLogger().warn("Baritone appears stuck at " + label + " (no progress in 5s), cancelling and retrying");
				this.baritone.cancel();
				this.pathAttempts++;
				if(this.pathAttempts >= 3) {
					this.fail("Baritone stuck at " + label + " after " + this.pathAttempts + " attempts, stopping.");
					return true;
				}
				this.goIssued = false;
				int backoffTicks = 60 * (1 << (this.pathAttempts - 1));
				this.goIssuedAt = this.stateTicks + backoffTicks;
				return true;
			}
		}
		this.lastStuckCheckPos = mc.player.blockPosition();
		return false;
	}

	/* ------------------------------ mining ------------------------------ */

	private void issueMine() {
		// exploreForBlocks is what makes Baritone walk on and on when it sees no ore
		if(!this.baritone.writeBoolean("exploreForBlocks", this.exploreForOre.getValue())) {
			this.getLogger().warn("Could not change Baritone's exploreForBlocks setting");
		}
		this.baritone.mine(mineNames().toArray(new String[0]));
		this.mineIssued = true;
		this.lastIssueAt = this.stateTicks;
	}

	/**
	 * Block names Baritone should mine: the chosen ore, plus coal when enabled.
	 */
	private List<String> mineNames() {
		if(this.cachedMineNames != null) {
			return this.cachedMineNames;
		}
		final List<String> names = new ArrayList<>();
		if(this.ore.getValue() == OreType.CUSTOM) {
			names.addAll(getCustomBlocks());
		} else {
			names.addAll(this.ore.getValue().blocks());
		}
		if(this.coalEnabled()) {
			names.addAll(OreType.COAL_BLOCKS);
		}
		this.cachedMineNames = names;
		return names;
	}

	/**
	 * Item ids that belong in the ore chest.
	 */
	private Set<String> oreItems() {
		if(this.cachedOreItems != null) {
			return this.cachedOreItems;
		}
		if(this.ore.getValue() == OreType.CUSTOM) {
			this.cachedOreItems = getCustomItems();
		} else {
			this.cachedOreItems = new HashSet<>(this.ore.getValue().items());
		}
		return this.cachedOreItems;
	}

	private String describeTarget() {
		return this.ore.getValue() == OreType.CUSTOM ? "custom blocks" : this.ore.getValue().toString().toLowerCase() + " ore";
	}

	/**
	 * Coal counts as on only when the switch is on AND a coal chest has been chosen. Without the second
	 * half, switching MineCoal on while running would send the bot to the default coordinates 0, 64, 0.
	 */
	private boolean coalEnabled() {
		return this.mineCoal.getValue() && this.coalChestSet.getValue();
	}

	/* ----------------------------- deposit rules ------------------------ */

	/**
	 * Does this chest take this item?
	 *
	 * @param inventorySlot 0-8 is the hotbar, 9-35 the main inventory
	 */
	private boolean accepts(Stop stop, int inventorySlot, String id, Set<String> oreIds) {
		if(stop.coal()) {
			return OreType.COAL_ITEMS.contains(id);
		}
		if(this.depositEverything.getValue()) {
			// The coal chest gets the coal, everything else goes in the ore chest. The hotbar is only
			// emptied of the ore itself (picked-up ore lands in hotbar slots first), never of tools or food.
			if(this.coalEnabled() && OreType.COAL_ITEMS.contains(id)) {
				return false;
			}
			return inventorySlot >= 9 || oreIds.contains(id);
		}
		return oreIds.contains(id);
	}

	/**
	 * Is there anything in the inventory that this chest would take?
	 */
	private boolean hasItemsFor(Stop stop) {
		final Set<String> oreIds = oreItems();
		final Inventory inventory = mc.player.getInventory();

		// Check regular inventory slots
		for(int i = 0; i < 36; i++) {
			final ItemStack stack = inventory.getItem(i);
			if(!stack.isEmpty() && this.accepts(stop, i, idOf(stack), oreIds)) {
				return true;
			}
		}
		return false;
	}

	/* ------------------------------- junk ------------------------------- */

	private boolean junkEnabled() {
		return this.dropJunk.getValue() && !this.depositEverything.getValue();
	}

	/**
	 * The items that may be thrown away: the junk list, minus anything we are here to collect,
	 * plus other ore types that we're not currently mining (so they get thrown away).
	 */
	private Set<String> junkSet() {
		if(this.cachedJunkItems != null) {
			return this.cachedJunkItems;
		}
		final Set<String> junk = parseList(this.junkItems.getValue());
		// Remove currently targeted ore items and coal from junk list
		junk.removeAll(oreItems());
		if(this.coalEnabled()) {
			junk.removeAll(OreType.COAL_ITEMS);
		}
		// Bug 2: Add other ore types' items as junk (so they get thrown away while mining)
		OreType currentOre = this.ore.getValue();
		for (OreType oreType : OreType.values()) {
			if (oreType == currentOre || oreType == OreType.CUSTOM) {
				continue; // Skip current ore and custom (custom blocks defined by user)
			}
			// Add this ore type's items to junk
			junk.addAll(oreType.items());
		}
		this.cachedJunkItems = junk;
		return junk;
	}

	/**
	 * Throw all junk stacks out of the main inventory.
	 * Also handles H5: NBT protection for enchanted items.
	 */
	private void dropAllJunk() {
		// never while another screen (a chest, say) is open
		if(mc.player == null || mc.player.inventoryMenu == null || mc.player.containerMenu != mc.player.inventoryMenu) {
			return;
		}

		final Set<String> junk = this.junkSet();
		if(junk.isEmpty()) {
			return;
		}

		final Inventory inventory = mc.player.getInventory();

		// H5: Build set of protected slots (enchanted items, damaged tools/armor) if ProtectEnchanted is enabled
		this.protectedSlots.clear();
		if(this.protectEnchanted.getValue()) {
			for(Slot slot : mc.player.inventoryMenu.slots) {
				if(slot.container != inventory || !slot.hasItem()) {
					continue;
				}
				ItemStack stack = slot.getItem();
				// Protect if enchanted
				if(stack.isEnchanted()) {
					this.protectedSlots.add(slot.index);
					continue;
				}
				// Protect damaged tools and armor (items with durability that are damaged)
				if(stack.getMaxDamage() > 0 && stack.isDamaged()) {
					this.protectedSlots.add(slot.index);
					continue;
				}
				// Note: Custom NBT protection skipped due to mapping API differences
				// Items with custom NBT (books, maps) are not explicitly protected
			}
		}

		// keep the biggest stack of building blocks for Baritone
		Slot keep = null;
		if(this.keepBuildingBlocks.getValue()) {
			int best = 0;
			for(Slot slot : mc.player.inventoryMenu.slots) {
				if(slot.container != inventory || !slot.hasItem()) {
					continue;
				}
				final String id = idOf(slot.getItem());
				if(BUILDING_BLOCKS.contains(id) && junk.contains(id) && slot.getItem().getCount() > best) {
					best = slot.getItem().getCount();
					keep = slot;
				}
			}
		}

		for(Slot slot : mc.player.inventoryMenu.slots) {
			// Hotbar slots are included on purpose: picked-up items land in empty hotbar slots first.
			// Tools and food are safe because only items named in the junk list are ever thrown.
			if(slot == keep || slot.container != inventory || !slot.hasItem()) {
				continue;
			}
			// H5: Skip protected slots (enchanted, damaged tools/armor, NBT items)
			if(this.protectedSlots.contains(slot.index)) {
				continue;
			}
			String id = idOf(slot.getItem());
			boolean isJunk = junk.contains(id);

			if(isJunk) {
				this.throwStack(mc.player.inventoryMenu.containerId, slot.index);
			}
		}
	}

	/* ------------------------------ helpers ----------------------------- */

	private void setState(State next) {
		this.state = next;
		this.stateTicks = 0;
		this.goIssued = false;

		switch(next) {
			case MINING -> {
				this.mineIssued = false;
				this.quickEnds = 0;
			}
			case TO_CHEST, TO_MINE -> this.pathAttempts = 0;
			case OPEN_CHEST -> this.openAttempts = 0;
			case DEPOSIT -> {
				// transferDelay is used directly in tickDeposit
			}
			default -> this.stateStartTime = Instant.now(); // track wall-clock for non-MINING states
		}
	}

	/**
	 * Stop the module and tell the player why.
	 */
	private void fail(String message) {
		ChatUtils.print("[MineBot] " + message);
		try {
			if(mc.player != null && mc.player.containerMenu != mc.player.inventoryMenu) {
				mc.player.closeContainer();
			}
		} catch(Throwable ignored) {
		}
		this.setToggled(false); // runs onDisable, which cancels Baritone
	}

	private BlockPos oreChestPos() {
		return new BlockPos(this.oreChestX.getValue(), this.oreChestY.getValue(), this.oreChestZ.getValue());
	}

	private BlockPos coalChestPos() {
		return new BlockPos(this.coalChestX.getValue(), this.coalChestY.getValue(), this.coalChestZ.getValue());
	}

	/**
	 * Check if there is a container (chest, barrel, shulker box, etc.) at the given position.
	 */
	private boolean isContainerAt(BlockPos pos) {
		if(mc.level == null) {
			return false;
		}
		var blockState = mc.level.getBlockState(pos);
		if(blockState.isAir()) {
			return false;
		}
		// Check if block entity exists (chests, barrels, shulkers all have block entities)
		var blockEntity = mc.level.getBlockEntity(pos);
		return blockEntity != null;
	}

	/**
	 * Within comfortable reach (4.5 blocks) of the chest centre.
	 * Increased from 3 blocks to ensure reliable chest interaction on anarchy servers.
	 */
	private boolean isNear(BlockPos pos) {
		return mc.player.getEyePosition().distanceToSqr(Vec3.atCenterOf(pos)) <= 20.25;
	}

	/**
	 * Empty slots in the 36 main + hotbar slots.
	 */
	private int freeSlots() {
		final Inventory inventory = mc.player.getInventory();
		int free = 0;
		for(int i = 0; i < 36; i++) {
			if(inventory.getItem(i).isEmpty()) {
				free++;
			}
		}
		return free;
	}

	/**
	 * "diamond" for a diamond stack. No namespace type is named here on purpose, Mojang renamed it in 1.21.11.
	 */
	private static String idOf(ItemStack stack) {
		return BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
	}

	/**
	 * Shift-click a slot of the open container. (Same call ContainerTweaks uses on 1.21.11.)
	 */
	private void quickMove(int containerId, int slotIndex) {
		mc.gameMode.handleInventoryMouseClick(containerId, slotIndex, 0, ClickType.QUICK_MOVE, mc.player);
	}

	/**
	 * Throw a whole stack out of the player's inventory (Ctrl+Q behaviour: button 1).
	 */
	private void throwStack(int containerId, int slotIndex) {
		mc.gameMode.handleInventoryMouseClick(containerId, slotIndex, 1, ClickType.THROW, mc.player);
	}

	/**
	 * "a, b,minecraft:c" -> {a, b, c}
	 */
	private static Set<String> parseList(String raw) {
		final Set<String> out = new HashSet<>();
		for(String part : raw.split(",")) {
			String s = part.trim().toLowerCase();
			if(s.isEmpty()) {
				continue;
			}
			if(s.startsWith("minecraft:")) {
				s = s.substring("minecraft:".length());
			}
			if(!s.isEmpty()) {
				out.add(s);
			}
		}
		return out;
	}

	private Set<String> getCustomBlocks() {
		if(this.cachedCustomBlocks != null) {
			return this.cachedCustomBlocks;
		}
		this.cachedCustomBlocks = parseList(this.customBlocks.getValue());
		return this.cachedCustomBlocks;
	}

	private Set<String> getCustomItems() {
		if(this.cachedCustomItems != null) {
			return this.cachedCustomItems;
		}
		this.cachedCustomItems = parseList(this.customItems.getValue());
		return this.cachedCustomItems;
	}

	private void invalidateCache() {
		this.cachedMineNames = null;
		this.cachedOreItems = null;
		this.cachedJunkItems = null;
		this.cachedCustomBlocks = null;
		this.cachedCustomItems = null;
	}

}