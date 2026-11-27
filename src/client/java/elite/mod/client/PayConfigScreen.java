package elite.mod.client;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Settings screen opened from Mod Menu, split into six tabs: Safe Pay, Payback, Minimum, Chat, Stats and Auction. */
public class PayConfigScreen extends Screen {
	private static final int WIDTH = 330;
	private static final int GAP = 4;

	private static final int TAB_SAFE_PAY = 0;
	private static final int TAB_PAYBACK = 1;
	private static final int TAB_MINIMUM = 2;
	private static final int TAB_CHAT = 3;
	private static final int TAB_STATS = 4;
	private static final int TAB_AUCTION = 5;
	private static final String[] TAB_NAMES = {"Safe Pay", "Payback", "Minimum", "Chat", "Stats", "Auction"};

	private final Screen parent;

	/** Which tab is showing. Kept when the window is resized. */
	private int tab = TAB_SAFE_PAY;
	private final Button[] tabButtons = new Button[TAB_NAMES.length];
	private final List<List<AbstractWidget>> tabWidgets = new ArrayList<>();

	// Safe Pay
	private boolean enabled;
	private EditBox thresholdBox;
	private EditBox expiryBox;

	// Payback
	private boolean paybackConfirm;
	private EditBox multiplierBox;
	private EditBox commandBox;
	private EditBox windowBox;
	private EditBox delayBox;
	private EditBox maxPayoutBox;
	private String activeCommand;

	// Minimum
	private EditBox minimumBox;
	private boolean hideUnderMinimum;

	// Chat timestamps
	private boolean timestampsEnabled;
	private String brackets;
	private boolean hoverDate;
	private EditBox formatBox;
	private EditBox colorBox;

	// Stats
	private boolean statsHud;
	private int statsX;
	private int statsY;
	private boolean positionOpen;
	private EditBox xBox;
	private EditBox yBox;
	private boolean clearArmed;
	private Button clearButton;
	private EditBox startBalanceBox;
	private String startBalanceText = "";

	// Auction
	private boolean auctionHud;
	private EditBox auctionXBox;
	private EditBox auctionYBox;

	private Component error = Component.empty();

	public PayConfigScreen(Screen parent) {
		super(Component.literal("DonutPLUS"));
		this.parent = parent;
	}

	private int top() {
		return Math.max(30, this.height / 2 - 125);
	}

	/** Adds a widget that only shows while the given tab is open. */
	private <T extends AbstractWidget> T addTabbed(int tabIndex, T widget) {
		this.tabWidgets.get(tabIndex).add(widget);
		return this.addRenderableWidget(widget);
	}

