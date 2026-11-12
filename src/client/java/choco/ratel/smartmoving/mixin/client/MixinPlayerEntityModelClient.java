package choco.ratel.smartmoving.mixin.client;

import choco.ratel.smartmoving.client.SmModelPartOverride;
import choco.ratel.smartmoving.client.SmartMovingAvatarRenderState;
import choco.ratel.smartmoving.client.SmartMovingClientState;
import choco.ratel.smartmoving.client.SmartMovingRenderContext;
import choco.ratel.smartmoving.config.SmartMovingConfig;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 12-1: setAngles() Mixin — SM 11 movement state animations.
 * 12-2: animateAngleJumping() — directional jump arm/leg angles.
 * 8-3 / 6-4: force leaningPitch to 0 — block vanilla -90° auto-rotation during swimming/diving/crawling.
 *
 * Original: ported from SmartMovingModel.setRotationAngles().
 * State priority (if-else chain order):
 *   isClimbing/isCrawlClimbing → isClimbJumping → isCeilingClimbing →
 *   isSwimming_sm → isDiving → isCrawling → isSliding →
 *   isFlying → isHeadJumping → isFalling → else(standard)
 *
 * Angle constants (radian based on SmartRenderContext):
 *   Half=π, Quarter=π/2, Eighth=π/4, Sixteenth=π/8,
 *   Thirtytwoth=π/16, Sixtyfourth=π/32
 *
 * NOTE: rotation order conversion handled by setAnglesYZX/setAnglesZXY helpers.
 *       original YZX GL order → JOML qY*qZ*qX → getEulerAnglesZYX → ModelPart pitch/yaw/roll.
 *       original ZXY GL order → JOML qZ*qX*qY → same conversion.
 */
@Environment(EnvType.CLIENT)
@Mixin(HumanoidModel.class)
public abstract class MixinPlayerEntityModelClient {

    // ── Angle constants (based on SmartRenderContext/SmartRenderUtilities) ─────────────
    private static final float HALF        = (float) Math.PI;        // π
    private static final float QUARTER     = HALF / 2f;              // π/2
    private static final float EIGHTH      = HALF / 4f;              // π/4
    private static final float SIXTEENTH   = HALF / 8f;              // π/8
    private static final float THIRTYTWOTH = HALF / 16f;             // π/16
    private static final float SIXTYFOURTH = HALF / 32f;             // π/32
    private static final float WHOLE       = HALF * 2f;              // 2π
    private static final float DEG_TO_RAD  = (float) (Math.PI / 180.0); // degrees to radians

    // ── BipedEntityModel parts @Shadow ────────────────────────────────────────
    @Shadow public ModelPart head;
    @Shadow public ModelPart hat;
    @Shadow public ModelPart body;
    @Shadow public ModelPart rightArm;
    @Shadow public ModelPart leftArm;
    @Shadow public ModelPart rightLeg;
    @Shadow public ModelPart leftLeg;

    // 🔴 (Phase 2 multi BUG-4) Block ModelPart pivot leak — prevent leak between players of single-instance model.
    //   Root cause: PlayerEntityModel is a single instance shared by all player renders.
    //   One player's SM branch (= sm_animateCrawling, etc.) changes head.pivotZ/body.pivotZ/leg.pivotX, etc. →
    //   vanilla setAngles resets only some fields per call → lingers in next player render → A's pose leaks to B.
    //   Solution: cache default pivot on first setAngles HEAD → restore cached values every setAngles HEAD.
    //   vanilla setAngles makes additional pivot changes in sneak branch, etc. → SM branch makes additional changes → normal flow.
    @Unique private boolean sm_pivotDefaultsCached = false;
    @Unique private float sm_headPivotX, sm_headPivotY, sm_headPivotZ;
    @Unique private float sm_bodyPivotX, sm_bodyPivotY, sm_bodyPivotZ;
    @Unique private float sm_rArmPivotX, sm_rArmPivotY, sm_rArmPivotZ;
    @Unique private float sm_lArmPivotX, sm_lArmPivotY, sm_lArmPivotZ;
    @Unique private float sm_rLegPivotX, sm_rLegPivotY, sm_rLegPivotZ;
    @Unique private float sm_lLegPivotX, sm_lLegPivotY, sm_lLegPivotZ;

    @Unique
    private void sm_cachePivotDefaultsIfNeeded() {
        if (sm_pivotDefaultsCached) return;
        sm_headPivotX = head.x; sm_headPivotY = head.y; sm_headPivotZ = head.z;
        sm_bodyPivotX = body.x; sm_bodyPivotY = body.y; sm_bodyPivotZ = body.z;
        sm_rArmPivotX = rightArm.x; sm_rArmPivotY = rightArm.y; sm_rArmPivotZ = rightArm.z;
        sm_lArmPivotX = leftArm.x;  sm_lArmPivotY = leftArm.y;  sm_lArmPivotZ = leftArm.z;
        sm_rLegPivotX = rightLeg.x; sm_rLegPivotY = rightLeg.y; sm_rLegPivotZ = rightLeg.z;
        sm_lLegPivotX = leftLeg.x;  sm_lLegPivotY = leftLeg.y;  sm_lLegPivotZ = leftLeg.z;
        sm_pivotDefaultsCached = true;
    }

    @Unique
    private void sm_restorePivotDefaults() {
        head.x = sm_headPivotX; head.y = sm_headPivotY; head.z = sm_headPivotZ;
        body.x = sm_bodyPivotX; body.y = sm_bodyPivotY; body.z = sm_bodyPivotZ;
        rightArm.x = sm_rArmPivotX; rightArm.y = sm_rArmPivotY; rightArm.z = sm_rArmPivotZ;
        leftArm.x  = sm_lArmPivotX; leftArm.y  = sm_lArmPivotY; leftArm.z  = sm_lArmPivotZ;
        rightLeg.x = sm_rLegPivotX; rightLeg.y = sm_rLegPivotY; rightLeg.z = sm_rLegPivotZ;
        leftLeg.x  = sm_lLegPivotX; leftLeg.y  = sm_lLegPivotY; leftLeg.z  = sm_lLegPivotZ;
        // Also restore rotation fields that vanilla setAngles doesn't reset (= some roll/yaw) — block leak.
        head.zRot = 0f;
        body.zRot = 0f;
        rightArm.zRot = 0f; leftArm.zRot = 0f;
        rightLeg.zRot = 0f; leftLeg.zRot = 0f;
    }

    /**
     * BipedEntityModel.sneaking — PlayerEntityRenderer.setModelPose sets per frame
     * `model.sneaking = entity.isInSneakingPose()`. The sneak branch of setAngles
     * (body.pitch=0.5, leg.pivotZ=4, head.pivotY=4.2, body.pivotY=3.2, arm.pivotY=5.2, etc.)
     * observes this field.
     */
    /**
     * To block vanilla sneak pose during falling, save original sneaking value on setAngles HEAD entry.
     * Restore it at TAIL start to maintain consistency with external references like layer renderers.
     *
     * Original 1.7.10 equivalence — SmartMovingModel.animateSneaking (L681-685):
     *   `if(isStandard && !isAngleJumping) imp.superAnimateSneaking(...);`
     *   When isFalling branch is entered, isStandard=false → skip vanilla sneak super call.
     *
     * 1.21.1 vanilla's setAngles is a unified method so cannot SKIP directly → temporarily set sneaking field to false
     * to bypass vanilla `if(this.sneaking)` branch to else branch (normal standing values).
     * Result: body.pitch=0, leg.pivotZ=0, leg.pivotY=12.0, head.pivotY=0,
     * body.pivotY=0, arm.pivotY=2.0F are applied, canceling residual sneak pose.
     */
    @Unique
    private boolean smOriginalSneakingForFalling;

    // ── [12-1] setAngles Mixin (HEAD — block vanilla sneak branch during falling) ─────────
    //
    // Equivalent to original 1.7.10 SmartMovingModel.animateSneaking skipping vanilla
    // superAnimateSneaking call when isStandard=false. In 1.21.1, setAngles is a unified
    // method so cannot skip directly → temporarily set sneaking field to false.
    //
    // Condition: cfgEnabled && !onGround && fallDistance > fallAnimationDistanceMinimum
    //            && !climbing family && !touchingWater (= same as isFallingForReset).
    @Inject(method = "setupAnim(Lnet/minecraft/client/renderer/entity/state/HumanoidRenderState;)V",
            at = @At("HEAD"))
    private void sm_setAnglesHead(
            HumanoidRenderState renderState,
            CallbackInfo ci) {
        // 🔴 (BUG A fix, 2026-05-13) ModelPart override clear — position *before* firstPersonArmRender guard.
        //   PlayerEntityModel is single instance — shared by all players. If sm_animateSliding applied
        //   override quat to remote's rightArm/leftArm in previous frame (= 90° slide rotation), it persists in that instance.
        //   Same frame self first-person hand render calls model.setAngles but returns due to firstPersonArmRender
        //   guard → clear doesn't execute → first-person hand drawn with slide pose *appears to disappear*.
        //   Solution: move *before* guard. First-person hand render attempts clear every frame → maintains vanilla default pose.
        ((SmModelPartOverride)(Object) rightArm).sm_clearOverrideQuat();
        ((SmModelPartOverride)(Object) leftArm).sm_clearOverrideQuat();

        // 🔴 (2026-05-05 Phase 1-A) Guard changed to apply SM pose when rendering other players:
        //   AbstractClientPlayerEntity = includes both local + remote players.
        if (!(renderState instanceof AvatarRenderState avatarState)
                || !((Object) avatarState instanceof SmartMovingAvatarRenderState smartState)) return;
        AbstractClientPlayer player = smartState.sm_getPlayer();
        if (player == null) return;

        // First-person hand render context (during PlayerEntityRenderer.renderArm) — skip all SM hooks.
        //   User intent: first-person hand always maintains vanilla default pose.
        //   Other players never enter firstPersonArmRender context → unaffected (guard as-is).
        if (SmartMovingRenderContext.firstPersonArmRender) return;

        // 🔴 (Phase 2 multi BUG-9 regression prevention) sm_cachePivotDefaultsIfNeeded + sm_restorePivotDefaults disabled.
        //   Root cause: default cached at first setAngles may be vanilla-modified values like sneak pose →
        //              each frame thereafter restores with wrong default → sliding etc. self pose also regresses.
        //   Alternative: guard-removed reset chunk pattern (L301 / cleanup branch) — BUG-8 reprocessing item.

        // Always save original (ensure TAIL restore safety even in cfgEnabled=false branch)
        smOriginalSneakingForFalling = renderState.isCrouching;

        // 🔴 (2026-05-05) self → Config.enabled / remote → always true.
        if (!choco.ratel.smartmoving.client.SmartMovingClient.isSmRenderEnabled(player)) return;

        SmartMovingClientState sm = SmartMovingClientState.get(player);
        // 🔴 (Phase 2 multi BUG-1) Direct fallDistance check → sm.doFallingAnimation.
        //   vanilla 1.21.1 server doesn't sync other players' fallDistance → remote always 0
        //   → sm_animateFalling branch never entered → user report "falling animation not visible".
        //   sm.doFallingAnimation synced by SmartMovingState bit 16 server-relay (sends calculation result per tick).
        //   Its definition itself expands equivalent to isFallingForReset formula (see sendStatePacket).
        boolean isFallingForReset = sm.doFallingAnimation;

        // 🔴 weeping/twisting vines climbing + sneak mapped same as ladder pose (user report resolved):
        //   ladder climbing + sneak: sm_resetSneakInClimb (MixinClientPlayerEntity L90) forces input.sneaking
        //     /isSneaking/pose false → vanilla setAngles sneak branch skips → no sneak pose.
        //   weeping/twisting vines: sm_resetSneakInClimb doesn't trigger (sm.isClimbing=false) →
        //     vanilla sneak pose + ladder pose combined visual.
        //   Solution: separate poses only — temporarily set sneaking=false to skip only vanilla setAngles sneak branch.
        //     motion stays vanilla (user intent — feature unmodified).
        Block blockAtPos = player.level().getBlockState(player.blockPosition()).getBlock();
        boolean isWeepingTwistingVines = (blockAtPos == Blocks.WEEPING_VINES
                || blockAtPos == Blocks.WEEPING_VINES_PLANT
                || blockAtPos == Blocks.TWISTING_VINES
                || blockAtPos == Blocks.TWISTING_VINES_PLANT);

        if (isFallingForReset || isWeepingTwistingVines) {
            renderState.isCrouching = false;
        }

        boolean anySmState = sm.isRopeSliding || sm.isClimbing || sm.isCrawlClimbing
                || sm.isCeilingClimbing || sm.isClimbJumping || sm.isSwimming_sm
                || sm.isDiving || sm.isCrawling || sm.isSliding || sm.isHeadJumping
                || sm.isFlying || sm.doFallingAnimation || isWeepingTwistingVines
                || sm.isAngleJumping();
        if (anySmState) {
            renderState.swimAmount = 0F;
        }
    }

    // ── animateArms Override (left-click hand swing) — fix #187 (2026-05-28) ─────────
    //
    // User report: "overall swing motion feels fast. Fox/HJ movement pronounced".
    //
    // ROOT CAUSE (= dump analysis + vanilla 1.21.1 disassembly + original 1.7.10/1.12.2 verbatim comparison):
    //   - vanilla 1.21.1 `BipedEntityModel.animateArms` = quartic ease (`f *= f; f *= f`).
    //     → swing≈0.16 (= 1 tick = 0.05 sec) reaches amplitude PEAK then quickly decays = 1-tick spike.
    //   - vanilla additional effects: arm.pivot ±5 unit sway (shoulder left/right/front/back swing) +
    //     both arm.yaw += body.yaw → visually "large motion".
    //   - Original 1.7.10 SR.animateWorkingBody+Arms / 1.12.2 SRModel.animateWorking* = cubic ease.
    //     swing=1 (= 6 tick = 0.3 sec) reaches PEAK. No shoulder sway. Natural swing motion.
    //
    // FIX (user decision — option 1: override animateArms itself + restore original formula 1:1):
    //   Block vanilla animateArms via @Inject HEAD @Cancellable + apply original formula 1:1.
    //   Consistent effect for both STAND/SM phases (user "overall" = includes STAND).
    //
    // Guards:
    //   - swing <= 0 equivalent to vanilla early-return path (= no cancel, just return).
    //   - AbstractClientPlayerEntity guard (self + remote).
    //   - vanilla maintained in SmartMovingRenderContext.firstPersonArmRender (block first-person hand leak pattern).
    //   - vanilla maintained when SmartMovingClient.isSmRenderEnabled false (SM disabled toggle).
    @Inject(method = "setupAttackAnimation",
            at = @At("HEAD"),
            cancellable = true)
    private void sm_animateArmsOverride(HumanoidRenderState renderState, CallbackInfo ci) {
        // vanilla early return path equivalent (26.3 renamed attackTime to swingAnimation)
        float swing = renderState.swingAnimation;
        if (swing <= 0F) return;

        // self + remote guard
        if (!(renderState instanceof AvatarRenderState avatarState)
                || !((Object) avatarState instanceof SmartMovingAvatarRenderState smartState)) return;
        AbstractClientPlayer player = smartState.sm_getPlayer();
        if (player == null) return;

        // first-person hand guard — maintain vanilla (BUG A pattern: skip SM hooks in firstPersonArmRender area)
        if (SmartMovingRenderContext.firstPersonArmRender) return;

        // maintain vanilla when SM disabled
        if (!choco.ratel.smartmoving.client.SmartMovingClient.isSmRenderEnabled(player)) return;

        // restore original 1.7.10/1.12.2 SR.animateWorkingBody + animateWorkingArms 1:1.
        // Arm determination: original 1.7.10 based on mainArm (no preferredHand concept).
        HumanoidArm mainArm = player.getMainArm();
        ModelPart arm = (mainArm == HumanoidArm.LEFT) ? this.leftArm : this.rightArm;

        // 🔴 fix #190 (2026-05-28): 1:1 mapping of original SmartMovingModel.animateWorkingBody (L667-673)
        //   `if(isStandard)` guard. SKIP vanilla body sway formula in SM phase.
        //
        // User report (after fix #189): "Body sways when arm swings during SM. Originally didn't".
        // Original fact: SmartMovingModel.animateWorkingBody → when isStandard=false SKIP vanilla
        //   body sway formula. Instead call animateNonStandardWorking (= only shoulder 90° upright,
        //   body.yaw unchanged).
        // animateWorkingArms has separate guard `isStandard || isWorking()` → call attempted in SM phase + swing
        //   (= preferredArm.pitch/yaw/roll formulas applied as-is).
        SmartMovingClientState sm = SmartMovingClientState.get(player);
        boolean anySmState = sm.isFlying || sm.isCrawling || sm.isSliding || sm.isHeadJumping
                || sm.isClimbing || sm.isCrawlClimbing || sm.isCeilingClimbing
                || sm.isSwimming_sm || sm.isDiving || sm.isRopeSliding || sm.isClimbJumping
                || sm.doFallingAnimation || sm.isAngleJumping();

        // animateWorkingBody (1.7.10 SR L298-304, 1.12.2 SR L401-406): only in isStandard.
        //   angle = sin(sqrt(swing) * 2π) * 0.2
        //   body.yaw += angle
        //   leftArm.pitch += angle
        if (!anySmState) {
            float angle = Mth.sin(Mth.sqrt(swing) * WHOLE) * 0.2F;
            this.body.yRot += angle;
            this.leftArm.xRot += angle;
        }

        // animateWorkingArms (1.7.10 SR L306-315, 1.12.2 SR L408-416):
        //   isStandard || isWorking() (= swing>0) → attempt apply in SM phase + swing.
        //   f6 = 1 - (1-swing)^3                  ← cubic (vanilla 1.21.1 is quartic)
        //   f7 = sin(f6 * π)
        //   f8 = sin(swing * π) * -(head.pitch - 0.7) * 0.75
        //   preferredArm.pitch -= f7 * 1.2 + f8
        //   preferredArm.yaw  += sin(sqrt(swing) * 2π) * 0.4
        //   preferredArm.roll -= sin(swing * π) * 0.4
        float f6 = 1.0F - swing;
        f6 = 1.0F - f6 * f6 * f6;
        float f7 = Mth.sin(f6 * HALF);
        float f8 = Mth.sin(swing * HALF) * -(this.head.xRot - 0.7F) * 0.75F;
        arm.xRot -= f7 * 1.2F + f8;
        arm.yRot  += Mth.sin(Mth.sqrt(swing) * WHOLE) * 0.4F;
        arm.zRot -= Mth.sin(swing * HALF) * 0.4F;

        ci.cancel(); // block vanilla quartic formula + arm.pivot shoulder sway + arm.yaw batch += body.yaw
    }

    // ── [12-1] setAngles Mixin (TAIL — SM overwrites after vanilla animation completes) ──

