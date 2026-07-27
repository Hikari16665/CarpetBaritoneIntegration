package me.nuoyuan.carpetbaritoneintegration.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import me.nuoyuan.carpetbaritoneintegration.network.PathSnapshotPayload;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client-only renderer modeled after upstream PathRenderer:
 * current path red, next path magenta, goal box green.
 */
public final class ClientPathRenderer {
    private static final long EXPIRY_MILLIS = 3_000L;
    private static final Map<UUID, CachedSnapshot> SNAPSHOTS =
            new ConcurrentHashMap<>();

    private ClientPathRenderer() { }

    public static void accept(PathSnapshotPayload payload) {
        if (!payload.active()) {
            SNAPSHOTS.remove(payload.fakePlayerId());
            return;
        }
        SNAPSHOTS.compute(payload.fakePlayerId(), (id, existing) ->
                existing == null
                        || payload.sequence() >= existing.payload.sequence()
                        ? new CachedSnapshot(payload,
                        System.currentTimeMillis()) : existing);
    }

    public static void clear() {
        SNAPSHOTS.clear();
    }

    public static void render(WorldRenderContext context) {
        Minecraft client = Minecraft.getInstance();
        if (client.level == null) {
            clear();
            return;
        }
        PoseStack matrices = context.matrixStack();
        MultiBufferSource consumers = context.consumers();
        if (matrices == null || consumers == null) return;
        long now = System.currentTimeMillis();
        SNAPSHOTS.entrySet().removeIf(entry ->
                now - entry.getValue().receivedAt > EXPIRY_MILLIS);
        String dimension =
                client.level.dimension().location().toString();
        Vec3 camera = context.camera().getPosition();
        VertexConsumer lines = consumers.getBuffer(RenderType.lines());
        PoseStack.Pose pose = matrices.last();
        for (CachedSnapshot cached : SNAPSHOTS.values()) {
            PathSnapshotPayload snapshot = cached.payload;
            if (!dimension.equals(snapshot.dimension())) continue;
            drawPath(lines, pose, camera,
                    snapshot.currentPath(), 255, 0, 0, 210);
            drawPath(lines, pose, camera,
                    snapshot.nextPath(), 255, 0, 255, 190);
            drawPath(lines, pose, camera,
                    snapshot.bestPathSoFar(), 0, 128, 255, 190);
            drawPath(lines, pose, camera,
                    snapshot.mostRecentConsidered(), 0, 255, 255, 170);
            drawBoxes(lines, pose, camera,
                    snapshot.blocksToBreak(), 255, 64, 64, 220);
            drawBoxes(lines, pose, camera,
                    snapshot.blocksToPlace(), 64, 128, 255, 220);
            drawBoxes(lines, pose, camera,
                    snapshot.blocksToWalkInto(), 255, 170, 0, 210);
            drawSelections(lines, pose, camera,
                    snapshot.selectionCorners());
            if (snapshot.goal() != null) {
                drawGoal(lines, pose, camera, snapshot.goal());
            }
        }
    }

    private static void drawPath(
            VertexConsumer consumer, PoseStack.Pose pose, Vec3 camera,
            List<BlockPos> positions,
            int red, int green, int blue, int alpha) {
        for (int index = 0; index + 1 < positions.size(); index++) {
            BlockPos from = positions.get(index);
            BlockPos to = positions.get(index + 1);
            line(consumer, pose, camera,
                    from.getX() + 0.5D, from.getY() + 0.53D,
                    from.getZ() + 0.5D,
                    to.getX() + 0.5D, to.getY() + 0.53D,
                    to.getZ() + 0.5D,
                    red, green, blue, alpha);
        }
    }

    private static void drawGoal(
            VertexConsumer consumer, PoseStack.Pose pose,
            Vec3 camera, BlockPos goal) {
        drawBox(consumer, pose, camera, goal,
                0.03D, 0.97D, 0.03D, 1.97D,
                0, 255, 0, 220);
    }

