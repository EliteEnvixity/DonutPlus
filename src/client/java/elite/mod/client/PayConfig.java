package elite.mod.client;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import elite.mod.DonutPlus;
import net.fabricmc.loader.api.FabricLoader;

/** Settings, saved to config/donutplus.json and editable through Mod Menu. */
public final class PayConfig {
	private static final BigDecimal DEFAULT_THRESHOLD = new BigDecimal("100000000");
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
	/**
	 * Matches the whole line DonutSMP shows when someone pays you, e.g. "Steve paid you $5M".
	 * Needs the named groups (?<player>...) and (?<amount>...). Because the whole line must match,
	 * player chat (which always has a name in front) can't fake a payment.
	 */
	public static final String DEFAULT_PAYBACK_PATTERN =
		"(?i)^(?<player>[A-Za-z0-9_.]{1,20}) paid you \\$ ?(?<amount>[0-9][0-9,]*(?:\\.[0-9]+)?[kmbt]?)\\.?$";

	private static PayConfig instance;

	public static final String DEFAULT_PAYBACK_COMMAND = "payback";

	/** Turn the whole safety check on or off. */
	public boolean enabled = true;
	/** Payments at or above this need confirmation. Accepts 100m, 2.5b, 50000000... */
	public String threshold = "100m";
	/** How long a confirmation stays open. 0 = never expires. */
	public int expirySeconds = 15;

	/** Put the time in front of every chat line. */
	public boolean timestampsEnabled = true;
	/** A java.time pattern: "HH:mm", "HH:mm:ss", "hh:mm a", "dd/MM HH:mm"... */
	public String timestampFormat = ChatTimestamp.DEFAULT_FORMAT;
	/** Hex color of the timestamp, with or without a leading #. */
	public String timestampColor = ChatTimestamp.DEFAULT_COLOR;
	/** What surrounds the time: "[]", "()", "<>" or "none". */
	public String timestampBrackets = ChatTimestamp.DEFAULT_BRACKETS;
	/** Hovering the timestamp shows the full date and time with seconds. */
	public boolean timestampHoverDate = true;

	// Payment payback. Waiting for payments is switched on and off with /payback.
	/** Players get back what they paid you times this. */
	public double paybackMultiplier = 2.0;
	/** Only payments from the last this-many seconds count. 0 = everything collected while waiting (the default). */
	public int paybackWindowSeconds = 0;
	/** Show the list and ask for a click before sending anything. Strongly recommended. */
	public boolean paybackConfirm = true;
	/** Ticks to wait between payments (20 = one second), so the server doesn't treat it as spam. */
	public int paybackDelayTicks = 20;
	/** Most one player can get back in one go. Accepts 100m, 2.5b... 0 = no limit. */
	public String paybackMaxPayout = "0";
	/** How an incoming payment line is read. See DEFAULT_PAYBACK_PATTERN. */
	public String paybackPattern = DEFAULT_PAYBACK_PATTERN;
	/** The command that triggers payback, without the slash. Takes effect after restarting the game. */
	public String paybackCommand = DEFAULT_PAYBACK_COMMAND;
	/** Players who paid you less than this in total (their payments add up) are never paid back. Accepts 100k, 2.5m... 0 = no minimum. */
	public String paybackMinimum = "0";
	/** While waiting, hide "X paid you $Y" lines from chat until that player's payments reach the minimum. */
	public boolean paybackHideUnderMinimum = false;

	/** Show the session earnings box on screen. */
	public boolean statsHudEnabled = true;
	/** Where the box sits, in pixels from the top-left of the screen. */
	public int statsHudX = 4;
	public int statsHudY = 4;

	/** How long an auction runs, in seconds. */
	public int auctionDurationSeconds = 60;
	/** Payments below this don't count as bids. Accepts 100k, 1m, 2.5m... */
	public String auctionMinBid = "1m";
	/** The item typed in the auction menu last time. Can be empty. */
	public String auctionItem = "";
	/** Show the auction box on screen. */
	public boolean auctionHudEnabled = true;
	/** Where the auction box sits, in pixels from the top-left of the screen. */
	public int auctionHudX = 4;
	public int auctionHudY = 44;
	/** List payments below the minimum bid in the overlay. They still don't count. */
	public boolean auctionShowUnderMin = false;
	/** Print "Top bid: ..." in your chat (only you see it) when someone takes the lead. */
	public boolean auctionChatUpdates = true;
	/** Play a sound when a valid bid comes in. */
	public boolean auctionSound = true;
	/** The overlay timer turns red in the last this-many seconds. 0 = never. */
	public int auctionWarnSeconds = 10;
	/** How long the result stays on screen after an auction ends. */
	public int auctionResultSeconds = 30;

	public static PayConfig get() {
		if (instance == null) {
			load();
		}
		return instance;
	}