    @Inject(method = "setupAnim(Lnet/minecraft/client/renderer/entity/state/HumanoidRenderState;)V",
            at = @At("TAIL"))
    private void sm_setAngles(
            HumanoidRenderState renderState,
            CallbackInfo ci) {
        // 🔴 (2026-05-05 Phase 1-A) AbstractClientPlayerEntity = local + remote.
        //   Guard expanded to apply SM pose when rendering other players.
        if (!(renderState instanceof AvatarRenderState avatarState)
                || !((Object) avatarState instanceof SmartMovingAvatarRenderState smartState)) return;
        AbstractClientPlayer player = smartState.sm_getPlayer();
        if (player == null) return;

        float limbSwing = renderState.walkAnimationPos;
        float limbSwingAmount = renderState.walkAnimationSpeed;
        float animationProgress = renderState.ageInTicks;
        float headYaw = renderState.yRot;
        float headPitch = renderState.xRot;

        // 🔴 (Phase 2 multi BUG-13) Move firstPersonArmRender guard after reset chunk.
        //   Previous: return immediately on entry → skip reset chunk → changes from other players' ModelParts
        //             (arm.roll, scales, leg.roll, etc.) linger → leak to own first-person hand.
        //   Change: apply reset chunk then guard before entering SM branch → attempt reset in first-person hand render →
        //           ModelPart becomes default → own first-person hand normal pose.

        // 🔴 restore sneaking — restore value temporarily set to false in HEAD right after vanilla setAngles ends.
        //   Even if layer renderer (cape/armor) in same frame references sneaking via copyBipedStateTo etc.,
        //   maintain consistency with entity's actual pose.
        //   (always set regardless of condition — always save original in HEAD so safe.)
        renderState.isCrouching = smOriginalSneakingForFalling;

        SmartMovingClientState sm = SmartMovingClientState.get(player);

        // ── [8-3][6-4] Force leaningPitch to 0 when SM active ─────────────────
        // When leaningPitch > 0, setupTransforms Branch 2(-90° X rotation) and
        // setAngles Step 13 (swimming arm animation) are activated. Suppress in SM state.
        // BUG-7 (session 36): Add Config.enabled guard to flyingCreative.
        //   Previous: only vanilla `player.getAbilities().flying` → even when SM disabled anySmState true →
        //   call sm_animateFlying + reset infrastructure operates → SM flying animation lingers (= toggle useless).
        //   Solution: change to cfgEnabled && capabilities.flying (only process SM flying when SM enabled).
        // 🔴 (2026-05-05) self → Config.enabled / remote → always true.
        boolean cfgEnabled = choco.ratel.smartmoving.client.SmartMovingClient.isSmRenderEnabled(player);
        // 🔴 (Phase 1-A) Use sm.isFlying to apply flying pose to other players too.
        //   Previous `player.getAbilities().flying` not synced by Mojang datatracker to other players
        //   → other players always false → sm_animateFlying not called BUG. sm.isFlying is
        //   properly server-relay synced (= both local + remote).
        boolean flyingCreative = cfgEnabled && sm.isFlying;
        // 🔴 (2026-04-27): falling also included in anySmState — cancel via reset
        //   infrastructure the body.yaw wobble effect from vanilla animateArms during swing (user report "air fall swing body jitter").
        //   Entry condition same as if-else chain falling guard (below).
        // 🔴 (Phase 2 multi BUG-1) Direct fallDistance check → sm.doFallingAnimation (server-relay sync).
        boolean isFallingForReset = cfgEnabled && sm.doFallingAnimation;
        // 🔴 weeping/twisting vines climbing apply ladder climbing pose animation.
        //   User intent: don't apply SM free climbing / auto enter / sneak hold etc. features at all.
        //   Keep vanilla 1.21.1 ladder climbing motion as-is (maintain sm_travel_client vanilla bypass).
        //   Only call animation sm_animateClimbing (isHandsVineClimbing=false → ladder pose).
        //   Check block every frame (at player position).
        boolean isWeepingTwistingVines = false;
        if (cfgEnabled) {
            Block blockAtPos = player.level().getBlockState(player.blockPosition()).getBlock();
            isWeepingTwistingVines = (blockAtPos == Blocks.WEEPING_VINES
                    || blockAtPos == Blocks.WEEPING_VINES_PLANT
                    || blockAtPos == Blocks.TWISTING_VINES
                    || blockAtPos == Blocks.TWISTING_VINES_PLANT);
            // 🔴 User report fix (2026-05-04 — "don't show vine animation during flying"):
            //   Don't apply vine pose during flying → enter flying pose (= isFlying branch).
            if (isWeepingTwistingVines && sm.isFlying) {
                isWeepingTwistingVines = false;
            }
        }

        boolean anySmState = sm.isRopeSliding || sm.isClimbing || sm.isCrawlClimbing || sm.isCeilingClimbing
                || sm.isClimbJumping || sm.isSwimming_sm || sm.isDiving
                || sm.isCrawling || sm.isSliding || sm.isHeadJumping || flyingCreative
                || isFallingForReset
                || isWeepingTwistingVines
                || sm.isAngleJumping();   // 🔴 (2026-04-27) activate reset infrastructure during angle jump —
                                          // reset head.pivotZ/body.yaw/head.roll/arm.pivot to 0 →
                                          // cancel previous SM branch linger + vanilla animateArms swing body.yaw wobble.
        if (anySmState) renderState.swimAmount = 0f;

        // 🔴 D-4 exit edge cleanup (user report — body pulled forward lingering after climbing ends):
        //   All nodes' pivotZ=-6 set on D-4 branch entry don't reset when climbing ends.
        //   Track previous frame climbing state → once on exit edge (true → false) explicit cleanup.
        //   leg.pivotX also set to ±2.0 on sm_animateClimbing entry so restore to vanilla default
        //   ±1.9 (defined value in 1.21.1 PlayerEntityModel layer definition).
        // 🔴 weeping/twisting vines also track cleanup — restore leg.pivotX/Y / pivotZ to vanilla on exit.
        boolean smIsClimbingNow = sm.isClimbing || sm.isCrawlClimbing || sm.isCeilingClimbing
                || isWeepingTwistingVines;
        if (sm.smWasClimbingForCleanup && !smIsClimbingNow) {
            body.z     = 0f;
            head.z     = 0f;
            rightArm.z = 0f;
            leftArm.z  = 0f;
            rightLeg.z = 0f;
            leftLeg.z  = 0f;
            rightLeg.x = -1.9f;
            leftLeg.x  =  1.9f;
            // D-4 leg.pivotY correction mapping (12*cos(0.5)≈10.529) → reset to vanilla default 12.
            rightLeg.y = 12f;
            leftLeg.y  = 12f;
            // 🔴 User report fix (2026-05-04 — "ladder/vine sneak+s then reattach sometimes arm shortens"):
            //   sm_animateClimbing isHandsVineClimbing branch (L591) = setArmScales(abs(cos), abs(cos)) →
            //   changes arm yScale. When vine ends vanilla setAngles doesn't reset yScale → lingers.
            //   isFeetVineClimbing branch (L631) setLegScales same pattern.
            //   fix: add scale reset to climbing exit edge cleanup (= restore 1.0 default).
            setArmScales(rightArm, leftArm, 1f, 1f);
            setLegScales(rightLeg, leftLeg, 1f, 1f);
        }
        sm.smWasClimbingForCleanup = smIsClimbingNow;

        // 🔴 isCrawling exit cleanup (user report fix — "linger when exiting prone state", 2026-05-03):
        //   Vanilla non-reset values set by sm_animateCrawling (head.pivotZ, head.roll, body.yaw/roll,
        //   leg.roll, arm.yaw/roll, scales) reset once to vanilla default on exit edge.
        //   Avoid wrong pattern from memory `feedback_animation_porting.md` (unconditionally reset pivotY/leg.pivotZ)
        //   — don't touch values set by vanilla during sneaking (pivotY 3.2, etc.).
        //   Follow D-4 cleanup pattern as-is (= apply only once on exit edge).
        if (sm.smWasCrawlingForCleanup && !sm.isCrawling) {
            head.z = 0f;
            head.zRot   = 0f;
            body.yRot    = 0f;
            body.zRot   = 0f;
            rightLeg.zRot = 0f;
            leftLeg.zRot  = 0f;
            rightArm.yRot  = 0f;
            leftArm.yRot   = 0f;
            rightArm.zRot = 0f;
            leftArm.zRot  = 0f;
            // restore scales = 1.0 (default) — cancel sm_animateCrawling setLegScales/setArmScales
            setLegScales(rightLeg, leftLeg, 1f, 1f);
            setArmScales(rightArm, leftArm, 1f, 1f);
        }
        sm.smWasCrawlingForCleanup = sm.isCrawling;
        // pivot/yaw/roll reset infrastructure (B-9/B-11/B-13 dependent): vanilla setAngles doesn't
        //   reset head/body pivotZ, body.yaw, head.roll every frame
        //   (sneak branch only changes leg.pivotZ / body.yaw only set in animateArms
        //   handSwingProgress > 0 branch / head.roll unused in vanilla).
        //   To prevent changes from previous SM branch accumulating to next branch,
        //   reset to vanilla default (0) right before entering SM branch or
        //   on SM disabled transition (BUG-7). Include (!cfgEnabled) beyond anySmState:
        //   clean up lingering SM changes when SM disabled.
        // 🔴 (Phase 2 multi BUG-8) Remove anySmState/cfgEnabled guards — unconditionally reset every frame.
        //   Root cause: PlayerEntityModel single instance — ModelPart field changed in A's SM branch
        //              lingers when B's setAngles entered → user report "when a crouches b has same pose".
        //              Guard (anySmState || !cfgEnabled) validates self only → leak in multi environment.
        //   Solution: remove guard. Reset every time even for players' setAngles not entering SM branch → block leak.
        //             even vanilla standing pose maintains head.pivotZ=0/body.pivotZ=0/body.yaw=0/head.roll=0 default →
        //             no vanilla effect. arm.pivot defaults ±5/0 are also vanilla setAngles Step 4 defaults.
        head.z = 0f;
        body.z = 0f;
        head.zRot   = 0f;
        rightLeg.zRot = 0f;
        leftLeg.zRot  = 0f;
        setLegScales(rightLeg, leftLeg, 1f, 1f);
        setArmScales(rightArm, leftArm, 1f, 1f);
        // 🔴 fix #180 v2 (2026-05-27, dump analysis + user question (4) reflected):
        //   Previous unconditional reset every frame cancels vanilla animateArms swing effect:
        //     - body.yaw = sin(sqrt(swing)*2π) * 0.2 → 0 cancel.
        //     - arm.pivotX = -cos(body.yaw)*5 → -5 cancel.
        //     - arm.pivotZ = sin(body.yaw)*5 → 0 cancel.
        //     - arm.roll = sin(swing*π) * -0.4 → 0 cancel.
        //   User verbatim "STAND swing also weird" + "just keep vanilla default" → reset infrastructure
        //     breaks normal vanilla swing. Solution: when self + swing skip reset only for swing-affected items.
        //   Multi leak defense — naturally resets in frames without swing.
        // 26.3 moved attackAnim into LivingEntity.SwingState; getSwingAnimation(1.0F) is its tick value.
        boolean preserveSwing = player.getSwingAnimation(1.0F) > 0F
                && net.minecraft.client.Minecraft.getInstance().player == player;
        if (!preserveSwing) {
            body.yRot    = 0f;
            leftArm.x  =  5f;
            leftArm.z  =  0f;
            rightArm.x = -5f;
            rightArm.z =  0f;
            rightArm.zRot = 0f;
            leftArm.zRot  = 0f;
        }

        // BUG-13/16 (session 36): skip all SM branches + isFalling + isAngleJumping at once when SM disabled.
        //   Previous: sm.* branches in if-else chain safe because sm.* itself only true after cfg.enabled,
        //   but else branch (isFalling) checks only vanilla condition → call sm_animateFalling even when SM disabled
        //   (user report = falling motion lingering). Also isAngleJumping based on SmartMovingJumper
        //   lingering state so no guarantee of false even when disabled.
        //   Solution: when cfgEnabled false return immediately at entry point — skip all SM branches + vanilla normal.
        //   reset infrastructure + cloak.pitch already handled above so lingering cleanup guaranteed.
        if (!cfgEnabled) return;

        // 🔴 (Phase 2 multi BUG-13) Skip SM branch when rendering first-person hand — reset chunk already applied above.
        //   Other players' setAngles result (= ModelPart arm.roll/scales/leg.roll, etc.) lingering leaks to own first-person hand →
        //   apply reset chunk only and don't enter SM branch → ModelPart default → own first-person hand normal.
        if (SmartMovingRenderContext.firstPersonArmRender) return;

        // ── [12-7] smallOverGroundHeight calculation ─────────────────────────────────
        // Original: SmartMovingRender.rotatePlayer() → moving.getOverGroundHeight(5D)
        // Calculate distance from feet to ground only in isCrawlClimbing/isHeadJumping states.
        if (sm.isCrawlClimbing || sm.isHeadJumping) {
            // 🔴 (Phase 2 multi BUG-6) Remove ClientPlayerEntity guard — compute for all players.
            //   computeSmallOverGroundHeight = entity position + world block scan → can calculate
            //   accurately for remote using server-sync entity.y + client-side world.
            //   Previous: self only → remote smallOverGroundHeight=0 → height=0.25 → large bodyAngleX →
            //             isCrawlClimbing pose looks wrong visually (user report "crawling forward animation only").
            sm.smallOverGroundHeight = computeSmallOverGroundHeight(player, player.level());
        }

        // ── SM 11-state if-else chain (SmartMovingModel.setRotationAngles priority) ──
        // 🔴 After user report (ceiling climbing): check isCeilingClimbing before isClimbing.
        //   Original SmartMovingModel L127-L316 prioritizes isClimb but assumes both
        //   isClimb && isCeilingClimb never both true. In 1.21.1 on ceiling climbing sm.isClimbing
        //   also maps to true so both true → enter isClimbing branch → apply regular climbing formula → ceiling
        //   climbing but arms/legs still. Priority change ensures sm_animateCeilingClimbing
        //   called when isCeilingClimbing.
        boolean standardPose = false;
        if (sm.isRopeSliding) {
            sm_animateRopeSliding(animationProgress, player);
        } else if (sm.isCeilingClimbing) {
            sm_animateCeilingClimbing(sm, headYaw);
        } else if (sm.isChainClimbing && sm.isClimbing && !sm.isCrawlClimbing) {
            // Chain climb: same mechanics as a ladder, different pose (docs/research_movement_prototypes.md).
            sm_animateChainClimbing(sm, headPitch);
        } else if (sm.isClimbing || sm.isCrawlClimbing) {
            sm_animateClimbing(sm, player, limbSwing, limbSwingAmount, headPitch, animationProgress);
        } else if (isWeepingTwistingVines) {
            // 🔴 weeping/twisting vines — apply ladder climbing pose animation.
            //   User intent: keep feature as vanilla climbing (maintain sm_travel_client bypass). Only animation is SM ladder.
            //   Set sm.actualHandsClimbType / actualFeetClimbType temporarily to 1/1 (UpGrab+DownStep) then
            //   call sm_animateClimbing → restore original values after call (no effect on other code).
            //   handleClimbing not called → lingering value per tick = NoGrab+NoStep (NONE.ordinal()=0).
            //   Temporary 1/1 set applies ladder formula (cos*0.3-0.3 leg pitch, etc.). Maintain isHandsVineClimbing=false
            //   → don't enter vine branch (arm scale boost / leg total formula) = ladder pose.
            int origHandsType = sm.actualHandsClimbType;
            int origFeetType  = sm.actualFeetClimbType;
            sm.actualHandsClimbType = 1;  // UpGrab
            sm.actualFeetClimbType  = 1;  // DownStep
            sm_animateClimbing(sm, player, limbSwing, limbSwingAmount, headPitch, animationProgress);
            sm.actualHandsClimbType = origHandsType;
            sm.actualFeetClimbType  = origFeetType;
        } else if (sm.isClimbJumping) {
            // [isClimbJump] climbing jump — arms extended upward pose
            rightArm.xRot = HALF + SIXTEENTH;
            leftArm.xRot  = HALF + SIXTEENTH;
            rightArm.zRot  = -THIRTYTWOTH;
            leftArm.zRot   =  THIRTYTWOTH;
        } else if (sm.isSwimming_sm) {
            sm_animateSwimming(sm, player, limbSwing, limbSwingAmount, animationProgress, renderState.swingAnimation);
        } else if (sm.isDiving) {
            sm_animateDiving(sm, player, limbSwing, limbSwingAmount, animationProgress, renderState.swingAnimation);
        } else if (sm.isCrawling) {
            sm_animateCrawling(sm, player, headYaw);
        } else if (sm.isSliding) {
            sm_animateSliding(sm, player, headYaw);
        } else if (flyingCreative) {
            sm_animateFlying(sm, player, limbSwing, limbSwingAmount, animationProgress);
        } else if (sm.isHeadJumping) {
            sm_animateHeadJumping(sm, player);
        } else {
            standardPose = true;
            // 🔴 (Phase 2 multi BUG-1) Direct fallDistance check → sm.doFallingAnimation (server-relay sync).
            //   vanilla 1.21.1 server doesn't sync remote fallDistance → user report "falling animation not visible".
            //   doFallingAnimation definition itself expands equivalent to isFalling formula (see sendStatePacket).
            if (sm.doFallingAnimation) {
                sm_animateFalling(sm, player);
            }
            // 🔴 (2026-04-28) Remove isStandard branch swing formula overwrite.
            //   Preserve += cumulative effects of vanilla setAngles sneak/idle vibration/heldItem, etc.
            //   Only change limbSwing/limbSwingAmount arguments via ModifyArg to SM stat (MixinLivingEntityRenderer).
        }

        // ── [12-2] Dodge (angle jump) pose — in the air and through the landing recovery ──
        //   Only over the standard pose, so another SM pose that starts mid-dodge is left alone.
        if (standardPose && sm.sampleDodgePose(animationProgress, smartState.sm_getBodyYaw())) {
            sm_animateDodge(sm, player, animationProgress, renderState.isCrouching);
        }

        // 🔴 (2026-04-27) head stays vanilla — fade in sm_setupTransforms TAIL affects
        //   all ModelPart parent transforms. Don't cancel head only → keep vanilla netHeadYaw
        //   (user intent "head only vanilla code"). So head also rotates following fade lag (intended behavior).

        // [B-16 / §16-24 / BUG-7] Move cloak.pitch handling above (above BUG-13/16 cfgEnabled return guard).
        //   Original SmartRenderModel L251 = always apply regardless of SM state. Guarantee reset to 0 even when cfgEnabled false.

        // In 26.2 sleeves, pants, jacket, and hat are child parts. They inherit the
        // parent transforms directly, so the old sibling copyFrom synchronization is gone.

    }

    // ─────────────────────────────────────────────────────────────────────────
    // Animation helper methods by state
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * isRopeSliding: grab rope and slide.
     * Original: SmartMovingModel.setRotationAngles() branch 1 (isRopeSliding).
     * bipedOuter.rotateAngleY (movement direction alignment) handled in MixinPlayerEntityRenderer bodyYaw.
     * bipedPelvic.rotateAngleX: no bipedPelvic → skip.
     * pivotY correction (B-8 / §16-11): vanilla setAngles explicitly assigns head/arm pivotY per frame in sneak branch
     *   so safe to overwrite in TAIL inject (automatically reset next frame).
     *   Original SR shoulder node (pivotY=2) absent so in 1.21.1 arm pivotY default=2 → -2 deduction = 0 (equals shoulder absolute position 0).
     */
    private void sm_animateRopeSliding(float animationProgress, net.minecraft.client.player.AbstractClientPlayer player) {
        float time = animationProgress * 0.15f;

        // head X/Z
        head.xRot = EIGHTH;
        Vec3 vel = player.getDeltaMovement();
        if (vel.x * vel.x + vel.z * vel.z >= 1e-4) {
            // clamp camera-movement direction difference to [-Sixteenth, Sixteenth] range
            float diff = Mth.wrapDegrees(
                    player.getYRot() - (float) Math.toDegrees(Math.atan2(-vel.x, vel.z))
            ) * DEG_TO_RAD;
            head.zRot = Mth.clamp(diff, -SIXTEENTH, SIXTEENTH);
        } else {
            head.zRot = 0f;
        }

        // torso X (periodic subtle sway)
        float torsoX = SIXTEENTH + SIXTYFOURTH * Mth.cos(time);
        body.xRot = torsoX;

        // arm X = Half - torsoX (hanging pose), Z (left/right fixed)
        rightArm.xRot = HALF - torsoX;
        leftArm.xRot  = HALF - torsoX;
        rightArm.zRot  =  SIXTEENTH + THIRTYTWOTH;
        leftArm.zRot   = -(SIXTEENTH + THIRTYTWOTH);

        // leg Z (fixed), X (sway)
        rightLeg.zRot  =  THIRTYTWOTH;
        leftLeg.zRot   = -THIRTYTWOTH;
        rightLeg.xRot =  SIXTYFOURTH * Mth.cos(time - QUARTER);
        leftLeg.xRot  =  SIXTYFOURTH * Mth.cos(time + QUARTER);

        // hanging pose pivotY correction (original SmartMovingModel L106/L117)
        head.y     =  2f;   // original bipedHead.rotationPointY = 2F
        rightArm.y =  0f;   // original bipedRightArm.rotationPointY = -2F (no shoulder node so vanilla default 2F - 2 = 0)
        leftArm.y  =  0f;   // original bipedLeftArm.rotationPointY = -2F
    }

