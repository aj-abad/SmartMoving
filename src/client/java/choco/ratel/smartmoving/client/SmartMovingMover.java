package choco.ratel.smartmoving.client;

import choco.ratel.smartmoving.config.SmartMovingConfig;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Client-side movement speed factor calculation.
 * Original: SmartMovingSelf.getConfigSpeedFactor / getPotionSpeedFactor /
 *           getNonSlowInputSpeedFactor / getSlowInputSpeedFactor ported.
 *
 * Included items:
 *   11-1: Speed factor 4-stage structure
 */
@Environment(EnvType.CLIENT)
public final class SmartMovingMover {

    private SmartMovingMover() {}

    // ── [11-1] Speed Factor 4-Stage ─────────────────────────────────────────

    /**
     * Config-based speed multiplier.
     * Original `SmartMovingSelf.getConfigSpeedFactor()` (L154-157):
     *   `Config.enabled ? Config._speedFactor.value * Config.getUserSpeedFactor() : 1F`
     * This port has no user speed keys (the original's `getUserSpeedFactor()` term), so only the
     * global `speedFactor` applies.
     *
     * B-1 (session 25): Added `cfg.enabled` guard.
     */
    public static float getConfigSpeedFactor(SmartMovingConfig cfg) {
        if (!cfg.enabled) return 1F;
        return cfg.speedFactor;
    }

    /**
     * Potion effect-inclusive movement speed inverse multiplier.
     * Original `SmartMovingSelf.getPotionSpeedFactor()` (L160-162) 1:1:
     *   `Config.enabled ? getLandMovementFactor() * 10F / (sp.isSprinting() ? 1.3F : 1F) : 1F`
     * B-1 (session 25): Added `cfg.enabled` guard.
     *
     * Potion effects are already reflected in the GENERIC_MOVEMENT_SPEED attribute → beware of double application.
     * Sprint modifier (+0.3) is also included, so divide by 1.3F during sprinting to remove it.
     */
    public static float getPotionSpeedFactor(LocalPlayer player) {
        if (!SmartMovingConfig.Config.enabled) return 1F;
        // 🔴 fix #44 (2026-05-09, user report "Fox movement distance insufficient + Jump Boost pronounced"):
        //   vanilla 1.21.1's ADD_MULTIPLIED_TOTAL operation sums all modifier values then
        //   multiplies once. vanilla 1.7.10's MULTIPLY_TOTAL (op2) multiplies cumulatively per modifier.
        //   → Sprint + Speed LV5: 1.7.10 = 0.1×1.3×2.0=0.26, 1.21.1 attribute = 0.1×(1+0.3+1.0)=0.23.
        //   → 11.5% less → maxHorizontalMotion smaller → shorter distance (= user perceives "pronounced").
        //   Solution: bypass vanilla attribute formula + direct calculation using 1.7.10 formula (= base × sprint × speed × slow).
        //   Read docs/research_fox_movement_distance.md §12 carefully.
        float base = 0.1F;
        float sprintMul = player.isSprinting() ? 1.3F : 1F;
        net.minecraft.world.effect.MobEffectInstance speed =
                player.getEffect(net.minecraft.world.effect.MobEffects.SPEED);
        net.minecraft.world.effect.MobEffectInstance slow  =
                player.getEffect(net.minecraft.world.effect.MobEffects.SLOWNESS);
        float speedMul = (speed != null) ? 1F + (speed.getAmplifier() + 1) * 0.2F : 1F;
        float slowMul  = (slow  != null) ? 1F + (slow.getAmplifier()  + 1) * (-0.15F) : 1F;
        float landMovementFactor = base * sprintMul * speedMul * slowMul;
        return landMovementFactor * 10F / sprintMul;
    }

    /**
     * Original `SmartMovingSelf.getCombinedSpeedFactor()` (L149-152) 1:1:
     *   `return getConfigSpeedFactor() * getPotionSpeedFactor();`
     * B-1 (session 25) new addition — replaces inline multiplication in 6 places like Climber/Jumper.
     */
    public static float getCombinedSpeedFactor(LocalPlayer player, SmartMovingConfig cfg) {
        return getConfigSpeedFactor(cfg) * getPotionSpeedFactor(player);
    }

