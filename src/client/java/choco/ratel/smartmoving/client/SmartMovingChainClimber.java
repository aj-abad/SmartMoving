package choco.ratel.smartmoving.client;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.ChainBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Chain climb helpers (prototype, docs/research_movement_prototypes.md).
 *
 * Vertical chains (iron and every copper variant) climb exactly like ladders: the ladder / vine
 * detection in {@link SmartMovingClimber#getOnLadderOrVine} and the ladder count in
 * {@link SmartMovingClimber#getOnLadderOrVineCount} treat a vertical chain in the player's column
 * or in a neighbouring column within {@code chainGrabDistance} as a ladder hold reachable from any
 * side. Everything downstream (auto entry by facing it, forward = up, back = down, sneak = hold,
 * sideways stepping, climb speeds, server fall reset) is the existing ladder code.
 *
 * The only chain-specific parts are the pose (`sm_animateChainClimbing`) and the body yaw toward
 * the chain, which use the helpers below.
 */
@Environment(EnvType.CLIENT)
public final class SmartMovingChainClimber {

    private SmartMovingChainClimber() {}

    public static boolean isVerticalChain(BlockState state) {
        return state != null && state.getBlock() instanceof ChainBlock
                && state.getValue(RotatedPillarBlock.AXIS) == Direction.Axis.Y;
    }

    /** Horizontal distance from the player centre to the centre of the given block column. */
    public static double distanceToColumn(Player player, int x, int z) {
        return Math.hypot(x + 0.5D - player.getX(), z + 0.5D - player.getZ());
    }

    /**
     * Centre (x, 0, z) of the nearest vertical chain column within {@code maxDistance} of the player
     * whose chain overlaps the player's height, or null. Used for the body yaw (self and remote).
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
                double distance = distanceToColumn(player, x0 + dx, z0 + dz);
                if (distance > bestDistance) continue;
                for (int y = yFeet; y <= yHead; y++) {
                    if (isVerticalChain(level.getBlockState(pos.set(x0 + dx, y, z0 + dz)))) {
                        best = new Vec3(x0 + dx + 0.5D, 0D, z0 + dz + 0.5D);
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
}