    /**
     * isClimbing / isCrawlClimbing: ladder/vine climbing.
     * Original: SmartMovingModel.setRotationAngles() branch 2.
     * Hands/feet pose branch by handsClimbType/feetClimbType ordinal (R-10/R-10b).
     * legAngleZ (roll) correction during isCrawlClimbing (R-10c).
     */
    private void sm_animateClimbing(SmartMovingClientState sm, net.minecraft.client.player.AbstractClientPlayer player, float limbSwing, float limbSwingAmount, float headPitch, float animationProgress) {
        // B-4 / §16-10 + smoothness 1:1 (after user report, grab climbing pendulum swing smoothness correction):
        //   Original SmartRenderModel uses per-frame `getCurrentSpeed(partialTicks)` lerp getter
        //   interpolates between prev/current EMA at partial tick ratio = 60Hz smoothness.
        //   Previous mapping direct reference `sm.stats.currentVerticalSpeed` = per-tick updated value = 20Hz jittery.
        //   Apply same pattern as flying (sm_animateFlying) — unify to lerp getters
        //   getCurrentVerticalSpeed/getTotalVerticalDistance.
        float partialTicks = SmartMovingClientState.globalCachedTickDelta;
        float verticalSpeed   = Math.min(0.5f, sm.stats.getCurrentVerticalSpeed(partialTicks));
        float horizontalSpeed = Math.min(0.5f, sm.stats.getCurrentHorizontalSpeed(partialTicks));
        float totalVerticalDistance   = sm.stats.getTotalVerticalDistance(partialTicks);
        float totalHorizontalDistance = sm.stats.getTotalHorizontalDistance(partialTicks);

        // leg.pivotZ reset — block -6 accumulation in frames without D-4 branch.
        //   (head/body/arm pivotZ reset to 0 by reset infrastructure in sm_setAngles TAIL.)
        rightLeg.z = 0f;
        leftLeg.z  = 0f;

        // 🔴 User report fix (2026-05-04 — "ladder/vine sneak+s then reattach sometimes arm shortens"):
        //   setArmScales/setLegScales in isHandsVineClimbing/isFeetVineClimbing branches (L591/L631) apply
        //   abs(cos) formula. When branch not entered (= ladder grab + not touching vine, etc.) not called →
        //   previous frame modified value lingers → visually arm/leg shortened.
        //   fix: reset to default 1.0 every frame → when branch entered cos formula overwrites. When not entered maintain 1.0.
        setArmScales(rightArm, leftArm, 1f, 1f);
        setLegScales(rightLeg, leftLeg, 1f, 1f);

        // 🔴 leg pivotX 1:1 mapping (user report 9th time — precise analysis of leg detail differences):
        //   Original SmartRenderModel.java L80-L87:
        //     bipedRightLeg.setRotationPoint(-2F, 0.0F, 0.0F);  // parent bipedPelvic (0,12,0)
        //     bipedLeftLeg.setRotationPoint(2.0F, 0.0F, 0.0F);
        //     → absolute leg pivot = (±2.0, 12, 0)
        //   1.21.1 vanilla PlayerEntityModel layer definition:
        //     right_leg.pivot(-1.9F, 12.0F, 0.0F)
        //     left_leg.pivot(1.9F, 12.0F, 0.0F)
        //     → absolute leg pivot = (±1.9, 12, 0)
        //   Difference 0.1 pixels — may have subtle visual effect. Explicitly set ±2.0 for 1:1 mapping.
        //   (Reset to vanilla default ±1.9 in exit edge cleanup in sm_setAngles TAIL.)
        rightLeg.x = -2f;
        leftLeg.x  =  2f;

        // 🔴 (User report 8th time — complete 1:1 mapping of basic block grab climbing):
        //   Unroll line-by-line 1:1 from isClimb branch original SmartMovingModel.setRotationAngles L127-L237.
        //   Variable names / formulas / branches all original (no mathematical simplification).

        // Original L131-L132: head yaw=0, pitch=mouse.
        head.yRot   = 0f;
        head.xRot = headPitch * DEG_TO_RAD;

        // 원본 L137-L138: hands/feet Frequence/Distance Up/Side Factor/Offset 변수 선언.
        float handsFrequenceUpFactor, handsDistanceUpFactor, handsDistanceUpOffset;
        float feetFrequenceUpFactor, feetDistanceUpFactor, feetDistanceUpOffset;
        float handsFrequenceSideFactor, handsDistanceSideFactor, handsDistanceSideOffset;
        float feetFrequenceSideFactor, feetDistanceSideFactor, feetDistanceSideOffset;

        // 원본 L140-L142: handsClimbType + vine MiddleGrab → UpGrab 변환.
        //   1.21.1 도메인: 0=NoGrab, 1=UpGrab, 2=MiddleGrab (handleClimbing 분기별 set 매핑).
        int handsClimbType = sm.actualHandsClimbType;
        if (sm.isHandsVineClimbing && handsClimbType == 2 /* MiddleGrab */) {
            handsClimbType = 1 /* UpGrab */;
        }

        // 원본 L144-L145: speed clamp (Math.min(0.5, currentVerticalSpeed/currentHorizontalSpeed)).
        //   1.21.1: partial tick lerp getter (60Hz 부드러움 — 원본 SmartRenderRender.renderPlayer
        //     L56-L57 의 getCurrentSpeed(renderPartialTicks) 등가).
        // (위에서 이미 verticalSpeed/horizontalSpeed/totalVerticalDistance/totalHorizontalDistance 정의됨.)

        // 원본 L147-L176: handsClimbType switch — 3-way 분기.
        final float FrequenceFactor = 0.6662f;  // 원본 SmartMovingModel.FrequenceFactor.
        switch (handsClimbType) {
            case 2:  // MiddleGrab — 원본 L149-L156
                handsFrequenceSideFactor = FrequenceFactor;
                handsDistanceSideFactor  = 1.0f;
                handsDistanceSideOffset  = 0.0f;
                handsFrequenceUpFactor   = FrequenceFactor;
                handsDistanceUpFactor    = 2f;
                handsDistanceUpOffset    = -QUARTER;
                break;
            case 1:  // UpGrab — 원본 L158-L165
                handsFrequenceSideFactor = FrequenceFactor;
                handsDistanceSideFactor  = 1.0f;
                handsDistanceSideOffset  = 0.0f;
                handsFrequenceUpFactor   = FrequenceFactor;
                handsDistanceUpFactor    = 2f;
                handsDistanceUpOffset    = -2.5f;
                break;
            default: // NoGrab (0) — 원본 L167-L175
                handsFrequenceSideFactor = FrequenceFactor;
                handsDistanceSideFactor  = 1.0f;
                handsDistanceSideOffset  = 0.0f;
                handsFrequenceUpFactor   = FrequenceFactor;
                handsDistanceUpFactor    = 0f;
                handsDistanceUpOffset    = -0.5f;
                break;
        }

        // 원본 L178-L198: feetClimbType switch — 2-way 분기 (UpGrab vs default).
        //   1.21.1 도메인: 0=NoStep, 1=DownStep (handleClimbing 분기별 set).
        //   원본 case `HandsClimbing.UpGrab` (=1) ≡ 1.21.1 DownStep (=1).
        int feetClimbType = sm.actualFeetClimbType;
        switch (feetClimbType) {
            case 1:  // UpGrab/DownStep — 원본 L180-L187
                feetFrequenceUpFactor   = FrequenceFactor;
                feetDistanceUpFactor    = 0.3f / verticalSpeed;  // 원본 L182 그대로 (verticalSpeed=0 시 NaN 동작도 1:1)
                feetDistanceUpOffset    = -0.3f;
                feetFrequenceSideFactor = FrequenceFactor;
                feetDistanceSideFactor  = 0.5f;
                feetDistanceSideOffset  = 0.0f;
                break;
            default: // NoStep — 원본 L189-L196
                feetFrequenceUpFactor   = FrequenceFactor;
                feetDistanceUpFactor    = 0.0f;
                feetDistanceUpOffset    = 0.0f;
                feetFrequenceSideFactor = FrequenceFactor;
                feetDistanceSideFactor  = 0.0f;
                feetDistanceSideOffset  = 0.0f;
                break;
        }

        // 원본 L200-L204: 팔 X(pitch)/Y(yaw) — 변수 풀어쓰기.
        float rArmPitch = Mth.cos(totalVerticalDistance * handsFrequenceUpFactor + HALF) * verticalSpeed * handsDistanceUpFactor + handsDistanceUpOffset;
        float lArmPitch = Mth.cos(totalVerticalDistance * handsFrequenceUpFactor)        * verticalSpeed * handsDistanceUpFactor + handsDistanceUpOffset;
        float rArmYaw   = Mth.cos(totalHorizontalDistance * handsFrequenceSideFactor + QUARTER) * horizontalSpeed * handsDistanceSideFactor + handsDistanceSideOffset;
        float lArmYaw   = Mth.cos(totalHorizontalDistance * handsFrequenceSideFactor)            * horizontalSpeed * handsDistanceSideFactor + handsDistanceSideOffset;
        // 🔴 fix #186 (2026-05-28): fix #183 revert — fix #177 패러다임 복원 (메모리 검증 패턴).
        //   feedback_animateArms_cancel 메모리 명시: preferred arm preserve 가드.
        //   클라이밍 분기는 setupTransforms 부모 R_x 회전 없음 → preCancelParentX 불필요 (메모리 명시).
        float swingClimb = player.getSwingAnimation(1.0F);
        HumanoidArm preferredArmClimb = player.getMainArm();
        boolean preserveRightClimb = swingClimb > 0F && preferredArmClimb == HumanoidArm.RIGHT;
        boolean preserveLeftClimb  = swingClimb > 0F && preferredArmClimb == HumanoidArm.LEFT;
        if (!preserveRightClimb) setAnglesYZX(rightArm, rArmPitch, rArmYaw, 0f);
        if (!preserveLeftClimb)  setAnglesYZX(leftArm,  lArmPitch, lArmYaw, 0f);

        // 원본 L206-L215: isHandsVineClimbing 추가 보정.
        if (sm.isHandsVineClimbing) {
            // 원본 L208-L209: arm.Y *= 1F + handsFrequenceSideFactor.
            leftArm.yRot  *= 1f + handsFrequenceSideFactor;
            rightArm.yRot *= 1f + handsFrequenceSideFactor;
            // 원본 L211-L212: leftArm.Y += Eighth, rightArm.Y -= Eighth.
            leftArm.yRot  += EIGHTH;
            rightArm.yRot -= EIGHTH;
            // 원본 L214: setArmScales(abs(cos(rightArm.X)), abs(cos(leftArm.X))).
            setArmScales(rightArm, leftArm,
                    Math.abs(Mth.cos(rightArm.xRot)),
                    Math.abs(Mth.cos(leftArm.xRot)));
        }

        // 원본 L217-L221: 다리 X(pitch) — `if(!isFeetVineClimbing)` 가드. 무조건 식 적용.
        //   verticalSpeed=0 시 `0.3F/0 = Infinity, cos*Infinity*0 = NaN` 발생. 원본 1.7.10
        //   GL11.glRotatef(NaN) = drivers 보통 무회전 (identity) → leg.pitch=0 시각 효과 ("다리 1자").
        //   1.21.1 JOML Quaternionf(NaN) = matrix NaN → vertex 깨짐. NaN 가드로 0 fallback —
        //   원본 1.7.10 OpenGL drivers 의 무회전 동작과 시각 일치 (사용자 보고 "처음 시작 시 다리 1자").
        //
        // 🔴 fix #76 (BUG #2.5, 2026-05-10, 사용자 보고 "weeping/twisting vines + sneak 시 다리 사라짐"):
        //   verticalSpeed 가 정확히 0 이 아닌 **매우 작은 양수** (float underflow 수준) 시 식 결과:
        //     0.3f / tiny_positive = +Infinity (float overflow, NaN 아님)
        //     cos * +Infinity * tiny_positive = ±Infinity (Infinity × positive = Infinity 유지)
        //     + offset = ±Infinity
        //   기존 Float.isNaN 가드는 Infinity 를 잡지 못함 → leg.pitch=±Infinity → 다리 transform 망가짐.
        //   해결: `!Float.isFinite(...)` 로 가드 강화 — NaN + ±Infinity 모두 0 fallback (= 원본
        //   "다리 1자" 의도 1:1).
        //   dump 검증 ([BUG2.5-DBG]): tick 168786 부터 rL.pitch=-Infinity / lL.pitch=Infinity 발생.
        //   사용자 시나리오 = weeping/twisting vines + sneak (정지 상태). 우리 sm_isSneaking fix #75
        //   후 sm.stats.currentVerticalSpeed 가 작은 underflow 양수로 갱신되는 것이 직접 원인.
        if (!sm.isFeetVineClimbing) {
            rightLeg.xRot = Mth.cos(totalVerticalDistance * feetFrequenceUpFactor)        * feetDistanceUpFactor * verticalSpeed + feetDistanceUpOffset;
            leftLeg.xRot  = Mth.cos(totalVerticalDistance * feetFrequenceUpFactor + HALF) * feetDistanceUpFactor * verticalSpeed + feetDistanceUpOffset;
            if (!Float.isFinite(rightLeg.xRot)) rightLeg.xRot = 0f;
            if (!Float.isFinite(leftLeg.xRot))  leftLeg.xRot  = 0f;
        }

        // 원본 L223-L224: 다리 Z(roll) — 무조건 식 적용.
        rightLeg.zRot = -(Mth.cos(totalHorizontalDistance * feetFrequenceSideFactor) - 1.0f)          * horizontalSpeed * feetDistanceSideFactor + feetDistanceSideOffset;
        leftLeg.zRot  = -(Mth.cos(totalHorizontalDistance * feetFrequenceSideFactor + QUARTER) + 1.0f) * horizontalSpeed * feetDistanceSideFactor + feetDistanceSideOffset;
        // fix #76 일관성: roll 도 isFinite 가드.
        if (!Float.isFinite(rightLeg.zRot)) rightLeg.zRot = 0f;
        if (!Float.isFinite(leftLeg.zRot))  leftLeg.zRot  = 0f;

        // vine 전용 — 원본 L228-L239.
        // B-5 / §16-12: 원본 L228/L232 cos 입력은 totalDistance (3D 누적). 이전 limbSwing
        //   (수평 누적) 잘못 매핑 → sm.stats.totalDistance 로 교체. SmartStatistics.calculate
        //   L91 = totalDistance 누적 1.7.10 등가.
        if (sm.isFeetVineClimbing) {
            // 부드러움: totalDistance 도 partial tick lerp getter 로 교체.
            float totalDistanceLerped = sm.stats.getTotalDistance(partialTicks);
            float total = (Mth.cos(totalDistanceLerped + HALF) + 1f) * THIRTYTWOTH + SIXTEENTH;
            rightLeg.xRot = -total;   // pitch 덮어쓰기
            leftLeg.xRot  = -total;

            float diff = Math.max(0f, Mth.cos(totalDistanceLerped - QUARTER)) * SIXTYFOURTH;
            leftLeg.zRot  += -diff;    // roll 누적 (원본 `+=`)
            rightLeg.zRot +=  diff;

            // 원본 SmartMovingModel.java L391: setLegScales(abs(cos(rightLeg.X)), abs(cos(leftLeg.X)))
            setLegScales(rightLeg, leftLeg,
                    Math.abs(Mth.cos(rightLeg.xRot)),
                    Math.abs(Mth.cos(leftLeg.xRot)));
        }

        // (D-2) 원본 SmartMovingModel L134-L135: bipedLeft/RightLeg.rotationOrder = YZX.
        //   ModelPart 기본 회전 순서는 ZYX. 다리 yaw 가 0 으로 명시되어 X/Z 회전 순서 차이는
        //   결과에 영향 없음 (Y=0 이면 Y 위치 무관) → 헬퍼 미사용 OK.
        rightLeg.yRot = 0f;
        leftLeg.yRot  = 0f;

        // isCrawlClimbing 추가 보정: 몸통 X 기울기 + 다리 roll (R-10c)
        if (sm.isCrawlClimbing) {
            float height = sm.smallOverGroundHeight + 0.25f;
            float bodyLength = 0.7f, legLength = 0.55f;
            float bodyAngleX, legAngleX, legAngleZ;
            if (height < bodyLength) {
                bodyAngleX = Math.max(0f, (float) Math.acos(height / bodyLength));
                legAngleX  = QUARTER - bodyAngleX;
                legAngleZ  = THIRTYTWOTH;
            } else if (height < bodyLength + legLength) {
                bodyAngleX = 0f;
                legAngleX  = Math.max(0f, (float) Math.acos((height - bodyLength) / legLength));
                legAngleZ  = THIRTYTWOTH * (legAngleX / 1.537f);
            } else {
                bodyAngleX = 0f;
                legAngleX  = 0f;
                legAngleZ  = 0f;
            }
            // 🔴 fade 보간 (2026-05-04 — 사용자 보고 fix "다리 땅 침투"):
            //   bodyAngleX 만 fade 시 legAngleX = QUARTER - bodyAngleX 식 → bodyAngleX=0 시작 시
            //   legAngleX = π/4 = 큰 앞쪽 회전 → leg vertex 박스 침투.
            //   fix: legAngleX/legAngleZ 도 별도 fade 적용 (= 각각 prev=0 시작 → target lerp).
            //   setupTransforms 가 먼저 호출 → bodyAngleX_faded store 됨. 여기서 read.
            bodyAngleX = sm.smCrawlClimbBodyAngleXFaded;
            // legAngleX/legAngleZ target = 위 if/else if/else 분기 결과. fade 0 시작 → target 점진.
            legAngleX = sm.applyCrawlClimbLegAngleXFade(legAngleX, animationProgress);
            legAngleZ = sm.applyCrawlClimbLegAngleZFade(legAngleZ, animationProgress);
            // 옵션 D 정확 1:1 매핑 (원본 isCrawlClimb 분기 L265-L276):
            //   L265 bipedTorso.X = bodyAngleX → setupTransforms 의 root R_x (별도 inject).
            //   L267-L268 bipedShoulder.X = -bodyAngleX → arm.pitch += -bodyAngleX (shoulder cancel).
            //   L270 bipedHead.X = -bodyAngleX → head.pitch 직접 set.
            //   L272-L273 bipedLeg.X = legAngleX → leg.pitch 직접 set (= 부모 R_x 효과 setupTransforms).
            //   L275-L276 bipedLeg.Z = ±legAngleZ → leg.roll 직접 set.

            body.xRot     =  0f;                     // L265 부모 매핑 (setupTransforms)
            head.xRot     = -bodyAngleX;             // L270 head cancel = 무회전
            rightLeg.xRot =  legAngleX;              // L272 leg local
            leftLeg.xRot  =  legAngleX;              // L273
            rightLeg.zRot  =  legAngleZ;              // L275
            leftLeg.zRot   = -legAngleZ;              // L276
            rightArm.xRot += -bodyAngleX;            // L267 shoulder cancel
            leftArm.xRot  += -bodyAngleX;            // L268
            // leg/arm pivot = vanilla default (= setupTransforms 부모 회전이 자동 적용).
            //   sm_animateClimbing L494-L495 의 leg.pivotX = ±2.0 그대로.
            //   leg.pivotY/Z, arm.pivotY/Z 는 vanilla default (= 12, 0) / (2, 0).
        }

        // NoGrab + non-NoStep 추가 보정 (원본 SmartMovingModel L279-L286).
        //   ⚠️ 원본은 `if(isCrawlClimb)` 블록 **밖** — 모든 클라이밍 (CrawlClimb 여부 무관) 에서 적용.
        //   이전 구현은 `if(isCrawlClimb)` **안** 에 두어 일반 그랩 클라이밍에서 보정 누락. 정정 (D-4).
        //   원본 식:
        //     bipedTorso.X = 0.5F           → body.pitch = 0.5f
        //     bipedHead.X -= 0.5F           → head.pitch -= 0.5f
        //     bipedPelvic.X -= 0.5F         → leg.pitch -= 0.5f (1.21.1 pelvic 부재로 다리 직접)
        //     bipedTorso.rotationPointZ = -6F → body.pivotZ = -6f
        //   조건 매핑:
        //     handsClimbType == NoGrab → 우리 ordinal NONE(0)/SINK(1) (h < 2).
        //     feetClimbType != NoStep ≡ DownStep — 원본 setShouldClimbSpeed 채널은 NoStep/DownStep
        //       2-way 만 set. "발 등반 중" 의미 = 우리 enum 의 SLOW_UP_*/FAST_UP (ordinal >= 4).
        //       기존 `> 0` 은 BASE_HOLD/BASE_WITH_HANDS/TOP_WITH_HANDS (발 가만히) 도 포함하는 오역.
        // 🔴 D-4 분기 도메인 변경: 원본 L279 = `handsClimbType == NoGrab && feetClimbType != NoStep`.
        //   새 0/1/2 도메인: hands == 0 (NoGrab) && feet != 0 (DownStep).
        if (sm.actualHandsClimbType == 0 && sm.actualFeetClimbType != 0) {
            // 🔴 D-4 1:1 매핑 (사용자 보고 7회차 — 자세 1대1 OK + 종료 후 잔존 fix):
            //   원본 SmartMovingModel L279-L286 분석:
            //     bipedTorso.rotateAngleX = 0.5F;        → body, head, arm, leg 모두 +0.5 회전 (자식 효과)
            //     bipedTorso.rotationPointZ = -6F;       → 모든 자식 z 평행이동
            //     bipedHead.rotateAngleX -= 0.5F;        → head 자식 효과 cancel → vanilla 결과
            //     bipedPelvic.rotateAngleX -= 0.5F;      → pelvic 자식 효과 cancel → leg = vanilla
            //     (arm cancel 없음 → arm 만 +0.5 그대로 노출)
            //
            //   1.21.1 parallel 노드 매핑 (사용자 자세 1대1 검증 OK):
            //     - body.pitch = 0.5             → bipedTorso.X 직접 매핑
            //     - 모든 노드 pivotZ = -6         → bipedTorso.rotationPointZ 평행이동
            //     - arm.pitch += 0.5             → 원본 arm 자식 효과 노출
            //     - head/leg.pitch 변경 안 함     → 원본 자식 효과 + cancel = 0 = vanilla 결과
            //
            //   등반 종료 후 standing 시 잔존 (사용자 명시) 은 위 reset 인프라 영역의 종료 엣지
            //   cleanup (smWasClimbingForCleanup 추적) 에서 처리.
            body.xRot  = 0.5f;
            body.z     = -6f;
            head.z     = -6f;
            rightArm.z = -6f;
            leftArm.z  = -6f;
            // 🔴 leg pivot 정확 매핑 (사용자 보고 — 다리 벽 안 뚫림 fix):
            //   원본 매트릭스: T(0,0,-6) * R_x(0.5) * T(0,12,0) * T(±2,0,0).
            //   bipedTorso 회전 (R_x 0.5) 가 자식 (leg) 위치에 적용 → leg 절대 위치 =
            //     (±2, 12*cos(0.5), -6 + 12*sin(0.5)) ≈ (±2, 10.529, -0.247).
            //   1.21.1 parallel 구조라 R_x(0.5) 자동 누적 안 됨. leg.pivotZ=-6 만 적용 시 leg
            //     절대 = (±2, 12, -6). Z 차이 -5.75 픽셀 = 사용자 보고 "다리 벽 안 5.75 뚫림".
            //   해결: leg.pivotY/Z 를 원본 매트릭스 결과 위치로 명시 set.
            rightLeg.y = 12f * Mth.cos(0.5f);  // ≈ 10.529
            leftLeg.y  = 12f * Mth.cos(0.5f);
            rightLeg.z = -6f + 12f * Mth.sin(0.5f);  // ≈ -0.247
            leftLeg.z  = -6f + 12f * Mth.sin(0.5f);
            rightArm.xRot += 0.5f;
            leftArm.xRot  += 0.5f;
        }
    }