	public static void load() {
		migrateOldConfig();
		PayConfig loaded = null;
		Path file = file();
		if (Files.exists(file)) {
			try (Reader reader = Files.newBufferedReader(file)) {
				loaded = GSON.fromJson(reader, PayConfig.class);
			} catch (Exception e) {
				DonutPlus.LOGGER.warn("Couldn't read {}, using defaults", file, e);
			}
		}
		instance = loaded != null ? loaded : new PayConfig();
		instance.sanitize();
		// Also saves when an older config file is missing the timestamp options, so they show up in the file.
		save();
	}

	public static void save() {
		Path file = file();
		try (Writer writer = Files.newBufferedWriter(file)) {
			GSON.toJson(get(), writer);
		} catch (IOException e) {
			DonutPlus.LOGGER.warn("Couldn't save {}", file, e);
		}
	}

	public BigDecimal thresholdValue() {
		BigDecimal value = AmountParser.parse(threshold);
		return value != null ? value : DEFAULT_THRESHOLD;
	}

	/** Repairs hand-edited values so a typo in the file can't break chat. */
	private void sanitize() {
		if (threshold == null) {
			threshold = "100m";
		}
		if (expirySeconds < 0) {
			expirySeconds = 0;
		}
		if (!ChatTimestamp.isValidPattern(timestampFormat)) {
			timestampFormat = ChatTimestamp.DEFAULT_FORMAT;
		}
		if (ChatTimestamp.parseColor(timestampColor) < 0) {
			timestampColor = ChatTimestamp.DEFAULT_COLOR;
		}
		if (timestampBrackets == null || !ChatTimestamp.isBracketStyle(timestampBrackets)) {
			timestampBrackets = ChatTimestamp.DEFAULT_BRACKETS;
		}
		if (!(paybackMultiplier > 0) || paybackMultiplier > 100) {
			paybackMultiplier = 2.0;
		}
		if (paybackWindowSeconds < 0) {
			paybackWindowSeconds = 0;
		}
		if (paybackDelayTicks < 1) {
			paybackDelayTicks = 1;
		}
		if (paybackMaxPayout == null) {
			paybackMaxPayout = "0";
		}
		if (!isValidPaybackPattern(paybackPattern)) {
			paybackPattern = DEFAULT_PAYBACK_PATTERN;
		}
		paybackCommand = paybackCommand == null ? "" : paybackCommand.trim().toLowerCase(Locale.ROOT);
		if (paybackCommand.startsWith("/")) {
			paybackCommand = paybackCommand.substring(1);
		}
		if (!isValidPaybackCommand(paybackCommand)) {
			paybackCommand = DEFAULT_PAYBACK_COMMAND;
		}
		if (paybackMinimum == null || AmountParser.parse(paybackMinimum) == null) {
			paybackMinimum = "0";
		}
		statsHudX = Math.max(0, Math.min(statsHudX, 9999));
		statsHudY = Math.max(0, Math.min(statsHudY, 9999));
		if (auctionDurationSeconds < 5 || auctionDurationSeconds > 3600) {
			auctionDurationSeconds = 60;
		}
		BigDecimal auctionMin = auctionMinBid == null ? null : AmountParser.parse(auctionMinBid);
		if (auctionMin == null || auctionMin.signum() <= 0) {
			auctionMinBid = "1m";
		}
		auctionHudX = Math.max(0, Math.min(auctionHudX, 9999));
		if (auctionItem == null) {
			auctionItem = "";
		} else if (auctionItem.length() > 40) {
			auctionItem = auctionItem.substring(0, 40);
		}
		auctionHudY = Math.max(0, Math.min(auctionHudY, 9999));
		auctionWarnSeconds = Math.max(0, Math.min(auctionWarnSeconds, 600));
		auctionResultSeconds = Math.max(0, Math.min(auctionResultSeconds, 3600));
	}

	/** One word of letters, digits or underscores, and not a command this mod or the server's /pay already uses. */
	public static boolean isValidPaybackCommand(String name) {
		if (name == null || !name.matches("[a-z0-9_]{1,32}")) {
			return false;
		}
		return !Set.of("pay", "epay", "paysafe", "paybacksafe").contains(name);
	}

	private static boolean isValidPaybackPattern(String pattern) {
		if (pattern == null || !pattern.contains("(?<player>") || !pattern.contains("(?<amount>")) {
			return false;
		}
		try {
			Pattern.compile(pattern);
			return true;
		} catch (PatternSyntaxException e) {
			return false;
		}
	}

	private static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve("donutplus.json");
	}

	/** The mod used to be called Elite's Mod. Carry its settings over so nobody loses them. */
	private static void migrateOldConfig() {
		Path oldFile = FabricLoader.getInstance().getConfigDir().resolve("elites-mod.json");
		Path newFile = file();
		if (Files.exists(oldFile) && !Files.exists(newFile)) {
			try {
				Files.move(oldFile, newFile);
			} catch (IOException e) {
				DonutPlus.LOGGER.warn("Couldn't move {} to {}", oldFile, newFile, e);
			}
		}
	}
}
