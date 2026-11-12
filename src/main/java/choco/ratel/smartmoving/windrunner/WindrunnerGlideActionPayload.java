package choco.ratel.smartmoving.windrunner;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** A request to open the robes' glide membranes or to fold them away. */
public record WindrunnerGlideActionPayload(int mode) implements CustomPacketPayload {
    public static final Type<WindrunnerGlideActionPayload> TYPE = new Type<>(Windrunner.id("windrunner_glide_action"));
    public static final StreamCodec<RegistryFriendlyByteBuf, WindrunnerGlideActionPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, WindrunnerGlideActionPayload::mode, WindrunnerGlideActionPayload::new);
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