    /**
     * isCeilingClimbing: 천장 매달리기 클라이밍.
     * 원본: SmartMovingModel.setRotationAngles() 4번 분기 (L284-L316).
     * head.yaw 는 원본 L315 `bipedHead.rotateAngleY = -rotateY;` 절대 할당으로
     *   vanilla setAngles 의 net head yaw (i*PI/180) 결과를 덮어쓴다 — 매달림 자세에서
     *   머리는 ceiling climb 동작에만 따르며 마우스 head yaw 입력 무시 (의도).
     * B-18 / §16-17: 이전 구현의 `head.yaw -= headYaw * DEG_TO_RAD` 차감은 원본에 없는
     *   잉여 보정이었으므로 제거 (세션 25).
     */
    private void sm_animateCeilingClimbing(SmartMovingClientState sm, float headYaw) {
        // 🔴 (사용자 보고 후, 천장 등반 1:1 정정): 원본 L298-L300 입력은 SM 통계.
        //   원본: distance = totalHorizontalDistance * 0.7F
        //         walkFactor = Factor(currentHorizontalSpeed, 0, 0.12951545)
        //         standFactor = Factor(currentHorizontalSpeed, 0.12951545, 0)
        //   이전 매핑 limbSwing/limbSwingAmount (vanilla limbAnimator) → 천장 매달려 좌우 이동 시
        //   ground walk 기준 vanilla 누적 안 됨 → walkFactor=0 → 팔/다리 진자운동 항 0 → 사용자 보고
        //   "팔다리 가만히". sm_animateClimbing 동일 패턴 — partial tick lerp getter 적용.
        float partialTicks = SmartMovingClientState.globalCachedTickDelta;
        float horizontalSpeedLerped = sm.stats.getCurrentHorizontalSpeed(partialTicks);
        float distance    = sm.stats.getTotalHorizontalDistance(partialTicks) * 0.7f;
        float walkFactor  = smFactor(horizontalSpeedLerped, 0f, 0.12951545f);
        float standFactor = smFactor(horizontalSpeedLerped, 0.12951545f, 0f);

        // 팔: 천장을 향해 위로 (XYZ 근사)
        leftArm.xRot  = (Mth.cos(distance) * 0.52f + HALF) * walkFactor + HALF * standFactor;
        rightArm.xRot = (Mth.cos(distance + HALF) * 0.52f - HALF) * walkFactor - HALF * standFactor;
        leftLeg.xRot  = -Mth.cos(distance) * 0.12f * walkFactor;
        rightLeg.xRot = -Mth.cos(distance + HALF) * 0.32f * walkFactor;

        float rotateY = Mth.cos(distance) * 0.44f * walkFactor;
        rightArm.yRot = leftArm.yRot = -rotateY;
        rightLeg.yRot = leftLeg.yRot = -rotateY;
        head.yRot     = -rotateY;
    }

    /**
     * isSwimming_sm: SM 수면 수영.
     * 원본: SmartMovingModel.setRotationAngles() 5번 분기 (isSwim).
     * bipedOuter X 기울기는 setupTransforms Mixin에서 처리 (standSneakFactor 경유).
     */
    private void sm_animateSwimming(SmartMovingClientState sm, AbstractClientPlayer player,
                                    float limbSwing, float limbSwingAmount, float totalTime,
                                    float attackTime) {
        // 🔵 (2026-05-20, BUG-Swim-Anim-6 fix #113) limbSwing/Amount → sm.stats 입력 교체.
        //   원본 SmartMovingModel L319-322: `distance = totalHorizontalDistance`, walkFactor/sneakFactor/
        //     standFactor 는 `currentHorizontalSpeed` 기준.
        //   기존 매핑은 vanilla `limbSwing` (= LimbAnimator.pos, 누적 `velocity * 4 * tick` 단위) 사용
        //     → 원본 `totalHorizontalDistance` (= 매 tick distance 누적, m 단위) 와 4배 빠른 cycle →
        //     팔 stroke + 다리 kick + body sway + head sway 모두 4배 빠른 위상 → 사용자 보고
        //     "팔 회전 이상" + "물 안 헤엄 팔/다리/몸통/머리 이상" 정확 cause.
        //   dive 분기 (sm_animateDiving) 가 이미 sm.stats.totalDistance/currentSpeed 사용 — swim 도
        //     동일 패턴.
        // 🔴 (2026-05-21, fix #125) 사용자 보고 "60Hz 보간 필요". direct field 접근이
        //   tick 단위 (= 50ms) jump → 끊김. SmartStatistics.getXxx(partialTicks) sub-tick
        //   interpolation 사용 — `prev + (current - prev) * partialTicks` (= 원본 1:1).
        //   partialTicks = totalTime - floor(totalTime) (= animationProgress 의 fractional 부분).
        float partialTicks = totalTime - (float) Math.floor(totalTime);
        float distance = sm.stats.getTotalHorizontalDistance(partialTicks);
        float speed    = sm.stats.getCurrentHorizontalSpeed(partialTicks);
        float walkFactor  = smFactor(speed, 0.15679921f, 0.52264464f);
        float sneakFactor = Math.min(
                smFactor(speed, 0f, 0.15679921f),
                smFactor(speed, 0.52264464f, 0.15679921f));
        float standFactor = smFactor(speed, 0.15679921f, 0f);
        float standSneakFactor = standFactor + sneakFactor;
        sm.swimStandSneakFactor = standSneakFactor;

        // 🔴 (2026-05-21, fix #122) "팔이랑 몸통 분리" — vanilla 1.21.1 BipedEntityModel 의
        //   arm 이 root 직접 자식 (vs 원본 SmartRender 의 arm 이 bipedBreast 자식 = body sway
        //   영향 받음). 우리 매핑에서 arm.yaw 에 body sway 값 추가 = 원본 breast 자식 효과 등가.
        //
        // 🔴 (2026-05-21, fix #124 정정) 원본 SmartRender model tree 정확 분석:
        //   - head: outer × torso(0) × breast(sway) × neck(0) × head(sway) = 2 × sway.
        //   - body: outer × torso(0) × body(sway) = **1 × sway** (= breast 와 sibling, 자식 X).
        //   - arm: outer × torso(0) × breast(sway) × shoulder(0) × arm(0) = 1 × sway.
        //   호출 흐름 검증 (SmartMovingModel L626-632 animateHeadRotation):
        //     setRotationAngles → isSwim 분기 실행 → bipedHead.Y=sway, bipedBreast.Y=sway, bipedBody.Y=sway.
        //     isStandard=false (L73) → imp.superAnimateHeadRotation skip → bipedNeck.ignoreBase=false 유지.
        //   = bipedNeck 의 parent transform 적용 → head 의 합성 = breast(sway) + head(sway) = 2×sway.
        //   = bipedBody 는 bipedTorso 자식 (= breast 와 sibling) → body 의 합성 = 1×sway.
        //   사용자 verbatim "body 변경 전이 원본과 동일" 정확 매치 — body 만 revert.
        float bodySway = Mth.cos(distance / 2f - QUARTER) * walkFactor;

        // 🔴 fix #193 (2026-05-28): ZYX 분해 singular 회피 — head 직접 set.
        //
        // 사용자 보고: "jump 꾹누름 + 수면 swim 시 좌클릭 swing 만 이상. 어깨 위/아래 토글".
        //
        // ROOT CAUSE (= dump fact 정밀 분석):
        //   setAnglesRyRxRy_standard 가 R_y(swayBreast) × R_x(pitch) × R_y(swayHead) 합성 후
        //   ZYX 분해 → ModelPart pitch/yaw/roll set. yaw ≈ ±π/2 (= singular) 시 atan2 분기
        //   변동으로 (pitch=π, yaw=π/2, roll=π) ↔ (pitch=0, yaw=π/2, roll=0) 토글.
        //   같은 input 의도지만 vanilla rotationZYX(roll, yaw, pitch) 재합성 시 다른
        //   quaternion → visual 토글.
        //
        // 차이 (일반 swim vs jump+swim):
        //   - 일반 swim: walkFactor < 1 → bodySway 작음 → yaw 작음 → singular 미도달 → 정상.
        //   - jump+swim: walkFactor=1 max → bodySway max ±1 → yaw ±π/2 도달 → singular 토글.
        //
        // dump 검증 (log_temp.txt 4회차):
        //   t=371 frame 10 (partial≈0.67): head.pitch=π, head.yaw=1.55, head.roll=π.
        //   t=371 frame 11 (partial≈0.83): head.pitch=0, head.yaw=1.54, head.roll=0.
        //   거의 같은 yaw 인데 pitch+roll 토글.
        //
        // FIX: 원본 식 = R_y(s) × R_x(p) × R_y(s). swimSSF=0 시 p=0 → R_y(s) × R_y(s) commute
        //   = R_y(2s). swimSSF>0 시 p=-EIGHTH*sSF (≠0) 미세 차이만 (sneak swim walkFactor 작음).
        //   ModelPart 의 vanilla rotationZYX(roll, yaw, pitch) 매핑:
        //     vertex_world = R_z(roll) × R_y(yaw) × R_x(pitch) × v_local.
        //   head.yaw = 2*bodySway, head.pitch = -EIGHTH*sSF, head.roll = 0 직접 set.
        //   = R_y(2s) × R_x(p) (vanilla 적용). 원본 R_y(s) × R_x(p) × R_y(s) 와 p=0 시 정확
        //   일치. p≠0 시 sneak swim 인데 walkFactor 작음 → 시각 미세 차이만.
        head.xRot = -EIGHTH * standSneakFactor;
        head.yRot   = 2f * bodySway;
        head.zRot  = 0f;
        head.z = -2f;   // 원본 bipedHead.rotationPointZ = -2F

        // 몸통 yaw — bipedBody 의 합성 = 1×sway (= torso 자식, breast 와 sibling, breast 영향 X).
        body.yRot = bodySway;

        // 🔴 (2026-05-21, fix #129) 사용자 verbatim "팔 회전 이상함" + 메모리 가이드 적용.
        //   원본 SmartMovingModel L337-L344 (isSwim 분기 arm):
        //     bipedRightArm.rotationOrder = YZX (= vertex 적용 Y → Z → X).
        //     bipedRightArm.rotateAngleZ = Quarter + Eighth + cos(t*0.1) * sSF * 0.8.
        //     bipedRightArm.rotateAngleX = ((distance*0.5) % Whole - Half) * walk + Sixteenth * sSF.
        //     bipedRightArm.rotateAngleY = 명시 안 됨 (= 0).
        //   메모리 [[feedback_animation_porting]] 가이드: "arm/leg 의 local Z → 그냥 arm.roll = localZ
        //     직접 대입. sin/cos 3D 외재적 분해 금지. 1.21.1 ModelPart 는 pitch-first 내재적
        //     ZYX (= R_z × R_y × R_x)".
        //   메모리 [[feedback_zxy_zyx_rotation_order]] 가이드: "작은 yaw (< π/4): ModelPart
        //     직접 set + 원본 부호 그대로 안전".
        //   = arm.yaw = bodySway (< 0.5 rad ≈ 28° < π/4) 영역에서 직접 set 안전.
        //   fix #121 의 setAnglesYZX_standard (= quaternion 합성 + euler 분해) 가 메모리 가이드
        //     위반. JOML euler 분해 시 큰 X (sawtooth ±π) 큰 Z (3π/4) 영역에서 분해 결과 ≠ 의도.
        //   해결: 원본 식 결과 = ModelPart.pitch/yaw/roll 직접 대입.
        //   fix #122 (arm.yaw = bodySway) 유지 — breast 자식 효과 등가.
        // 🔴 (2026-05-21, fix #138 revert) 사용자 verbatim "방금 변경 돌리고 dump 추가".
        //   = fix #137 (좌우 swap) 그대로.
        // 🔴 (2026-05-21, fix #141) roll sign swap 만 revert.
        // 🔴 (2026-05-21, fix #144) pitch phase swap 도 revert (= 원본 1:1 매핑).
        //   원본 SmartMovingModel.java L343-L344:
        //     bipedRightArm.rotateAngleX = ((d*0.5)%Whole-Half) * walk + Sixteenth * sSF.   (= sawtooth(d))
        //     bipedLeftArm.rotateAngleX  = ((d*0.5+Half)%Whole-Half) * walk + Sixteenth * sSF. (= sawtooth(d+π))
        //   원본 SmartMovingModel.java L340-L341:
        //     bipedRightArm.rotateAngleZ = +(Q+E) + cos*sSF*0.8.
        //     bipedLeftArm.rotateAngleZ  = -(Q+E) - cos*sSF*0.8.
        //   fix #137 swap 의 원인 = fix #122 의 R_y middle position 잘못. fix #144 helper 정정 후
        //     swap 필요 없음 → revert.
        float dist2      = distance * 0.5f;
        // 원본 1:1 (= fix #137/#141 swap revert).
        // 사용자 보고 "팔 회전 뚜둑" plicking 의 진짜 cause = SmartStatistics dist field 의 tick boundary
        //   backward jump (= partial=0.000 frame 시 prev_total stale). fix #149 monotonic guard 로 해결.
        float rightPitch = ((dist2 % WHOLE) - HALF) * walkFactor + SIXTEENTH * standSneakFactor;
        float leftPitch  = (((dist2 + HALF) % WHOLE) - HALF) * walkFactor + SIXTEENTH * standSneakFactor;
        float rightRoll  =  (QUARTER + EIGHTH) + Mth.cos(totalTime * 0.1f) * standSneakFactor * 0.8f;
        float leftRoll   = -(QUARTER + EIGHTH) - Mth.cos(totalTime * 0.1f) * standSneakFactor * 0.8f;
        // 🔴 fix #191 (2026-05-28): preferred arm preserve 가드 (= fix #177 패러다임).
        //
        // 사용자 보고 (fix #188~#190 후): "수영 시 팔 휘두르는게 안 고쳐졌다".
        // 원본 fact (SmartMovingModel.setRotationAngles L317-362 + L667-679):
        //   isSwim 분기 = arm 식 set (rotateAngleZ, rotateAngleX 등).
        //   isWorking() 시 (= swing > 0) animateWorkingBody → animateNonStandardWorking →
        //     bipedRightArm.reset() (= arm 의 rotation/pivot/scale 모두 0 reset)!
        //   그 다음 animateWorkingArms (isStandard || isWorking()) → vanilla cubic 식 적용.
        //   즉 원본은 swing 시 swim 자세 사라지고 cubic 식 결과만 남음.
        // 우리 매핑 오류: sm_animateSwimming 이 vanilla animateArms (= fix #188 cubic) 후
        //   호출 → arm 식 새로 set → cubic 결과 덮어씀 → swing visual 사라짐.
        // 해결: preferred arm preserve (= fix #186/#177 패러다임). swing 시 preferred arm
        //   식 SKIP + pivot default → vanilla cubic 식 결과 보존 (= 원본 reset 후 cubic 등가).
        float swingSwim = attackTime;
        HumanoidArm preferredArmSwim = player.getMainArm();
        boolean preserveRightSwim = swingSwim > 0F && preferredArmSwim == HumanoidArm.RIGHT;
        boolean preserveLeftSwim  = swingSwim > 0F && preferredArmSwim == HumanoidArm.LEFT;
        // 🔴 (2026-05-21, fix #140) 원본 visual 정확 매핑.
        //   원본 합성 = R_y(sway) × R_x(pitch) × R_z(roll) (= breast 자식 + arm YZX rotation order).
        //   vanilla ModelPart 식 R_z × R_y × R_x 와 다른 합성 → setAnglesYXZ_breastSwim helper 사용.
        //   fix #122 의 arm.yaw = bodySway 매핑 잘못 → 새 helper 적용.
        if (!preserveRightSwim) setAnglesYXZ_breastSwim(rightArm, bodySway, rightPitch, rightRoll);
        if (!preserveLeftSwim)  setAnglesYXZ_breastSwim(leftArm,  bodySway, leftPitch,  leftRoll);

        // 🔴 (2026-05-21, fix #146) arm pivot 의 R_y(bodySway) 변환 — 어깨가 몸통과 떨어진 BUG fix.
        //   사용자 verbatim "어깨가 확실히 몸통에 붙어있어야되는데 지금 떨어져있다는 거야".
        //
        //   원인:
        //     원본 1.7.10 model tree: bipedBreast.R_y(bodySway) → bipedRightShoulder.pivot(-5,2,0)
        //       → bipedRightArm.R(R_x×R_z, YZX, Y=0).
        //     원본 vertex_world = R_y(bodySway) × ( shoulder.pivot + arm.R 자체 × vertex )
        //                       = R_y(bodySway) × shoulder.pivot   +   R_y(bodySway) × arm.R 자체 × vertex.
        //                          ↑ shoulder pivot 도 R_y 적용
        //     vanilla 1.21.1 BipedEntityModel tree: root → arm 직접 (= body 와 형제).
        //       arm.pivot = (-5, 2, 0). arm.R 자체 적용 후 + arm.pivot.
        //     fix #140 helper q = R_y(bodySway) × R_x × R_z → arm.R 에 R_y 포함.
        //     vanilla 매핑 vertex_world = arm.pivot + q × vertex
        //                                = (-5, 2, 0)    +    R_y(bodySway) × R_x × R_z × vertex.
        //                                   ↑ pivot 에 R_y 적용 누락 ✗
        //     = vertex 부분 동등, pivot 부분 차이 (= R_y × shoulder.pivot 누락).
        //
        //   검증 (bodySway = -0.277, log line 11):
        //     fix #145 arm pivot world = (-0.3125, 0.125, 0.0).
        //     body shoulder corner world = R_y(-0.277) × (-4, 0, -2) / 16 = (-0.2405, 0, -0.0683).
        //     Z 차이 = +0.0683 (= +1.09 pixel forward 떨어짐) ✗.
        //   fix #146 arm pivot world = R_y(-0.277) × (-5, 2, 0) / 16 = (-0.301, 0.125, -0.0854).
        //     Z 차이 = -0.0171 (= -0.27 pixel) ✓ 거의 align.
        //
        //   적용: arm.pivot 의 R_y(bodySway) 변환 (= pivot 의 X-Z plane 회전).
        //     R_y(sway) × (vx, vy, vz) = (vx*cos + vz*sin, vy, -vx*sin + vz*cos).
        //     rightArm.pivot (-5, 2, 0) → (-5*cos, 2, 5*sin).
        //     leftArm.pivot (+5, 2, 0)  → (+5*cos, 2, -5*sin).
        //
        //   회귀 차단: ModelPart.pivot field 인스턴스별. swim 분기 이후 다른 분기 진입 시 default
        //     reset 필요 → sm_animate* dispatcher 또는 default 값 reset (= 아래 default 복원).
        // 🔴 fix #194 (2026-05-28): preserve 가드 안 pivot default 제거 — swing 시도 R_y(sway) 변환 유지.
        //
        // 사용자 verbatim (fix #193 후): "팔 휘두를 때 그때 기준으로 휘두르는 팔 어깨가 그 위치에
        //   고정되서 그때 동안 움직이는 몸 움직임에 안 따라가고 거기 고정되어있어서".
        //
        // ROOT CAUSE: fix #191 의 preserve 가드가 arm.pivot 도 default (-5,2,0) / (5,2,0) 으로
        //   reset → swing 시 어깨가 body sway 와 분리.
        // 원본 1.7.10 model tree: bipedRightArm 의 부모 = bipedBreast (= R_y(sway)).
        //   swing 시 animateNonStandardWorking 는 arm.reset() 만 (= rotation 0 reset). 부모
        //   breast.R(sway) 는 유지 → arm 어깨 vertex = R_y(sway) × shoulder.pivot 매 frame 변동.
        // 우리 매핑: vanilla 1.21.1 에 breast 노드 없음. swim 의 arm.pivot R_y(sway) 변환
        //   (fix #146) 가 breast 부모 효과 대체. swing 시도 이 변환 유지해야 어깨가 body 따라 이동.
        //
        // FIX: arm.pivot 변환 매 frame 무조건 적용. rotation (pitch/yaw/roll) 만 preserve.
        //   preCancelParentXPivot(theta=swimTilt≈π/2) 가 추가로 pivot Y/Z 변환 → 최종
        //   pivot = (-5*cos, 5*sin (after R_y), -2 (after R_x cancel)). breast.R + outer.R cancel
        //   결합. dump 검증 (정지 시 sin=0 → pivot=(-5,0,-2)) 정상.
        float swayCos = Mth.cos(bodySway);
        float swaySin = Mth.sin(bodySway);
        rightArm.x = -5f * swayCos;
        rightArm.z =  5f * swaySin;
        leftArm.x  =  5f * swayCos;
        leftArm.z  = -5f * swaySin;
        // pivotY = 2 (= R_y 의 Y axis invariant, 변경 X).

        // 다리 X (앞뒤 발차기) — distance = sm.stats.totalHorizontalDistance.
        rightLeg.xRot = Mth.cos(distance) * 0.52264464f * walkFactor;
        leftLeg.xRot  = Mth.cos(distance + HALF) * 0.52264464f * walkFactor;

        float feetZ = SIXTEENTH * standSneakFactor
                + Mth.cos(totalTime * 0.1f) * 0.4f * (standFactor - sneakFactor);
        rightLeg.zRot =  feetZ;
        leftLeg.zRot  = -feetZ;

        // 원본 SmartMovingModel.java L532-L542: 다리/팔 yScale 호흡 패턴 (sneakFactor 기반).
        // 원본 가드 `if (scaleLegType != NoScaleStart)` — 메인 모델 = Scale 이므로 항상 true.
        float legSc = 1f + (Mth.cos(totalTime * 0.1f + QUARTER) - 1f) * 0.15f * sneakFactor;
        setLegScales(rightLeg, leftLeg, legSc, legSc);
        float armSc = 1f + (Mth.cos(totalTime * 0.1f - QUARTER) - 1f) * 0.15f * sneakFactor;
        // 🔴 fix #191: preserve 시 scale 도 default (= vanilla cubic swing 식과 호환).
        float rightArmSc = preserveRightSwim ? 1f : armSc;
        float leftArmSc  = preserveLeftSwim  ? 1f : armSc;
        setArmScales(rightArm, leftArm, rightArmSc, leftArmSc);

        // 🔴 fix #192 (2026-05-28): preCancelParentX 추가 — setupTransforms swim 분기의
        //   R_x(67.5°~90°) 부모 회전 cancel. CR/SLD/HJ/FLY 와 같은 fix #186 패턴.
        //
        // 사용자 보고 (fix #191 후): "수영 시 팔 휘두르는게 이상하다".
        // dump fact: rArm preserve 작동 (= vanilla cubic 결과 보존). 단 부모 R_x(67.5°) 회전
        //   안에서 cubic 식 (= 직립 자세 가정) 이 적용 → 어깨가 정상 위치에서 휘둘러지는
        //   visual (= STAND swing 과 동일) → SM swim 자세와 안 어울림.
        // 원본 흐름: animateNonStandardWorking 의 shoulder.R(Z=π, Y=workAng, X=vert) 가
        //   부모 회전 보정 + arm.reset() + cubic 식 적용. 1.21.1 매핑은 preCancelParentX 로
        //   동등 효과 (= preserve preferred arm 에 부모 R_x cancel 적용).
        // thetaCancel = sm.smSwimDiveTiltX_prev (= setupTransforms 가 setAngles 직전에
        //   set 한 fade lerped 값. setAngles → swim/dive 분기 호출 시점에 이번 frame 값).
        // 🔴 fix #195 (2026-05-28): preCancelParentXPivot 호출 SKIP — Rotation 만 유지.
        //
        // 사용자 verbatim (fix #194 후): "팔 휘두를 때 어깨가 그 위치에 고정되서 몸 움직임에 안 따라감".
        //
        // ROOT CAUSE (= dump fact + vanilla 매트릭스 합성 분석):
        //   ModelPart pivot world 매핑 (= setupTransforms × scale × T(0,-1.501,0) 후):
        //     pivot_world.x = -px,  pivot_world.y = pz + 1.5,  pivot_world.z = py - 0.001.
        //   원본 의도 어깨 world = R_x(swimTilt=π/2) × R_y(sway) × (-5, 2, 0) scale 후 = (5cos, 5sin, 2).
        //   = y 변동 (= 어깨 위/아래).
        //
        //   fix #194 결과 ModelPart pivot = (-5cos, 2, 5sin) (= R_y(sway) 변환).
        //   fix #192 preCancelParentXPivot(swimTilt=π/2) 적용 후 = (-5cos, 5sin, -2) (= Y/Z swap).
        //   world = (5cos, -2+1.5, 5sin-0.001) = (5cos, -0.5 고정, 5sin).
        //   = y 고정, z 변동 → 사용자 인지 "어깨 고정" 정확 매치.
        //
        // FIX: swim 분기 preCancelParentXPivot SKIP. fix #194 의 pivot (= -5cos, 2, 5sin) 그대로.
        //   world = (5cos, 5sin + 1.5, 2 - 0.001) ≈ (5cos, 5sin + 1.5, 2). 원본 (5cos, 5sin, 2) 와
        //   미세 차이 (+1.5 y offset) 만. 시각 정상.
        //   preCancelParentXRotation 은 유지 — swing 시 cubic 식의 부모 R_x 회전 cancel 효과.
        if (swingSwim > 0F) {
            float thetaCancelSwim = sm.smSwimDiveTiltX_prev;
            if (preserveRightSwim) {
                preCancelParentXRotation(rightArm, thetaCancelSwim);
            }
            if (preserveLeftSwim) {
                preCancelParentXRotation(leftArm, thetaCancelSwim);
            }
        }
    }

