package baritone.api.utils;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import java.util.HashMap;
import java.util.Map;

public final class BlockUtils {
    private static volatile Map<String, Block> cache = Map.of();
    private BlockUtils() { }
    public static String blockToString(Block block) {
        Identifier id = BuiltInRegistries.BLOCK.getKey(block);
        return id.getNamespace().equals("minecraft") ? id.getPath() : id.toString();
    }
    public static Block stringToBlockRequired(String name) {
        Block result = stringToBlockNullable(name);
        if (result == null) throw new IllegalArgumentException("Invalid block name " + name);
        return result;
    }
    public static Block stringToBlockNullable(String name) {
        if (cache.containsKey(name)) return cache.get(name);
        Identifier id = Identifier.tryParse(
                name.contains(":") ? name : "minecraft:" + name);
        Block result = id == null ? null : BuiltInRegistries.BLOCK.getOptional(id).orElse(null);
        Map<String, Block> updated = new HashMap<>(cache);
        updated.put(name, result);
        cache = updated;
        return result;
    }
}
