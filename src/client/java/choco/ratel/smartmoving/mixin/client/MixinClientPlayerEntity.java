package choco.ratel.smartmoving.mixin.client;

import choco.ratel.smartmoving.client.SmartMovingClientState;
import choco.ratel.smartmoving.client.input.SmartMovingKeys;
import choco.ratel.smartmoving.config.SmartMovingConfig;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Input;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 4-2: Unconditional tickEssential() call Mixin.
 *
 * Always call tickEssential() in ClientPlayerEntity.tickMovement() HEAD
 * regardless of SM isActive status.
 *
 * Original: SmartMovingPlayerBase.updateEntityActionState()
 *       → moving.tickEssential()  ← always called without isActive() check
 *       → isActive() ? moving.updateEntityActionState(false) : localUpdateEntityActionState()
 */
@Mixin(LocalPlayer.class)
public abstract class MixinClientPlayerEntity {

    /**
     * 🔴 Block vanilla automatic yaw rotation when re-entering flight (user report fix):
     *   Remaining user motion (falling large motionY) + vanilla processing inside
     *   LivingEntity.tickMovement on flight re-entry applies yaw correction based on motion direction.
     *   Result: Forward (W) → +180°, Back (S) → ?, Left/Right (A/D) → ±90°.
     *
     *   Solution:
     *   1. Save yaw + abilFly at tickMovement HEAD time.
     *   2. TAIL time detect flying entry edge (HEAD abilFly=false → TAIL abilFly=true)
     *      → restore yaw only at that moment.
     *   3. Threshold 30° (catches left/right ±90° rotation cases too).
     *
     *   Mouse input is separate method (changeLookDirection) so yaw change inside tick is
     *   only vanilla automatic processing → can safely restore.
     */
    @org.spongepowered.asm.mixin.Unique
    private float sm_yawAtTickStart;
    @org.spongepowered.asm.mixin.Unique
    private boolean sm_abilFlyAtTickStart;

    @Inject(method = "aiStep", at = @At("HEAD"))
    private void sm_tickMovement(CallbackInfo ci) {
        LocalPlayer player = (LocalPlayer)(Object)this;
        sm_yawAtTickStart = player.getYRot();
        sm_abilFlyAtTickStart = player.getAbilities().flying;
        SmartMovingClientState.get(player).tickEssential(player);
    }

    /**
     * 🔴 (Session 145 BUG-sneak-speed): vanilla 1.21.1 `KeyboardInput.tick(slowDown, factor)` when
     *   `slowDown=true` applies movementForward/Sideways *= **PLAYER_SNEAKING_SPEED** (=**0.3**)
     *   multiplication. `slowDown` decider = `ClientPlayerEntity.shouldSlowDown()` =
     *   `isInSneakingPose() || isCrawling()`.
     *
     * SM's `getSlowInputSpeedFactor` also adds sneak/crawl time sneakFactor (0.3) / crawlFactor (0.15)
     * multiplication → vanilla 0.3 × SM 0.3 = **0.09** double slowdown BUG. About 3.3x
     * difference when SM active/inactive (user report).
     *
     * Fix: When SM active, force vanilla `shouldSlowDown()` result to false → block vanilla movement multiply.
     * Apply only SM's sneakFactor/crawlFactor → 0.3/0.15 base. When SM inactive, inject
     * passes → vanilla works correctly (0.3) + SM guard auto-inactive → 0.3 base. Same effect.
     */
    @Inject(method = "isMovingSlowly", at = @At("HEAD"), cancellable = true)
    private void sm_blockVanillaSlowDown(org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Boolean> cir) {
        if (!SmartMovingConfig.Config.enabled) return;
        cir.setReturnValue(false);
    }