    /**
     * isDiving: SM 완전 잠수.
     * 원본: SmartMovingModel.setRotationAngles() 6번 분기 (isDive, L363-L390).
     * bipedOuter X 기울기는 setupTransforms에서 처리.
     * 머리 자세 (B-10 / §16-15):
     *   - head.pitch = -EIGHTH (원본 L370): 다이빙 시 머리 살짝 위로 들기
     *   - head.pivotZ = -2F (원본 L371): 머리 앞으로 2px 이동
     *   vanilla 가 매 프레임 head.pitch (j*PI/180) 와 head.pivotZ (B-9 reset 인프라) 모두 reset → 안전.
     */
    private void sm_animateDiving(SmartMovingClientState sm, AbstractClientPlayer player,
                                  float limbSwing, float limbSwingAmount, float totalTime,
                                  float attackTime) {
        // B-6 / §16-13: 원본 SmartMovingModel L365-L367 = totalDistance (3D 누적) +
        //   currentSpeed (3D 속도). 이전 limbSwing/limbSwingAmount (수평) 잘못 매핑 →
        //   sm.stats.totalDistance/currentSpeed 로 교체. 다이빙은 수직+수평 운동 모두 강한
        //   상태 → 차이 가시화 가능.
        // 🔴 (2026-05-21, fix #125) 60Hz 보간 — getter (partialTicks) 사용.
        //   direct field 접근 = tick 단위 jump → 끊김. sub-tick interpolation 적용.
        float partialTicks = totalTime - (float) Math.floor(totalTime);
        float interpTotalDistance  = sm.stats.getTotalDistance(partialTicks);
        float interpCurrentSpeed   = sm.stats.getCurrentSpeed(partialTicks);
        float distance    = interpTotalDistance * 0.7f;
        float walkFactor  = smFactor(interpCurrentSpeed, 0f, 0.15679921f);
        float standFactor = smFactor(interpCurrentSpeed, 0.15679921f, 0f);

        // 머리 자세 (원본 L370-L371)
        head.xRot  = -EIGHTH;   // 원본 bipedHead.rotateAngleX = -Eighth
        head.z = -2f;       // 원본 bipedHead.rotationPointZ = -2F
        // 🔵 (2026-05-21, fix #118-C) head.yaw=0 강제 — 원본 L370-L371 head.rotateAngleY 미설정 (= 0 유지).
        //   vanilla setAngles 의 head.yaw = netHeadYaw * π/180 잔존 차단 → 머리 mouse 추적 X.
        //   memory [[feedback_smbody_yaw_force_head_yaw_zero]]: bodyYaw force 시 sm_animate* 에 head.yaw=0
        //   강제 패턴. swim 분기는 setAnglesYXZ 가 head.yaw=cos sway 명시 set (= 잔존 cancel), dive 만 누락.
        head.yRot = 0f;

        // 🔵 (2026-05-20, BUG-Swim-Anim-WASD fix #117) vanilla swing pitch cancel.
        //   로그 실측 cause: pre-arms[rP=0.486] (vanilla setAngles 끝, 우리 inject 진입 시) →
        //     DIVE-POST arms[rP=0.454] (우리 inject 끝, arm.pitch 미설정 → vanilla 값 유지).
        //   = vanilla 1.21.1 BipedEntityModel.setAngles 의 swing 식 (= cos(limbSwing*0.6662+π) *
        //     2 * limbSwingAmount * 0.5) 잔존 → wasd 시 limbSwingAmount > 0 → arm.pitch ±0.5
        //     swing → 사용자 보고 "몸통 기준 앞뒤로의 회전값" 정확 cause.
        //   원본 mechanism (실제 코드 read 확정):
        //     - SmartMovingModel extends SmartRenderContext (= ModelBiped 상속 X).
        //     - SmartRenderModel.reset() 매 frame arm.X = 0 reset.
        //     - SmartMovingModel.animateArmSwinging L640-647 의 `if(isStandard)` 가드로 isDive 시
        //       isStandard=false → super.animateArmSwinging (= vanilla ModelBiped swing 식) 호출
        //       자체 미발생 → arm.X 가 0 그대로.
        //   1.21.1 vanilla BipedEntityModel.setAngles 는 가드 없이 swing 식 항상 적용 → 명시
        //     reset 필수.
        //   원본 isDive 분기 (L363-L391) 가 arm.X / leg.X 미설정 = "0 유지" 가 의도.
        //   isSwim/isCrawl/isSlide 분기는 arm.X 명시 set → 영향 X (= isDive 만 고유 cancel 필요).
        // 🔴 fix #191 (2026-05-28): preferred arm preserve 가드 (= fix #177 패러다임).
        //   원본 dive + swing 시 (= isWorking()) animateWorkingBody → animateNonStandardWorking →
        //     bipedRightArm.reset() → cubic 식 적용. = swing 시 dive 자세 사라지고 cubic 결과만.
        //   우리 매핑 누락: arm.pitch=0 명시 cancel + arm.roll=식 set 이 vanilla cubic 식 결과
        //     덮어씀 → swing 시 visual 사라짐.
        //   해결: preserve 가드. swing 시 preferred arm 식 SKIP → vanilla cubic (= fix #188) 보존.
        float swingDive = attackTime;
        HumanoidArm preferredArmDive = player.getMainArm();
        boolean preserveRightDive = swingDive > 0F && preferredArmDive == HumanoidArm.RIGHT;
        boolean preserveLeftDive  = swingDive > 0F && preferredArmDive == HumanoidArm.LEFT;

        if (!preserveRightDive) rightArm.xRot = 0f;
        if (!preserveLeftDive)  leftArm.xRot  = 0f;
        rightLeg.xRot = 0f;
        leftLeg.xRot  = 0f;

        // 🔴 fix #123 reverted (2026-05-21, 사용자 보고 진폭 축소): fade 제거, 원본 instant 식 복원.
        rightLeg.zRot = (Mth.cos(distance) + 1f) * 0.52264464f * walkFactor + SIXTEENTH * standFactor;
        leftLeg.zRot  = (Mth.cos(distance + HALF) - 1f) * 0.52264464f * walkFactor - SIXTEENTH * standFactor;
        if (!preserveRightDive) {
            rightArm.zRot = (Mth.cos(distance + HALF) * 0.52264464f * 2.5f + QUARTER) * walkFactor
                    + (QUARTER + EIGHTH) * standFactor;
        }
        if (!preserveLeftDive) {
            leftArm.zRot  = (Mth.cos(distance) * 0.52264464f * 2.5f - QUARTER) * walkFactor
                    - (QUARTER + EIGHTH) * standFactor;
        }

        // 원본 SmartMovingModel.java L572-L583: 다리/팔 yScale (walkFactor 기반).
        // 원본 가드 `if (scaleLegType != NoScaleStart)` — 메인 모델 = Scale.
        // 다리: 0.25F * walkFactor / 팔: 0.15F * walkFactor (계수 다름).
        float legSc = 1f + (Mth.cos(distance - QUARTER) - 1f) * 0.25f * walkFactor;
        setLegScales(rightLeg, leftLeg, legSc, legSc);
        float armSc = 1f + (Mth.cos(distance + QUARTER) - 1f) * 0.15f * walkFactor;
        // fix #191: preserve 시 scale 도 default.
        float rightArmScDive = preserveRightDive ? 1f : armSc;
        float leftArmScDive  = preserveLeftDive  ? 1f : armSc;
        setArmScales(rightArm, leftArm, rightArmScDive, leftArmScDive);

        // 🔴 fix #192: preCancelParentX 추가 — setupTransforms dive 분기의 R_x 부모 회전 cancel.
        //   swim 과 동일 fade prev field (sm.smSwimDiveTiltX_prev) 공유.
        if (swingDive > 0F) {
            float thetaCancelDive = sm.smSwimDiveTiltX_prev;
            if (preserveRightDive) {
                preCancelParentXPivot(rightArm, thetaCancelDive);
                preCancelParentXRotation(rightArm, thetaCancelDive);
            }
            if (preserveLeftDive) {
                preCancelParentXPivot(leftArm, thetaCancelDive);
                preCancelParentXRotation(leftArm, thetaCancelDive);
            }
        }
    }

    /**
     * isCrawling: 바닥 크롤링.
     * 원본: SmartMovingModel.setRotationAngles() 7번 분기 (isCrawl, L395-L431).
     * 몸통 X 기울기 (QUARTER-THIRTYTWOTH ≈ 79°)로 수평 자세 재현.
     * 원본 YZX 회전 순서 → setAnglesYZX 헬퍼로 정확하게 변환.
     * pivot 보정 (B-12 / §16-18):
     *   - head.pivotZ = -2F (원본 L401): 몸이 수평이므로 머리 앞쪽 2 픽셀 이동
     *   - body.pivotY = +3F (원본 L405): 수평 자세에서 몸통 위치 보정 (SR bipedTorso 단일 노드 근사)
     */
    private void sm_animateCrawling(SmartMovingClientState sm, net.minecraft.client.player.AbstractClientPlayer player, float headYaw) {
        // 🔴 (Phase 1, Crawl 애니메이션 1:1):
        //   원본 SmartMovingModel L395-L397 입력 = totalHorizontalDistance + currentHorizontalSpeedFlattened.
        //   이전 매핑 limbSwing/limbSwingAmount (vanilla limbAnimator) 잘못. 그랩 클라이밍 패턴 동일.
        float partialTicks = SmartMovingClientState.globalCachedTickDelta;
        float distance    = sm.stats.getTotalHorizontalDistance(partialTicks) * 1.3f;
        // 🔴 (2026-05-03 crawl 디테일 fix): partial ticks lerp getter 사용 (= 60Hz 부드러움).
        //   원본 SmartStatistics.getCurrentHorizontalSpeedFlattened(partialTicks, -1) 매핑.
        float speedFlat   = sm.stats.getCurrentHorizontalSpeedFlattened(partialTicks);
        float walkFactor  = smFactor(speedFlat, 0f, 0.12951545f);
        float standFactor = smFactor(speedFlat, 0.12951545f, 0f);

        // 머리
        // 🔴 사용자 보고 fix (2026-05-03 — "엎드린 상태에서 머리 움직임 원본과 다름"):
        //   원본 SmartMovingModel.java L399-L401 isCrawl 분기:
        //     bipedHead.rotateAngleZ = -viewHorizontalAngelOffset / RadiantToAngle
        //     bipedHead.rotateAngleX = -Eighth
        //     bipedHead.rotationPointZ = -2F
        //     ⚠ bipedHead.rotateAngleY 는 미설정 → reset() 값 0 유지.
        //   원본 animateHeadRotation (L626-L632) 의 isStandard 가드 → isCrawl != isStandard →
        //     superAnimateHeadRotation 호출 X → SR default head.rotateAngleY 매핑 미적용.
        //   우리 sm_setAngles 가 @At("TAIL") → vanilla BipedEntityModel.setAngles 이미
        //     head.yaw = headYaw * π/180 설정. cancel 안 하면 살아있음 → 마우스 좌우 회전이
        //     head.roll (= -headYaw 매핑) + head.yaw (= vanilla 잔존) 양쪽 적용 → 어긋남.
        //   fix: head.yaw = 0 명시.
        head.zRot  = -headYaw * DEG_TO_RAD;
        head.xRot = -EIGHTH;
        head.yRot   = 0f;
        head.z = -2f;   // 원본 bipedHead.rotationPointZ = -2F (B-12 / §16-18)

        // 🔴 (Phase 1, Crawl 애니메이션 1:1): 몸통 회전 순서 YZX 매핑.
        //   원본 L403 `bipedTorso.rotationOrder = ModelRotationRenderer.YZX` 명시.
        //   1.21.1 ModelPart 기본 ZYX → setAnglesYZX_v2 (정확 매핑) 사용.
        // 🔴 Phase 3 자식 효과 매핑 (2026-05-03): body.pitch 의 QUARTER-THIRTYTWOTH 항 제거 +
        //   body.pivotY = 3f 제거. entity 회전 + translate 가 sm_setupTransforms 의 isCrawling 분기에서
        //   대신 적용 (= 모든 자식 노드 일괄 처리). 원본 bipedTorso 부모 효과 1:1.
        //   본 위치는 LOCAL 미세 진동 (Y/Z cos) 만 유지.
        // 🔴 setAnglesYZX → setAnglesYZX_v2 변경 (2026-05-03 사용자 보고 fix — "팔/몸통 디테일"):
        //   기존 setAnglesYZX BUG (vertex 적용 X→Z→Y, 원본 Y→Z→X 와 반대). v2 = 정확 매핑.
        //   body 의 yaw/roll 작은 cos 라 영향 미세하지만 1:1 정확.
        setAnglesYZX_v2(body,
                0f,                                                              // ← entity 회전이 대신 적용
                Mth.cos(distance + HALF) * SIXTYFOURTH * walkFactor,    // 원본 L407 bipedBody.Y
                0f);                                                             // L406 bipedTorso.Z 는 sm_setupTransforms 의 matrices.rotate(R_z) 에서 매핑 (= 부모 효과 1:1)
        // body.pivotY = 3f 제거 — entity translate 가 대신 적용

        // 다리
        rightLeg.xRot = (Mth.cos(distance - QUARTER) * SIXTYFOURTH + THIRTYTWOTH) * walkFactor
                + THIRTYTWOTH * standFactor;
        leftLeg.xRot  = (Mth.cos(distance - HALF - QUARTER) * SIXTYFOURTH + THIRTYTWOTH) * walkFactor
                + THIRTYTWOTH * standFactor;
        rightLeg.zRot  = (Mth.cos(distance - QUARTER) + 1f) * 0.25f * walkFactor + THIRTYTWOTH * standFactor;
        leftLeg.zRot   = (Mth.cos(distance - QUARTER) - 1f) * 0.25f * walkFactor - THIRTYTWOTH * standFactor;

        // 팔 (YZX 순서): pitch=X(앞뒤), yaw=Y(±Quarter), roll=Z(좌우)
        float rRoll = (Mth.cos(distance + HALF) * SIXTYFOURTH + THIRTYTWOTH) * walkFactor
                + SIXTEENTH * standFactor;
        float lRoll = (Mth.cos(distance + HALF) * SIXTYFOURTH - THIRTYTWOTH) * walkFactor
                - SIXTEENTH * standFactor;
        // 🔴 setAnglesYZX_v2 사용 (2026-05-03 사용자 보고 fix — "팔이 하늘로 들림"):
        //   원본 YZX vertex 적용 = Y → Z → X. arm yaw=±90° 큰 회전 + pitch=225° 큰 회전 →
        //   회전 순서 차이가 팔 위치에 큰 visual 영향. v2 = 원본 1:1.
        // 🔴 fix #186 (2026-05-28): fix #183 revert — fix #177 패러다임 복원 (메모리 검증 패턴).
        //   preserve 가드 + preCancelParentX 복원. CR 분기 setupTransforms R_x(-78.75°) 부모 회전 있음.
        float swingCrawl = player.getSwingAnimation(1.0F);
        HumanoidArm preferredArmCrawl = player.getMainArm();
        boolean preserveRightCrawl = swingCrawl > 0F && preferredArmCrawl == HumanoidArm.RIGHT;
        boolean preserveLeftCrawl  = swingCrawl > 0F && preferredArmCrawl == HumanoidArm.LEFT;
        if (!preserveRightCrawl) setAnglesYZX_v2(rightArm, HALF + EIGHTH, -QUARTER, rRoll);
        if (!preserveLeftCrawl)  setAnglesYZX_v2(leftArm,  HALF + EIGHTH,  QUARTER, lRoll);
        if (swingCrawl > 0F) {
            // setupTransforms R_x(-tiltAngle) cancel. tiltAngle = π/2 - π/16 = 78.75°.
            float thetaCancelCrawl = (float)(Math.PI / 2 - Math.PI / 16);
            if (preserveRightCrawl) {
                preCancelParentXPivot(rightArm, thetaCancelCrawl);
                preCancelParentXRotation(rightArm, thetaCancelCrawl);
            }
            if (preserveLeftCrawl) {
                preCancelParentXPivot(leftArm, thetaCancelCrawl);
                preCancelParentXRotation(leftArm, thetaCancelCrawl);
            }
        }

        // 원본 SmartMovingModel.java L622-L626 + L645-L648: 다리/팔 yScale.
        // 원본 가드 `if (scaleLegType != NoScaleStart)` / `scaleArmType != NoScaleStart` — 메인 모델 = Scale.
        // 좌우 위상 다름 — 원본 표기 그대로 보존 (cos(distance + Quarter - Quarter) = cos(distance) 등 단순화 안 함).
        setLegScales(rightLeg, leftLeg,
                1f + (Mth.cos(distance + QUARTER - QUARTER) - 1f) * 0.25f * walkFactor,
                1f + (Mth.cos(distance - QUARTER - QUARTER) - 1f) * 0.25f * walkFactor);
        setArmScales(rightArm, leftArm,
                1f + (Mth.cos(distance + QUARTER) - 1f) * 0.15f * walkFactor,
                1f + (Mth.cos(distance - QUARTER) - 1f) * 0.15f * walkFactor);

        // 🔴 (Phase 2 multi BUG-12) vanilla setAngles 의 sneak 분기 set field cancel.
        //   진입 edge frame 에 server-sync pose 가 CROUCHING 잔존 (= 직전 sneak 키 누름) +
        //   sm.isCrawling=true (= SM packet 도착) 인 경우 vanilla setAngles 가 sneak 분기 진입 →
        //   body.pivotY=3.2/head.pivotY=4.2/leg.pivotZ=4/arm.pivotY=5.2/leg.pivotY=12.2 set.
        //   sm_animateCrawling 가 회전만 set → vanilla sneak pivotY 잔존 → 자세 부분별 어긋남.
        //   해결: sneak 분기 set field 를 standing default 값 으로 강제 cancel.
        //   self 측은 pose=SWIMMING 즉시 → vanilla sneak 분기 미진입 → 영향 없음.
        body.y = 0f;
        head.y = 0f;
        rightArm.y = 2f;
        leftArm.y = 2f;
        rightLeg.y = 12f;
        leftLeg.y = 12f;
        rightLeg.z = 0f;
        leftLeg.z = 0f;
    }

