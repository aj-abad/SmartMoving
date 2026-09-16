package choco.ratel.smartmoving.client;

import choco.ratel.smartmoving.client.input.SmartMovingKeys;
import choco.ratel.smartmoving.config.SmartMovingConfig;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.stats.Stats;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Client-side jump physics logic.
 * Original: SmartMovingSelf.handleJumping(), tryJump(), getJumpMoving(), handleWallJumping()
 *
 * Contains:
 *   10-1: handleJumping() — Jump determination entry point
 *   10-2: tryJump() — Actual velocity calculation
 *   10-3: getJumpMoving() — Angled jump horizontal velocity helper
 *   10-4: setPoseSmall() / resetHeightOffset() — Head jump hitbox
 *   10-5: handleWallJumping() — Wall jump
 */
@Environment(EnvType.CLIENT)
public final class SmartMovingJumper {

    private SmartMovingJumper() {}

    // ── jumpType constants (Phase B-2.5 / Session 19 cleanup) ──────────────────
    // SmartMovingConfig.JUMP_TYPE_* alias (original SmartMovingClientConfig L178-L192).
    // Unified with original values: Up=0, ChargeUp=1, Angle=2, HeadUp=3, SlideDown=4, ClimbUp=5,
    //   ClimbUpHandsOnly=6, ClimbBackUp=7, ClimbBackUpHandsOnly=8, ClimbBackHead=9,
    //   ClimbBackHeadHandsOnly=10, WallUp=11, WallHead=12, WallUpSlide=13, WallHeadSlide=14.
    public static final int UP                          = SmartMovingConfig.JUMP_TYPE_UP;
    public static final int CHARGE_UP                   = SmartMovingConfig.JUMP_TYPE_CHARGE_UP;
    public static final int ANGLE                       = SmartMovingConfig.JUMP_TYPE_ANGLE;
    public static final int HEAD_UP                     = SmartMovingConfig.JUMP_TYPE_HEAD_UP;
    public static final int SLIDE_DOWN                  = SmartMovingConfig.JUMP_TYPE_SLIDE_DOWN;
    public static final int CLIMB_UP                    = SmartMovingConfig.JUMP_TYPE_CLIMB_UP;
    public static final int CLIMB_UP_HANDS_ONLY         = SmartMovingConfig.JUMP_TYPE_CLIMB_UP_HANDS_ONLY;
    public static final int CLIMB_BACK                  = SmartMovingConfig.JUMP_TYPE_CLIMB_BACK_UP;
    public static final int CLIMB_BACK_HANDS_ONLY       = SmartMovingConfig.JUMP_TYPE_CLIMB_BACK_UP_HANDS_ONLY;
    public static final int CLIMB_BACK_HEAD             = SmartMovingConfig.JUMP_TYPE_CLIMB_BACK_HEAD;
    public static final int CLIMB_BACK_HEAD_HANDS_ONLY  = SmartMovingConfig.JUMP_TYPE_CLIMB_BACK_HEAD_HANDS_ONLY;
    public static final int WALL_UP                     = SmartMovingConfig.JUMP_TYPE_WALL_UP;
    public static final int WALL_HEAD                   = SmartMovingConfig.JUMP_TYPE_WALL_HEAD;
    public static final int WALL_UP_SLIDE               = SmartMovingConfig.JUMP_TYPE_WALL_UP_SLIDE;
    public static final int WALL_HEAD_SLIDE             = SmartMovingConfig.JUMP_TYPE_WALL_HEAD_SLIDE;
    // Phase E-4 (Session 20) integration complete: double-click directional jump → tryJump(ANGLE, ..., worldAngleDeg).
    //   Existing LEFT/RIGHT/BACK sentinel constants removed from usage 0 → removed.

    // ── [10-3] getJumpMoving ─────────────────────────────────────────────────

    /**
     * Calculate horizontal velocity for angled jump.
     * Original: SmartMovingSelf.getJumpMoving() formula ported as-is.
     *
     * | reset=false      | actual + move * horizontal                          |
     * | reset, opposite direction | move * horizontalJumpFactor                         |
     * | reset, same direction | max(|actual|, |move| * horizontal) * signum(move)   |
     */
    public static double getJumpMoving(double actual, double move, boolean reset,
                                        double horizontal, float horizontalJumpFactor) {
        if (!reset)
            return actual + move * horizontal;
        else if (Math.signum(actual) != Math.signum(move))
            return move * horizontalJumpFactor;
        else
            return Math.max(Math.abs(actual), Math.abs(move) * horizontal) * Math.signum(move);
    }

    // ── [10-4] setPoseSmall / resetHeightOffset ──────────────────────────────

    /**
     * Start head jump: Switch to SLIDING pose and recalculate hitbox.
     * Original: setHeightOffset(-1F) → direct boundingBox.minY manipulation (1.7.10)
     * 1.21.1: Replaced with getBaseDimensions() Mixin (SLIDING→0.6×0.8) + pose change.
     * pose_strategy.md C-02 final decision: EntityPose.SLIDING + getBaseDimensions Mixin.
     */
    public static void setPoseSmall(LocalPlayer player) {
        player.setPose(Pose.SLIDING);
        player.refreshDimensions();
    }

    /**
     * End head jump (landing): Restore STANDING pose. Keep SLIDING if insufficient space.
     * Original: resetHeightOffset() + standUp()
     * PlayerEntity excluded from Entity.calculateDimensions() (pose_strategy.md 5-3) →
     * SM directly checks space before pose transition.
     *
     * Caller (MixinLivingEntityClient) checks isOnGround && isHeadJumping every tick,
     * so keep isHeadJumping = true until space is available to force updatePose Mixin to maintain SLIDING.
     */
    public static void resetHeightOffset(LocalPlayer player, SmartMovingClientState sm) {
        Level world = player.level();
        int px = (int) Math.floor(player.getX());
        int pz = (int) Math.floor(player.getZ());
        int fromY = (int) Math.ceil(player.getY() + 0.8D);
        int toY   = (int) Math.ceil(player.getY() + 1.8D);

        for (int by = fromY; by <= toY; by++) {
            BlockPos pos = new BlockPos(px, by, pz);
            if (!world.getBlockState(pos).getCollisionShape(world, pos).isEmpty()) {
                return; // Insufficient space — keep isHeadJumping, maintain SLIDING pose
            }
        }

        // Space available — restore STANDING
        sm.isHeadJumping = false;
        sm.heightOffset = 0F;

        // B-29 (Session 54): Restore original toSlidingOrCrawling L2226 condition 1:1 —
        //   `Config.isSlidingEnabled() && (grabButton.Pressed || wasHeadJumping)`
        // Remove previous approximate `isSprinting() || isFast` mapping. Original is "grab pressed or previous tick
        // headJumping" condition — remove oversimplified mapping with completely different meaning.
        SmartMovingConfig cfg = SmartMovingConfig.Config;
        if (cfg.slide && cfg.enabled
                && (SmartMovingKeys.grab.isDown() || sm.wasHeadJumping)) {
            sm.isSliding = true;
        } else {
            AABB standBox = player.getDimensions(Pose.STANDING)
                                 .makeBoundingBox(player.position())
                                 .deflate(1.0E-7);
            if (!world.noCollision(player, standBox)) {
                // B-45b (Session 45): Correspond to original toSlidingOrCrawling else branch `wasCrawling = toCrawling()`
                // Previous inline (missing cfg.enabled) → call ClientState.toCrawling() helper.
                sm.toCrawling();
            }
        }

        player.setPose(Pose.STANDING);
        player.refreshDimensions();
    }