    /**
     * Non-slow input multiplier (ice/sprint/running).
     * Factor application priority: ice > sprint > running (no nesting).
     *
     * isRunning = isSprinting() && !isFast (verified value A-21).
     * iceSpeedFactor: not in original config — fixed at 1.5F (verified A-25).
     */
    public static float getNonSlowInputSpeedFactor(LocalPlayer player, SmartMovingClientState sm,
                                                    SmartMovingConfig cfg) {
        // 🔴 (2026-04-28) Original SmartMovingSelf L197-227 precise 1:1 mapping.
        //   Original: isFast returns sprintFactor (1.5). !isFast returns 1.0.
        //   Separate in landMotion L712: isRunning && !isFast multiplies runFactor (1.3).
        float speedFactor = 1.0F;
        if (sm.isFast) {
            // Original: !isLevitating ? sprintFactor : sprintFactorLevitate. Levitate handled separately in SmartMovingFlyer.
            speedFactor *= cfg.sprintFactor;  // 1.5
        }
        // 🔴 Original SmartMovingSelf L203-205: multiply by _freeClimbingHorizontalSpeedFactor when isClimbing.
        //   Original guard `if(moveStrafing != 0F || moveForward != 0F)` requires movementInput info.
        //   Our caller's climbSpeedFactor affects motion only via updateVelocity(speed, movementInput) parameter
        //   → when input is 0, multiplication result is irrelevant. Guard omission has same effect.
        //   User report #2/#5: this multiplication missing in our code → fence/iron_bars climb/strafe speed differs from original.
        if (sm.isClimbing) {
            speedFactor *= cfg.freeClimbingHorizontalSpeedFactor;
        }
        return speedFactor;
    }

    /**
     * Slow input multiplier (crawling/sneaking/ceiling climbing).
     * Factors are applied as cumulative multiplication in order.
     *
     * 🔴 fix (third-party gun mod compatibility, 2026-09-13): removed item-use clause.
     *
     * Previously, item use slowdown was self-hardcoded here with `if (player.isUsingItem()) f *= 0.2F`.
     * This is the same type of double slowdown bug as "BUG-sneak speed" above (see MixinClientPlayerEntity):
     * vanilla `LocalPlayer.modifyInput()` already multiplies item's actual `DataComponents.USE_EFFECTS`
     * → `speedMultiplier()` to xxa/zza when `isUsingItem()` (LivingEntity.aiStep: L3055 applyInput
     * → L3101 movementInput construction → L3109 travel() call), and that movementInput passes directly
     * through sm_travel_client's HEAD to handleLand/climbing/swimming. Multiplying hardcoded 0.2F again here causes:
     *   1. Double application — vanilla portion × SM portion (for default items 0.2×0.2=0.04, i.e., far more
     *      slowdown than expected when drawing vanilla bow).
     *   2. Ignores per-item actual multiplier — all items treated uniformly as 0.2x. When gun mods
     *      register different per-item `speedMultiplier` (e.g., 0.8/0.9/1.0 — 1.0 intended as
     *      "no slowdown while aiming"), that value is completely ignored and all uniformly over-slowed
     *      (items that should have no slowdown are locked at 0.2x).
     *
     * Sneak is blocked via vanilla `isMovingSlowly()` itself with sm_blockVanillaSlowDown to prevent
     * double application, but item-use slowdown is a separate vanilla branch unrelated to `isMovingSlowly()`
     * so that block doesn't apply — therefore, instead of blocking vanilla side, remove SM's reapplication
     * to leave only vanilla calculation (already reflected in movementInput).
     */
    public static float getSlowInputSpeedFactor(LocalPlayer player, SmartMovingClientState sm,
                                                 SmartMovingConfig cfg) {
        float f = 1.0F;
        if (sm.isCrawling) f *= cfg.crawlFactor;
        else if (player.isShiftKeyDown()) f *= cfg.sneakFactor;
        if (sm.isCeilingClimbing) f *= cfg.ceilingClimbingSpeedFactor;
        return f;
    }

