package choco.ratel.smartmoving.client;

import choco.ratel.smartmoving.climbing.FeetClimbing;
import choco.ratel.smartmoving.climbing.HandsClimbing;
import choco.ratel.smartmoving.client.input.SmartMovingKeys;
import choco.ratel.smartmoving.config.SmartMovingConfig;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.ChainBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Chain climb (prototype, docs/research_movement_prototypes.md).
 *
 * Vertical chains (iron and every copper variant) become climbable. Chains have a collision box in
 * the centre of their block, so unlike ladders the player is never "inside" the chain block; the
 * climber therefore looks for the nearest vertical chain in the 3×3 columns around the player and
 * holds the player against it.
 *
 * Controls while on a chain: forward = climb, back = climb down, sneak = slide down fast
 * (rope-sliding pose), jump = let go with a small push away. Grab, or walking into a chain while
 * facing it, takes hold; that also works in the air, so a falling player can catch a chain.
 *
 * Flags: isChainClimbing (bit 40, pose yaw), isClimbing (bit 14, server fall reset and position
 * check suppression), isHandsVineClimbing + climb types (existing climbing animation),
 * isRopeSliding (bit 32, existing slide pose).
 *
 * Called from sm_travel_client before the ladder / vine detection; returns true when it owns the
 * motion for the tick (caller cancels vanilla travel).
 */
@Environment(EnvType.CLIENT)
public final class SmartMovingChainClimber {

    private SmartMovingChainClimber() {}

    /** Player centre to chain centre while hanging: half player width + half chain width + a little. */
    private static final double HANG_DISTANCE = 0.42D;
    /** Fraction of the remaining offset to the hang point closed per tick, and its cap. */
    private static final double PULL_RATE = 0.5D;
    private static final double PULL_MAX = 0.15D;
    /** Blocks per tick when climbing up or down. */
    private static final double CLIMB_SPEED = 0.12D;
    /** Blocks per tick when sliding down with sneak. */
    private static final double SLIDE_SPEED = 0.5D;
    /** How far (degrees) the look direction may be off the chain to take hold by walking into it. */
    private static final float FACING_TOLERANCE = 60F;
    /** Chest height: climbing up stops once no chain is left at this height (hands at the top). */
    private static final double HANDS_HEIGHT = 1.6D;

    public static boolean isVerticalChain(BlockState state) {
        return state != null && state.getBlock() instanceof ChainBlock
                && state.getValue(RotatedPillarBlock.AXIS) == Direction.Axis.Y;
    }

