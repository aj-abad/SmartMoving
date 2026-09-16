package choco.ratel.smartmoving.climbing;

/**
 * Feet climbing state enum. Converts original Typesafe Enum (None=-3 ~ FastUp=3) to Java enum.
 * Replicates original _value comparison logic using ordinal comparison.
 */
public enum FeetClimbing {
    NONE,                          // Original: None(-3)
    BASE_HOLD,                     // Original: BaseHold(-2)
    BASE_WITH_HANDS,               // Original: BaseWithHands(-1)
    TOP_WITH_HANDS,                // Original: TopWithHands(0)
    SLOW_UP_WITH_HOLD_WITHOUT_HANDS,  // Original: SlowUpWithHoldWithoutHands(1)
    SLOW_UP_WITH_SINK_WITHOUT_HANDS,  // Original: SlowUpWithSinkWithoutHands(2)
    FAST_UP;                       // Original: FastUp(3)

    // Int constants used for storing actualFeetClimbType and 4-bit network encoding
    public static final int NO_STEP = 0;
    public static final int DOWN_STEP = 1;

    /** All states except NONE. Original: _value > None._value */
    public boolean isRelevant() {
        return this != NONE;
    }

    /**
     * State where climbing is possible with feet alone, without hands.
     * Original: _value > BaseWithHands._value → TopWithHands(0) and above.
     * Reference criteria for motionY decision in handleClimbing Simple mode.
     */
    public boolean isIndependentlyRelevant() {
        return this.ordinal() > BASE_WITH_HANDS.ordinal();
    }

    // Original: this == SlowUpWithHoldWithoutHands || this == SlowUpWithSinkWithoutHands || this == FastUp
    public boolean isUp() {
        return this == SLOW_UP_WITH_HOLD_WITHOUT_HANDS
            || this == SLOW_UP_WITH_SINK_WITHOUT_HANDS
            || this == FAST_UP;
    }

    /**
     * Selects the stronger of two FeetClimbing states.
     * Original: If !SkipGaps, OR-aggregate CanStand/MustCrawl. If other is stronger, replace Block/Meta/Direction.
     */
    public FeetClimbing max(FeetClimbing other, ClimbGap[] inout_thisGap, ClimbGap otherGap) {
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
