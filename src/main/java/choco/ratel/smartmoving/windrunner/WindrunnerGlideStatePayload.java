package choco.ratel.smartmoving.windrunner;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.phys.Vec3;
import choco.ratel.smartmoving.windrunner.WindrunnerGlidePhysics.State;

public record WindrunnerGlideStatePayload(int playerId, int epoch, int mode, int acknowledged, long tick,
                                        Vec3 position, Vec3 velocity, State state) implements CustomPacketPayload {
    public static final Type<WindrunnerGlideStatePayload> TYPE = new Type<>(Windrunner.id("windrunner_glide_state"));
    public static final StreamCodec<RegistryFriendlyByteBuf, WindrunnerGlideStatePayload> CODEC = StreamCodec.of(
            (buf, packet) -> {
                buf.writeVarInt(packet.playerId); buf.writeVarInt(packet.epoch); buf.writeByte(packet.mode);
                buf.writeVarInt(packet.acknowledged); buf.writeLong(packet.tick);
                writeVector(buf, packet.position); writeVector(buf, packet.velocity);
                State s = packet.state;
                buf.writeFloat(s.yaw); buf.writeFloat(s.pitch); buf.writeFloat(s.roll);
                buf.writeFloat(s.yawRate); buf.writeFloat(s.pitchRate); buf.writeFloat(s.rollRate);
                buf.writeFloat(s.recovery); buf.writeVarInt(s.boostTicks); buf.writeVarInt(s.boostCooldown);
                buf.writeVarInt(s.brakeTicks);
            }, buf -> {
                int player = buf.readVarInt(), epoch = buf.readVarInt(), mode = buf.readUnsignedByte(), ack = buf.readVarInt();
                long tick = buf.readLong();
                Vec3 position = readVector(buf), velocity = readVector(buf);
                State s = new State();
                s.yaw = buf.readFloat(); s.pitch = buf.readFloat(); s.roll = buf.readFloat();
                s.yawRate = buf.readFloat(); s.pitchRate = buf.readFloat(); s.rollRate = buf.readFloat();
                s.recovery = buf.readFloat(); s.boostTicks = buf.readVarInt(); s.boostCooldown = buf.readVarInt();
                s.brakeTicks = buf.readVarInt();
                return new WindrunnerGlideStatePayload(player, epoch, mode, ack, tick, position, velocity, s);
            });
    private static void writeVector(RegistryFriendlyByteBuf buf, Vec3 v) { buf.writeDouble(v.x); buf.writeDouble(v.y); buf.writeDouble(v.z); }
    private static Vec3 readVector(RegistryFriendlyByteBuf buf) { return new Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble()); }
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