    // ── [10-2] tryJump (Phase D 1:1 rewrite, Session 19) ────────────────────

    /**
     * Calculate and apply actual jump velocity.
     *
     * Original: `SmartMovingSelf.tryJump()` L1999-L2136 (Focus #2.5 Phase D, Session 19, 1:1 port).
     *
     * **§7-1 approximation (D-5/D-14 skip)**: jumpExhaustion gate + accumulation not ported.
     *   In Easy 1:1 dimension all jumpExhaustion=false → exhausionEnabled always false →
     *   D-5/D-14 inner block 100% dead. focus_05 §6.5 P-9~P-19 dead analysis consistent.
     *   Manual activation by user does not work (focus_02_5 §7-1 permanently registered).
     *
     * @param type             SmartMovingConfig.JUMP_TYPE_* constant (15 types)
     * @param inWaterOrNull    null → use sm.isDipping / non-null → force (SlideDown=false etc)
     * @param isRunningOrNull  null → use sm.isRunning(player) / non-null → force (SlideDown=wasRunning etc)
     * @param angle            null → normal jump / non-null → angled jump (Wall/Side/Back)
     * @return enabled         Result of isJumpingEnabled(speed, type). For caller fallback decision.
     */
    public static boolean tryJump(LocalPlayer player, SmartMovingClientState sm,
                                   int type, Boolean inWaterOrNull, Boolean isRunningOrNull, Float angle) {
        SmartMovingConfig cfg = SmartMovingConfig.Config;

        // === D-2 (original L2002-L2006) — WallUpSlide/WallHeadSlide → noVertical conversion ===
        boolean noVertical = false;
        if (type == SmartMovingConfig.JUMP_TYPE_WALL_UP_SLIDE
                || type == SmartMovingConfig.JUMP_TYPE_WALL_HEAD_SLIDE) {
            type = (type == SmartMovingConfig.JUMP_TYPE_WALL_UP_SLIDE)
                    ? SmartMovingConfig.JUMP_TYPE_WALL_UP
                    : SmartMovingConfig.JUMP_TYPE_WALL_HEAD;
            noVertical = true;
        }

        // === D-3 (original L2008-L2012) — local variable calculation ===
        boolean inWater   = inWaterOrNull   != null ? inWaterOrNull   : sm.isDipping;
        boolean isRunning = isRunningOrNull != null ? isRunningOrNull : sm.isRunning(player);
        boolean charged = type == SmartMovingConfig.JUMP_TYPE_CHARGE_UP;
        boolean up = type == SmartMovingConfig.JUMP_TYPE_UP
                  || type == SmartMovingConfig.JUMP_TYPE_CHARGE_UP
                  || type == SmartMovingConfig.JUMP_TYPE_HEAD_UP
                  || type == SmartMovingConfig.JUMP_TYPE_CLIMB_UP
                  || type == SmartMovingConfig.JUMP_TYPE_CLIMB_UP_HANDS_ONLY
                  || type == SmartMovingConfig.JUMP_TYPE_CLIMB_BACK_UP
                  || type == SmartMovingConfig.JUMP_TYPE_CLIMB_BACK_UP_HANDS_ONLY
                  || type == SmartMovingConfig.JUMP_TYPE_CLIMB_BACK_HEAD
                  || type == SmartMovingConfig.JUMP_TYPE_CLIMB_BACK_HEAD_HANDS_ONLY
                  || type == SmartMovingConfig.JUMP_TYPE_ANGLE
                  || type == SmartMovingConfig.JUMP_TYPE_WALL_UP
                  || type == SmartMovingConfig.JUMP_TYPE_WALL_HEAD;
        boolean head = type == SmartMovingConfig.JUMP_TYPE_HEAD_UP
                    || type == SmartMovingConfig.JUMP_TYPE_CLIMB_BACK_HEAD
                    || type == SmartMovingConfig.JUMP_TYPE_CLIMB_BACK_HEAD_HANDS_ONLY
                    || type == SmartMovingConfig.JUMP_TYPE_WALL_HEAD;

        // === D-4 (original L2014-L2015) — getJumpSpeed + isJumpingEnabled ===
        int speed = SmartMovingConfig.getJumpSpeed(sm.isStanding, sm.isSlow, isRunning, sm.isFast, angle);
        boolean enabled = cfg.isJumpingEnabled(speed, type);

        // === enabled gate (original L2016 if(enabled)) — F-1 audit correction (Session 21) ===
        //   All D-5 ~ D-16 jump processing blocks inside enabled. When user disables sub-jump (e.g.
        //   move.jump.run=false) jump itself doesn't work — original intent. D-18 state clearing is
        //   handled regardless of enabled (original caller handled same way, integrated internally for 1.21.1).
        if (enabled) {
            // === D-5 — SKIP (§7-1 approximation) ===
            // Original L2018-L2027 jumpExhaustion gate/accumulation: Easy default all jumpExhaustion=false →
            //   exhausionEnabled always false → inner block 100% dead. focus_05 §6.5 P-9~P-19 consistent.
            //   Does not work with manual user activation (focus_02_5 §7-1 permanently registered).

            // === D-6 (original L2029-L2032) — jumpFactor (potion) + horizontal/vertical factor + jumpChargeFactor ===
            float jumpFactor = 1F;
            MobEffectInstance jumpBoost = player.getEffect(MobEffects.JUMP_BOOST);
            if (jumpBoost != null)
                jumpFactor = 1F + (jumpBoost.getAmplifier() + 1) * 0.2F;
            float horizontalJumpFactor = cfg.getJumpHorizontalFactor(speed, type) * jumpFactor;
            float verticalJumpFactor   = cfg.getJumpVerticalFactor(speed, type) * jumpFactor;
            float jumpChargeFactor = charged ? cfg.getJumpChargeFactor(sm.jumpCharge) : 1F;

            // === D-7 (original L2034-L2038) — !up conversion (sqrt) ===
            if (!up) {
                horizontalJumpFactor = (float) Math.sqrt(
                        horizontalJumpFactor * horizontalJumpFactor
                                + verticalJumpFactor * verticalJumpFactor);
                verticalJumpFactor = 0F;
            }

            // === D-8 (original L2040-L2045) — maxHorizontalMotion + verticalMotion initialization ===
            Double maxHorizontalMotion = null;
            double horizontalMotion = Math.sqrt(sm.jumpMotionX * sm.jumpMotionX
                                               + sm.jumpMotionZ * sm.jumpMotionZ);
            double verticalMotion = -0.078 + 0.498 * verticalJumpFactor * jumpChargeFactor;
            if (horizontalJumpFactor > 1F && !player.horizontalCollision) {
                maxHorizontalMotion = (double) cfg.getMaxHorizontalMotion(speed, type, inWater)
                                      * SmartMovingMover.getCombinedSpeedFactor(player, cfg);
            }

            // === D-9 + D-10/D-11/D-12 (original L2047-L2111) — Up && vanilla branch if/else structure ===
            //   F-1 audit correction (Session 21): Original is if(Up && vanilla) {...} else { head/angle/scale }
            //   1.21.1 previous: all 4 ifs execute sequentially → D-12 scale applied to vanilla Up jump
            //   regression (sprint jump horizontal velocity doubled). Restore if/else structure.
            Vec3 vel = player.getDeltaMovement();
            double motionX = vel.x;
            double motionZ = vel.z;
            if (type == SmartMovingConfig.JUMP_TYPE_UP && sm.vanilla()) {
                // === D-9 (original L2047-L2062) — vanilla Up branch ===
                verticalMotion = 0.41999998688697815D;
                if (jumpBoost != null) verticalMotion += (jumpBoost.getAmplifier() + 1) * 0.1F;
                if (player.isSprinting()) {
                    float f = player.getYRot() * 0.017453292F;
                    motionX -= Math.sin(f) * 0.2F;
                    motionZ += Math.cos(f) * 0.2F;
                }
            } else {
                // === D-10 (original L2065-L2079) — head recalculation ===
                if (head) {
                    double normalAngle = Math.atan(verticalMotion / horizontalMotion);
                    double totalMotion = Math.sqrt(verticalMotion * verticalMotion
                                                   + horizontalMotion * horizontalMotion);
                    double factor = cfg.getHeadJumpFactor(sm.headJumpCharge);
                    double newAngle = factor * normalAngle;
                    double newVerticalMotion = totalMotion * Math.sin(newAngle);
                    double newHorizontalMotion = totalMotion * Math.cos(newAngle);
                    if (maxHorizontalMotion != null)
                        maxHorizontalMotion = maxHorizontalMotion * (newHorizontalMotion / horizontalMotion);
                    verticalMotion = newVerticalMotion;
                    horizontalMotion = newHorizontalMotion;
                }

                // === D-11 (original L2081-L2095) — angle != null branch ===
                if (angle != null) {
                    // Original RadiantToAngle = 180 / PI ≈ 57.2957795
                    float jumpAngleRad = angle / 57.295776F;
                    boolean reset = type == SmartMovingConfig.JUMP_TYPE_WALL_UP
                                 || type == SmartMovingConfig.JUMP_TYPE_WALL_HEAD;
                    double horizontal = Math.max(horizontalMotion, horizontalJumpFactor);
                    double moveX = -Math.sin(jumpAngleRad);
                    double moveZ =  Math.cos(jumpAngleRad);
                    motionX = getJumpMoving(sm.jumpMotionX, moveX, reset, horizontal, horizontalJumpFactor);
                    motionZ = getJumpMoving(sm.jumpMotionZ, moveZ, reset, horizontal, horizontalJumpFactor);
                    horizontalMotion = 0;
                    verticalMotion = verticalJumpFactor;
                }

                // === D-12 (original L2097-L2110) — horizontalMotion > 0 scale ===
                if (horizontalMotion > 0) {
                    double absMotionX = Math.abs(motionX) * horizontalJumpFactor;
                    double absMotionZ = Math.abs(motionZ) * horizontalJumpFactor;
                    if (maxHorizontalMotion != null) {
                        absMotionX = Math.min(absMotionX, maxHorizontalMotion
                                        * (horizontalJumpFactor * (Math.abs(motionX) / horizontalMotion)));
                        absMotionZ = Math.min(absMotionZ, maxHorizontalMotion
                                        * (horizontalJumpFactor * (Math.abs(motionZ) / horizontalMotion)));
                    }
                    motionX = Math.signum(motionX) * absMotionX;
                    motionZ = Math.signum(motionZ) * absMotionZ;
                }
            }

            // === D-13 (original L2113-L2118) — up && !noVertical → apply motionY + Stats + isSprintJump ===
            double motionY = vel.y;  // Keep default when !up or noVertical
            if (up && !noVertical) {
                motionY = verticalMotion;
                player.awardStat(Stats.JUMP);
                sm.isSprintJump = sm.isFast;
            }

            // === D-14 — SKIP (§7-1 approximation) ===
            // Original L2120-L2124 post-jump exhaustion accumulation: Same dead as D-5.

            // === D-15 (original L2126-L2130) — head → isHeadJumping + setPoseSmall + heightOffset ===
            if (head) {
                sm.isHeadJumping = true;
                setPoseSmall(player);
                sm.heightOffset = -1F;
                // 🔴 fix (2026-05-08, user report "camera jumps up on entry + bounce on landing"):
                //   Previous mapping: setPos(y+1m) + calculateDimensions() → player.y +1m immediate jump +
                //     box update. Result:
                //     (a) Entry camera = vanilla getCameraPosVec lerp(prevY=old, y=old+1) interpolates
                //         +1m over 1 tick → "jumps up" visual (first/third person both).
                //     (b) On exit player.y remains +1m → STANDING POSE restore box feet +1m above
                //         → vanilla collision 1m away → box suddenly enlarges + bounces.
                //   Original behavior: setHeightOffset(-1F) adjusts boundingBox.minY +=1m + height -=1m,
                //     posY unchanged. Camera = posY + 1.62F (STANDING eyeHeight) = unchanged. Only vy
                //     moves smoothly. Exit resetHeightOffset also only changes boundingBox, posY unchanged →
                //     box naturally restores.
                //   True mapping: posY unchanged + dim eyeHeight=1.62F (same as STANDING) handling.
                //     Box collision foot position same as STANDING (1m difference — collision difference from
                //     original requires separate fix if needed via calculateBoundingBox interception later).
                //   eyeHeight 1.62F handling in MixinPlayerEntityClient.sm_getBaseDimensions_client with
                //     isHeadJumping separate branch.
                // Removed setPos / calculateDimensions — posY unchanged + camera = player.y + 1.62F.
            }

            // === D-16 (original L2131-L2134) — setVelocity + isJumping ===
            player.setDeltaMovement(motionX, noVertical ? vel.y : motionY, motionZ);
            // sp.isAirBorne = true — automatic vanilla (velocityDirty + fallDistance)
            sm.isJumping = true;
            // onLivingJump() — automatic vanilla PlayerEntity jump event handling
        }

        // === D-18 removed (Phase C 1:1 correction) — separated caller handling restores original 1:1 ===
        //   Previous: tryJump end sets jumpCharge=0 / headJumpCharge=0 / blockJumpTillButtonRelease=true /
        //         jumpPending=false all together. Original handles separately in caller (handleJumping charge/head
        //         release branches) — other call sites (wall/climb/angle/slide/water/CreativeFlying)
        //         don't set.
        //   Side effect (before removal): blockJumpTillButtonRelease=true set after every tryJump →
        //         wall/climb back/angle jump then jump key hold regular jump guard fails → difference.
        //   After restoration: matches original behavior exactly. handleJumping charge/head branch caller resets
        //         already correctly mapped (L413-L416 / L463-L468 / L419-L423 / L469-L473).
        //         jumpPending=false reset each tick at handleJumping start (L367).

        // === D-17 (original L2135) — return enabled ===
        return enabled;
    }

