package elite.mod.client;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.mojang.blaze3d.platform.InputConstants;

import elite.mod.DonutPlus;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;

/**
 * Auction helper. The keybind opens the auction menu ({@link AuctionScreen}), where you pick the item, the
 * length and the minimum bid, and start or end the auction. Everyone bids by paying you. While it runs,
 * every payment you receive that is at or above the minimum bid counts, a player's payments add up, and
 * whoever has paid the most is the top bidder. The auction ends exactly when the timer hits zero (or when
 * you end it from the menu), and the winner is shown on screen and in chat.
 *
 * This only reads what you receive and shows it to you. It never sends a chat message or a payment, and
 * nothing is refunded for you: paying back the losing bidders is up to you.
 */
public final class AuctionHelper {
	private static final int PADDING = 3;
	private static final int BAR = 2;

	private static final int GREEN = 0xFF55FF55;
	private static final int RED = 0xFFFF5555;
	private static final int GOLD = 0xFFFFAA00;
	private static final int GRAY = 0xFFAAAAAA;
	private static final int WHITE = 0xFFFFFFFF;

	private static final String AMT = "[0-9][0-9,]*(?:\\.[0-9]+)?[kmbt]?";
	private static final String PLAYER = "[A-Za-z0-9_.]{1,20}";

	/**
	 * The lines DonutSMP shows when someone pays you. The whole line has to match, so player chat (which
	 * always has the sender's name in front) can't fake a bid.
	 */
	private static final Pattern[] BID_PATTERNS = {
		Pattern.compile("(?i)^(?<player>" + PLAYER + ") paid you \\$ ?(?<amount>" + AMT + ")\\.?$"),
		Pattern.compile("(?i)^You were paid \\$ ?(?<amount>" + AMT + ") by (?<player>" + PLAYER + ")\\.?$")
	};

	private record Bidder(String name, BigDecimal total) {}

	/** Everyone who has bid, keyed by lowercase name so "Steve" and "steve" are one bidder. */
	private static final Map<String, Bidder> bidders = new LinkedHashMap<>();
	private static boolean running = false;
	private static long endsAt = 0L;
	/** When the last auction ended (epoch millis), or 0 if there's no result to show. */
	private static long finishedAt = 0L;
	private static BigDecimal minBid = BigDecimal.ZERO;
	/** What's being auctioned, as typed in the menu. Can be empty. */
	private static String item = "";
	/** Key of the current top bidder, or null if nobody has bid yet. */
	private static String topKey = null;
	private static int bidCount = 0;
	/** The last payment that was under the minimum bid, for the overlay. Null if none. */
	private static String lastLow = null;

	private static KeyMapping auctionKey;

	private AuctionHelper() {}

