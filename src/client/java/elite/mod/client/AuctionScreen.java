package elite.mod.client;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * The auction menu, opened with the auction keybind. A dark window that slides up when it opens and down when
 * it closes, with two tabs of the same size:
 *
 * Item: a search box above a scrollable grid of every item and block, and a preview frame with the chosen
 * item, the minimum bid, the length and the Start button (or End while an auction is running).
 *
 * Settings: the on-screen overlay and how the auction behaves.
 *
 * The orange bar under the tabs slides from one tab to the other while the whole tab slides with it.
 * All the drawing is done here; the buttons and boxes are real widgets so clicks, typing and focus work
 * normally. Only the widgets of the open tab get input, and none while something is sliding.
 */
public class AuctionScreen extends Screen {
	// Window layout, in GUI pixels. Everything below is relative to the window's top-left corner.
	private static final int PW = 404;
	private static final int PH = 224;
	private static final int PAD = 12;

	private static final int TAB_Y = 26;
	private static final int TAB_H = 16;
	private static final int TAB_W = (PW - PAD * 2) / 2;
	private static final int BAR_Y = 41;
	private static final int CONTENT_Y = 48;
	private static final int CONTENT_H = 164;

	// Item tab, left side
	private static final int LEFT_W = 222;
	private static final int SEARCH_Y = 48;
	private static final int GRID_Y = 70;
	private static final int COLS = 12;
	private static final int ROWS = 6;
	private static final int CELL = 18;
	private static final int HELD_Y = 184;
	private static final int WHEEL_ROWS = 2;

	// Item tab, preview frame on the right
	private static final int FRAME_X = PAD + LEFT_W + 8;
	private static final int FRAME_W = PW - PAD - FRAME_X;
	private static final int MIN_Y = 100;
	private static final int DUR_Y = 122;
	private static final int MSG_Y = 146;
	private static final int START_Y = 184;
	private static final int FIELD_W = 56;

	// Settings tab
	private static final int SET_W = PW - PAD * 2;
	private static final int ROW_H = 22;
	private static final int ROW_Y = CONTENT_Y + 4;

	private static final long OPEN_MS = 240L;
	private static final long CLOSE_MS = 190L;
	private static final long TAB_MS = 230L;

	private static final int C_PANEL = 0xFF1C1C1C;
	private static final int C_BORDER = 0xFF333333;
	private static final int C_FRAME = 0xFF242424;
	private static final int C_FIELD = 0xFF262626;
	private static final int C_FIELD_EDGE = 0xFF555555;
	private static final int C_BTN = 0xFF2E2E2E;
	private static final int C_BTN_HOVER = 0xFF3A3A3A;
	private static final int C_ACCENT = 0xFFFF4A2B;
	private static final int C_TEXT = 0xFFE8E8E8;
	private static final int C_DIM = 0xFF888888;
	private static final int C_GOLD = 0xFFFFAA00;
	private static final int C_RED = 0xFFFF5555;
	private static final int C_LINE = 0xFF2E2E2E;

	/** One item or block in the list. The search text is the name plus the id, lowercase. */
	private record Entry(ItemStack stack, String name, String search) {}

	private static List<Entry> allEntries;

	/** Every item and block, built the first time the menu opens. */
	private static List<Entry> all() {
		if (allEntries == null) {
			List<Entry> list = new ArrayList<>();
			for (Item item : BuiltInRegistries.ITEM) {
				if (item == Items.AIR) {
					continue;
				}
				ItemStack stack = item.getDefaultInstance();
				String name = stack.getHoverName().getString();
				var key = BuiltInRegistries.ITEM.getKey(item);
				String search = (name + " " + key.getPath().replace('_', ' ')).toLowerCase(Locale.ROOT);
				list.add(new Entry(stack, name, search));
			}
			allEntries = list;
		}
		return allEntries;
	}

	private final List<Entry> filtered = new ArrayList<>();
	private String query = "";
	private int scrollRow = 0;
	private Entry selected = null;
	/** The name shown for the auction. Empty = no item. */
	private String selectedName = "";
	private boolean selectionLoaded = false;
	private Component error = Component.empty();
	private boolean settingsDirty = false;