    // ── [10-1] handleJumping ─────────────────────────────────────────────────

    /**
     * Jump determination entry point. Called from sm_travel_client() HEAD each tick.
     * Original: SmartMovingSelf.handleJumping() L1842-L1944.
     *
     * **Focus #4 B-3 (Session 2 rewrite)**: User in-game report — "charge jump triggers with just shift
     *   press" → batch fix for 7+ bugs.
     *
     * Processing order (original 1:1):
     *   1. jumpPending = false (method start, original L1844)
     *   2. Release blockJumpTillButtonRelease (on jumpKey release, original L1846)
     *   3. isSwimming/isDiving early return (original L1849)
     *   4. Save jumpMotionX/Z (original L1853-L1854)
     *   5. Charge jump (original L1856-L1878) — jumpKey hold trigger + wouldIsSneaking gate
     *   6. Head jump (original L1880-L1899) — jumpKey hold trigger + grab+sprint gate
     *   7. Water jump (original L1901-L1913) — jumpKey hold + isDipping
     *   8. Normal jump (original L1915-L1916) — jumpAvoided + !isVineAnyClimbing
     *   9. Directional dodge — dedicated dodge key or existing A/D/S double-tap
     */
    public static void handleJumping(LocalPlayer player, SmartMovingClientState sm) {
        SmartMovingConfig cfg = SmartMovingConfig.Config;
        // BUG-14 (Session 36): Don't process jump when SM disabled → vanilla jump works normally.
        //   Previous: No guard → even with SM disabled jumpPending cleared / jumpAvoided set etc →
        //   vanilla jump() intercepted + SM jump also doesn't work → jump itself doesn't work (user report).
        //   Solution: Immediately return when cfg.enabled false → vanilla jump as-is.
        if (!cfg.enabled) return;

        // B-7 (Phase B 1:1 correction): Map original esp.movementInput.jump — use player.input.jumping
        //   instead of raw KeyBinding. Apply `feedback_movementInput_vs_isSneaking` pattern
        //   (processed value from vanilla side — matches flying/sleeping etc scenarios).
        boolean jumpKeyPressed  = player.input.keyPresses.jump();
        boolean grabKeyPressed  = SmartMovingKeys.grab.isDown();

        // 1. Clear jumpPending (original L1844 — method start) ★ B-3 correction
        sm.jumpPending = false;

        // 2. Release blockJumpTillButtonRelease (original L1846-L1847)
        if (sm.blockJumpTillButtonRelease && !jumpKeyPressed) {
            sm.blockJumpTillButtonRelease = false;
        }

        // 3. isSwimming/isDiving early return (original L1849-L1850) ★ B-3 new
        if (sm.isSwimming_sm || sm.isDiving) return;

        // 4. jump = jumpAvoided && isJumpingField && !isInWater && !isInLava (original L1852)
        //    + save jumpMotionX/Z (original L1853-L1854)
        boolean jump = sm.jumpAvoided && sm.jumpPending  // both set when sm_jump intercepted
                && !player.isInWater() && !player.isInLava();
        // But sm.jumpPending cleared to false above in L1 → semantic difference.
        // 1.21.1 mapping: use only jumpAvoided (set on sm_jump intercept, reset false at each tick start).
        jump = sm.jumpAvoided && !player.isInWater() && !player.isInLava();
        {
            Vec3 cv = player.getDeltaMovement();
            sm.jumpMotionX = cv.x;
            sm.jumpMotionZ = cv.z;
        }

        // 5. Charge jump (original L1856-L1878) ★ B-3 1:1 correction
        // Original formula:
        //   isJumpChargingPossible = sp.onGround && isStanding
        //   isJumpCharging = isJumpChargingPossible && wouldIsSneaking
        //   actualJumpCharging = isJumpChargingPossible
        //                      && (!_jumpChargeCancelOnSneakRelease || wouldIsSneaking)
        //   if (actualJumpCharging)
        //     if (esp.movementInput.jump && (cancelOnSneak || wouldIsSneaking))  ★ jump key hold
        //         jumpCharge++
        //     else  if (jumpCharge > 0)  tryJump(ChargeUp); jumpCharge = 0;
        //   else  if (jumpCharge > 0) blockJumpTillButtonRelease = true; jumpCharge = 0;
        boolean isJumpCharging = false;
        if (cfg.jumpCharge) {
            boolean hasMovementInput = player.input.getMoveVector().lengthSquared() > 1.0E-5F;
            boolean movingSneakCharge = sm.wouldIsSneaking && hasMovementInput;
            boolean isJumpChargingPossible = player.onGround()
                    && (sm.isStanding || movingSneakCharge);
            isJumpCharging = isJumpChargingPossible && sm.wouldIsSneaking;

            boolean actualJumpCharging = isJumpChargingPossible
                    && (!cfg.jumpChargeCancelOnSneakRelease || sm.wouldIsSneaking);
            if (actualJumpCharging) {
                if (jumpKeyPressed
                        && (cfg.jumpChargeCancelOnSneakRelease || sm.wouldIsSneaking)) {
                    float chargeStep = movingSneakCharge ? 0.5F : 1F;
                    sm.jumpCharge = Math.min(sm.jumpCharge + chargeStep, cfg.jumpChargeMaximum);
                } else {
                    if (sm.jumpCharge > 0) {
                        tryJump(player, sm, CHARGE_UP, null, null, null);
                    }
                    sm.jumpCharge = 0;
                }
            } else {
                if (sm.jumpCharge > 0) {
                    sm.blockJumpTillButtonRelease = true;
                }
                sm.jumpCharge = 0;
            }
        }

        // 6. Head jump (original L1880-L1899) ★ B-3 1:1 correction — jumpKey trigger
        // Original formula:
        //   isHeadJumpCharging = grabButton.Pressed && (isGroundSprinting || isSprintJump
        //                       || (isRunning() && sp.onGround)) && !isCrawling
        //   if (isHeadJumpCharging)
        //     if (esp.movementInput.jump)         ★ jump key hold
        //         headJumpCharge++
        //     else  if (headJumpCharge > 0 && sp.onGround) tryJump(HeadUp); headJumpCharge = 0;
        //   else  if (headJumpCharge > 0) blockJumpTillButtonRelease = true; headJumpCharge = 0;
        //
        // B-2 / B-3 (Phase B 1:1 correction): Remove local redefine of isGroundSprinting/isRunning.
        //   Previous: Redefined in local variable with wrong formula (isGroundSprinting is original
        //         isStandupSprintingOrRunning formula + isRunning incorrectly uses isFlying instead vanilla()).
        //   Original is isGroundSprinting **field** (L2679 set: canHorizontallySprint
        //   && (onGround||isLevitating) && !swimming && !diving && !climbing) +
        //   isRunning() **method** (L3239: isSprinting && !isFast && (onGround||vanilla())).
        //   1.21.1 side already has sm.isGroundSprinting / sm.isRunning(player) mapping — use directly.
        //
        // B-Slide-HeadJump-fix (2026-05-04): Keep `!sm.isSliding` guard — user reported BUG fix.
        //   Original only explicitly guards `!isCrawling`. 1.21.1 vanilla sprint auto-stop behavior difference
        //   causes isRunning to persist after sliding with forward held → head jump charge activation BUG.
        //   This guard intentionally violates original 1:1.
        boolean isHeadJumpCharging = false;
        if (cfg.headJump) {
            // 🔴 fix #37 revert (2026-05-08, user intent — maintain combined jump direction):
            //   User confirms: fix #33 point (= remove guard + combined jumps) most similar to original.
            //   Only remaining symptoms (bounce/push/low jump) need separate fix direction. Remove guard again.
            // 🔴 fix #82 (2026-05-12, user report "jump charges while sliding and model upside down + forward move"):
            //   Original 1.7.10 L1883 + 1.12.2 L1771 = only guards `!isCrawling`. No `!isSliding`.
            //   Our mapping is also original 1:1 but user perceives visual corruption of jump charging while sliding
            //   (= enter with isHeadJumping=true + normal head jump rotation visual → head ground/feet sky upside down).
            //   Conflicts with fix #33 history (= combined jump intent) but user explicitly requests.
            //   Regression concern: Block combined jump visual. If user *doesn't want behavior* fix revert needed.
            isHeadJumpCharging = grabKeyPressed
                    && (sm.isGroundSprinting || sm.isSprintJump
                        || (sm.isRunning(player) && player.onGround()))
                    && !sm.isCrawling
                    && !sm.isSliding;
            if (isHeadJumpCharging) {
                if (jumpKeyPressed) {
                    // B-3 (Phase B 1:1 correction): Original L1886 `headJumpCharge++` — no clamp.
                    //   Clamp only inside getHeadJumpFactor. Remove Math.min at set stage.
                    sm.headJumpCharge++;
                } else {
                    if (sm.headJumpCharge > 0 && player.onGround()) {
                        tryJump(player, sm, HEAD_UP, null, null, null);
                    }
                    sm.headJumpCharge = 0;
                }
            } else {
                if (sm.headJumpCharge > 0) {
                    sm.blockJumpTillButtonRelease = true;
                }
                sm.headJumpCharge = 0;
            }
        }

        // 7. Water jump (original L1901-L1913) ★ B-3 1:1 correction — jumpKey hold trigger
        // Original formula:
        //   if (esp.movementInput.jump && sp.isInWater() && isDipping)
        //     if (posY - floor(posY) > (isSlow ? 0.37 : 0.6))
        //       sp.motionY -= 0.04F
        //       if (!isStillSwimmingJump && sp.onGround && jumpCharge == 0)
        //         tryJump(Up, true, null, null) → splash sound
        if (jumpKeyPressed && player.isInWater() && sm.isDipping) {
            double frac = player.getY() - Math.floor(player.getY());
            double threshold = sm.isSlow ? 0.37D : 0.6D;
            if (frac > threshold) {
                Vec3 vel = player.getDeltaMovement();
                player.setDeltaMovement(vel.x, vel.y - 0.04D, vel.z);
                if (!sm.isStillSwimmingJump && player.onGround() && sm.jumpCharge == 0) {
                    tryJump(player, sm, UP, Boolean.TRUE, null, null);
                    // Original L1910 splash sound not ported to 1.21.1 (sound effect only, doesn't affect gameplay)
                }
            }
        }

        // 8. Normal jump (original L1915-L1916) ★ B-3 1:1 correction — add !isVineAnyClimbing
        if (jump && !sm.blockJumpTillButtonRelease && !isJumpCharging && !isHeadJumpCharging
                && !sm.isVineAnyClimbing) {
            tryJump(player, sm, UP, Boolean.FALSE, null, null);
        }

        // 9. Dedicated dodge key + existing double-click directional jump (extends original L1918-L1943)
        boolean dodgeTriggered = false;
        if (sm.dodgeJustPressed) {
            Minecraft mc = Minecraft.getInstance();
            int forward = (mc.options.keyUp.isDown() ? 1 : 0)
                    - (mc.options.keyDown.isDown() ? 1 : 0);
            int right = (mc.options.keyRight.isDown() ? 1 : 0)
                    - (mc.options.keyLeft.isDown() ? 1 : 0);
            dodgeTriggered = tryKeyboundDodge(player, sm, cfg, forward, right);
            if (dodgeTriggered) {
                sm.leftJumpCount = 0;
                sm.rightJumpCount = 0;
                sm.backJumpCount = 0;
            }
        }

        // Existing A/D/S double-tap dodge maintained for compatibility.
        // [IMPL-03] Double-click triggers when leftJumpCount/rightJumpCount/backJumpCount == -1.
        if (!dodgeTriggered) {
            int left = 0, back = 0;
            if (sm.leftJumpCount  == -1) left++;
            if (sm.rightJumpCount == -1) left--;
            if (sm.backJumpCount  == -1) back++;

            if (left != 0 || back != 0) {
                boolean canAngleJump = player.onGround() && !sm.isCrawling && !sm.isClimbing
                        && !sm.isCrawlClimbing && !sm.isSwimming_sm && !sm.isDiving;

                if (canAngleJump) {
                    int relAngle;
                    if (left > 0)      relAngle = back == 0 ? 270 : 225;
                    else if (left < 0) relAngle = back == 0 ? 90  : 135;
                    else               relAngle = 180;

                    tryAngleJump(player, sm, relAngle);
                }

                sm.leftJumpCount  = 0;
                sm.rightJumpCount = 0;
                sm.backJumpCount  = 0;
            }
        }
    }

