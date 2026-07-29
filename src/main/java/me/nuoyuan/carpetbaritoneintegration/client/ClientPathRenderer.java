package me.nuoyuan.carpetbaritoneintegration.client;

import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import me.nuoyuan.carpetbaritoneintegration.network.PathSnapshotPayload;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Minecraft 26.2 path renderer. Rendering is split into Fabric's extraction
 * and drawing phases so the draw thread never accesses mutable world data.
 */
public final class ClientPathRenderer {
    private static final long EXPIRY_MILLIS = 3_000L;
    private static final Map<UUID, CachedSnapshot> SNAPSHOTS =
            new ConcurrentHashMap<>();
    private static final StagedVertexBuffer BUFFER =
            new StagedVertexBuffer(() -> "CBI path overlay",
                    RenderType.SMALL_BUFFER_SIZE);
    private static final Vector4f COLOR_MODULATOR =
            new Vector4f(1F, 1F, 1F, 1F);
    private static final Vector3f MODEL_OFFSET = new Vector3f();
    private static final Matrix4f TEXTURE_MATRIX = new Matrix4f();
    private static volatile ExtractedState extracted = ExtractedState.EMPTY;

    private ClientPathRenderer() {
    }

    public static void initialize() {
        LevelExtractionEvents.END_EXTRACTION.register(
                ClientPathRenderer::extract);
        LevelRenderEvents.AFTER_TRANSLUCENT_TERRAIN.register(
                ClientPathRenderer::render);
    }

    public static void accept(PathSnapshotPayload payload) {
        if (!payload.active()) {
            SNAPSHOTS.remove(payload.fakePlayerId());
            return;
        }
        SNAPSHOTS.compute(payload.fakePlayerId(), (id, previous) ->
                previous == null
                        || payload.sequence() >= previous.payload.sequence()
                        ? new CachedSnapshot(payload,
                        System.currentTimeMillis()) : previous);
    }

    public static void clear() {
        SNAPSHOTS.clear();
        extracted = ExtractedState.EMPTY;
    }

    public static void close() {
        BUFFER.close();
    }

    private static void extract(LevelExtractionContext context) {
        Minecraft client = Minecraft.getInstance();
        if (client.level == null) {
            clear();
            return;
        }
        long now = System.currentTimeMillis();
        SNAPSHOTS.entrySet().removeIf(entry ->
                now - entry.getValue().receivedAt > EXPIRY_MILLIS);
        String dimension =
                client.level.dimension().identifier().toString();
        List<PathSnapshotPayload> visible = new ArrayList<>();
        for (CachedSnapshot cached : SNAPSHOTS.values()) {
            if (dimension.equals(cached.payload.dimension())) {
                visible.add(cached.payload);
            }
        }
        extracted = new ExtractedState(List.copyOf(visible),
                client.level.getMinY(), client.level.getMaxY());
    }

    private static void render(LevelRenderContext context) {
        ExtractedState state = extracted;
        if (state.snapshots.isEmpty()) return;
        RenderPipeline pipeline = RenderPipelines.LINES_TRANSLUCENT;
        VertexFormat format = pipeline.getVertexFormatBinding(0);
        if (format == null) return;
        PrimitiveTopology primitive = pipeline.getPrimitiveTopology();
        StagedVertexBuffer.Draw draw = BUFFER.appendDraw(format, primitive,
                primitive == PrimitiveTopology.QUADS
                        ? RenderSystem.getProjectionType().vertexSorting()
                        : null);
        VertexConsumer consumer = BUFFER.getVertexBuilder(draw);
        PoseStack matrices = context.poseStack();
        PoseStack.Pose pose = matrices.last();
        Vec3 camera = context.levelState().cameraRenderState.pos;

        for (PathSnapshotPayload snapshot : state.snapshots) {
            drawSnapshot(consumer, pose, camera, snapshot,
                    state.minY, state.maxY);
        }
        BUFFER.upload();
        StagedVertexBuffer.ExecuteInfo info = BUFFER.getExecuteInfo(draw);
        if (info != null) execute(info, pipeline);
        BUFFER.endFrame();
    }

