package choco.ratel.smartmoving.mixin.client;

import choco.ratel.smartmoving.client.SmartMovingClientState;
import choco.ratel.smartmoving.config.SmartMovingConfig;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 5-8 (client-side): move() HEAD/TAIL hook — STEP_HEIGHT suppression/restoration + accumulated movement distance.
 * 8-6: swimming sound accumulation — distance-based playback.
 *
 * Client-side implementation corresponding to server-side implementation of MixinEntity (main).
 */
@Mixin(Entity.class)
@Environment(EnvType.CLIENT)
public abstract class MixinEntityClient {

    /**
     * 5-8 (client-side) after:
     *   - accumulate climbing movement distance (for exhaustion calculation)
     *   - accumulate swimming sound (8-6)
     *
     * Original: afterMoveEntity() — distanceSwom accumulation, playback when SwimSoundDistance exceeded.
     * SwimSoundDistance = 1/0.7F ≈ 1.4286F (swim_dive.md recorded value).
     * Sound: volume=0.05F, pitch=1.0F ± rand*0.4F.
     *
     * Red circle fix #73 phase 2 (2026-05-10): remove all STEP_HEIGHT=0 suppression/restoration logic.
     *   See MixinEntity.sm_afterMove comment for details.
     */
    @Inject(method = "move", at = @At("TAIL"))
    private void sm_afterMove_client(MoverType type, Vec3 movement, CallbackInfo ci) {
        if (!((Object) this instanceof LocalPlayer player)) return;

        // BUG-24 (session 36): when SM disabled, skip SmartStatistics update + heightOffset correction + climb distance
        //   accumulation + swim sound → vanilla normal. block player.setPos impact from remaining sm.heightOffset
        //   (possible cause 1 of flight entry stutter).
        if (!SmartMovingConfig.Config.enabled) return;

        SmartMovingClientState sm = SmartMovingClientState.get(player);

        // C-25: SmartStatistics update — prevX/Y/Z are previous positions saved in tickMovement HEAD
        // yaw: original SmartRenderRender.currentCameraAngle = rotationYaw / RadiantToAngle for
        sm.stats.calculate(player.xo, player.yo, player.zo,
                           player.getX(), player.getY(), player.getZ(),
                           player.getYRot());

        // Accumulate climbing movement distance
        if (sm.isClimbing || sm.isCrawlClimbing || sm.isCeilingClimbing) {
            sm.distanceClimbedModified += movement.length() * (sm.isClimbing ? 1.2 : 0.9);
        }

        // Accumulate swimming sound (8-6)
        // isSwimming_sm: SM swimming state (field name separated to distinguish from vanilla isSwimming())
        if (sm.isSwimming_sm) {
            sm.distanceSwom += movement.horizontalDistance();
            // SwimSoundDistance = 1/0.7F (swim_dive.md: "distanceSwom > SwimSoundDistance (= 1/0.7F ≈ 1.4286F)")
            final float SWIM_SOUND_DISTANCE = 1.0F / 0.7F;
            if (sm.distanceSwom > SWIM_SOUND_DISTANCE) {
                sm.distanceSwom -= SWIM_SOUND_DISTANCE;
                // Original sound: "random.splash" → 1.21.1: SoundEvents.ENTITY_PLAYER_SWIM
                // volume=0.05F, pitch=1.0F ± rand*0.4F (swim_dive.md recorded value)
                player.playSound(
                        SoundEvents.PLAYER_SWIM,
                        0.05F,
                        1.0F + (player.getRandom().nextFloat() - 0.5F) * 0.4F
                );
            }
        }
    }

