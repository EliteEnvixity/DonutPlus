package elite.mod.client;

import java.util.function.Consumer;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Plain black confirmation screen for large payments. */
public class PayConfirmScreen extends Screen {
	private final Component message;
	private final long expiresAt;
	private final Consumer<Boolean> callback;

	/** @param expiresAt time in milliseconds when the screen cancels itself, or 0 to never expire */
	public PayConfirmScreen(Component title, Component message, long expiresAt, Consumer<Boolean> callback) {
		super(title);
		this.message = message;
		this.expiresAt = expiresAt;
		this.callback = callback;
	}

	@Override
	protected void init() {
		int buttonWidth = 100;
		int gap = 8;
		int y = this.height / 2 + 20;
		int left = this.width / 2 - buttonWidth - gap / 2;

		this.addRenderableWidget(Button.builder(Component.literal("Send"), b -> this.callback.accept(true))
			.bounds(left, y, buttonWidth, 20)
			.build());

		this.addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> this.callback.accept(false))
			.bounds(left + buttonWidth + gap, y, buttonWidth, 20)
			.build());
	}

	@Override
	public void tick() {
		super.tick();
		if (this.expiresAt > 0 && System.currentTimeMillis() > this.expiresAt) {
			this.callback.accept(false);
		}
	}

	@Override
	public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
		graphics.fill(0, 0, this.width, this.height, 0xFF000000);
	}

	@Override
	public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
		super.render(graphics, mouseX, mouseY, delta);
		graphics.drawCenteredString(this.font, this.title, this.width / 2, this.height / 2 - 30, 0xFFFFFFFF);
		graphics.drawCenteredString(this.font, this.message, this.width / 2, this.height / 2 - 10, 0xFFCCCCCC);

		if (this.expiresAt > 0) {
			long secondsLeft = Math.max(0, (this.expiresAt - System.currentTimeMillis() + 999) / 1000);
			graphics.drawCenteredString(this.font, Component.literal("Expires in " + secondsLeft + "s"),
				this.width / 2, this.height / 2 + 50, 0xFF888888);
		}
	}

	@Override
	public void onClose() {
		// Escape cancels the payment
		this.callback.accept(false);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
