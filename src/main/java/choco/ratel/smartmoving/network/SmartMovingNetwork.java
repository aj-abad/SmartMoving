package choco.ratel.smartmoving.network;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * SM packet channel registration and 7 types of Payload definition.
 * Original ObjectOutputStream/FML → Fabric PacketByteBuf/CustomPayload conversion.
 *
 * Channel directions:
 *  C2S: state, config_info, config_change, speed_change, hunger_change, sound
 *  S2C: state, config_content, config_change, speed_change
 */
public final class SmartMovingNetwork {

    // ── Payload types ──────────────────────────────────────────────

    /** State packet (C2S + S2C): Client sends its movement state, server relays to tracking players */
    public record StatePayload(int entityId, long state) implements CustomPacketPayload {
        public static final Type<StatePayload> ID = new Type<>(Identifier.fromNamespaceAndPath("smartmoving", "state"));
        public static final StreamCodec<FriendlyByteBuf, StatePayload> CODEC = new StreamCodec<>() {
            @Override
            public StatePayload decode(FriendlyByteBuf buf) {
                return new StatePayload(buf.readInt(), buf.readLong());
            }
            @Override
            public void encode(FriendlyByteBuf buf, StatePayload value) {
                buf.writeInt(value.entityId());
                buf.writeLong(value.state());
            }
        };
        @Override public Type<? extends CustomPacketPayload> type() { return ID; }
    }

    /** ConfigInfo packet (C2S): Client sends config info to server */
    public record ConfigInfoPayload(String config) implements CustomPacketPayload {
        public static final Type<ConfigInfoPayload> ID = new Type<>(Identifier.fromNamespaceAndPath("smartmoving", "config_info"));
        public static final StreamCodec<FriendlyByteBuf, ConfigInfoPayload> CODEC = new StreamCodec<>() {
            @Override
            public ConfigInfoPayload decode(FriendlyByteBuf buf) {
                return new ConfigInfoPayload(buf.readUtf());
            }
            @Override
            public void encode(FriendlyByteBuf buf, ConfigInfoPayload value) {
                buf.writeUtf(value.config());
            }
        };
        @Override public Type<? extends CustomPacketPayload> type() { return ID; }
    }

    /**
     * ConfigContent packet (S2C): Server sends config content to client.
     * lines = null: config disabled. username = null: no permission for that player.
     */
    public record ConfigContentPayload(String[] lines, String username) implements CustomPacketPayload {
        public static final Type<ConfigContentPayload> ID = new Type<>(Identifier.fromNamespaceAndPath("smartmoving", "config_content"));
        public static final StreamCodec<FriendlyByteBuf, ConfigContentPayload> CODEC = new StreamCodec<>() {
            @Override
            public ConfigContentPayload decode(FriendlyByteBuf buf) {
                boolean hasLines = buf.readBoolean();
                String[] lines = null;
                if (hasLines) {
                    int len = buf.readByte() & 0xFF;
                    lines = new String[len];
                    for (int i = 0; i < len; i++) lines[i] = buf.readUtf();
                }
                boolean hasUsername = buf.readBoolean();
                String username = hasUsername ? buf.readUtf() : null;
                return new ConfigContentPayload(lines, username);
            }
            @Override
            public void encode(FriendlyByteBuf buf, ConfigContentPayload value) {
                boolean hasLines = value.lines() != null;
                buf.writeBoolean(hasLines);
                if (hasLines) {
                    buf.writeByte(value.lines().length);
                    for (String line : value.lines()) buf.writeUtf(line);
                }
                buf.writeBoolean(value.username() != null);
                if (value.username() != null) buf.writeUtf(value.username());
            }
        };
        @Override public Type<? extends CustomPacketPayload> type() { return ID; }
    }

    /** ConfigChange packet (C2S + S2C): Config change notification, no payload */
    public record ConfigChangePayload() implements CustomPacketPayload {
        public static final Type<ConfigChangePayload> ID = new Type<>(Identifier.fromNamespaceAndPath("smartmoving", "config_change"));
        public static final StreamCodec<FriendlyByteBuf, ConfigChangePayload> CODEC = new StreamCodec<>() {
            @Override
            public ConfigChangePayload decode(FriendlyByteBuf buf) {
                return new ConfigChangePayload();
            }
            @Override
            public void encode(FriendlyByteBuf buf, ConfigChangePayload value) {
                // No payload
            }
        };
        @Override public Type<? extends CustomPacketPayload> type() { return ID; }
    }

    /**
     * SpeedChange packet (C2S + S2C): Speed change request.
     * username = null: player has no permission to change speed.
     */
    public record SpeedChangePayload(int difference, String username) implements CustomPacketPayload {
        public static final Type<SpeedChangePayload> ID = new Type<>(Identifier.fromNamespaceAndPath("smartmoving", "speed_change"));
        public static final StreamCodec<FriendlyByteBuf, SpeedChangePayload> CODEC = new StreamCodec<>() {
            @Override
            public SpeedChangePayload decode(FriendlyByteBuf buf) {
                int diff = buf.readInt();
                boolean hasUsername = buf.readBoolean();
                String username = hasUsername ? buf.readUtf() : null;
                return new SpeedChangePayload(diff, username);
            }
            @Override
            public void encode(FriendlyByteBuf buf, SpeedChangePayload value) {
                buf.writeInt(value.difference());
                buf.writeBoolean(value.username() != null);
                if (value.username() != null) buf.writeUtf(value.username());
            }
        };
        @Override public Type<? extends CustomPacketPayload> type() { return ID; }
    }

