package me.nuoyuan.carpetbaritoneintegration.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** S2C authoritative OVERLOAD MODE state and permission view. */
public record OverloadStatePayload(
        boolean enabled, boolean canManage, String message)
        implements CustomPacketPayload {
    private static final int MAX_MESSAGE_LENGTH = 512;
    public static final Type<OverloadStatePayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(
                    "carpetbaritoneintegration", "overload_state"));
    public static final StreamCodec<RegistryFriendlyByteBuf,
            OverloadStatePayload> STREAM_CODEC =
            CustomPacketPayload.codec(
                    OverloadStatePayload::write,
                    OverloadStatePayload::new);

    private OverloadStatePayload(RegistryFriendlyByteBuf buffer) {
        this(buffer.readBoolean(), buffer.readBoolean(),
                buffer.readUtf(MAX_MESSAGE_LENGTH));
    }

    private void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeBoolean(enabled);
        buffer.writeBoolean(canManage);
        buffer.writeUtf(message == null ? "" : message,
                MAX_MESSAGE_LENGTH);
    }

    @Override public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
