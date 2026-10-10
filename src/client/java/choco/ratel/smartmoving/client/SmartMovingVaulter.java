package choco.ratel.smartmoving.client;

import choco.ratel.smartmoving.client.input.SmartMovingKeys;
import choco.ratel.smartmoving.config.SmartMovingConfig;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Sprint vault (prototype, docs/research_movement_prototypes.md).
 *
 * Sprinting straight at an obstacle while holding Grab, with the obstacle top between the vanilla
 * step height and {@code vaultMaxHeight} above the feet (1-block ledges, fences, walls), vaults it
 * in two parts:
 *   1. Rise — a short scripted lift (gravity off, vanilla travel cancelled) that carries the player
 *      forward at sprint speed while raising the feet just above the obstacle top, timed so the
 *      feet clear the top as the box reaches the obstacle face.
 *   2. Release — over the top the player is handed back to normal air physics with forward speed
 *      and a little upward momentum, so a 1-block-wide obstacle is cleared and the landing is on
 *      the far side.
 *
 * Both parts need the space over the obstacle and the landing spot to be collision-free at the
 * moment of the trigger. Called from sm_travel_client before handleJumping every tick; returns
 * true when it owns the motion for the tick (caller cancels vanilla travel).
 */
@Environment(EnvType.CLIENT)
public final class SmartMovingVaulter {

    private SmartMovingVaulter() {}

    /** Ticks after the release before ground contact may end the vault. */
    private static final int LANDING_GRACE_TICKS = 2;
    /** Hard stop so a vault off a cliff turns back into a normal fall. */
    private static final int MAX_TICKS = 40;
    /** Vanilla step height: anything this low is walked over, never vaulted. */
    private static final double STEP_HEIGHT = 0.6D;
    /** Vanilla sprint speed, the floor for the vault speed. */
    private static final double SPRINT_SPEED = 0.28D;
    /** Upward speed handed over at the release (before vaultVerticalFactor). */
    private static final double RELEASE_VELOCITY = 0.25D;
    /** Fastest the scripted rise may lift per tick; longer rises get more ticks instead. */
    private static final double MAX_LIFT_PER_TICK = 0.6D;
    /** Clearance kept between the feet and the obstacle top. */
    private static final double TOP_CLEARANCE = 0.1D;
    private static final int MIN_RISE_TICKS = 2;
    private static final int MAX_RISE_TICKS = 6;

    /**
     * Drives the vault. Order: scripted rise in progress → end detection → trigger (which also
     * performs the first rise step so there is no idle tick).
     */
    public static boolean tick(LocalPlayer player, SmartMovingClientState sm, Vec3 movementInput) {
        SmartMovingConfig cfg = SmartMovingConfig.Config;

        if (sm.isVaulting) {
            if (sm.vaultRiseTicks > 0) {
                return rise(player, sm, cfg);
            }
            int age = player.tickCount - sm.vaultStartTick;
            boolean landed = player.onGround() && age >= sm.vaultRiseTotal + LANDING_GRACE_TICKS;
            if (landed || age > MAX_TICKS || !cfg.enabled || !cfg.vault
                    || player.isInWater() || player.getAbilities().flying
                    || sm.isClimbing || sm.isChainClimbing || sm.isSwimming_sm || sm.isDiving) {
                end(sm);
            }
            return false;
        }

        if (!cfg.enabled || !cfg.vault) return false;
        if (!player.onGround() || !player.isSprinting() || !SmartMovingKeys.grab.isDown()
                || player.isShiftKeyDown()) {
            return false;
        }
        if (movementInput.z <= 0D) return false;   // must be pushing forward
        if (sm.isRolling || sm.isCrawling || sm.isSliding || sm.isHeadJumping
                || sm.isClimbing || sm.isChainClimbing || sm.isCrawlClimbing || sm.isCeilingClimbing
                || sm.isSwimming_sm || sm.isDiving || sm.isFlying
                || player.getAbilities().flying || player.isFallFlying()
                || player.isPassenger() || player.isSleeping()
                || player.isInWater() || player.isInLava()
                || sm.jumpCharge > 0F || sm.headJumpCharge > 0F) {
            return false;
        }

        // Direction: where the player is actually going, or the look direction when barely moving.
        Vec3 v = player.getDeltaMovement();
        double speed = v.horizontalDistance();
        double dx, dz;
        if (speed > 0.05D) {
            dx = v.x / speed;
            dz = v.z / speed;
        } else {
            double yaw = Math.toRadians(player.getYRot());
            dx = -Math.sin(yaw);
            dz = Math.cos(yaw);
        }

        AABB box = player.getBoundingBox();
        double reach = Math.max(0.1D, cfg.vaultReach);
        double minH = Math.max(STEP_HEIGHT, cfg.vaultMinHeight);
        double maxH = cfg.vaultMaxHeight;
        if (maxH <= minH) return false;

        // Probe: the player's footprint moved `reach` ahead, between step height and the max vault height.
        AABB probe = new AABB(box.minX + dx * reach, box.minY + minH + 1.0E-3D, box.minZ + dz * reach,
                              box.maxX + dx * reach, box.minY + maxH,          box.maxZ + dz * reach);
        Level level = player.level();
        double top = Double.NaN;
        double distance = Double.NaN;
        for (VoxelShape shape : level.getBlockCollisions(player, probe)) {
            if (shape.isEmpty()) continue;
            for (AABB b : shape.toAabbs()) {
                if (!b.intersects(probe)) continue;
                top = Double.isNaN(top) ? b.maxY : Math.max(top, b.maxY);
                double d = entryDistance(box, dx, dz, b);
                if (!Double.isNaN(d)) distance = Double.isNaN(distance) ? d : Math.min(distance, d);
            }
        }
        if (Double.isNaN(top)) return false;
        double h = top - box.minY;
        // Lower: walkable. Higher: a wall, not a vault (a 2-high wall also lands here through its upper block).
        if (h < minH || h > maxH) return false;
        if (Double.isNaN(distance)) distance = reach;

        // Clearance: the full player box over the obstacle (plus release arc) and at the landing spot.
        double lift = h + TOP_CLEARANCE;
        AABB over = box.move(dx * (distance + 0.5D), lift, dz * (distance + 0.5D));
        over = new AABB(over.minX, over.minY, over.minZ, over.maxX, over.maxY + 0.4D, over.maxZ);
        AABB landing = box.move(dx * (distance + 1.3D), lift, dz * (distance + 1.3D));
        if (!level.noCollision(player, over) || !level.noCollision(player, landing)) return false;

        // Plan the rise: reach the top as the box reaches the face, never faster than MAX_LIFT_PER_TICK.
        double vaultSpeed = Math.max(speed, SPRINT_SPEED);
        int riseTicks = (int) Math.ceil(Math.max(distance / vaultSpeed, lift / MAX_LIFT_PER_TICK));
        riseTicks = Math.max(MIN_RISE_TICKS, Math.min(MAX_RISE_TICKS, riseTicks));

        sm.isVaulting = true;
        sm.vaultStartTick = player.tickCount;
        sm.vaultYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        sm.vaultDirX = dx;
        sm.vaultDirZ = dz;
        sm.vaultSpeed = vaultSpeed;
        sm.vaultLift = lift / riseTicks;
        sm.vaultRiseTicks = riseTicks;
        sm.vaultRiseTotal = riseTicks;
        sm.isJumping = true;
        sm.rollArmedTicks = 0;

        return rise(player, sm, cfg);
    }

