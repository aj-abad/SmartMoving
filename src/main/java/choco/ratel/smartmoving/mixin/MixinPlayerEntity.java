package choco.ratel.smartmoving.mixin;

import choco.ratel.smartmoving.config.SmartMovingConfig;
import choco.ratel.smartmoving.server.SmartMovingServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 6-1 (server): getBaseDimensions() returns custom EntityDimensions.
 * 6-4 (server): updatePose() Mixin — Forces pose setting by SM state.
 *
 * LivingEntity.getDimensions() is final → getBaseDimensions() is the only override entry point.
 * Yarn: getBaseDimensions (intermediary: method_55694 verified)
 * Yarn: updatePose (intermediary: method_7318 verified)
 *
 * Client side → MixinPlayerEntityClient.sm_getBaseDimensions_client(), sm_updatePose_client()
 */
@Mixin(Player.class)
public abstract class MixinPlayerEntity {

    /**
     * 6-1 (server): Returns custom EntityDimensions by SM pose.
     *
     * **Focus #2.7 Phase C rewrite (session 4, 2026-04-25)**: Completely symmetric with client (option 3).
     *   Original `setHeightOffset(-1F)` (SmartMovingSelf L1694-L1704) → `sp.height = 1.8F + (-1F)
     *   = 0.8F` + `getEyeHeight() = height - 0.18F = 0.62F` (ServerPlayerBase L142-L145).
     *   → All small states (isCrawling/isCrawlClimbing/isHeadJumping/isSliding/isSwimming/
     *   isDiving/isFlying/isLevitating) unified 0.6×0.8 + 0.62F.
     *
     * Same 8 SM OR branches as client (MixinPlayerEntityClient.sm_getBaseDimensions_client).
     * isFlying is vanilla `abilities.flying + cfg.fly + !isSwimming + !isDiving` original formula
     * (SmartMovingSelf L2509-L2515) 1:1.
     */
    @Inject(method = "getDefaultDimensions", at = @At("HEAD"), cancellable = true, require = 0)
    private void sm_getBaseDimensions_server(Pose pose, CallbackInfoReturnable<EntityDimensions> cir) {
        if (!((Object) this instanceof ServerPlayer player)) return;
        SmartMovingServer sm = SmartMovingServer.get(player);

        // 🔴 BUG-29 real cause fix (Flying Phase / session 45): Remove flying/Levitate branch (server side).
        //   Same fix as client side sm_getBaseDimensions_client — pass vanilla STANDING during flying.
        //   Original 1.7.10 vanilla has no EntityPose system → flying uses STANDING model + eyeHeight
        //   1.62 as-is. 1.21.1 incorrect mapping = force SLIDING → camera at feet.
        //   Keep client/server symmetry: both pass vanilla STANDING POSE during flying → datatracker consistent.
        //
        // Original setHeightOffset(-1F) state exhaustive — only Crawling/Sliding/Swimming etc. use 0.6 × 0.8 +
        // eyeHeight 0.62F. isFlying/isLevitating pass vanilla.
        // 🔴 Add isClimbCrawling branch (user report fix — block X/Z pulling server reconcile):
        //   During ICC active, client dim eyeHeight=1.62 (mixin offset box +1 applied) ↔ server vanilla
        //   dim 1.8 (mixin offset not applied) → box 1m Y difference → server box ladder collision
        //   not detected → block → server position correction packet → client position reset.
        //   Add isClimbCrawling branch same as client sm_getBaseDimensions_client → server box
        //   same as client (entity.y+1, entity.y+1.8) → no server reconcile.
        // 🔴 Revert attempt to add isCrawlClimbing ICC branch (2026-05-03):
        //   Same as client sm_getBaseDimensions_client — during isCrawlClimbing, box +1m mixin offset
        //   triggers → exceeds ladder grip area → unintended ICC trigger. Original 1:1 = apply smSmall dim
        //   (0.8, 0.62) (no original setHeightOffset call → previous isCrawling box remains).
        if (sm.isClimbCrawling) {
            cir.setReturnValue(EntityDimensions.scalable(0.6F, 0.8F).withEyeHeight(1.62F));
            return;
        }
        // 🔴 Separate isHeadJumping branch (2026-05-08, settled after multi-step fix) — 1:1 symmetric with client:
        //   Phase 1 dim (0.6, 0.8, 1.62F) → MixinEntity offset guard match → box +1m → infinite loop BUG.
        //   Phase 2 dim (0.6, **1.8**, 1.62F) → height STANDING → offset guard mismatch → box STANDING.
        //   Same mapping as client (MixinPlayerEntityClient) — block server reconcile.
        if (sm.isHeadJumping) {
            // 🔴 fix #13 (2026-05-08): Same mapping as client side — dim 0.6×0.8 + eyeHeight 1.62F.
            //   MixinEntity.sm_offsetBoundingBoxForFlying's POSE=SLIDING guard blocks mixin offset.
            cir.setReturnValue(EntityDimensions.scalable(0.6F, 0.8F).withEyeHeight(1.62F));
            return;
        }
        // 🔴 fix #59 (2026-05-10, dump analysis result — user report "during fox movement EYEHEIGHT set
        //   below collision"):
        //   During isSliding, fix #56's POSE.SLIDING guard activates mixin offset → box +1m up
        //   (= bb.minY = entity.y + 1m). However, dim eyeHeight=0.62 remains → camera = entity.y +
        //   0.62 → 0.38m below box (entity.y+1, entity.y+1.8).
        //   1.7.10/1.12.2's setHeightOffset(-1) effect = boundingBox +1m up + height 0.8 +
        //   eyeHeight unchanged (= STANDING 1.62 remains, position unchanged mapping).
        //   Solution: During isSliding, set eyeHeight=1.62 (= STANDING) → camera = entity.y + 1.62 →
        //   inside box (= entity.y+1.62 within entity.y+1 ~ entity.y+1.8) normal.
        if (sm.isSliding) {
            cir.setReturnValue(EntityDimensions.scalable(0.6F, 0.8F).withEyeHeight(1.62F));
            return;
        }
        // 🔵 (2026-05-20, Phase 0 validation fix #102) separate swim/dive branch — 1:1 symmetric with client mapping.
        //   Original 1.7.10 `setHeightOffset(-1F)` effect = box +1m up + keep eyeHeight 1.62 STANDING.
        //   Must sync 1:1 with swim/dive branch changes in client side `sm_getBaseDimensions_client`
        //   (= server reconcile + multi remote bb consistency).
        if (sm.isSwimming || sm.isDiving) {
            cir.setReturnValue(EntityDimensions.scalable(0.6F, 0.8F).withEyeHeight(1.62F));
            return;
        }
        // 🔴 v26.12 — Discard v26.5 guard (sync both client/server).
        //   STANDING dim fall through BUG root. Restore 1:1 intent of Fix 7 (= add isCrawlClimbing to smSmall).
        //   User report "block STANDING → lying down transition".
        boolean smSmall = sm.isCrawling || sm.isCrawlClimbing;
        if (smSmall) {
            cir.setReturnValue(EntityDimensions.scalable(0.6F, 0.8F).withEyeHeight(0.62F));
            return;
        }

        // 🔴 Attempt to restore original flying collision 1:1 (user request, phase 1):
        //   Original setHeightOffset(-1F) (L1694-L1704) → height 0.8, eyeHeight unchanged (1.62).
        //   1.21.1 mapping: dimensions = (0.6, 0.8, eyeHeight=1.62).
        //   Server side isFlying formula (original L2510): cfg.fly && abilities.flying && !swim && !dive.
        //   Apply same on both sides for client/server hitbox consistency. instanceof ServerPlayerEntity guard
        //   already exists at method entry — player variable available.
        boolean serverIsFlying = SmartMovingConfig.Config.fly
                              && player.getAbilities().flying
                              && !sm.isSwimming
                              && !sm.isDiving;
        if (serverIsFlying || sm.isLevitating) {
            cir.setReturnValue(EntityDimensions.scalable(0.6F, 0.8F).withEyeHeight(1.62F));
            return;
        }

        // SLIDING POSE entered from non-vanilla path (reinforcement for external mods etc).
        if (pose == Pose.SLIDING) {
            cir.setReturnValue(EntityDimensions.scalable(0.6F, 0.8F).withEyeHeight(0.62F));
            return;
        }

        // 🔴 orphan SWIMMING pose guard (2026-05-04 user report — "releasing sliding just before entering 1 space
        //   sometimes has collision smaller than normal lying down"):
        //   Same guard as client sm_getBaseDimensions_client — when pose=SWIMMING + smSmall mismatch,
        //   force STANDING dim. Keep box sync consistent by client/server symmetry (block server reconcile).
        if (pose == Pose.SWIMMING) {
            cir.setReturnValue(EntityDimensions.scalable(0.6F, 1.8F).withEyeHeight(1.62F));
        }
    }

