package me.nuoyuan.carpetbaritoneintegration.mixin;

import baritone.cache.ServerWorldCache;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Level.class)
public abstract class LevelBlockChangeMixin {
    @Inject(
            method = "setBlock(Lnet/minecraft/core/BlockPos;"
                    + "Lnet/minecraft/world/level/block/state/BlockState;II)Z",
            at = @At("RETURN"))
    private void carpetBaritone$updateCachedBlock(
            BlockPos pos, BlockState state, int flags,
            int recursionLeft, CallbackInfoReturnable<Boolean> cir) {
        if (!Boolean.TRUE.equals(cir.getReturnValue())
                || !((Object) this instanceof ServerLevel level)) {
            return;
        }
        ServerWorldCache cache = ServerWorldCache.ifPresent(level);
        if (cache == null) return;
        LevelChunk chunk = level.getChunkSource().getChunkNow(
                pos.getX() >> 4, pos.getZ() >> 4);
        if (chunk != null) cache.updateBlock(pos, state, chunk);
    }
}
