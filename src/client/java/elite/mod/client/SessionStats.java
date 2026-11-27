package elite.mod.client;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import elite.mod.DonutPlus;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/**
 * Session earnings: how much you've been paid, how much you've paid back, and what's left over.
 *
 * Saved to config/donutplus-session.json every time it changes, so a crash or restart doesn't lose
 * it. It keeps counting across game sessions until you clear it in the settings screen.
 */
public final class SessionStats {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final int PADDING = 3;

	private static final int GREEN = 0xFF55FF55;
	private static final int RED = 0xFFFF5555;
	private static final int GRAY = 0xFFAAAAAA;
	private static final int WHITE = 0xFFFFFFFF;
	/** The percent next to the amount: the same colors, a little dimmer. */
	private static final int GREEN_DIM = 0xB355FF55;
	private static final int RED_DIM = 0xB3FF5555;
	/** Width of the colored bar on the box's left edge. */
	private static final int BAR = 2;

	/** What's written to the file. Amounts are text so no precision is lost. */
	private static final class Data {
		String received = "0";
		String paidBack = "0";
		String startBalance = null;
		long since = 0L;
	}

	private static BigDecimal received = BigDecimal.ZERO;
	private static BigDecimal paidBack = BigDecimal.ZERO;
	/** When the first amount was recorded after the last clear (epoch millis), or 0 if nothing yet. */
	private static long since = 0L;
	/** Your balance when the session started, or null if it isn't known yet. */
	private static BigDecimal startBalance = null;

	/** An amount as DonutSMP writes it: 1,500 / 250.50 / 2.5K / 25M / 1.5B / 2T. */
	private static final String AMT = "[0-9][0-9,]*(?:\\.[0-9]+)?[kmbt]?";
	/** A player name, including Bedrock names that start with a dot. */
	private static final String PLAYER = "[A-Za-z0-9_.]{1,20}";

	/*
	 * Every pattern has to match the WHOLE line. Player chat always arrives with the sender's name in
	 * front, so someone typing "Steve paid you $1M" in public chat can't fake a payment.
	 * Wording as DonutSMP prints it (note the space after the $):
	 *   Steve paid you $ 7.4M
	 *   You were paid $ 4.8M by Steve
	 *   You were paid $ 4.8M while you were away      (summary when you join)
	 *   You paid Steve $ 6M                            (a tax can follow: "(+$ 120K tax)")
	 *   You have $ 313,162,724                         (the reply to /bal)
	 */
	private static final Pattern[] RECEIVED_PATTERNS = {
		Pattern.compile("(?i)^" + PLAYER + " paid you \\$ ?(?<amount>" + AMT + ")\\.?$"),
		Pattern.compile("(?i)^You were paid \\$ ?(?<amount>" + AMT + ") by " + PLAYER + "\\.?$"),
		Pattern.compile("(?i)^You were paid \\$ ?(?<amount>" + AMT + ") while you were away\\.?$")
	};

	/** A payment you sent. The optional (...) part holds the tax, which also comes out of your balance. */
	private static final Pattern SENT_PATTERN = Pattern.compile(
		"(?i)^You paid " + PLAYER + " \\$ ?(?<amount>" + AMT + ")(?: \\((?<extra>[^)]*)\\))?\\.?$");

	/** Finds the tax amount inside the (...) part of a sent-payment line. */
	private static final Pattern TAX_PATTERN = Pattern.compile("(?i)\\+ ?\\$ ?(" + AMT + ")");

	/** The reply to /bal. Only your own balance matches ("You have ..."), not other players'. */
	private static final Pattern BALANCE_PATTERN = Pattern.compile(
		"(?i)^You have \\$ ?(?<amount>" + AMT + ")\\.?$");

	private SessionStats() {}