    /**
     * 6-4 (server): Cancel vanilla updatePose() and force SM pose setting in SM movement states.
     *
     * **Focus #2.7 Phase D-1 (session 4, 2026-04-25)**: Completely symmetric with client (option 3).
     *   Same 4 branch mapping as client (MixinPlayerEntityClient.sm_updatePose_client):
     *     isCrawling || isCrawlClimbing            → SWIMMING (lying down)
     *     isHeadJumping || isSliding               → SLIDING  (flat)
     *     isSwimming || isDiving                   → SWIMMING (swimming)
     *     isFlying (original formula) || isLevitating     → SLIDING  (flying)
     *     others                                   → vanilla pass through
     *
     * Client/server POSE same sync → datatracker oscillation 0.
     */
    @Inject(method = "updatePlayerPose", at = @At("HEAD"), cancellable = true)
    private void sm_updatePose_server(CallbackInfo ci) {
        if (!((Object) this instanceof ServerPlayer player)) return;
        SmartMovingServer sm = SmartMovingServer.get(player);

        if (sm.isCrawling || sm.isCrawlClimbing) {
            player.setPose(Pose.SWIMMING);
            ci.cancel();
        } else if (sm.isHeadJumping || sm.isSliding) {
            player.setPose(Pose.SLIDING);
            ci.cancel();
        } else if (sm.isSwimming || sm.isDiving) {
            player.setPose(Pose.SWIMMING);
            ci.cancel();
        }
        // 🔴 BUG-29 real cause fix (Flying Phase / session 45): Remove flying/Levitate branch (server side).
        //   Same fix as client side sm_updatePose_client — pass vanilla STANDING POSE during flying.
        //   Original 1.7.10 vanilla has no POSE system → flying stays STANDING. 1.21.1 incorrect mapping
        //   = force SLIDING → camera at feet. Fix: pass vanilla → STANDING POSE → eyeHeight 1.62.
        //   Keep client/server symmetry → datatracker oscillation 0.
        // isFlying/isLevitating/isDipping/others → pass vanilla updatePose() (STANDING etc)
    }

