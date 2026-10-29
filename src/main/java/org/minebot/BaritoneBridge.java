package org.minebot;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

import com.google.gson.JsonParser;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Talks to whatever Baritone is installed, through reflection.
 * <p>
 * Why reflection: the RusherHack API does not expose Baritone, and compiling against a
 * Baritone jar would tie this plugin to one Baritone build and one set of Minecraft mappings.
 * Reflection only needs the public {@code baritone.api} interfaces to exist at runtime.
 * <p>
 * Every Method is looked up on a public interface (never on the implementation class),
 * because the implementation classes are package-private and cannot be invoked reflectively.
 */
final class BaritoneBridge {

	private boolean ready = false;
	private String error = "not initialised";

	private Object provider;

	private Method getPrimaryBaritone;
	private Method getMineProcess;
	private Method getCustomGoalProcess;
	private Method getPathingBehavior;

	private Method mineByName;
	private Method isActive;
	private Method setGoalAndPath;
	private Method cancelEverything;

	private Object settings;
	
	private Class<?> goalClass;
	private Constructor<?> goalNearCtor;
	private Constructor<?> goalBlockCtor;

	/**
	 * @return true when Baritone was found and every method we need exists
	 */
	synchronized boolean available() {
		if(this.ready) {
			return true;
		}

		try {
			final Class<?> api = load("baritone.api.BaritoneAPI");
			final Class<?> providerIface = load("baritone.api.IBaritoneProvider");
			final Class<?> baritoneIface = load("baritone.api.IBaritone");
			final Class<?> mineIface = load("baritone.api.process.IMineProcess");
			final Class<?> processIface = load("baritone.api.process.IBaritoneProcess");
			final Class<?> goalProcessIface = load("baritone.api.process.ICustomGoalProcess");
			final Class<?> pathingIface = load("baritone.api.behavior.IPathingBehavior");

			this.goalClass = load("baritone.api.pathing.goals.Goal");
			this.goalNearCtor = load("baritone.api.pathing.goals.GoalNear").getConstructor(BlockPos.class, int.class);
			this.goalBlockCtor = load("baritone.api.pathing.goals.GoalBlock").getConstructor(BlockPos.class);

			this.provider = findMethod(api, "getProvider", "getProviderInstance", "provider").invoke(null);
			this.settings = findMethod(api, "getSettings", "getSettingsInstance", "settings").invoke(null);
			this.getPrimaryBaritone = findMethod(providerIface, "getPrimaryBaritone", "getPrimary", "primaryBaritone");
			this.getMineProcess = findMethod(baritoneIface, "getMineProcess", "mineProcess");
			this.getCustomGoalProcess = findMethod(baritoneIface, "getCustomGoalProcess", "customGoalProcess");
			this.getPathingBehavior = findMethod(baritoneIface, "getPathingBehavior", "pathingBehavior", "getPathing");

			// mine(int quantity, String... blocks) or mine(int quantity, String[] blocks)
			this.mineByName = findMethod(mineIface, new String[]{"mineByName", "mine", "startMining"}, int.class, String[].class);
			this.isActive = findMethod(processIface, "isActive", "isRunning", "active");
			// setGoalAndPath(Goal goal)
			this.setGoalAndPath = findMethod(goalProcessIface, new String[]{"setGoalAndPath", "setGoal", "pathTo"}, this.goalClass);
			this.cancelEverything = findMethod(pathingIface, "cancelEverything", "cancel", "stop", "clear");

			this.ready = true;
			this.error = "";
			// Version check: verify Baritone version compatibility
			if(!checkBaritoneVersion()) {
				this.ready = false;
				this.error = "Incompatible Baritone version";
				return false;
			}
		} catch(ReflectiveOperationException | IllegalArgumentException t) {
			this.ready = false;
			this.error = describe(t);
		}
		return this.ready;
	}

	synchronized String error() {
		return this.error;
	}