    /**
     * Final speed factor = 4-stage multiplication.
     * Call location: in each handle*() method within travel() SM pipeline when calculating speedFactor.
     */
    public static float getSpeedFactor(LocalPlayer player, SmartMovingClientState sm,
                                        SmartMovingConfig cfg) {
        return getConfigSpeedFactor(cfg)
             * getPotionSpeedFactor(player)
             * getNonSlowInputSpeedFactor(player, sm, cfg)
             * getSlowInputSpeedFactor(player, sm, cfg);
    }

    // ── Constants (original SmartMovingContext.java) ──────────────────────
    private static final float HORIZONTAL_GROUND_DAMPING       = 0.546F;
    private static final float HORIZONTAL_AIR_DAMPING          = 0.91F;

    // ── isRunning (original SmartMovingSelf.isRunning) ───────────────────
    /**
     * Original `SmartMovingSelf.isRunning()` (L3239-3242) 1:1:
     *   `return sp.isSprinting() && !isFast && (sp.onGround || vanilla())`.
     *
     * cfg.run check is performed separately by the caller (handleLand L712) — same as original.
     */
    public static boolean isRunning(LocalPlayer player, SmartMovingClientState sm,
                                     SmartMovingConfig cfg) {
        return player.isSprinting() && !sm.isFast && (player.onGround() || sm.vanilla());
    }

    // ── 11-2: handleLand (original superMoveEntityWithHeading + landMotion + setLandMotions) ──

