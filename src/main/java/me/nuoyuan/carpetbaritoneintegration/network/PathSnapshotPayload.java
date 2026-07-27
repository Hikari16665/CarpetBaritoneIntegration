package me.nuoyuan.carpetbaritoneintegration.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

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
        BlockPos goal,
        long sequence
) implements CustomPacketPayload {
    public static final Type<PathSnapshotPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(
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

    @Override public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