    /**
     * 🔴 (Session 145 BUG-edge): vanilla `ClientPlayerEntity.isSneaking()` override
     *   directly checks `input.sneaking` — bypasses Entity.isSneaking() DataTracker flag.
     *   Our MixinEntityClient.sm_isSneaking inject (targeting Entity.isSneaking)
     *   is bypassed in ClientPlayerEntity → shift pressed always gives isSneaking()=true →
     *   `clipAtLedge()=true` → vanilla edge protection activates → can't fall while crouched.
     *
     * Apply 1:1 same expression from original SmartMovingSelf.isSneaking() L3226-L3232.
     *   crawlOverEdge=true (default) + isCrawling all terms become false → return false →
     *   edge protection doesn't activate → falls.
     */
    @Inject(method = "isShiftKeyDown", at = @At("HEAD"), cancellable = true)
    private void sm_isSneaking_ClientPlayer(org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Boolean> cir) {
        LocalPlayer player = (LocalPlayer)(Object)this;
        if (!SmartMovingConfig.Config.enabled || player.isPassenger()) return;
        SmartMovingClientState sm = SmartMovingClientState.get(player);
        if (sm.forceIsSneaking != null) { cir.setReturnValue(sm.forceIsSneaking); return; }
        SmartMovingConfig cfg = SmartMovingConfig.Config;

        // 🔴 fix #75 (BUG #2): weeping/twisting vines 위 + sneak 시 vanilla 위임.
        //   상세는 MixinEntityClient.sm_isSneaking 주석 참조.
        net.minecraft.world.level.block.Block _blockAtPos = player.level()
                .getBlockState(player.blockPosition()).getBlock();
        if (_blockAtPos == net.minecraft.world.level.block.Blocks.WEEPING_VINES
                || _blockAtPos == net.minecraft.world.level.block.Blocks.WEEPING_VINES_PLANT
                || _blockAtPos == net.minecraft.world.level.block.Blocks.TWISTING_VINES
                || _blockAtPos == net.minecraft.world.level.block.Blocks.TWISTING_VINES_PLANT) {
            return;  // override skip → vanilla isSneaking 그대로
        }

        // 🔴 fix #83 (2026-05-12): 슬라이딩 시 isSneaking()=true 강제. 자세한 사유 = MixinEntityClient 동일 inject.
        boolean result = (sm.isSlow && player.onGround())
                || (!cfg.sneak && sm.wouldIsSneaking && sm.jumpCharge > 0)
                || (!cfg.crawlOverEdge && sm.isCrawling && !sm.isClimbing)
                || sm.isSliding;
        cir.setReturnValue(result);
    }

    /**
     * 🔴 (Session 145 BUG-jump): 1:1 mapping of blocking vanilla jump when SM states like crawl/slide.
     *
     * Original SmartMovingSelf L2366-L2370 `setIsJumpingField(...)` guard:
     *   movementInput.jump && !isCrawling && !isSliding
     *   && !(headJumpEnabled && grab && sprint)
     *   && !(jumpChargeEnabled && wouldIsSneaking && onGround && isStanding)
     *   && !blockJumpTillButtonRelease
     *
     * 1.21.1 vanilla flow:
     *   Inside ClientPlayerEntity.tickNewAi() `this.jumping = this.input.jumping` (offset 38-41).
     *   Existing sm_jumpingFilter (LivingEntity.tickMovement HEAD) executes before tickNewAi →
     *   after applying guard vanilla sets again with input.jumping → negates. Direct cause of jump fire BUG.
     *
     * Fix: ClientPlayerEntity.tickNewAi TAIL inject — after vanilla `this.jumping = input.jumping`
     *      apply our guard to force set false.
     */
    @Inject(method = "applyInput", at = @At("TAIL"))
    private void sm_jumpingFilter_tickNewAi(CallbackInfo ci) {
        LocalPlayer player = (LocalPlayer)(Object)this;
        SmartMovingConfig cfg = SmartMovingConfig.Config;
        if (!cfg.enabled) return;
        SmartMovingClientState sm = SmartMovingClientState.get(player);

        // Phase E BUG-3 1:1 correction: Negation of original L2367-L2370 setIsJumpingField 5-AND expression (= setJumping(false) condition).
        //   Original expression (positive): movementInput.jump && !isCrawling && !isSliding
        //                && !(headJumpEnabled && grab && sprint)
        //                && !(jumpChargeEnabled && wouldIsSneaking && onGround && isStanding)
        //                && !blockJumpTillButtonRelease
        //   Negation (= force jumping false): isCrawling || isSliding
        //                || (headJumpEnabled && grab && sprint)         ★ Block from charge start possible time
        //                || (jumpChargeEnabled && wouldIsSneaking && onGround && isStanding)  ★ Same
        //                || blockJumpTillButtonRelease
        //   Existing simplification (isHeadJumping / jumpCharge>0) only guards "already fired/charging" state → on start frame
        //   vanilla jump fire potentially possible. 1:1 restore.
        boolean shouldBlock =
                sm.isCrawling
             || sm.isSliding
             || (cfg.headJump && SmartMovingKeys.grab.isDown() && player.isSprinting())
             || (cfg.jumpCharge && sm.wouldIsSneaking && player.onGround() && sm.isStanding)
             || sm.blockJumpTillButtonRelease;
        if (shouldBlock) {
            ((net.minecraft.world.entity.LivingEntity)(Object)this).setJumping(false);
        }
    }