	public static void register() {
		load();
		// Reads every payment you receive or send, plus the reply to /bal. Always on, not just while /payback is waiting.
		ClientReceiveMessageEvents.ALLOW_GAME.register((message, overlay) -> {
			if (!overlay) {
				onGameMessage(message.getString());
			}
			return true;
		});
		HudElementRegistry.addLast(DonutPlus.id("session_stats"), (graphics, delta) -> render(graphics));
	}

	// ---------------------------------------------------------------- numbers

	/** Money that came in: someone paid you. */
	public static void recordReceived(BigDecimal amount) {
		if (amount == null || amount.signum() <= 0) {
			return;
		}
		received = received.add(amount);
		touch();
		save();
	}

	/** Money that went out: any /pay you sent, tax included (not only paybacks). */
	public static void recordPaidBack(BigDecimal amount) {
		if (amount == null || amount.signum() <= 0) {
			return;
		}
		paidBack = paidBack.add(amount);
		touch();
		save();
	}

	public static void clear() {
		received = BigDecimal.ZERO;
		paidBack = BigDecimal.ZERO;
		since = 0L;
		startBalance = null;
		save();
	}

	public static BigDecimal received() {
		return received;
	}

	public static BigDecimal paidBack() {
		return paidBack;
	}

	/** What you've made: received minus paid back. Negative if you've paid out more than you took in. */
	public static BigDecimal net() {
		return received.subtract(paidBack);
	}

	public static long since() {
		return since;
	}

	/** Your balance when the session started, or null if unknown. */
	public static BigDecimal startBalance() {
		return startBalance;
	}

	/** Sets the starting balance by hand. Null means unknown (it will be picked up from the next balance message). */
	public static void setStartBalance(BigDecimal balance) {
		startBalance = balance != null && balance.signum() >= 0 ? balance : null;
		touch();
		save();
	}

	private static void onGameMessage(String text) {
		if (text == null) {
			return;
		}
		String line = text.trim();

		for (Pattern pattern : RECEIVED_PATTERNS) {
			Matcher m = pattern.matcher(line);
			if (m.matches()) {
				recordReceived(AmountParser.parse(m.group("amount")));
				return;
			}
		}

		Matcher sent = SENT_PATTERN.matcher(line);
		if (sent.matches()) {
			BigDecimal total = AmountParser.parse(sent.group("amount"));
			if (total != null) {
				String extra = sent.group("extra");
				if (extra != null) {
					Matcher tax = TAX_PATTERN.matcher(extra);
					if (tax.find()) {
						BigDecimal taxAmount = AmountParser.parse(tax.group(1));
						if (taxAmount != null) {
							total = total.add(taxAmount);
						}
					}
				}
				recordPaidBack(total);
			}
			return;
		}

		// The reply to /bal fills in the starting balance if it isn't known yet. If you've already made or
		// lost money this session, work back to what you had before it, so the percent stays right.
		if (startBalance == null) {
			Matcher bal = BALANCE_PATTERN.matcher(line);
			if (bal.matches()) {
				BigDecimal now = AmountParser.parse(bal.group("amount"));
				if (now != null) {
					BigDecimal start = now.subtract(net());
					if (start.signum() >= 0) {
						setStartBalance(start);
					}
				}
			}
		}
	}

	private static void touch() {
		if (since == 0L) {
			since = System.currentTimeMillis();
		}
	}

	// ---------------------------------------------------------------- saving

