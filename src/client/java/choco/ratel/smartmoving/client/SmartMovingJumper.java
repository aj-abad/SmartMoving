package choco.ratel.smartmoving.client;

import choco.ratel.smartmoving.client.windrunner.WindrunnerControls;
import choco.ratel.smartmoving.client.windrunner.WindrunnerWallRun;
import choco.ratel.smartmoving.config.SmartMovingConfig;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
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
 *   10-5: handleWallJumping() — Wall jump
 */
@Environment(EnvType.CLIENT)
public final class SmartMovingJumper {

    private SmartMovingJumper() {}

    // ── jumpType constants (Phase B-2.5 / Session 19 cleanup) ──────────────────
    // SmartMovingConfig.JUMP_TYPE_* alias (original SmartMovingClientConfig L178-L192).
    // Unified with original values: Up=0, ChargeUp=1, Angle=2, SlideDown=4, ClimbUp=5,
    //   ClimbUpHandsOnly=6, ClimbBackUp=7, ClimbBackUpHandsOnly=8, WallUp=11, WallUpSlide=13.
    public static final int UP                          = SmartMovingConfig.JUMP_TYPE_UP;
    public static final int CHARGE_UP                   = SmartMovingConfig.JUMP_TYPE_CHARGE_UP;
    public static final int ANGLE                       = SmartMovingConfig.JUMP_TYPE_ANGLE;
    public static final int SLIDE_DOWN                  = SmartMovingConfig.JUMP_TYPE_SLIDE_DOWN;
    public static final int CLIMB_UP                    = SmartMovingConfig.JUMP_TYPE_CLIMB_UP;
    public static final int CLIMB_UP_HANDS_ONLY         = SmartMovingConfig.JUMP_TYPE_CLIMB_UP_HANDS_ONLY;
    public static final int CLIMB_BACK                  = SmartMovingConfig.JUMP_TYPE_CLIMB_BACK_UP;
    public static final int CLIMB_BACK_HANDS_ONLY       = SmartMovingConfig.JUMP_TYPE_CLIMB_BACK_UP_HANDS_ONLY;
    public static final int WALL_UP                     = SmartMovingConfig.JUMP_TYPE_WALL_UP;
    public static final int WALL_UP_SLIDE               = SmartMovingConfig.JUMP_TYPE_WALL_UP_SLIDE;
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