    /**
     * Centre (x, 0, z) of the nearest vertical chain column within {@code maxDistance} of the player
     * whose chain overlaps the player's height, or null. Also used by the renderer for remote players.
     */
    public static Vec3 findChain(Player player, double maxDistance) {
        Level level = player.level();
        AABB box = player.getBoundingBox();
        int x0 = Mth.floor(player.getX());
        int z0 = Mth.floor(player.getZ());
        int yFeet = Mth.floor(box.minY);
        int yHead = Mth.floor(box.maxY - 1.0E-3D);

        Vec3 best = null;
        double bestDistance = maxDistance;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                double cx = x0 + dx + 0.5D;
                double cz = z0 + dz + 0.5D;
                double distance = Math.hypot(cx - player.getX(), cz - player.getZ());
                if (distance > bestDistance) continue;
                for (int y = yFeet; y <= yHead; y++) {
                    if (isVerticalChain(level.getBlockState(pos.set(x0 + dx, y, z0 + dz)))) {
                        best = new Vec3(cx, 0D, cz);
                        bestDistance = distance;
                        break;
                    }
                }
            }
        }
        return best;
    }

    /** World yaw (degrees) from the player toward the chain, using the entity yaw convention. */
    public static float yawToward(Player player, Vec3 chain) {
        return (float) Math.toDegrees(Math.atan2(-(chain.x - player.getX()), chain.z - player.getZ()));
    }

    private static boolean isBlocked(LocalPlayer player, SmartMovingClientState sm) {
        return sm.isSwimming_sm || sm.isDiving || sm.isFlying || player.getAbilities().flying
                || player.isFallFlying() || sm.isCrawling || sm.isSliding || sm.isHeadJumping
                || sm.isCeilingClimbing || sm.isClimbCrawling || sm.isCrawlClimbing
                || sm.isRolling || sm.isVaulting
                || player.isPassenger() || player.isSleeping() || player.isInLava();
    }

    public static boolean handle(LocalPlayer player, SmartMovingClientState sm, Vec3 movementInput) {
        SmartMovingConfig cfg = SmartMovingConfig.Config;
        boolean active = sm.isChainClimbing;

        if (!cfg.enabled || !cfg.chainClimb || isBlocked(player, sm)) {
            if (active) exit(sm);
            return false;
        }
        Vec3 chain = findChain(player, cfg.chainGrabDistance);
        if (chain == null) {
            if (active) exit(sm);
            return false;
        }

        var options = Minecraft.getInstance().options;
        boolean grab = SmartMovingKeys.grab.isDown();
        boolean sneakKey = options.keyShift.isDown();
        float forward = (float) movementInput.z;
        float chainYaw = yawToward(player, chain);
        boolean facing = Math.abs(Mth.wrapDegrees(player.getYRot() - chainYaw)) <= FACING_TOLERANCE;

        if (!active) {
            boolean wants = grab || (forward > 0F && facing);
            if (!wants || sm.jumpKeyStartPressed) return false;
            sm.isChainClimbing = true;
            sm.rollArmedTicks = 0;
        } else if (sm.jumpKeyStartPressed) {
            // Let go with a small push away from the chain; vanilla motion resumes this tick.
            exit(sm);
            double toX = chain.x - player.getX();
            double toZ = chain.z - player.getZ();
            double d = Math.hypot(toX, toZ);
            if (d > 1.0E-4D) {
                player.setDeltaMovement(-toX / d * 0.2D, 0.3D, -toZ / d * 0.2D);
            }
            return false;
        } else if (player.onGround() && !grab && forward <= 0F && !sneakKey) {
            // Standing at the bottom with no climbing intent: just stand.
            exit(sm);
            return false;
        }

        sm.chainX = chain.x;
        sm.chainZ = chain.z;
        sm.chainYaw = chainYaw;

        // Horizontal: ease to the hang point on the player's side of the chain.
        double toX = chain.x - player.getX();
        double toZ = chain.z - player.getZ();
        double d = Math.hypot(toX, toZ);
        double nx, nz;
        if (d > 1.0E-4D) {
            nx = toX / d;
            nz = toZ / d;
        } else {
            double yaw = Math.toRadians(player.getYRot());
            nx = -Math.sin(yaw);
            nz = Math.cos(yaw);
        }
        double hangX = chain.x - nx * HANG_DISTANCE;
        double hangZ = chain.z - nz * HANG_DISTANCE;
        double vx = Mth.clamp((hangX - player.getX()) * PULL_RATE, -PULL_MAX, PULL_MAX);
        double vz = Mth.clamp((hangZ - player.getZ()) * PULL_RATE, -PULL_MAX, PULL_MAX);

        // Vertical: sneak slides, forward climbs while there is chain above the hands, back descends.
        double speedFactor = SmartMovingMover.getCombinedSpeedFactor(player, cfg);
        double vy;
        sm.isRopeSliding = false;
        if (sneakKey && !player.onGround()) {
            vy = -SLIDE_SPEED * cfg.chainSlideSpeedFactor * speedFactor;
            sm.isRopeSliding = true;
        } else if (forward > 0F) {
            vy = hasChainAtHands(player, chain) ? CLIMB_SPEED * cfg.chainClimbSpeedFactor * speedFactor : 0D;
        } else if (forward < 0F) {
            vy = -CLIMB_SPEED * cfg.chainClimbSpeedFactor * speedFactor;
        } else {
            vy = 0D;
        }

        player.setDeltaMovement(vx, vy, vz);
        player.move(MoverType.SELF, player.getDeltaMovement());
        player.fallDistance = 0D;

        // Server behavior rides on isClimbing; the animation on the vine-style climbing pose.
        sm.isClimbing = true;
        sm.isHandsVineClimbing = true;
        sm.isFeetVineClimbing = false;
        sm.actualHandsClimbType = HandsClimbing.UP_GRAB;
        sm.actualFeetClimbType = FeetClimbing.DOWN_STEP;
        return true;
    }

    private static boolean hasChainAtHands(LocalPlayer player, Vec3 chain) {
        BlockPos pos = BlockPos.containing(chain.x, player.getBoundingBox().minY + HANDS_HEIGHT, chain.z);
        return isVerticalChain(player.level().getBlockState(pos));
    }

    private static void exit(SmartMovingClientState sm) {
        sm.isChainClimbing = false;
        sm.isClimbing = false;
        sm.isRopeSliding = false;
        sm.isHandsVineClimbing = false;
        sm.actualHandsClimbType = HandsClimbing.NO_GRAB;
        sm.actualFeetClimbType = FeetClimbing.NO_STEP;
    }
}