    @Inject(method = "aiStep", at = @At("TAIL"))
    private void sm_tickMovementYawRestore(CallbackInfo ci) {
        LocalPlayer player = (LocalPlayer)(Object)this;
        // Check only flying entry edge (HEAD: abilFly=false, TAIL: abilFly=true).
        boolean flyEntryEdge = !sm_abilFlyAtTickStart && player.getAbilities().flying;
        if (!flyEntryEdge) return;
        // All yaw changes at flying entry edge point = vanilla automatic correction (no user mouse input).
        // Threshold 1° (avoid floating-point noise, catch all other vanilla automatic rotations).
        // User mouse input is separate method (changeLookDirection) so no yaw change inside tick →
        // safely restore all changes.
        float currentYaw = player.getYRot();
        float diff = currentYaw - sm_yawAtTickStart;
        while (diff > 180F) diff -= 360F;
        while (diff < -180F) diff += 360F;
        if (Math.abs(diff) > 1F) {
            player.setYRot(sm_yawAtTickStart);
            player.yBodyRot = sm_yawAtTickStart;
            player.yBodyRotO = sm_yawAtTickStart;
        }
    }


    /**
     * 🔴 Block sneak pose change while climbing ladder/vine.
     *
     * Original SmartMovingSelf.isSneaking() L3226-3232:
     *   return ... || (!_crawlOverEdge && isCrawling && !isClimbing) || ...;
     * Original 1.7.10 vanilla pose = directly uses isSneaking() result → SM override false →
     * pose STANDING. **No pose change in all ladder climbing cases regardless of W**.
     *
     * 1.21.1 vanilla pose decision source tracking impossible → reset all sneak sources together.
     * Guard: only sm.isClimbing (regardless of W — 1:1 original).
     */
    @Inject(method = "aiStep", at = @At("TAIL"))
    private void sm_resetSneakInClimb(CallbackInfo ci) {
        LocalPlayer player = (LocalPlayer)(Object)this;
        if (!SmartMovingConfig.Config.enabled) return;
        SmartMovingClientState sm = SmartMovingClientState.get(player);
        // 🔴 Maintain 1-tick reset right after climbing (wasClimbing). User report: vanilla
        //   CROUCHING activates right after climbing ends. KeyboardInput.tick runs before tickMovement
        //   so even if forced sneaking=false in KeyboardInput, vanilla pose processing for that tick
        //   already progressed so pose persists. Also reset with wasClimbing guard in tickMovement TAIL.
        if (sm.isClimbing || sm.wasClimbing || sm.sneakHeldDuringClimb) {
            // 1. input.sneaking 강제 false
            Input input = player.input.keyPresses;
            player.input.keyPresses = new Input(input.forward(), input.backward(), input.left(), input.right(),
                    input.jump(), false, input.sprint());
            // 2. SNEAKING flag 강제 false
            if (player.isShiftKeyDown()) player.setShiftKeyDown(false);
            // 3. CROUCHING 자세 강제 STANDING
            if (player.getPose() == Pose.CROUCHING) {
                player.setPose(Pose.STANDING);
            }
            // 🔴 Remove forced reset of isCrawling/crawlToggled: At climbing end isClimbCrawling
            //   release edge (L1890-1908) toCrawling() call result (isCrawling=true) would be
            //   immediately invalidated → block SM crawl mode entry + persist heightOffset=-1 (B-35 reset doesn't fire)
            //   → vanilla CROUCHING + persisting small box becomes user report "crouching" pose.
            //   isCrawling is auto-blocked during climbing by canCrawl `!isClimbing` guard in main expression (L1535)
            //   so additional forced reset unnecessary.
        }
    }