    /**
     * Horizontal velocity for a dodge (angle jump).
     * The original `getJumpMoving(reset = false)` added `direction * max(|v|, factor)` to the current
     * velocity with no cap, so chained dodges in one direction doubled the speed each time
     * (docs/research_dodge_spam_and_animation.md). Instead: at least `factor` along the dodge
     * direction (cancelling momentum against it), sideways momentum kept, and the result never faster
     * than max(factor, current speed).
     */
    public static Vec3 getDodgeMotion(double motionX, double motionZ, double dirX, double dirZ,
                                      double factor) {
        double along = motionX * dirX + motionZ * dirZ;
        double newAlong = Math.max(along, factor);
        double x = motionX + (newAlong - along) * dirX;
        double z = motionZ + (newAlong - along) * dirZ;

        double limit = Math.max(factor, Math.sqrt(motionX * motionX + motionZ * motionZ));
        double speed = Math.sqrt(x * x + z * z);
        if (speed > limit) {
            x *= limit / speed;
            z *= limit / speed;
        }
        return new Vec3(x, 0, z);
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
     * @param type             SmartMovingConfig.JUMP_TYPE_* constant
     * @param inWaterOrNull    null → use sm.isDipping / non-null → force (SlideDown=false etc)
     * @param isRunningOrNull  null → use sm.isRunning(player) / non-null → force (SlideDown=wasRunning etc)
     * @param angle            null → normal jump / non-null → angled jump (Wall/Side/Back)
     * @return enabled         Result of isJumpingEnabled(speed, type). For caller fallback decision.
     */
    public static boolean tryJump(LocalPlayer player, SmartMovingClientState sm,
                                   int type, Boolean inWaterOrNull, Boolean isRunningOrNull, Float angle) {
        SmartMovingConfig cfg = SmartMovingConfig.Config;

        // === D-2 (original L2002-L2006) — WallUpSlide → noVertical conversion ===
        boolean noVertical = false;
        if (type == SmartMovingConfig.JUMP_TYPE_WALL_UP_SLIDE) {
            type = SmartMovingConfig.JUMP_TYPE_WALL_UP;
            noVertical = true;
        }

        // === D-3 (original L2008-L2012) — local variable calculation ===
        boolean inWater   = inWaterOrNull   != null ? inWaterOrNull   : sm.isDipping;
        boolean isRunning = isRunningOrNull != null ? isRunningOrNull : sm.isRunning(player);
        boolean charged = type == SmartMovingConfig.JUMP_TYPE_CHARGE_UP;
        boolean up = type == SmartMovingConfig.JUMP_TYPE_UP
                  || type == SmartMovingConfig.JUMP_TYPE_CHARGE_UP
                  || type == SmartMovingConfig.JUMP_TYPE_CLIMB_UP
                  || type == SmartMovingConfig.JUMP_TYPE_CLIMB_UP_HANDS_ONLY
                  || type == SmartMovingConfig.JUMP_TYPE_CLIMB_BACK_UP
                  || type == SmartMovingConfig.JUMP_TYPE_CLIMB_BACK_UP_HANDS_ONLY
                  || type == SmartMovingConfig.JUMP_TYPE_ANGLE
                  || type == SmartMovingConfig.JUMP_TYPE_WALL_UP;

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

            // === D-9 + D-11/D-12 (original L2047-L2111) — Up && vanilla branch if/else structure ===
            //   F-1 audit correction (Session 21): Original is if(Up && vanilla) {...} else { angle/scale }
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
                // === D-11 (original L2081-L2095) — angle != null branch ===
                if (angle != null) {
                    // Original RadiantToAngle = 180 / PI ≈ 57.2957795
                    float jumpAngleRad = angle / 57.295776F;
                    boolean reset = type == SmartMovingConfig.JUMP_TYPE_WALL_UP;
                    double horizontal = Math.max(horizontalMotion, horizontalJumpFactor);
                    double moveX = -Math.sin(jumpAngleRad);
                    double moveZ =  Math.cos(jumpAngleRad);
                    if (type == SmartMovingConfig.JUMP_TYPE_ANGLE) {
                        // Dodge: capped so repeated dodges cannot stack speed (see getDodgeMotion).
                        Vec3 dodge = getDodgeMotion(sm.jumpMotionX, sm.jumpMotionZ, moveX, moveZ,
                                horizontalJumpFactor);
                        motionX = dodge.x;
                        motionZ = dodge.z;
                    } else {
                        motionX = getJumpMoving(sm.jumpMotionX, moveX, reset, horizontal, horizontalJumpFactor);
                        motionZ = getJumpMoving(sm.jumpMotionZ, moveZ, reset, horizontal, horizontalJumpFactor);
                    }
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

            // === D-16 (original L2131-L2134) — setVelocity + isJumping ===
            // Windrunner Boots raise every SmartMoving jump by their jump strength, as vanilla's would be.
            player.setDeltaMovement(motionX, noVertical ? vel.y : WindrunnerControls.jumpVelocity(player, motionY), motionZ);
            // sp.isAirBorne = true — automatic vanilla (velocityDirty + fallDistance)
            sm.isJumping = true;
            // onLivingJump() — automatic vanilla PlayerEntity jump event handling
        }

        // === D-18 removed (Phase C 1:1 correction) — separated caller handling restores original 1:1 ===
        //   Previous: tryJump end sets jumpCharge=0 / blockJumpTillButtonRelease=true /
        //         jumpPending=false all together. Original handles separately in caller (handleJumping charge
        //         release branch) — other call sites (wall/climb/angle/slide/water/CreativeFlying)
        //         don't set.
        //   Side effect (before removal): blockJumpTillButtonRelease=true set after every tryJump →
        //         wall/climb back/angle jump then jump key hold regular jump guard fails → difference.
        //   After restoration: matches original behavior exactly. handleJumping charge branch caller resets
        //         already correctly mapped.
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
     *   6. Water jump (original L1901-L1913) — jumpKey hold + isDipping
     *   7. Normal jump (original L1915-L1916) — jumpAvoided + !isVineAnyClimbing
     *   8. Directional dodge — dedicated dodge key only (A/D/S double-tap removed)
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
                    float chargeStep = (movingSneakCharge ? 0.5F : 1F) * WindrunnerControls.chargeRate(player);
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

        // 6. Water jump (original L1901-L1913) ★ B-3 1:1 correction — jumpKey hold trigger
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

        // 7. Normal jump (original L1915-L1916) ★ B-3 1:1 correction — add !isVineAnyClimbing
        if (jump && !sm.blockJumpTillButtonRelease && !isJumpCharging
                && !sm.isVineAnyClimbing) {
            tryJump(player, sm, UP, Boolean.FALSE, null, null);
        }

        // 8. Dedicated dodge key (replaces original L1918-L1943 A/D/S double-tap)
        if (sm.dodgeJustPressed) {
            Minecraft mc = Minecraft.getInstance();
            int forward = (mc.options.keyUp.isDown() ? 1 : 0)
                    - (mc.options.keyDown.isDown() ? 1 : 0);
            int right = (mc.options.keyRight.isDown() ? 1 : 0)
                    - (mc.options.keyLeft.isDown() ? 1 : 0);
            if (player.onGround()) {
                tryKeyboundDodge(player, sm, cfg, forward, right);
            }
        }
    }

    /**
     * Trigger bound dodge key with current WASD direction. Does not activate without directional input,
     * or while the previous dodge's cooldown (`angleJumpCooldownTicks` ground ticks) is running.
     */
    private static boolean tryKeyboundDodge(LocalPlayer player, SmartMovingClientState sm,
                                    SmartMovingConfig cfg, int forward, int right) {
        // With the Windrunner Headband on, its own ground dash fires from this press instead.
        if (WindrunnerControls.overridesDodge(player)) return false;
        if (forward == 0 && right == 0) return false;
        if (sm.dodgeCooldown > 0) return false;
        if (player.isSleeping() || !player.onGround()
                || sm.isCrawling || sm.isClimbing || sm.isClimbCrawling
                || sm.isCrawlClimbing || sm.isCeilingClimbing
                || sm.isSwimming_sm || sm.isDiving || sm.isSliding
                || sm.isFlying
                || sm.isChainClimbing) {
            return false;
        }

        if (forward > 0 && !cfg.angleJumpForward) return false;
        if (forward < 0 && !cfg.angleJumpBack) return false;
        if (right != 0 && !cfg.angleJumpSide) return false;

        int relAngle = Math.round((float) Math.toDegrees(Math.atan2(right, forward)) / 45F) * 45;
        if (relAngle < 0) relAngle += 360;
        if (!tryAngleJump(player, sm, relAngle)) return false;
        sm.dodgeCooldown = (int) Math.ceil(cfg.angleJumpCooldownTicks);
        return true;
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
        sm.startAngleJump(player.tickCount, worldAngleDeg);
        return true;
    }

    // ── [10-5] Wall jump state update ─────────────────────────────────────────────

    /**
     * Update wall jump state every tick (canWallJumping / wallJumpCount / triggerWallJumping /
     * wantWallJumping / continueWallJumping).
     *
     * Original: SmartMovingSelf (L2863-2897) + jump.md L629-668 1:1 port.
     *
     *   canWallJumping = isWallJumpEnabled && !onGround
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

        // 🔴 fix #157 (2026-05-27): Restore original L2865-L2896 order 1:1.
        //   Previous: wantWallJumping calculation → continueWallJumping=false set order.
        //   Original: continueWallJumping=false set → wantWallJumping calculation order.
        //   Reversed order BUG: At jumpKey release, wantWallJumping formula right side continueWallJumping=true
        //   persists → wantWallJumping stays true each tick → handleWallJumping fires each tick → successful tryJump
        //   sets continueWallJumping=true again → **permanent self-sustaining cycle** (= user report
        //   "can't escape wall jump even after releasing jump").

        // 1. (Original L2865-L2866) continueWallJumping false transition — first
        boolean jumpPressed = Minecraft.getInstance().options.keyJump.isDown();
        if (sm.continueWallJumping && (player.onGround() || sm.isClimbing || !jumpPressed)) {
            sm.continueWallJumping = false;
        }

        // 2. (Original L2868) canWallJumping
        boolean isWallJumpEnabled = cfg.wallUpJump;
        boolean canWallJumping = isWallJumpEnabled && !player.onGround()
                && !sm.isClimbing && !sm.isSwimming_sm && !sm.isDiving
                && !sm.isLevitating && !sm.isFlying;

        // 3. (Original L2869-L2892) double click processing
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

        // 4. (Original L2894-L2896) wantWallJumping — last
        // Self-referential pattern: Read previous tick wantWallJumping, maintain if jumpPressed && !collided.
        sm.wantWallJumping = canWallJumping &&
                (sm.triggerWallJumping || sm.continueWallJumping ||
                 (sm.wantWallJumping && jumpPressed && !player.horizontalCollision));
    }

    // ── [10-5] handleWallJumping ─────────────────────────────────────────────

    /**
     * Handle wall jump. Called before climbing handling inside sm_travel_client().
     * Original: SmartMovingSelf.handleWallJumping() L1946-1997.
     *
     * Original entry condition (L1450): `if (!wantWallJumping || NaN(horizontalCollisionAngle)) return;`
     * → wantWallJumping is value updated in updateWallJumpState.
     *
     * Reflection angle formula: reflectedAngle = horizontalCollisionAngle * 2 - movementAngle + 180
     * jumpAngle = round(reflectedAngle / 90) * 90  (90° unit rounding)
     */
    public static void handleWallJumping(LocalPlayer player, SmartMovingClientState sm) {
        SmartMovingConfig cfg = SmartMovingConfig.Config;

        // Original L1948: `if (!wantWallJumping || Double.isNaN(horizontalCollisionAngle)) return;`
        // 🔴 fix #157 (2026-05-27): Restore NaN guard — user report "triggers on flat ground even without wall
        //   (= spinning + rising)". Previous calculateSeparateCollisionAngle NaN fallback (=
        //   returns movementAngle) disabled original NaN guard. In air (= all 4-axis collisions false) →
        //   getHorizontalCollisionangle NaN → original immediately return. Mapping: NaN → fallback
        //   movementAngle → handleWallJumping proceeds → in-air wall jump triggers.
        if (!sm.wantWallJumping) return;
        // A Windrunner wall run turns the same Jump press into its own kick off the wall.
        if (WindrunnerWallRun.running()) return;
        float horizontalCollisionAngle = calculateSeparateCollisionAngle(player);
        if (Float.isNaN(horizontalCollisionAngle)) return;

        // 🔴 fix #162 (2026-05-27): Force wasCollidedHorizontally=false.
        //   Detailed dump analysis (docs/log_temp.txt t=1057~1063):
        //     User scenario (W hold + jump repeat between two walls) each tick wasColl=true matches →
        //     jumpType=WALL_UP_SLIDE (=13) + jumpAngle=horizontalCollisionAngle (= wall normal) →
        //     setYaw(normal) = user facing = same direction → no visual rotation. WALL_UP_SLIDE → noVertical=true
        //     → motionY not applied → no rise.
        //   Original vs our mapping timing difference:
        //     Original 1.7.10 handleWallJumping = called after moveEntity + L1993 set false → same tick
        //     no additional vanilla move() call → next tick wasColl=false maintained → rotation branch matches.
        //     Our mapping handleWallJumping = travel HEAD → L700 set false → same tick vanilla
        //     travel body → vanilla move() → horizontalCollision becomes true again → next tick wasColl=true
        //     → slide branch matches → no rotation.
        //   Solution: Force wasCollidedHorizontally=false. User verbatim "body rotates opposite direction on wall jump
        //     + front wall back wall front wall back wall repeat" matches. Original spec WALL_UP_SLIDE branch (= no vertical
        //     when box sliding against wall) difficult to match in 1.21.1 mapping — user intent takes priority.
        boolean wasCollH = false;

        // Original L1952-L1963.
        if (!cfg.wallUpJump) return;
        if (player.fallDistance > cfg.wallUpJumpFallMaximumDistance) return;
        int jumpType = wasCollH ? WALL_UP_SLIDE : WALL_UP;

        // Original L1965-L1975: wasCollidedHorizontally=false → reflection angle; true → wall normal angle as-is.
        // 🔴 fix #164 (2026-05-27): jumpMotion=0 fallback movementAngle = player.getYaw().
        //   Detailed dump analysis (docs/log_temp.txt scenario A trigger 1/3 t=2109/2401):
        //     Box -X wall match + yaw=90 + jumpMotion=0 → fix #163 formula (= movementAngle=hCA=270) →
        //     jumpAngle = 270*2 - 270 + 180 = 90. setYaw(90) = no change. User report "forward direction or
        //     only 90° off direction" matches.
        //   Analysis:
        //     W hold + aiming box wall → vel direction = yaw forward direction. i.e. vel direction
        //     atan2 result = vanilla yaw coordinate system yaw. jumpMotion=0 loses vel direction info →
        //     fallback must use player.getYaw() for consistency.
        //   Verification (all 4-axis match cases):
        //     +Z wall (yaw=0): jumpAngle = 0-0+180 = 180 ✓
        //     -Z wall (yaw=180): jumpAngle = 360-180+180 = 360 mod 360 = 0 ✓
        //     +X wall (yaw=270): jumpAngle = 180-270+180 = 90 ✓ (wall normal yaw=270 → opposite +X→-X)
        //     -X wall (yaw=90): jumpAngle = 540-90+180 = 270 ✓
        //   = All 180° rotation (= opposite wall direction). User intent matches.
        // 🔴 fix #168 (2026-05-27): Simplify jumpAngle formula — block vanilla collision clamp effect.
        //   Dump analysis (t=2774): Box +Z wall match + user yaw=15° + jumpMotion=(-0.022, 0) (=
        //   vanilla collision clamp only clamps vel.z, vel.x persists). Formula movementAngle =
        //   atan2(0.022, 0) = 90° → jumpAngle = 0*2 - 90 + 180 = **90°** → setYaw(90) → -X direction
        //   (= 90° rotation parallel to wall). User intent = -Z direction (= opposite user facing).
        //   Original formula has same mechanism — vel.x persists causes movementAngle ±90° → jumpAngle parallel to wall.
        //   Solution:
        //     1. Use original formula only when jumpMotion large enough (= user intent strafe).
        //     2. When jumpMotion tiny (= vanilla clamp persists) fallback: jumpAngle = yaw + 180
        //        (= opposite user facing) set directly. Bypass formula.
        //   threshold = 0.05 (= about half of W input motion). Covers sub-0.02 residual after vanilla clamp.
        //   User strafe (0.1+) case unaffected.
        float jumpAngle;
        if (!wasCollH) {
            double jmMag = Math.abs(sm.jumpMotionX) + Math.abs(sm.jumpMotionZ);
            if (jmMag < 0.05D) {
                // fallback: opposite user facing direction (= yaw + 180). Block tiny jumpMotion effect.
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

        // Phase G BUG-1 1:1 correction: Apply while>360 + orthogonalTolerance outside branch (original L1977-L1988).
        //   Previous: Only applied inside !wasCollidedHorizontally branch → when wasCollidedHorizontally=true (Slide type)
        //         90° alignment not applied → inaccurate jump angle. Original applies both.
        // 🔴 fix #167 (2026-05-27): Normalize jumpAngle range to 0~360. Original formula `while > 360` only → doesn't handle
        //   negative or exactly 360.0 case. setYaw(360.0) → vanilla yaw field stays 360.0 (no mod)
        //   → yaw accumulates change each tick → inventory player model rotation oscillation/distortion.
        //   Solution: Add while < 0 + handle exact 360 case. jumpAngle always in [0, 360) range.
        while (jumpAngle > 360F) jumpAngle -= 360F;
        while (jumpAngle < 0F) jumpAngle += 360F;
        if (jumpAngle >= 360F) jumpAngle = 0F;
        if (cfg.wallUpJumpOrthogonalTolerance != 0F) {
            float aligned = jumpAngle;
            while (aligned > 45F) aligned -= 90F;
            if (Math.abs(aligned) < cfg.wallUpJumpOrthogonalTolerance)
                jumpAngle = Math.round(jumpAngle / 90F) * 90F;
        }

        // Phase G difference 2/3 1:1 correction: tryJump result if branch + post-processing only on success (original L1990-L1996).
        //   Previous: Ignored tryJump result + double set isWallJumping + post-processing before tryJump call.
        if (tryJump(player, sm, jumpType, null, null, jumpAngle)) {
            // Original L1992.
            sm.continueWallJumping = true;
            // Original L1993: sp.isCollidedHorizontally = false
            player.horizontalCollision = false;
            // Original L1994: sp.rotationYaw = jumpAngle
            player.setYRot(jumpAngle);
            player.yBodyRot = jumpAngle;       // (Phase G difference 4 deferred — maintain rotation sync intent)
            // Original L1995 onStartWallJump(jumpAngle) inline:
            //   - prev rotateAngleY = angle / RadiantToAngle  (Phase G difference 5 deferred — animation cycle)
            sm.isWallJumping = true;          // Original onStartWallJump (SmartMoving.java L154)
            player.fallDistance = 0F;          // Original onStartWallJump (SmartMoving.java L155)
        }
    }

    /**
     * Detect 4-axis AABB collision then return wall normal angle.
     * Original: SmartMovingSelf.calculateSeparateCollisions() + getHorizontalCollisionangle() call.
     *
     * Original call site: getHorizontalCollisionangle(posZ, negZ, posX, negX) — maintain X/Z swap.
     */
    private static float calculateSeparateCollisionAngle(LocalPlayer player) {
        Level world = player.level();
        AABB bb = player.getBoundingBox();
        double delta = 0.001D;
        boolean posX = !world.noCollision(player, bb.move( delta, 0, 0));
        boolean negX = !world.noCollision(player, bb.move(-delta, 0, 0));
        boolean posZ = !world.noCollision(player, bb.move(0, 0,  delta));
        boolean negZ = !world.noCollision(player, bb.move(0, 0, -delta));
        // 🔴 fix #165 revert (2026-05-27): Remove corner case NaN skip.
        //   User verbatim (restate): "Diagonal wall corner should do normal wall jump but isn't".
        //   Original helper spec = corner case returns corner angle (45/135/225/315) → normal wall jump
        //   triggers. Corner case should also trigger user intended wall jump.
        return getHorizontalCollisionangle(posZ, negZ, posX, negX);
    }

    /**
     * 4-axis collision combination → wall normal angle (degrees) conversion.
     * Original: SmartRenderUtilities.getHorizontalCollisionangle() — A-23 verified.
     *
     * Note: Original call site (SmartMovingSelf.beforeMoveEntity) X/Z parameters are swapped.
     * Maintain same order here: (posZ, negZ, posX, negX) → (param1, param2, param3, param4)
     *
     * Single axis collision results: +Z→0° (south wall), -Z→180° (north wall), +X→90° (east wall), -X→270° (west wall)
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