    /**
     * 🔴 (2026-04-28) Original SmartMovingSelf.superMoveEntityWithHeading + landMotion +
     *   setLandMotions complete 1:1 mapping.
     *
     * Original flow (`SmartMovingSelf.handleLand` L633-663):
     *   1. landMotion (L675-810): determine horizontalDamping + moveFlying (movementInput → motion).
     *   2. move (L655): call vanilla move().
     *   3. handleClimbing (L657): adjust motionY (called in separate branch).
     *   4. setLandMotions (L1176-1182): motionY -= 0.08, *= 0.98, motionX/Z *= damping.
     *
     * Same pattern as flying — cancel vanilla travel entirely + apply SM's own formula.
     * @return true = SM processing complete (target for caller's ci.cancel).
     */
    public static boolean handleLand(LocalPlayer player, SmartMovingClientState sm,
                                      Vec3 movementInput) {
        SmartMovingConfig cfg = SmartMovingConfig.Config;
        if (!cfg.enabled) return false;

        float moveStrafing = (float) movementInput.x;
        float moveForward  = (float) movementInput.z;

        // Original superMoveEntityWithHeading L119-130: speedFactor calculation.
        float speedFactor = getConfigSpeedFactor(cfg)
                * getPotionSpeedFactor(player)
                * getNonSlowInputSpeedFactor(player, sm, cfg);
        float slowFactor = getSlowInputSpeedFactor(player, sm, cfg);
        if (sm.vanilla()) {
            moveForward  *= slowFactor;
            moveStrafing *= slowFactor;
        } else {
            speedFactor *= slowFactor;
        }

        // Original landMotion L677-690: determine horizontalDamping + L686-687 sprintJumpVertical.
        float horizontalDamping;
        if (player.onGround() && (!sm.isJumping || sm.vanilla())) {
            BlockState below = player.level().getBlockState(player.getOnPos());
            float slip = below.getBlock().getFriction();
            horizontalDamping = slip > 0F ? slip * HORIZONTAL_AIR_DAMPING
                                          : HORIZONTAL_GROUND_DAMPING;
            // Original L686-687: apply sprintJumpVerticalFactor when jump key held + isFast.
            boolean jumpKeyPressed = Minecraft.getInstance().options.keyJump.isDown();
            if (jumpKeyPressed && sm.isFast) {
                speedFactor *= cfg.sprintJumpVerticalFactor;
            }
        } else {
            horizontalDamping = HORIZONTAL_AIR_DAMPING;
        }

        // Original landMotion L703-706: jump control factor.
        // Phase F 1:1 correction: explicitly added original L705 `Config.enabled && ...` guard (cosmetic — function start
        //   already has `if (!cfg.enabled) return false` guard at function level, same behavior).
        if (cfg.enabled && !player.onGround()
                && !player.getAbilities().flying && !sm.isFlying) {
            speedFactor *= cfg.jumpControlFactor;
        }

        // Original landMotion L708-709: rawSpeed.
        float f3 = 0.1627714F / (horizontalDamping * horizontalDamping * horizontalDamping);
        float rawSpeed;
        if (player.onGround()) {
            // 🔴 fix #50 (2026-05-10, user precise 100m measurement + 1.12.2 SMReboot code line-by-line comparison):
            //   sm_original 1.7.10 SmartMovingSelf L709: rawSpeed = 0.1F × f3 (base hardcode).
            //   1.12.2 SMReboot SMSelf.java L682:        rawSpeed = getLandMovementFactor() × f3.
            //   = vanilla bl() = attribute getValue() = value with SPEED multiplier reflected.
            //
            //   User measurement environment verification:
            //   - vanilla SPEED LV3 sprint+W: 1.7.10=11.06s / 1.21.1=11.11s (= matches vanilla formula).
            //   - SM SPEED LV3 sprint+W:      1.7.10/1.12.2=5.96s / 1.21.1 ours=9.70s.
            //   - When applying 1.12.2 SMReboot formula: rawSpeed=0.208×1.0/1.3=0.16, speedFactor=2.4,
            //     ADD=0.376, preMove=0.829, 100m=6.03s ↔ measured 5.96s matches (error ±0.07s).
            //
            //   sm_original's 0.1F hardcode formula = incorrect reference (= based on different release/fork).
            //   User 1.7.10/1.12.2 measurement environment = 1.12.2 SMReboot formula = SPEED multiplier
            //   applies to both rawSpeed and speedFactor (getPotionSpeedFactor) (= double-apply intended).
            //
            //   Change: 0.1F → player.getMovementSpeed() (= vanilla 1.21.1 attribute = 0.208).
            rawSpeed = player.getSpeed() * f3;
            // 🔴 fix #50: add sprint reduction from 1.12.2 SMReboot L683 (ground branch only).
            //   Original: float rawSpeed = sp.isSprinting() ? f4 / 1.3F : f4;
            //   Air branch keeps 0.02F hardcode from fix #45 (= 1.7.10 vanilla sprint result formula 1:1).
            if (player.isSprinting()) rawSpeed /= 1.3F;
        } else {
            // 🔴 fix #45 (2026-05-09, user report "even basic fox movement differs" / "speed sprint speed differs"):
            //   Original SmartMovingSelf L709: rawSpeed = jumpMovementFactor / (sprint ? 1.3 : 1).
            //   vanilla 1.7.10 EntityPlayer.onLivingUpdate sets jumpMovementFactor to
            //   0.02 + 0.02×0.3 = 0.026 on sprint → SM normalizes by dividing by 1.3 → always results 0.02.
            //   vanilla 1.21.1 has no jumpMovementFactor field, so we hardcoded 0.02 then
            //   divide by 1.3 on sprint yielding 0.01538 (= 23% less) → insufficient air acceleration → shorter jump distance.
            //   Solution: always 0.02 same as 1.7.10 result formula. Sprint bonus replaced by SM's own sprintFactor.
            rawSpeed = 0.02F;
        }

        // Original landMotion L712-713: multiply runFactor when isRunning && !isFast.
        // (Original runFactorLevitate used only on levitate — unrelated to land branch, just runFactor)
        if (cfg.run && isRunning(player, sm, cfg) && !sm.isFast) {
            speedFactor *= cfg.runFactor;
        }

        // 🔴 fix #51 (2026-05-10, user 1.7.10/1.12.2 SmartMoving measurement + code line-by-line comparison):
        //   1.7.10 sm_original SmartMovingSelf.landMotion L714-716:
        //     if (!sp.onGround) speedFactor /= getPotionSpeedFactor();
        //   1.12.2 SMReboot SMSelf.landMotion: this formula itself is absent.
        //   User measurement environment = 1.7.10 + 1.12.2 Forge SmartMoving = 1.12.2 SMReboot formula.
        //   → Remove branch. (= the 1.7.10 sm_original our mapping followed is incorrect reference).
        //
        //   Impact: SPEED multiplier applies to air acceleration while airborne.
        //   - SPEED LV3 (= 1.6 multiplier): air ADD increases 1.62x.
        //   - SPEED LV5 (= 2.0 multiplier): air ADD increases 2.0x.
        //   - SPEED LV10 (= 3.0 multiplier): air ADD increases 3.0x.
        //   Intent 1:1 with fix #50 (ground rawSpeed): 1.12.2 SMReboot formula 1:1 mapping where
        //   SPEED multiplier applies to both ground/air.
        //
        //   User report "pronounced distance difference with Jump Boost (= SPEED) series" + "fox
        //   distance 1.7.10/1.12.2 goes further" report exactly matches.

        // Original L718: moveFlying(strafe, forward, rawSpeed * speedFactor) — ADD to motion.
        applyLandMoveFlying(player, moveStrafing, moveForward, rawSpeed * speedFactor);

        // Original L655: vanilla move() — update position.
        player.move(MoverType.SELF, player.getDeltaMovement());

        // Original setLandMotions L1176-1182: motionY -= 0.08, *= 0.98, motionX/Z *= damping.
        Vec3 vel = player.getDeltaMovement();
        double newY = (vel.y - 0.08D) * 0.98D;
        double newX = vel.x * horizontalDamping;
        double newZ = vel.z * horizontalDamping;

        // vanilla LivingEntity.travel's small motion 0 clamp at end (= cleanup residual motion).
        if (Math.abs(newX) < 0.003D) newX = 0.0D;
        if (Math.abs(newY) < 0.003D) newY = 0.0D;
        if (Math.abs(newZ) < 0.003D) newZ = 0.0D;

        player.setDeltaMovement(newX, newY, newZ);

        return true;
    }