    /**
     * afterOnLivingUpdate: flyWhileOnGround handling.
     * Original: SmartMovingSelf.afterOnLivingUpdate() (SmartMovingPlayerBase.java, lines 1368-1377)
     *
     * After vanilla tickMovement execution, isFlying became false from landing, but
     * if SM flyWhileOnGround=true, restore flying.
     * Allow landing when sneakButton+grabButton pressed simultaneously.
     *
     * Original: sp.cameraYaw=0; sp.prevCameraYaw=0 — fields don't exist in 1.21.1 (deleted).
     */
    @Inject(method = "aiStep", at = @At("TAIL"), order = 900)
    private void sm_flyWhileOnGround(CallbackInfo ci) {
        LocalPlayer player = (LocalPlayer)(Object)this;
        SmartMovingClientState sm = SmartMovingClientState.get(player);
        SmartMovingConfig cfg = SmartMovingConfig.Config;

        // 🔴 Remove extraneous guard (Flying Phase F-6 / Session 40): Remove !cfg.flyCloseToGround guard.
        //   Original SmartMovingSelf L1827 = only checks `_flyWhileOnGround.value && !(sneak+grab) &&
        //   wasCapabilitiesIsFlying && !sp.capabilities.isFlying && sp.onGround` —
        //   no _flyCloseToGround guard. 1.21.1 additional `!cfg.flyCloseToGround` guard is extraneous
        //   not in original → when flyCloseToGround=true (default) blocks auto flight restore = BUG impact.
        //   Fix: Delete guard (only check cfg.flyWhileOnGround).
        if (!cfg.enabled || !cfg.flyWhileOnGround) return;
        // sneakButton.Pressed: vanilla 키 직접 (SM isSneaking override 우회)
        if (net.minecraft.client.Minecraft.getInstance().options.keyShift.isDown()
                && SmartMovingKeys.grab.isDown()) return;
        if (sm.wasCapabilitiesIsFlying && !player.getAbilities().flying && player.onGround()) {
            player.getAbilities().flying = true;
            player.onUpdateAbilities();
        }
    }

    /**
     * correctOnUpdate: bodyYaw correction during low-speed movement like swimming/crawling.
     * Original: SmartMovingBase.correctOnUpdate(isSmall, reverseMaterialAcceleration)
     *       isSmall = isSwimming||isDiving||isDipping||isCrawling
     *       reverseMaterialAcceleration(isSwimming) → 1.21.1 N/A
     *
     * In 0.02 < f < 0.05 range (slow movement), gradually align renderYawOffset(=bodyYaw) to movement direction.
     * When sp.swingProgress > 0, fix rotationYaw.
     */
    @Inject(method = "aiStep", at = @At("TAIL"))
    private void sm_correctOnUpdate(CallbackInfo ci) {
        LocalPlayer player = (LocalPlayer)(Object)this;
        SmartMovingConfig cfg = SmartMovingConfig.Config;
        if (!cfg.enabled) return;
        SmartMovingClientState sm = SmartMovingClientState.get(player);

        boolean isSmall = sm.isSwimming_sm || sm.isDiving || sm.isDipping || sm.isCrawling;
        if (!isSmall) return;

        double d  = player.getX() - player.xo;
        double d1 = player.getZ() - player.zo;
        float f = (float) Math.sqrt(d * d + d1 * d1);
        if (f <= 0.02F || f >= 0.05F) return;

        float f1 = (float)(Math.atan2(d1, d) * 180.0 / Math.PI) - 90F;
        if (player.attackAnim > 0.0F) f1 = player.getYRot();

        float f4 = f1 - player.yBodyRot;
        for (; f4 < -180F; f4 += 360F) {}
        for (; f4 >= 180F;  f4 -= 360F) {}
        float x = player.yBodyRot + f4 * 0.3F;

        float f5 = player.getYRot() - x;
        for (; f5 < -180F; f5 += 360F) {}
        for (; f5 >= 180F;  f5 -= 360F) {}
        if (f5 < -75F) f5 = -75F;
        if (f5 >= 75F)  f5 = 75F;

        player.yBodyRot = player.getYRot() - f5;
        if (f5 * f5 > 2500F) player.yBodyRot += f5 * 0.2F;

        for (; player.yBodyRot - player.yBodyRotO < -180F; player.yBodyRotO -= 360F) {}
        for (; player.yBodyRot - player.yBodyRotO >= 180F; player.yBodyRotO += 360F) {}
    }

    // R-01: tickMovement TAIL — send State packet after all movement processing
    @Inject(method = "aiStep", at = @At("TAIL"))
    private void sm_sendStatePacket(CallbackInfo ci) {
        LocalPlayer player = (LocalPlayer)(Object)this;
        SmartMovingClientState sm = SmartMovingClientState.get(player);
        sm.sendStatePacket(player);

        // 🔵 (2026-05-14, Option F fix #97) StatsPayload — send self stats angle every tick.
        //   Cause: vanilla lerpPosAndRotation interpolates REMOTE position in 5-tick equal division → REMOTE
        //     own stats.calculate result (= realDxYz atan2) flows differently from self trajectory →
        //     lerpFadeAngle 0.2 insufficient time to chase peak.
        //   Application: self sm.stats.currentVerticalAngle/HorizontalAngle every tick C2S → server
        //     relay → REMOTE overwrites own calculation result with packet value.
        //   Load: every tick 12 bytes (= int + float*2) * tracker count. ~5 kbps/tracker.
        if (net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.canSend(
                choco.ratel.smartmoving.network.SmartMovingNetwork.StatsPayload.ID)) {
            net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(
                new choco.ratel.smartmoving.network.SmartMovingNetwork.StatsPayload(
                    player.getId(),
                    sm.stats.currentVerticalAngle,
                    sm.stats.currentHorizontalAngle));
        }
    }

