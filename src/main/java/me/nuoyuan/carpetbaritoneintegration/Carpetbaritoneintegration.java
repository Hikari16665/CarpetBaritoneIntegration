package me.nuoyuan.carpetbaritoneintegration;

import baritone.server.ServerBaritoneRegistry;
import baritone.Baritone;
import baritone.cache.ServerWorldCache;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import baritone.api.event.events.BlockInteractEvent;
import baritone.api.event.events.ChunkEvent;
import baritone.api.event.events.BlockChangeEvent;
import baritone.api.event.events.type.EventState;
import baritone.api.utils.Pair;
import baritone.api.BaritoneAPI;
import baritone.server.ServerBaritoneProvider;
import net.minecraft.world.level.ChunkPos;
import java.util.List;

public class Carpetbaritoneintegration implements ModInitializer {
    public static final ServerBaritoneRegistry BARITONES = new ServerBaritoneRegistry();

    @Override
    public void onInitialize() {
        BaritoneAPI.setProvider(new ServerBaritoneProvider(BARITONES));
        ServerTickEvents.END_SERVER_TICK.register(BARITONES::tick);
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> BARITONES.clear());
        PlayerBlockBreakEvents.AFTER.register((world, player, pos, state, entity) -> {
            if (Baritone.settings().repackOnAnyBlockChange.value
                    && world instanceof ServerLevel level) {
                ServerWorldCache.get(level).invalidateChunk(
                        pos.getX() >> 4, pos.getZ() >> 4);
                var instance = player instanceof net.minecraft.server.level.ServerPlayer serverPlayer
                        ? BARITONES.get(serverPlayer) : null;
                if (instance != null) instance.getGameEventHandler().onBlockInteract(
                        new BlockInteractEvent(pos,
                                BlockInteractEvent.Type.START_BREAK));
                if (instance != null) instance.getGameEventHandler().onBlockChange(
                        new BlockChangeEvent(new ChunkPos(pos),
                                List.of(new Pair<>(pos.immutable(),
                                        level.getBlockState(pos)))));
            }
        });
        UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
            if (Baritone.settings().repackOnAnyBlockChange.value
                    && world instanceof ServerLevel level) {
                ServerWorldCache.get(level).invalidateChunk(
                        hit.getBlockPos().getX() >> 4,
                        hit.getBlockPos().getZ() >> 4);
                var instance = player instanceof net.minecraft.server.level.ServerPlayer serverPlayer
                        ? BARITONES.get(serverPlayer) : null;
                if (instance != null) instance.getGameEventHandler().onBlockInteract(
                        new BlockInteractEvent(hit.getBlockPos(),
                                BlockInteractEvent.Type.USE));
            }
            return InteractionResult.PASS;
        });
        ServerChunkEvents.CHUNK_LOAD.register((world, chunk) ->
                BARITONES.forEach(instance -> {
                    if (instance.getPlayerContext().world() == world) {
                        instance.getGameEventHandler().onChunkEvent(new ChunkEvent(
                                EventState.POST, ChunkEvent.Type.LOAD,
                                chunk.getPos().x, chunk.getPos().z));
                    }
                }));
        ServerChunkEvents.CHUNK_UNLOAD.register((world, chunk) ->
                BARITONES.forEach(instance -> {
                    if (instance.getPlayerContext().world() == world) {
                        instance.getGameEventHandler().onChunkEvent(new ChunkEvent(
                                EventState.PRE, ChunkEvent.Type.UNLOAD,
                                chunk.getPos().x, chunk.getPos().z));
                    }
                }));
    }
}
