package choco.ratel.smartmoving.mixin;

import choco.ratel.smartmoving.config.SmartMovingConfig;
import choco.ratel.smartmoving.server.SmartMovingServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 5-7: velocityDirty @Shadow — Triggers server synchronization when SM directly changes velocity.
 * 5-8: move() HEAD/TAIL hook — Porting beforeMoveEntity / afterMoveEntity.
 *
 * Note: Entity.setVelocity() automatically sets velocityDirty=true internally.
 *       If SM changes velocity through setVelocity(), separate setting is not needed.
 *       Manual setting is only required when directly manipulating the velocity vector field.
 */
@Mixin(Entity.class)
public abstract class MixinEntity {

    /**
     * Entity.dimensions — Current EntityDimensions (width/height/eyeHeight).
     * Guard signal for sm_offsetBoundingBoxForFlying.
     */
    @Shadow private EntityDimensions dimensions;

    /**
     * 3-4: isSneaking() override — Server player only.
     * isSneaking() is declared in Entity, so cannot be injected in ServerPlayerEntity.
     * Applied only to server players with instanceof guard in Entity Mixin.
     *
     * Original: SmartMovingServer.isSneaking(): forceIsSneaking != null ? forceIsSneaking : vanilla
     */
    @Inject(method = "isShiftKeyDown", at = @At("HEAD"), cancellable = true)
    private void sm_isSneaking(CallbackInfoReturnable<Boolean> cir) {
        if (!((Object) this instanceof ServerPlayer player)) return;
        SmartMovingServer sm = SmartMovingServer.get(player);
        if (sm.forceIsSneaking != null) {
            cir.setReturnValue(sm.forceIsSneaking);
        }
    }

    /**
     * 5-8 after: afterMoveEntity() porting (server side).
     *
     * Accumulate climbing movement distance (for exhaustion calculation)
     *   isClimbing: while touching ground → 1.2 multiplier
     *   isCrawlClimbing / isCeilingClimbing: in air → 0.9 multiplier
     *
     * heightOffset position correction: Implemented in MixinEntityClient.sm_afterMove_client().
     *
     * 🔴 fix #73 phase 2 (2026-05-10): Force STEP_HEIGHT=0 + remove all restoration logic.
     *   Original SmartMovingSelf.beforeMoveEntity's `ySize=0F` is a different variable from 1.21.1 GENERIC_STEP_HEIGHT
     *   (ySize = rendering interpolation offset after step up, stepHeight = actual step height).
     *   Original SmartMovingBase.move does not change sp.stepHeight itself → uses vanilla 0.5F as-is.
     *   1.12.2 SMReboot is the same. The mapping itself is incorrect → removal is 1:1 correct.
     *   No in-game regression for both phase 1 (isCrawling) + phase 2 (isCrawlClimbing/isCeilingClimbing).
     */
    @Inject(method = "move", at = @At("TAIL"))
    private void sm_afterMove(MoverType type, Vec3 movement, CallbackInfo ci) {
        if (!((Object) this instanceof ServerPlayer player)) return;
        SmartMovingServer sm = SmartMovingServer.get(player);
        if (sm.isClimbing || sm.isCrawlClimbing || sm.isCeilingClimbing) {
            sm.distanceClimbedModified += movement.length() * (sm.isClimbing ? 1.2 : 0.9);
        }
    }

