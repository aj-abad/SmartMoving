package choco.ratel.smartmoving.climbing;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;

/** Data class that holds climbing search results. Replaces original Block(int)+Meta(int) pair with single BlockState object. */
public class ClimbGap {
    /** null = not set (replaces original Block=-1/Meta=-1) */
    public BlockState state;
    public boolean canStand;
    public boolean mustCrawl;
    public Direction direction;
    public boolean skipGaps;

    public void reset() {
        state = null;
        canStand = false;
        mustCrawl = false;
        direction = null;
        skipGaps = false;
    }

    public void copyFrom(ClimbGap other) {
        this.state = other.state;
        this.canStand = other.canStand;
        this.mustCrawl = other.mustCrawl;
        this.direction = other.direction;
        this.skipGaps = other.skipGaps;
    }
}