    /**
     * Trigger bound dodge key with current WASD direction. Does not activate without directional input.
     */
    private static boolean tryKeyboundDodge(LocalPlayer player, SmartMovingClientState sm,
                                             SmartMovingConfig cfg, int forward, int right) {
        if (forward == 0 && right == 0) return false;
        if (player.isSleeping() || !player.onGround()
                || sm.isCrawling || sm.isClimbing || sm.isClimbCrawling
                || sm.isCrawlClimbing || sm.isCeilingClimbing
                || sm.isSwimming_sm || sm.isDiving || sm.isSliding
                || sm.isHeadJumping || sm.isFlying) {
            return false;
        }

        if (forward > 0 && !cfg.angleJumpForward) return false;
        if (forward < 0 && !cfg.angleJumpBack) return false;
        if (right != 0 && !cfg.angleJumpSide) return false;

        int relAngle = Math.round((float) Math.toDegrees(Math.atan2(right, forward)) / 45F) * 45;
        if (relAngle < 0) relAngle += 360;
        return tryAngleJump(player, sm, relAngle);
    }

    /**
     * Convert relative direction (0°=forward) to angle jump physics and 1~8 directional state.
     * Code 8 is forward and syncs high bit to state packet bit 36.
     */
    private static boolean tryAngleJump(LocalPlayer player, SmartMovingClientState sm, int relAngle) {
        int normalizedAngle = Math.floorMod(relAngle, 360);
        sm.angleJumpType = 8 - normalizedAngle / 45;

        float worldAngleDeg = (float) ((player.getYRot() + normalizedAngle) % 360.0);
        if (worldAngleDeg < 0F) worldAngleDeg += 360F;

        if (!tryJump(player, sm, ANGLE, null, null, worldAngleDeg)) {
            sm.angleJumpType = 0;
            return false;
        }
        return true;
    }