    private static void drawSnapshot(VertexConsumer consumer,
            PoseStack.Pose pose, Vec3 camera,
            PathSnapshotPayload snapshot, int minY, int maxY) {
        PathSnapshotPayload.RenderSettings settings =
                snapshot.renderSettings();
        if (settings.renderPath()) {
            path(consumer, pose, camera, snapshot.currentPath(),
                    settings.currentPathColor(), 210, settings.fadePath());
            path(consumer, pose, camera, snapshot.nextPath(),
                    settings.nextPathColor(), 190, settings.fadePath());
            path(consumer, pose, camera, snapshot.bestPathSoFar(),
                    settings.bestPathColor(), 190, settings.fadePath());
            path(consumer, pose, camera, snapshot.mostRecentConsidered(),
                    settings.recentPathColor(), 170, settings.fadePath());
        }
        if (settings.renderSelectionBoxes()) {
            boxes(consumer, pose, camera, snapshot.blocksToBreak(),
                    settings.breakColor(), 220);
            boxes(consumer, pose, camera, snapshot.blocksToPlace(),
                    settings.placeColor(), 220);
            boxes(consumer, pose, camera, snapshot.blocksToWalkInto(),
                    settings.walkIntoColor(), 210);
        }
        if (settings.renderSelection()) {
            selections(consumer, pose, camera,
                    snapshot.selectionCorners(), settings.selectionColor());
        }
        if (!settings.renderGoal()) return;
        if (!snapshot.goals().isEmpty()) {
            for (PathSnapshotPayload.GoalRender goal : snapshot.goals()) {
                goal(consumer, pose, camera, goal, settings, minY, maxY);
            }
        } else if (snapshot.goal() != null) {
            box(consumer, pose, camera, snapshot.goal(),
                    0.03, 0.97, 0.03, 1.97,
                    settings.goalColor(), 220);
        }
    }

    private static void path(VertexConsumer consumer, PoseStack.Pose pose,
            Vec3 camera, List<BlockPos> positions, int color,
            int baseAlpha, boolean fade) {
        for (int i = 0; i + 1 < positions.size(); i++) {
            int alpha = fade && i > 10
                    ? Math.max(0, baseAlpha
                    * (20 - Math.min(20, i)) / 10) : baseAlpha;
            if (alpha == 0) break;
            BlockPos from = positions.get(i);
            BlockPos to = positions.get(i + 1);
            line(consumer, pose, camera,
                    from.getX() + 0.5, from.getY() + 0.53,
                    from.getZ() + 0.5, to.getX() + 0.5,
                    to.getY() + 0.53, to.getZ() + 0.5, color, alpha);
        }
    }

    private static void boxes(VertexConsumer consumer, PoseStack.Pose pose,
            Vec3 camera, List<BlockPos> positions, int color, int alpha) {
        for (BlockPos pos : positions) {
            box(consumer, pose, camera, pos,
                    0.01, 0.99, 0.01, 0.99, color, alpha);
        }
    }

    private static void selections(VertexConsumer consumer,
            PoseStack.Pose pose, Vec3 camera,
            List<BlockPos> corners, int color) {
        for (int i = 0; i + 1 < corners.size(); i += 2) {
            BlockPos min = corners.get(i);
            BlockPos max = corners.get(i + 1);
            bounds(consumer, pose, camera,
                    min.getX() + 0.002, min.getY() + 0.002,
                    min.getZ() + 0.002, max.getX() + 0.998,
                    max.getY() + 0.998, max.getZ() + 0.998, color, 210);
        }
    }

    private static void goal(VertexConsumer consumer, PoseStack.Pose pose,
            Vec3 camera, PathSnapshotPayload.GoalRender goal,
            PathSnapshotPayload.RenderSettings settings,
            int minY, int maxY) {
        BlockPos pos = goal.position();
        int color = goal.inverted()
                ? settings.invertedGoalColor() : settings.goalColor();
        switch (goal.kind()) {
            case BLOCK_TWO_HIGH -> box(consumer, pose, camera, pos,
                    0.03, 0.97, 0.03, 1.97, color, 220);
            case BLOCK_ONE_HIGH -> box(consumer, pose, camera, pos,
                    0.03, 0.97, 0.03, 0.97, color, 220);
            case XZ_COLUMN -> bounds(consumer, pose, camera,
                    pos.getX() + 0.03, minY, pos.getZ() + 0.03,
                    pos.getX() + 0.97, maxY, pos.getZ() + 0.97,
                    color, 220);
            case Y_LEVEL -> {
                double radius = settings.yLevelBoxSize();
                bounds(consumer, pose, camera,
                        pos.getX() + 0.5 - radius, pos.getY() + 0.03,
                        pos.getZ() + 0.5 - radius,
                        pos.getX() + 0.5 + radius, pos.getY() + 1.97,
                        pos.getZ() + 0.5 + radius, color, 220);
            }
        }
    }