    /**
     * Block sneak pose during falling — redirect only isSneaking() call in vanilla updatePose().
     *
     * Original 1.7.10 1:1 basis (SmartMovingModel.animateSneaking L681-685):
     *   `if(isStandard && !isAngleJumping) imp.superAnimateSneaking(...);`
     *   When isFalling branch entered, isStandard=false → skip vanilla sneak visual application.
     *
     * 1.21.1 EntityPose system difference:
     *   1.7.10 vanilla: no boundingBox change during sneak + only 0.08 eyeHeight reduction → minimal jitter.
     *   1.21.1 vanilla: pose=CROUCHING → boundingBox.height 1.5 (vs 1.8) + eyeHeight 1.27
     *     (vs 1.62) → lerp jump occurs on camera/hitbox toggle.
     *
     * Solution: Redirect only isSneaking() call (bytecode offset 68) in PlayerEntity.updatePose →
     *   return false during falling → skip CROUCHING branch (offset 67-88) → STANDING fallthrough.
     *   Other vanilla branches (FallFlying/Sleeping/Swimming/SpinAttack) priority unchanged.
     *
     * Location: main mixin set so auto-applies to both client + server (no instanceof guard needed).
     *   - Client: pose=STANDING → no hitbox/eye height toggle + setModelPose auto sets model.sneaking=false
     *     → auto skip sneak branch in BipedEntityModel.setAngles.
     *   - Server: pose=STANDING → client/server hitbox sync → avoid position reject like passing narrow ceiling.
     *
     * SM states (Crawling/CrawlClimbing/HeadJumping/Sliding/Swimming/Diving) are already handled by sm_updatePose_*
     * after ci.cancel() with setPose() → vanilla body not executed → this redirect unaffected.
     */
    @Redirect(method = "getDesiredPose",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/world/entity/player/Player;isShiftKeyDown()Z"))
    private boolean sm_redirectIsSneakingForFallingPose(Player self) {
        boolean original = self.isShiftKeyDown();
        if (!original) return false;
        if (!SmartMovingConfig.Config.enabled) return original;

        // isFalling guard (same condition as sm_setAnglesHead):
        //   !onGround && fallDistance > fallAnimationDistanceMinimum && !touchingWater &&
        //   !fallFlying && !abilities.flying.
        // SM states (isClimbing etc) are stored separately in SmartMovingClientState/Server so cannot
        // directly reference from both sides. However, SmartMovingClimber resets fallDistance=0 during climbing,
        // so fallDistance > minimum condition naturally excludes climbing.
        boolean isFalling = !self.onGround()
                && self.fallDistance > SmartMovingConfig.Config.fallAnimationDistanceMinimum
                && !self.isInWater()
                && !self.isFallFlying()
                && !self.getAbilities().flying;
        if (isFalling) return false;

        // 🔴 fix #77 (BUG #2.6, 2026-05-10, user report "weeping/twisting vines + sneak causes box to shrink"):
        //   Normal ladder/vine + sneak: sm_resetSneakInClimb guard (`sm.isClimbing` etc) matches →
        //     force setPose(STANDING) → maintain box 1.8m. Hanging = SM's own motion handling.
        //   weeping/twisting vines (vanilla bypass = sm.isClimbing=false): sm_resetSneakInClimb not triggered
        //     → after fix #75, vanilla isSneaking()=true → vanilla updatePose → CROUCHING → box 1.5m.
        //
        //   Solution: Redirect only isSneaking() call in vanilla updatePose → return false → skip CROUCHING branch
        //     → STANDING fallthrough. isHoldingOntoLadder() = isSneaking() call not affected by redirect
        //     (called from different vanilla method) → maintain hanging (= preserve functionality).
        //   Align with intent in project_vine_animation_complete.md "weeping/twisting vines = ladder pose
        //     + vanilla hanging motion".
        //   This redirect is PlayerEntity (main mixin) so auto-applies to both client + server.
        net.minecraft.world.level.block.Block blockAtPos = self.level()
                .getBlockState(self.blockPosition()).getBlock();
        if (blockAtPos == net.minecraft.world.level.block.Blocks.WEEPING_VINES
                || blockAtPos == net.minecraft.world.level.block.Blocks.WEEPING_VINES_PLANT
                || blockAtPos == net.minecraft.world.level.block.Blocks.TWISTING_VINES
                || blockAtPos == net.minecraft.world.level.block.Blocks.TWISTING_VINES_PLANT) {
            return false;
        }

        return original;
    }

    /**
     * 3-3: Block addExhaustion() exhaustion.
     * Blocks all vanilla exhaustion addition while disableAddExhaustion is true.
     *
     * addExhaustion() is declared in PlayerEntity so inject here.
     * Not declared in ServerPlayerEntity so cannot inject in MixinServerPlayerEntity.
     */
    @Inject(method = "causeFoodExhaustion", at = @At("HEAD"), cancellable = true)
    private void sm_addExhaustion(float exhaustion, CallbackInfo ci) {
        if (!((Object) this instanceof ServerPlayer player)) return;
        SmartMovingServer sm = SmartMovingServer.get(player);
        if (sm.disableAddExhaustion) {
            ci.cancel();
        }
    }
}