    // ── [10-5] Wall jump state update ─────────────────────────────────────────────

    /**
     * Update wall jump state every tick (canWallJumping / wallJumpCount / triggerWallJumping /
     * wantWallJumping / continueWallJumping).
     *
     * Original: SmartMovingSelf (L2863-2897) + jump.md L629-668 1:1 port.
     *
     *   canWallJumping = isWallJumpEnabled && !isHeadJumping && !onGround
     *                    && !isClimbing && !isSwimming && !isDiving
     *                    && !isLevitating && !isFlying
     *
     *   if (_wallJumpDoubleClick) {
     *     if (canWallJumping) {
     *       if (jumpStartPressed) { count==0 → count=ticks(); else trigger=true; count=0 }
     *       else if (count>0) count--
     *     } else count=0
     *   } else trigger = jumpStartPressed
     *
     *   wantWallJumping = canWallJumping &&
     *                     (trigger || continueWallJumping ||
     *                      (wantWallJumping && jumpPressed && !isCollidedHorizontally))
     *
     *   if (continue && (onGround || isClimbing || !jumpPressed)) continue=false
     */
    public static void updateWallJumpState(LocalPlayer player, SmartMovingClientState sm) {
        SmartMovingConfig cfg = SmartMovingConfig.Config;

        // 🔴 fix #157 (2026-05-27): 원본 L2865-L2896 순서 1:1 복원.
        //   기존: wantWallJumping 계산 → continueWallJumping=false set 순서.
        //   원본: continueWallJumping=false set → wantWallJumping 계산 순서.
        //   순서 역전 BUG: jumpKey release 시점, wantWallJumping 식 우측 continueWallJumping=true
        //   잔존 → wantWallJumping 매 tick true 유지 → handleWallJumping 매 tick 발동 → tryJump
        //   성공 시 continueWallJumping=true 재set → **영구 자가유지 cycle** (= 사용자 보고
        //   "jump 떼도 wall jump 탈출 안 됨").

        // 1. (원본 L2865-L2866) continueWallJumping false 전환 — 먼저
        boolean jumpPressed = Minecraft.getInstance().options.keyJump.isDown();
        if (sm.continueWallJumping && (player.onGround() || sm.isClimbing || !jumpPressed)) {
            sm.continueWallJumping = false;
        }

        // 2. (원본 L2868) canWallJumping
        boolean isWallJumpEnabled = cfg.wallUpJump || cfg.wallHeadJump;
        boolean canWallJumping = isWallJumpEnabled && !sm.isHeadJumping && !player.onGround()
                && !sm.isClimbing && !sm.isSwimming_sm && !sm.isDiving
                && !sm.isLevitating && !sm.isFlying;

        // 3. (원본 L2869-L2892) double click 처리
        if (cfg.wallJumpDoubleClick) {
            if (canWallJumping) {
                if (sm.jumpKeyStartPressed) {
                    if (sm.wallJumpCount == 0) {
                        sm.wallJumpCount = (int) Math.ceil(cfg.wallJumpDoubleClickTicks);
                    } else {
                        sm.triggerWallJumping = true;
                        sm.wallJumpCount = 0;
                    }
                } else if (sm.wallJumpCount > 0) {
                    sm.wallJumpCount--;
                }
            } else {
                sm.wallJumpCount = 0;
            }
        } else {
            sm.triggerWallJumping = sm.jumpKeyStartPressed;
        }

        // 4. (원본 L2894-L2896) wantWallJumping — 마지막
        // 자기참조 패턴: 이전 틱 wantWallJumping 을 읽어 jumpPressed && !collided 이면 유지.
        sm.wantWallJumping = canWallJumping &&
                (sm.triggerWallJumping || sm.continueWallJumping ||
                 (sm.wantWallJumping && jumpPressed && !player.horizontalCollision));
    }