	@Override
	protected void init() {
		PayConfig config = PayConfig.get();
		this.enabled = config.enabled;
		this.timestampsEnabled = config.timestampsEnabled;
		this.brackets = config.timestampBrackets;
		this.hoverDate = config.timestampHoverDate;
		this.paybackConfirm = config.paybackConfirm;
		this.hideUnderMinimum = config.paybackHideUnderMinimum;
		this.activeCommand = config.paybackCommand;
		this.statsHud = config.statsHudEnabled;
		this.statsX = config.statsHudX;
		this.statsY = config.statsHudY;
		this.auctionHud = config.auctionHudEnabled;
		this.positionOpen = false;
		this.clearArmed = false;

		this.tabWidgets.clear();
		for (int i = 0; i < TAB_NAMES.length; i++) {
			this.tabWidgets.add(new ArrayList<>());
		}

		int top = top();
		int left = this.width / 2 - WIDTH / 2;
		int half = (WIDTH - GAP) / 2;
		int right = left + half + GAP;
		int y0 = top + 30;

		// --- Tab buttons ---
		int tabWidth = (WIDTH - (TAB_NAMES.length - 1) * GAP) / TAB_NAMES.length;
		for (int i = 0; i < TAB_NAMES.length; i++) {
			final int index = i;
			this.tabButtons[i] = this.addRenderableWidget(Button.builder(Component.literal(TAB_NAMES[i]), b -> showTab(index))
				.bounds(left + i * (tabWidth + GAP), top, tabWidth, 20).build());
		}

		// --- Safe Pay tab ---
		addTabbed(TAB_SAFE_PAY, Button.builder(enabledLabel(), b -> {
			this.enabled = !this.enabled;
			b.setMessage(enabledLabel());
		}).bounds(left, y0, WIDTH, 20).build());

		this.thresholdBox = addTabbed(TAB_SAFE_PAY,
			new EditBox(this.font, left, y0 + 40, WIDTH, 20, Component.literal("Threshold")));
		this.thresholdBox.setValue(config.threshold);

		this.expiryBox = addTabbed(TAB_SAFE_PAY,
			new EditBox(this.font, left, y0 + 80, WIDTH, 20, Component.literal("Expiry")));
		this.expiryBox.setFilter(s -> s.matches("\\d{0,4}"));
		this.expiryBox.setValue(String.valueOf(config.expirySeconds));

		// --- Payback tab ---
		addTabbed(TAB_PAYBACK, Button.builder(confirmLabel(), b -> {
			this.paybackConfirm = !this.paybackConfirm;
			b.setMessage(confirmLabel());
		}).bounds(left, y0, WIDTH, 20).build());

		this.multiplierBox = addTabbed(TAB_PAYBACK,
			new EditBox(this.font, left, y0 + 40, half, 20, Component.literal("Multiplier")));
		this.multiplierBox.setMaxLength(6);
		this.multiplierBox.setFilter(s -> s.matches("\\d{0,3}(\\.\\d{0,2})?"));
		this.multiplierBox.setValue(BigDecimal.valueOf(config.paybackMultiplier).stripTrailingZeros().toPlainString());

		// The command name, without the slash. Only read at startup, so a change needs a restart.
		this.commandBox = addTabbed(TAB_PAYBACK,
			new EditBox(this.font, right, y0 + 40, half, 20, Component.literal("Command")));
		this.commandBox.setMaxLength(32);
		this.commandBox.setFilter(s -> s.matches("[A-Za-z0-9_]{0,32}"));
		this.commandBox.setValue(config.paybackCommand);

		this.windowBox = addTabbed(TAB_PAYBACK,
			new EditBox(this.font, left, y0 + 80, half, 20, Component.literal("Window")));
		this.windowBox.setMaxLength(6);
		this.windowBox.setFilter(s -> s.matches("\\d{0,6}"));
		this.windowBox.setValue(String.valueOf(config.paybackWindowSeconds));

		this.delayBox = addTabbed(TAB_PAYBACK,
			new EditBox(this.font, right, y0 + 80, half, 20, Component.literal("Delay")));
		this.delayBox.setMaxLength(3);
		this.delayBox.setFilter(s -> s.matches("\\d{0,3}"));
		this.delayBox.setValue(String.valueOf(config.paybackDelayTicks));

		this.maxPayoutBox = addTabbed(TAB_PAYBACK,
			new EditBox(this.font, left, y0 + 120, WIDTH, 20, Component.literal("Max payout")));
		this.maxPayoutBox.setMaxLength(16);
		this.maxPayoutBox.setValue(config.paybackMaxPayout);

		// --- Minimum tab ---
		this.minimumBox = addTabbed(TAB_MINIMUM,
			new EditBox(this.font, left, y0 + 12, WIDTH, 20, Component.literal("Minimum")));
		this.minimumBox.setMaxLength(16);
		this.minimumBox.setValue(config.paybackMinimum);

		addTabbed(TAB_MINIMUM, Button.builder(hideLabel(), b -> {
			this.hideUnderMinimum = !this.hideUnderMinimum;
			b.setMessage(hideLabel());
		}).bounds(left, y0 + 56, WIDTH, 20).build());

		// --- Chat tab ---
		addTabbed(TAB_CHAT, Button.builder(timestampsLabel(), b -> {
			this.timestampsEnabled = !this.timestampsEnabled;
			b.setMessage(timestampsLabel());
		}).bounds(left, y0, WIDTH, 20).build());

		this.formatBox = addTabbed(TAB_CHAT,
			new EditBox(this.font, left, y0 + 40, WIDTH, 20, Component.literal("Format")));
		this.formatBox.setMaxLength(32);
		this.formatBox.setValue(config.timestampFormat);

		this.colorBox = addTabbed(TAB_CHAT,
			new EditBox(this.font, left, y0 + 80, WIDTH, 20, Component.literal("Color")));
		this.colorBox.setMaxLength(7);
		this.colorBox.setFilter(s -> s.matches("#?[0-9a-fA-F]{0,6}"));
		this.colorBox.setValue(config.timestampColor);

		addTabbed(TAB_CHAT, Button.builder(bracketsLabel(), b -> {
			int next = (ChatTimestamp.BRACKET_STYLES.indexOf(this.brackets) + 1) % ChatTimestamp.BRACKET_STYLES.size();
			this.brackets = ChatTimestamp.BRACKET_STYLES.get(next);
			b.setMessage(bracketsLabel());
		}).bounds(left, y0 + 106, half, 20).build());

		addTabbed(TAB_CHAT, Button.builder(hoverLabel(), b -> {
			this.hoverDate = !this.hoverDate;
			b.setMessage(hoverLabel());
		}).bounds(right, y0 + 106, half, 20).build());

		// --- Stats tab ---
		addTabbed(TAB_STATS, Button.builder(statsHudLabel(), b -> {
			this.statsHud = !this.statsHud;
			b.setMessage(statsHudLabel());
		}).bounds(left, y0, WIDTH, 20).build());

		addTabbed(TAB_STATS, Button.builder(positionLabel(), b -> {
			if (this.positionOpen) {
				this.statsX = parseCoord(this.xBox.getValue());
				this.statsY = parseCoord(this.yBox.getValue());
			}
			this.positionOpen = !this.positionOpen;
			b.setMessage(positionLabel());
			applyPositionVisibility();
		}).bounds(left, y0 + 24, WIDTH, 20).build());

		// Shown when the position button is clicked: where the box sits, in pixels from the top-left of the screen.
		int fieldWidth = (WIDTH - 36) / 2;
		this.xBox = addTabbed(TAB_STATS,
			new EditBox(this.font, left + 14, y0 + 48, fieldWidth, 20, Component.literal("X")));
		this.xBox.setMaxLength(4);
		this.xBox.setFilter(s -> s.matches("\\d{0,4}"));
		this.xBox.setValue(String.valueOf(this.statsX));
		this.yBox = addTabbed(TAB_STATS,
			new EditBox(this.font, left + 22 + fieldWidth + 14, y0 + 48, fieldWidth, 20, Component.literal("Y")));
		this.yBox.setMaxLength(4);
		this.yBox.setFilter(s -> s.matches("\\d{0,4}"));
		this.yBox.setValue(String.valueOf(this.statsY));

		// Clearing happens right away (it doesn't wait for Done) and takes two clicks so it can't be done by accident.
		this.clearButton = addTabbed(TAB_STATS, Button.builder(clearLabel(), b -> {
			if (this.clearArmed) {
				SessionStats.clear();
				this.clearArmed = false;
				if (this.startBalanceBox != null) {
					this.startBalanceBox.setValue("");
					this.startBalanceText = "";
				}
			} else {
				this.clearArmed = true;
			}
			b.setMessage(clearLabel());
		}).bounds(left, y0 + 72, WIDTH, 20).build());

		// Your balance when the session started. Blank = unknown: it's picked up from a balance message when one shows up.
		this.startBalanceBox = addTabbed(TAB_STATS,
			new EditBox(this.font, left, y0 + 112, WIDTH, 20, Component.literal("Starting balance")));
		this.startBalanceBox.setMaxLength(16);
		this.startBalanceText = SessionStats.startBalance() == null ? ""
			: SessionStats.startBalance().toBigInteger().toString();
		this.startBalanceBox.setValue(this.startBalanceText);

		// --- Auction tab ---
		addTabbed(TAB_AUCTION, Button.builder(auctionLabel(), b -> {
			this.auctionHud = !this.auctionHud;
			b.setMessage(auctionLabel());
		}).bounds(left, y0, WIDTH, 20).build());

		this.auctionXBox = addTabbed(TAB_AUCTION,
			new EditBox(this.font, left, y0 + 52, half, 20, Component.literal("X")));
		this.auctionXBox.setMaxLength(4);
		this.auctionXBox.setFilter(s -> s.matches("\\d{0,4}"));
		this.auctionXBox.setValue(String.valueOf(config.auctionHudX));

		this.auctionYBox = addTabbed(TAB_AUCTION,
			new EditBox(this.font, right, y0 + 52, half, 20, Component.literal("Y")));
		this.auctionYBox.setMaxLength(4);
		this.auctionYBox.setFilter(s -> s.matches("\\d{0,4}"));
		this.auctionYBox.setValue(String.valueOf(config.auctionHudY));

		// --- Bottom buttons (always visible) ---
		int bottom = Math.min(y0 + 176, this.height - 28);
		this.addRenderableWidget(Button.builder(Component.literal("Done"), b -> save())
			.bounds(this.width / 2 - 102, bottom, 100, 20).build());

		this.addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> this.onClose())
			.bounds(this.width / 2 + 2, bottom, 100, 20).build());

		showTab(this.tab);
		// showTab clears the error; nothing to show yet on a fresh init anyway.
	}

	/** Shows one tab's widgets and hides the others. The open tab's button is greyed out. */
	private void showTab(int index) {
		this.tab = index;
		this.error = Component.empty();
		this.setFocused(null);
		this.clearArmed = false;
		if (this.clearButton != null) {
			this.clearButton.setMessage(clearLabel());
		}

		for (int i = 0; i < this.tabWidgets.size(); i++) {
			for (AbstractWidget widget : this.tabWidgets.get(i)) {
				widget.visible = i == index;
			}
			this.tabButtons[i].active = i != index;
		}
		applyPositionVisibility();
	}

	/** Jumps to the tab with the problem and shows the message there. */
	private void fail(int tabIndex, String message) {
		showTab(tabIndex);
		this.error = Component.literal(message);
	}

	private Component enabledLabel() {
		return Component.literal("Payment confirmation: " + (this.enabled ? "On" : "Off"));
	}

	private Component hideLabel() {
		return Component.literal("Hide payments under the minimum: " + (this.hideUnderMinimum ? "On" : "Off"));
	}

	private Component confirmLabel() {
		return Component.literal("Ask before paying back: " + (this.paybackConfirm ? "On" : "Off"));
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

	private Component statsHudLabel() {
		return Component.literal("Session earnings box: " + (this.statsHud ? "On" : "Off"));
	}

	private Component positionLabel() {
		return Component.literal(this.positionOpen ? "Box position: set X and Y below (click to close)"
			: "Box position: X " + this.statsX + ", Y " + this.statsY + " (click to change)");
	}

	/** The X and Y boxes only show while the position button is open. */
	private void applyPositionVisibility() {
		boolean show = this.tab == TAB_STATS && this.positionOpen;
		if (this.xBox != null) {
			this.xBox.visible = show;
		}
		if (this.yBox != null) {
			this.yBox.visible = show;
		}
	}

	private static int parseCoord(String text) {
		if (text == null || text.isEmpty()) {
			return 0;
		}
		try {
			return Math.min(9999, Integer.parseInt(text));
		} catch (NumberFormatException e) {
			return 0;
		}
	}

	private Component auctionLabel() {
		return Component.literal("Auction box: " + (this.auctionHud ? "On" : "Off"));
	}

	private Component clearLabel() {
		return Component.literal(this.clearArmed ? "Click again to clear (can't be undone)" : "Clear session earnings");
	}

	private void save() {
		// --- Safe Pay ---
		BigDecimal threshold = AmountParser.parse(this.thresholdBox.getValue());
		if (threshold == null || threshold.signum() <= 0) {
			fail(TAB_SAFE_PAY, "Amount must look like 100m, 2.5b or 50000000");
			return;
		}

		String expiryText = this.expiryBox.getValue();
		int expiry = expiryText.isEmpty() ? 0 : Integer.parseInt(expiryText);

		// --- Payback ---
		double multiplier;
		try {
			multiplier = Double.parseDouble(this.multiplierBox.getValue().trim());
		} catch (NumberFormatException e) {
			multiplier = -1;
		}
		if (!(multiplier > 0) || multiplier > 100) {
			fail(TAB_PAYBACK, "Payback multiplier must be between 0.01 and 100");
			return;
		}

		String command = this.commandBox.getValue().trim().toLowerCase(Locale.ROOT);
		if (!PayConfig.isValidPaybackCommand(command)) {
			fail(TAB_PAYBACK, "Command must be one word (letters, digits, _), not pay, epay or paysafe");
			return;
		}

		String windowText = this.windowBox.getValue();
		int window = windowText.isEmpty() ? 0 : Integer.parseInt(windowText);

		String delayText = this.delayBox.getValue();
		int delay = delayText.isEmpty() ? 1 : Math.max(1, Integer.parseInt(delayText));

		String maxPayout = this.maxPayoutBox.getValue().trim();
		if (maxPayout.isEmpty()) {
			maxPayout = "0";
		}
		if (AmountParser.parse(maxPayout) == null) {
			fail(TAB_PAYBACK, "Max payout must look like 100m, 2.5b or 0 for no limit");
			return;
		}

		// --- Minimum ---
		String minimum = this.minimumBox.getValue().trim();
		if (minimum.isEmpty()) {
			minimum = "0";
		}
		if (AmountParser.parse(minimum) == null) {
			fail(TAB_MINIMUM, "Minimum must look like 100k, 2.5m or 0 for no minimum");
			return;
		}

		// --- Chat ---
		String format = this.formatBox.getValue().trim();
		if (!ChatTimestamp.isValidPattern(format)) {
			fail(TAB_CHAT, "Invalid time format. Try HH:mm, HH:mm:ss or hh:mm a");
			return;
		}

		if (ChatTimestamp.parseColor(this.colorBox.getValue()) < 0) {
			fail(TAB_CHAT, "Color must be 6 hex digits, like AAAAAA or 55FF55");
			return;
		}
		String color = this.colorBox.getValue().trim();
		if (color.startsWith("#")) {
			color = color.substring(1);
		}

		// --- Stats ---
		int hudX = parseCoord(this.xBox.getValue());
		int hudY = parseCoord(this.yBox.getValue());
		String startText = this.startBalanceBox.getValue().trim().replace("$", "");
		BigDecimal startBalance = null;
		if (!startText.isEmpty()) {
			startBalance = AmountParser.parse(startText);
			if (startBalance == null) {
				fail(TAB_STATS, "Balance must look like 3.4b, 250m or 50000000");
				return;
			}
		}

		// --- Auction ---
		int auctionX = parseCoord(this.auctionXBox.getValue());
		int auctionY = parseCoord(this.auctionYBox.getValue());

		PayConfig config = PayConfig.get();
		config.threshold = this.thresholdBox.getValue().trim();
		config.expirySeconds = expiry;
		config.paybackConfirm = this.paybackConfirm;
		config.paybackMultiplier = multiplier;
		config.paybackCommand = command;
		config.paybackWindowSeconds = window;
		config.paybackDelayTicks = delay;
		config.paybackMaxPayout = maxPayout;
		config.paybackMinimum = minimum;
		config.paybackHideUnderMinimum = this.hideUnderMinimum;
		config.timestampsEnabled = this.timestampsEnabled;
		config.timestampFormat = format;
		config.timestampColor = color.toUpperCase();
		config.timestampBrackets = this.brackets;
		config.timestampHoverDate = this.hoverDate;
		config.statsHudEnabled = this.statsHud;
		config.statsHudX = hudX;
		config.statsHudY = hudY;
		config.auctionHudEnabled = this.auctionHud;
		config.auctionHudX = auctionX;
		config.auctionHudY = auctionY;
		config.enabled = this.enabled;
		PayConfig.save();

		// Only touch the saved balance if it was edited, so reopening and pressing Done can't change it.
		if (!startText.equals(this.startBalanceText)) {
			SessionStats.setStartBalance(startBalance);
		}

		this.minecraft.setScreen(this.parent);
	}

	@Override
	public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
		super.render(graphics, mouseX, mouseY, delta);

		int top = top();
		int left = this.width / 2 - WIDTH / 2;
		int half = (WIDTH - GAP) / 2;
		int right = left + half + GAP;
		int y0 = top + 30;

		graphics.drawCenteredString(this.font, this.title, this.width / 2, top - 18, 0xFFFFFFFF);

		switch (this.tab) {
			case TAB_SAFE_PAY -> {
				graphics.drawString(this.font, Component.literal("Confirm payments at or above"), left, y0 + 28, 0xFFAAAAAA);
				graphics.drawString(this.font, Component.literal("Expires after, seconds (0 = never)"), left, y0 + 68, 0xFFAAAAAA);
			}
			case TAB_PAYBACK -> {
				graphics.drawString(this.font, Component.literal("Multiplier (2 = double)"), left, y0 + 28, 0xFFAAAAAA);
				graphics.drawString(this.font, Component.literal("Command (restart)"), right, y0 + 28, 0xFFAAAAAA);
				graphics.drawString(this.font, Component.literal("Counts last (sec, 0 = all)"), left, y0 + 68, 0xFFAAAAAA);
				graphics.drawString(this.font, Component.literal("Delay (ticks)"), right, y0 + 68, 0xFFAAAAAA);
				graphics.drawString(this.font, Component.literal("Max payout per player (0 = no limit)"), left, y0 + 108, 0xFFAAAAAA);
			}
			case TAB_MINIMUM -> {
				graphics.drawString(this.font, Component.literal("Minimum total to pay back"), left, y0, 0xFFAAAAAA);
				graphics.drawString(this.font, Component.literal("Payments add up per player. 0 = off."),
					left, y0 + 38, 0xFF777777);
			}
			case TAB_CHAT -> {
				graphics.drawString(this.font, Component.literal("Format (HH:mm, hh:mm a)"), left, y0 + 28, 0xFFAAAAAA);
				graphics.drawString(this.font, Component.literal("Color (hex, e.g. AAAAAA)"), left, y0 + 68, 0xFFAAAAAA);

				// Live preview: uses the same code as real chat, so what you see here is what you get.
				// Invalid values in the boxes fall back to defaults here instead of throwing.
				Component preview = ChatTimestamp.stamp(Component.literal("Hello world"),
					this.formatBox.getValue().trim(), this.colorBox.getValue(), this.brackets, false);
				graphics.drawString(this.font, Component.literal("Preview:"), left, y0 + 136, 0xFFAAAAAA);
				graphics.drawString(this.font, preview, left + this.font.width("Preview: "), y0 + 136, 0xFFFFFFFF);
				if (!this.timestampsEnabled) {
					graphics.drawString(this.font, Component.literal("(timestamps are off)"), left, y0 + 148, 0xFF777777);
				}
			}
			case TAB_STATS -> {
				BigDecimal net = SessionStats.net();
				if (this.positionOpen) {
					graphics.drawString(this.font, Component.literal("X"), left + 2, y0 + 54, 0xFFAAAAAA);
					graphics.drawString(this.font, Component.literal("Y"), left + 22 + (WIDTH - 36) / 2 + 2, y0 + 54, 0xFFAAAAAA);
				}
				graphics.drawString(this.font, Component.literal((net.signum() < 0 ? "Lost: " : "Made: ") + "$"
					+ AmountParser.format(net.abs())), left, y0 + 140, net.signum() < 0 ? 0xFFFF5555 : 0xFF55FF55);
				String since = SessionStats.since() == 0L ? "Nothing recorded yet"
					: "Counting since " + java.time.format.DateTimeFormatter.ofPattern("MMM d, HH:mm")
						.format(java.time.Instant.ofEpochMilli(SessionStats.since()).atZone(java.time.ZoneId.systemDefault()));
				graphics.drawString(this.font, Component.literal(since), left, y0 + 152, 0xFF777777);
				graphics.drawString(this.font, Component.literal("Balance when the session started (blank = auto)"),
					left, y0 + 100, 0xFFAAAAAA);
			}
			case TAB_AUCTION -> {
				graphics.drawString(this.font, Component.literal("Box position: X and Y (pixels from top-left)"), left, y0 + 40, 0xFFAAAAAA);
				graphics.drawString(this.font, Component.literal("The item, length and minimum bid are set in the auction menu."), left, y0 + 84, 0xFF777777);
				graphics.drawString(this.font, Component.literal("Set the auction menu key in Options > Controls > Key Binds."), left, y0 + 96, 0xFF777777);
			}
			default -> {
			}
		}

		// Messages under the tabs, above the Done/Cancel row.
		int messageY = Math.min(y0 + 204, this.height - 12);
		if (!this.error.getString().isEmpty()) {
			graphics.drawCenteredString(this.font, this.error, this.width / 2, messageY, 0xFFFF5555);
		} else if (!this.commandBox.getValue().trim().equalsIgnoreCase(this.activeCommand)) {
			graphics.drawCenteredString(this.font,
				Component.literal("The new payback command applies after restarting the game."),
				this.width / 2, messageY, 0xFFFFFF55);
		}
	}

	@Override
	public void onClose() {
		this.minecraft.setScreen(this.parent);
	}
}
