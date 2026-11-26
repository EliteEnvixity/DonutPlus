package elite.mod.client;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import elite.mod.DonutPlus;
import net.fabricmc.loader.api.FabricLoader;

/** Settings, saved to config/donutplus.json and editable through Mod Menu. */
public final class PayConfig {
	private static final BigDecimal DEFAULT_THRESHOLD = new BigDecimal("100000000");
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static PayConfig instance;

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