    /**
     * isSneaking() override — SM state-based sneaking judgment.
     * Original: SmartMovingSelf.isSneaking() (SmartMovingPlayerBase.java, lines 1972-1981)
     *
     * isSneaking() is defined in Entity — no override in LivingEntity/PlayerEntity, handled in Entity Mixin.
     * Delegates to vanilla when Config inactive or riding a vehicle.
     */
    @Inject(method = "isShiftKeyDown", at = @At("HEAD"), cancellable = true)
    private void sm_isSneaking(CallbackInfoReturnable<Boolean> cir) {
        if (!((Object) this instanceof LocalPlayer player)) return;
        SmartMovingClientState sm = SmartMovingClientState.get(player);
        SmartMovingConfig cfg = SmartMovingConfig.Config;

        if (!cfg.enabled || player.isPassenger()) return;

        if (sm.forceIsSneaking != null) { cir.setReturnValue(sm.forceIsSneaking); return; }

        // Red circle fix #75 (BUG #2, 2026-05-10, user report "weeping/twisting vines + sneak vibration"):
        //   Vibration cycle: pose toggle (STANDING ↔ CROUCHING) → box height toggle (1.8 ↔ 1.5)
        //     → vanilla move/collision result onGround toggle → our sm_isSneaking's
        //     `(isSlow && onGround)` branch result toggle → vanilla updatePose toggles pose based on isSneaking
        //     → infinite cycle.
        //   Original SmartMovingSelf.isSneaking formula itself is 1:1 mapped but 1.21.1 vanilla's box
        //   height change → onGround toggle quirk + weeping/twisting vines's isHoldingOntoLadder
        //   special handling combination causes this only in 1.21.1. 1.7.10 vanilla has no weeping/twisting vines.
        //
        //   Memory project_vine_animation_complete.md's intent = "weeping/twisting vines behave
        //   as vanilla". sm_isSneaking also delegates to vanilla in that scenario → use vanilla's
        //   stable SNEAKING flag (input.sneaking as-is) → pose stable → no vibration.
        net.minecraft.world.level.block.Block _blockAtPos = player.level()
                .getBlockState(player.blockPosition()).getBlock();
        if (_blockAtPos == net.minecraft.world.level.block.Blocks.WEEPING_VINES
                || _blockAtPos == net.minecraft.world.level.block.Blocks.WEEPING_VINES_PLANT
                || _blockAtPos == net.minecraft.world.level.block.Blocks.TWISTING_VINES
                || _blockAtPos == net.minecraft.world.level.block.Blocks.TWISTING_VINES_PLANT) {
            return;  // override skip → vanilla isSneaking as-is
        }

        // Red circle fix #83 (2026-05-12, user report "screen shake while sliding + footstep + flight toggle possible"):
        //   1.21.1 vanilla bobbing/footstep/jump double-tap flight toggle all based on isSneaking().
        //   Mapping original formula as-is (= without isSliding guard) results in isSneaking()=false during sliding →
        //   normal walking processing → user reported BUG.
        //   1.21.1 mapping forces isSneaking()=true during isSliding + clipAtLedge override (fix #84)
        //   blocks step-back side effect.
        boolean result = (sm.isSlow && player.onGround())
                || (!cfg.sneak && sm.wouldIsSneaking && sm.jumpCharge > 0)
                || (!cfg.crawlOverEdge && sm.isCrawling && !sm.isClimbing)
                || sm.isSliding;
        cir.setReturnValue(result);
    }

    /**
     * Red circle block SNEAKING flag setting for ladder/vine climbing + W + sneak.
     *
     * vanilla 1.21.1 pose determination (CROUCHING) mechanism uses SNEAKING flag or input.sneaking
     * directly (exact method mapping differs) → our isSneaking() override alone is insufficient.
     *
     * Entity.setSneaking(boolean) is called every tick by vanilla with input.sneaking →
     * sets SNEAKING flag (DataTracker). Blocking this flag itself blocks pose determination + isSneaking()
     * + all sneak effects uniformly.
     *
     * User intent: "ladder + W + sneak → no action, just climb".
     *   - sm.isClimbing && forward > 0 && sneaking=true → block setSneaking(true).
     *   - all other cases: vanilla normal processing.
     */
    @Inject(method = "setShiftKeyDown", at = @At("HEAD"), cancellable = true)
    private void sm_setSneaking(boolean sneaking, CallbackInfo ci) {
        if (!((Object) this instanceof LocalPlayer player)) return;
        if (!SmartMovingConfig.Config.enabled) return;
        SmartMovingClientState sm = SmartMovingClientState.get(player);
        if (sm.isClimbing && player.input.getMoveVector().y > 0F && sneaking) {
            ci.cancel();
        }
    }

    /**
     * pushOutOfBlocks: suppress immediately after server→client position synchronization.
     * Original: SmartMovingSelf.pushOutOfBlocks (SmartMovingSelf.md L1263-1277)
     *
     * If multiPlayerInitialized > 0, cancel execution and decrement counter by 1.
     * 1.21.1: Entity.pushOutOfBlocks(double,double,double) — defined in Entity.
     */
    // fix #175 v1/v2/v3 all attempted — inject itself not active (= dump FIX175-CHECK matches 0). revert.
    // next direction: add SM state check to main mixin (MixinEntity.sm_offsetBoundingBoxForFlying) guard.

    @Inject(method = "moveTowardsClosestSpace(DDD)V", at = @At("HEAD"), cancellable = true)
    private void sm_pushOutOfBlocks(double x, double y, double z, CallbackInfo ci) {
        if (!((Object) this instanceof LocalPlayer player)) return;
        SmartMovingClientState sm = SmartMovingClientState.get(player);
        if (sm.multiPlayerInitialized > 0) {
            sm.multiPlayerInitialized--;
            ci.cancel();
        }
    }

}