    // ── [10-5] handleWallJumping ─────────────────────────────────────────────

    /**
     * 벽 점프 처리. sm_travel_client() 내 climbing 처리 전에 호출된다.
     * 원본: SmartMovingSelf.handleWallJumping() L1946-1997.
     *
     * 원본 진입 조건(L1450): `if (!wantWallJumping || NaN(horizontalCollisionAngle)) return;`
     * → wantWallJumping 은 updateWallJumpState 에서 갱신된 값.
     *
     * 반사 각도 공식: reflectedAngle = horizontalCollisionAngle * 2 - movementAngle + 180
     * jumpAngle = round(reflectedAngle / 90) * 90  (90° 단위 반올림)
     */
    public static void handleWallJumping(LocalPlayer player, SmartMovingClientState sm) {
        SmartMovingConfig cfg = SmartMovingConfig.Config;

        // 원본 L1948: `if (!wantWallJumping || Double.isNaN(horizontalCollisionAngle)) return;`
        // 🔴 fix #157 (2026-05-27): NaN 가드 복원 — 사용자 보고 "벽 없어도 평지에서 발동
        //   (= 뱅글뱅글 + 상승)". 기존 calculateSeparateCollisionAngle 의 NaN fallback (=
        //   movementAngle 반환) 가 원본 NaN 가드 무력화. 공중 (= 4축 collision 모두 false) →
        //   getHorizontalCollisionangle NaN → 원본 즉시 return. 매핑은 NaN → fallback
        //   movementAngle → handleWallJumping 진행 → 공중 wall jump 발동.
        if (!sm.wantWallJumping) return;
        float horizontalCollisionAngle = calculateSeparateCollisionAngle(player);
        if (Float.isNaN(horizontalCollisionAngle)) return;

        // 🔴 fix #162 (2026-05-27): wasCollidedHorizontally=false 강제.
        //   dump 정밀 분석 (docs/log_temp.txt t=1057~1063):
        //     사용자 시나리오 (W hold + 양 벽 사이 jump 연타) 시점 매 tick wasColl=true 매치 →
        //     jumpType=WALL_UP_SLIDE (=13) + jumpAngle=horizontalCollisionAngle (= 벽 법선) →
        //     setYaw(법선) = 사용자 정면 = 같은 방향 → 시각 회전 X. WALL_UP_SLIDE → noVertical=true
        //     → motionY 미적용 → 상승 X.
        //   원본 vs 우리 매핑 timing 차이:
        //     원본 1.7.10 handleWallJumping = moveEntity 후 호출 + L1993 set false → 같은 tick
        //     vanilla move() 추가 호출 없음 → 다음 tick wasColl=false 유지 → 회전 분기 매치.
        //     우리 매핑 handleWallJumping = travel HEAD → L700 set false → 같은 tick vanilla
        //     travel 본체 → vanilla move() → horizontalCollision 다시 true → 다음 tick wasColl=true
        //     → slide 분기 매치 → 회전 X.
        //   해결: wasCollidedHorizontally=false 강제. 사용자 verbatim "벽점프 시 반대방향으로 몸이
        //     회전 + 앞벽 뒤벽 앞벽 뒤벽 연속" 매치. 원본 spec 의 WALL_UP_SLIDE 분기 (= 박스 벽
        //     sliding 시 수직 미적용) 는 1.21.1 매핑에서 매치 어려움 — 사용자 의도 우선.
        boolean wasCollH = false;

        // 원본 L1952-L1963: grab=true → WallHead/WallHeadSlide, grab=false → WallUp/WallUpSlide.
        boolean grabPressed = SmartMovingKeys.grab.isDown();
        int jumpType;
        if (grabPressed) {
            if (!cfg.wallHeadJump) return;
            if (player.fallDistance > cfg.wallHeadJumpFallMaximumDistance) return;
            jumpType = wasCollH ? WALL_HEAD_SLIDE : WALL_HEAD;
        } else {
            if (!cfg.wallUpJump) return;
            if (player.fallDistance > cfg.wallUpJumpFallMaximumDistance) return;
            jumpType = wasCollH ? WALL_UP_SLIDE : WALL_UP;
        }

        // 원본 L1965-L1975: wasCollidedHorizontally=false → 반사 각도; true → 벽 법선 각도 그대로.
        // 🔴 fix #164 (2026-05-27): jumpMotion=0 시 fallback movementAngle = player.getYaw().
        //   dump 정밀 분석 (docs/log_temp.txt 시나리오 A trigger 1/3 t=2109/2401):
        //     박스 -X 벽 매치 + yaw=90 + jumpMotion=0 → fix #163 식 (= movementAngle=hCA=270) →
        //     jumpAngle = 270*2 - 270 + 180 = 90. setYaw(90) = 변경 X. 사용자 보고 "정방향 또는
        //     90도만 틀어진 방향" 매치.
        //   분석:
        //     W hold + 박스 벽 향함 → vel direction = yaw forward direction. 즉 vel direction 의
        //     atan2 결과 = vanilla yaw 좌표계의 yaw. jumpMotion=0 시 vel direction 정보 손실 →
        //     fallback 으로 player.getYaw() 사용해야 일관성.
        //   검증 (모든 4축 매치 case):
        //     +Z 벽 (yaw=0): jumpAngle = 0-0+180 = 180 ✓
        //     -Z 벽 (yaw=180): jumpAngle = 360-180+180 = 360 mod 360 = 0 ✓
        //     +X 벽 (yaw=270): jumpAngle = 180-270+180 = 90 ✓ (벽 법선 yaw=270 → 반대 +X→-X)
        //     -X 벽 (yaw=90): jumpAngle = 540-90+180 = 270 ✓
        //   = 모두 180° 회전 (= 벽 반대 방향). 사용자 의도 매치.
        // 🔴 fix #168 (2026-05-27): jumpAngle 식 단순화 — vanilla collision clamp 영향 차단.
        //   dump 분석 (t=2774): 박스 +Z 벽 매치 + 사용자 yaw=15° + jumpMotion=(-0.022, 0) (=
        //   vanilla collision clamp 가 vel.z 만 clamp, vel.x 잔존). 식 movementAngle =
        //   atan2(0.022, 0) = 90° → jumpAngle = 0*2 - 90 + 180 = **90°** → setYaw(90) → -X 방향
        //   (= 벽 평행 90° 회전). 사용자 의도 = -Z 방향 (= 사용자 정면 반대).
        //   원본 식도 동일 mechanism — vel.x 잔존 시 movementAngle ±90° → jumpAngle 벽 평행.
        //   해결:
        //     1. jumpMotion 가 충분히 큰 경우 (= 사용자 의도 strafe) 만 원본 식 사용.
        //     2. jumpMotion 미세 (= vanilla clamp 잔존) 시 fallback: jumpAngle = yaw + 180
        //        (= 사용자 정면 반대) 직접 set. 식 우회.
        //   threshold = 0.05 (= W input motion 의 약 절반). vanilla clamp 후 잔존 0.02 미만
        //   범위 cover. 사용자 strafe (0.1+) case 영향 X.
        float jumpAngle;
        if (!wasCollH) {
            double jmMag = Math.abs(sm.jumpMotionX) + Math.abs(sm.jumpMotionZ);
            if (jmMag < 0.05D) {
                // fallback: 사용자 정면 반대 방향 (= yaw + 180). jumpMotion 미세 영향 차단.
                jumpAngle = player.getYRot() + 180F;
            } else {
                float movementAngle = (float) Math.toDegrees(Math.atan2(-sm.jumpMotionX, sm.jumpMotionZ));
                if (movementAngle < 0) movementAngle += 360F;
                if (Float.isNaN(movementAngle)) return;
                jumpAngle = horizontalCollisionAngle * 2 - movementAngle + 180F;
            }
        } else {
            jumpAngle = horizontalCollisionAngle;
        }

        // Phase G BUG-1 1:1 정정: while>360 + orthogonalTolerance 분기 외부 적용 (원본 L1977-L1988).
        //   기존: !wasCollidedHorizontally 분기 안에만 적용 → wasCollidedHorizontally=true (Slide 타입)
        //         시 90° 정렬 미적용 → 점프 각도 부정확. 원본은 둘 다 적용.
        // 🔴 fix #167 (2026-05-27): jumpAngle 범위 0~360 정규화. 원본 식 `while > 360` 만 → 음수
        //   또는 정확 360.0 case 미처리. setYaw(360.0) → vanilla yaw field=360.0 그대로 (mod X)
        //   → 매 tick yaw 누적 변동 → 인벤토리 player 모델 회전 진동/뒤틀림.
        //   해결: while < 0 추가 + 정확 360 case 처리. jumpAngle 항상 [0, 360) 범위.
        while (jumpAngle > 360F) jumpAngle -= 360F;
        while (jumpAngle < 0F) jumpAngle += 360F;
        if (jumpAngle >= 360F) jumpAngle = 0F;
        if (cfg.wallUpJumpOrthogonalTolerance != 0F) {
            float aligned = jumpAngle;
            while (aligned > 45F) aligned -= 90F;
            if (Math.abs(aligned) < cfg.wallUpJumpOrthogonalTolerance)
                jumpAngle = Math.round(jumpAngle / 90F) * 90F;
        }

        // Phase G 차이 2/3 1:1 정정: tryJump 결과 if 분기 + 후처리는 성공 시에만 (원본 L1990-L1996).
        //   기존: tryJump 결과 무시 + isWallJumping 이중 set + tryJump 호출 전 후처리.
        if (tryJump(player, sm, jumpType, null, null, jumpAngle)) {
            // 원본 L1992: continueWallJumping = !isHeadJumping (WallHead 시 false)
            sm.continueWallJumping = !sm.isHeadJumping;
            // 원본 L1993: sp.isCollidedHorizontally = false
            player.horizontalCollision = false;
            // 원본 L1994: sp.rotationYaw = jumpAngle
            player.setYRot(jumpAngle);
            player.yBodyRot = jumpAngle;       // (Phase G 차이 4 보류 — 회전 동기화 의도 유지)
            // 원본 L1995 onStartWallJump(jumpAngle) inline:
            //   - prev rotateAngleY = angle / RadiantToAngle  (Phase G 차이 5 보류 — 애니메이션 사이클)
            sm.isWallJumping = true;          // 원본 onStartWallJump (SmartMoving.java L154)
            player.fallDistance = 0F;          // 원본 onStartWallJump (SmartMoving.java L155)
        }
    }

