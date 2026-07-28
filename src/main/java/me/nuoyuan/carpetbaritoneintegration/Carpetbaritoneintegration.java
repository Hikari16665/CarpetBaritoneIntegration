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
import net.minecraft.server.level.ServerPlayer;
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
import me.nuoyuan.carpetbaritoneintegration.network.PathNetwork;
import me.nuoyuan.carpetbaritoneintegration.network.ControlOptionsPayload;
import me.nuoyuan.carpetbaritoneintegration.network.ControlOptionsRequestPayload;
import me.nuoyuan.carpetbaritoneintegration.network.CommandSubmitPayload;
import me.nuoyuan.carpetbaritoneintegration.network.CommandResultPayload;
import me.nuoyuan.carpetbaritoneintegration.network.SettingOptions;
import baritone.server.BasicGoalCommandHandler;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import carpet.patches.EntityPlayerMPFake;
import java.util.LinkedHashSet;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import baritone.utils.schematic.SchematicSystem;
import me.nuoyuan.carpetbaritoneintegration.compat.SyncmaticaBridge;

public class Carpetbaritoneintegration implements ModInitializer {
    public static final ServerBaritoneRegistry BARITONES = new ServerBaritoneRegistry();

    @Override
    public void onInitialize() {
        PathNetwork.registerCommon();
        ServerPlayNetworking.registerGlobalReceiver(
                ControlOptionsRequestPayload.TYPE, (payload, context) ->
                        context.server().execute(() -> {
                            LinkedHashSet<String> fakeNames =
                                    new LinkedHashSet<>();
                            context.server().getPlayerList().getPlayers()
                                    .stream()
                                    .filter(EntityPlayerMPFake.class::isInstance)
                                    .map(player -> player.getGameProfile()
                                            .name())
                                    .forEach(fakeNames::add);
                            context.server().getAllLevels().forEach(level ->
                                    level.players().stream()
                                            .filter(EntityPlayerMPFake.class
                                                    ::isInstance)
                                            .map(player -> player
                                                    .getGameProfile()
                                                    .name())
                                            .forEach(fakeNames::add));
                            BARITONES.snapshot().stream()
                                    .map(instance -> instance
                                            .getPlayerContext().player())
                                    .filter(EntityPlayerMPFake.class::isInstance)
                                    .map(player -> player.getGameProfile()
                                            .name())
                                    .forEach(fakeNames::add);
                            List<String> fakePlayers = fakeNames.stream()
                                    .sorted().toList();
                            List<String> onlinePlayers = context.server()
                                    .getPlayerList().getPlayers().stream()
                                    .map(player -> player.getGameProfile()
                                            .name())
                                    .sorted().toList();
                            List<ControlOptionsPayload.WaypointOption>
                                    waypoints = BARITONES.snapshot().stream()
                                    .flatMap(instance -> instance
                                            .getWorldProvider()
                                            .getCurrentWorld().getWaypoints()
                                            .getAllWaypoints().stream()
                                            .map(waypoint -> new
                                                    ControlOptionsPayload
                                                            .WaypointOption(
                                                    instance.getPlayerContext()
                                                            .player()
                                                            .getScoreboardName(),
                                                    waypoint.getName(),
                                                    waypoint.getTag().getName(),
                                                    waypoint.getLocation().x,
                                                    waypoint.getLocation().y,
                                                    waypoint.getLocation().z)))
                                    .toList();
                            ServerPlayNetworking.send(context.player(),
                                    new ControlOptionsPayload(
                                            fakePlayers, onlinePlayers,
                                            schematicFiles(),
                                            SyncmaticaBridge.list(context.server())
                                                    .stream().map(value -> new
                                                            ControlOptionsPayload.SyncmaticaOption(
                                                            value.id().toString(),
                                                            value.name(),
                                                            value.dimension(),
                                                            value.origin().getX(),
                                                            value.origin().getY(),
                                                            value.origin().getZ(),
                                                            value.rotation().name(),
                                                            value.mirror().name()))
                                                    .toList(),
                                            SettingOptions.snapshot(),
                                            waypoints));
                        }));
        ServerPlayNetworking.registerGlobalReceiver(
                CommandSubmitPayload.TYPE, (payload, context) ->
                        context.server().execute(() -> {
                            var fake = context.server().getPlayerList()
                                    .getPlayerByName(payload.fakePlayer());
                            BasicGoalCommandHandler.ExecutionResult result =
                                    fake == null
                                    ? new BasicGoalCommandHandler
                                            .ExecutionResult(false,
                                            "错误: 找不到假人 "
                                                    + payload.fakePlayer())
                                    : BasicGoalCommandHandler.executeDirect(
                                            context.player(), fake,
                                            payload.command());
                            ServerPlayNetworking.send(context.player(),
                                    new CommandResultPayload(
                                            result.success(),
                                            result.message()));
                        }));
        BaritoneAPI.setProvider(new ServerBaritoneProvider(BARITONES));
        ServerTickEvents.END_SERVER_TICK.register(BARITONES::tick);
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> BARITONES.clear());
        PlayerBlockBreakEvents.AFTER.register((world, player, pos, state, entity) -> {
            if (Baritone.settings().repackOnAnyBlockChange.value
                    && world instanceof ServerLevel level) {
                var cache = ServerWorldCache.get(level);
                var changedChunk = level.getChunkSource().getChunkNow(
                        pos.getX() >> 4, pos.getZ() >> 4);
                if (changedChunk != null) {
                    cache.updateBlock(pos, level.getBlockState(pos),
                            changedChunk);
                }
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
                var cache = ServerWorldCache.get(level);
                var changedChunk = level.getChunkSource().getChunkNow(
                        hit.getBlockPos().getX() >> 4,
                        hit.getBlockPos().getZ() >> 4);
                if (changedChunk != null) {
                    cache.updateBlock(hit.getBlockPos(),
                            level.getBlockState(hit.getBlockPos()),
                            changedChunk);
                }
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

    private static List<String> schematicFiles() {
        Path root = Path.of("schematics").toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) return List.of();
        try (var files = Files.walk(root, 8)) {
            return files.filter(Files::isRegularFile)
                    .filter(path -> SchematicSystem.INSTANCE
                            .getByFile(path.toFile()).isPresent())
                    // Bound work performed by a client options request even
                    // when the server directory contains an unusually large
                    // schematic archive.
                    .limit(4096)
                    .map(root::relativize)
                    .map(path -> path.toString().replace('\\', '/'))
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .limit(1024)
                    .toList();
        } catch (IOException ignored) {
            return List.of();
        }
    }
}
