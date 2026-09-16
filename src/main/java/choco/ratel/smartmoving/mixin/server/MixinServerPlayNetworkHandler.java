package choco.ratel.smartmoving.mixin.server;

import choco.ratel.smartmoving.server.SmartMovingServer;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 3-2: floatingTicks reset Mixin.
 * During SM climbing (isClimbing / isCrawlClimbing / isCeilingClimbing),
 * ServerPlayNetworkHandler.floatingTicks is reset to 0 every tick to
 * prevent kick for exceeding 80 ticks ("multiplayer.disconnect.flying").
 *
 * Reset condition: resetTicksForFloatKick == true (wall jump not included)
 * Fall distance reset condition: resetFallDistance == true (wall jump included)
 *
 * Original: SmartMovingServerPlayerBase.resetTicksForFloatKick()
 *           → Reflect.SetField(NetHandlerPlayServer, handler, "ticksForFloatKick", 0)
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class MixinServerPlayNetworkHandler {

    /** Yarn: field_14138 — Player floating in air tick counter. Kick if exceeding 80. */
    @Shadow private int aboveGroundTickCount;

    /** Target player to process */
    @Shadow public ServerPlayer player;

    /**
     * At tick() HEAD, reset floatingTicks and initialize fall distance based on SM state.
     * When client State packet is received, processStatePacket() updates resetTicksForFloatKick/
     * resetFallDistance flags, so this Mixin simply references the flags.
     */
    /** C-21: SM climbing/crawling active status — cached upon onPlayerMove entry. */
    @Unique
    private boolean sm_suppressPositionCheck;

    /** 🔴 fix #103 — SM phase exit history flag. */
    @Unique
    private boolean _smWasInPhase = false;
    @Unique
    private int _smPhaseExitTick = -9999;

    /**
     * C-21: onPlayerMove HEAD — cache SM movement state.
     * Original: NetHandlerPlayServer.processPlayer() position verification skip patch.
     */
    @Inject(method = "handleMovePlayer", at = @At("HEAD"))
    private void sm_onPlayerMove_head(ServerboundMovePlayerPacket packet, CallbackInfo ci) {
        if (player == null) return;
        SmartMovingServer sm = SmartMovingServer.get(player);
        // 🔴 fix #89 (2026-05-12, BUG = "press sneak while charging jump → 1 block raised crouch"):
        //   When isSliding is missing, its own sliding launch frame (= client entity.y -=1m) delta y
        //   matches vanilla "moved too quickly" check → requestTeleport (=PlayerPositionLookS2CPacket
        //   sent) triggers → client entity.y +1m forced correction → subsequent SS-SlideStop + fix #62 v2 push accumulates
        //   → box hovers at ground+1m → user perceives "1 block raised crouch".
        //   Same pattern as other SM phase guards (isClimbing/isCrawling/isCrawlClimbing/isCeilingClimbing).
        // 🔴 fix #100 (2026-05-31, BUG-1 jump boost+fox movement "moved too quickly"):
        //   Fox movement server-side state = isHeadJumping=true (isSliding=false). Original formula
        //   missing isHeadJumping → suppress=false → vanilla requestTeleport passes → client
        //   rubber-band → "stops in place". Confirmed via diagnostic log.
        boolean curInPhase = sm.isClimbing || sm.isCrawling || sm.isCrawlClimbing || sm.isCeilingClimbing
                || sm.isSliding || sm.isHeadJumping;

        // 🔴 fix #103 (2026-05-31, BUG = "HJ/sliding + 1 block water gap causes sharp cut and bounce back"):
        //   Diagnostic log confirmed fact — client sends isHJ=false StatePayload → server.sm.isHeadJumping
        //   updates immediately → at next onPlayerMove of same tick sm_suppressPositionCheck=false →
        //   fix #101/#102's ModifyVariable returns original distSq/postDistSq value → vanilla "moved
        //   wrongly" matches (postDistSq=0.10 > 0.0625) → requestTeleport EXECUTED → client
        //   PlayerPositionLookS2CPacket received → setPos(serverY+1.0) → +1.5m up jump "bounces up".
        //   = SM phase state becomes false quickly in transition frame so fix #101/#102 can't catch it.
        //
        //   Solution: history flag pattern (= borrowed from fix #153 v3 / #154 v4. memo [feedback_multistep_state_transition_history_flag]).
        //   Record SM phase exit tick + maintain suppress during N tick window → block rubber-band in transition frame.
        //
        //   5 tick window — stable measured value for server broadcast lag. Adjust after verification.
        if (_smWasInPhase && !curInPhase) {
            _smPhaseExitTick = player.tickCount;
        }
        boolean recentlyExited = (player.tickCount - _smPhaseExitTick) >= 0
                && (player.tickCount - _smPhaseExitTick) <= 5;
        sm_suppressPositionCheck = curInPhase || recentlyExited;
        _smWasInPhase = curInPhase;
    }

    /**
     * 🔴 fix #101 (2026-05-31, BUG "moved too quickly" — return 살아있는 문제):
     * vanilla bytecode `505: ifle 609` (threshold check) 가 SM phase 중에도 매치되면
     * warn + requestTeleport + return 가 통째로 실행. 기존 SM redirect 는 requestTeleport
     * 만 cancel 하므로 return 은 그대로 → player.move() 미실행 → server 위치 동결 → 다음
     * packet 도 동일 cascade.
     *
     * 해결: slot 25 의 첫 번째 dstore (= distanceSq, offset 313) 직후 값을 0 으로 만들면
     * `(0 - velocityLenSq) ≤ 0` → ifle 609 → 분기 진입 자체가 차단되어 warn + teleport +
     * return 전체 skip. 후속 player.move() 정상 실행. server 좌표 자연 갱신.
     *
     * 참고: 사이 sleeping check (offset 325, dload 25) 는 isSleeping=true 시점에 분기되며
     * 어차피 return 도달 경로라 distSq=0 영향 X.
     */
    @ModifyVariable(method = "handleMovePlayer", require = 0,
        at = @At(value = "STORE", ordinal = 0),
        index = 25)
    private double sm_suppressDistanceSq(double original) {
        return sm_suppressPositionCheck ? 0.0 : original;
    }

    /**
     * 🔴 fix #102 (2026-05-31, BUG "moved wrongly" — server 동기화 안 됨):
     * vanilla bytecode `817: ifle 885` (postDistSq > 0.0625 check) 매치 시 wronglyMoved=1
     * + warn + 후속 rubber-band 분기 진입. 기존 SM redirect 는 requestTeleport 만 cancel
     * 하지만 return 은 살아있어 `player.updatePositionAndAngles(packetX, packetY, packetZ)`
     * 미호출 → server 가 packet pos 동기화 X. 진단 로그로 직접 확인: SM phase 중 client
     * SM physics vs server.move() 결과가 ~0.35m 차이 (sliding+sneak motion 식 미일치) →
     * postDistSq=0.14 > 0.0625 매번 매치.
     *
     * 해결: slot 25 의 두 번째 dstore (= postDistSq, offset 796) 직후 값을 0 으로 만들면
     * `0 ≤ 0.0625` → ifle 885 → wronglyMoved=1 set 안 됨 + warn skip. 후속 `iload 33;
     * ifeq 923` 에서 wronglyMoved=0 (default) 이므로 isPlayerNotCollidingWithBlocks 분기로.
     * 거기서도 SM phase 중 box 차이로 false positive 가능 → 기존 requestTeleport redirect
     * 가 안전망. updatePositionAndAngles 까지 진행되어 server 동기화 정상.
     */
    @ModifyVariable(method = "handleMovePlayer", require = 0,
        at = @At(value = "STORE", ordinal = 1),
        index = 25)
    private double sm_suppressPostDistSq(double original) {
        return sm_suppressPositionCheck ? 0.0 : original;
    }

    /**
     * C-21: onPlayerMove 내 requestTeleport 호출 전부 가로채기.
     * SM 이동 중에는 "moved wrongly" / "moved too quickly" rubber-band를 차단한다.
     * require=0: 메서드 내 대상 invocation 개수가 변경되어도 크래시 없이 소프트 실패.
     *
     * 참고: fix #101/#102 가 두 IF 분기 자체를 차단하므로 SM phase 중 이 redirect 가
     * 호출될 일은 거의 없음. 단 isPlayerNotCollidingWithBlocks=true edge case 의 안전망.
     */
    @Redirect(method = "handleMovePlayer", require = 0,
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/network/ServerGamePacketListenerImpl;teleport(DDDFF)V"))
    private void sm_cancelTeleportForSmMove(ServerGamePacketListenerImpl handler,
            double x, double y, double z, float yaw, float pitch) {
        if (!sm_suppressPositionCheck) {
            teleport(x, y, z, yaw, pitch);
        }
    }

    @Shadow
    public abstract void teleport(double x, double y, double z, float yaw, float pitch);

    @Inject(method = "tick", at = @At("HEAD"))
    private void sm_tick(CallbackInfo ci) {
        if (player == null) return;
        SmartMovingServer sm = SmartMovingServer.get(player);

        // 3-9: 낙하 거리 리셋 (isClimbing 계열 + isWallJumping 중 fallDistance가 누적되지 않도록)
        if (sm.resetFallDistance) {
            sm.applyFallDistanceReset(player);
        }

        // 3-2: floatingTicks 리셋 (80틱 kick 방지)
        if (sm.resetTicksForFloatKick) {
            aboveGroundTickCount = 0;
        }
    }
}