    /** HungerChange packet (C2S): Send client exhaustion value to server. -1 = suppress release, 0 = no exhaustion. */
    public record HungerChangePayload(float hunger) implements CustomPacketPayload {
        public static final Type<HungerChangePayload> ID = new Type<>(Identifier.fromNamespaceAndPath("smartmoving", "hunger_change"));
        public static final StreamCodec<FriendlyByteBuf, HungerChangePayload> CODEC = new StreamCodec<>() {
            @Override
            public HungerChangePayload decode(FriendlyByteBuf buf) {
                return new HungerChangePayload(buf.readFloat());
            }
            @Override
            public void encode(FriendlyByteBuf buf, HungerChangePayload value) {
                buf.writeFloat(value.hunger());
            }
        };
        @Override public Type<? extends CustomPacketPayload> type() { return ID; }
    }

    /**
     * Sound packet (C2S): SM exclusive sound playback request.
     * Original IPacketReceiver.processSoundPacket parameter is named distance but actually serializes volume.
     */
    public record SoundPayload(String soundId, float volume, float pitch) implements CustomPacketPayload {
        public static final Type<SoundPayload> ID = new Type<>(Identifier.fromNamespaceAndPath("smartmoving", "sound"));
        public static final StreamCodec<FriendlyByteBuf, SoundPayload> CODEC = new StreamCodec<>() {
            @Override
            public SoundPayload decode(FriendlyByteBuf buf) {
                return new SoundPayload(buf.readUtf(), buf.readFloat(), buf.readFloat());
            }
            @Override
            public void encode(FriendlyByteBuf buf, SoundPayload value) {
                buf.writeUtf(value.soundId());
                buf.writeFloat(value.volume());
                buf.writeFloat(value.pitch());
            }
        };
        @Override public Type<? extends CustomPacketPayload> type() { return ID; }
    }

    /**
     * 🔵 Stats packet (C2S + S2C): self → server → REMOTE per-tick stats angle synchronization.
     * Cause: vanilla lerpPosAndRotation interpolates REMOTE position evenly over 5 ticks →
     *   sm.stats.calculate realDxYz input becomes flattened + spike pattern →
     *   currentVerticalAngle/HorizontalAngle different trajectory than SELF →
     *   lerpFadeAngle 0.2 lerp insufficient peak-chasing time.
     * Fix (2026-05-14, Option F): self stats angle sent per-tick packet →
     *   REMOTE overwrites own stats.calculate result with packet value → 100% identical to self trajectory.
     * Packet overhead: per tick 8 bytes * tracker count = ~3.2 kbps/tracker (= acceptable overhead).
     * Fix #96 wasSelfSlideFire bit 35 pattern extended.
     */
    public record StatsPayload(int entityId, float verticalAngle, float horizontalAngle) implements CustomPacketPayload {
        public static final Type<StatsPayload> ID = new Type<>(Identifier.fromNamespaceAndPath("smartmoving", "stats"));
        public static final StreamCodec<FriendlyByteBuf, StatsPayload> CODEC = new StreamCodec<>() {
            @Override
            public StatsPayload decode(FriendlyByteBuf buf) {
                return new StatsPayload(buf.readInt(), buf.readFloat(), buf.readFloat());
            }
            @Override
            public void encode(FriendlyByteBuf buf, StatsPayload value) {
                buf.writeInt(value.entityId());
                buf.writeFloat(value.verticalAngle());
                buf.writeFloat(value.horizontalAngle());
            }
        };
        @Override public Type<? extends CustomPacketPayload> type() { return ID; }
    }

    // ── Registration ──────────────────────────────────────────────────────

    /**
     * Called from SmartMoving.onInitialize().
     * Register all required payload types in both C2S and S2C directions to PayloadTypeRegistry.
     */
    public static void register() {
        // C2S (Client→Server)
        PayloadTypeRegistry.serverboundPlay().register(StatePayload.ID,        StatePayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(ConfigInfoPayload.ID,   ConfigInfoPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(ConfigChangePayload.ID, ConfigChangePayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(SpeedChangePayload.ID,  SpeedChangePayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(HungerChangePayload.ID, HungerChangePayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(SoundPayload.ID,        SoundPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(StatsPayload.ID,        StatsPayload.CODEC);

        // S2C (Server→Client)
        PayloadTypeRegistry.clientboundPlay().register(StatePayload.ID,         StatePayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(ConfigContentPayload.ID, ConfigContentPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(ConfigChangePayload.ID,  ConfigChangePayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(SpeedChangePayload.ID,   SpeedChangePayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(StatsPayload.ID,         StatsPayload.CODEC);
    }

    private SmartMovingNetwork() {}
}