	// Widgets. The fixed ones (tab buttons) always take input; each tab's own only when it is open and still.
	private final List<AbstractWidget> fixed = new ArrayList<>();
	private final List<AbstractWidget> itemWidgets = new ArrayList<>();
	private final List<AbstractWidget> settingsWidgets = new ArrayList<>();
	private final List<AbstractWidget> itemInput = new ArrayList<>();
	private final List<AbstractWidget> settingsInput = new ArrayList<>();

	private EditBox searchBox;
	private EditBox minBox;
	private EditBox durationBox;
	private EditBox hudXBox;
	private EditBox hudYBox;
	private EditBox warnBox;
	private EditBox resultBox;
	private Button[] cells = new Button[COLS * ROWS];

	// Animation state
	private long openStart = -1L;
	private boolean closing = false;
	private long closeStart = 0L;
	private Runnable afterClose = null;
	private int tab = 0;
	private float tabFrom = 0f;
	private float tabTo = 0f;
	private long tabStart = 0L;

	public AuctionScreen() {
		super(Component.literal("DonutPLUS Auction"));
	}

	private int px() {
		return this.width / 2 - PW / 2;
	}

	private int py() {
		return Math.max(2, this.height / 2 - PH / 2);
	}

	// ---------------------------------------------------------------- setup

