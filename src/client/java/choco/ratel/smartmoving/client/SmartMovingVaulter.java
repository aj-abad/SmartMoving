package choco.ratel.smartmoving.client;

import choco.ratel.smartmoving.config.SmartMovingConfig;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Sprint vault (prototype, docs/research_movement_prototypes.md).
 *
 * Sprinting straight at an obstacle whose top sits between the vanilla step height and
 * {@code vaultMaxHeight} above the feet (1-block ledges, fences, walls) launches a hop that clears
 * it, as long as the space over the obstacle and the landing spot beyond it are free. Normal air
 * physics carry the player afterwards; the state only drives the pose and the remote animation.
 *
 * Called from sm_travel_client right after handleJumping every tick.
 */
@Environment(EnvType.CLIENT)
public final class SmartMovingVaulter {

    private SmartMovingVaulter() {}

    /** A vault is never ended by ground contact before this many ticks (the takeoff tick is grounded). */
    private static final int MIN_TICKS = 2;
    /** Hard stop so a vault off a cliff turns back into a normal fall. */
    private static final int MAX_TICKS = 30;
    /** Vanilla step height: anything this low is walked over, never vaulted. */
    private static final double STEP_HEIGHT = 0.6D;
    /** Vanilla jump velocity. */
    private static final double JUMP_VELOCITY = 0.42D;
    /** Vanilla sprint speed, the floor for the horizontal launch speed. */
    private static final double SPRINT_SPEED = 0.28D;

    public static void tick(LocalPlayer player, SmartMovingClientState sm, Vec3 movementInput) {
        SmartMovingConfig cfg = SmartMovingConfig.Config;

        if (sm.isVaulting) {
            int age = player.tickCount - sm.vaultStartTick;
            boolean landed = player.onGround() && age >= MIN_TICKS;
            if (landed || age > MAX_TICKS || !cfg.enabled || !cfg.vault
                    || player.isInWater() || player.getAbilities().flying
                    || sm.isClimbing || sm.isChainClimbing || sm.isSwimming_sm || sm.isDiving) {
                sm.isVaulting = false;
                sm.vaultStartTick = Integer.MIN_VALUE;
            }
            return;
        }

        if (!cfg.enabled || !cfg.vault) return;
        if (!player.onGround() || !player.isSprinting() || player.isShiftKeyDown()) return;
        if (movementInput.z <= 0D) return;   // must be pushing forward
        if (sm.isRolling || sm.isCrawling || sm.isSliding || sm.isHeadJumping
                || sm.isClimbing || sm.isChainClimbing || sm.isCrawlClimbing || sm.isCeilingClimbing
                || sm.isSwimming_sm || sm.isDiving || sm.isFlying
                || player.getAbilities().flying || player.isFallFlying()
                || player.isPassenger() || player.isSleeping()
                || player.isInWater() || player.isInLava()
                || sm.jumpCharge > 0F || sm.headJumpCharge > 0F) {
            return;
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
        if (maxH <= minH) return;

        // Probe: the player's footprint moved `reach` ahead, between step height and the max vault height.
        AABB probe = new AABB(box.minX + dx * reach, box.minY + minH + 1.0E-3D, box.minZ + dz * reach,
                              box.maxX + dx * reach, box.minY + maxH,          box.maxZ + dz * reach);
        Level level = player.level();
        double top = Double.NaN;
        for (VoxelShape shape : level.getBlockCollisions(player, probe)) {
            if (shape.isEmpty()) continue;
            for (AABB b : shape.toAabbs()) {
                if (!b.intersects(probe)) continue;
                top = Double.isNaN(top) ? b.maxY : Math.max(top, b.maxY);
            }
        }
        if (Double.isNaN(top)) return;
        double h = top - box.minY;
        // Lower: walkable. Higher: a wall, not a vault (a 2-high wall also lands here through its upper block).
        if (h < minH || h > maxH) return;

        // Clearance: the full player box over the obstacle (plus arc height) and at the landing spot.
        double lift = h + 0.05D;
        AABB over = box.move(dx * (reach + 0.5D), lift, dz * (reach + 0.5D));
        over = new AABB(over.minX, over.minY, over.minZ, over.maxX, over.maxY + 0.3D, over.maxZ);
        AABB landing = box.move(dx * (reach + 1.2D), lift, dz * (reach + 1.2D));
        if (!level.noCollision(player, over) || !level.noCollision(player, landing)) return;

        // Launch: vanilla jump clears 1 block; scale up for taller obstacles (≈0.50 for a fence).
        double vy = JUMP_VELOCITY * cfg.vaultVerticalFactor
                * (h <= 1.0D ? 1.0D : 1.0D + 0.4D * (h - 1.0D));
        double hs = Math.max(speed, SPRINT_SPEED) * cfg.vaultHorizontalFactor;
        player.setDeltaMovement(dx * hs, vy, dz * hs);
        player.fallDistance = 0D;

        sm.isJumping = true;
        sm.isVaulting = true;
        sm.vaultStartTick = player.tickCount;
        sm.vaultYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
    }
}
