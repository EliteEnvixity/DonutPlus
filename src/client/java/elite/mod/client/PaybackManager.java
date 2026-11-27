package elite.mod.client;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import com.mojang.blaze3d.platform.InputConstants;

import elite.mod.DonutPlus;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;

/**
 * Payment payback. Remembers who recently paid you, and when you press the keybind or run
 * /payback, pays each of them back what they sent times the configured multiplier (2x by default).
 *
 * Nothing is ever sent on its own: it always takes a keypress or command from you, and by default
 * you also have to click Confirm on the list first.
 */
public final class PaybackManager {
	private static final int MAX_LINES = 8;
	private static final int MAX_TRACKED = 1000;

	private record Received(String player, BigDecimal amount, long time) {}

	private record Tally(String player, BigDecimal total) {}

	private record Payout(String player, BigDecimal received, BigDecimal payout) {}

	private record Plan(String id, List<Payout> payouts, long createdAt, long expiresAt) {}

	private record Send(String player, BigDecimal received, BigDecimal amount, long planTime) {}

	private static final List<Received> received = new ArrayList<>();
	private static final Deque<Send> sendQueue = new ArrayDeque<>();
	/** The payout currently waiting on a Safe Pay click, so stopping can put it back on a list. */
	private static Send inFlight = null;
	private static Plan pending = null;
	/** True while /payback is waiting for payments. Payments are only tracked while this is on. */
	private static boolean waiting = false;
	private static int cooldown = 0;
	private static int totalToSend = 0;
	private static int sentSoFar = 0;
	private static int skipped = 0;
	private static boolean warnedUnmatched = false;

	private static String cachedSource = null;
	private static Pattern cachedPattern = null;

	private static KeyMapping paybackKey;

	private PaybackManager() {}

