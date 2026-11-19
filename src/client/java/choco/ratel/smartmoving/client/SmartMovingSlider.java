package choco.ratel.smartmoving.client;

import choco.ratel.smartmoving.config.SmartMovingConfig;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Client-side sliding physics logic.
 * Original: SmartMovingSelf.landMotion() sliding branch, SmartMoving.spawnParticles() ported.
 *
 * Included items:
 *   9-4: handleSliding() — sliding damping formula + strafe forced rotation + travel() replacement
 *   9-5: spawnSlidingParticle() — BlockState-based particle spawning
 *
 * Exit conditions (sneak release / speed threshold) handled in the SmartMovingClientState main tick.
 * Original L2563-L2567 1:1.
 */
@Environment(EnvType.CLIENT)
public final class SmartMovingSlider {

    private SmartMovingSlider() {}

    // ── [9-4] handleSliding ──────────────────────────────────────────────────

    /**
     * Apply sliding horizontal damping.
     * Replace vanilla travel() by calling move() directly.
     *
     * Damping formula (original SmartMovingSelf.landMotion() L729 isSliding branch):
     *   damping = 1 / (((1/slip - 1) / 25) * _slideSlipperinessFactor + 1) * 0.98F
     *   - slip: slipperiness of block below feet (ice=0.98, normal=0.6)
     *   - _slideSlipperinessFactor: Config value (default 1.0F)
     *   - 0.98F: air resistance-like coefficient
     *
     * Exit conditions handled in SmartMovingClientState main tick (original L2563-L2567 1:1).
     *
     * @return true if SM handles it (travel() cancel target)
     */
    public static boolean handleSliding(LocalPlayer player, SmartMovingClientState sm) {
        if (!sm.isSliding) return false;
        SmartMovingConfig cfg = SmartMovingConfig.Config;
        if (!cfg.slide) {
            sm.isSliding = false;
            return false;
        }

        Vec3 vel = player.getDeltaMovement();
        // Block slipperiness below feet
        BlockState below = player.level().getBlockState(player.getOnPos());
        float slip = below.getBlock().getFriction();

        // Original damping formula
        float damping = 1F / (((1F / slip - 1F) / 25F) * cfg.slideSlipperinessFactor + 1F) * 0.98F;

        double newVx = vel.x * damping;
        double newVz = vel.z * damping;
        // Vertical: gravity applied (fall if airborne while sliding)
        double newVy = vel.y - player.getAttributeValue(Attributes.GRAVITY);
        newVy *= 0.98D;

        // Original L730-L744: strafe forced rotation (left/right keys control sliding direction).
        //   When moveStrafing != 0 && _slideControlDegrees > 0, rotate motionX/Z.
        //   _slideControlDegrees: rotation amount per tick (deg). Default 1F.
        //   moveStrafing sign = Math.signum() → determines left/right rotation direction.
        //   Original RadiantToAngle = 180/π → Math.toRadians 1:1.
        //   After damping applied to newVx/newVz, rotate (scalar damping × unit-preserving rotation = commute → same result).
        float moveStrafing = player.input.getMoveVector().x;
        if (moveStrafing != 0F && cfg.slideControlDegrees > 0F) {
            double angle = -Math.atan(newVx / newVz);
            if (!Double.isNaN(angle)) {
                if (newVz < 0) angle += Math.PI;
                angle -= Math.toRadians(cfg.slideControlDegrees) * Math.signum(moveStrafing);
                double hMotion = Math.sqrt(newVx * newVx + newVz * newVz);
                newVx = hMotion * -Math.sin(angle);
                newVz = hMotion *  Math.cos(angle);
            }
        }

        player.setDeltaMovement(newVx, newVy, newVz);
        player.move(MoverType.SELF, player.getDeltaMovement());

        // 🔴 BUG fix (2026-05-08):
        //   sm_travel_client's ci.cancel() skips vanilla LivingEntity.travel body →
        //   fallDistance update formula inside body (`if onGround → fallDistance=0; else if vy<0 →
        //   fallDistance -= vy`) also skipped. Sliding off cliff → fallDistance=0 maintained →
        //   the fall-based slide exits never trigger.
        //   Original 1.7.10 flow: SM superMoveEntityWithHeading calls vanilla moveEntity →
        //   fallDistance auto-updated inside moveEntity. 1.21.1 vanilla travel cancel means
        //   direct mapping needed.
        //   Solution: 1:1 mapping of vanilla 1.21.1 LivingEntity.travel fallDistance update formula.
        // 🔴 fix #67 (2026-05-10, dump analysis — root cause confirmed):
        //   During isSliding, when box feet = ground top exactly, vanilla *touching* → onGround=false.
        //   Old formula: onGround false → else if (vy<0) → fall += -vy accumulated → cycle BUG.
        //   Solution: when box.minY ≈ solid top (epsilon 1e-3 match), reset fall=0 (= treat as touching=ground).
        net.minecraft.world.phys.AABB _bbS67 = player.getBoundingBox();
        double _solidTopS67 = SmartMovingClientState.getMaxPlayerSolidBetween(player, _bbS67.minY - 1.0, _bbS67.minY, 0);
        boolean _atSolidTop = Math.abs(_bbS67.minY - _solidTopS67) < 1.0E-3;
        if (player.onGround() || _atSolidTop) {
            player.fallDistance = 0.0F;
        } else if (player.getDeltaMovement().y < 0.0) {
            player.fallDistance -= (float) player.getDeltaMovement().y;
        }

        // [9-5] Sliding particle
        spawnSlidingParticle(player, sm, new Vec3(newVx, 0, newVz));

        // Exit conditions in SmartMovingClientState main tick (original L2563-L2567)
        // handled: sneak release + horizontalSpeedSquare < stopFactor*0.01 + wasCrawling=toCrawling() 1:1 processing.
        return true;
    }

    // ── [9-5] spawnSlidingParticle ───────────────────────────────────────────

    /**
     * Spawn sliding particle.
     * Original: SmartMoving.spawnParticles() isSliding branch.
     *
     * Original behavior:
     *   spawnSlindingParticle += dt (timer accumulation)
     *   if > _slideParticlePeriodFactor * 0.1F (= 0.5F * 0.1F = 0.05F):
     *       particle: blockcrack_<id>_<meta> → 1.21.1 ParticleTypes.BLOCK + BlockState
     *       motionX/Z = movement direction * -4D, motionY = 1.5D
     *       reset timer
     */
    private static void spawnSlidingParticle(LocalPlayer player, SmartMovingClientState sm, Vec3 horizontal) {
        if (!player.level().isClientSide()) return;

        SmartMovingConfig cfg = SmartMovingConfig.Config;
        sm.spawnSlindingParticle += 1F;
        float threshold = cfg.slideParticlePeriodFactor * 0.1F;

        if (sm.spawnSlindingParticle > threshold) {
            sm.spawnSlindingParticle = 0F;

            BlockState below = player.level().getBlockState(player.blockPosition().below());
            if (below.isAir()) return;

            double speed = horizontal.horizontalDistance();
            if (speed < 0.01D) return;

            // Emit particle opposite to movement direction (original: motionX/Z = direction * -4D)
            double nx = horizontal.x / speed;
            double nz = horizontal.z / speed;

            player.level().addParticle(
                    new BlockParticleOption(ParticleTypes.BLOCK, below),
                    player.getX(), player.getY(), player.getZ(),
                    nx * -4D, 1.5D, nz * -4D
            );
        }
    }
}
