package me.nuoyuan.carpetbaritoneintegration.mixin;

import baritone.Baritone;
import me.nuoyuan.carpetbaritoneintegration.Carpetbaritoneintegration;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.BooleanSupplier;

/**
 * Vanilla's empty-server timer may ignore Carpet fake players. Preserve the
 * configured pause behavior, but reset its timer while a Baritone task can
 * still make progress.
 */
@Mixin(MinecraftServer.class)
abstract class MinecraftServerEmptyPauseMixin {
    @Shadow private int emptyTicks;

    @Inject(method = "tickServer", at = @At("HEAD"))
    private void cbi$keepActiveTasksTicking(
            BooleanSupplier hasTimeLeft, CallbackInfo callback) {
        if (!Baritone.settings().keepServerAwakeForTasks.value) return;
        if (Carpetbaritoneintegration.BARITONES.snapshot().stream()
                .anyMatch(Baritone::hasActiveTask)) {
            emptyTicks = 0;
        }
    }
}
