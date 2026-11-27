package elite.mod.client;

import net.fabricmc.api.ClientModInitializer;

public class DonutPlusClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		// This entrypoint is suitable for setting up client-specific logic, such as rendering.
		// SessionStats and AuctionHelper go before Payback, which can hide payment lines from chat, so they still see them.
		SessionStats.register();
		AuctionHelper.register();
		PayConfirmation.register();
		PaybackManager.register();
	}
}