    private static void box(VertexConsumer consumer, PoseStack.Pose pose,
            Vec3 camera, BlockPos pos, double hMin, double hMax,
            double vMin, double vMax, int color, int alpha) {
        bounds(consumer, pose, camera,
                pos.getX() + hMin, pos.getY() + vMin, pos.getZ() + hMin,
                pos.getX() + hMax, pos.getY() + vMax, pos.getZ() + hMax,
                color, alpha);
    }

    private static void bounds(VertexConsumer consumer, PoseStack.Pose pose,
            Vec3 camera, double minX, double minY, double minZ,
            double maxX, double maxY, double maxZ, int color, int alpha) {
        double[][] c = {{minX, minY, minZ}, {maxX, minY, minZ},
                {maxX, minY, maxZ}, {minX, minY, maxZ},
                {minX, maxY, minZ}, {maxX, maxY, minZ},
                {maxX, maxY, maxZ}, {minX, maxY, maxZ}};
        int[][] edges = {{0, 1}, {1, 2}, {2, 3}, {3, 0},
                {4, 5}, {5, 6}, {6, 7}, {7, 4},
                {0, 4}, {1, 5}, {2, 6}, {3, 7}};
        for (int[] edge : edges) {
            double[] a = c[edge[0]];
            double[] b = c[edge[1]];
            line(consumer, pose, camera, a[0], a[1], a[2],
                    b[0], b[1], b[2], color, alpha);
        }
    }

    private static void line(VertexConsumer consumer, PoseStack.Pose pose,
            Vec3 camera, double x1, double y1, double z1,
            double x2, double y2, double z2, int color, int alpha) {
        float dx = (float) (x2 - x1);
        float dy = (float) (y2 - y1);
        float dz = (float) (z2 - z1);
        float length = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (length <= 1.0E-5F) return;
        dx /= length;
        dy /= length;
        dz /= length;
        consumer.addVertex(pose, (float) (x1 - camera.x),
                        (float) (y1 - camera.y),
                        (float) (z1 - camera.z))
                .setColor(red(color), green(color), blue(color), alpha)
                .setNormal(pose, dx, dy, dz).setLineWidth(2F);
        consumer.addVertex(pose, (float) (x2 - camera.x),
                        (float) (y2 - camera.y),
                        (float) (z2 - camera.z))
                .setColor(red(color), green(color), blue(color), alpha)
                .setNormal(pose, dx, dy, dz).setLineWidth(2F);
    }

    private static void execute(StagedVertexBuffer.ExecuteInfo info,
            RenderPipeline pipeline) {
        Minecraft client = Minecraft.getInstance();
        GpuBufferSlice transforms = RenderSystem.getDynamicUniforms()
                .writeTransform(RenderSystem.getModelViewMatrixCopy(),
                        COLOR_MODULATOR, MODEL_OFFSET, TEXTURE_MATRIX);
        RenderTarget target = client.gameRenderer.mainRenderTarget();
        GpuTextureView color = target.getColorTextureView();
        if (color == null) return;
        try (RenderPass pass = RenderSystem.getDevice()
                .createCommandEncoder().createRenderPass(
                        () -> "CBI path overlay", color, Optional.empty(),
                        target.getDepthTextureView(),
                        OptionalDouble.empty())) {
            pass.setPipeline(pipeline);
            RenderSystem.bindDefaultUniforms(pass);
            pass.setUniform("DynamicTransforms", transforms);
            pass.setVertexBuffer(0, info.vertexBuffer().slice());
            pass.setIndexBuffer(info.indexBuffer(), info.indexType());
            pass.drawIndexed(info.indexCount(), 1, info.firstIndex(),
                    info.baseVertex(), 0);
        }
    }

    private static int red(int color) {
        return color >> 16 & 0xFF;
    }

    private static int green(int color) {
        return color >> 8 & 0xFF;
    }

    private static int blue(int color) {
        return color & 0xFF;
    }

    private record CachedSnapshot(
            PathSnapshotPayload payload, long receivedAt) {
    }

    private record ExtractedState(
            List<PathSnapshotPayload> snapshots, int minY, int maxY) {
        private static final ExtractedState EMPTY =
                new ExtractedState(List.of(), 0, 0);
    }
}
