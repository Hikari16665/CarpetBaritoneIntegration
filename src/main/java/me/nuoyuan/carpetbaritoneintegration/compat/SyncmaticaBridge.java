package me.nuoyuan.carpetbaritoneintegration.compat;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;

import java.io.File;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Optional, reflection-only bridge to Syncmatica. Keeping this class free of
 * compile-time Syncmatica references lets dedicated servers run CBI with or
 * without the mod installed (and tolerates minor Syncmatica version changes).
 */
public final class SyncmaticaBridge {
    private static final String SYNCMATICA = "ch.endte.syncmatica.Syncmatica";

    private SyncmaticaBridge() { }

    public record SharedSchematic(
            UUID id, String name, String dimension, BlockPos origin,
            Rotation rotation, Mirror mirror, File file) { }

    public static List<SharedSchematic> list(MinecraftServer server) {
        if (server == null) return List.of();
        try {
            Class<?> root = Class.forName(SYNCMATICA);
            Object serverKey = root.getField("SERVER_CONTEXT").get(null);
            Object context = invokeStatic(root, "getContext",
                    new Class<?>[]{Identifier.class}, serverKey);
            if (context == null) return List.of();
            Object manager = invoke(context, "getSyncmaticManager");
            Object storage = invoke(context, "getFileStorage");
            if (manager == null || storage == null) return List.of();
            Object values = invoke(manager, "getAll");
            if (!(values instanceof Iterable<?> placements)) return List.of();

            List<SharedSchematic> result = new ArrayList<>();
            for (Object placement : placements) {
                SharedSchematic value = readPlacement(storage, placement);
                if (value != null && value.file().isFile()) result.add(value);
                if (result.size() >= 1024) break;
            }
            result.sort(Comparator.comparing(SharedSchematic::name,
                    String.CASE_INSENSITIVE_ORDER).thenComparing(
                    value -> value.id().toString()));
            return List.copyOf(result);
        } catch (ClassNotFoundException ignored) {
            return List.of();
        } catch (ReflectiveOperationException | LinkageError exception) {
            System.err.println("[CBI] Syncmatica compatibility unavailable: "
                    + exception.getMessage());
            return List.of();
        }
    }

    public static Optional<SharedSchematic> find(
            MinecraftServer server, UUID id) {
        return list(server).stream().filter(value ->
                value.id().equals(id)).findFirst();
    }

    private static SharedSchematic readPlacement(
            Object storage, Object placement) throws ReflectiveOperationException {
        UUID id = (UUID) invoke(placement, "getId");
        String name = String.valueOf(invoke(placement, "getName"));
        if (name.isBlank() || name.equals("null")) {
            name = String.valueOf(invoke(placement, "getFileName"));
        }
        String dimension = String.valueOf(invoke(placement, "getDimension"));
        BlockPos origin = (BlockPos) invoke(placement, "getPosition");
        Rotation rotation = enumValue(Rotation.class,
                invoke(placement, "getRotation"), Rotation.NONE);
        Mirror mirror = enumValue(Mirror.class,
                invoke(placement, "getMirror"), Mirror.NONE);
        Object local = invoke(placement, "getFile");
        Path path = local instanceof Path value ? value : null;
        if (path == null || !path.toFile().isFile()) {
            local = invoke(storage, "getLocalLitematic",
                    placement.getClass(), placement);
            if (!(local instanceof Path fallback)) return null;
            path = fallback;
        }
        return new SharedSchematic(id, name, dimension, origin,
                rotation, mirror, path.toFile());
    }

    private static <E extends Enum<E>> E enumValue(
            Class<E> type, Object value, E fallback) {
        if (value == null) return fallback;
        try {
            return Enum.valueOf(type, ((Enum<?>) value).name());
        } catch (IllegalArgumentException exception) {
            return fallback;
        }
    }

    private static Object invoke(Object target, String name)
            throws ReflectiveOperationException {
        return target.getClass().getMethod(name).invoke(target);
    }

    private static Object invoke(
            Object target, String name, Class<?> parameter, Object value)
            throws ReflectiveOperationException {
        Method method = target.getClass().getMethod(name, parameter);
        return method.invoke(target, value);
    }

    private static Object invokeStatic(
            Class<?> target, String name, Class<?>[] parameters, Object value)
            throws ReflectiveOperationException {
        return target.getMethod(name, parameters).invoke(null, value);
    }
}