	@Override
	protected void init() {
		this.fixed.clear();
		this.itemWidgets.clear();
		this.settingsWidgets.clear();
		this.itemInput.clear();
		this.settingsInput.clear();
		this.cells = new Button[COLS * ROWS];

		PayConfig config = PayConfig.get();
		boolean running = AuctionHelper.isRunning();
		int px = px();
		int py = py();
		int left = px + PAD;

		// Remembered for next time, or what's being auctioned if one is already running.
		if (!this.selectionLoaded) {
			this.selectedName = running ? AuctionHelper.item() : config.auctionItem;
			this.selected = findByName(this.selectedName);
			this.selectionLoaded = true;
		}
		refilter();

		// --- Tab buttons (invisible; the labels are drawn by us) ---
		this.fixed.add(hit(left, py + TAB_Y, TAB_W, TAB_H, () -> setTab(0)));
		this.fixed.add(hit(left + TAB_W, py + TAB_Y, TAB_W, TAB_H, () -> setTab(1)));

		// --- Item tab: search ---
		this.searchBox = new EditBox(this.font, left + 5, py + SEARCH_Y + 5, LEFT_W - 10, 13, Component.literal("Search"));
		this.searchBox.setBordered(false);
		this.searchBox.setMaxLength(40);
		this.searchBox.setHint(Component.literal("Search every item..."));
		this.searchBox.setValue(this.query);
		this.searchBox.setResponder(text -> {
			this.query = text;
			this.scrollRow = 0;
			refilter();
		});
		this.itemWidgets.add(this.searchBox);

		// --- Item tab: grid (invisible buttons give the clicks, the icons are drawn by us) ---
		int gridX = left;
		int gridY = py + GRID_Y;
		for (int i = 0; i < this.cells.length; i++) {
			final int slot = i;
			this.cells[i] = hit(gridX + (i % COLS) * CELL, gridY + (i / COLS) * CELL, CELL, CELL, () -> pick(slot));
			this.itemWidgets.add(this.cells[i]);
		}

		// Clicking the top or bottom half of the scroll bar pages up or down.
		int trackX = left + COLS * CELL + 2;
		this.itemWidgets.add(hit(trackX - 1, gridY, 6, ROWS * CELL / 2, () -> scrollBy(-3)));
		this.itemWidgets.add(hit(trackX - 1, gridY + ROWS * CELL / 2, 6, ROWS * CELL / 2, () -> scrollBy(3)));

		// --- Item tab: use the held item ---
		Button held = hit(left, py + HELD_Y, LEFT_W, 16, this::useHeldItem);
		held.active = !running;
		this.itemWidgets.add(held);

		// --- Item tab: preview frame fields ---
		int fx = px + FRAME_X;
		int boxX = fx + FRAME_W - 8 - FIELD_W;
		this.minBox = fieldBox(boxX, py + MIN_Y, FIELD_W, 16, 16, "Minimum bid");
		this.minBox.setValue(config.auctionMinBid);
		this.minBox.setEditable(!running);
		this.itemWidgets.add(this.minBox);

		this.durationBox = fieldBox(boxX, py + DUR_Y, FIELD_W, 16, 4, "Length");
		this.durationBox.setFilter(s -> s.matches("\\d{0,4}"));
		this.durationBox.setValue(String.valueOf(config.auctionDurationSeconds));
		this.durationBox.setEditable(!running);
		this.itemWidgets.add(this.durationBox);

		this.itemWidgets.add(hit(fx + 8, py + START_Y, FRAME_W - 16, 20, running ? this::endAuction : this::startAuction));

		// --- Settings tab ---
		int rightX = left + SET_W - 8;
		int[] rowY = new int[7];
		for (int i = 0; i < rowY.length; i++) {
			rowY[i] = py + ROW_Y + i * ROW_H;
		}
		this.settingsWidgets.add(hit(rightX - 44, rowY[0] + 3, 44, 14, () -> {
			config.auctionHudEnabled = !config.auctionHudEnabled;
			this.settingsDirty = true;
		}));
		this.hudXBox = fieldBox(rightX - 84, rowY[1] + 3, 40, 14, 4, "Overlay X");
		this.hudXBox.setFilter(s -> s.matches("\\d{0,4}"));
		this.hudXBox.setValue(String.valueOf(config.auctionHudX));
		this.hudXBox.setResponder(s -> {
			config.auctionHudX = parseInt(s, 0, 9999, config.auctionHudX);
			this.settingsDirty = true;
		});
		this.settingsWidgets.add(this.hudXBox);
		this.hudYBox = fieldBox(rightX - 40, rowY[1] + 3, 40, 14, 4, "Overlay Y");
		this.hudYBox.setFilter(s -> s.matches("\\d{0,4}"));
		this.hudYBox.setValue(String.valueOf(config.auctionHudY));
		this.hudYBox.setResponder(s -> {
			config.auctionHudY = parseInt(s, 0, 9999, config.auctionHudY);
			this.settingsDirty = true;
		});
		this.settingsWidgets.add(this.hudYBox);
		this.settingsWidgets.add(hit(rightX - 44, rowY[2] + 3, 44, 14, () -> {
			config.auctionShowUnderMin = !config.auctionShowUnderMin;
			this.settingsDirty = true;
		}));
		this.settingsWidgets.add(hit(rightX - 44, rowY[3] + 3, 44, 14, () -> {
			config.auctionChatUpdates = !config.auctionChatUpdates;
			this.settingsDirty = true;
		}));
		this.settingsWidgets.add(hit(rightX - 44, rowY[4] + 3, 44, 14, () -> {
			config.auctionSound = !config.auctionSound;
			this.settingsDirty = true;
		}));
		this.warnBox = fieldBox(rightX - 40, rowY[5] + 3, 40, 14, 3, "Warn seconds");
		this.warnBox.setFilter(s -> s.matches("\\d{0,3}"));
		this.warnBox.setValue(String.valueOf(config.auctionWarnSeconds));
		this.warnBox.setResponder(s -> {
			config.auctionWarnSeconds = parseInt(s, 0, 600, config.auctionWarnSeconds);
			this.settingsDirty = true;
		});
		this.settingsWidgets.add(this.warnBox);
		this.resultBox = fieldBox(rightX - 40, rowY[6] + 3, 40, 14, 4, "Result seconds");
		this.resultBox.setFilter(s -> s.matches("\\d{0,4}"));
		this.resultBox.setValue(String.valueOf(config.auctionResultSeconds));
		this.resultBox.setResponder(s -> {
			config.auctionResultSeconds = parseInt(s, 0, 3600, config.auctionResultSeconds);
			this.settingsDirty = true;
		});
		this.settingsWidgets.add(this.resultBox);

		this.itemInput.addAll(this.fixed);
		this.itemInput.addAll(this.itemWidgets);
		this.settingsInput.addAll(this.fixed);
		this.settingsInput.addAll(this.settingsWidgets);

		refreshCells();
	}