    /**
     * isSliding: 미끄러지기.
     * 원본: SmartMovingModel.setRotationAngles() 8번 분기 (isSlide, L437-L478).
     * bipedOuter X 기울기(Quarter) + outer.pivotY=+5F 는 sm_setupTransforms 에서 처리.
     * 원본 YZX 회전 순서 → setAnglesYZX 헬퍼로 정확하게 변환.
     * 머리/몸통 피벗 (B-13 / §16-19):
     *   - head.roll  = -headYaw * DEG_TO_RAD (원본 L442 -viewHorizontalAngelOffset/RadiantToAngle)
     *   - head.pivotZ = -2F (원본 L444)
     *   - body.pivotY = +6.5F (원본 L453, SR 다층 부재로 body 단일 노드 근사)
     *   - body.offsetY = -0.4F (원본 L452): ModelPart 에 offsetY 필드 부재로 sm_setupTransforms 의
     *     matrices.translate(0, -0.4F/16F, 0) 로 보정 (slide 분기 안에 통합)
     */
    private void sm_animateSliding(SmartMovingClientState sm, net.minecraft.client.player.AbstractClientPlayer player, float headYaw) {
        // 🔴 BUG-Slide-Anim-6 fix (2026-05-04 사용자 보고 — "디테일 값 다름"):
        //   원본 SmartMovingModel.java L439-L440 입력 = totalHorizontalDistance + currentHorizontalSpeed.
        //   SmartMovingModel L62/L84: `currentHorizontalSpeedFlattened` 가 NaN 아닐 때
        //     `currentHorizontalSpeed = currentHorizontalSpeedFlattened` 덮어씀.
        //   원본 SmartRenderRender L56-L57: getTotalDistance/getCurrentSpeed(renderPartialTicks) lerped getter.
        //   이전 매핑 limbSwing/limbSwingAmount (vanilla limbAnimator) 잘못 — sm_animateCrawling
        //     동일 패턴으로 정정 (= partial tick lerp + Flattened 속도).
        float partialTicks = SmartMovingClientState.globalCachedTickDelta;
        float distance   = sm.stats.getTotalHorizontalDistance(partialTicks) * 0.7f;
        float speedFlat  = sm.stats.getCurrentHorizontalSpeedFlattened(partialTicks);
        float walkFactor = smFactor(speedFlat, 0f, 1f) * 0.8f;

        // 🔴 BUG-Slide-Anim-7 fix (2026-05-04 사용자 보고 — "마우스 움직임에 따른 머리 고정"):
        //   원본 SmartMovingModel.java L442-L444 isSlide 분기:
        //     bipedHead.rotateAngleZ = -viewHorizontalAngelOffset / RadiantToAngle
        //     bipedHead.rotateAngleX = -Eighth - Sixteenth
        //     bipedHead.rotationPointZ = -2F
        //     ⚠ bipedHead.rotateAngleY 는 미설정 → SR reset() 효과로 0 유지.
        //   우리 sm_setAngles 가 @At("TAIL") → vanilla BipedEntityModel.setAngles 이미
        //     head.yaw = headYaw * π/180 설정. cancel 안 하면 잔존 → 마우스 좌우 회전이
        //     head 따라옴 → 사용자 보고 BUG.
        //   메모리 feedback_smbody_yaw_force_head_yaw_zero.md: smBodyYawOverride force +
        //     head.yaw=0 force 동시 적용 패턴.
        //   sm_animateCrawling (L928) 동일 패턴 차용 — head.yaw=0 명시.
        head.xRot  = -EIGHTH - SIXTEENTH;
        head.zRot   = -headYaw * DEG_TO_RAD;   // 원본 bipedHead.rotateAngleZ = -viewHorizontalAngelOffset/RadiantToAngle (B-13)
        head.yRot    = 0f;                      // ★ vanilla netHeadYaw cancel
        head.z = -2f;                     // 원본 bipedHead.rotationPointZ = -2F (B-13)

        // 몸통 (YXZ 순서) — 원본 SmartMovingModel.java L672-L676:
        //   bipedBody.rotationOrder = YXZ
        //   bipedBody.rotateAngleX = cos(distance - Eighth) * Sixtyfourth * walkFactor
        //   bipedBody.rotateAngleY = cos(distance + Eighth) * Sixtyfourth * walkFactor
        // R-17: YXZ → GL call Z, X, Y → setAnglesYXZ 헬퍼.
        setAnglesYXZ(body,
                Mth.cos(distance - EIGHTH) * SIXTYFOURTH * walkFactor,
                Mth.cos(distance + EIGHTH) * SIXTYFOURTH * walkFactor,
                0f);
        // 🔴 사용자 보고 fix (2026-05-04 — "몸통 자체가 다리쪽으로 내려감"):
        //   원본 `bipedBody.rotationPointY = +6.5F` (L453) 직접 매핑은 body cuboid 를 modelpart Y
        //   +6.5 (다리쪽) 으로 이동 → R_x(-π/2) 회전 후 modelpart Z -6.5 (= 다리 방향) → body 가
        //   다리쪽 너머로 이동 BUG.
        //   원본 SR 다층 구조 (bipedOuter → bipedTorso → bipedBody) 의 부모-자식 누적 효과가
        //   1.21.1 vanilla biped 단일 노드 매핑에서 다른 결과. body.pivotY 직접 매핑은 부정확.
        //   회전 origin 변경 (sm_setupTransforms pivotY) 이 이미 모든 노드 +5 효과 내포 →
        //   body.pivotY 추가 적용 = 누적 효과 + 6.5 = body 만 다리쪽 너무 많이.
        //   fix: body.pivotY = 0 (default, body 자체 추가 이동 제거) → 어깨 부근 정상 위치.
        body.y = 0f;

        // 다리
        rightLeg.xRot = Mth.cos(distance + HALF) * SIXTYFOURTH * walkFactor + SIXTYFOURTH;
        leftLeg.xRot  = Mth.cos(distance + QUARTER) * SIXTYFOURTH * walkFactor + SIXTYFOURTH;
        rightLeg.zRot  =  THIRTYTWOTH;
        leftLeg.zRot   = -THIRTYTWOTH;

        // ★★ 원본 SmartMovingModel.java L463-L473 원자 단위 1:1 번역 ★★
        //   bipedRightArm.rotationOrder = ModelRotationRenderer.YZX  ← R_x * R_z * R_y (vertex Y→Z→X)
        //   bipedRightArm.rotateAngleX = cos(distance + Quarter) * Sixtyfourth * walkFactor + Half - Sixtyfourth
        //   bipedLeftArm.rotateAngleX  = cos(distance - Half)    * Sixtyfourth * walkFactor + Half - Sixtyfourth
        //   bipedRightArm.rotateAngleZ =  Sixteenth     bipedLeftArm.rotateAngleZ = -Sixteenth
        //   bipedRightArm.rotateAngleY = -Quarter       bipedLeftArm.rotateAngleY =  Quarter
        //
        // 🔴 사용자 보고 fix v4 (2026-05-04 — "팔 흔들림 좌우 → 위아래" 13단계 시행착오 정착):
        //   원인: vanilla ModelPart.rotate 의 rotationZYX(roll, yaw, pitch) hardcoded.
        //         원본 yaw = -π/2 정확히 ZYX gimbal lock 영역.
        //         JOML getEulerAnglesZYX 분해 부정확 → qDiff angle = π (정반대 회전 손실).
        //         setAnglesYZX/YXZ 헬퍼 + 직접 set 모두 손실 발생.
        //   디버그 dump 검증:
        //     INTENT (의도 1.7.10 YZX) hand X = -8.83 정적 (어깨 옆 펼침), slidY 진동 1.4 (위아래 ✓).
        //     REAL (분해 후 vanilla ZYX) hand X = -1.36~-2.85 (가운데 모임 ✗), slidY 정적 (위아래 X ✗).
        //   해결: MixinModelPart 으로 ModelPart.rotate 의 rotationZYX 우회 → 의도 q 직접 적용.
        //         메모리 feedback_zxy_zyx_rotation_order.md 가이드라인 (큰 yaw 헬퍼 금지) 준수.
        float armYaw = -QUARTER;
        float rPitch = Mth.cos(distance + QUARTER) * SIXTYFOURTH * walkFactor + HALF - SIXTYFOURTH;
        float lPitch = Mth.cos(distance - HALF)    * SIXTYFOURTH * walkFactor + HALF - SIXTYFOURTH;

        // 🔴 fix #186 (2026-05-28): fix #183 revert — fix #177 패러다임 복원 (메모리 검증 패턴).
        //   preserve 가드 + preCancelParentX 복원. SLD setupTransforms R_x(-π/2) 부모 회전 있음.
        //   preferred arm 의 sm_setOverrideQuat skip → vanilla animateArms swing 효과 보존 +
        //   MixinModelPart override quat clear path (= 비-override frame vanilla path).
        float swingSlide = player.getSwingAnimation(1.0F);
        HumanoidArm preferredArmSlide = player.getMainArm();
        boolean preserveRightSlide = swingSlide > 0F && preferredArmSlide == HumanoidArm.RIGHT;
        boolean preserveLeftSlide  = swingSlide > 0F && preferredArmSlide == HumanoidArm.LEFT;

        // 의도 q = q_x(pitch) * q_z(roll) * q_y(yaw) — 원본 1.7.10 YZX 매트릭스.
        if (!preserveRightSlide) {
            Quaternionf qRight = new Quaternionf().rotationY(armYaw);
            qRight.premul(new Quaternionf().rotationZ(SIXTEENTH));
            qRight.premul(new Quaternionf().rotationX(rPitch));
            ((SmModelPartOverride)(Object) rightArm).sm_setOverrideQuat(qRight);
        }

        if (!preserveLeftSlide) {
            Quaternionf qLeft = new Quaternionf().rotationY(-armYaw);
            qLeft.premul(new Quaternionf().rotationZ(-SIXTEENTH));
            qLeft.premul(new Quaternionf().rotationX(lPitch));
            ((SmModelPartOverride)(Object) leftArm).sm_setOverrideQuat(qLeft);
        }

        if (swingSlide > 0F) {
            float thetaCancelSlide = (float)(Math.PI / 2);
            if (preserveRightSlide) {
                preCancelParentXPivot(rightArm, thetaCancelSlide);
                preCancelParentXRotation(rightArm, thetaCancelSlide);
            }
            if (preserveLeftSlide) {
                preCancelParentXPivot(leftArm, thetaCancelSlide);
                preCancelParentXRotation(leftArm, thetaCancelSlide);
            }
        }
    }


    /**
     * isFlying (creative): 창작 모드 비행.
     * 원본: SmartMovingModel.setRotationAngles() 9번 분기 (isFlying).
     * bipedOuter X 기울기는 setupTransforms에서 처리.
     * 원본 팔 회전 순서 XZY → 여기서는 XYZ 근사.
     *
     * ANIM-01: head.pitch 보정.
     * setupTransforms @TAIL: matrices.multiply(POSITIVE_X.rotation(θ)) 전역 적용.
     * θ = (Quarter - currentVerticalAngle) * walkFactor
     * 원본: bipedHead.X = -bipedOuter.X / 2 = -θ/2 → world-space head X = θ/2
     * 1.21.1 등가: head.pitch = -θ/2 (전역 θ 상쇄 후 최종 θ/2)
     */
    private void sm_animateFlying(SmartMovingClientState sm, net.minecraft.client.player.AbstractClientPlayer player, float limbSwing, float limbSwingAmount, float totalTime) {
        // 🔴 (세션 65g): preferred arm 만 vanilla swing 처리. 다른 모델 (몸/다리/다른 팔/head)
        //   은 sm 비행 자세 그대로 유지.
        //   사용자 요구: "오른팔 (휘두르는 팔만 그런거야). 모든 에니메이션을 갑자기 초기화하지
        //   말고. 휘두르는 에니메이션만 처리".
        //   = preferred arm 만 setAnglesXZY skip (vanilla swing 잔존) + 부모 X 회전 cancel
        //     (직립 자세). Y 회전은 그대로 (이동 방향 따라감, 모델과 같이 회전).

        // 🔴 (세션 52): partial tick lerp 적용 — 원본 SmartRenderRender.renderPlayer L56-L57:
        //   `totalDistance = statistics.getTotalDistance(renderPartialTicks)` —
        //   `currentSpeed = statistics.getCurrentSpeed(renderPartialTicks)` 매 프레임 lerped 값.
        //   이전 1.21.1 매핑은 sm.stats.totalDistance/currentSpeed 직접 사용 (lerp 안 함) →
        //   매 틱 띡 변경 → 팔/다리/모든 애니메이션 부드럽지 않음 = 사용자 보고 직접 원인.
        //   정정: setupTransforms 에서 cached tickDelta 사용 → lerped getter 호출.
        //   세션 52b: SmartMovingClientState.globalCachedTickDelta 로 이동 (Mixin private static 제약).
        float partialTicks = SmartMovingClientState.globalCachedTickDelta;

        // B-7 / §16-20: 원본 L477-L479 = totalDistance (3D 누적) + currentSpeed (3D 속도).
        //   원본 SmartRenderRender L56-L57: getTotalDistance/getCurrentSpeed(renderPartialTicks).
        float currentSpeedLerped = sm.stats.getCurrentSpeed(partialTicks);
        float distance    = sm.stats.getTotalDistance(partialTicks) * 0.08f;
        float walkFactor  = smFactor(currentSpeedLerped, 0f, 1f);
        float standFactor = smFactor(currentSpeedLerped, 1f, 0f);

        // 팔 (XZY 순서) — 원본 SmartMovingModel.java L696-L733:
        //   bipedRightArm.rotationOrder = XZY
        //   rotateAngleY = cos(time*0.15) * Sixteenth * standFactor   (정지 시 미세 흔들림)
        //   rotateAngleZ = (cos(distance + Half) * Sixtyfourth + Half - Sixteenth) * walkFactor + Quarter * standFactor   (날개짓)
        // R-17: XZY → GL call Y, Z, X → setAnglesXZY 헬퍼.
        //
        // 🔴 fix #186 (2026-05-28): fix #183 revert — fix #177 패러다임 복원 (메모리 검증 13단계 시행착오 정착).
        //   project_flying_complete 메모리 명시: 비행 swing arm 매핑 함부로 수정 금지.
        //   preserve 가드 + preCancelParentX 복원. FLY setupTransforms R_x(-θ) 부모 회전 있음.
        float swing = player.getSwingAnimation(1.0F);
        HumanoidArm preferredArm = player.getMainArm();
        boolean preserveRight = swing > 0F && preferredArm == HumanoidArm.RIGHT;
        boolean preserveLeft  = swing > 0F && preferredArm == HumanoidArm.LEFT;

        float rYaw  = Mth.cos(totalTime * 0.15f) * SIXTEENTH * standFactor;
        float lYaw  = Mth.cos(totalTime * 0.15f) * SIXTEENTH * standFactor;
        float rRoll = (Mth.cos(distance + HALF) * SIXTYFOURTH + HALF - SIXTEENTH) * walkFactor
                + QUARTER * standFactor;
        float lRoll = (Mth.cos(distance) * SIXTYFOURTH - HALF + SIXTEENTH) * walkFactor
                - QUARTER * standFactor;
        if (!preserveRight) setAnglesXZY(rightArm, 0f, rYaw, rRoll);
        if (!preserveLeft)  setAnglesXZY(leftArm,  0f, lYaw, lRoll);

        // 다리
        rightLeg.xRot = Mth.cos(distance) * SIXTYFOURTH * walkFactor
                + Mth.cos(totalTime * 0.15f + HALF) * SIXTYFOURTH * standFactor;
        leftLeg.xRot  = Mth.cos(distance + HALF) * SIXTYFOURTH * walkFactor
                + Mth.cos(totalTime * 0.15f) * SIXTYFOURTH * standFactor;
        rightLeg.zRot  =  SIXTYFOURTH;
        leftLeg.zRot   = -SIXTYFOURTH;

        // ANIM-01: head pitch 보정 — setupTransforms theta의 절반 역보정
        // 🔴 1:1 정정 (세션 47b): 원본 SmartMovingModel L481 isJump 분기 추가.
        // 🔴 (세션 52): currentSpeedLerped 사용 — 매 프레임 부드러운 변화.
        // 🔴 (세션 56 revert): 세션 54 의 fade lerped 매핑 revert. 원본 1:1 (raw target / 2).
        //   원본 SmartMovingModel L488: bipedHead.rotateAngleX = -bipedOuter.rotateAngleX / 2F
        //   (이때 bipedOuter.X = setRotationAngles 시점 raw target, fadeIntermediate 호출 전).
        //   = head.pitch = -raw_theta / 2f. 우리 매핑 정확.
        float verticalAngle = sm.isJumping
                ? Math.abs(sm.stats.currentVerticalAngle)
                : sm.stats.currentVerticalAngle;
        float theta = (QUARTER - verticalAngle) * currentSpeedLerped;
        head.xRot = -theta / 2f;

        // 🔴 (세션 60): 원본 reset() 효과 매핑 — bipedHead.Y/Z = 0 강제.
        //   원본 SmartRenderModel.setRotationAngles L166: `reset()` 호출 → 모든 ModelPart 의
        //   rotation = 0 reset. SmartMovingModel.isFlying 분기 (L484-L488) 가 bipedHead.X 만 set,
        //   bipedHead.Y/Z 는 reset 후 0 유지 → 머리 정면 (몸 정렬) 완전 고정.
        //   1.21.1 vanilla setAngles 가 매 프레임 head.yaw = (headYaw - bodyYaw) * π/180 set →
        //   우리 entity.bodyYaw = lerpedYaw 강제 후 head.yaw ≈ 0 이지만 미묘한 차이 (lerping 등).
        //   원본 reset 효과 1:1 매핑 = 명시적 0 강제.
        head.yRot  = 0f;
        head.zRot = 0f;

        // 🔴 fix #186 (2026-05-28): fix #177 패러다임 복원 — preCancelParentXRotation/Pivot.
        //   feedback_animateArms_cancel 메모리 명시: 부모 X 회전 있는 분기에 preCancel 필수.
        //   vanilla animateArms 의 arm.pivot 변동 cancel (= 비-preferred 만 default).
        if (swing > 0F) {
            float thetaCancel = lerpFadeAngle(sm.smOuterTiltX_prev, theta,
                                              sm.smOuterFade_prevTime, totalTime);
            if (preserveRight) {
                preCancelParentXPivot(rightArm, thetaCancel);
                preCancelParentXRotation(rightArm, thetaCancel);
            }
            if (preserveLeft) {
                preCancelParentXPivot(leftArm, thetaCancel);
                preCancelParentXRotation(leftArm, thetaCancel);
            }

            // 비-preferred arm 만 vanilla setAngles Step 4 기본값 복원
            // (preferred arm 은 vanilla swing 효과 그대로 보존).
            if (!preserveRight) {
                rightArm.x = -5f;
                rightArm.z =  0f;
            }
            if (!preserveLeft) {
                leftArm.x  =  5f;
                leftArm.z  =  0f;
            }
        }
    }