    /**
     * 낙하 중 sneak 시 모델/카메라 Y -0.125 점프 차단 — `inSneakingPose` 필드 결정 가로채기.
     *
     * 배경: ClientPlayerEntity 가 자체 `inSneakingPose` boolean 필드를 보유하며,
     * `isInSneakingPose()` 를 override 해 entity.pose 가 아니라 이 필드를 반환.
     * `PlayerEntityRenderer.getPositionOffset` 가 `isInSneakingPose()=true` 시
     * Y = scale × -2/16 = -0.125 offset 적용 → 모델/카메라 점프.
     *
     * `inSneakingPose` 필드는 ClientPlayerEntity.tickMovement (bytecode offset 80-145)
     * 에서 매 tick 별도로 결정:
     *   inSneakingPose = !flying && !swimming && !hasVehicle && canChangeIntoPose(CROUCHING)
     *                    && (isSneaking() || (!isSleeping() && !canChangeIntoPose(STANDING)));
     *
     * MixinPlayerEntity.sm_redirectIsSneakingForFallingPose 가 PlayerEntity.updatePose 의
     * isSneaking() 만 가로챘으나, 이 ClientPlayerEntity.tickMovement 의 isSneaking() 호출은
     * 별도라 그대로 통과 → inSneakingPose=true → getPositionOffset Y -0.125 적용.
     *
     * 해결: tickMovement 안의 `isSneaking()` 호출 (offset 114) 만 redirect → 일반 낙하 중 false
     * 반환 → inSneakingPose 의 isSneaking() term 무력화 → inner = (!isSleeping &&
     * !canChangeIntoPose(STANDING)) 만 남음 → 일반 공중 낙하 시 false → inSneakingPose=false.
     * 엘리트라 활공 중에는 redirect 결과를 바꾸지 않아 vanilla 자세 처리를 보존한다.
     *
     * 좁은 천장 시나리오 (canChangeIntoPose(STANDING)=false) 에서는 vanilla 가 자체 판단으로
     * inSneakingPose=true 강제 — vanilla 안전 fallback 으로 사용자 강제 STANDING 시 끼임 방지.
     */
    @Redirect(method = "aiStep",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/player/LocalPlayer;isShiftKeyDown()Z"))
    private boolean sm_redirectIsSneakingForInSneakingPose(LocalPlayer self) {
        boolean original = self.isShiftKeyDown();
        if (!original) return false;
        if (!SmartMovingConfig.Config.enabled) return original;

        boolean isFalling = !self.onGround()
                && self.fallDistance > SmartMovingConfig.Config.fallAnimationDistanceMinimum
                && !self.isInWater()
                && !self.isFallFlying()
                && !self.getAbilities().flying;
        if (isFalling) return false;

        // 🔴 fix #78 (BUG #2.6, 2026-05-11, 사용자 보고 "weeping/twisting vines + sneak 시 모델 살짝 아래"):
        //   ClientPlayerEntity 의 자체 inSneakingPose 필드는 entity.pose 무관하게 별도 식으로 결정:
        //     inSneakingPose = !flying && !swimming && !hasVehicle && canChangeIntoPose(CROUCHING)
        //                      && (isSneaking() || (!isSleeping() && !canChangeIntoPose(STANDING)));
        //   fix #77 은 PlayerEntity.updatePose 의 isSneaking() 만 redirect (= entity.pose=STANDING).
        //   하지만 여기 ClientPlayerEntity.tickMovement 의 isSneaking() 호출은 별도 → fix #75 후
        //   vanilla 위임 → true → inSneakingPose=true → PlayerEntityRenderer.getPositionOffset
        //   `Y = -2/16 = -0.125` offset 적용 → 모델 살짝 아래.
        //
        //   해결: 같은 redirect 에 weeping/twisting vines 가드 추가 → 해당 isSneaking() 호출만 false
        //   반환 → inSneakingPose=false → 모델 정상 위치.
        //   다른 isSneaking() 호출 (isHoldingOntoLadder 등) 영향 X → 매달림 유지 (= 기능 보존).
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
}
