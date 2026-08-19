package me.nuoyuan.carpetbaritoneintegration.mixin;

import baritone.server.BasicGoalCommandHandler;
import baritone.server.llm.LlmConversationService;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.PlayerChatMessage;
import net.minecraft.network.chat.Component;
import net.minecraft.server.commands.MsgCommand;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Collection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mixin(MsgCommand.class)
public abstract class MsgCommandMixin {
    private static final Logger CBI_TELL_LOGGER =
            LoggerFactory.getLogger("CBI-TELL");
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
            String content = message.signedContent();
            boolean targetHandled = BasicGoalCommandHandler.handle(
                    sender, target, content);
            String route = "cbi";
            if (!targetHandled) {
                targetHandled = LlmConversationService.INSTANCE.handle(
                        sender, target, content);
                route = "llm";
            }
            if (targetHandled) {
                handled = true;
                String senderName = sender.getScoreboardName();
                String targetName = target.getScoreboardName();
                sender.sendSystemMessage(Component.literal(
                        "你悄悄地对 " + targetName + " 说：\"" + content + "\""));
                CBI_TELL_LOGGER.info("Intercepted /tell sender={} target={} "
                                + "route={} message={}", senderName, targetName,
                        route, compactForLog(content));
            }
        }
        if (handled) {
            ci.cancel();
        }
    }


    private static String compactForLog(String value) {
        String compact = value == null ? "" : value
                .replaceAll("[\\r\\n\\t]+", " ")
                .replaceAll("(?i)(llmApiKey\\s+)(\\\"[^\\\"]*\\\"|'[^']*'|\\S+)",
                        "$1<redacted>")
                .replaceAll("(?i)\\bsk-[A-Za-z0-9_-]{8,}\\b",
                        "<redacted-key>").trim();
        return compact.length() <= 1_000 ? compact
                : compact.substring(0, 1_000) + "...";
    }
}
