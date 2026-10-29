package org.minebot;

import org.rusherhack.client.api.RusherHackAPI;
import org.rusherhack.client.api.plugin.Plugin;

/**
 * MineBot: mine a chosen ore with Baritone, deposit it in a chest, repeat.
 */
public class MineBotPlugin extends Plugin {
	
	@Override
	public void onLoad() {
		final MineBotModule module = new MineBotModule();
		RusherHackAPI.getModuleManager().registerFeature(module);
		RusherHackAPI.getCommandManager().registerFeature(new MineBotCommand(module));
		
		this.getLogger().info("MineBot loaded");
	}
	
	@Override
	public void onUnload() {
		this.getLogger().info("MineBot unloaded");
	}
	
}
