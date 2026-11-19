package choco.ratel.smartmoving.network;

/**
 * State packet long bitmap.
 * Original SmartMovingOther/SmartMovingServer bit layout ported as-is.
 *
 * bits 0-3:   actualFeetClimbType  (FeetClimbing ordinal, 4 bits)
 * bits 4-7:   actualHandsClimbType (HandsClimbing ordinal, 4 bits)
 * bit  8:     isJumping
 * bit  9:     isDiving
 * bit  10:    isDipping
 * bit  11:    isSwimming
 * bit  12:    isCrawlClimbing
 * bit  13:    isCrawling
 * bit  14:    isClimbing
 * bit  15:    isSmall
 * bit  16:    doFallingAnimation
 * bit  17:    doFlyingAnimation
 * bit  18:    isCeilingClimbing
 * bit  19:    isLevitating
 * bit  20:    unused (reserved so later bits keep their positions)
 * bit  21:    isSliding
 * bits 22-24: angleJumpType lower 3 bits
 * bit  25:    isFeetVineClimbing
 * bit  26:    isHandsVineClimbing
 * bit  27:    isClimbJumping
 * bit  28:    isClimbBackJumping
 * bit  29:    isSlow
 * bit  30:    isFast
 * bit  31:    isWallJumping
 * bit  32:    isRopeSliding
 * bit  33:    isSneakButtonPressed (server-only read)
 * bit  34:    isClimbCrawling      (server-only read — box +1 mixin offset sync)
 * bit  35:    unused (reserved so later bits keep their positions)
 * bit  36:    angleJumpType upper bit (1-8 direction code extended with forward)
 * bit  37-39: unused (reserved so later bits keep their positions)
 * bit  40:    isChainClimbing  (vertical chain climb — remote body yaw / pose)
 */
public final class SmartMovingState {

    public int actualFeetClimbType;
    public int actualHandsClimbType;

    public boolean isJumping;
    public boolean isDiving;
    public boolean isDipping;
    public boolean isSwimming;
    public boolean isCrawlClimbing;
    public boolean isCrawling;
    public boolean isClimbing;
    public boolean isSmall;
    public boolean doFallingAnimation;
    public boolean doFlyingAnimation;
    public boolean isCeilingClimbing;
    public boolean isLevitating;
    public boolean isSliding;

    public int angleJumpType;

    public boolean isFeetVineClimbing;
    public boolean isHandsVineClimbing;
    public boolean isClimbJumping;
    public boolean isClimbBackJumping;
    public boolean isSlow;
    public boolean isFast;
    public boolean isWallJumping;
    public boolean isRopeSliding;
    public boolean isSneakButtonPressed;
    public boolean isClimbCrawling;

    // Movement prototypes (docs/research_movement_prototypes.md): bit 40.
    public boolean isChainClimbing;

    public static long encode(SmartMovingState s) {
        long bits = 0L;
        bits |= (s.actualFeetClimbType  & 0xF);
        bits |= (long) (s.actualHandsClimbType & 0xF) << 4;
        if (s.isJumping)           bits |= 1L << 8;
        if (s.isDiving)            bits |= 1L << 9;
        if (s.isDipping)           bits |= 1L << 10;
        if (s.isSwimming)          bits |= 1L << 11;
        if (s.isCrawlClimbing)     bits |= 1L << 12;
        if (s.isCrawling)          bits |= 1L << 13;
        if (s.isClimbing)          bits |= 1L << 14;
        if (s.isSmall)             bits |= 1L << 15;
        if (s.doFallingAnimation)  bits |= 1L << 16;
        if (s.doFlyingAnimation)   bits |= 1L << 17;
        if (s.isCeilingClimbing)   bits |= 1L << 18;
        if (s.isLevitating)        bits |= 1L << 19;
        // Bit 20 is unused and stays reserved.
        if (s.isSliding)           bits |= 1L << 21;
        bits |= (long) (s.angleJumpType & 0x7) << 22;
        if (s.isFeetVineClimbing)   bits |= 1L << 25;
        if (s.isHandsVineClimbing)  bits |= 1L << 26;
        if (s.isClimbJumping)       bits |= 1L << 27;
        if (s.isClimbBackJumping)   bits |= 1L << 28;
        if (s.isSlow)               bits |= 1L << 29;
        if (s.isFast)               bits |= 1L << 30;
        if (s.isWallJumping)        bits |= 1L << 31;
        if (s.isRopeSliding)        bits |= 1L << 32;
        if (s.isSneakButtonPressed) bits |= 1L << 33;
        if (s.isClimbCrawling)      bits |= 1L << 34;
        // Bit 35 is unused and stays reserved.
        if ((s.angleJumpType & 0x8) != 0) bits |= 1L << 36;
        // Bits 37-39 are unused and stay reserved.
        if (s.isChainClimbing)      bits |= 1L << 40;
        return bits;
    }

    public static SmartMovingState decode(long bits) {
        SmartMovingState s = new SmartMovingState();
        s.actualFeetClimbType  = (int) (bits        & 0xF);
        s.actualHandsClimbType = (int) ((bits >> 4) & 0xF);
        s.isJumping           = ((bits >> 8)  & 1) != 0;
        s.isDiving            = ((bits >> 9)  & 1) != 0;
        s.isDipping           = ((bits >> 10) & 1) != 0;
        s.isSwimming          = ((bits >> 11) & 1) != 0;
        s.isCrawlClimbing     = ((bits >> 12) & 1) != 0;
        s.isCrawling          = ((bits >> 13) & 1) != 0;
        s.isClimbing          = ((bits >> 14) & 1) != 0;
        s.isSmall             = ((bits >> 15) & 1) != 0;
        s.doFallingAnimation  = ((bits >> 16) & 1) != 0;
        s.doFlyingAnimation   = ((bits >> 17) & 1) != 0;
        s.isCeilingClimbing   = ((bits >> 18) & 1) != 0;
        s.isLevitating        = ((bits >> 19) & 1) != 0;
        s.isSliding           = ((bits >> 21) & 1) != 0;
        s.angleJumpType       = (int) ((bits >> 22) & 0x7)
                              | (int) (((bits >> 36) & 1) << 3);
        s.isFeetVineClimbing  = ((bits >> 25) & 1) != 0;
        s.isHandsVineClimbing = ((bits >> 26) & 1) != 0;
        s.isClimbJumping      = ((bits >> 27) & 1) != 0;
        s.isClimbBackJumping  = ((bits >> 28) & 1) != 0;
        s.isSlow              = ((bits >> 29) & 1) != 0;
        s.isFast              = ((bits >> 30) & 1) != 0;
        s.isWallJumping       = ((bits >> 31) & 1) != 0;
        s.isRopeSliding       = ((bits >> 32) & 1) != 0;
        s.isSneakButtonPressed = ((bits >> 33) & 1) != 0;
        s.isClimbCrawling      = ((bits >> 34) & 1) != 0;
        s.isChainClimbing      = ((bits >> 40) & 1) != 0;
        return s;
    }
}