    /**
     * isHeadJumping: 헤드점프 (낮은 천장 아래 점프).
     * 원본: SmartMovingModel.setRotationAngles() 10번 분기 (isHeadJump).
     * bipedOuter X/Y 기울기는 setupTransforms에서 처리.
     * smallOverGroundHeight로 팔 Z 각도 클램프.
     */
    private void sm_animateHeadJumping(SmartMovingClientState sm, AbstractClientPlayer player) {
        float angle = sm.stats.currentVerticalAngle;

        // bendFactor: Factor(angle, Quarter, 0) ∩ Factor(angle, -Quarter, 0)
        // 수직 각도 0°(수평)일 때 최대 1, ±Quarter(수직)일 때 0. (SmartMovingModel.md 10번 분기)
        float bendFactor = Math.min(smFactor(angle, QUARTER, 0f), smFactor(angle, -QUARTER, 0f));
        // 🔴 fix #186 (2026-05-28): fix #183 revert — fix #182 패러다임 복원 (메모리 검증).
        //   preserve 가드 + preCancelParentX 복원. HJ setupTransforms R_x(QUARTER - angle) 부모 회전 있음.
        float swingHJ = player.getSwingAnimation(1.0F);
        HumanoidArm preferredArmHJ = player.getMainArm();
        boolean preserveRightHJ = swingHJ > 0F && preferredArmHJ == HumanoidArm.RIGHT;
        boolean preserveLeftHJ  = swingHJ > 0F && preferredArmHJ == HumanoidArm.LEFT;
        if (!preserveRightHJ) rightArm.xRot = bendFactor * -EIGHTH;
        if (!preserveLeftHJ)  leftArm.xRot  = bendFactor * -EIGHTH;
        rightLeg.xRot = bendFactor * -EIGHTH;
        leftLeg.xRot  = bendFactor * -EIGHTH;

        // 머리 X 보정: setupTransforms에서 θ=Quarter-angle이 전역 적용됨.
        // 원본 head world-X = θ/2 → head.pitch = -θ/2 (C-09-2 등가 증명)
        head.xRot = -(QUARTER - angle) / 2f;

        // 🔴 head.yaw=0 강제 (2026-05-11, 사용자 보고 "헤드점프 시 머리가 마우스 따라 움직임"):
        //   feedback_smbody_yaw_force_head_yaw_zero.md 패턴 1:1 적용.
        //   setupTransforms 에서 smBodyYawOverride 가 ModifyArg 인자만 force (entity.bodyYaw
        //   필드는 자유 값) → vanilla setAngles netHeadYaw = (headYaw - 자유 bodyYaw lerp)
        //   잔존 → head.yaw 가 마우스 따라 회전.
        //   원본 1.7.10 은 entity.renderYawOffset 필드 자체 force → netHeadYaw = (마우스 -
        //   force 된 이동 방향) → 이동 방향 기준 회전 (사용자 시각 "거의 고정").
        //   head.yaw=0 강제로 vanilla netHeadYaw 효과 cancel → head world yaw = force bodyYaw
        //   (= 이동 방향) → 마우스 무관 고정. 다른 SM 분기 (sliding/flying/crawling/angleJump)
        //   와 동일 패턴.
        head.yRot = 0f;

        // 팔 Z: Factor(angle, Quarter, -Quarter). 원본 SmartMovingModel L520-522:
        //   armFactorZ = Factor(currentVerticalAngle, Quarter, -Quarter);
        //   if (overGroundBlock != null && overGroundBlock.getMaterial().isSolid())
        //       armFactorZ = Math.min(armFactorZ, smallOverGroundHeight / 5F);
        //
        // 원본 getOverGroundBlockId 는 박스 발 위치 (= floor(bb.minY)) 의 블록 1회 검사 후
        //   즉시 반환 (= 1.7.10 air 도 non-null 이라 for 첫 iteration 에서 반환).
        //   박스 발 = 헤드점프 진행 중 일반적으로 공중 → air block → isSolid=false →
        //   원본 clamp 미적용 (= armFactorZ = factorZraw 그대로).
        //   feedback_headjump_overground_air_is_solid.md.
        float armFactorZ = smFactor(angle, QUARTER, -QUARTER);
        if (sm.smallOverGroundHeight < 5f && isOverGroundBlockSolid(player)) {
            armFactorZ = Math.min(armFactorZ, sm.smallOverGroundHeight / 5f);
        }
        if (!preserveRightHJ) rightArm.zRot =  HALF - SIXTEENTH + armFactorZ * EIGHTH;
        if (!preserveLeftHJ)  leftArm.zRot  = -(HALF - SIXTEENTH) - armFactorZ * EIGHTH;

        // 다리 Z: Factor(angle, -Quarter, Quarter)
        float legFactorZ = smFactor(angle, -QUARTER, QUARTER);
        rightLeg.zRot =  SIXTYFOURTH * legFactorZ;
        leftLeg.zRot  = -SIXTYFOURTH * legFactorZ;

        // 🔴 fix #187 (2026-05-28): preCancelParentX θ 를 setupTransforms 의 fade lerped 값으로 변경.
        //   기존: thetaCancelHJ = QUARTER - angle (= raw target). setupTransforms 는 fade lerp 결과
        //   사용 → 두 식 mismatch → swing 진행 동안 cancel 부정확 → 사용자 verbatim
        //   "HJ + 여우무빙 빠른 느낌".
        //   해결: setupTransforms HJ 분기 (MixinPlayerEntityRenderer L914) 가 갱신한 마지막
        //   thetaLerped 값 (= sm.smHeadJumpTiltX_prev) 사용. setupTransforms 가 setAngles 보다
        //   먼저 호출되므로 이 시점에 이번 frame 적용 값.
        //   여우무빙 (wasSelfSlideFire=true) 시도 setupTransforms 의 thetaTarget = π/2 고정 +
        //   prev = π/2 강제 → smHeadJumpTiltX_prev = π/2 → preCancelParentX 정확 매치.
        if (swingHJ > 0F) {
            float thetaCancelHJ = sm.smHeadJumpTiltX_prev;
            if (preserveRightHJ) {
                preCancelParentXPivot(rightArm, thetaCancelHJ);
                preCancelParentXRotation(rightArm, thetaCancelHJ);
            }
            if (preserveLeftHJ) {
                preCancelParentXPivot(leftArm, thetaCancelHJ);
                preCancelParentXRotation(leftArm, thetaCancelHJ);
            }
        }
    }

    /**
     * 원본 SmartMovingBase.getOverGroundBlockId(distance).getMaterial().isSolid() 1:1 매핑.
     *
     * 원본 식 (L862-882):
     *   int y = floor(bb.minY);
     *   for(; y >= minY; y--) {
     *       Block block = world.getBlock(x, y, z);   // 1.7.10 air 도 non-null
     *       if(block != null) return block;            // 항상 true → 첫 iteration 즉시 반환
     *   }
     *   → 박스 발 위치 (= floor(bb.minY)) 의 블록 1회 검사 후 반환.
     *
     * 1.21.1 등가:
     *   - getBlockState(BlockPos) 항상 BlockState 반환 (null 안 됨).
     *   - isSolid() 등가 = collision shape 비어있지 않음 (air → false, solid → true).
     *
     * 헤드점프 진행 동안 박스 발 = 일반적으로 공중 → air → false → clamp 미적용.
     *
     * 멀티 친화: AbstractClientPlayerEntity (self + remote 공통).
     *   박스 발 위치 self/remote 모두 entity.y + 1m (mixin offset 활성, POSE=SLIDING).
     *   원본 1.7.10 의 esp==null 보정 (y++) 은 우리 매핑에서 박스 발 위치 동일이라 불필요.
     */
    private static boolean isOverGroundBlockSolid(AbstractClientPlayer player) {
        Level world = player.level();
        if (world == null) return false;
        AABB bb = player.getBoundingBox();
        int x = Mth.floor(player.getX());
        int z = Mth.floor(player.getZ());
        int y = Mth.floor(bb.minY);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        pos.set(x, y, z);
        BlockState state = world.getBlockState(pos);
        // 1.21.1 isSolid 등가 (feedback_isFullCube_partial_solid_pattern.md):
        //   collision shape 비어있지 않음. air → 비어있음 → false. solid → 비어있지 않음 → true.
        return !state.getCollisionShape(world, pos).isEmpty();
    }

    /**
     * isFalling: 자유 낙하.
     * 원본: SmartMovingModel.setRotationAngles() L531-549 (isFalling 분기).
     *
     * 🔴 distance 입력 1:1 정정 (2026-04-27):
     *   원본 L533 `distance = totalDistance * 0.1F` 의 totalDistance 는
     *   SmartRenderRender L56 `statistics.getTotalDistance(renderPartialTicks)` 호출 결과.
     *   = `total - legYaw * (1.0F - partialTicks)` (SmartStatisticsData L33-36).
     *
     *   - 매 틱 `total += legYaw` 누적 (legYaw = currentSpeed EMA, factor 0.4, 0~1 clamp).
     *   - 매 프레임 partialTicks lerp → 60Hz 부드러움 (사용자 "fade 보간 부드러움" 의 실체).
     *   - 낙하 시작 직후 legYaw 가 0→1 점진 증가 → 휘저음 가속도 자연스러움
     *     (사용자 "팔/다리 휘저어지는 속도 및 가속도" 의 직접 원인).
     *
     *   이전 매핑 `animationProgress * 0.1F` 는 vanilla `entity.age + tickDelta` —
     *   이동 가속도 무관 + 시간 단조 증가 → 가속도 부재 + 정지 시에도 휘저음 지속 (원본과 다름).
     *
     *   정정: `sm.stats.getTotalDistance(partialTicks) * 0.1F` (이미 인프라 존재).
     *
     * 회전 순서: 팔 XZY (setAnglesXZY 헬퍼). 회전식은 원본 L538-548 라인별 1:1.
     *
     * 🔴 swing 처리 (2026-04-27):
     *   vanilla `BipedEntityModel.animateArms` 가 handSwingProgress > 0 시 body.yaw 를
     *   `sin(sqrt(swing) * 2π) * 0.2` 로 흔든다 → 사용자 보고 "공중 낙하 휘두름 시 몸통 움찔움찔".
     *   비행 분기 sm_animateFlying 의 preserve 패턴 차용:
     *   - body.yaw cancel = sm_setAngles 의 anySmState reset 인프라 (isFallingForReset 추가).
     *   - preferred arm 의 setAnglesXZY skip → vanilla swing pitch/yaw/roll 그대로 보존.
     *   - falling 은 setupTransforms X 회전 없음 → preCancelParentX* 불필요.
     */
    private void sm_animateFalling(SmartMovingClientState sm, net.minecraft.client.player.AbstractClientPlayer player) {
        float partialTicks = net.minecraft.client.Minecraft.getInstance()
                .getDeltaTracker().getGameTimeDeltaPartialTick(false);
        float totalDistance = sm.stats.getTotalDistance(partialTicks);
        float distance = totalDistance * 0.1f;

        // 🔴 (2026-04-27) 낙하 시 비행 패턴 — 머리/몸 같이 fade lag.
        //   sm_setupTransforms TAIL 의 fade matrix 가 head 포함 모든 ModelPart 영향.
        //   head.yaw=0 force → vanilla netHeadYaw 무력화 → head world yaw = body world yaw.
        //   head.pitch=0 force → vanilla headPitch 무력화 → 마우스 위아래 시 머리 끄덕임 고정.
        //   사용자 요청 (2026-04-27): "낙하일 때는 비행일때랑 같게 처리" + "마우스 위아래 끄덕임 고정".
        //   비행 분기 (sm_animateFlying) 는 setupTransforms X tilt 보정 위해 head.pitch=-theta/2 set,
        //   낙하는 X tilt 없음 → 단순 0 force.
        head.yRot = 0f;
        head.xRot = 0f;
        head.zRot = 0f;

        // 🔴 fix #186 (2026-05-28): fix #183 revert — fix #177 패러다임 복원.
        //   메모리: falling 은 setupTransforms 부모 R_x 회전 없음 → preCancelParentX 불필요.
        //   preferred arm 만 vanilla swing 보존. swing > 0 시에만 활성.
        float swing = player.getSwingAnimation(1.0F);
        HumanoidArm preferredArm = player.getMainArm();
        boolean preserveRight = swing > 0F && preferredArm == HumanoidArm.RIGHT;
        boolean preserveLeft  = swing > 0F && preferredArm == HumanoidArm.LEFT;

        // 팔 (XZY 순서) — 원본 L535-542.
        float rYaw  = Mth.cos(distance + QUARTER) * EIGHTH;
        float lYaw  = Mth.cos(distance + QUARTER) * EIGHTH;
        float rRoll = Mth.cos(distance) * EIGHTH + QUARTER;
        float lRoll = Mth.cos(distance) * EIGHTH - QUARTER;
        if (!preserveRight) setAnglesXZY(rightArm, 0f, rYaw, rRoll);
        if (!preserveLeft)  setAnglesXZY(leftArm,  0f, lYaw, lRoll);

        // 다리 X (앞뒤 흔들림) — 원본 L544-545.
        rightLeg.xRot = Mth.cos(distance + HALF + QUARTER) * SIXTEENTH + THIRTYTWOTH;
        leftLeg.xRot  = Mth.cos(distance + QUARTER)        * SIXTEENTH + THIRTYTWOTH;
        // 다리 Z (좌우 흔들림) — 원본 L547-548.
        rightLeg.zRot  = Mth.cos(distance) * SIXTEENTH + THIRTYTWOTH;
        leftLeg.zRot   = Mth.cos(distance) * SIXTEENTH - THIRTYTWOTH;
    }

    /**
     * [12-2] Dodge (angle jump) pose, in the air and through the landing recovery.
     * Original: SmartMovingModel.animateAngleJumping() L559-584 (one static pose per direction).
     *
     * Reworked (docs/research_dodge_spam_and_animation.md) so it no longer snaps in, freezes and snaps out:
     *   - limbs blend from the vanilla pose (walk swing included) to the dodge pose by sm.dodgeWeight;
     *   - legs gather toward the top of the hop and extend for the landing (sm.dodgeTuck);
     *   - part of the vanilla sneak pose absorbs the landing (sm.dodgeCrouch);
     *   - direction weights are relative to the rendered body, which keeps its vanilla yaw, so the head
     *     keeps its vanilla (corrected) yaw too instead of being forced to 0.
     *
     * Model space: limb xRot < 0 swings forward, > 0 backward. Outward leg abduction is
     * leftLeg.zRot < 0 / rightLeg.zRot > 0; outward arm raise is leftArm.zRot < 0 / rightArm.zRot > 0.
     */
    private void sm_animateDodge(SmartMovingClientState sm, AbstractClientPlayer player, float animationProgress,
                                 boolean crouching) {
        float w = sm.dodgeWeight;
        float tuck = sm.dodgeTuck;
        float fw = sm.dodgeFw;
        float bk = sm.dodgeBk;
        float lf = sm.dodgeLf;
        float rt = sm.dodgeRt;

        // Legs: takeoff/landing pose plus the mid-air gather.
        //   forward: running lunge — lead (left) leg forward, trailing (right) leg back.
        //   back:    both legs forward, staggered and slightly apart.
        //   side:    lead leg reaches out, trailing leg tucks forward under the body.
        float lLegX = fw * (-0.45f - 0.45f * tuck) + bk * (-0.30f - 0.35f * tuck)
                    + lf * -0.10f + rt * (-0.20f - 0.35f * tuck);
        float rLegX = fw * ( 0.40f + 0.25f * tuck) + bk * (-0.15f - 0.35f * tuck)
                    + lf * (-0.20f - 0.35f * tuck) + rt * -0.10f;
        float lLegZ = bk * -0.10f + lf * (-0.35f - 0.15f * tuck) + rt * (-0.05f + 0.10f * tuck);
        float rLegZ = bk *  0.10f + lf * ( 0.05f - 0.10f * tuck) + rt * ( 0.35f + 0.15f * tuck);
        blendLimb(leftLeg,  lLegX, lLegZ, w);
        blendLimb(rightLeg, rLegX, rLegZ, w);

        // Arms counter-balance and flare a little more at the top of the hop.
        //   forward: counter-swing; back: both reach forward; side: trailing arm flung out.
        // The targets include vanilla's idle arm bob (AnimationUtils.bobModelPart) so it keeps going.
        float flare = 0.8f + 0.2f * tuck;
        float bobZ = Mth.cos(animationProgress * 0.09F) * 0.05F + 0.05F;
        float bobX = Mth.sin(animationProgress * 0.067F) * 0.05F;
        float lArmX = (fw *  0.45f + bk * -0.70f + lf * -0.15f + rt * -0.25f) * flare - bobX;
        float rArmX = (fw * -0.55f + bk * -0.70f + lf * -0.25f + rt * -0.15f) * flare + bobX;
        float lArmZ = (fw * -0.10f + bk * -0.20f + lf * -0.30f + rt * -0.75f) * flare - bobZ;
        float rArmZ = (fw *  0.10f + bk *  0.20f + lf *  0.75f + rt *  0.30f) * flare + bobZ;
        // Leave the swinging arm to the attack animation (same rule as sm_animateFalling).
        boolean swinging = player.getSwingAnimation(1.0F) > 0F;
        HumanoidArm mainArm = player.getMainArm();
        if (!swinging || mainArm != HumanoidArm.LEFT)  blendLimb(leftArm,  lArmX, lArmZ, w);
        if (!swinging || mainArm != HumanoidArm.RIGHT) blendLimb(rightArm, rArmX, rArmZ, w);

        // The renderer leans the whole body about the hips; keep the head looking where the player looks.
        head.xRot -= sm.dodgePitch * 0.6f;
        head.zRot -= sm.dodgeRoll * 0.5f;

        // Landing: part of the vanilla sneak pose (HumanoidModel.setupAnim isCrouching); the renderer
        // drops the model to match. Skipped while really sneaking, which already has the full pose.
        float crouch = crouching ? 0f : sm.dodgeCrouch;
        if (crouch > 0f) {
            body.xRot += 0.5f * crouch;
            head.y += 4.2f * crouch;
            body.y += 3.2f * crouch;
            leftArm.y += 3.2f * crouch;
            rightArm.y += 3.2f * crouch;
            leftArm.xRot += 0.4f * crouch;
            rightArm.xRot += 0.4f * crouch;
            leftLeg.z += 4.0f * crouch;
            rightLeg.z += 4.0f * crouch;
        }
    }

    /**
     * Chain climb (docs/research_movement_prototypes.md): hands overhead gripping the chain hand over
     * hand, knees bent and turned in around it. The cycle is driven by the climbed distance (like the
     * ladder pose) so it stops when the player hangs still and runs backwards when descending.
     */
    private void sm_animateChainClimbing(SmartMovingClientState sm, float headPitch) {
        float partialTicks = SmartMovingClientState.globalCachedTickDelta;
        float verticalSpeed = Math.min(0.5f, Math.abs(sm.stats.getCurrentVerticalSpeed(partialTicks)));
        float phase = sm.stats.getTotalVerticalDistance(partialTicks) * 3.0f;
        float reach = 0.30f + verticalSpeed * 0.9f;   // arm alternation grows with climb speed

        setArmScales(rightArm, leftArm, 1f, 1f);
        setLegScales(rightLeg, leftLeg, 1f, 1f);
        rightLeg.z = 0f;
        leftLeg.z  = 0f;
        rightLeg.x = -2f;
        leftLeg.x  =  2f;

        // Arms straight up, hands meeting over the head, alternating hand over hand.
        float up = -(float) Math.PI + 0.35f;
        rightArm.xRot = up + reach * Mth.cos(phase);
        leftArm.xRot  = up - reach * Mth.cos(phase);
        rightArm.yRot = 0f;
        leftArm.yRot  = 0f;
        rightArm.zRot =  0.28f;
        leftArm.zRot  = -0.28f;

        // Legs wrapped around the chain: knees up and turned in, opposite to the arms.
        rightLeg.xRot = -0.95f - 0.30f * Mth.cos(phase);
        leftLeg.xRot  = -0.95f + 0.30f * Mth.cos(phase);
        rightLeg.zRot = -0.32f;
        leftLeg.zRot  =  0.32f;

        // Look up the chain a little, body leaning back off it.
        head.yRot = 0f;
        head.xRot = headPitch * DEG_TO_RAD - 0.25f;
        body.xRot = -0.12f;
    }