    /** One scripted rise step. Returns true (travel cancelled) unless the vault had to be abandoned. */
    private static boolean rise(LocalPlayer player, SmartMovingClientState sm, SmartMovingConfig cfg) {
        if (!cfg.enabled || !cfg.vault || player.isInWater() || player.getAbilities().flying) {
            end(sm);
            return false;
        }

        player.setDeltaMovement(sm.vaultDirX * sm.vaultSpeed, sm.vaultLift, sm.vaultDirZ * sm.vaultSpeed);
        player.move(MoverType.SELF, player.getDeltaMovement());
        player.fallDistance = 0D;
        sm.vaultRiseTicks--;

        // Something overhead stopped the lift: give up and let vanilla physics take it from here.
        if (player.verticalCollision && !player.onGround()) {
            end(sm);
            return true;
        }

        if (sm.vaultRiseTicks <= 0) {
            // Over the top: forward plus a little upward momentum, then normal air physics.
            double hs = sm.vaultSpeed * cfg.vaultHorizontalFactor;
            double vy = RELEASE_VELOCITY * cfg.vaultVerticalFactor;
            player.setDeltaMovement(sm.vaultDirX * hs, vy, sm.vaultDirZ * hs);
        }
        return true;
    }

    private static void end(SmartMovingClientState sm) {
        sm.isVaulting = false;
        sm.vaultStartTick = Integer.MIN_VALUE;
        sm.vaultRiseTicks = 0;
    }

    /**
     * Distance the player box can travel along (dx, dz) before touching {@code b} in the XZ plane
     * (slab test against b inflated by the box's half extents). 0 when already overlapping, NaN when
     * the path misses the box.
     */
    private static double entryDistance(AABB box, double dx, double dz, AABB b) {
        double hx = (box.maxX - box.minX) / 2D;
        double hz = (box.maxZ - box.minZ) / 2D;
        double cx = (box.minX + box.maxX) / 2D;
        double cz = (box.minZ + box.maxZ) / 2D;
        double minX = b.minX - hx, maxX = b.maxX + hx;
        double minZ = b.minZ - hz, maxZ = b.maxZ + hz;

        double tMin = 0D;
        double tMax = Double.POSITIVE_INFINITY;
        if (Math.abs(dx) < 1.0E-9D) {
            if (cx < minX || cx > maxX) return Double.NaN;
        } else {
            double t1 = (minX - cx) / dx, t2 = (maxX - cx) / dx;
            tMin = Math.max(tMin, Math.min(t1, t2));
            tMax = Math.min(tMax, Math.max(t1, t2));
        }
        if (Math.abs(dz) < 1.0E-9D) {
            if (cz < minZ || cz > maxZ) return Double.NaN;
        } else {
            double t1 = (minZ - cz) / dz, t2 = (maxZ - cz) / dz;
            tMin = Math.max(tMin, Math.min(t1, t2));
            tMax = Math.min(tMax, Math.max(t1, t2));
        }
        return tMax < tMin ? Double.NaN : tMin;
    }
}
