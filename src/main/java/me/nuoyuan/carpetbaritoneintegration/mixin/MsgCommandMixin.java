package me.nuoyuan.carpetbaritoneintegration.mixin;

import baritone.server.BasicGoalCommandHandler;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.PlayerChatMessage;
import net.minecraft.server.commands.MsgCommand;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Collection;

@Mixin(MsgCommand.class)
public abstract class MsgCommandMixin {
    @Inject(method = "sendMessage", at = @At("HEAD"), cancellable = true)
    private static void carpetBaritone$receiveCommand(
            CommandSourceStack source,
            Collection<ServerPlayer> targets,
            PlayerChatMessage message,
            CallbackInfo ci
    ) {
        ServerPlayer sender = source.getPlayer();
        if (sender == null) {
            return;
        }
        boolean handled = false;
        for (ServerPlayer target : targets) {
            handled |= BasicGoalCommandHandler.handle(sender, target, message.signedContent());
        }
        if (handled) {
            ci.cancel();
        }
    }
}
