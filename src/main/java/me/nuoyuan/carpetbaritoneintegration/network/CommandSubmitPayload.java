package me.nuoyuan.carpetbaritoneintegration.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** C2S command transport that is independent of Minecraft chat limits. */
public record CommandSubmitPayload(String fakePlayer, String command)
        implements CustomPacketPayload {
    private static final int MAX_FAKE_NAME = 64;
    private static final int MAX_COMMAND_LENGTH = 262_144;
    public static final Type<CommandSubmitPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(
                    "carpetbaritoneintegration", "command_submit"));
    public static final StreamCodec<RegistryFriendlyByteBuf,
            CommandSubmitPayload> STREAM_CODEC =
            CustomPacketPayload.codec(
                    CommandSubmitPayload::write,
                    CommandSubmitPayload::new);

    private CommandSubmitPayload(RegistryFriendlyByteBuf buffer) {
        this(buffer.readUtf(MAX_FAKE_NAME),
                buffer.readUtf(MAX_COMMAND_LENGTH));
    }

    private void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeUtf(fakePlayer, MAX_FAKE_NAME);
        buffer.writeUtf(command, MAX_COMMAND_LENGTH);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