	/** An invisible button: it gets the clicks, we draw how it looks. */
	private static Button hit(int x, int y, int w, int h, Runnable action) {
		Button button = Button.builder(Component.empty(), b -> action.run()).bounds(x, y, w, h).build();
		button.setAlpha(0f);
		return button;
	}

	/**
	 * A text box with no border of its own (we draw a flat one behind it). The box is placed so its text sits in the
	 * middle of the drawn rectangle at (x, y, w, h).
	 */
	private EditBox fieldBox(int x, int y, int w, int h, int maxLength, String name) {
		int textY = y + (h - 8) / 2;
		EditBox box = new EditBox(this.font, x + 4, textY, w - 8, y + h - textY, Component.literal(name));
		box.setBordered(false);
		box.setMaxLength(maxLength);
		return box;
	}

	private static int parseInt(String text, int min, int max, int fallback) {
		if (text == null || text.isEmpty()) {
			return min;
		}
		try {
			return Mth.clamp(Integer.parseInt(text), min, max);
		} catch (NumberFormatException e) {
			return fallback;
		}
	}

	// ---------------------------------------------------------------- input gating

	private boolean isBusy(long now) {
		return this.closing || this.openStart < 0L || now - this.openStart < OPEN_MS || now - this.tabStart < TAB_MS;
	}

	/** Only the open tab takes input, and nothing does while the window or a tab is sliding. */
	@Override
	public List<? extends GuiEventListener> children() {
		if (this.closing) {
			return List.of();
		}
		if (isBusy(System.currentTimeMillis())) {
			return this.fixed;
		}
		return this.tab == 0 ? this.itemInput : this.settingsInput;
	}

	private void setTab(int index) {
		if (index == this.tab) {
			return;
		}
		this.tabFrom = tabProgress(System.currentTimeMillis());
		this.tab = index;
		this.tabTo = index;
		this.tabStart = System.currentTimeMillis();
		this.setFocused(null);
		this.error = Component.empty();
	}

	private float tabProgress(long now) {
		float t = Mth.clamp((now - this.tabStart) / (float) TAB_MS, 0f, 1f);
		return this.tabFrom + (this.tabTo - this.tabFrom) * easeInOut(t);
	}

	private static float easeOut(float t) {
		float u = 1f - t;
		return 1f - u * u * u;
	}

	private static float easeIn(float t) {
		return t * t * t;
	}

	private static float easeInOut(float t) {
		return t < 0.5f ? 4f * t * t * t : 1f - (float) Math.pow(-2f * t + 2f, 3) / 2f;
	}

	// ---------------------------------------------------------------- the list

	private static Entry findByName(String name) {
		if (name == null || name.isEmpty()) {
			return null;
		}
		for (Entry entry : all()) {
			if (entry.name().equals(name)) {
				return entry;
			}
		}
		return null;
	}

	/** Rebuilds the visible list from the search text. */
	private void refilter() {
		this.filtered.clear();
		String[] words = this.query.toLowerCase(Locale.ROOT).trim().split("\\s+");
		for (Entry entry : all()) {
			boolean matches = true;
			for (String word : words) {
				if (!word.isEmpty() && !entry.search().contains(word)) {
					matches = false;
					break;
				}
			}
			if (matches) {
				this.filtered.add(entry);
			}
		}
		this.scrollRow = Mth.clamp(this.scrollRow, 0, maxScroll());
		refreshCells();
	}

	private int maxScroll() {
		int rows = (this.filtered.size() + COLS - 1) / COLS;
		return Math.max(0, rows - ROWS);
	}

	private void scrollBy(int rows) {
		this.scrollRow = Mth.clamp(this.scrollRow + rows, 0, maxScroll());
		refreshCells();
	}

