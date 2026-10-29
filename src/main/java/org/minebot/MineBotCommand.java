package org.minebot;

import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import org.rusherhack.client.api.feature.command.Command;
import org.rusherhack.core.command.annotations.CommandExecutor;

/**
 * Sets the chests by looking at them.
 * <p>
 * Named "minechest" on purpose: RusherHack already creates a command with the module's own name
 * ("minebot") for changing its settings, and a second command with the same name hides one of the two.
 * <p>
 * *minechest        shows the status
 * *minechest ore    ore chest = the block you are looking at
 * *minechest coal   coal chest = the block you are looking at
 */
public class MineBotCommand extends Command {
	
	private final MineBotModule module;
	
	public MineBotCommand(MineBotModule module) {
		super("minechest", "Set the MineBot chests by looking at them");
		this.module = module;
	}
	
	@CommandExecutor
	private String status() {
		return this.module.getStatus();
	}
	
	@CommandExecutor(subCommand = "ore")
	private String ore() {
		final var hit = lookedAtBlock();
		if(hit == null) {
			return "Look at the chest you want to use, then run this again.";
		}
		this.module.setOreChest(hit.getBlockPos());
		return "Ore chest set to " + hit.getBlockPos().toShortString();
	}
	
	@CommandExecutor(subCommand = "coal")
	private String coal() {
		final var hit = lookedAtBlock();
		if(hit == null) {
			return "Look at the chest you want to use, then run this again.";
		}
		this.module.setCoalChest(hit.getBlockPos());
		return "Coal chest set to " + hit.getBlockPos().toShortString();
	}
	
	private BlockHitResult lookedAtBlock() {
		if(mc.player == null || mc.level == null) {
			return null;
		}
		if(mc.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK) {
			return hit;
		}
		return null;
	}
	
}
