package elite.mod.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import elite.mod.client.ChatTimestamp;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.network.chat.Component;

/**
 * Every line that shows up in the chat window (player chat, system messages, and our own
 * client messages) ends up in ChatComponent.addMessage, so stamping the time here covers all of them.
 * The format, color and on/off switch live in the config; see {@link ChatTimestamp}.
 */
@Mixin(ChatComponent.class)
public class ChatComponentMixin {
	@ModifyVariable(
		method = "addMessage(Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/MessageSignature;Lnet/minecraft/client/GuiMessageTag;)V",
		at = @At("HEAD"),
		argsOnly = true)
	private Component elitesmod$addTimestamp(Component message) {
		return ChatTimestamp.apply(message);
	}
}