	/** Only the squares that have an item behind them can be clicked. */
	private void refreshCells() {
		boolean running = AuctionHelper.isRunning();
		for (int i = 0; i < this.cells.length; i++) {
			Button cell = this.cells[i];
			if (cell != null) {
				boolean has = this.scrollRow * COLS + i < this.filtered.size();
				cell.visible = has;
				cell.active = has && !running;
			}
		}
	}

	private void pick(int slot) {
		int index = this.scrollRow * COLS + slot;
		if (index < this.filtered.size() && !AuctionHelper.isRunning()) {
			this.selected = this.filtered.get(index);
			this.selectedName = this.selected.name();
			this.error = Component.empty();
		}
	}

	/** Selects the item you're holding. A renamed item keeps its custom name. */
	private void useHeldItem() {
		if (this.minecraft == null || this.minecraft.player == null) {
			return;
		}
		ItemStack stack = this.minecraft.player.getMainHandItem();
		if (stack.isEmpty()) {
			this.error = Component.literal("Hold the item you're auctioning first");
			return;
		}
		this.selectedName = stack.getHoverName().getString();
		this.selected = null;
		for (Entry entry : all()) {
			if (entry.stack().getItem() == stack.getItem()) {
				this.selected = entry;
				break;
			}
		}
		this.error = Component.empty();
	}