    /**
     * Flying collision — User mapping (phase 4 recovery):
     *   Original SmartMovingSelf.setHeightOffset(-1F) (L1694-L1704):
     *     boundingBox.minY -= heightOffset = +1 (move box 1 block up)
     *     height += heightOffset = -1 (1.8 → 0.8)
     *
     *   1.21.1 mapping:
     *   - sm_getBaseDimensions inject: dimensions=(0.6, 0.8, 1.62) during flying (height 0.8).
     *   - This mixin: calculateBoundingBox result box.offset(0, 1, 0) during flying — move box +1 up.
     *   - Result: box = (entity.y+1, entity.y+1+0.8) = (entity.y+1, entity.y+1.8).
     *
     *   Guard: dimensions.height < 1 && eyeHeight > 1 — flying/Levitate only (other small SM
     *   has eyeHeight 0.62 so does not pass). PlayerEntity only.
     *   abilities=null guard: PlayerEntity constructor may call setPosition before abilities initialization.
     */
    /**
     * Red circle fix #175 v5 (2026-05-27, user report after fix v4 "head jump/fox movement collision+animation issue"):
     *   v4 formula = check sm.isSliding only → blocks head jump attempt → box +1m up does not happen → box feet stuck.
     *   v5 = check all SLIDING POSE entry paths (= sm_updatePose_client/server):
     *     `isHeadJumping || isSliding → SLIDING`.
     *     (flying/Levitate passes STANDING after BUG-29 fix → does not enter SLIDING POSE).
     *   If either phase is true, normal → keep main mixin active. Only if both false → block.
     *   If reflection fails, return true (= pass guard → maintain existing behavior).
     */
    @org.spongepowered.asm.mixin.Unique
    private static boolean sm_isValidSlidingPoseState(Player player) {
        if (player instanceof ServerPlayer sp) {
            SmartMovingServer sm = SmartMovingServer.get(sp);
            return sm.isSliding || sm.isHeadJumping;
        }
        // client side - reflection (avoids client class symbol during main module compile).
        // 🔴 fix (2026-06-08, BUG = "dev mode OK / in build (prod) quick sliding tap causes ground lock"):
        //   Existing code used Class.forName("net.minecraft.client.network.AbstractClientPlayerEntity") —
        //   named class name. String literal not touched by Loom/Mixin remapper, so in prod
        //   intermediary runtime (net.minecraft.class_742) ClassNotFoundException → catch →
        //   return true (always box +1 offset). dev (named runtime) succeeds → accurate isSliding check.
        //   Result: in prod, isSliding=false transition frame still has box +1 remaining → 1 block air under feet
        //   → client free fall → server reconcile (COLLISION) → ground lock = root of dev/prod difference.
        //   Solution: Remove Minecraft named class Class.forName. Search get(...) by name+parameter count.
        try {
            Class<?> stateCls = Class.forName("choco.ratel.smartmoving.client.SmartMovingClientState");
            java.lang.reflect.Method getM = null;
            for (java.lang.reflect.Method mm : stateCls.getMethods()) {
                if (mm.getName().equals("get") && mm.getParameterCount() == 1
                        && java.lang.reflect.Modifier.isStatic(mm.getModifiers())) {
                    getM = mm;
                    break;
                }
            }
            if (getM == null) return true;
            Object state = getM.invoke(null, player);
            if (state == null) return true;  // fall through to main mixin.
            return stateCls.getField("isSliding").getBoolean(state)
                    || stateCls.getField("isHeadJumping").getBoolean(state);
        } catch (Throwable e) {
            return true;  // If reflection fails, consider as normal SLIDING → keep main mixin active.
        }
    }

    @Inject(method = "makeBoundingBox", at = @At("RETURN"), cancellable = true)
    private void sm_offsetBoundingBoxForFlying(CallbackInfoReturnable<AABB> cir) {
        if (!((Object) this instanceof Player player)) return;
        if (!SmartMovingConfig.Config.enabled) return;
        if (player.getAbilities() == null) return;

        EntityDimensions dim = this.dimensions;
        if (dim.height() >= 1.0F) return;       // STANDING / normal height
        // 🔴 fix #56 (2026-05-10, user report "after fix #53: box low + wall collision + difficult entry"):
        //   Previous guard `eye <= 1.0F` only = mixin offset active only during isHeadJumping (eye=1.62).
        //   During fix #53 entry (= 1:1 with 1.12.2): force isHeadJumping=false + isSliding=true → dim eye=0.62
        //   → mixin offset blocked → box feet = entity.y = original ground - 1m → stuck.
        //
        //   1.7.10/1.12.2 `setHeightOffset(-1)` pattern = boundingBox.minY += 1m **direct change**
        //   (regardless of POSE, applied to both isSliding/isHeadJumping). In vanilla 1.21.1 mapping,
        //   mixin offset equivalent is guard only by `eye>1` → BUG: not applied when isSliding.
        //
        //   Solution: activate mixin offset also on POSE.SLIDING. POSE.SLIDING branch:
        //     - sm_updatePose pattern: isHeadJumping || isSliding || isFlying || isLevitating → SLIDING.
        //   → offset +1m up on all SLIDING POSE. 1:1 effect as 1.7.10/1.12.2 setHeightOffset.
        //   Side effect: normal sliding also box +1m up — equivalent 1.12.2 behavior (= no regression).
        Pose pose = ((Entity) (Object) this).getPose();
        if (dim.eyeHeight() <= 1.0F && pose != Pose.SLIDING) return;

        // Red circle fix #175 v5 (2026-05-27, v4 after head jump/fox movement regression fix):
        //   SLIDING POSE entry path = 4 SM phase (isHeadJumping || isSliding || isFlying || isLevitating).
        //   v4 = checks only sm.isSliding → blocks other phases → box gets stuck.
        //   v5 = passes if any of 4 phases is true. blocks only when all false → remaining.
        if (pose == Pose.SLIDING) {
            if (!sm_isValidSlidingPoseState(player)) return;
        }

        AABB original = cir.getReturnValue();
        cir.setReturnValue(original.move(0.0, 1.0, 0.0));
        // Red circle v26.6 — remote side timer override is additionally applied in client-side mixin (reason for separation:
        //   main mixin set cannot reference client class during server build).
        //   client-side `MixinEntityClient.sm_iccTimerOffsetBb` injects RETURN on the same method.
    }

}
