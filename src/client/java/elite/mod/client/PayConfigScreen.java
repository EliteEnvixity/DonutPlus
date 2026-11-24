package elite.mod.client;

import java.math.BigDecimal;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Settings screen opened from Mod Menu. */
public class PayConfigScreen extends Screen {
	private final Screen parent;

	private boolean enabled;
	private EditBox thresholdBox;
	private EditBox expiryBox;
	private Component error = Component.empty();

	public PayConfigScreen(Screen parent) {
		super(Component.literal("Elite's Mod"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		PayConfig config = PayConfig.get();
		this.enabled = config.enabled;

		int w = 200;
		int x = this.width / 2 - w / 2;
		int top = this.height / 2 - 60;

		this.addRenderableWidget(Button.builder(enabledLabel(), b -> {
			this.enabled = !this.enabled;
			b.setMessage(enabledLabel());
		}).bounds(x, top, w, 20).build());

		this.thresholdBox = new EditBox(this.font, x, top + 40, w, 20, Component.literal("Threshold"));
		this.thresholdBox.setValue(config.threshold);
		this.addRenderableWidget(this.thresholdBox);

		this.expiryBox = new EditBox(this.font, x, top + 80, w, 20, Component.literal("Expiry"));
		this.expiryBox.setFilter(s -> s.matches("\\d{0,4}"));
		this.expiryBox.setValue(String.valueOf(config.expirySeconds));
		this.addRenderableWidget(this.expiryBox);

		this.addRenderableWidget(Button.builder(Component.literal("Done"), b -> save())
			.bounds(x, top + 112, 96, 20).build());

		this.addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> this.onClose())
			.bounds(x + 104, top + 112, 96, 20).build());
	}

	private Component enabledLabel() {
		return Component.literal("Payment confirmation: " + (this.enabled ? "On" : "Off"));
	}

	private void save() {
		BigDecimal threshold = AmountParser.parse(this.thresholdBox.getValue());
		if (threshold == null || threshold.signum() <= 0) {
			this.error = Component.literal("Amount must look like 100m, 2.5b or 50000000");
			return;
		}

		String expiryText = this.expiryBox.getValue();
		int expiry = expiryText.isEmpty() ? 0 : Integer.parseInt(expiryText);

		PayConfig config = PayConfig.get();
		config.enabled = this.enabled;
		config.threshold = this.thresholdBox.getValue().trim();
		config.expirySeconds = expiry;
		PayConfig.save();

		this.minecraft.setScreen(this.parent);
	}

	@Override
	public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
		super.render(graphics, mouseX, mouseY, delta);

		int x = this.width / 2 - 100;
		int top = this.height / 2 - 60;

		graphics.drawCenteredString(this.font, this.title, this.width / 2, top - 24, 0xFFFFFFFF);
		graphics.drawString(this.font, Component.literal("Confirm payments at or above (e.g. 100m)"), x, top + 28, 0xFFAAAAAA);
		graphics.drawString(this.font, Component.literal("Expires after, in seconds (0 = never)"), x, top + 68, 0xFFAAAAAA);
		graphics.drawCenteredString(this.font, this.error, this.width / 2, top + 140, 0xFFFF5555);
	}

	@Override
	public void onClose() {
		this.minecraft.setScreen(this.parent);
	}
}
