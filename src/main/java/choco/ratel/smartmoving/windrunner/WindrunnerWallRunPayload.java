package choco.ratel.smartmoving.windrunner;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * A wall run starting or stopping: {@link WindrunnerWallRunModel} mode and the wall's horizontal side
 * (2D data value). The runner sends its own (the id is ignored); the server relays it to watchers.
 */
public record WindrunnerWallRunPayload(int playerId, int mode, int side) implements CustomPacketPayload {
    public static final Type<WindrunnerWallRunPayload> TYPE = new Type<>(Windrunner.id("windrunner_wall_run"));
    public static final StreamCodec<RegistryFriendlyByteBuf, WindrunnerWallRunPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, WindrunnerWallRunPayload::playerId,
            ByteBufCodecs.VAR_INT, WindrunnerWallRunPayload::mode,
            ByteBufCodecs.VAR_INT, WindrunnerWallRunPayload::side, WindrunnerWallRunPayload::new);
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
