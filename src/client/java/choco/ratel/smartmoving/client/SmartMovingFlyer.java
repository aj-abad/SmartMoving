package choco.ratel.smartmoving.client;

import choco.ratel.smartmoving.config.SmartMovingConfig;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.phys.Vec3;

/**
 * SM flying physics — handleAlternativeFlying() ported.
 *
 * Original: SmartMovingSelf.handleAlternativeFlying() (lines 602-631)
 *           + SmartMovingBase.moveFlying() (lines 56-93)
 *
 * Activation condition: sm.isFlying && cfg.fly (= capabilities.isFlying && Config.isFlyingEnabled())
 * Processing:
 *   1. sneak(descend): motionY += 0.15D, moveUpward -= 0.98F
 *   2. jump(ascend): motionY -= 0.15D, moveUpward += 0.98F
 *   3. moveFlying(): yaw-based horizontal + pitch-based vertical (when treeDimensional=true) velocity summed
 *   4. damping: motionX/Y/Z * HorizontalAirDamping(0.91F)
 */
public class SmartMovingFlyer {

    /**
     * Apply SM flying physics.
     *
     * @param jumping LivingEntity.jumping field (whether Space key is held)
     * @return true → SM handles it, caller should ci.cancel() + return
     */
    public static boolean handleFlying(LocalPlayer player, SmartMovingClientState sm,
                                       Vec3 movementInput, boolean jumping) {
        SmartMovingConfig cfg = SmartMovingConfig.Config;
        // Original SmartMovingSelf.handleAlternativeFlying L604:
        //   handleAlternativeFlying = !handledSwimming && !handledLava && capabilities.isFlying && Config.isFlyingEnabled()
        //   1.21.1: caller sm_travel_client reached after handleSwimming/handleLava = !handledSwimming
        //   && !handledLava automatically satisfied. sm.isFlying = isFlyingEnabled() && abilities.flying && !swim
        //   && !dive — original L2510 isFlying formula 1:1 mapped. cfg.fly redundant guard (included in sm.isFlying).
        if (!sm.isFlying) return false;

        // Original L607-L608: resetSwimming(); resetClimbing();
        //   1.21.1: sm_travel_client reached after handleSwimming/handleLava = SM swimming branch ci.cancel
        //   not applied only → swimming/lava processing not done = reset meaning identical (already maintained false). Mapping omitted.

        // Original L611-L620: sneak/jump → motionY ± 0.15 + moveUpward ± 0.98 (esp.movementInput direct)
        // 🔴 sneak check correction (user report fix — flying sneak fast descend BUG, 2026-05-03):
        //   Existing `player.isSneaking()` via SM `MixinClientPlayerEntity.sm_isSneaking_ClientPlayer`
        //   inject returns false when flying → branch not entered → vanilla -=0.15 cancel failed + SM moveFlying
        //   not applied + damping x0.91 only → per frame -=0.15 accumulated → converges to -1.5/tick = -30m/sec terrifying descent.
        //   Original SmartMovingSelf L611 = `esp.movementInput.sneak` direct check. Changed to player.input.sneaking
        //   = original 1:1.
        float moveUpward = 0F;
        if (player.input.keyPresses.shift()) {
            Vec3 vel = player.getDeltaMovement();
            player.setDeltaMovement(vel.x, vel.y + 0.14999999999999999D, vel.z);
            moveUpward -= 0.98F;
        }
        if (jumping) {
            Vec3 vel = player.getDeltaMovement();
            player.setDeltaMovement(vel.x, vel.y - 0.14999999999999999D, vel.z);
            moveUpward += 0.98F;
        }

        float moveForward = (float) movementInput.z;
        float moveStrafe  = (float) movementInput.x;

        // 🔴 BUG-25 correction (Flying Phase F-6 / session 40): getNonSlowInputSpeedFactor missing supplement.
        //   Original SmartMovingSelf L119 `speedFactor = getConfigSpeedFactor * getPotionSpeedFactor *
        //   getNonSlowInputSpeedFactor(moveForward, moveStrafing)`.
        //   getNonSlowInputSpeedFactor (L197-L227) body:
        //     if (isFast) speedFactor *= (!isLevitating ? sprintFactor : sprintFactorLevitate);
        //     if (isClimbing) ... (flying irrelevant)
        //   → when flying, sprint key hold + flying possible = 1.5F (sprintFactor) or sprintFactorLevitate (Levitate)
        //   multiplication. 1.21.1 SmartMovingMover.getCombinedSpeedFactor (= getConfigSpeedFactor *
        //   getPotionSpeedFactor) only, NonSlow sprint multiplication missing → flying sprint 1.5x slower.
        //   Solution: Add sprintFactor (or sprintFactorLevitate when isLevitating) multiplication when isFast.
        float combinedFactor = SmartMovingMover.getCombinedSpeedFactor(player, cfg);
        if (sm.isFast) {
            combinedFactor *= sm.isLevitating ? cfg.sprintFactorLevitate : cfg.sprintFactor;
        }
        float flyingSpeed    = combinedFactor * 0.05F * cfg.flyingSpeedFactor;

        // Original: moveFlying(moveUpward, moveStrafing, moveForward, speed, Options._flyControlVertical)
        // 🔴 BUG-27 mandatory correction (Flying Phase / session 50): Remove user environment config file disk dependency.
        //   Original Unmodified default = true (Properties.java L171-L172). Auto-migration (sessions
        //   48/49) may not work (marker already present or different disk location) → ignore cfg value and force
        //   true application → mouse pitch → W progress direction effect (original default 1:1) guaranteed.
        boolean treeDimensional = true;  // Force original default. User option only on code change.
        moveFlying(player, moveUpward, moveStrafe, moveForward, flyingSpeed, treeDimensional);

        // 🔴 BUG-1+8 (session 37): Original SmartMovingSelf.handleAlternativeFlying L624
        //   `sp.moveEntity(sp.motionX, sp.motionY, sp.motionZ)` mapping — **previously missing**.
        //   sm_travel_client blocks vanilla travel() via ci.cancel() → vanilla move() not called
        //   → motion not applied by setVelocity alone = body fixed when flying (BUG-1).
        //   Fix: after motion calculation, explicitly call player.move() → vanilla collision/position update handling.
        Vec3 motion = player.getDeltaMovement();
        player.move(MoverType.SELF, motion);

        // Damping (HorizontalAirDamping = 0.91F) — original L626-L628
        Vec3 velAfterMove = player.getDeltaMovement();
        player.setDeltaMovement(velAfterMove.x * 0.91F, velAfterMove.y * 0.91F, velAfterMove.z * 0.91F);

        return true;
    }