	private static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve("donutplus-session.json");
	}

	private static void load() {
		Path file = file();
		if (!Files.exists(file)) {
			return;
		}
		try (Reader reader = Files.newBufferedReader(file)) {
			Data data = GSON.fromJson(reader, Data.class);
			if (data != null) {
				received = parse(data.received);
				paidBack = parse(data.paidBack);
				since = Math.max(0L, data.since);
				startBalance = data.startBalance == null ? null : parse(data.startBalance);
			}
		} catch (Exception e) {
			DonutPlus.LOGGER.warn("Couldn't read {}, starting the session from zero", file, e);
		}
	}

	private static BigDecimal parse(String text) {
		try {
			BigDecimal value = new BigDecimal(text == null ? "0" : text.trim());
			return value.signum() < 0 ? BigDecimal.ZERO : value;
		} catch (NumberFormatException e) {
			return BigDecimal.ZERO;
		}
	}

	/** Writes to a temporary file first and then swaps it in, so a crash mid-save can't corrupt the stats. */
	private static void save() {
		Path file = file();
		Path temp = file.resolveSibling(file.getFileName() + ".tmp");

		Data data = new Data();
		data.received = received.toPlainString();
		data.paidBack = paidBack.toPlainString();
		data.since = since;
		data.startBalance = startBalance == null ? null : startBalance.toPlainString();

		try {
			try (Writer writer = Files.newBufferedWriter(temp)) {
				GSON.toJson(data, writer);
			}
			try {
				Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			} catch (AtomicMoveNotSupportedException e) {
				Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
			}
		} catch (IOException e) {
			DonutPlus.LOGGER.warn("Couldn't save {}", file, e);
		}
	}

	// ---------------------------------------------------------------- HUD

	private static void render(GuiGraphics graphics) {
		PayConfig config = PayConfig.get();
		Minecraft client = Minecraft.getInstance();
		if (!config.statsHudEnabled || client.player == null || client.options.hideGui) {
			return;
		}

		Font font = client.font;
		BigDecimal net = net();
		int color = net.signum() < 0 ? RED : GREEN;
		int dimColor = net.signum() < 0 ? RED_DIM : GREEN_DIM;
		String amount = (net.signum() < 0 ? "-" : "+") + AmountParser.formatShort(net);
		// How far you're up or down compared to where you started. Only when the starting balance is known.
		String percent = null;
		if (startBalance != null && startBalance.signum() > 0) {
			BigDecimal pct = net.abs().multiply(BigDecimal.valueOf(100)).divide(startBalance, 2, RoundingMode.DOWN);
			percent = "(" + (net.signum() < 0 ? "-" : "+") + pct.toPlainString() + "%)";
		}
		String from = "From ";
		String balance = startBalance == null ? "?" : AmountParser.formatShort(startBalance);
		// Until the session has started (no starting balance yet), tell the player how to start it.
		String hint = startBalance == null ? "do /bal to start session earnings" : null;

		int gap = font.width(" ");
		int line1 = font.width(amount) + (percent == null ? 0 : gap + font.width(percent));
		int line2 = font.width(from) + font.width(balance);
		int line3 = hint == null ? 0 : font.width(hint);
		int lineHeight = font.lineHeight + 2;
		int lines = hint == null ? 2 : 3;
		int boxWidth = BAR + Math.max(Math.max(line1, line2), line3) + PADDING * 2;
		int boxHeight = lineHeight * lines + PADDING * 2 - 2;

		int x = Math.max(0, Math.min(config.statsHudX, graphics.guiWidth() - boxWidth));
		int y = Math.max(0, Math.min(config.statsHudY, graphics.guiHeight() - boxHeight));

		graphics.fill(x, y, x + boxWidth, y + boxHeight, 0x80000000);
		graphics.fill(x, y, x + BAR, y + boxHeight, color);

		int textX = x + BAR + PADDING;
		int textY = y + PADDING;
		graphics.drawString(font, Component.literal(amount), textX, textY, color);
		if (percent != null) {
			graphics.drawString(font, Component.literal(percent), textX + font.width(amount) + gap, textY, dimColor);
		}
		graphics.drawString(font, Component.literal(from), textX, textY + lineHeight, GRAY);
		graphics.drawString(font, Component.literal(balance), textX + font.width(from), textY + lineHeight, WHITE);
		if (hint != null) {
			graphics.drawString(font, Component.literal(hint), textX, textY + lineHeight * 2, GRAY);
		}
	}
}