    /**
     * 4방향 AABB 충돌 감지 후 벽 법선 각도를 반환한다.
     * 원본: SmartMovingSelf.calculateSeparateCollisions() + getHorizontalCollisionangle() 호출.
     *
     * 원본 call site: getHorizontalCollisionangle(posZ, negZ, posX, negX) — X/Z swap 유지.
     */
    private static float calculateSeparateCollisionAngle(LocalPlayer player) {
        Level world = player.level();
        AABB bb = player.getBoundingBox();
        double delta = 0.001D;
        boolean posX = !world.noCollision(player, bb.move( delta, 0, 0));
        boolean negX = !world.noCollision(player, bb.move(-delta, 0, 0));
        boolean posZ = !world.noCollision(player, bb.move(0, 0,  delta));
        boolean negZ = !world.noCollision(player, bb.move(0, 0, -delta));
        // 🔴 fix #165 revert (2026-05-27): corner case NaN skip 제거.
        //   사용자 verbatim (재): "대각선 벽 모서리는 일반적인 벽점프가 되어야되는데 안되고 있음".
        //   원본 헬퍼 spec = corner case 시 corner angle (45/135/225/315) 반환 → 일반 wall jump
        //   발동. corner case 도 사용자 의도 wall jump 발동.
        return getHorizontalCollisionangle(posZ, negZ, posX, negX);
    }