    private static void drawBoxes(
            VertexConsumer consumer, PoseStack.Pose pose, Vec3 camera,
            List<BlockPos> positions,
            int red, int green, int blue, int alpha) {
        for (BlockPos position : positions) {
            drawBox(consumer, pose, camera, position,
                    0.01D, 0.99D, 0.01D, 0.99D,
                    red, green, blue, alpha);
        }
    }

    private static void drawSelections(
            VertexConsumer consumer, PoseStack.Pose pose, Vec3 camera,
            List<BlockPos> corners) {
        for (int index = 0; index + 1 < corners.size(); index += 2) {
            BlockPos min = corners.get(index);
            BlockPos max = corners.get(index + 1);
            drawBounds(consumer, pose, camera,
                    min.getX() + 0.002D, min.getY() + 0.002D,
                    min.getZ() + 0.002D,
                    max.getX() + 0.998D, max.getY() + 0.998D,
                    max.getZ() + 0.998D,
                    255, 255, 255, 210);
        }
    }

    private static void drawBox(
            VertexConsumer consumer, PoseStack.Pose pose,
            Vec3 camera, BlockPos goal,
            double horizontalMin, double horizontalMax,
            double verticalMin, double verticalMax,
            int red, int green, int blue, int alpha) {
        double minX = goal.getX() + horizontalMin;
        double minY = goal.getY() + verticalMin;
        double minZ = goal.getZ() + horizontalMin;
        double maxX = goal.getX() + horizontalMax;
        double maxY = goal.getY() + verticalMax;
        double maxZ = goal.getZ() + horizontalMax;
        drawBounds(consumer, pose, camera,
                minX, minY, minZ, maxX, maxY, maxZ,
                red, green, blue, alpha);
    }

    private static void drawBounds(
            VertexConsumer consumer, PoseStack.Pose pose,
            Vec3 camera,
            double minX, double minY, double minZ,
            double maxX, double maxY, double maxZ,
            int red, int green, int blue, int alpha) {
        double[][] corners = {
                {minX, minY, minZ}, {maxX, minY, minZ},
                {maxX, minY, maxZ}, {minX, minY, maxZ},
                {minX, maxY, minZ}, {maxX, maxY, minZ},
                {maxX, maxY, maxZ}, {minX, maxY, maxZ}
        };
        int[][] edges = {
                {0, 1}, {1, 2}, {2, 3}, {3, 0},
                {4, 5}, {5, 6}, {6, 7}, {7, 4},
                {0, 4}, {1, 5}, {2, 6}, {3, 7}
        };
        for (int[] edge : edges) {
            double[] from = corners[edge[0]];
            double[] to = corners[edge[1]];
            line(consumer, pose, camera,
                    from[0], from[1], from[2],
                    to[0], to[1], to[2],
                    red, green, blue, alpha);
        }
    }

    private static void line(
            VertexConsumer consumer, PoseStack.Pose pose, Vec3 camera,
            double x1, double y1, double z1,
            double x2, double y2, double z2,
            int red, int green, int blue, int alpha) {
        float dx = (float) (x2 - x1);
        float dy = (float) (y2 - y1);
        float dz = (float) (z2 - z1);
        float length = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (length <= 1.0E-5F) return;
        dx /= length;
        dy /= length;
        dz /= length;
        consumer.addVertex(pose,
                        (float) (x1 - camera.x),
                        (float) (y1 - camera.y),
                        (float) (z1 - camera.z))
                .setColor(red, green, blue, alpha)
                .setNormal(pose, dx, dy, dz);
        consumer.addVertex(pose,
                        (float) (x2 - camera.x),
                        (float) (y2 - camera.y),
                        (float) (z2 - camera.z))
                .setColor(red, green, blue, alpha)
                .setNormal(pose, dx, dy, dz);
    }

    private record CachedSnapshot(
            PathSnapshotPayload payload, long receivedAt) { }
}