	public static void register() {
		// Shows up under Options > Controls > Key Binds. Unbound until you pick a key.
		KeyMapping.Category category = ModKeys.MAIN;
		paybackKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
			"key.donutplus.payback",
			InputConstants.Type.KEYSYM,
			-1, // GLFW_KEY_UNKNOWN: not bound by default
			category));

		// Only system/game messages, never the action bar copy, so a payment isn't counted twice.
		// ALLOW_GAME lets us hide a payment line (see onMessage) as well as read it.
		ClientReceiveMessageEvents.ALLOW_GAME.register((message, overlay) ->
			overlay || onMessage(message.getString()));

		ClientTickEvents.END_CLIENT_TICK.register(PaybackManager::tick);

		// The command name comes from paybackCommand in config/donutplus.json (read once at startup).
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
			dispatcher.register(ClientCommandManager.literal(PayConfig.get().paybackCommand)
				.executes(ctx -> {
					toggleWaiting(Minecraft.getInstance());
					return 1;
				})
				.then(ClientCommandManager.literal("start").executes(ctx -> {
					trigger(Minecraft.getInstance(), false);
					return 1;
				}))
				.then(ClientCommandManager.literal("status").executes(ctx -> {
					showStatus(Minecraft.getInstance());
					return 1;
				}))
				.then(ClientCommandManager.literal("clear").executes(ctx -> {
					clear(Minecraft.getInstance());
					return 1;
				}))
				.then(ClientCommandManager.literal("stop").executes(ctx -> {
					stop(Minecraft.getInstance());
					return 1;
				}))));
	}

	// ---------------------------------------------------------------- reading payments

	/**
	 * Reads a game message while waiting for payments and tracks it if it's a payment.
	 *
	 * @return false if the message should be hidden from chat (a payment that doesn't add up to the
	 *         minimum yet, with "hide under minimum" on), true otherwise
	 */
	private static boolean onMessage(String text) {
		PayConfig config = PayConfig.get();
		if (!waiting || text == null) {
			return true;
		}

		String line = text.trim();
		Matcher m = pattern(config).matcher(line);
		if (!m.matches()) {
			// Looks like a payment but isn't in the expected shape: tell the user once, so they can
			// fix paybackPattern instead of wondering why nothing is tracked.
			if (!warnedUnmatched && line.toLowerCase(Locale.ROOT).contains(" paid you ")) {
				warnedUnmatched = true;
				DonutPlus.LOGGER.info("Payback couldn't read this payment line: {}", line);
				Minecraft client = Minecraft.getInstance();
				String shown = line.length() > 80 ? line.substring(0, 80) + "..." : line;
				say(client, "Saw a line that looks like a payment but doesn't match paybackPattern: \""
					+ shown + "\". Adjust paybackPattern in config/donutplus.json.", ChatFormatting.YELLOW);
			}
			return true;
		}

		BigDecimal amount;
		String player;
		try {
			amount = AmountParser.parse(m.group("amount"));
			player = m.group("player");
		} catch (IllegalArgumentException e) {
			return true; // pattern is missing a named group
		}
		if (amount == null || amount.signum() <= 0 || player == null) {
			return true;
		}

		prune(System.currentTimeMillis());
		received.add(new Received(player, amount, System.currentTimeMillis()));
		if (received.size() > MAX_TRACKED) {
			received.remove(0);
		}

		// Hide it only while this player's payments still add up to less than the minimum. Once their
		// total reaches the minimum, payments show as normal. Nothing is said about hidden ones.
		if (config.paybackHideUnderMinimum && belowMinimum(totalFrom(player), config)) {
			return false;
		}
		return true;
	}

	/** Everything tracked from one player so far, case-insensitively. */
	private static BigDecimal totalFrom(String player) {
		BigDecimal total = BigDecimal.ZERO;
		for (Received r : received) {
			if (r.player().equalsIgnoreCase(player)) {
				total = total.add(r.amount());
			}
		}
		return total;
	}

	private static Pattern pattern(PayConfig config) {
		String source = config.paybackPattern;
		if (cachedPattern == null || !source.equals(cachedSource)) {
			Pattern compiled;
			try {
				compiled = Pattern.compile(source);
			} catch (PatternSyntaxException e) {
				compiled = Pattern.compile(PayConfig.DEFAULT_PAYBACK_PATTERN);
			}
			cachedSource = source;
			cachedPattern = compiled;
		}
		return cachedPattern;
	}

	/** Forget payments older than the configured window. */
	private static void prune(long now) {
		long windowMs = PayConfig.get().paybackWindowSeconds * 1000L;
		if (windowMs > 0) {
			received.removeIf(r -> now - r.time() > windowMs);
		}
	}

	// ---------------------------------------------------------------- triggering

	/** /payback: start or stop waiting for payments. The list is kept when you stop waiting. */
	private static void toggleWaiting(Minecraft client) {
		if (client.player == null) {
			return;
		}
		waiting = !waiting;
		if (waiting) {
			say(client, "Waiting for payments. Run /" + cmd() + " again to stop waiting, or /" + cmd()
				+ " start to pay them back.", ChatFormatting.GRAY);
		} else {
			say(client, "Stopped waiting for payments. " + tally().size() + " player(s) on the list. Use /" + cmd()
				+ " start to pay them back.", ChatFormatting.GRAY);
		}
	}

	/**
	 * /payback start (or the keybind): pay back everyone on the list.
	 *
	 * @param stopIfRunning if payments are already going out, stop them (the keybind does this) instead
	 *                      of just saying they're already going out
	 */
	private static void trigger(Minecraft client, boolean stopIfRunning) {
		PayConfig config = PayConfig.get();
		if (client.player == null || client.getConnection() == null) {
			return;
		}
		// With nothing going out, a keybind press starts paying back; while payments are going out,
		// a press stops them (the /start command never stops them, /stop does).
		if (!sendQueue.isEmpty() || PayConfirmation.hasPaybackRequest()) {
			if (stopIfRunning) {
				stop(client);
			} else {
				say(client, "Already paying back. Use /" + cmd() + " stop to cancel.", ChatFormatting.GRAY);
			}
			return;
		}

		long now = System.currentTimeMillis();
		prune(now);
		List<Payout> payouts = buildPayouts(config);
		if (payouts.isEmpty()) {
			say(client, "No recent payments to pay back.", ChatFormatting.GRAY);
			return;
		}

		long expiresAt = config.expirySeconds > 0 ? now + config.expirySeconds * 1000L : 0L;
		Plan plan = new Plan(UUID.randomUUID().toString().substring(0, 8), payouts, now, expiresAt);

		if (config.paybackConfirm) {
			pending = plan;
			showPlan(client, plan, config);
		} else {
			execute(client, plan);
		}
	}

	private static List<Payout> buildPayouts(PayConfig config) {
		BigDecimal multiplier = BigDecimal.valueOf(config.paybackMultiplier);
		BigDecimal cap = AmountParser.parse(config.paybackMaxPayout);
		boolean capped = cap != null && cap.signum() > 0;

		List<Payout> payouts = new ArrayList<>();
		for (Tally tally : tally()) {
			BigDecimal payout = tally.total().multiply(multiplier).setScale(0, RoundingMode.DOWN);
			if (capped && payout.compareTo(cap) > 0) {
				payout = cap.setScale(0, RoundingMode.DOWN);
			}
			if (payout.signum() > 0) {
				payouts.add(new Payout(tally.player(), tally.total(), payout));
			}
		}
		return payouts;
	}

	/**
	 * Adds up what each player paid you, case-insensitively, so four $400K payments become $1.6M.
	 * Players whose total is under the minimum are left out.
	 */
	private static List<Tally> tally() {
		PayConfig config = PayConfig.get();
		Map<String, String> names = new LinkedHashMap<>();
		Map<String, BigDecimal> totals = new LinkedHashMap<>();
		for (Received r : received) {
			String key = r.player().toLowerCase(Locale.ROOT);
			names.putIfAbsent(key, r.player());
			totals.merge(key, r.amount(), BigDecimal::add);
		}
		List<Tally> list = new ArrayList<>();
		totals.forEach((key, total) -> {
			if (!belowMinimum(total, config)) {
				list.add(new Tally(names.get(key), total));
			}
		});
		return list;
	}

	private static void showPlan(Minecraft client, Plan plan, PayConfig config) {
		BigDecimal total = BigDecimal.ZERO;
		for (Payout p : plan.payouts()) {
			total = total.add(p.payout());
		}

		say(client, "Pay back " + plan.payouts().size() + " player(s) at " + multiplierText(config) + "x?",
			ChatFormatting.WHITE);

		int shown = 0;
		for (Payout p : plan.payouts()) {
			if (shown++ == MAX_LINES) {
				say(client, "...and " + (plan.payouts().size() - MAX_LINES) + " more", ChatFormatting.GRAY);
				break;
			}
			say(client, Component.literal(p.player()).withStyle(ChatFormatting.GRAY)
				.append(Component.literal(" sent $" + AmountParser.format(p.received())
					+ " -> pay back $" + AmountParser.format(p.payout())).withStyle(ChatFormatting.GRAY)));
		}

		say(client, Component.literal("Total: ").withStyle(ChatFormatting.GRAY)
			.append(Component.literal("$" + AmountParser.format(total)).withStyle(ChatFormatting.GRAY)));

		MutableComponent confirm = Component.literal("[\u2714 Confirm]").withStyle(style -> style
			.withColor(ChatFormatting.GREEN)
			.withClickEvent(new ClickEvent.RunCommand("/paybacksafe confirm " + plan.id()))
			.withHoverEvent(new HoverEvent.ShowText(Component.literal("Send these payments"))));

		MutableComponent cancel = Component.literal("[\u2718 Cancel]").withStyle(style -> style
			.withColor(ChatFormatting.RED)
			.withClickEvent(new ClickEvent.RunCommand("/paybacksafe cancel " + plan.id()))
			.withHoverEvent(new HoverEvent.ShowText(Component.literal("Don't send"))));

		say(client, Component.literal("Click to ").withStyle(ChatFormatting.GRAY)
			.append(confirm)
			.append(Component.literal(" or ").withStyle(ChatFormatting.GRAY))
			.append(cancel));
	}

	// ---------------------------------------------------------------- confirming

	/**
	 * The chat buttons run "paybacksafe confirm <id>" / "paybacksafe cancel <id>". Like the Safe Pay
	 * buttons these aren't real commands: they're caught before they reach the server, and the random
	 * id means a message from the server can't confirm a payback for you.
	 *
	 * @return true if the command was ours and has been handled (so it must be blocked)
	 */
	public static boolean handleHiddenCommand(String command) {
		String[] words = command.trim().split("\\s+");
		if (words.length != 3 || !words[0].equalsIgnoreCase("paybacksafe")) {
			return false;
		}

		if (words[1].equalsIgnoreCase("confirm")) {
			resolve(words[2], true);
			return true;
		}
		if (words[1].equalsIgnoreCase("cancel")) {
			resolve(words[2], false);
			return true;
		}
		return false;
	}

	private static void resolve(String id, boolean confirmed) {
		Minecraft client = Minecraft.getInstance();
		Plan plan = pending;

		if (plan == null || !plan.id().equals(id)) {
			say(client, "That payback request is no longer active.", ChatFormatting.RED);
			return;
		}

		pending = null;

		if (plan.expiresAt() > 0 && System.currentTimeMillis() > plan.expiresAt()) {
			say(client, "Payback request expired.", ChatFormatting.RED);
		} else if (confirmed) {
			execute(client, plan);
		} else {
			say(client, "Payback cancelled.", ChatFormatting.GRAY);
		}
	}

	private static void execute(Minecraft client, Plan plan) {
		for (Payout p : plan.payouts()) {
			sendQueue.add(new Send(p.player(), p.received(), p.payout(), plan.createdAt()));
		}
		totalToSend = plan.payouts().size();
		sentSoFar = 0;
		skipped = 0;
		cooldown = 0;
		say(client, "Paying back " + totalToSend + " player(s)...", ChatFormatting.GREEN);
	}

	// ---------------------------------------------------------------- sending

	private static void tick(Minecraft client) {
		if (client.player == null || client.getConnection() == null) {
			// Left the world: forget everything so nothing carries over to another server.
			received.clear();
			sendQueue.clear();
			PayConfirmation.dropPaybackRequest();
			pending = null;
			waiting = false;
			while (paybackKey.consumeClick()) {
				// discard clicks made while not in a world
			}
			return;
		}

		while (paybackKey.consumeClick()) {
			trigger(client, true);
		}

		if (pending != null && pending.expiresAt() > 0 && System.currentTimeMillis() > pending.expiresAt()) {
			pending = null;
			say(client, "Payback request expired.", ChatFormatting.RED);
		}

		if (sendQueue.isEmpty()) {
			return;
		}
		// A large payment is waiting for your click (Safe Pay): don't move on to the next one yet.
		if (PayConfirmation.isBusy()) {
			return;
		}
		if (cooldown > 0) {
			cooldown--;
			return;
		}

		Send next = sendQueue.poll();
		inFlight = next;
		cooldown = Math.max(1, PayConfig.get().paybackDelayTicks);

		// Goes through Safe Pay: small payments are sent right away, large ones ask you first.
		// finish() is called once the payment is sent, or cancelled/expired.
		PayConfirmation.sendChecked(client, next.player(), next.amount(), sent -> finish(client, next, sent));
	}

	private static void finish(Minecraft client, Send send, boolean sent) {
		inFlight = null;
		// Sent, cancelled or expired: either way this player is off the tracked list. Their payment is
		// only paid back once, and a cancelled one won't come back up the next time you run /payback.
		received.removeIf(r -> r.player().equalsIgnoreCase(send.player()) && r.time() <= send.planTime());

		if (sent) {
			sentSoFar++;
		} else {
			skipped++;
		}

		if (sentSoFar + skipped >= totalToSend) {
			String text = "Done: paid back " + sentSoFar + " of " + totalToSend + " player(s).";
			if (skipped > 0) {
				text += " " + skipped + " not sent.";
			}
			say(client, text, skipped > 0 ? ChatFormatting.YELLOW : ChatFormatting.GREEN);
		}
	}

	// ---------------------------------------------------------------- /payback subcommands

	private static void showStatus(Minecraft client) {
		PayConfig config = PayConfig.get();
		prune(System.currentTimeMillis());

		say(client, (waiting ? "Waiting for payments" : "Not waiting for payments") + " ("
			+ multiplierText(config) + "x, last "
			+ (config.paybackWindowSeconds > 0 ? config.paybackWindowSeconds + "s" : "session") + ", "
			+ (config.paybackConfirm ? "asks first" : "sends right away") + minimumText(config) + ")", ChatFormatting.WHITE);

		List<Tally> tallies = tally();
		if (tallies.isEmpty()) {
			say(client, "No payments tracked.", ChatFormatting.GRAY);
			return;
		}

		int shown = 0;
		for (Tally t : tallies) {
			if (shown++ == MAX_LINES) {
				say(client, "...and " + (tallies.size() - MAX_LINES) + " more", ChatFormatting.GRAY);
				break;
			}
			say(client, t.player() + ": $" + AmountParser.format(t.total()), ChatFormatting.GRAY);
		}
	}

	private static void clear(Minecraft client) {
		received.clear();
		pending = null;
		say(client, "Cleared tracked payments.", ChatFormatting.GRAY);
	}

	/**
	 * Stops sending. Everyone who hasn't been paid yet goes onto a new list, shown right away with
	 * Confirm/Cancel buttons, so you can pick up where you left off or drop them.
	 */
	private static void stop(Minecraft client) {
		List<Payout> unsent = new ArrayList<>();
		long planTime = System.currentTimeMillis();

		if (inFlight != null && PayConfirmation.hasPaybackRequest()) {
			unsent.add(new Payout(inFlight.player(), inFlight.received(), inFlight.amount()));
			planTime = inFlight.planTime();
		}
		for (Send s : sendQueue) {
			unsent.add(new Payout(s.player(), s.received(), s.amount()));
			planTime = Math.min(planTime, s.planTime());
		}

		sendQueue.clear();
		PayConfirmation.dropPaybackRequest();
		inFlight = null;
		pending = null;

		if (unsent.isEmpty()) {
			say(client, "Nothing was being sent.", ChatFormatting.YELLOW);
			return;
		}

		say(client, "Stopped. " + unsent.size() + " payment(s) were not sent. Here they are as a new list:",
			ChatFormatting.YELLOW);

		PayConfig config = PayConfig.get();
		long expiresAt = config.expirySeconds > 0 ? System.currentTimeMillis() + config.expirySeconds * 1000L : 0L;
		Plan plan = new Plan(UUID.randomUUID().toString().substring(0, 8), unsent, planTime, expiresAt);
		pending = plan;
		showPlan(client, plan, config);
	}

	// ---------------------------------------------------------------- helpers

	/** The minimum payment worth paying back, or null if there isn't one. */
	private static BigDecimal minimum(PayConfig config) {
		BigDecimal min = AmountParser.parse(config.paybackMinimum);
		return min != null && min.signum() > 0 ? min : null;
	}

	private static boolean belowMinimum(BigDecimal amount, PayConfig config) {
		BigDecimal min = minimum(config);
		return min != null && amount.compareTo(min) < 0;
	}

	private static String minimumText(PayConfig config) {
		BigDecimal min = minimum(config);
		return min == null ? "" : ", min $" + AmountParser.format(min);
	}

	private static String cmd() {
		return PayConfig.get().paybackCommand;
	}

	private static String multiplierText(PayConfig config) {
		return BigDecimal.valueOf(config.paybackMultiplier).stripTrailingZeros().toPlainString();
	}

	private static void say(Minecraft client, Component body) {
		if (client.player != null) {
			client.player.displayClientMessage(body, false);
		}
	}

	/** All payback messages are gray, whatever color the caller asked for. */
	private static void say(Minecraft client, String text, ChatFormatting color) {
		say(client, Component.literal(text).withStyle(ChatFormatting.GRAY));
	}
}