	/** Mouse wheel scrolls the list, but only on the Item tab and not while sliding. */
	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		if (scrollY != 0 && this.tab == 0 && !isBusy(System.currentTimeMillis())) {
			scrollBy(scrollY > 0 ? -WHEEL_ROWS : WHEEL_ROWS);
			return true;
		}
		return false;
	}

	// ---------------------------------------------------------------- start, end, close

	private void startAuction() {
		String durationText = this.durationBox.getValue().trim();
		int seconds = durationText.isEmpty() ? 0 : Integer.parseInt(durationText);
		String item = this.selectedName.trim();
		if (item.isEmpty()) {
			this.error = Component.literal("Pick an item first");
			return;
		}
		if (seconds < 5 || seconds > 3600) {
			this.error = Component.literal("Length must be 5 to 3600 seconds");
			return;
		}

		String minText = this.minBox.getValue().trim();
		BigDecimal min = AmountParser.parse(minText);
		if (min == null || min.signum() <= 0) {
			this.error = Component.literal("Min bid must look like 100k, 1m or 2.5m");
			return;
		}

		// Remembered for next time.
		PayConfig config = PayConfig.get();
		config.auctionItem = item;
		config.auctionDurationSeconds = seconds;
		config.auctionMinBid = minText;
		PayConfig.save();
		this.settingsDirty = false;

		net.minecraft.client.Minecraft client = this.minecraft;
		beginClose(() -> AuctionHelper.start(client, item, seconds, min));
	}

	private void endAuction() {
		net.minecraft.client.Minecraft client = this.minecraft;
		beginClose(() -> AuctionHelper.end(client));
	}

	/** Slides the window down, then closes it and runs the action. */
	private void beginClose(Runnable after) {
		if (this.closing) {
			return;
		}
		this.closing = true;
		this.closeStart = System.currentTimeMillis();
		this.afterClose = after;
		this.setFocused(null);
	}

	@Override
	public void onClose() {
		beginClose(null);
	}

	@Override
	public void tick() {
		super.tick();
		if (this.closing && System.currentTimeMillis() - this.closeStart >= CLOSE_MS) {
			Runnable after = this.afterClose;
			this.afterClose = null;
			this.minecraft.setScreen(null);
			if (after != null) {
				after.run();
			}
		}
	}

	@Override
	public void removed() {
		if (this.settingsDirty) {
			PayConfig.save();
			this.settingsDirty = false;
		}
		super.removed();
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	// ---------------------------------------------------------------- drawing helpers

	/**
	 * A rounded rectangle. It is drawn at a third of a GUI pixel so the curve is smooth instead of stepped. The
	 * radius follows the size: big panels get big corners, small buttons and boxes get smaller ones.
	 */
	private static void box(GuiGraphics graphics, int x, int y, int w, int h, int color) {
		final int s = 3;
		int r = Mth.clamp(Math.round(Math.min(w, h) * 0.3f), 2, 8);
		r = Math.min(r, Math.min(w, h) / 2) * s;
		int x0 = x * s;
		int y0 = y * s;
		int w0 = w * s;
		int h0 = h * s;

		graphics.pose().pushMatrix();
		graphics.pose().scale(1f / s, 1f / s);
		for (int i = 0; i < r; i++) {
			double dy = r - i - 0.5;
			int inset = r - (int) Math.round(Math.sqrt((double) r * r - dy * dy));
			graphics.fill(x0 + inset, y0 + i, x0 + w0 - inset, y0 + i + 1, color);
			graphics.fill(x0 + inset, y0 + h0 - i - 1, x0 + w0 - inset, y0 + h0 - i, color);
		}
		graphics.fill(x0, y0 + r, x0 + w0, y0 + h0 - r, color);
		graphics.pose().popMatrix();
	}

	private static void field(GuiGraphics graphics, int x, int y, int w, int h, boolean focused) {
		box(graphics, x, y, w, h, focused ? C_ACCENT : C_FIELD_EDGE);
		box(graphics, x + 1, y + 1, w - 2, h - 2, C_FIELD);
	}

	private void button(GuiGraphics graphics, int x, int y, int w, int h, String label, int mx, int my, boolean enabled) {
		boolean hover = enabled && mx >= x && mx < x + w && my >= y && my < y + h;
		box(graphics, x, y, w, h, hover ? C_BTN_HOVER : C_BTN);
		graphics.drawCenteredString(this.font, label, x + w / 2, y + (h - 8) / 2 + 1, enabled ? C_TEXT : C_DIM);
	}

	private void toggle(GuiGraphics graphics, int x, int y, boolean on, int mx, int my) {
		boolean hover = mx >= x && mx < x + 44 && my >= y && my < y + 14;
		box(graphics, x, y, 44, 14, on ? (hover ? 0xFFFF6A4D : C_ACCENT) : (hover ? C_BTN_HOVER : C_BTN));
		graphics.drawCenteredString(this.font, on ? "On" : "Off", x + 22, y + 3, on ? 0xFFFFFFFF : C_DIM);
	}

	/** Which list entry the mouse is over, or -1. Coordinates are already in the tab's own space. */
	private int hoveredIndex(int mx, int my) {
		int gridX = px() + PAD;
		int gridY = py() + GRID_Y;
		if (mx < gridX || my < gridY || mx >= gridX + COLS * CELL || my >= gridY + ROWS * CELL) {
			return -1;
		}
		int index = this.scrollRow * COLS + (my - gridY) / CELL * COLS + (mx - gridX) / CELL;
		return index < this.filtered.size() ? index : -1;
	}

	/** Nothing here: render() draws its own dim, which fades in as the window slides up. */
	@Override
	public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
	}

	@Override
	public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
		long now = System.currentTimeMillis();
		if (this.openStart < 0L) {
			this.openStart = now;
		}

		// 0 = off the bottom of the screen, 1 = in place
		float shown;
		if (this.closing) {
			shown = 1f - easeIn(Mth.clamp((now - this.closeStart) / (float) CLOSE_MS, 0f, 1f));
		} else {
			shown = easeOut(Mth.clamp((now - this.openStart) / (float) OPEN_MS, 0f, 1f));
		}
		int px = px();
		int py = py();
		int slideY = Math.round((1f - shown) * (this.height - py + 4));
		int my = mouseY - slideY;

		graphics.fill(0, 0, this.width, this.height, ((int) (0x70 * shown)) << 24);

		graphics.pose().pushMatrix();
		graphics.pose().translate(0f, (float) slideY);

		// Window and title
		box(graphics, px - 1, py - 1, PW + 2, PH + 2, C_BORDER);
		box(graphics, px, py, PW, PH, C_PANEL);
		graphics.drawString(this.font, this.title, px + PAD, py + 10, C_TEXT);
		String hint = "Esc to close";
		graphics.drawString(this.font, hint, px + PW - PAD - this.font.width(hint), py + 10, C_DIM);

		// Tabs, with the orange bar sliding between them
		float p = tabProgress(now);
		graphics.drawCenteredString(this.font, "Item", px + PAD + TAB_W / 2, py + TAB_Y + 4, p < 0.5f ? C_TEXT : C_DIM);
		graphics.drawCenteredString(this.font, "Settings", px + PAD + TAB_W + TAB_W / 2, py + TAB_Y + 4, p < 0.5f ? C_DIM : C_TEXT);
		graphics.fill(px + PAD, py + BAR_Y + 1, px + PW - PAD, py + BAR_Y + 2, C_BORDER);
		int barX = px + PAD + Math.round(p * TAB_W);
		graphics.fill(barX, py + BAR_Y, barX + TAB_W, py + BAR_Y + 2, C_ACCENT);

		// Both tabs are the same size; the whole tab slides with the bar.
		graphics.enableScissor(px + 1, py + CONTENT_Y - 2, px + PW - 1, py + PH - 1);
		if (p < 0.999f) {
			renderItemTab(graphics, -Math.round(p * PW), mouseX, my, delta);
		}
		if (p > 0.001f) {
			renderSettingsTab(graphics, Math.round((1f - p) * PW), mouseX, my, delta);
		}
		graphics.disableScissor();

		graphics.pose().popMatrix();
	}

	private void renderItemTab(GuiGraphics graphics, int dx, int mouseX, int my, float delta) {
		int px = px();
		int py = py();
		int left = px + PAD;
		int mx = mouseX - dx;
		boolean running = AuctionHelper.isRunning();

		graphics.pose().pushMatrix();
		graphics.pose().translate((float) dx, 0f);

		// Search
		field(graphics, left, py + SEARCH_Y, LEFT_W, 18, this.searchBox.isFocused());
		this.searchBox.render(graphics, mx, my, delta);

		// Grid, straight on the window color
		int gridX = left;
		int gridY = py + GRID_Y;
		int hover = hoveredIndex(mx, my);
		for (int i = 0; i < COLS * ROWS; i++) {
			int index = this.scrollRow * COLS + i;
			if (index >= this.filtered.size()) {
				break;
			}
			Entry entry = this.filtered.get(index);
			int cx = gridX + (i % COLS) * CELL;
			int cy = gridY + (i / COLS) * CELL;
			if (index == hover && !running) {
				box(graphics, cx, cy, CELL, CELL, 0xFF2C2C2C);
			}
			if (entry == this.selected) {
				box(graphics, cx, cy, CELL, CELL, C_ACCENT);
				box(graphics, cx + 1, cy + 1, CELL - 2, CELL - 2, C_PANEL);
			}
			graphics.renderItem(entry.stack(), cx + 1, cy + 1);
		}
		if (this.filtered.isEmpty()) {
			graphics.drawCenteredString(this.font, "No items match", gridX + COLS * CELL / 2, gridY + ROWS * CELL / 2 - 4, C_DIM);
		}

		// Scroll bar
		int trackX = gridX + COLS * CELL + 2;
		int trackH = ROWS * CELL;
		box(graphics, trackX, gridY, 4, trackH, 0xFF2A2A2A);
		int max = maxScroll();
		if (max > 0) {
			int thumbH = Math.max(12, trackH * ROWS / (max + ROWS));
			int thumbY = gridY + (trackH - thumbH) * this.scrollRow / max;
			box(graphics, trackX, thumbY, 4, thumbH, 0xFF777777);
		}

		// Hint (or the name of what the mouse is on) and the held item button
		button(graphics, left, py + HELD_Y, LEFT_W, 16, "Use held item", mx, my, !running);

		// Preview frame, down to the bottom of the window
		int fx = px + FRAME_X;
		box(graphics, fx, py + CONTENT_Y, FRAME_W, CONTENT_H, C_FRAME);
		graphics.drawString(this.font, "PREVIEW", fx + 8, py + CONTENT_Y + 8, C_DIM);

		box(graphics, fx + 8, py + CONTENT_Y + 20, 24, 24, 0xFF2E2E2E);
		if (this.selected != null) {
			graphics.renderItem(this.selected.stack(), fx + 12, py + CONTENT_Y + 24);
		}
		boolean none = this.selectedName.isEmpty();
		graphics.drawString(this.font,
			this.font.plainSubstrByWidth(none ? "No item yet" : this.selectedName, FRAME_W - 48),
			fx + 40, py + CONTENT_Y + 28, none ? C_DIM : C_GOLD);

		int boxX = fx + FRAME_W - 8 - FIELD_W;
		graphics.drawString(this.font, "Min bid", fx + 8, py + MIN_Y + 4, C_DIM);
		field(graphics, boxX, py + MIN_Y, FIELD_W, 16, this.minBox.isFocused());
		this.minBox.render(graphics, mx, my, delta);
		graphics.drawString(this.font, "Duration (sec)", fx + 8, py + DUR_Y + 4, C_DIM);
		field(graphics, boxX, py + DUR_Y, FIELD_W, 16, this.durationBox.isFocused());
		this.durationBox.render(graphics, mx, my, delta);

		// A problem in red, or how the running auction is going
		int textW = FRAME_W - 16;
		if (!this.error.getString().isEmpty()) {
			drawWrapped(graphics, this.error, fx + 8, py + MSG_Y, textW, C_RED);
		} else if (running) {
			drawWrapped(graphics, Component.literal("Running: " + AuctionHelper.statusLine()), fx + 8, py + MSG_Y, textW, C_GOLD);
		}

		button(graphics, fx + 8, py + START_Y, FRAME_W - 16, 20, running ? "End Auction" : "Start Auction", mx, my, true);

		graphics.pose().popMatrix();
	}

	private void drawWrapped(GuiGraphics graphics, Component text, int x, int y, int width, int color) {
		int lineY = y;
		for (FormattedCharSequence line : this.font.split(text, width)) {
			graphics.drawString(this.font, line, x, lineY, color);
			lineY += 10;
		}
	}

	private void renderSettingsTab(GuiGraphics graphics, int dx, int mouseX, int my, float delta) {
		int px = px();
		int py = py();
		int left = px + PAD;
		int mx = mouseX - dx;
		PayConfig config = PayConfig.get();

		graphics.pose().pushMatrix();
		graphics.pose().translate((float) dx, 0f);

		box(graphics, left, py + CONTENT_Y, SET_W, CONTENT_H, C_FRAME);

		int rightX = left + SET_W - 8;
		String[] labels = {
			"Show overlay on screen", "Overlay position (X, Y)", "Show under minimum bids",
			"Chat updates for top bids", "Sound on new bid", "Warn in the last (seconds)", "Keep result on screen (seconds)"
		};
		for (int i = 0; i < labels.length; i++) {
			int y = py + ROW_Y + i * ROW_H;
			graphics.drawString(this.font, labels[i], left + 8, y + 7, C_TEXT);
			if (i < labels.length - 1) {
				graphics.fill(left + 8, y + ROW_H - 1, left + SET_W - 8, y + ROW_H, C_LINE);
			}
		}

		int y0 = py + ROW_Y;
		toggle(graphics, rightX - 44, y0 + 3, config.auctionHudEnabled, mx, my);
		toggle(graphics, rightX - 44, y0 + ROW_H * 2 + 3, config.auctionShowUnderMin, mx, my);
		toggle(graphics, rightX - 44, y0 + ROW_H * 3 + 3, config.auctionChatUpdates, mx, my);
		toggle(graphics, rightX - 44, y0 + ROW_H * 4 + 3, config.auctionSound, mx, my);

		EditBox[] boxes = {this.hudXBox, this.hudYBox, this.warnBox, this.resultBox};
		int[] rows = {1, 1, 5, 6};
		int[] xs = {rightX - 84, rightX - 40, rightX - 40, rightX - 40};
		for (int i = 0; i < boxes.length; i++) {
			field(graphics, xs[i], y0 + rows[i] * ROW_H + 3, 40, 14, boxes[i].isFocused());
			boxes[i].render(graphics, mx, my, delta);
		}

		graphics.pose().popMatrix();
	}
}
