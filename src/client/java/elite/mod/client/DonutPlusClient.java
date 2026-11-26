package elite.mod.client;

import net.fabricmc.api.ClientModInitializer;

public class DonutPlusClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		// This entrypoint is suitable for setting up client-specific logic, such as rendering.
		PayConfirmation.register();
		PaybackManager.register();
	}
}
