package me.nuoyuan.carpetbaritoneintegration.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Compact server-to-client snapshot of one fake player's visible path. */
public record PathSnapshotPayload(
        UUID fakePlayerId,
        String fakePlayerName,
        String dimension,
        String process,
        boolean active,
        List<BlockPos> currentPath,
        List<BlockPos> nextPath,
        List<BlockPos> bestPathSoFar,
        List<BlockPos> mostRecentConsidered,
        List<BlockPos> blocksToBreak,
        List<BlockPos> blocksToPlace,
        List<BlockPos> blocksToWalkInto,
        List<BlockPos> selectionCorners,
        RenderSettings renderSettings,
        List<GoalRender> goals,
        BlockPos goal,
        long sequence
) implements CustomPacketPayload {
    public static final Type<PathSnapshotPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(
                    "carpetbaritoneintegration", "path_snapshot"));
    public static final StreamCodec<RegistryFriendlyByteBuf,
            PathSnapshotPayload> STREAM_CODEC =
            CustomPacketPayload.codec(
                    PathSnapshotPayload::write,
                    PathSnapshotPayload::new);

    private PathSnapshotPayload(RegistryFriendlyByteBuf buffer) {
        this(
                buffer.readUUID(),
                buffer.readUtf(64),
                buffer.readUtf(128),
                buffer.readUtf(128),
                buffer.readBoolean(),
                readPositions(buffer),
                readPositions(buffer),
                readPositions(buffer),
                readPositions(buffer),
                readPositions(buffer),
                readPositions(buffer),
                readPositions(buffer),
                readPositions(buffer),
                RenderSettings.read(buffer),
                readGoals(buffer),
                buffer.readBoolean() ? buffer.readBlockPos() : null,
                buffer.readVarLong());
    }

    private void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeUUID(fakePlayerId);
        buffer.writeUtf(fakePlayerName, 64);
        buffer.writeUtf(dimension, 128);
        buffer.writeUtf(process, 128);
        buffer.writeBoolean(active);
        writePositions(buffer, currentPath);
        writePositions(buffer, nextPath);
        writePositions(buffer, bestPathSoFar);
        writePositions(buffer, mostRecentConsidered);
        writePositions(buffer, blocksToBreak);
        writePositions(buffer, blocksToPlace);
        writePositions(buffer, blocksToWalkInto);
        writePositions(buffer, selectionCorners);
        renderSettings.write(buffer);
        writeGoals(buffer, goals);
        buffer.writeBoolean(goal != null);
        if (goal != null) buffer.writeBlockPos(goal);
        buffer.writeVarLong(sequence);
    }

    private static List<BlockPos> readPositions(
            RegistryFriendlyByteBuf buffer) {
        int encodedCount = buffer.readVarInt();
        int retainedCount = Math.min(1024, encodedCount);
        List<BlockPos> result = new ArrayList<>(retainedCount);
        for (int index = 0; index < encodedCount; index++) {
            BlockPos position = buffer.readBlockPos();
            if (index < retainedCount) result.add(position);
        }
        return List.copyOf(result);
    }

    private static void writePositions(
            RegistryFriendlyByteBuf buffer, List<BlockPos> positions) {
        int count = Math.min(1024, positions.size());
        buffer.writeVarInt(count);
        for (int index = 0; index < count; index++) {
            buffer.writeBlockPos(positions.get(index));
        }
    }

    private static List<GoalRender> readGoals(
            RegistryFriendlyByteBuf buffer) {
        int encodedCount = buffer.readVarInt();
        int retainedCount = Math.min(256, encodedCount);
        List<GoalRender> result = new ArrayList<>(retainedCount);
        for (int index = 0; index < encodedCount; index++) {
            GoalRender value = GoalRender.read(buffer);
            if (index < retainedCount) result.add(value);
        }
        return List.copyOf(result);
    }

    private static void writeGoals(
            RegistryFriendlyByteBuf buffer, List<GoalRender> goals) {
        int count = Math.min(256, goals.size());
        buffer.writeVarInt(count);
        for (int index = 0; index < count; index++) {
            goals.get(index).write(buffer);
        }
    }

    @Override public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public record RenderSettings(
            boolean renderPath,
            boolean renderPathAsLine,
            boolean renderGoal,
            boolean renderSelectionBoxes,
            boolean renderSelection,
            boolean fadePath,
            double yLevelBoxSize,
            int currentPathColor,
            int nextPathColor,
            int bestPathColor,
            int recentPathColor,
            int breakColor,
            int placeColor,
            int walkIntoColor,
            int goalColor,
            int invertedGoalColor,
            int selectionColor) {
        private static RenderSettings read(RegistryFriendlyByteBuf buffer) {
            return new RenderSettings(
                    buffer.readBoolean(), buffer.readBoolean(),
                    buffer.readBoolean(), buffer.readBoolean(),
                    buffer.readBoolean(), buffer.readBoolean(),
                    buffer.readDouble(),
                    buffer.readInt(), buffer.readInt(),
                    buffer.readInt(), buffer.readInt(),
                    buffer.readInt(), buffer.readInt(),
                    buffer.readInt(), buffer.readInt(),
                    buffer.readInt(), buffer.readInt());
        }

        private void write(RegistryFriendlyByteBuf buffer) {
            buffer.writeBoolean(renderPath);
            buffer.writeBoolean(renderPathAsLine);
            buffer.writeBoolean(renderGoal);
            buffer.writeBoolean(renderSelectionBoxes);
            buffer.writeBoolean(renderSelection);
            buffer.writeBoolean(fadePath);
            buffer.writeDouble(yLevelBoxSize);
            buffer.writeInt(currentPathColor);
            buffer.writeInt(nextPathColor);
            buffer.writeInt(bestPathColor);
            buffer.writeInt(recentPathColor);
            buffer.writeInt(breakColor);
            buffer.writeInt(placeColor);
            buffer.writeInt(walkIntoColor);
            buffer.writeInt(goalColor);
            buffer.writeInt(invertedGoalColor);
            buffer.writeInt(selectionColor);
        }
    }

    public record GoalRender(
            GoalKind kind, BlockPos position, boolean inverted) {
        private static GoalRender read(RegistryFriendlyByteBuf buffer) {
            int ordinal = buffer.readUnsignedByte();
            GoalKind[] values = GoalKind.values();
            GoalKind kind = ordinal < values.length
                    ? values[ordinal] : GoalKind.BLOCK_TWO_HIGH;
            return new GoalRender(
                    kind, buffer.readBlockPos(), buffer.readBoolean());
        }

        private void write(RegistryFriendlyByteBuf buffer) {
            buffer.writeByte(kind.ordinal());
            buffer.writeBlockPos(position);
            buffer.writeBoolean(inverted);
        }
    }

    public enum GoalKind {
        BLOCK_TWO_HIGH,
        BLOCK_ONE_HIGH,
        XZ_COLUMN,
        Y_LEVEL
    }
}