	public static void register() {
		// Shows up under Options > Controls > Key Binds. Unbound until you pick a key.
		auctionKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
			"key.donutplus.auction",
			InputConstants.Type.KEYSYM,
			-1, // GLFW_KEY_UNKNOWN: not bound by default
			ModKeys.MAIN));

		// Never hides anything: it only reads. The action bar copy of a payment is skipped so a bid isn't counted twice.
		ClientReceiveMessageEvents.ALLOW_GAME.register((message, overlay) -> {
			if (!overlay) {
				onMessage(message.getString());
			}
			return true;
		});

		ClientTickEvents.END_CLIENT_TICK.register(AuctionHelper::tick);
		HudElementRegistry.addLast(DonutPlus.id("auction"), (graphics, delta) -> render(graphics));
	}

	// ---------------------------------------------------------------- what the menu uses

	public static boolean isRunning() {
		return running;
	}

	/** What's being auctioned right now (or in the last auction). Empty if no item was set. */
	public static String item() {
		return item;
	}

	/** One line about the running auction for the menu: time left and who's winning. */
	public static String statusLine() {
		if (!running) {
			return "No auction is running.";
		}
		long seconds = Math.max(0L, (endsAt - System.currentTimeMillis() + 999L) / 1000L);
		Bidder top = topKey == null ? null : bidders.get(topKey);
		return clock(seconds) + " left - "
			+ (top == null ? "no bids yet" : top.name() + " $" + AmountParser.formatShort(top.total()));
	}

	/** Starts an auction. The numbers have already been checked by the menu. */
	public static void start(Minecraft client, String itemName, int seconds, BigDecimal minimumBid) {
		item = itemName == null ? "" : itemName.trim();
		minBid = minimumBid;

		bidders.clear();
		topKey = null;
		bidCount = 0;
		lastLow = null;
		finishedAt = 0L;
		running = true;
		endsAt = System.currentTimeMillis() + seconds * 1000L;

		say(client, "Auction started" + (item.isEmpty() ? "" : " for " + item) + ": " + clock(seconds)
			+ ", minimum bid $" + AmountParser.format(minBid) + ".", ChatFormatting.GOLD);
	}

	/** Ends the running auction right now. Does nothing if none is running. */
	public static void end(Minecraft client) {
		if (running) {
			finish(client, true);
		}
	}

	// ---------------------------------------------------------------- running the auction

	private static void tick(Minecraft client) {
		if (client.player == null || client.getConnection() == null) {
			// Left the world: drop the auction so nothing carries over to another server.
			running = false;
			finishedAt = 0L;
			bidders.clear();
			topKey = null;
			while (auctionKey.consumeClick()) {
				// discard clicks made while not in a world
			}
			return;
		}

		boolean open = false;
		while (auctionKey.consumeClick()) {
			open = true;
		}
		if (open) {
			client.setScreen(new AuctionScreen());
		}

		if (running && System.currentTimeMillis() >= endsAt) {
			finish(client, false);
		}
	}

	private static void finish(Minecraft client, boolean early) {
		running = false;
		finishedAt = System.currentTimeMillis();

		String name = "Auction" + (item.isEmpty() ? "" : " for " + item);
		Bidder top = topKey == null ? null : bidders.get(topKey);
		if (top == null) {
			say(client, name + " ended" + (early ? " early" : "") + ". No bids.", ChatFormatting.GRAY);
			return;
		}

		say(client, name + " ended" + (early ? " early" : "") + ". Winner: " + top.name() + " with $"
			+ AmountParser.format(top.total()) + " (" + bidCount + (bidCount == 1 ? " bid" : " bids") + ").",
			ChatFormatting.GREEN);

		// Next best bids, so you know who to pay back.
		List<Bidder> others = new ArrayList<>();
		for (Map.Entry<String, Bidder> entry : bidders.entrySet()) {
			if (!entry.getKey().equals(topKey)) {
				others.add(entry.getValue());
			}
		}
		others.sort(Comparator.comparing(Bidder::total).reversed());
		int shown = 0;
		for (Bidder other : others) {
			if (shown++ == 2) {
				say(client, "...and " + (others.size() - 2) + " more", ChatFormatting.GRAY);
				break;
			}
			say(client, other.name() + ": $" + AmountParser.format(other.total()), ChatFormatting.GRAY);
		}
	}

	// ---------------------------------------------------------------- reading bids

	private static void onMessage(String text) {
		if (!running || text == null) {
			return;
		}

		String line = text.trim();
		for (Pattern pattern : BID_PATTERNS) {
			Matcher m = pattern.matcher(line);
			if (m.matches()) {
				addBid(m.group("player"), AmountParser.parse(m.group("amount")));
				return;
			}
		}
	}

	private static void addBid(String player, BigDecimal amount) {
		if (player == null || amount == null) {
			return;
		}
		PayConfig config = PayConfig.get();

		// A payment under the minimum bid doesn't count at all, not even toward a player's total.
		// It can still be listed in the overlay if that's switched on.
		if (amount.compareTo(minBid) < 0) {
			if (config.auctionShowUnderMin) {
				lastLow = player + " $" + AmountParser.formatShort(amount) + " - too low";
			}
			return;
		}

		String key = player.toLowerCase(Locale.ROOT);
		Bidder before = bidders.get(key);
		Bidder after = new Bidder(before == null ? player : before.name(), before == null ? amount : before.total().add(amount));
		bidders.put(key, after);
		bidCount++;
		if (config.auctionSound) {
			Minecraft.getInstance().getSoundManager()
				.play(SimpleSoundInstance.forUI(SoundEvents.EXPERIENCE_ORB_PICKUP, 1.0F));
		}

		// The top bidder only changes when someone passes them, so a tie goes to whoever got there first.
		Bidder top = topKey == null ? null : bidders.get(topKey);
		if (top == null || key.equals(topKey) || after.total().compareTo(top.total()) > 0) {
			topKey = key;
			if (config.auctionChatUpdates) {
				Minecraft client = Minecraft.getInstance();
				say(client, "Top bid: " + after.name() + " $" + AmountParser.format(after.total()), ChatFormatting.GOLD);
			}
		}
	}

	// ---------------------------------------------------------------- HUD

	private static void render(GuiGraphics graphics) {
		PayConfig config = PayConfig.get();
		Minecraft client = Minecraft.getInstance();
		if (!config.auctionHudEnabled || client.player == null || client.options.hideGui) {
			return;
		}

		long now = System.currentTimeMillis();
		if (!running && (finishedAt == 0L || now - finishedAt > config.auctionResultSeconds * 1000L)) {
			return;
		}

		Bidder top = topKey == null ? null : bidders.get(topKey);
		String title;
		int titleColor;
		int barColor;
		if (running) {
			long seconds = Math.max(0L, (endsAt - now + 999L) / 1000L);
			title = "Auction " + clock(seconds);
			titleColor = seconds <= config.auctionWarnSeconds ? RED : GOLD;
			barColor = titleColor;
		} else {
			title = "Auction ended";
			titleColor = GRAY;
			barColor = top == null ? GRAY : GREEN;
		}

		String topLine;
		int topColor;
		if (top == null) {
			topLine = "No bids yet";
			topColor = GRAY;
		} else {
			topLine = (running ? "" : "Won by ") + top.name() + " $" + AmountParser.formatShort(top.total());
			topColor = GREEN;
		}
		String info = "Min $" + AmountParser.formatShort(minBid) + " - " + bidCount + (bidCount == 1 ? " bid" : " bids");

		List<String> texts = new ArrayList<>();
		List<Integer> colors = new ArrayList<>();
		texts.add(title);
		colors.add(titleColor);
		if (!item.isEmpty()) {
			texts.add(item);
			colors.add(WHITE);
		}
		texts.add(topLine);
		colors.add(topColor);
		texts.add(info);
		colors.add(GRAY);
		if (config.auctionShowUnderMin && lastLow != null) {
			texts.add(lastLow);
			colors.add(RED);
		}

		Font font = client.font;
		int lineHeight = font.lineHeight + 2;
		int textWidth = 0;
		for (String text : texts) {
			textWidth = Math.max(textWidth, font.width(text));
		}
		int boxWidth = BAR + textWidth + PADDING * 2;
		int boxHeight = lineHeight * texts.size() + PADDING * 2 - 2;

		int x = Math.max(0, Math.min(config.auctionHudX, graphics.guiWidth() - boxWidth));
		int y = Math.max(0, Math.min(config.auctionHudY, graphics.guiHeight() - boxHeight));

		graphics.fill(x, y, x + boxWidth, y + boxHeight, 0x80000000);
		graphics.fill(x, y, x + BAR, y + boxHeight, barColor);

		int textX = x + BAR + PADDING;
		int textY = y + PADDING;
		for (int i = 0; i < texts.size(); i++) {
			graphics.drawString(font, Component.literal(texts.get(i)), textX, textY + lineHeight * i, colors.get(i));
		}
	}

	// ---------------------------------------------------------------- helpers

	/** 75 -> "1:15" */
	private static String clock(long totalSeconds) {
		return String.format(Locale.ROOT, "%d:%02d", totalSeconds / 60, totalSeconds % 60);
	}

	private static void say(Minecraft client, String text, ChatFormatting color) {
		if (client.player != null) {
			client.player.displayClientMessage(Component.literal(text).withStyle(color), false);
		}
	}
}
