package me.nuoyuan.carpetbaritoneintegration.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Administrator-only C2S request to change OVERLOAD MODE. */
public record OverloadTogglePayload(boolean enabled)
        implements CustomPacketPayload {
    public static final Type<OverloadTogglePayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(
                    "carpetbaritoneintegration", "overload_toggle"));
    public static final StreamCodec<RegistryFriendlyByteBuf,
            OverloadTogglePayload> STREAM_CODEC =
            CustomPacketPayload.codec(
                    OverloadTogglePayload::write,
                    OverloadTogglePayload::new);

    private OverloadTogglePayload(RegistryFriendlyByteBuf buffer) {
        this(buffer.readBoolean());
    }

    private void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeBoolean(enabled);
    }

    @Override public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
