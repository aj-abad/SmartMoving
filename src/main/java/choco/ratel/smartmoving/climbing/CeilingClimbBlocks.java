package choco.ratel.smartmoving.climbing;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 1-6: Determining blocks that support ceiling climbing.
 * Original: SmartMovingBase.supportsCeilingClimbing() — redesigned from Block ID/Meta to BlockState-based.
 *
 * Supported blocks (ported from original _ceilingClimbConfigurationString defaults):
 *   tile.fenceIron → minecraft:iron_bars
 *   tile.trapdoor/0/1/2/3 → all wooden trapdoors (OPEN=false, closed state)
 *   tile.trapdoor_iron/0/1/2/3 → minecraft:iron_trapdoor (OPEN=false)
 *
 * Original meta 0/1/2/3 = closed state (direction angles), meta 4-7 = open state.
 * 1.21.1: TrapdoorBlock.OPEN = false indicates closed state.
 *
 * Custom block additions based on config files are not yet confirmed (additional research needed).
 * TODO: Reimplement config string parsing system and add config-based blocks to this method.
 */
public final class CeilingClimbBlocks {

    private CeilingClimbBlocks() {}

    /**
     * Determines if the given BlockState is a block that supports ceiling climbing.
     *
     * @param state BlockState to check (returns false if null)
     * @return true if the block supports ceiling climbing
     */
    public static boolean supports(BlockState state) {
        if (state == null) return false;

        // tile.fenceIron → minecraft:iron_bars: all states allowed
        if (state.is(Blocks.IRON_BARS)) return true;

        // 🔴 User request: fence ≡ iron_bars identical behavior. Add fence to ceiling climbing too.
        //   Original doesn't support ceiling climbing on fences (_ceilingClimbConfigurationString defaults
        //   only include tile.fenceIron) but user intent "both blocks behave identically" takes priority.
        //   isFence(state) = FenceBlock || WallBlock || closed FenceGate.
        if (Orientation.isFence(state)) return true;

        // tile.trapdoor/trapdoor_iron → TrapDoorBlock types: only closed state allowed
        // Original meta 0-3 = OPEN=false equivalent
        if (state.getBlock() instanceof TrapDoorBlock) {
            return !state.getValue(TrapDoorBlock.OPEN);
        }

        return false;
    }
}
