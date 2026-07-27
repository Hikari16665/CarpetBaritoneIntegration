package me.nuoyuan.carpetbaritoneintegration.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record ControlOptionsRequestPayload()
        implements CustomPacketPayload {
    public static final Type<ControlOptionsRequestPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(
                    "carpetbaritoneintegration", "control_options_request"));
    public static final StreamCodec<RegistryFriendlyByteBuf,
            ControlOptionsRequestPayload> STREAM_CODEC =
            CustomPacketPayload.codec(
                    ControlOptionsRequestPayload::write,
                    ControlOptionsRequestPayload::new);

    private ControlOptionsRequestPayload(RegistryFriendlyByteBuf ignored) {
        this();
    }

    private void write(RegistryFriendlyByteBuf ignored) { }

    @Override public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
