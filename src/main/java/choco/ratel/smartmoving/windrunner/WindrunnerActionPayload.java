package choco.ratel.smartmoving.windrunner;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** One of the {@link WindrunnerMoves} actions the client has already applied to its own movement. */
public record WindrunnerActionPayload(int action) implements CustomPacketPayload {
    public static final Type<WindrunnerActionPayload> TYPE = new Type<>(Windrunner.id("windrunner_action"));
    public static final StreamCodec<RegistryFriendlyByteBuf, WindrunnerActionPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, WindrunnerActionPayload::action, WindrunnerActionPayload::new);
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