    /**
     * 4방향 충돌 조합 → 벽 법선 각도(도) 변환.
     * 원본: SmartRenderUtilities.getHorizontalCollisionangle() — A-23 확인 완료.
     *
     * 주의: 원본 call site(SmartMovingSelf.beforeMoveEntity)에서 X/Z 파라미터가 swap됨.
     * 여기서도 같은 순서 유지: (posZ, negZ, posX, negX) → (param1, param2, param3, param4)
     *
     * 단일 방향 충돌 결과: +Z→0°(남벽), -Z→180°(북벽), +X→90°(동벽), -X→270°(서벽)
     */
    public static float getHorizontalCollisionangle(
            boolean isCollidedPositiveX, boolean isCollidedNegativeX,
            boolean isCollidedPositiveZ, boolean isCollidedNegativeZ) {
        if (isCollidedPositiveX) {
            if (isCollidedNegativeX) {
                if (isCollidedPositiveZ)  return isCollidedNegativeZ ? Float.NaN : 90F;
                else                       return isCollidedNegativeZ ? 270F : Float.NaN;
            } else {
                if (isCollidedPositiveZ)  return isCollidedNegativeZ ? 0F : 45F;
                else                       return isCollidedNegativeZ ? 315F : 0F;
            }
        } else {
            if (isCollidedNegativeX) {
                if (isCollidedPositiveZ)  return isCollidedNegativeZ ? 180F : 135F;
                else                       return isCollidedNegativeZ ? 225F : 180F;
            } else {
                if (isCollidedPositiveZ)  return isCollidedNegativeZ ? Float.NaN : 90F;
                else                       return isCollidedNegativeZ ? 270F : Float.NaN;
            }
        }
    }
}