    /**
     * 🔴 fix #49 (2026-05-09, user report "speed sprint velocity differs from original"):
     *   Original SmartMovingSelf.landMotion L718 `sp.moveFlying(strafe, forward, rawSpeed*speedFactor)`
     *   calls **3-argument vanilla EntityLivingBase.moveFlying**. Different from `SmartMovingBase.moveFlying`
     *   5-argument version (= sqrt(sqrt) normalization).
     *
     *   vanilla 1.7.10 EntityLivingBase.moveFlying formula (= land branch 1:1):
     *     f3 = strafe² + forward²;
     *     if (f3 >= 0.0001F) {
     *         f3 = sqrt(f3); if (f3 < 1.0F) f3 = 1.0F;
     *         f3 = speed / f3;
     *         strafe *= f3; forward *= f3;
     *         sin = sin(yaw); cos = cos(yaw);
     *         motionX += strafe*cos - forward*sin;
     *         motionZ += forward*cos + strafe*sin;
     *     }
     *
     *   Our previous mapping had additional `total2 = sqrt(sqrt(diffX²+diffZ²))` normalization → when only W pressed,
     *   motion ADD = `speed × 0.98 / sqrt(0.98) ≈ speed × 0.99` (= 1% larger). Cumulates each tick
     *   → user report "speed sprint velocity differs". Fix #49 = corrected to simple ADD.
     */
    private static void applyLandMoveFlying(LocalPlayer player,
                                             float moveStrafing, float moveForward, float speed) {
        float f3 = moveStrafing * moveStrafing + moveForward * moveForward;
        if (f3 < 0.0001F) return;
        f3 = Mth.sqrt(f3);
        if (f3 < 1.0F) f3 = 1.0F;
        f3 = speed / f3;
        moveStrafing *= f3;
        moveForward  *= f3;

        float yawRad = player.getYRot() * (float) Math.PI / 180F;
        float sin = Mth.sin(yawRad);
        float cos = Mth.cos(yawRad);

        Vec3 vel = player.getDeltaMovement();
        player.setDeltaMovement(
                vel.x + moveStrafing * cos - moveForward * sin,
                vel.y,
                vel.z + moveForward * cos + moveStrafing * sin);
    }
}
