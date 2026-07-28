package me.nuoyuan.carpetbaritoneintegration.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** S2C acknowledgement/error paired with {@link CommandSubmitPayload}. */
public record CommandResultPayload(boolean success, String message)
        implements CustomPacketPayload {
    private static final int MAX_MESSAGE_LENGTH = 8_192;
    public static final Type<CommandResultPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(
                    "carpetbaritoneintegration", "command_result"));
    public static final StreamCodec<RegistryFriendlyByteBuf,
            CommandResultPayload> STREAM_CODEC =
            CustomPacketPayload.codec(
                    CommandResultPayload::write,
                    CommandResultPayload::new);

    private CommandResultPayload(RegistryFriendlyByteBuf buffer) {
        this(buffer.readBoolean(), buffer.readUtf(MAX_MESSAGE_LENGTH));
    }

    private void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeBoolean(success);
        buffer.writeUtf(message, MAX_MESSAGE_LENGTH);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
