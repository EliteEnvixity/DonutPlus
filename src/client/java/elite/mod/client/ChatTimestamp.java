package elite.mod.client;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;

/**
 * Builds the timestamp that goes in front of each chat line, using the settings in
 * {@link PayConfig}. Kept separate from the mixin so the settings screen can reuse it for
 * its live preview.
 */
public final class ChatTimestamp {
	public static final String DEFAULT_FORMAT = "HH:mm";
	public static final String DEFAULT_COLOR = "AAAAAA";
	public static final String DEFAULT_BRACKETS = "[]";

	/** The choices for the characters around the time, in the order the button cycles through them. */
	public static final List<String> BRACKET_STYLES = List.of("[]", "()", "<>", "none");

	private static final DateTimeFormatter HOVER_FORMAT = DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy, HH:mm:ss");

	private static String cachedPattern;
	private static DateTimeFormatter cachedFormatter;

	private ChatTimestamp() {}

	/** Called for every chat line. Returns the message untouched when timestamps are turned off. */
	public static Component apply(Component message) {
		PayConfig config = PayConfig.get();
		if (!config.timestampsEnabled) {
			return message;
		}
		return stamp(message, config.timestampFormat, config.timestampColor,
			config.timestampBrackets, config.timestampHoverDate);
	}

	/**
	 * Puts a timestamp in front of a message. Bad values (an invalid format, a color that isn't
	 * hex) fall back to the defaults instead of throwing, so a broken setting can never break chat.
	 */
	public static Component stamp(Component message, String pattern, String color, String brackets, boolean hoverDate) {
		ZonedDateTime now = ZonedDateTime.now();

		String open = "";
		String close = "";
		if ("[]".equals(brackets)) {
			open = "[";
			close = "]";
		} else if ("()".equals(brackets)) {
			open = "(";
			close = ")";
		} else if ("<>".equals(brackets)) {
			open = "<";
			close = ">";
		}

		int rgb = parseColor(color);
		Style style = Style.EMPTY.withColor(TextColor.fromRgb(rgb >= 0 ? rgb : 0xAAAAAA));
		if (hoverDate) {
			style = style.withHoverEvent(new HoverEvent.ShowText(Component.literal(now.format(HOVER_FORMAT))));
		}

		MutableComponent stamp = Component.literal(open + formatTime(now, pattern) + close).withStyle(style);

		// Start from an empty parent so the color only applies to the timestamp: children inherit
		// any style their parent sets, which would otherwise tint the whole message.
		return Component.empty().append(stamp).append(Component.literal(" ")).append(message);
	}

	public static boolean isValidPattern(String pattern) {
		if (pattern == null || pattern.isBlank()) {
			return false;
		}
		try {
			DateTimeFormatter.ofPattern(pattern).format(ZonedDateTime.now());
			return true;
		} catch (RuntimeException e) {
			return false;
		}
	}

	/** Accepts "AAAAAA" or "#AAAAAA". Returns the RGB value, or -1 if it isn't six hex digits. */
	public static int parseColor(String text) {
		if (text == null) {
			return -1;
		}
		String hex = text.trim();
		if (hex.startsWith("#")) {
			hex = hex.substring(1);
		}
		if (!hex.matches("[0-9a-fA-F]{6}")) {
			return -1;
		}
		return Integer.parseInt(hex, 16);
	}

	public static boolean isBracketStyle(String style) {
		return BRACKET_STYLES.contains(style);
	}

	/** How a bracket style is shown on its button. */
	public static String bracketLabel(String style) {
		return style.length() == 2 ? style.charAt(0) + " " + style.charAt(1) : "None";
	}

	private static String formatTime(ZonedDateTime now, String pattern) {
		try {
			return now.format(formatter(pattern));
		} catch (RuntimeException e) {
			return now.format(formatter(DEFAULT_FORMAT));
		}
	}

	private static DateTimeFormatter formatter(String pattern) {
		String wanted = (pattern == null || pattern.isBlank()) ? DEFAULT_FORMAT : pattern;
		if (!wanted.equals(cachedPattern)) {
			DateTimeFormatter built;
			try {
				built = DateTimeFormatter.ofPattern(wanted);
			} catch (IllegalArgumentException e) {
				wanted = DEFAULT_FORMAT;
				built = DateTimeFormatter.ofPattern(DEFAULT_FORMAT);
			}
			cachedPattern = wanted;
			cachedFormatter = built;
		}
		return cachedFormatter;
	}
}
