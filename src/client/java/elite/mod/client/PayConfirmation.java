package elite.mod.client;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;

/**
 * Intercepts "/pay <player> <amount>" and asks for confirmation in chat, with clickable
 * buttons, when the amount is at or above the threshold from {@link PayConfig}.
 */
public final class PayConfirmation {
	private static final Set<String> PAY_COMMANDS = Set.of("pay", "epay", "essentials:pay", "essentials:epay");

	/** Set while we re-send a confirmed command so it isn't intercepted again. */
	private static boolean bypass = false;

	/** Blocked command waiting to be shown on the next client tick. */
	private static PendingPay queued = null;

	/** The payment currently waiting for a click in chat. */
	private static ChatRequest chatRequest = null;

	private record PendingPay(String command, String player, String rawAmount, BigDecimal amount,
			Consumer<Boolean> callback) {}

	private record ChatRequest(PendingPay pay, String id, long expiresAt) {}

	private PayConfirmation() {}

	public static void register() {
		PayConfig.load();

		ClientSendMessageEvents.ALLOW_COMMAND.register(command -> {
			// Typed versions of the hidden confirm/cancel commands (clicks are caught by ConnectionMixin).
			if (handleHiddenCommand(command)) {
				return false;
			}

			PayConfig config = PayConfig.get();
			if (bypass || !config.enabled) {
				return true;
			}

			PendingPay pay = parse(command);
			if (pay == null || pay.amount().compareTo(config.thresholdValue()) < 0) {
				return true;
			}

			queued = pay;
			return false;
		});

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			if (queued != null) {
				PendingPay pay = queued;
				queued = null;
				askInChat(client, pay);
			}

			if (chatRequest != null && isExpired(chatRequest.expiresAt())) {
				Consumer<Boolean> callback = chatRequest.pay().callback();
				chatRequest = null;
				say(client, Component.literal("Payment request expired.").withStyle(ChatFormatting.RED));
				if (callback != null) {
					callback.accept(false);
				}
			}
		});
	}

	/**
	 * The chat buttons run "paysafe confirm <id>" / "paysafe cancel <id>". They aren't registered
	 * as real commands, so they never show up in autofill and never go to the server.
	 *
	 * @return true if the command was one of ours and has been handled (so it must be blocked)
	 */
	public static boolean handleHiddenCommand(String command) {
		if (PaybackManager.handleHiddenCommand(command)) {
			return true;
		}

		String[] words = command.trim().split("\\s+");
		if (words.length != 3 || !words[0].equalsIgnoreCase("paysafe")) {
			return false;
		}

		if (words[1].equalsIgnoreCase("confirm")) {
			resolveChat(words[2], true);
			return true;
		}
		if (words[1].equalsIgnoreCase("cancel")) {
			resolveChat(words[2], false);
			return true;
		}
		return false;
	}

	private static void askInChat(Minecraft client, PendingPay pay) {
		String id = UUID.randomUUID().toString().substring(0, 8);
		chatRequest = new ChatRequest(pay, id, expiryTime());

		MutableComponent confirm = Component.literal("[\u2714 Confirm]").withStyle(style -> style
			.withColor(ChatFormatting.GREEN)
			.withClickEvent(new ClickEvent.RunCommand("/paysafe confirm " + id))
			.withHoverEvent(new HoverEvent.ShowText(Component.literal("Send the payment"))));

		MutableComponent cancel = Component.literal("[\u2718 Cancel]").withStyle(style -> style
			.withColor(ChatFormatting.RED)
			.withClickEvent(new ClickEvent.RunCommand("/paysafe cancel " + id))
			.withHoverEvent(new HoverEvent.ShowText(Component.literal("Don't send"))));

		MutableComponent paymentLine = Component.literal("Payment for ").withStyle(ChatFormatting.WHITE)
			.append(Component.literal(pay.rawAmount()).withStyle(ChatFormatting.GREEN))
			.append(Component.literal(" to ").withStyle(ChatFormatting.WHITE))
			.append(Component.literal(pay.player()).withStyle(ChatFormatting.GREEN));

		MutableComponent clickLine = Component.literal("Click to ").withStyle(ChatFormatting.GRAY)
			.append(confirm)
			.append(Component.literal(" or ").withStyle(ChatFormatting.GRAY))
			.append(cancel);

		say(client, paymentLine);
		say(client, clickLine);
	}

	private static void resolveChat(String id, boolean confirmed) {
		Minecraft client = Minecraft.getInstance();
		ChatRequest request = chatRequest;

		if (request == null || !request.id().equals(id)) {
			say(client, Component.literal("That payment request is no longer active.").withStyle(ChatFormatting.RED));
			return;
		}

		chatRequest = null;
		Consumer<Boolean> callback = request.pay().callback();

		if (isExpired(request.expiresAt())) {
			say(client, Component.literal("Payment request expired.").withStyle(ChatFormatting.RED));
			if (callback != null) {
				callback.accept(false);
			}
		} else if (confirmed) {
			send(client, request.pay().command());
			if (callback != null) {
				callback.accept(true);
			}
		} else {
			say(client, Component.literal("Payment cancelled.").withStyle(ChatFormatting.GRAY));
			if (callback != null) {
				callback.accept(false);
			}
		}
	}

	private static long expiryTime() {
		int seconds = PayConfig.get().expirySeconds;
		return seconds > 0 ? System.currentTimeMillis() + seconds * 1000L : 0L;
	}

	private static boolean isExpired(long expiresAt) {
		return expiresAt > 0 && System.currentTimeMillis() > expiresAt;
	}

	/** True while a Safe Pay question is waiting to be shown or answered. */
	static boolean isBusy() {
		return queued != null || chatRequest != null;
	}

	/**
	 * Sends "/pay player amount" on behalf of Payback, but through Safe Pay: if Safe Pay is on and
	 * the amount is at or above the threshold, the usual Confirm/Cancel buttons are shown and the
	 * payment is only sent when you click Confirm. Smaller amounts go straight out.
	 *
	 * The callback is always called exactly once: true if the payment was sent, false if it was
	 * cancelled or the request expired.
	 */
	static void sendChecked(Minecraft client, String player, BigDecimal amount, Consumer<Boolean> callback) {
		String command = "pay " + player + " " + amount.toBigInteger();
		PayConfig config = PayConfig.get();

		if (!config.enabled || amount.compareTo(config.thresholdValue()) < 0) {
			send(client, command);
			callback.accept(true);
			return;
		}

		queued = new PendingPay(command, player, "$" + AmountParser.format(amount), amount, callback);
	}

	/** True if a Safe Pay question that came from Payback is waiting to be shown or answered. */
	static boolean hasPaybackRequest() {
		return (queued != null && queued.callback() != null)
			|| (chatRequest != null && chatRequest.pay().callback() != null);
	}

	/** Forgets any Safe Pay question that came from Payback, without calling its callback. */
	static void dropPaybackRequest() {
		if (queued != null && queued.callback() != null) {
			queued = null;
		}
		if (chatRequest != null && chatRequest.pay().callback() != null) {
			chatRequest = null;
		}
	}

	/** Sends a command without Safe Pay stepping in. Callers have already asked the user. */
	static void send(Minecraft client, String command) {
		if (client.getConnection() == null) {
			return;
		}
		bypass = true;
		try {
			client.getConnection().sendCommand(command);
		} finally {
			bypass = false;
		}
	}

	private static void say(Minecraft client, Component message) {
		if (client.player != null) {
			client.player.displayClientMessage(message, false);
		}
	}

	/** Returns null if the command isn't a well-formed pay command. */
	private static PendingPay parse(String command) {
		String[] parts = command.trim().split("\\s+");
		if (parts.length < 3 || !PAY_COMMANDS.contains(parts[0].toLowerCase(Locale.ROOT))) {
			return null;
		}

		BigDecimal amount = AmountParser.parse(parts[2]);
		if (amount == null) {
			return null;
		}
		return new PendingPay(command, parts[1], parts[2], amount, null);
	}
}