	/**
	 * Start mining the given blocks (names like "copper_ore"). Quantity 0 means "keep going".
	 */
	synchronized void mine(String... blockNames) {
		call(this.mineByName, mineProcess(), 0, blockNames);
	}

	/**
	 * Walk to within {@code range} blocks of the position.
	 */
	synchronized void goNear(BlockPos pos, int range) {
		try {
			final Object goal = this.goalNearCtor.newInstance(pos, range);
			call(this.setGoalAndPath, goalProcess(), goal);
		} catch(ReflectiveOperationException e) {
			throw new RuntimeException(describe(e), e);
		}
	}

	/**
	 * Walk to exactly this block.
	 */
	synchronized void goTo(BlockPos pos) {
		try {
			final Object goal = this.goalBlockCtor.newInstance(pos);
			call(this.setGoalAndPath, goalProcess(), goal);
		} catch(ReflectiveOperationException e) {
			throw new RuntimeException(describe(e), e);
		}
	}

	synchronized boolean mineActive() {
		return (boolean) call(this.isActive, mineProcess());
	}

	synchronized boolean goalActive() {
		return (boolean) call(this.isActive, goalProcess());
	}

	/**
	 * Read a boolean Baritone setting such as "exploreForBlocks".
	 *
	 * @return the value, or null when the setting could not be read
	 */
	synchronized Boolean readBoolean(String settingName) {
		try {
			final Object setting = this.settings.getClass().getField(settingName).get(this.settings);
			return (Boolean) setting.getClass().getField("value").get(setting);
		} catch(ReflectiveOperationException t) {
			return null;
		}
	}
	
	/**
	 * Change a boolean Baritone setting.
	 *
	 * @return false when the setting could not be changed
	 */
	synchronized boolean writeBoolean(String settingName, boolean value) {
		try {
			final Object setting = this.settings.getClass().getField(settingName).get(this.settings);
			setting.getClass().getField("value").set(setting, value);
			return true;
		} catch(ReflectiveOperationException t) {
			return false;
		}
	}

	/**
	 * Change a Baritone string list setting (e.g., blockBreakBlacklist).
	 * Expects comma-separated string like "chest,furnace,hopper"
	 *
	 * @return false when the setting could not be changed
	 */
	synchronized boolean writeStringList(String settingName, String commaSeparated) {
		try {
			final Object setting = this.settings.getClass().getField(settingName).get(this.settings);
			// Try common list field types
			Class<?> settingClass = setting.getClass();

			// Try StringListSetting (has "value" as List<String>)
			try {
				java.lang.reflect.Field valueField = settingClass.getField("value");
				Object list = valueField.get(setting);
				if(list instanceof java.util.List) {
					@SuppressWarnings("unchecked")
					java.util.List<String> stringList = (java.util.List<String>) list;
					stringList.clear();
					for(String s : commaSeparated.split(",")) {
						String trimmed = s.trim();
						if(!trimmed.isEmpty()) {
							stringList.add(trimmed);
						}
					}
					return true;
				}
			} catch(ReflectiveOperationException ignored) {}

			// Try direct setter if it's a simple string field
			try {
				settingClass.getField("value").set(setting, commaSeparated);
				return true;
			} catch(ReflectiveOperationException ignored) {}

			return false;
		} catch(ReflectiveOperationException t) {
			return false;
		}
	}

	/**
	 * Stop mining and pathing.
	 */
	synchronized void cancel() {
		if(!this.ready) {
			return;
		}
		call(this.cancelEverything, pathing());
	}

	/* ------------------------------------------------------------------ */

	private Object baritone() {
		return call(this.getPrimaryBaritone, this.provider);
	}

	private Object mineProcess() {
		return call(this.getMineProcess, baritone());
	}

	private Object goalProcess() {
		return call(this.getCustomGoalProcess, baritone());
	}

	private Object pathing() {
		return call(this.getPathingBehavior, baritone());
	}