    /** Moves a limb's xRot/zRot from their current (vanilla) values toward the dodge pose by {@code weight}. */
    private static void blendLimb(ModelPart part, float xRot, float zRot, float weight) {
        part.xRot += (xRot - part.xRot) * weight;
        part.zRot += (zRot - part.zRot) * weight;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 내부 유틸리티
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * [12-7] 발 아래 지면까지의 거리 계산 (최대 5블록).
     *
     * 원본: SmartMovingBase.getOverGroundHeight(5D) 클라이언트 경로
     *   = sp.boundingBox.minY - getMaxPlayerSolidBetween(minY - 5D, minY, 0)
     *
     * 1.21.1: 플레이어 중심 열(column)을 최대 5블록 아래까지 스캔하여
     *         첫 고체 블록의 top surface Y를 구하고, playerY와의 차를 반환한다.
     * 반환값 범위: 0.0F (지면 위) ~ 5.0F (5블록 아래까지 고체 없음).
     */
    private static float computeSmallOverGroundHeight(net.minecraft.client.player.AbstractClientPlayer player, Level world) {
        // 🔴 사용자 보고 fix (2026-05-04 — "다리가 처음부터 모임"):
        //   원본 SmartMovingBase.getOverGroundHeight: bb.minY - getMaxPlayerSolidBetween(minY-5, minY, 0).
        //   getMaxPlayerSolidBetween = 박스 horizontal area (= 0.6 x 0.6) 안 max solid Y.
        //   이전 매핑은 single column (player.x/z floor) 만 → 등반 블록이 column 밖이면 측정 못 함.
        //   fix: SmartMovingClientState.getMaxPlayerSolidBetween 사용 (= horizontal area 정확).
        net.minecraft.world.phys.AABB bb = player.getBoundingBox();
        double minY = bb.minY;
        double solidTop = SmartMovingClientState.getMaxPlayerSolidBetween(player, minY - 5D, minY, 0);
        return (float) Math.max(0D, minY - solidTop);
    }

    /**
     * 원본 SmartMovingModel.setArmScales(rightScale, leftScale) 1:1 이식.
     *
     * 원본 본체 (SmartMovingModel.java L948-L963):
     *   if (scaleArmType == Scale)         { rightArm.scaleY = rs; leftArm.scaleY = ls; }
     *   else if (scaleArmType == NoScaleEnd) { rightArm.offsetY -= (1F - rs) * 0.5F; ... }
     *
     * 메인 모델 = `Scale` 타입 (SmartMovingRender.java L88-L89: modelBipedMain.scaleArmType=Scale)
     *   → 1.21.1 PlayerEntityModel 도 메인 플레이어 모델이므로 Scale 분기 본체만 적용.
     *
     * 근사 이식 — 원본과 차이: scaleArmType=NoScaleEnd 분기 (갑옷 흉갑) 미이식.
     *   ModelPart 에 offsetY 필드 부재 + 갑옷 레이어는 별도 ArmorFeatureRenderer 처리.
     *   → 갑옷 yScale 보정은 본 포커스 #1 (메인 애니메이션) 범위 외 (§7 등재 / 잔여).
     */
    private static void setArmScales(ModelPart rightArm, ModelPart leftArm, float rightScale, float leftScale) {
        rightArm.yScale = rightScale;
        leftArm.yScale  = leftScale;
    }

    /**
     * 원본 SmartMovingModel.setLegScales(rightScale, leftScale) 1:1 이식.
     * 원본 본체 (SmartMovingModel.java L972-L987) — setArmScales 와 동일 패턴, 대상이 다리.
     * 메인 모델 = Scale (SmartMovingRender.java L89: modelBipedMain.scaleLegType=Scale).
     */
    private static void setLegScales(ModelPart rightLeg, ModelPart leftLeg, float rightScale, float leftScale) {
        rightLeg.yScale = rightScale;
        leftLeg.yScale  = leftScale;
    }

    /**
     * 원본 YZX GL 순서(glRotate Y → Z → X) → ModelPart pitch/yaw/roll 변환.
     * JOML: qY*qZ*qX → getEulerAnglesZYX → (e.x=pitch, e.y=yaw, e.z=roll).
     *
     * ⚠️ BUG 가능성 (메모리 `feedback_zxy_zyx_rotation_order.md` 의 BUG-30 패턴 동일):
     *   원본 ModelRotationRenderer YZX 분기 (L145/L151/L154) GL call 순서 = X → Z → Y.
     *   GL post-multiply: vertex 적용 = call 역순 = Y → Z → X.
     *   현재 q = q_Y*q_Z*q_X → vertex 적용 X → Z → Y (원본 반대).
     *   사용처: 그랩 클라이밍 (`project_grab_climbing_complete.md` 16라운드 정착) — 함부로 수정 금지.
     *   crawl 분기는 정확 매핑 위해 `setAnglesYZX_v2` 별도 사용.
     */
    private static void setAnglesYZX(ModelPart part, float pitch, float yaw, float roll) {
        Quaternionf q = new Quaternionf()
                .rotationY(yaw)
                .mul(new Quaternionf().rotationZ(roll))
                .mul(new Quaternionf().rotationX(pitch));
        Vector3f e = q.getEulerAnglesZYX(new Vector3f());
        part.xRot = e.x;
        part.yRot   = e.y;
        part.zRot  = e.z;
    }

    /**
     * 원본 YZX rotationOrder 의 정확 1:1 매핑 — vertex 적용 순서 Y → Z → X.
     *
     * 원본 ModelRotationRenderer YZX (L145, L151, L154) GL call 순서 = X → Z → Y.
     * GL post-multiply: vertex 적용 = call 역순 = Y → Z → X.
     * JOML: q = q_X * q_Z * q_Y → vertex 적용 right-most first = Y → Z → X. ✓
     *
     * 사용자 보고 fix (2026-05-03 — "엎드린 자세 팔이 하늘로 들림"):
     *   crawl arm yaw=±90° (= 큰 회전) 시 setAnglesYZX (= q_Y*q_Z*q_X) 의 vertex 적용 X → Z → Y
     *   가 원본 vertex 적용 Y → Z → X 와 정반대 → arm 위치 잘못. 메모리 BUG-30 (setAnglesXZY)
     *   와 동일 패턴. crawl 만 별도 함수로 정확 매핑 — 다른 사용처 (climbing/sliding) 영향 없음.
     */
    private static void setAnglesYZX_v2(ModelPart part, float pitch, float yaw, float roll) {
        Quaternionf q = new Quaternionf()
                .rotationX(pitch)
                .mul(new Quaternionf().rotationZ(roll))
                .mul(new Quaternionf().rotationY(yaw));
        Vector3f e = q.getEulerAnglesZYX(new Vector3f());
        part.xRot = e.x;
        part.yRot   = e.y;
        part.zRot  = e.z;
    }

    /**
     * 🔴 (2026-05-21, swim 팔 회전 궤적 BUG fix) 표준 ZYX intrinsic Euler 분해.
     *
     * JOML 1.10.5 의 getEulerAnglesZYX 가 e.x 분모를 `0.5 - x² + y²` (비표준, BUG) 로
     * 계산 → 큰 sawtooth pitch (= ±π) + 큰 roll (= Q+E = 135°) 영역에서 ModelPart.rotateZYX
     * 적용 결과 quaternion ≠ 우리 의도 q (= qX*qZ*qY) → "회전 궤적 이상" 시각 BUG.
     *
     * 검증 (disassembly):
     *   e.x = atan2(y*z + w*x, 0.5 - x*x + y*y)   ← 분모 +y² BUG
     *   e.y = safeAsin(2*(w*y - x*z))             ← 표준
     *   e.z = atan2(x*y + w*z, 0.5 - y*y - z*z)   ← 표준
     *
     * 표준 ZYX intrinsic 분해 식 (= R = R_z(γ) * R_y(β) * R_x(α) 형태):
     *   α (pitch X) = atan2(2*(w*x + y*z), 1 - 2*(x² + y²))
     *   β (yaw Y)   = asin(2*(w*y - x*z))
     *   γ (roll Z)  = atan2(2*(w*z + x*y), 1 - 2*(y² + z²))
     *
     * vanilla `ModelPart.rotate` 의 적용 식 = `new Quaternionf().rotationZYX(roll, yaw, pitch)`
     * = R = R_z(roll) * R_y(yaw) * R_x(pitch). = ZYX intrinsic. ✓
     *
     * 사용처 한정 — swim 분기 arm 만 (= 큰 sawtooth + 큰 roll 영역). 다른 분기는
     * setAnglesYZX_v2 그대로 (= JOML BUG 결과가 정착된 자세 — 회귀 차단).
     */
    private static void setAnglesYZX_standard(ModelPart part, float pitch, float yaw, float roll) {
        Quaternionf q = new Quaternionf()
                .rotationX(pitch)
                .mul(new Quaternionf().rotationZ(roll))
                .mul(new Quaternionf().rotationY(yaw));
        float x = q.x, y = q.y, z = q.z, w = q.w;
        // 표준 ZYX intrinsic Euler 분해.
        float alpha = (float) Math.atan2(2.0 * (w * x + y * z), 1.0 - 2.0 * (x * x + y * y));
        float beta  = (float) Math.asin(Math.max(-1.0, Math.min(1.0, 2.0 * (w * y - x * z))));
        float gamma = (float) Math.atan2(2.0 * (w * z + x * y), 1.0 - 2.0 * (y * y + z * z));
        part.xRot = alpha;
        part.yRot   = beta;
        part.zRot  = gamma;
    }

    /**
     * 🔴 (2026-05-21, fix #126) 원본 YXZ rotationOrder 의 표준 분해 매핑.
     *
     * setAnglesYXZ (= JOML getEulerAnglesZYX 사용) 의 e.x 분모 `0.5 - x² + y²` BUG 가
     * 큰 yaw (= 2 × sway, ±115° peak) + pitch (= -EIGHTH × sSF) 영역에서 cycle 안 다른
     * quadrant 으로 분해 → frame 간 discontinuity 시각 BUG. 사용자 보고 "swim 시 머리가
     * 돌아가다가 몸쪽으로 꺾임" 정확 매치.
     *
     * 표준 ZYX intrinsic 분해 식 (= R = R_z(γ) × R_y(β) × R_x(α) 형태):
     *   α (pitch X) = atan2(2*(w*x + y*z), 1 - 2*(x² + y²))
     *   β (yaw Y)   = asin(2*(w*y - x*z))
     *   γ (roll Z)  = atan2(2*(w*z + x*y), 1 - 2*(y² + z²))
     *
     * 사용처 한정 — swim head 만 (= 큰 yaw 영역). 다른 분기 (sliding body 등) 은 setAnglesYXZ
     * 그대로 유지 (= 정착 자세 회귀 차단).
     */
    private static void setAnglesYXZ_standard(ModelPart part, float pitch, float yaw, float roll) {
        Quaternionf q = new Quaternionf()
                .rotationZ(roll)
                .mul(new Quaternionf().rotationX(pitch))
                .mul(new Quaternionf().rotationY(yaw));
        float x = q.x, y = q.y, z = q.z, w = q.w;
        float alpha = (float) Math.atan2(2.0 * (w * x + y * z), 1.0 - 2.0 * (x * x + y * y));
        float beta  = (float) Math.asin(Math.max(-1.0, Math.min(1.0, 2.0 * (w * y - x * z))));
        float gamma = (float) Math.atan2(2.0 * (w * z + x * y), 1.0 - 2.0 * (y * y + z * z));
        part.xRot = alpha;
        part.yRot   = beta;
        part.zRot  = gamma;
    }

    /**
     * 🔴 (2026-05-21, fix #127) swim head 의 원본 합성 visual 정확 매핑.
     *
     * 원본 model tree: head 가 bipedNeck 자식 → bipedBreast 자식.
     *   M_head = R_z(0) × R_x(pitch) × R_y(sway_head)  (YXZ vertex order Y → X → Z).
     *   M_neck = identity.
     *   M_breast = R_y(sway_breast)                    (XYZ vertex order, no pitch/roll).
     *   = visual 합성 M = M_breast × M_neck × M_head = R_y(sway) × R_x(pitch) × R_y(sway).
     *
     * fix #124 의 setAnglesYXZ_standard(head, pitch, 2*sway, 0) 가 R_x(pitch) × R_y(2×sway)
     * 형태로 매핑 → pitch=0 시 R_y(2×sway) 동일하지만, pitch ≠ 0 시 두 R_y 사이에 R_x 끼어
     * 있는 nested form 과 차이 → cycle 안 angular velocity 빨라짐 (= 사용자 "머리 회전 빠른 느낌").
     *
     * 정확 매핑: q = R_y(sway_breast) × R_x(pitch) × R_y(sway_head) 합성 → 표준 ZYX 분해.
     */
    /**
     * 🔴 (2026-05-21, fix #140) swim arm 의 원본 visual 정확 매핑.
     * 🔴 (2026-05-21, fix #145) fix #144 의 helper 식 변경 revert (= fix #140 식이 원본 정확).
     *
     * 원본 ModelRotationRenderer.java L145-L155 의 YZX rotation order GL call 순서:
     *   L146 (YZX 매치): GL.rotate(X).
     *   L152 (YZX 매치): GL.rotate(Z).
     *   L155 (YZX 매치): GL.rotate(Y).
     *   = GL call 순서 X → Z → Y.
     *   GL post-multiply: matrix = R_x × R_z × R_y. vertex 적용 = matrix × vertex.
     *
     * 원본 model tree: arm 이 bipedBreast 자식. bipedBreast.rotateAngleY = sway.
     *   bipedArm (YZX, X=pitch, Y=0, Z=roll) 의 R = R_x(pitch) × R_z(roll) × R_y(0) = R_x × R_z.
     *   부모 bipedBreast 의 R_y(sway) 적용:
     *   = arm 의 final R = R_y(sway) × R_x(pitch) × R_z(roll). (matrix form, 좌측이 outermost)
     *   = vertex 적용 vertex → R_z → R_x → R_y.
     *
     * fix #122 (arm.yaw = bodySway) 매핑이 잘못 (R_y middle position).
     * fix #137 swap 가 fix #122 의 visual 보정 (= 좌우 flip).
     * fix #140 helper 추가 시 swap 유지 — 잘못. fix #144 가 모두 revert + helper 식 변경.
     * fix #145 = fix #144 의 helper 식 변경 revert (= fix #140 식 정확). swap revert 유지.
     *
     * q = R_y(sway) × R_x(pitch) × R_z(roll). 표준 ZYX 분해.
     */
    private static void setAnglesYXZ_breastSwim(ModelPart part, float swayBreast, float pitch, float roll) {
        Quaternionf q = new Quaternionf()
                .rotationY(swayBreast)
                .mul(new Quaternionf().rotationX(pitch))
                .mul(new Quaternionf().rotationZ(roll));
        float x = q.x, y = q.y, z = q.z, w = q.w;
        float alpha = (float) Math.atan2(2.0 * (w * x + y * z), 1.0 - 2.0 * (x * x + y * y));
        float beta  = (float) Math.asin(Math.max(-1.0, Math.min(1.0, 2.0 * (w * y - x * z))));
        float gamma = (float) Math.atan2(2.0 * (w * z + x * y), 1.0 - 2.0 * (y * y + z * z));
        part.xRot = alpha;
        part.yRot   = beta;
        part.zRot  = gamma;
    }

    private static void setAnglesRyRxRy_standard(ModelPart part, float swayBreast, float pitch, float swayHead) {
        Quaternionf q = new Quaternionf()
                .rotationY(swayBreast)
                .mul(new Quaternionf().rotationX(pitch))
                .mul(new Quaternionf().rotationY(swayHead));
        float x = q.x, y = q.y, z = q.z, w = q.w;
        float alpha = (float) Math.atan2(2.0 * (w * x + y * z), 1.0 - 2.0 * (x * x + y * y));
        float beta  = (float) Math.asin(Math.max(-1.0, Math.min(1.0, 2.0 * (w * y - x * z))));
        float gamma = (float) Math.atan2(2.0 * (w * z + x * y), 1.0 - 2.0 * (y * y + z * z));
        part.xRot = alpha;
        part.yRot   = beta;
        part.zRot  = gamma;
    }

    /**
     * 원본 YXZ rotationOrder → ModelPart pitch/yaw/roll 변환.
     *
     * 🔴 BUG-30 정정 확장 (Flying Phase / 세션 42):
     *   원본 ModelRotationRenderer.rotate YXZ 분기 (L143/L146/L155) GL call 순서 = Z, X, Y.
     *   GL post-multiply 규칙: vertex 적용 순서 = call 의 역순 = Y, X, Z.
     *   JOML quaternion 곱 (q = qA * qB * qC) 의 vertex 적용 = right-most 부터 →
     *     vertex 적용 순서 Y, X, Z 매칭 = qZ * qX * qY.
     *
     *   이전 R-17 매핑 = qY * qX * qZ → vertex 적용 Z, X, Y (원본과 반대 순서).
     *   setAnglesXZY (세션 41) 에서 발견된 동일 패턴 오류 — YXZ/ZXY 헬퍼도 같은 reversal.
     *   사용처 (isSwim head, isSlide body) 에서 큰 pitch/roll 시 가시 차이 가능.
     *
     *   정정: qZ * qX * qY 순서로 변경 → 원본 vertex 적용 Y → X → Z 1:1 매칭.
     *
     * 사용처: isSwim head, isSlide body.
     */
    private static void setAnglesYXZ(ModelPart part, float pitch, float yaw, float roll) {
        Quaternionf q = new Quaternionf()
                .rotationZ(roll)
                .mul(new Quaternionf().rotationX(pitch))
                .mul(new Quaternionf().rotationY(yaw));
        Vector3f e = q.getEulerAnglesZYX(new Vector3f());
        part.xRot = e.x;
        part.yRot   = e.y;
        part.zRot  = e.z;
    }

    /**
     * 원본 XZY rotationOrder → ModelPart pitch/yaw/roll 변환.
     *
     * 🔴 BUG-30 정정 (Flying Phase / 세션 41):
     *   원본 ModelRotationRenderer.rotate XZY 분기 (L148/L151/L157) GL call 순서 = Y, Z, X.
     *   GL post-multiply 규칙: vertex 적용 순서 = call 의 역순 = X, Z, Y.
     *   JOML quaternion 곱 (q = qA * qB * qC): vertex 가 right-most 부터 적용 →
     *     vec → qC → qB → qA. 즉 vertex 적용 순서 X, Z, Y 매칭 = qY * qZ * qX.
     *
     *   이전 R-17 매핑 = qX * qZ * qY → vertex 적용 Y, Z, X (원본과 반대 순서).
     *   small angle 시 차이 미세하지만 큰 roll (π/2 = 90°) 비행 자세에서 가시화 — 사용자
     *   보고 BUG-30: "팔 방향과 같은 축으로 스크류 회전" (= 원본 X 회전이 아닌 잘못된 회전).
     *
     *   정정: qY * qZ * qX 순서로 변경 → 원본 vertex 적용 X → Z → Y 1:1 매칭.
     *
     * 사용처: isFlying arm, isFalling arm.
     */
    private static void setAnglesXZY(ModelPart part, float pitch, float yaw, float roll) {
        Quaternionf q = new Quaternionf()
                .rotationY(yaw)
                .mul(new Quaternionf().rotationZ(roll))
                .mul(new Quaternionf().rotationX(pitch));
        Vector3f e = q.getEulerAnglesZYX(new Vector3f());
        part.xRot = e.x;
        part.yRot   = e.y;
        part.zRot  = e.z;
    }

    /**
     * 부모 setupTransforms POSITIVE_X(-theta) 회전을 자식 ModelPart 단계에서 정확히 cancel.
     *
     * vanilla ModelPart.rotate: matrices.multiply(Quaternionf.rotationZYX(roll, yaw, pitch))
     *   → 자식 회전 q_arm = R_z(roll) * R_y(yaw) * R_x(pitch) (vertex 에 X 가 가장 먼저 적용).
     * 부모 R_x(-theta) 적용 후 자식 q_arm → 결과 R_x(-theta) * q_arm * v.
     * 원하는 효과 = q_arm * v (부모 X 회전 무시) → 새 자식 q_new = R_x(theta) * q_arm.
     * ZYX Euler 분해 → (pitch, yaw, roll).
     *
     * 단순 `pitch += theta` 는 회전 비교환성으로 yaw/roll 이 0 이 아닐 때 부정확
     * (vanilla animateArms 의 roll = sin(swing*π)*-0.4 ~ -23° 와 합성 시 오차).
     *
     * 사용처: 비행 swing 진행 중 preferred arm — 직립 자세로 vanilla swing 효과 잔존.
     */
    private static void preCancelParentXRotation(ModelPart part, float theta) {
        // 🔴 (세션 65q): 부호 반전 + scale 보정.
        //   vanilla render: setupTransforms → scale(-1,-1,1) → ModelPart.rotate.
        //   scale Y 부호 반전 → ModelPart R_x(theta) = world R_x(-theta).
        //   부모 world R_x(-θ) cancel 위해 ModelPart R_x(-theta) 적용 (= world R_x(+θ)).
        Quaternionf qOrig = new Quaternionf().rotationZYX(part.zRot, part.yRot, part.xRot);
        Quaternionf qNew = new Quaternionf().rotationX(-theta).mul(qOrig);
        Vector3f e = qNew.getEulerAnglesZYX(new Vector3f());
        part.xRot = e.x;
        part.yRot   = e.y;
        part.zRot  = e.z;
    }

    /**
     * arm pivot 위치를 부모 X 회전 cancel — 회전 중심 ModelPart (0, 0, 0).
     *
     * vanilla render 흐름: setupTransforms(R_x(-θ) at world (0,1.5,0)) → scale(-1,-1,1) →
     * translate(0, -1.501, 0) → ModelPart.rotate.
     * 회전 중심 (0, 1.5, 0) world 가 ModelPart 좌표계에서 ≈ (0, 0, 0) (scale + translate 보정).
     * scale Y 부호 반전 → ModelPart R_x(-theta) = world R_x(+theta) → 부모 cancel.
     */
    private static void preCancelParentXPivot(ModelPart part, float theta) {
        float py = part.y;
        float pz = part.z;
        float cos = Mth.cos(theta);
        float sin = Mth.sin(theta);
        part.y = py * cos + pz * sin;
        part.z = -py * sin + pz * cos;
    }

    /**
     * setupTransforms 의 fade 보간 (lerpFadeAngle) 과 동일한 계산.
     *
     * sm_setAngles 가 setupTransforms 보다 먼저 호출되므로, 같은 frame 의 fade 결과를
     * setAngles 시점에 직접 계산해야 정확한 X cancel 이 가능. setupTransforms 도 같은
     * input (smOuterTiltX_prev, smOuterFade_prevTime) 으로 같은 결과 산출 후 prev 갱신.
     *
     * 원본 ModelRotationRenderer.GetIntermediateAngle (L347-L364) 1:1.
     */
    private static float lerpFadeAngle(float prev, float target, float prevTime, float currentTime) {
        if (prevTime < -100f) return target;
        float deltaTime = currentTime - prevTime;
        if (deltaTime > 2f || deltaTime < 0f) return target;
        if (target == prev) return target;
        final float W = (float) (2.0 * Math.PI);
        final float H = (float) Math.PI;
        float p = prev, s = target;
        while (p >= W) p -= W;
        while (p < 0f) p += W;
        while (s >= W) s -= W;
        while (s < 0f) s += W;
        if (s > p && (s - p) > H) p += W;
        if (s < p && (p - s) > H) s += W;
        return p + (s - p) * deltaTime * 0.2f;
    }

    /**
     * 원본 ZXY rotationOrder → ModelPart pitch/yaw/roll 변환.
     *
     * 🔴 BUG-30 정정 확장 (Flying Phase / 세션 42):
     *   원본 ModelRotationRenderer.rotate ZXY 분기 (L140/L146/L152) GL call 순서 = Y, X, Z.
     *   GL post-multiply 규칙: vertex 적용 순서 = call 의 역순 = Z, X, Y.
     *   JOML 의 vertex 적용 = right-most 부터 → vertex 적용 순서 Z, X, Y 매칭 = qY * qX * qZ.
     *
     *   이전 R-17 매핑 = qZ * qX * qY → vertex 적용 Y, X, Z (원본과 반대 순서).
     *   setAnglesXZY/YXZ 동일 패턴 오류 — animateAngleJumping 다리 작은 각도라 차이 미세.
     *
     *   정정: qY * qX * qZ 순서로 변경 → 원본 vertex 적용 Z → X → Y 1:1 매칭.
     *
     * 사용처: animateAngleJumping 다리.
     */
    private static void setAnglesZXY(ModelPart part, float pitch, float yaw, float roll) {
        Quaternionf q = new Quaternionf()
                .rotationY(yaw)
                .mul(new Quaternionf().rotationX(pitch))
                .mul(new Quaternionf().rotationZ(roll));
        Vector3f e = q.getEulerAnglesZYX(new Vector3f());
        part.xRot = e.x;
        part.yRot   = e.y;
        part.zRot  = e.z;
    }

    /**
     * SmartMovingModel.Factor() 이식 — 선형 보간 팩터.
     * x0~x1 범위에서 0→1(또는 1→0) 보간. 범위 밖 클램프.
     */
    private static float smFactor(float x, float x0, float x1) {
        if (x0 > x1) {
            if (x <= x1) return 1f;
            if (x >= x0) return 0f;
            return (x0 - x) / (x0 - x1);
        } else {
            if (x >= x1) return 1f;
            if (x <= x0) return 0f;
            return (x - x0) / (x1 - x0);
        }
    }
}
