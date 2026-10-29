package org.minebot;

import java.util.List;

/**
 * Ores the module can mine. Each entry lists the block names Baritone should mine and the item ids
 * that count as "the haul" (what gets put in the ore chest). The ore blocks themselves are in the item
 * list too, for the case where a silk touch pickaxe is used.
 */
public enum OreType {
	
	COPPER("Copper", List.of("copper_ore", "deepslate_copper_ore"), List.of("raw_copper", "copper_ore", "deepslate_copper_ore")),
	IRON("Iron", List.of("iron_ore", "deepslate_iron_ore"), List.of("raw_iron", "iron_ore", "deepslate_iron_ore")),
	GOLD("Gold", List.of("gold_ore", "deepslate_gold_ore"), List.of("raw_gold", "gold_ore", "deepslate_gold_ore")),
	DIAMOND("Diamond", List.of("diamond_ore", "deepslate_diamond_ore"), List.of("diamond", "diamond_ore", "deepslate_diamond_ore")),
	REDSTONE("Redstone", List.of("redstone_ore", "deepslate_redstone_ore"), List.of("redstone", "redstone_ore", "deepslate_redstone_ore")),
	LAPIS("Lapis", List.of("lapis_ore", "deepslate_lapis_ore"), List.of("lapis_lazuli", "lapis_ore", "deepslate_lapis_ore")),
	EMERALD("Emerald", List.of("emerald_ore", "deepslate_emerald_ore"), List.of("emerald", "emerald_ore", "deepslate_emerald_ore")),
	QUARTZ("Quartz", List.of("nether_quartz_ore"), List.of("quartz", "nether_quartz_ore")),
	ANCIENT_DEBRIS("AncientDebris", List.of("ancient_debris"), List.of("ancient_debris")),
	/** Uses the CustomBlocks and CustomItems settings. */
	CUSTOM("Custom", List.of(), List.of());
	
	/** Coal is handled separately: it has its own optional switch and its own chest. */
	public static final List<String> COAL_BLOCKS = List.of("coal_ore", "deepslate_coal_ore");
	public static final List<String> COAL_ITEMS = List.of("coal", "coal_ore", "deepslate_coal_ore");
	
	private final String label;
	private final List<String> blocks;
	private final List<String> items;
	
	OreType(String label, List<String> blocks, List<String> items) {
		this.label = label;
		this.blocks = blocks;
		this.items = items;
	}
	
	public List<String> blocks() {
		return this.blocks;
	}
	
	public List<String> items() {
		return this.items;
	}
	
	/** Shown in the GUI. */
	@Override
	public String toString() {
		return this.label;
	}
	
}