    /**
     * Original: SmartMovingBase.moveFlying(moveUpward, moveStrafing, moveForward, speedFactor, treeDimensional)
     *
     * Steps:
     *   1. Calculate YAW-based horizontal direction vector (input normalization + sin/cos decomposition)
     *   2. pitch-based vertical correction (when treeDimensional=true)
     *   3. Final velocity increment calculation (non-standard normalization: sqrt(sqrt(x²+z²) + y²))
     *   4. Sum into player velocity
     */
    static void moveFlying(LocalPlayer player,
                           float moveUpward, float moveStrafing, float moveForward,
                           float speedFactor, boolean treeDimensional) {
        float diffMotionXStrafing = 0F, diffMotionXForward = 0F;
        float diffMotionZStrafing = 0F, diffMotionZForward = 0F;

        // Step 1: YAW-based horizontal direction vector
        float total = (float) Math.sqrt(moveStrafing * moveStrafing + moveForward * moveForward);
        if (total >= 0.01F) {
            if (total < 1.0F) total = 1.0F;  // Normalization lower bound (original same)
            float moveStrafingFactor = moveStrafing / total;
            float moveForwardFactor  = moveForward  / total;
            float yawRad = (float) Math.toRadians(player.getYRot());
            float sin = (float) Math.sin(yawRad);
            float cos = (float) Math.cos(yawRad);
            diffMotionXStrafing =  moveStrafingFactor * cos;
            diffMotionXForward  = -moveForwardFactor  * sin;
            diffMotionZStrafing =  moveStrafingFactor * sin;
            diffMotionZForward  =  moveForwardFactor  * cos;
        }

        // Step 2: pitch-based vertical correction (when treeDimensional=true pitch → radians)
        // RadiantToAngle = 180/π → rotationPitch / RadiantToAngle = toRadians(pitch)
        float rotation = treeDimensional ? (float) Math.toRadians(player.getXRot()) : 0F;
        float divingHorizontalFactor = (float) Math.cos(rotation);
        // signum(moveForward): when looking forward (forward>0) looking up ascends, looking back inverts
        float divingVerticalFactor   = -(float) Math.sin(rotation) * Math.signum(moveForward);

        // Step 3: Final velocity increment
        float diffMotionX = diffMotionXForward * divingHorizontalFactor + diffMotionXStrafing;
        // diffMotionY: forward horizontal magnitude × vertical factor + sneak/jump movement
        float diffMotionY = (float) Math.sqrt(
                diffMotionXForward * diffMotionXForward + diffMotionZForward * diffMotionZForward
        ) * divingVerticalFactor + moveUpward;
        float diffMotionZ = diffMotionZForward * divingHorizontalFactor + diffMotionZStrafing;

        // Non-standard normalization: sqrt(sqrt(x²+z²) + y²) — original SmartMovingBase same formula
        float totalNorm = (float) Math.sqrt(
                Math.sqrt(diffMotionX * diffMotionX + diffMotionZ * diffMotionZ) + diffMotionY * diffMotionY
        );
        if (totalNorm > 0.01F) {
            float factor = speedFactor / totalNorm;
            Vec3 vel = player.getDeltaMovement();
            player.setDeltaMovement(
                    vel.x + diffMotionX * factor,
                    vel.y + diffMotionY * factor,
                    vel.z + diffMotionZ * factor
            );
        }
    }
}
