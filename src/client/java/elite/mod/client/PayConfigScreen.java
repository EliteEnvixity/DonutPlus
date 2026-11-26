package elite.mod.client;

import java.math.BigDecimal;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Settings screen opened from Mod Menu. Payments on the left, chat timestamps on the right. */
public class PayConfigScreen extends Screen {
	private static final int COLUMN_WIDTH = 150;
	private static final int GAP = 20;

	private final Screen parent;

	// Payments
	private boolean enabled;
	private EditBox thresholdBox;
	private EditBox expiryBox;

	// Timestamps
	private boolean timestampsEnabled;
	private String brackets;
	private boolean hoverDate;
	private EditBox formatBox;
	private EditBox colorBox;

	private Component error = Component.empty();

	public PayConfigScreen(Screen parent) {
		super(Component.literal("DonutPLUS"));
		this.parent = parent;
	}

	private int top() {
		return Math.max(40, this.height / 2 - 100);
	}

	@Override
	protected void init() {
		PayConfig config = PayConfig.get();
		this.enabled = config.enabled;
		this.timestampsEnabled = config.timestampsEnabled;
		this.brackets = config.timestampBrackets;
		this.hoverDate = config.timestampHoverDate;

		int top = top();
		int left = this.width / 2 - COLUMN_WIDTH - GAP / 2;
		int right = this.width / 2 + GAP / 2;

		// --- Payments (left column) ---
		this.addRenderableWidget(Button.builder(enabledLabel(), b -> {
			this.enabled = !this.enabled;
			b.setMessage(enabledLabel());
		}).bounds(left, top, COLUMN_WIDTH, 20).build());

		this.thresholdBox = new EditBox(this.font, left, top + 40, COLUMN_WIDTH, 20, Component.literal("Threshold"));
		this.thresholdBox.setValue(config.threshold);
		this.addRenderableWidget(this.thresholdBox);

		this.expiryBox = new EditBox(this.font, left, top + 80, COLUMN_WIDTH, 20, Component.literal("Expiry"));
		this.expiryBox.setFilter(s -> s.matches("\\d{0,4}"));
		this.expiryBox.setValue(String.valueOf(config.expirySeconds));
		this.addRenderableWidget(this.expiryBox);

		// --- Chat timestamps (right column) ---
		this.addRenderableWidget(Button.builder(timestampsLabel(), b -> {
			this.timestampsEnabled = !this.timestampsEnabled;
			b.setMessage(timestampsLabel());
		}).bounds(right, top, COLUMN_WIDTH, 20).build());

		this.formatBox = new EditBox(this.font, right, top + 40, COLUMN_WIDTH, 20, Component.literal("Format"));
		this.formatBox.setMaxLength(32);
		this.formatBox.setValue(config.timestampFormat);
		this.addRenderableWidget(this.formatBox);

		this.colorBox = new EditBox(this.font, right, top + 80, COLUMN_WIDTH, 20, Component.literal("Color"));
		this.colorBox.setMaxLength(7);
		this.colorBox.setFilter(s -> s.matches("#?[0-9a-fA-F]{0,6}"));
		this.colorBox.setValue(config.timestampColor);
		this.addRenderableWidget(this.colorBox);

		int half = (COLUMN_WIDTH - 4) / 2;
		this.addRenderableWidget(Button.builder(bracketsLabel(), b -> {
			int next = (ChatTimestamp.BRACKET_STYLES.indexOf(this.brackets) + 1) % ChatTimestamp.BRACKET_STYLES.size();
			this.brackets = ChatTimestamp.BRACKET_STYLES.get(next);
			b.setMessage(bracketsLabel());
		}).bounds(right, top + 106, half, 20).build());

		this.addRenderableWidget(Button.builder(hoverLabel(), b -> {
			this.hoverDate = !this.hoverDate;
			b.setMessage(hoverLabel());
		}).bounds(right + half + 4, top + 106, half, 20).build());

		// --- Bottom buttons ---
		int bottom = top + 172;
		this.addRenderableWidget(Button.builder(Component.literal("Done"), b -> save())
			.bounds(this.width / 2 - 102, bottom, 100, 20).build());

		this.addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> this.onClose())
			.bounds(this.width / 2 + 2, bottom, 100, 20).build());
	}

	private Component enabledLabel() {
		return Component.literal("Payment confirmation: " + (this.enabled ? "On" : "Off"));
	}

	private Component timestampsLabel() {
		return Component.literal("Chat timestamps: " + (this.timestampsEnabled ? "On" : "Off"));
	}

	private Component bracketsLabel() {
		return Component.literal("Brackets: " + ChatTimestamp.bracketLabel(this.brackets));
	}

	private Component hoverLabel() {
		return Component.literal("Hover: " + (this.hoverDate ? "On" : "Off"));
	}

	private void save() {
		BigDecimal threshold = AmountParser.parse(this.thresholdBox.getValue());
		if (threshold == null || threshold.signum() <= 0) {
			this.error = Component.literal("Amount must look like 100m, 2.5b or 50000000");
			return;
		}

		String format = this.formatBox.getValue().trim();
		if (!ChatTimestamp.isValidPattern(format)) {
			this.error = Component.literal("Invalid time format. Try HH:mm, HH:mm:ss or hh:mm a");
			return;
		}

		if (ChatTimestamp.parseColor(this.colorBox.getValue()) < 0) {
			this.error = Component.literal("Color must be 6 hex digits, like AAAAAA or 55FF55");
			return;
		}
		String color = this.colorBox.getValue().trim();
		if (color.startsWith("#")) {
			color = color.substring(1);
		}

		String expiryText = this.expiryBox.getValue();
		int expiry = expiryText.isEmpty() ? 0 : Integer.parseInt(expiryText);

		PayConfig config = PayConfig.get();
		config.enabled = this.enabled;
		config.threshold = this.thresholdBox.getValue().trim();
		config.expirySeconds = expiry;
		config.timestampsEnabled = this.timestampsEnabled;
		config.timestampFormat = format;
		config.timestampColor = color.toUpperCase();
		config.timestampBrackets = this.brackets;
		config.timestampHoverDate = this.hoverDate;
		PayConfig.save();

		this.minecraft.setScreen(this.parent);
	}

	@Override
	public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
		super.render(graphics, mouseX, mouseY, delta);

		int top = top();
		int left = this.width / 2 - COLUMN_WIDTH - GAP / 2;
		int right = this.width / 2 + GAP / 2;

		graphics.drawCenteredString(this.font, this.title, this.width / 2, top - 24, 0xFFFFFFFF);

		graphics.drawString(this.font, Component.literal("Confirm payments at or above"), left, top + 28, 0xFFAAAAAA);
		graphics.drawString(this.font, Component.literal("Expires after, seconds (0 = never)"), left, top + 68, 0xFFAAAAAA);

		graphics.drawString(this.font, Component.literal("Format (HH:mm, hh:mm a)"), right, top + 28, 0xFFAAAAAA);
		graphics.drawString(this.font, Component.literal("Color (hex, e.g. AAAAAA)"), right, top + 68, 0xFFAAAAAA);

		// Live preview: uses the same code as real chat, so what you see here is what you get.
		// Invalid values in the boxes fall back to defaults here instead of throwing.
		Component preview = ChatTimestamp.stamp(Component.literal("Hello world"),
			this.formatBox.getValue().trim(), this.colorBox.getValue(), this.brackets, false);
		graphics.drawString(this.font, Component.literal("Preview:"), right, top + 136, 0xFFAAAAAA);
		graphics.drawString(this.font, preview, right + this.font.width("Preview: "), top + 136, 0xFFFFFFFF);
		if (!this.timestampsEnabled) {
			graphics.drawString(this.font, Component.literal("(timestamps are off)"), right, top + 148, 0xFF777777);
		}

		graphics.drawCenteredString(this.font, this.error, this.width / 2, top + 198, 0xFFFF5555);
	}

	@Override
	public void onClose() {
		this.minecraft.setScreen(this.parent);
	}
}