	private static Object call(Method method, Object target, Object... args) {
		try {
			return method.invoke(target, args);
		} catch(InvocationTargetException e) {
			final Throwable cause = e.getCause() != null ? e.getCause() : e;
			throw new RuntimeException(describe(cause), cause);
		} catch(IllegalAccessException e) {
			throw new RuntimeException(describe(e), e);
		}
	}

	/**
	 * Find a method by trying multiple fallback names for version compatibility.
	 * Searches all declared methods (including inherited) and matches by name with
	 * compatible parameter types. Falls back to exact parameter match, then assignable match.
	 */
	private static Method findMethod(Class<?> clazz, String[] names, Class<?>... paramTypes) throws ReflectiveOperationException {
		for(String name : names) {
			// First try exact parameter match
			try {
				return clazz.getMethod(name, paramTypes);
			} catch(NoSuchMethodException ignored) {
			}
			// Then search all methods for name + assignable parameters
			for(Method m : clazz.getMethods()) {
				if(!m.getName().equals(name)) {
					continue;
				}
				Class<?>[] params = m.getParameterTypes();
				if(params.length != paramTypes.length) {
					continue;
				}
				boolean compatible = true;
				for(int i = 0; i < params.length; i++) {
					if(!params[i].isAssignableFrom(paramTypes[i])) {
						compatible = false;
						break;
					}
				}
				if(compatible) {
					return m;
				}
			}
		}
		throw new NoSuchMethodException("None of " + java.util.Arrays.toString(names) + " with params " + java.util.Arrays.toString(paramTypes) + " found in " + clazz.getName());
	}

	// Backward-compatible overload for zero-arg methods
	private static Method findMethod(Class<?> clazz, String... names) throws ReflectiveOperationException {
		return findMethod(clazz, names, new Class<?>[0]);
	}

	/**
	 * Try the loaders that could plausibly see Baritone. Plugins may be loaded by a loader
	 * that cannot see other mods, so the Minecraft loader (Fabric's Knot) goes first.
	 */
	private static Class<?> load(String name) throws ClassNotFoundException {
		final List<ClassLoader> loaders = new ArrayList<>();
		loaders.add(Minecraft.class.getClassLoader());
		loaders.add(Thread.currentThread().getContextClassLoader());
		loaders.add(BaritoneBridge.class.getClassLoader());

		ClassNotFoundException last = null;
		for(ClassLoader loader : loaders) {
			if(loader == null) {
				continue;
			}
			try {
				return Class.forName(name, true, loader);
			} catch(ClassNotFoundException e) {
				last = e;
			}
		}
		throw last != null ? last : new ClassNotFoundException(name);
	}

	private static String describe(Throwable t) {
		return t.getClass().getSimpleName() + ": " + t.getMessage();
	}

	/**
	 * Check Baritone version compatibility by reading fabric.mod.json.
	 * Returns true if compatible, false if version cannot be determined or is incompatible.
	 */
	private static boolean checkBaritoneVersion() {
		try {
			for(ClassLoader loader : List.of(Minecraft.class.getClassLoader(),
					Thread.currentThread().getContextClassLoader(),
					BaritoneBridge.class.getClassLoader())) {
				if(loader == null) continue;
				var resource = loader.getResourceAsStream("fabric.mod.json");
				if(resource != null) {
					try(var reader = new java.io.InputStreamReader(resource, java.nio.charset.StandardCharsets.UTF_8)) {
						var json = new com.google.gson.JsonParser().parse(reader).getAsJsonObject();
						if(json.has("depends") && json.get("depends").isJsonObject()) {
							var depends = json.getAsJsonObject("depends");
							if(depends.has("baritone")) {
								String version = depends.get("baritone").getAsString();
								// Require Baritone 2.0+ (compatible with Minecraft 1.21+)
								if(version.matches("^\\d+")) {
									int major = Integer.parseInt(version.split("\\.")[0]);
									return major >= 2;
								}
							}
						}
					}
				}
			}
		} catch(Throwable ignored) {
			// If version check fails, allow it (backward compatibility)
		}
		return true; // Allow if version cannot be determined
	}

}
