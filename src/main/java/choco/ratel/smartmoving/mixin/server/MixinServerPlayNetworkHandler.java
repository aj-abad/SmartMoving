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
        boolean curInPhase = sm.isClimbing || sm.isCrawling || sm.isCrawlClimbing || sm.isCeilingClimbing
                || sm.isSliding;

        // 🔴 fix #103 (2026-05-31, BUG = "sliding + 1 block water gap causes sharp cut and bounce back"):
        //   Diagnostic log confirmed fact — client sends a phase-exit StatePayload → server.sm phase flag
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
     * 🔴 fix #101 (2026-05-31, BUG "moved too quickly" — return stays alive issue):
     * vanilla bytecode `505: ifle 609` (threshold check) still matches during SM phase
     * executing warn + requestTeleport + return entirely. Existing SM redirect only cancels requestTeleport
     * but return stays → player.move() doesn't execute → server position frozen → next
     * packet cascades identically.
     *
     * Solution: after first dstore of slot 25 (= distanceSq, offset 313), set value to 0
     * then `(0 - velocityLenSq) ≤ 0` → ifle 609 → branch entry itself blocked, warn + teleport +
     * return entirely skipped. Following player.move() executes normally. Server coordinates update naturally.
     *
     * Note: sleeping check in between (offset 325, dload 25) branches when isSleeping=true
     * which is return path anyway so distSq=0 impact is none.
     *
     * 26.3: the check moved from handleMovePlayer into handlePlayerPositionChange, and the variable
     * (movedDist) moved from slot 25 to 33, so it is now selected by name. Same two stores.
     */
    @ModifyVariable(method = "handlePlayerPositionChange", require = 0,
        at = @At(value = "STORE", ordinal = 0),
        name = "movedDist")
    private double sm_suppressDistanceSq(double original) {
        return sm_suppressPositionCheck ? 0.0 : original;
    }

    /**
     * 🔴 fix #102 (2026-05-31, BUG "moved wrongly" — server sync fails):
     * vanilla bytecode `817: ifle 885` (postDistSq > 0.0625 check) matches setting wronglyMoved=1
     * + warn + enters following rubber-band branch. Existing SM redirect only cancels requestTeleport
     * but return stays so `player.updatePositionAndAngles(packetX, packetY, packetZ)`
     * never called → server doesn't sync packet pos. Confirmed via diagnostic log: during SM phase
     * client SM physics vs server.move() result differs ~0.35m (sliding+sneak motion formula mismatch) →
     * postDistSq=0.14 > 0.0625 matches every time.
     *
     * Solution: after second dstore of slot 25 (= postDistSq, offset 796), set value to 0
     * then `0 ≤ 0.0625` → ifle 885 → wronglyMoved=1 not set + warn skipped. Following `iload 33;
     * ifeq 923` with wronglyMoved=0 (default) branches to isPlayerNotCollidingWithBlocks. There too
     * false positive possible due to box diff during SM phase → existing requestTeleport redirect
     * is safeguard. Proceeds to updatePositionAndAngles so server sync normal.
     *
     * 26.3: second store of movedDist in handlePlayerPositionChange (see fix #101 above).
     */
    @ModifyVariable(method = "handlePlayerPositionChange", require = 0,
        at = @At(value = "STORE", ordinal = 1),
        name = "movedDist")
    private double sm_suppressPostDistSq(double original) {
        return sm_suppressPositionCheck ? 0.0 : original;
    }

    /**
     * C-21: Intercept all requestTeleport calls within onPlayerMove.
     * Blocks "moved wrongly" / "moved too quickly" rubber-band during SM movement.
     * require=0: soft fail without crash if target invocation count changes in method.
     *
     * Note: fix #101/#102 blocks both IF branches themselves, so this redirect rarely
     * gets called during SM phase. But it's safeguard for isPlayerNotCollidingWithBlocks=true edge case.
     *
     * 26.3: the same three teleport calls (sleeping, too quickly, collision fallback) now sit in
     * handlePlayerPositionChange.
     */
    @Redirect(method = "handlePlayerPositionChange", require = 0,
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

        // 3-9: Reset fall distance (prevent fallDistance accumulation during isClimbing series + isWallJumping)
        if (sm.resetFallDistance) {
            sm.applyFallDistanceReset(player);
        }

        // 3-2: Reset floatingTicks (prevent 80 tick kick)
        if (sm.resetTicksForFloatKick) {
            aboveGroundTickCount = 0;
        }
    }
}
