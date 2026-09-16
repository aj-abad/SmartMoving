package choco.ratel.smartmoving.climbing;

/**
 * Hands climbing state enum. Converts original Typesafe Enum (None=-3 ~ FastUp=2) to Java enum.
 * Replicates original _value comparison logic using ordinal comparison.
 */
public enum HandsClimbing {
    NONE,        // Original: None(-3)
    SINK,        // Original: Sink(-2)
    TOP_HOLD,    // Original: TopHold(-1)
    BOTTOM_HOLD, // Original: BottomHold(0)
    UP,          // Original: Up(1)
    FAST_UP;     // Original: FastUp(2)

    // Int constants used for storing actualHandsClimbType and 4-bit network encoding
    public static final int NO_GRAB = 0;
    public static final int UP_GRAB = 1;
    public static final int MIDDLE_GRAB = 2;

    /** All states except NONE. Original: _value > None._value */
    public boolean isRelevant() {
        return this != NONE;
    }

    /** Up or FastUp. Original: _value > BottomHold._value */
    public boolean isUp() {
        return this.ordinal() > BOTTOM_HOLD.ordinal();
    }

    /** Transition BottomHold → Up. Other values return unchanged. */
    public HandsClimbing toUp() {
        return this == BOTTOM_HOLD ? UP : this;
    }

    /** Transition TopHold → Sink. Other values return unchanged. */
    public HandsClimbing toDown() {
        return this == TOP_HOLD ? SINK : this;
    }

    /**
     * Selects the stronger of two HandsClimbing states.
     * Original: If !SkipGaps, OR-aggregate CanStand/MustCrawl. If other is stronger, replace Block/Meta/Direction.
     */
    public HandsClimbing max(HandsClimbing other, ClimbGap[] inout_thisGap, ClimbGap otherGap) {
        if (!otherGap.skipGaps) {
            inout_thisGap[0].canStand |= otherGap.canStand;
            inout_thisGap[0].mustCrawl |= otherGap.mustCrawl;
        }
        if (other.ordinal() > this.ordinal()) {
            inout_thisGap[0].state = otherGap.state;
            inout_thisGap[0].direction = otherGap.direction;
            return other;
        }
        return this;
    }
}
