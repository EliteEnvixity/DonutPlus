package elite.mod.client;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import elite.mod.EliteSMod;
import net.fabricmc.loader.api.FabricLoader;

/** Settings, saved to config/elites-mod.json and editable through Mod Menu. */
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

	public static PayConfig get() {
		if (instance == null) {
			load();
		}
		return instance;
	}

	public static void load() {
		PayConfig loaded = null;
		Path file = file();
		if (Files.exists(file)) {
			try (Reader reader = Files.newBufferedReader(file)) {
				loaded = GSON.fromJson(reader, PayConfig.class);
			} catch (Exception e) {
				EliteSMod.LOGGER.warn("Couldn't read {}, using defaults", file, e);
			}
		}
		instance = loaded != null ? loaded : new PayConfig();
		instance.sanitize();
		if (loaded == null) {
			save();
		}
	}

	public static void save() {
		Path file = file();
		try (Writer writer = Files.newBufferedWriter(file)) {
			GSON.toJson(get(), writer);
		} catch (IOException e) {
			EliteSMod.LOGGER.warn("Couldn't save {}", file, e);
		}
	}

	public BigDecimal thresholdValue() {
		BigDecimal value = AmountParser.parse(threshold);
		return value != null ? value : DEFAULT_THRESHOLD;
	}

	private void sanitize() {
		if (threshold == null) {
			threshold = "100m";
		}
		if (expirySeconds < 0) {
			expirySeconds = 0;
		}
	}

	private static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve("elites-mod.json");
	}
}
