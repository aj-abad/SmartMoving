package choco.ratel.smartmoving.windrunner;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public record WindrunnerGlideInputPayload(int epoch, int sequence, float pitch, float turn, boolean boost) implements CustomPacketPayload {
    public static final Type<WindrunnerGlideInputPayload> TYPE = new Type<>(Windrunner.id("windrunner_glide_input"));
    public static final StreamCodec<RegistryFriendlyByteBuf, WindrunnerGlideInputPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, WindrunnerGlideInputPayload::epoch,
            ByteBufCodecs.VAR_INT, WindrunnerGlideInputPayload::sequence,
            ByteBufCodecs.FLOAT, WindrunnerGlideInputPayload::pitch,
            ByteBufCodecs.FLOAT, WindrunnerGlideInputPayload::turn,
            ByteBufCodecs.BOOL, WindrunnerGlideInputPayload::boost, WindrunnerGlideInputPayload::new);
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
