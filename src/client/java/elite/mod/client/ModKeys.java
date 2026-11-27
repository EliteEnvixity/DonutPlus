package elite.mod.client;

import elite.mod.DonutPlus;
import net.minecraft.client.KeyMapping;

/**
 * The keybind category shared by every DonutPLUS key. It can only be registered once, so each feature
 * that adds a keybind takes it from here instead of registering its own.
 */
final class ModKeys {
	static final KeyMapping.Category MAIN = KeyMapping.Category.register(DonutPlus.id("main"));

	private ModKeys() {}
}
