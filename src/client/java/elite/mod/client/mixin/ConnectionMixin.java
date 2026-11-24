package elite.mod.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import elite.mod.client.PayConfirmation;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundChatCommandPacket;

/**
 * Clicking a chat button sends its command straight to the network layer, skipping the
 * normal command events. Catch our hidden confirm/cancel commands here so they never
 * reach the server.
 */
@Mixin(Connection.class)
public class ConnectionMixin {
	@Inject(method = "send(Lnet/minecraft/network/protocol/Packet;)V", at = @At("HEAD"), cancellable = true)
	private void elitesmod$catchHiddenCommands(Packet<?> packet, CallbackInfo ci) {
		if (packet instanceof ServerboundChatCommandPacket commandPacket
				&& PayConfirmation.handleHiddenCommand(commandPacket.command())) {
			ci.cancel();
		}
	}
}
