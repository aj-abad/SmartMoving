package choco.ratel.smartmoving.network;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * SM 패킷 채널 등록 및 7종 Payload 타입 정의.
 * 원본 ObjectOutputStream/FML → Fabric PacketByteBuf/CustomPayload 변환.
 *
 * 채널 방향:
 *  C2S: state, config_info, config_change, speed_change, hunger_change, sound
 *  S2C: state, config_content, config_change, speed_change
 */
public final class SmartMovingNetwork {

    // ── Payload 타입 ──────────────────────────────────────────────

    /** State 패킷 (C2S + S2C): 클라이언트가 자신의 이동 상태를 전송, 서버가 추적 플레이어에게 릴레이 */
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

    /** ConfigInfo 패킷 (C2S): 클라이언트가 설정 정보를 서버에 전송 */
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
     * ConfigContent 패킷 (S2C): 서버가 설정 내용을 클라이언트에게 전송.
     * lines = null 시 설정 비활성화. username = null 시 해당 플레이어에게 권한 없음.
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

    /** ConfigChange 패킷 (C2S + S2C): 설정 변경 알림, 페이로드 없음 */
    public record ConfigChangePayload() implements CustomPacketPayload {
        public static final Type<ConfigChangePayload> ID = new Type<>(Identifier.fromNamespaceAndPath("smartmoving", "config_change"));
        public static final StreamCodec<FriendlyByteBuf, ConfigChangePayload> CODEC = new StreamCodec<>() {
            @Override
            public ConfigChangePayload decode(FriendlyByteBuf buf) {
                return new ConfigChangePayload();
            }
            @Override
            public void encode(FriendlyByteBuf buf, ConfigChangePayload value) {
                // 페이로드 없음
            }
        };
        @Override public Type<? extends CustomPacketPayload> type() { return ID; }
    }

    /**
     * SpeedChange 패킷 (C2S + S2C): 속도 변경 요청.
     * username = null 시 해당 플레이어는 속도 변경 권한 없음.
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

    /** HungerChange 패킷 (C2S): 클라이언트 소진값을 서버에 전달. -1 = 억제 해제, 0 = 소진 없음. */
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
     * Sound 패킷 (C2S): SM 전용 사운드 재생 요청.
     * 원본 IPacketReceiver.processSoundPacket 파라미터명이 distance이지만 실제로는 volume 직렬화.
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
     * 🔵 Stats 패킷 (C2S + S2C): self → server → REMOTE 매 tick stats angle 동기화.
     * 원인: vanilla lerpPosAndRotation 가 REMOTE 의 위치를 5-tick 균등 분할 보간 →
     *   sm.stats.calculate 의 realDxYz 입력이 평탄화 + spike 패턴 →
     *   currentVerticalAngle/HorizontalAngle 가 SELF trajectory 와 다른 흐름 →
     *   lerpFadeAngle 0.2 lerp 가 peak 추격 시간 부족.
     * fix (2026-05-14, Option F): self 의 stats angle 매 tick packet 송신 →
     *   REMOTE 가 자체 stats.calculate 결과를 packet 값으로 덮어쓰기 → 100% self 와 동일 trajectory.
     * 패킷 부하: 매 tick 8 byte * tracker 수 = ~3.2 kbps/tracker (= 부담 적음).
     * fix #96 wasSelfSlideFire bit 35 패턴 확장.
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

    // ── 등록 ──────────────────────────────────────────────────────

    /**
     * SmartMoving.onInitialize()에서 호출.
     * C2S/S2C 양방향에 필요한 모든 페이로드 타입을 PayloadTypeRegistry에 등록.
     */
    public static void register() {
        // C2S (클라이언트→서버)
        PayloadTypeRegistry.serverboundPlay().register(StatePayload.ID,        StatePayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(ConfigInfoPayload.ID,   ConfigInfoPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(ConfigChangePayload.ID, ConfigChangePayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(SpeedChangePayload.ID,  SpeedChangePayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(HungerChangePayload.ID, HungerChangePayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(SoundPayload.ID,        SoundPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(StatsPayload.ID,        StatsPayload.CODEC);

        // S2C (서버→클라이언트)
        PayloadTypeRegistry.clientboundPlay().register(StatePayload.ID,         StatePayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(ConfigContentPayload.ID, ConfigContentPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(ConfigChangePayload.ID,  ConfigChangePayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(SpeedChangePayload.ID,   SpeedChangePayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(StatsPayload.ID,         StatsPayload.CODEC);
    }

    private SmartMovingNetwork() {}
}
