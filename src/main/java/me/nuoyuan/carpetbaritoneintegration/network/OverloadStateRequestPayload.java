package me.nuoyuan.carpetbaritoneintegration.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** C2S request for the server-authoritative OVERLOAD MODE state. */
public record OverloadStateRequestPayload() implements CustomPacketPayload {
    public static final Type<OverloadStateRequestPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(
                    "carpetbaritoneintegration", "overload_state_request"));
    public static final StreamCodec<RegistryFriendlyByteBuf,
            OverloadStateRequestPayload> STREAM_CODEC =
            CustomPacketPayload.codec(
                    OverloadStateRequestPayload::write,
                    OverloadStateRequestPayload::new);

    private OverloadStateRequestPayload(RegistryFriendlyByteBuf ignored) {
        this();
    }

    private void write(RegistryFriendlyByteBuf ignored) { }

    @Override public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
