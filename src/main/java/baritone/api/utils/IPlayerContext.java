/*
 * Server-side Baritone fork.
 * Derived from Baritone, licensed under LGPL-3.0.
 */
package baritone.api.utils;

import baritone.api.cache.IWorldData;
import baritone.cache.WorldData;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Optional;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * The server-side view of one Carpet fake player and its world.
 *
 * <p>This replaces upstream's {@code Minecraft}/{@code LocalPlayer} context.
 * World and block queries are performed directly against {@link ServerLevel}.</p>
 */
public interface IPlayerContext {

    MinecraftServer server();

    ServerPlayer player();

    ServerLevel world();

    default IWorldData worldData() {
        return new WorldData(world());
    }

    /**
     * Entities visible to avoidance and follow calculations.
     * Implementations may return a bounded collection around the player.
     */
    Iterable<Entity> entities();

    default Stream<Entity> entitiesStream() {
        return StreamSupport.stream(entities().spliterator(), false);
    }

    /**
     * Server-side ray trace result based on the fake player's current rotation.
     */
    HitResult objectMouseOver();

    default BetterBlockPos playerFeet() {
        BetterBlockPos feet = new BetterBlockPos(
                player().position().x,
                player().position().y + 0.1251D,
                player().position().z
        );
        if (world().getBlockState(feet).getBlock() instanceof SlabBlock) {
            return feet.above();
        }
        return feet;
    }

    default Vec3 playerFeetAsVec() {
        return player().position();
    }

    default Vec3 playerHead() {
        Vec3 position = player().position();
        return new Vec3(position.x, position.y + player().getEyeHeight(), position.z);
    }

    default Vec3 playerMotion() {
        return player().getDeltaMovement();
    }

    default BetterBlockPos viewerPos() {
        return BetterBlockPos.from(player().blockPosition());
    }

    default Rotation playerRotations() {
        return new Rotation(player().getYRot(), player().getXRot());
    }

    default Optional<BlockPos> getSelectedBlock() {
        HitResult result = objectMouseOver();
        if (result != null && result.getType() == HitResult.Type.BLOCK) {
            return Optional.of(((BlockHitResult) result).getBlockPos());
        }
        return Optional.empty();
    }

    default boolean isLookingAt(BlockPos pos) {
        return getSelectedBlock().equals(Optional.of(pos));
    }
}
