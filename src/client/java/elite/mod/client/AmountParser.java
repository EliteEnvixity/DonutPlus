package elite.mod.client;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses amounts like 100m, 2.5b, 750k or 250000000. */
public final class AmountParser {
	private static final Pattern AMOUNT = Pattern.compile("^(\\d+(?:\\.\\d+)?)([kmbt]?)$", Pattern.CASE_INSENSITIVE);

	private AmountParser() {}

	/** Returns null if the text isn't a valid amount. */
	public static BigDecimal parse(String raw) {
		if (raw == null) {
			return null;
		}
		Matcher m = AMOUNT.matcher(raw.trim().replace(",", "").replace("_", ""));
		if (!m.matches()) {
			return null;
		}
		return new BigDecimal(m.group(1)).multiply(multiplier(m.group(2)));
	}

	/**
	 * Short form of an amount, without a sign: 4400000 -> "4.4m", 12345678 -> "12.34m", 950 -> "950".
	 * Uses k, m, b and t, keeps up to two decimals and rounds down so it never overstates.
	 */
	public static String formatShort(BigDecimal value) {
		BigDecimal abs = value.abs();
		String[] suffixes = {"t", "b", "m", "k"};
		BigDecimal[] units = {
			new BigDecimal(BigInteger.TEN.pow(12)),
			new BigDecimal(BigInteger.TEN.pow(9)),
			new BigDecimal(BigInteger.TEN.pow(6)),
			new BigDecimal(BigInteger.TEN.pow(3))
		};
		for (int i = 0; i < units.length; i++) {
			if (abs.compareTo(units[i]) >= 0) {
				return abs.divide(units[i], 2, RoundingMode.DOWN).stripTrailingZeros().toPlainString() + suffixes[i];
			}
		}
		return abs.setScale(0, RoundingMode.DOWN).toPlainString();
	}

	/** 100000000 -> "100,000,000" */
	public static String format(BigDecimal value) {
		return String.format(Locale.US, "%,d", value.toBigInteger());
	}

	private static BigDecimal multiplier(String suffix) {
		return switch (suffix.toLowerCase(Locale.ROOT)) {
			case "k" -> new BigDecimal(BigInteger.TEN.pow(3));
			case "m" -> new BigDecimal(BigInteger.TEN.pow(6));
			case "b" -> new BigDecimal(BigInteger.TEN.pow(9));
			case "t" -> new BigDecimal(BigInteger.TEN.pow(12));
			default -> BigDecimal.ONE;
		};
	}
}
