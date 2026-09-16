package choco.ratel.smartmoving.climbing;

import choco.ratel.smartmoving.config.SmartMovingConfig;
import java.util.HashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.IronBarsBlock;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.PipeBlock;
import net.minecraft.world.level.block.PressurePlateBlock;
import net.minecraft.world.level.block.SignBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.level.block.WallBlock;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.block.state.properties.WallSide;

/**
 * Direct 1:1 port of original `net.smart.moving.Orientation` (SmartMoving 1.7.10).
 *
 * **B-19a0 (session 90) scope**: Core structure — 9 constants + `_i`/`_k` + angle judgment helpers +
 * `rotate` + `getOrientation` + `getClimbingOrientations` + `getHorizontalBorderGap`.
 *
 * **Future sub-atomic scopes (not included)**:
 *   * B-19a1: `SmartMovingContext` helpers (isOnLadderOrVine / isOnOpenTrapDoor / isRope /
 *     isOnWallRope / isBaseAccessible / isFullAccessible / isFullExtentAccessible /
 *     isJustLowerHalfExtentAccessible / isFullEmpty / isSolid) port
 *   * B-19a2: `isLadderSubstitute` core + `hasHalfHold` / `hasBottomHold` — gap 1-5 calculation +
 *     `ClimbGap.canStand/mustCrawl` setting essentials
 *   * B-19a3: `handsClimbing()` / `feetClimbing()` judgment methods
 *   * B-19a4: `seekClimbGap` method + Climber.handleClimbing connection
 *
 * B-19a0 is a standalone class without extending `SmartMovingContext`. Upon B-19a1 completion,
 * `extends SmartMovingContext` will be reintroduced + `isTunnelAhead` / `getKnownLadderOrientation` /
 * `isFeetLadderSubstitute` / `isHandsLadderSubstitute` / `baseVineClimbing` etc. will be added.
 *
 * **Original reference**: `.tmp_research/Orientation.java.md` L37-L205 + L998-L1085.
 */
public class Orientation {

    // ── 9 Directional Constants (original L39-L49) ──────────────────────────

    /** Original L39 — Center (default value). Excluded from rotate. */
    public static final Orientation ZZ = new Orientation(0, 0);

    /** Original L41 — +X direction (East). */
    public static final Orientation PZ = new Orientation(1, 0);
    /** Original L42 — +Z direction (South). */
    public static final Orientation ZP = new Orientation(0, 1);
    /** Original L43 — -X direction (West). */
    public static final Orientation NZ = new Orientation(-1, 0);
    /** Original L44 — -Z direction (North). */
    public static final Orientation ZN = new Orientation(0, -1);

    /** Original L46 — +X+Z diagonal (SE). */
    public static final Orientation PP = new Orientation(1, 1);
    /** Original L47 — -X-Z diagonal (NW). */
    public static final Orientation NN = new Orientation(-1, -1);
    /** Original L48 — +X-Z diagonal (NE). */
    public static final Orientation PN = new Orientation(1, -1);
    /** Original L49 — -X+Z diagonal (SW). */
    public static final Orientation NP = new Orientation(-1, 1);

    // ── Meta / Internal Constants (original L51-L63) ─────────────────────────

    /** Original L51 — Unset (default). */
    public static final int DefaultMeta = -1;
    /** Original L52 — Vine is in front. */
    public static final int VineFrontMeta = 0;
    /** Original L53 — Vine is on side. */
    public static final int VineSideMeta = 1;

    /** Original L55-L59 — Vertical search offset (top=+2, middle=+1, base=0, sub=-1, subSub=-2). */
    protected static final int top    =  2;
    protected static final int middle =  1;
    protected static final int base   =  0;
    protected static final int sub    = -1;
    protected static final int subSub = -2;

    /** Original L61-L63 — Grab types. */
    protected static final int NoGrab     = 0;
    protected static final int HalfGrab   = 1;
    protected static final int AroundGrab = 2;

    // ── Orthogonals HashSet (original L65-L73) ──────────────────────────────

    /** Original L65-L73 — 4 orthogonal directions HashSet. Static initialization. */
    public static final HashSet<Orientation> Orthogonals = new HashSet<>();

    static {
        Orthogonals.add(PZ);
        Orthogonals.add(ZP);
        Orthogonals.add(NZ);
        Orthogonals.add(ZN);
    }

    // ── Instance Fields (original L75-L79) ────────────────────────────────

    /** Original L75 — X offset (-1/0/+1). */
    protected int _i;
    /** Original L75 — Z offset (-1/0/+1). */
    protected int _k;

    /**
     * X offset getter for external package access.
     * **Focus #3 B-7 (session 6)**: When porting BottomHold ladder 2-level check from SmartMovingClimber
     *   L963-L976, `ladderOrientation._i` access needed → public getter added.
     */
    public int getOffsetI() { return _i; }

    /** Z offset getter for external package access (Focus #3 B-7 session 6). */
    public int getOffsetK() { return _k; }
    /** Original L76 — Is diagonal (`_i != 0 && _k != 0`). */
    private boolean _isDiagonal;
    /** Original L77 — Direction angle (in degrees, 0-360). */
    private float _directionAngle;
    /** Original L78 — Minimum climbable angle. */
    private float _mimimumClimbingAngle;
    /** Original L79 — Maximum climbable angle. */
    private float _maximumClimbingAngle;

    // ── Constructor (original L81-L88) ──────────────────────────────────

    private Orientation(int i, int k) {
        _i = i;
        _k = k;
        _isDiagonal = _i != 0 && _k != 0;

        setClimbingAngles();
    }

    // ── Angle Calculation (original L998-L1062) ────────────────────────────

    /**
     * Original L998-L1037 `setClimbingAngles()` — Direction angle switch by `_i`/`_k` combination.
     * Original reference coordinate system: +X = West(NZ), +Z = South(ZP), 0° = South.
     *
     *   (-1,-1)=NN → 135°
     *   (-1, 0)=NZ →  90°
     *   (-1,+1)=NP →  45°
     *   ( 0,-1)=ZN → 180°
     *   ( 0, 0)=ZZ → 0~360 (all)
     *   ( 0,+1)=ZP →   0°
     *   (+1,-1)=PN → 225°
     *   (+1, 0)=PZ → 270°
     *   (+1,+1)=PP → 315°
     */
    private boolean setClimbingAngles() {
        switch (_i) {
            case -1:
                switch (_k) {
                    case -1: return setClimbingAngles(135F);
                    case  0: return setClimbingAngles( 90F);
                    case  1: return setClimbingAngles( 45F);
                }
                break;
            case 0:
                switch (_k) {
                    case -1: return setClimbingAngles(180F);
                    case  0: return setClimbingAngles(0F, 360F);
                    case  1: return setClimbingAngles(  0F);
                }
                break;
            case 1:
                switch (_k) {
                    case -1: return setClimbingAngles(225F);
                    case  0: return setClimbingAngles(270F);
                    case  1: return setClimbingAngles(315F);
                }
                break;
        }
        return false;
    }

    /**
     * Original L1039-L1047 `setClimbingAngles(float directionAngle)`.
     * Takes half of `_freeClimbingDiagonalDirectionAngle` (80F) / `_freeClimbingOrthogonalDirectionAngle`
     * (90F) config values and spreads it on both sides of forward direction angle to calculate min/max.
     */
    private boolean setClimbingAngles(float directionAngle) {
        _directionAngle = directionAngle;
        SmartMovingConfig cfg = SmartMovingConfig.Config;
        float halfAreaAngle = (_isDiagonal
                ? cfg.freeClimbingDiagonalDirectionAngle
                : cfg.freeClimbingOrthogonalDirectionAngle) / 2F;
        return setClimbingAngles(directionAngle - halfAreaAngle,
                directionAngle + halfAreaAngle);
    }

    /**
     * Original L1049-L1062 `setClimbingAngles(min, max)`. Normalizes negative/360+ values, then stores in fields.
     */
    private boolean setClimbingAngles(float mimimumClimbingAngle, float maximumClimbingAngle) {
        if (mimimumClimbingAngle < 0F)   mimimumClimbingAngle += 360F;
        if (maximumClimbingAngle > 360F) maximumClimbingAngle -= 360F;

        _mimimumClimbingAngle = mimimumClimbingAngle;
        _maximumClimbingAngle = maximumClimbingAngle;

        return mimimumClimbingAngle != maximumClimbingAngle;
    }

    /**
     * Original L1064-L1069 — Whether this orientation's forward angle (`_directionAngle`) is within
     * the given rotation range. Used in `getOrientation` to find direction matching player rotation tolerance.
     */
    private boolean isWithinAngle(float minimumRotation, float maximumRotation) {
        return isWithinAngle(_directionAngle, minimumRotation, maximumRotation);
    }

    /**
     * Original L1071-L1075 — Whether the given rotation is within this orientation's climbable angle range.
     * Used in `seekClimbGap` / `addTo`.
     */
    protected boolean isRotationForClimbing(float rotation) {
        return isWithinAngle(rotation, _mimimumClimbingAngle, _maximumClimbingAngle);
    }

    /**
     * Original L1077-L1085 — Whether rotation value is within [min, max] range. If min > max (crossing 0°
     * boundary), handles with OR logic.
     */
    private static boolean isWithinAngle(float rotation, float minimumRotation, float maximumRotation) {
        if (minimumRotation > maximumRotation)
            return rotation >= minimumRotation || rotation <= maximumRotation;
        return rotation >= minimumRotation && rotation <= maximumRotation;
    }

    // ── rotate (original L90-L165) ──────────────────────────────────────

    /**
     * Original L90-L165 — Returns result of rotating this orientation by `angle` degrees.
     * Supported angles: 0 / ±45 / ±90 / ±135 / ±180.
     * ZZ cannot be rotated (RuntimeException).
     */
    public Orientation rotate(int angle) {
        if (this == ZZ) throw new RuntimeException("unrotatable orientation");

        switch (angle) {
            case 0:
                return this;
            case 45:
                if (this == PZ) return PP;
                if (this == PP) return ZP;
                if (this == ZP) return NP;
                if (this == NP) return NZ;
                if (this == NZ) return NN;
                if (this == NN) return ZN;
                if (this == ZN) return PN;
                if (this == PN) return PZ;
                throw new RuntimeException("unknown orientation \"" + this + "\"");
            case -45:
                if (this == PZ) return PN;
                if (this == PN) return ZN;
                if (this == ZN) return NN;
                if (this == NN) return NZ;
                if (this == NZ) return NP;
                if (this == NP) return ZP;
                if (this == ZP) return PP;
                if (this == PP) return PZ;
                throw new RuntimeException("unknown orientation \"" + this + "\"");
            case 90:   return rotate(45).rotate(45);
            case -90:  return rotate(-45).rotate(-45);
            case 135:  return rotate(180).rotate(-45);
            case -135: return rotate(-180).rotate(45);
            case 180:
            case -180:
                if (this == PZ) return NZ;
                if (this == PN) return NP;
                if (this == ZN) return ZP;
                if (this == NN) return PP;
                if (this == NZ) return PZ;
                if (this == NP) return PN;
                if (this == ZP) return ZN;
                if (this == PP) return NN;
                throw new RuntimeException("unknown orientation");
        }
        throw new RuntimeException("angle \"" + angle + "\" not supported");
    }

    // ── getOrientation (original L167-L205) ─────────────────────────────────

    /**
     * Original L167-L205 — Returns matching Orientation based on player rotation angle + tolerance range.
     * Priority: orthogonal first (NZ/PZ/ZN/ZP), then diagonal (NP/PN/NN/PP).
     * Returns null if no match found.
     *
     * 1.21.1 mapping: `p.rotationYaw` → `player.getYaw()`.
     */
    public static Orientation getOrientation(Player p, float tolerance,
                                             boolean orthogonals, boolean diagonals) {
        float rotation = p.getYRot() % 360F;
        if (rotation < 0) rotation += 360F;

        float minimumRotation = rotation - tolerance;
        if (minimumRotation < 0) minimumRotation += 360F;

        float maximumRotation = rotation + tolerance;
        if (maximumRotation >= 360F) maximumRotation -= 360F;

        if (orthogonals) {
            if (NZ.isWithinAngle(minimumRotation, maximumRotation)) return NZ;
            if (PZ.isWithinAngle(minimumRotation, maximumRotation)) return PZ;
            if (ZN.isWithinAngle(minimumRotation, maximumRotation)) return ZN;
            if (ZP.isWithinAngle(minimumRotation, maximumRotation)) return ZP;
        }
        if (diagonals) {
            if (NP.isWithinAngle(minimumRotation, maximumRotation)) return NP;
            if (PN.isWithinAngle(minimumRotation, maximumRotation)) return PN;
            if (NN.isWithinAngle(minimumRotation, maximumRotation)) return NN;
            if (PP.isWithinAngle(minimumRotation, maximumRotation)) return PP;
        }
        return null;
    }

    // ── getClimbingOrientations + addTo (original L262-L297) ────────────────

    private static HashSet<Orientation> _getClimbingOrientationsHashSet = null;

    /**
     * Original L262-L289 — Returns set of climbable orientations based on player rotation angle.
     * orthogonals=true → checks NZ/PZ/ZN/ZP. diagonals=true → checks NP/PN/NN/PP.
     * Each orientation is added if its `_mimimumClimbingAngle ~ _maximumClimbingAngle` range contains rotation.
     *
     * **Note**: Returned HashSet is static cache reused — beware mutation between calls.
     * 1.21.1 mapping: `p.rotationYaw` → `player.getYaw()`.
     */
    public static HashSet<Orientation> getClimbingOrientations(Player p,
                                                               boolean orthogonals, boolean diagonals) {
        float rotation = p.getYRot() % 360F;
        if (rotation < 0) rotation += 360F;

        if (_getClimbingOrientationsHashSet == null)
            _getClimbingOrientationsHashSet = new HashSet<>();
        else
            _getClimbingOrientationsHashSet.clear();

        if (orthogonals) {
            NZ.addTo(rotation);
            PZ.addTo(rotation);
            ZN.addTo(rotation);
            ZP.addTo(rotation);
        }
        if (diagonals) {
            NP.addTo(rotation);
            PN.addTo(rotation);
            NN.addTo(rotation);
            PP.addTo(rotation);
        }
        return _getClimbingOrientationsHashSet;
    }

    /**
     * Original L293-L297 — Adds to static HashSet if `isRotationForClimbing(rotation)` is true.
     * Auxiliary method dedicated to `getClimbingOrientations`.
     */
    private void addTo(float rotation) {
        if (isRotationForClimbing(rotation))
            _getClimbingOrientationsHashSet.add(this);
    }

    // ── getHorizontalBorderGap (original L226-L247) ─────────────────────────

    /**
     * Original L226-L247 — Distance from player (i, k) position to block boundary in this direction.
     *   NZ: i%1 (fractional part of player X coordinate)
     *   PZ: 1 - i%1
     *   ZN: k%1
     *   ZP: 1 - k%1
     *   Other: 0
     *
     * Only meaningful for orthogonal 4-directions. Diagonal/ZZ returns 0.
     *
     * **Overload (static position-based)**: When calculation based on arbitrary coordinates rather than
     * entity position is needed, caller directly extracts i, k and calls static version. Instance-based
     * overload (original L231-L234 using `base_id`/`base_kd`) provided as getHorizontalBorderGap() (B-19a1a).
     */
    public double getHorizontalBorderGap(double i, double k) {
        if (this == NZ) return i % 1;
        if (this == PZ) return 1 - (i % 1);
        if (this == ZN) return k % 1;
        if (this == ZP) return 1 - (k % 1);
        return 0D;
    }

    /**
     * Original L231-L234 `getHorizontalBorderGap()` — Based on instance fields `base_id`/`base_kd`.
     * Pattern: upon `seekClimbGap` entry, `initialize(world, i, id, jhd, k, kd)` sets `base_id=id`/`base_kd=kd`,
     * then this is called.
     */
    public double getHorizontalBorderGap() {
        return getHorizontalBorderGap(base_id, base_kd);
    }

    // ════════════════════════════════════════════════════════════════════════
    // B-19a1a (session 91) — State fields + basic block identification/judgment helpers
    // Original: Orientation.java L2729-L2744 static fields + L1148-L2625 method equivalents
    // ════════════════════════════════════════════════════════════════════════

    // ── Instance State Fields (original L2729-L2744) ──────────────────────────
    //
    // Original is static fields in `Orientation extends SmartMovingContext`. In 1.21.1 there is
    // room for thread-safety improvement, but **1:1 port principle** is maintained — stored as static
    // identically. Caller pattern: `initialize(world, i, id, jhd, k, kd)` sets global state, then helpers called.

    /** Original L2729 — World currently being processed. Set in `initialize`. */
    protected static Level world;
    /** Original L2731 — Player reference Y (base_j). Absolute Y basis for `all_offset = local_offset`. */
    protected static int all_j;
    /** Original L2731 — Accumulated Y offset for current search target. Reset to 0 on `isLadderSubstitute` entry. */
    protected static int all_offset;
    /** Original L2732 — Base (player position) X. */
    protected static int base_i;
    /** Original L2732 — Base (player position) Z. */
    protected static int base_k;
    /** Original L2733 — Base X including fractional part (player exact position). */
    protected static double base_id;
    /** Original L2733 — Base Z including fractional part. */
    protected static double base_kd;
    /** Original L2734 — Remote (adjacent block in this Orientation direction) X. `base_i + _i`. */
    protected static int remote_i;
    /** Original L2734 — Remote Z. `base_k + _k`. */
    protected static int remote_k;
    /** Original L2735 — Player crawling state (isSmallClimbing). */
    protected static boolean crawl;
    /** Original L2738 — Combined with local_offset to determine actual Y coordinate (half-block unit search). */
    protected static int local_half;
    /** Original L2739 — Relative offset of `all_j + all_offset`. Varies with each `isLadderSubstitute` call. */
    protected static int local_offset;

    /** Original L2741 — Whether grab target is remote block (not base). */
    protected static boolean grabRemote;
    /** Original L2742 — Grab type (NoGrab/HalfGrab/AroundGrab). */
    protected static int grabType;
    /** Original L2743 — Grabbed block state. (Original is `Block` + `grabMeta` int pair; 1.21.1 is
     *  `BlockState` single object surface mapping — metadata embedded as BlockState property.) */
    protected static BlockState grabBlock;
    /** Original L2744 — `grabMeta` original Meta integer. In 1.21.1, accessible via property inside `grabBlock`,
     *  so `DefaultMeta` (=-1) default value retained. Detailed meta queries read `grabBlock` property directly. */
    protected static int grabMeta = DefaultMeta;

    /**
     * `jh_offset` — Vertical offset set in original `initializeOffset(double offset, ...)`
     * (relative to player boundingBox). Used in `handsClimbing` gap threshold judgment (`_handClimbingHoldGap`).
     * B-19a2b port complete.
     */
    protected static double jh_offset;

    /** Original L2730 `base_jhd` — Set in `initialize`. Consumed by `initializeOffset` (B-19a2b). */
    protected static double base_jhd;

    /** Original `local_halfOffset` — Intermediate value inside `initializeLocal` (B-19a2b). */
    protected static int local_halfOffset;

    /**
     * Original L2722-L2724 `_handClimbingHoldGap` — Config value-based static final threshold.
     * Used in `handsClimbing`/`feetClimbing` as `jh_offset` comparison basis (B-19a3).
     *
     *   Math.min(0.25F, 0.06F * Math.max(upSpeedFactor, downSpeedFactor))
     *
     * With Config default 1.0F/1.0F = 0.06F.
     */
    /**
     * 🔴 (2026-04-28) Changed static final → method.
     *   Original: modload → Config init → Orientation first use (static final evaluation) → 0.06 normal.
     *   In Fabric environment, SmartMovingConfig.Config uninitialized at class load time →
     *   freeClimbingUpSpeedFactor=0F (Java default) → _handClimbingHoldGap=0 → BottomHold
     *   branch (jh_offset<holdGap) never triggered → user report "grab only doesn't stop" direct cause.
     *   Solution: Evaluate every time (returns normal 0.06 after Config init).
     */
    private static float _handClimbingHoldGap() {
        return Math.min(0.25F,
                0.06F * Math.max(
                        SmartMovingConfig.Config.freeClimbingUpSpeedFactor,
                        SmartMovingConfig.Config.freeClimbingDownSpeedFactor));
    }

    /**
     * Original L2726-L2727 `_climbGapTemp` / `_climbGapOuterTemp` — static ClimbGap instances.
     * Reused in `handsClimbing`/`feetClimbing` (B-19a3) + `seekClimbGap` (B-19a4).
     */
    private static final ClimbGap _climbGapTemp = new ClimbGap();
    private static final ClimbGap _climbGapOuterTemp = new ClimbGap();

    // ── Block Identification Helpers (original L1188-L1214) ─────────────────

    /**
     * Original L1188-L1191 — `block == Block.getBlockFromName("ladder")`.
     * 1.21.1 mapping: `instanceof LadderBlock`. Identifies vanilla ladder blocks.
     */
    public static boolean isLadder(BlockState state) {
        return state != null && state.getBlock() instanceof LadderBlock;
    }

    /**
     * Original L1193-L1196 — Identifies vanilla vine blocks.
     */
    public static boolean isVine(BlockState state) {
        return state != null && state.getBlock() instanceof VineBlock;
    }

    /**
     * Original L1198-L1202 `isLadderOrVine` — Ladder/vine/LadderKit combination.
     *
     * **§7 approximate port (B-19a1a-approx-1)**: Original `isBlockIdOfType(block, _ladderKitLadderTypes)`
     * (LadderKit mod compatibility) not ported — mod not available in 1.21.1. Only detects pure vanilla ladder/vine.
     */
    public static boolean isLadderOrVine(BlockState state) {
        // Approximate port — difference from original: _ladderKitLadderTypes (LadderKit mod) excluded
        return isLadder(state) || isVine(state);
    }

    /**
     * Original L2241-L2244 `isTrapDoor(Block block)` — Identifies vanilla trap doors.
     * 1.21.1 `instanceof TrapdoorBlock` (covers all trapdoors: oak/iron/copper/bamboo etc.).
     */
    public static boolean isTrapDoor(BlockState state) {
        return state != null && state.getBlock() instanceof TrapDoorBlock;
    }

    /**
     * Original `isClosedTrapDoor(int metadata)` — 3rd bit (0x4) of metadata is OPEN state.
     * 1.21.1 `TrapdoorBlock.OPEN` property.
     */
    public static boolean isClosedTrapDoor(BlockState state) {
        return isTrapDoor(state) && !state.getValue(TrapDoorBlock.OPEN);
    }

    /**
     * Original L1210-L1215 — `block.isLadder(world, i, j, k, player)` Forge hook.
     *
     * **§7 approximate port (B-19a1a-approx-2)**: No equivalent hook in 1.21.1 Fabric.
     * Approximate using `BlockTags.CLIMBABLE` tag — detects vanilla ladder/vine + blocks mods add to tag.
     * Nearly equivalent to original intent (whether block is climbable by player).
     */
    public static boolean isClimbable(Level world, int i, int j, int k) {
        // Approximate port — difference from original: Forge Block.isLadder(world,x,y,z,player) hook → BlockTags.CLIMBABLE
        BlockState state = world.getBlockState(new BlockPos(i, j, k));
        return state.is(BlockTags.CLIMBABLE);
    }

    // ── Material / Solid Helpers (original L2155-L2175, L2616-L2619) ──────────

    /**
     * Original L2616-L2619 `isSolid(Material material)` — `material.isSolid() && blocksMovement()`.
     *
     * **§7 approximate port (B-19a1a-approx-3)**: Material API completely removed in 1.21.1 (1.19+).
     * `state.isFullCube(world, pos)` = "full cube collision shape" (opacity irrelevant) — equivalent to
     * original intent: Material.isSolid("fills space as solid") + blocksMovement("blocks movement").
     *
     * Previous mapping `state.isSolidBlock` additionally checks opacity (opaqueness), missing transparent+full-cube
     * blocks like leaves/glass/ice → user report "leaf block grab climbing not working".
     * 1.7.10 original had Material.leaves / Material.glass both isSolid=true for grabbing.
     */
    public static boolean isSolid(BlockState state, Level world, BlockPos pos) {
        // Approximate port — difference from original: Material API removed → state.isFullCube() (full cube collision, opacity irrelevant)
        return state != null && state.isCollisionShapeFullBlock(world, pos);
    }

    /**
     * Original L2155-L2175 `isFullEmpty(Block block)` — Block does not block player movement.
     *
     * Original check order:
     *   (1) null → true
     *   (2) `!isSolid(material)` → empty
     *   (3) Exception: standing_sign / wall_sign / pressure_plate → elevated to empty
     *   (4) Exception: ASGrapplingHook → empty
     *   (5) Exception: ASRope → non-empty (reverted to solid)
     *
     * **§7 approximate port (B-19a1a-approx-4)**: (4)(5) `hasASGrapplingHook`/`hasRopesPlus`
     * mod compatibility not ported — mods not available in 1.21.1 → check skipped.
     * (3) standing sign / wall sign detected via `AbstractSignBlock`/`WallSignBlock`.
     * Pressure plate via `PressurePlateBlock`.
     */
    public static boolean isFullEmpty(BlockState state, Level world, BlockPos pos) {
        if (state == null) return true;
        boolean empty = !isSolid(state, world, pos);
        if (!empty) {
            Block blk = state.getBlock();
            if (blk instanceof SignBlock)     empty = true;  // standing_sign + wall_sign unified
            else if (blk instanceof WallSignBlock)    empty = true;  // redundancy safety
            else if (blk instanceof PressurePlateBlock) empty = true;
            // Approximate port — difference from original: hasASGrapplingHook/hasRopesPlus mod check skipped
        }
        return empty;
    }

    // ── World Access Helpers (original L2621-L2639) ──────────────────────────

    /**
     * Original L2621-L2624 `getBlock(int i, int j_offset, int k)`.
     * Returns block at Y coordinate `local_offset + j_offset`.
     */
    protected static BlockState getBlock(int i, int j_offset, int k) {
        return world.getBlockState(new BlockPos(i, local_offset + j_offset, k));
    }

    /**
     * Original L2636-L2639 `getBaseBlockId(int j_offset)`. Returns block at base position (player position).
     * 1.21.1 mapping: `Block` → `BlockState` (metadata embedded).
     */
    protected static BlockState getBaseBlockId(int j_offset) {
        return world.getBlockState(new BlockPos(base_i, local_offset + j_offset, base_k));
    }

    /**
     * Original `getRemoteBlockId(int j_offset)` — Returns block at remote position (adjacent block).
     * 1.21.1 mapping: BlockState.
     */
    protected static BlockState getRemoteBlockId(int j_offset) {
        return world.getBlockState(new BlockPos(remote_i, local_offset + j_offset, remote_k));
    }

    // ── Basic Ladder/Vine Check (original L1148-L1186) ─────────────────────

    /**
     * Original L1148-L1158 `isOnLadder(int j_offset)` — Whether ladder exists at base position.
     *   (1) `isLadder(baseBlock)` → true
     *   (2) `isVine(baseBlock)` → false (vine checked separately)
     *   (3) `isClimbable(world, base_i, Y, base_k)` → true (Forge ladder hook)
     *   (4) → false
     */
    protected static boolean isOnLadder(int j_offset) {
        BlockState state = getBaseBlockId(j_offset);
        if (isLadder(state)) return true;
        if (isVine(state))   return false;
        if (isClimbable(world, base_i, local_offset + j_offset, base_k)) return true;
        return false;
    }

    /**
     * Original L1172-L1175 `isOnVine(int j_offset)` — Whether vine exists at base position.
     */
    protected static boolean isOnVine(int j_offset) {
        return isVine(getBaseBlockId(j_offset));
    }

    /**
     * Original L1182-L1186 `isOnLadderOrVine(int j_offset)`.
     * `isLadderOrVine(baseBlock) || isVine(grabBlock)` — ladder/vine at base or already
     * holding grabBlock that is vine.
     */
    protected static boolean isOnLadderOrVine(int j_offset) {
        return isLadderOrVine(getBaseBlockId(j_offset)) || isVine(grabBlock);
    }

    // ════════════════════════════════════════════════════════════════════════
    // B-19a1b (session 92) — front/back/rope/trapdoor instance helpers
    // Original: Orientation.java L1160-L1180 (Behind), L1217-L1238 (Front/Back instance),
    //           L1274-L1359 (orientation reverse mapping), L1361-L1384 (Remote/TrapDoor orientation),
    //           L1443-L1485 (Rope), L1513-L1541 (TrapDoor front)
    // ════════════════════════════════════════════════════════════════════════

    // ── Ladder Orientation Reverse Mapping (original L1274-L1359) ───────────

    /**
     * Original L1325-L1359 `hasLadderOrientation(int i, int j_offset, int k)` — Judges whether
     * ladder at (i, j_offset, k) is **accessible from this orientation**.
     *
     * Original mapping (vanilla 1.7.10 ladder `metadata & 0x7`):
     *   5 → NZ (EAST facing → approach from west)
     *   4 → PZ (WEST facing → approach from east)
     *   2 → ZP (NORTH facing → approach from south)
     *   3 → ZN (SOUTH facing → approach from north)
     *
     * 1.21.1 mapping: `state.get(LadderBlock.FACING)` Direction compared directly. Opposite of facing direction
     * = player search direction.
     *
     * **§7 approximate** (B-19a1b-approx-1): Original `_ladderKitLadderTypes` branch + `carpentersBlockData`
     * branch skipped (LadderKit / Carpenters mods not ported).
     */
    private boolean hasLadderOrientation(int i, int j_offset, int k) {
        BlockState state = getBlock(i, j_offset, k);
        if (!isLadder(state)) return false;
        Direction facing = state.getValue(LadderBlock.FACING);
        if (this == NZ) return facing == Direction.EAST;
        if (this == PZ) return facing == Direction.WEST;
        if (this == ZP) return facing == Direction.NORTH;
        if (this == ZN) return facing == Direction.SOUTH;
        return false;
    }

    /**
     * Original L1311-L1323 `hasVineOrientation(World world, int i, int j, int k)` — Whether vine
     * at (i, j, k) is attached in this Orientation direction.
     *
     * Original mapping (vanilla 1.7.10 vine `metadata` bits):
     *   bit 0 (0x1) = SOUTH → ZP
     *   bit 1 (0x2) = WEST  → NZ
     *   bit 2 (0x4) = NORTH → ZN
     *   bit 3 (0x8) = EAST  → PZ
     *
     * 1.21.1 mapping: `VineBlock.NORTH/SOUTH/EAST/WEST` BooleanProperty queried directly.
     *
     * **Instance method** + `world` parameter (no static state dependency) — callable
     * outside static context.
     */
    public boolean hasVineOrientation(Level world, int i, int j, int k) {
        BlockState state = world.getBlockState(new BlockPos(i, j, k));
        if (!isVine(state)) return false;
        if (this == NZ) return state.getValue(VineBlock.WEST);
        if (this == PZ) return state.getValue(VineBlock.EAST);
        if (this == ZP) return state.getValue(VineBlock.SOUTH);
        if (this == ZN) return state.getValue(VineBlock.NORTH);
        return false;
    }

    /**
     * Original L1274-L1309 `getKnownLadderOrientation(World world, int i, int j, int k)` —
     * Reverse maps ladder facing direction at (i, j, k) to Orientation.
     *
     * Original `metadata & 0x7` mapping same as `hasLadderOrientation`. 1.21.1 uses `LadderBlock.FACING`
     * directly.
     *
     * **§7 approximate** (B-19a1b-approx-1): `_ladderKitLadderTypes` (`metadata & 0x3`) branch skipped.
     * Only pure vanilla ladder reverse-mapped.
     */
    public static Orientation getKnownLadderOrientation(Level world, int i, int j, int k) {
        BlockState state = world.getBlockState(new BlockPos(i, j, k));
        if (!isLadder(state)) return null;
        Direction facing = state.getValue(LadderBlock.FACING);
        switch (facing) {
            case EAST:  return NZ;
            case WEST:  return PZ;
            case NORTH: return ZP;
            case SOUTH: return ZN;
            default:    return null;
        }
    }

    // ── Ladder/Vine front/back + Behind (original L1160-L1180, L1217-L1238) ─

    /** Original L1217-L1220 — Whether ladder at base position is front-facing in this direction. */
    protected boolean isOnLadderFront(int j_offset) {
        return hasLadderOrientation(base_i, j_offset, base_k);
    }

    /**
     * Original L1222-L1226 — Whether ladder at remote position is front-facing relative to
     * **opposite** direction (rotate(180)). I.e., determines "ladder graspable from opposite side".
     */
    protected boolean isOnLadderBack(int j_offset) {
        return rotate(180).hasLadderOrientation(remote_i, j_offset, remote_k);
    }

    /** Original L1228-L1232 — Whether vine at base position is attached in this direction. */
    protected boolean isOnVineFront(int j_offset) {
        return hasVineOrientation(world, base_i, local_offset + j_offset, base_k);
    }

    /** Original L1234-L1238 — Whether vine at remote position is attached from opposite direction. */
    protected boolean isOnVineBack(int j_offset) {
        return rotate(180).hasVineOrientation(world, remote_i, local_offset + j_offset, remote_k);
    }

    /** Original L1160-L1170 — Whether ladder exists at remote position (for rear ladder search). */
    protected static boolean isBehindLadder(int j_offset) {
        BlockState state = getRemoteBlockId(j_offset);
        if (isLadder(state)) return true;
        if (isVine(state))   return false;
        if (isClimbable(world, remote_i, local_offset + j_offset, remote_k)) return true;
        return false;
    }

    /** Original L1177-L1180 — Whether vine exists at remote position. */
    protected static boolean isBehindVine(int j_offset) {
        return isVine(getRemoteBlockId(j_offset));
    }

    // ── Rope / WallRope — §7 B-19a1b-approx-2 All-false Approximate ────────

    /**
     * Original L1443-L1446 `isRope(int j_offset)` — Checks `fcRopeBlock` (BetterThanWolves) /
     * `blockRopeCentral` (RopesPlus) mod blocks.
     *
     * **§7 approximate port** (B-19a1b-approx-2): Mods not ported to 1.21.1 → all false.
     */
    protected static boolean isRope(int j_offset) {
        // Approximate port — difference from original: BetterThanWolves/RopesPlus mod blocks not ported
        return false;
    }

    /**
     * Original L1470-L1475 `isOnWallRope(int j_offset)` — `blockRope` (ASRope) wall-climbing rope block.
     *
     * **§7 approximate port** (B-19a1b-approx-2): ASRope mod not ported to 1.21.1 → all false.
     */
    protected static boolean isOnWallRope(int j_offset) {
        // Approximate port — difference from original: ASRope mod blocks not ported
        return false;
    }

    // ── TrapDoor (original L1367-L1384, L1513-L1541) ───────────────────────

    /**
     * Original L1513-L1517 `isOnOpenTrapDoor(int j_offset)` — Open trap door exists at base position.
     */
    protected static boolean isOnOpenTrapDoor(int j_offset) {
        BlockState state = getBaseBlockId(j_offset);
        return isTrapDoor(state) && !isClosedTrapDoor(state);
    }

    /**
     * Original L1519-L1541 `isTrapDoorFront(int trapDoorMetadata)` — Whether trap door facing
     * matches this Orientation direction.
     *
     * Original metadata mapping (vanilla 1.7.10 `metadata & 3`):
     *   0 = SOUTH, 1 = NORTH, 2 = EAST, 3 = WEST
     *
     * Orthogonal: single direction match. Diagonal: either of 2 directions match.
     *
     * 1.21.1 mapping: `TrapdoorBlock.FACING` Direction compared directly. Original takes int metadata,
     * 1.21.1 embeds BlockState object so modified to BlockState parameter (surface mapping).
     */
    private boolean isTrapDoorFront(BlockState state) {
        if (!isTrapDoor(state)) return false;
        Direction facing = state.getValue(TrapDoorBlock.FACING);
        if (this == NZ) return facing == Direction.WEST;
        if (this == PZ) return facing == Direction.EAST;
        if (this == ZP) return facing == Direction.SOUTH;
        if (this == ZN) return facing == Direction.NORTH;
        if (this == PN) return facing == Direction.EAST || facing == Direction.NORTH;
        if (this == PP) return facing == Direction.EAST || facing == Direction.SOUTH;
        if (this == NN) return facing == Direction.WEST || facing == Direction.NORTH;
        if (this == NP) return facing == Direction.WEST || facing == Direction.SOUTH;
        return false;
    }

    /**
     * Original L1366-L1384 `getOpenTrapDoorOrientation(World world, int i, int j, int k)` —
     * Reverse maps open trap door facing direction to Orientation.
     *
     * Original mapping:
     *   0 (SOUTH) → ZP, 1 (NORTH) → ZN, 2 (EAST) → PZ, 3 (WEST) → NZ
     */
    public static Orientation getOpenTrapDoorOrientation(Level world, int i, int j, int k) {
        BlockState state = world.getBlockState(new BlockPos(i, j, k));
        if (!isTrapDoor(state) || isClosedTrapDoor(state)) return null;
        Direction facing = state.getValue(TrapDoorBlock.FACING);
        switch (facing) {
            case SOUTH: return ZP;
            case NORTH: return ZN;
            case EAST:  return PZ;
            case WEST:  return NZ;
            default:    return null;
        }
    }

    /**
     * Original L1361-L1364 `isRemoteSolid(World world, int i, int j, int k)` — Whether block
     * at (i+_i, j, k+_k) is solid (adjacent block in this direction).
     *
     * Material API removal approximate (linked to B-19a1a-approx-3 — `isSolidBlock`).
     */
    public boolean isRemoteSolid(Level world, int i, int j, int k) {
        BlockPos pos = new BlockPos(i + _i, j, k + _k);
        BlockState state = world.getBlockState(pos);
        return isSolid(state, world, pos);
    }

    // ════════════════════════════════════════════════════════════════════════
    // B-19a1c1 (session 93) — Block identification + stair/slab/fence/wall/door helpers
    // Original: Orientation.java L1544-L1615 (stair metadata), L2026-L2063 (slab),
    //           L2177-L2222 (fence/fenceGate), L2289-L2323 (door/doorFrontBlocked),
    //           L2333-L2340 (wallBlock).
    //
    // 1.21.1 mapping essentials:
    //   - vanilla 1.7.10 stair `metadata & 3` (0=EAST/1=WEST/2=SOUTH/3=NORTH) ↔
    //     `StairsBlock.FACING` Direction property
    //   - `metadata & 4` (TOP half bit) ↔ `StairsBlock.HALF == BlockHalf.TOP`
    //   - slab `metadata & 8` ↔ `SlabBlock.TYPE == SlabType.TOP/BOTTOM`
    //   - door `metadata == 8` (upper marker) ↔ `DoorBlock.HALF == DoubleBlockHalf.UPPER`
    //   - fenceGate `metadata & 4 == 0` (closed) ↔ `!FenceGateBlock.OPEN`
    // ════════════════════════════════════════════════════════════════════════

    // ── Stair (original L1544-L1615) ────────────────────────────────────────

    /**
     * Original L2059-L2063 `isStairCompact(Block)`.
     *
     * **§7 approximate** (B-19a1c1-approx-1): Original `_knownCompactStairBlocks` (mod-added stair
     * list) check skipped — mods not ported to 1.21.1. Only vanilla StairsBlock detected.
     */
    public static boolean isStairCompact(BlockState state) {
        // Approximate port — difference from original: _knownCompactStairBlocks (mod stair) excluded
        return state != null && state.getBlock() instanceof StairBlock;
    }

    /**
     * Original L1612-L1615 `isTopStairCompact(int stairMetadata)` — `(metadata & 4) != 0`.
     * 1.21.1: `StairsBlock.HALF == BlockHalf.TOP`.
     */
    public static boolean isTopStairCompact(BlockState state) {
        return isStairCompact(state) && state.getValue(StairBlock.HALF) == Half.TOP;
    }

    /**
     * Original L1568-L1588 `isStairCompactFront(int stairMetadata)` — Interprets `metadata & 3` as
     * facing direction and checks if it matches this Orientation.
     *
     * Original mapping (`metadata & 3`):
     *   0 = EAST  → PZ
     *   1 = WEST  → NZ
     *   2 = SOUTH → ZP
     *   3 = NORTH → ZN
     *
     * 1.21.1 mapping: `StairsBlock.FACING` Direction compared directly.
     * Diagonal is 2-direction OR.
     */
    public boolean isStairCompactFront(BlockState state) {
        if (!isStairCompact(state)) return false;
        Direction facing = state.getValue(StairBlock.FACING);
        if (this == NZ) return facing == Direction.WEST;
        if (this == PZ) return facing == Direction.EAST;
        if (this == ZP) return facing == Direction.SOUTH;
        if (this == ZN) return facing == Direction.NORTH;
        if (this == PN) return facing == Direction.EAST  || facing == Direction.NORTH;
        if (this == PP) return facing == Direction.EAST  || facing == Direction.SOUTH;
        if (this == NN) return facing == Direction.WEST  || facing == Direction.NORTH;
        if (this == NP) return facing == Direction.WEST  || facing == Direction.SOUTH;
        return false;
    }

    /**
     * Original L1590-L1610 `isStairCompactBack` — When facing is search **opposite** direction.
     */
    public boolean isStairCompactBack(BlockState state) {
        if (!isStairCompact(state)) return false;
        Direction facing = state.getValue(StairBlock.FACING);
        if (this == NZ) return facing == Direction.EAST;
        if (this == PZ) return facing == Direction.WEST;
        if (this == ZP) return facing == Direction.NORTH;
        if (this == ZN) return facing == Direction.SOUTH;
        if (this == PN) return facing == Direction.WEST  || facing == Direction.SOUTH;
        if (this == PP) return facing == Direction.WEST  || facing == Direction.NORTH;
        if (this == NN) return facing == Direction.EAST  || facing == Direction.SOUTH;
        if (this == NP) return facing == Direction.EAST  || facing == Direction.NORTH;
        return false;
    }

    /** Original L1556-L1560 — `isTopStairCompact && isStairCompactFront`. */
    public boolean isTopStairCompactFront(BlockState state) {
        return isTopStairCompact(state) && isStairCompactFront(state);
    }

    /** Original L1562-L1566 — `isTopStairCompact && isStairCompactBack`. */
    public boolean isTopStairCompactBack(BlockState state) {
        return isTopStairCompact(state) && isStairCompactBack(state);
    }

    /** Original L1550-L1554 — `!isTopStairCompact && isStairCompactFront` (bottom half + front). */
    public boolean isBottomStairCompactFront(BlockState state) {
        return isStairCompact(state) && !isTopStairCompact(state) && isStairCompactFront(state);
    }

    /** Original L1544-L1548 — `!isTopStairCompact && !isStairCompactBack`. */
    public boolean isBottomStairCompactNotBack(BlockState state) {
        return isStairCompact(state) && !isTopStairCompact(state) && !isStairCompactBack(state);
    }

    // ── Slab (original L2026-L2056) ───────────────────────────────────────

    /**
     * Original L2053-L2056 `isHalfBlock(Block)` — `BlockSlab && !isOpaqueCube()`.
     * 1.21.1: `instanceof SlabBlock` + `SlabType != DOUBLE` (double slab is full block).
     *
     * **§7 approximate** (B-19a1c1-approx-2): Original `_knownHalfBlocks` (mod slab) check skipped.
     */
    public static boolean isHalfBlock(BlockState state) {
        // Approximate port — difference from original: _knownHalfBlocks (mod slab) excluded
        if (state == null || !(state.getBlock() instanceof SlabBlock)) return false;
        return state.getValue(SlabBlock.TYPE) != SlabType.DOUBLE;
    }

    /**
     * Original L2038-L2041 `isTopHalfBlock(Block, int metadata)` — `isHalfBlock && (metadata & 8) != 0`.
     * 1.21.1: `SlabType.TOP`.
     */
    public static boolean isTopHalfBlock(BlockState state) {
        return isHalfBlock(state) && state.getValue(SlabBlock.TYPE) == SlabType.TOP;
    }

    /**
     * Original L2026-L2036 `isBottomHalfBlock(Block, int metadata)`.
     *   (1) `isHalfBlock && (metadata & 8) == 0`
     *   (2) `block == bed` (BedBlock)
     *   (3) BetterThanWolves anchor + metadata == 1 (mod) — **§7 approximate skipped**
     *
     * **§7 approximate** (B-19a1c1-approx-3): BetterThanWolves anchor check skipped. Vanilla BedBlock
     * exception 1:1 ported via `instanceof BedBlock`.
     */
    public static boolean isBottomHalfBlock(BlockState state) {
        if (state == null) return false;
        if (isHalfBlock(state) && state.getValue(SlabBlock.TYPE) == SlabType.BOTTOM) return true;
        // Approximate port — difference from original: BetterThanWolves anchor excluded
        return state.getBlock() instanceof net.minecraft.world.level.block.BedBlock;
    }

    // ── Fence / FenceGate (original L2177-L2222) ──────────────────────────

    /**
     * Original L2177-L2181 `isFenceBase(Block)` — `FenceBlock || WallBlock`.
     */
    public static boolean isFenceBase(BlockState state) {
        return state != null && (state.getBlock() instanceof FenceBlock
                              || state.getBlock() instanceof WallBlock);
    }

    /**
     * Original L2208-L2212 `isFenceGate(Block)` — `FenceGateBlock` (+ mod list approximation excluded).
     */
    public static boolean isFenceGate(BlockState state) {
        return state != null && state.getBlock() instanceof FenceGateBlock;
    }

    /** Original L2219-L2222 `isClosedFenceGate(int metadata)` — `(metadata & 4) == 0`. */
    public static boolean isClosedFenceGate(BlockState state) {
        return isFenceGate(state) && !state.getValue(FenceGateBlock.OPEN);
    }

    /** Original L2214-L2217 `isOpenFenceGate(Block, int metadata)`. */
    public static boolean isOpenFenceGate(BlockState state) {
        return isFenceGate(state) && state.getValue(FenceGateBlock.OPEN);
    }

    /** Original L2183-L2187 `isFence(Block, int i, int j_offset, int k)` — base + closed gate. */
    public static boolean isFence(BlockState state) {
        return isFenceBase(state) || isClosedFenceGate(state);
    }

    /** Original L2189-L2192 `isFence(int i, int j_offset, int k)` — Coordinate-based wrapper. */
    public static boolean isFence(int i, int j_offset, int k) {
        return isFence(getBlock(i, j_offset, k));
    }

    // ── Wall / Door (original L2289-L2340) ─────────────────────────────────

    /**
     * Original L2333-L2340 `isWallBlock(Block, int i, int j_offset, int k)`.
     *   `BlockPane || isFence(block, i, j_offset, k) || (Carpenters && ...)`.
     *
     * **§7 approximate** (B-19a1c1-approx-4): Carpenters `_blockCarpentersLadder` branch +
     * `_knownThinWallBlocks` (mod pane list) skipped. Only vanilla PaneBlock + FenceBlock +
     * WallBlock (FenceBase) + closed FenceGate.
     */
    public static boolean isWallBlock(BlockState state) {
        // Approximate port — difference from original: _knownThinWallBlocks + _blockCarpentersLadder excluded
        if (state == null) return false;
        return state.getBlock() instanceof IronBarsBlock || isFence(state);
    }

    /**
     * Original L2289-L2293 `isDoor(Block)` — vanilla wooden_door / iron_door.
     * 1.21.1: `DoorBlock` covers oak/spruce/birch/... + iron + copper etc.
     */
    public static boolean isDoor(BlockState state) {
        return state != null && state.getBlock() instanceof DoorBlock;
    }

    /**
     * Original L2295-L2298 `isDoorTop(int metaData)` — `metaData == 8` (upper half marker).
     * 1.21.1: `DoorBlock.HALF == DoubleBlockHalf.UPPER`.
     */
    public static boolean isDoorTop(BlockState state) {
        return isDoor(state) && state.getValue(DoorBlock.HALF) == DoubleBlockHalf.UPPER;
    }

    // ════════════════════════════════════════════════════════════════════════
    // B-19a1c2 (session 94) — isEmpty + isBaseAccessible + coordinate-based trapdoor wrappers
    // Original: Orientation.java L2224-L2238 (trapdoor coordinate wrappers),
    //           L2342-L2399 (isBaseAccessible 2 overloads), L2537-L2541 (isEmpty).
    // ════════════════════════════════════════════════════════════════════════

    // ── Trapdoor Coordinate-based Wrappers (original L2224-L2238) ─────────

    /** Original L2236-L2238 `isTrapDoor(int i, int j_offset, int k)` — Coordinate → BlockState wrapper. */
    protected static boolean isTrapDoor(int i, int j_offset, int k) {
        return isTrapDoor(getBlock(i, j_offset, k));
    }

    /** Original L2230-L2233 `isClosedTrapDoor(int i, int j_offset, int k)` — Coordinate wrapper. */
    protected static boolean isClosedTrapDoor(int i, int j_offset, int k) {
        BlockState state = getBlock(i, j_offset, k);
        return isTrapDoor(state) && isClosedTrapDoor(state);
    }

    /** Original L2224-L2227 `isOpenTrapDoor(int i, int j_offset, int k)` — Coordinate wrapper. */
    protected static boolean isOpenTrapDoor(int i, int j_offset, int k) {
        BlockState state = getBlock(i, j_offset, k);
        return isTrapDoor(state) && !isClosedTrapDoor(state);
    }

    // ── isFullEmpty Coordinate Overload (B-19a1a extension — using static world) ─

    /**
     * Overloads original single-parameter `isFullEmpty(Block)` to coordinate-based.
     * Passes to 1.21.1 `isFullEmpty(BlockState, World, BlockPos)`. Simplifies call sites.
     */
    protected static boolean isFullEmpty(int i, int j_offset, int k) {
        BlockPos pos = new BlockPos(i, local_offset + j_offset, k);
        return isFullEmpty(world.getBlockState(pos), world, pos);
    }

    // ── isEmpty (original L2537-L2541) ────────────────────────────────────

    /**
     * Original L2537-L2541 `isEmpty(int i, int j_offset, int k)`:
     *   `isFullEmpty(getBlock(i, j_offset, k)) && !isFence(i, j_offset - 1, k)`.
     *
     * Space player can traverse + directly below is not fence (fence is 1.5-high → blocks).
     */
    protected static boolean isEmpty(int i, int j_offset, int k) {
        // 🔴 User requirement: fence ≡ iron_bars same behavior.
        //   Original only checks `!isFence(i, j-1, k)` (fence 1.5 height → blocks above space).
        //   iron_bars also 1.5 height should block above space equally, but not in isFence → isEmpty=true.
        //   → User standing on fence grab+W: isEmpty(base,0)=false → hasHalfHold L2421
        //     not entered → L2427 wallState branch (grabRemote=false) active.
        //   → User standing on iron_bars grab+W: isEmpty(base,0)=true → L2421 entered
        //     (grabRemote=true) → different isLadderSubstitute gap → different motion → different speed.
        //   Solution: Block iron_bars same as fence → both unified to L2427 branch.
        BlockState belowState = getBlock(i, j_offset - 1, k);
        return isFullEmpty(i, j_offset, k)
            && !isFence(belowState)
            && belowState.getBlock() != Blocks.IRON_BARS;
    }

    // ── isBaseAccessible 2 Overloads (original L2342-L2399) ─────────────────

    /**
     * Original L2342-L2345 `isBaseAccessible(int j_offset)` — Simple wrapper, bottom=false/full=false.
     */
    protected static boolean isBaseAccessible(int j_offset) {
        return isBaseAccessible(j_offset, false, false);
    }

    /**
     * Original L2347-L2399 `isBaseAccessible(int j_offset, boolean bottom, boolean full)` —
     * Whether base position is space player can occupy.
     *
     * 7 branches (OR accumulated):
     *   (1) `isEmpty(base_i, j_offset, base_k)` — Empty space + no fence below
     *   (2) [§7 approximate skipped] RedPower wire special handling
     *   (3) `isFullEmpty(baseBlock)` — Block space itself empty
     *   (4) `isOpenTrapDoor(base_i, j_offset, base_k)` — Open trap door
     *   (5) bottom && `isClosedTrapDoor(base_i, j_offset, base_k)` — bottom flag + closed trapdoor
     *   (6) !full && `isWallBlock(baseBlock, base_i, j_offset, base_k)` — full=false + thin wall/fence
     *   (7) [§7 approximate skipped] ASRope — !full && mod rope
     *   (8) `isDoor(baseBlock)` — door block
     *   (9) [§7 approximate skipped] Carpenters `_blockCarpentersLadder`
     *
     * **§7 B-19a1c2 3 approximates**:
     *   - RedPower wire special judgment (`isRedPowerWire`/`getRpCoverSides`/`isRedPowerWireBottom/Top`
     *     chain) entirely skipped — RedPower mod not ported to 1.21.1
     *   - ASRope branch skipped — ASRope mod not ported to 1.21.1 (linked to B-19a1b `isRope`/`isOnWallRope`
     *     false approximate)
     *   - Carpenters `_blockCarpentersLadder` branch skipped — Carpenters mod not ported to 1.21.1
     */
    protected static boolean isBaseAccessible(int j_offset, boolean bottom, boolean full) {
        BlockState id = getBaseBlockId(j_offset);
        boolean accessible = isEmpty(base_i, j_offset, base_k);

        // Approximate port — difference from original: RedPower wire branch (original L2352-L2369) skipped

        if (!accessible && isFullEmpty(getBaseBlockId(j_offset), world,
                new BlockPos(base_i, local_offset + j_offset, base_k)))
            accessible = true;

        if (!accessible && isOpenTrapDoor(base_i, j_offset, base_k))
            accessible = true;

        if (!accessible && bottom && isClosedTrapDoor(base_i, j_offset, base_k))
            accessible = true;

        if (!accessible && !full && isWallBlock(id))
            accessible = true;

        // Approximate port — difference from original: ASRope branch (original L2385-L2389) skipped

        if (!accessible && isDoor(id))
            accessible = true;

        // Approximate port — difference from original: Carpenters _blockCarpentersLadder branch (original L2394-L2396) skipped

        return accessible;
    }

    // ════════════════════════════════════════════════════════════════════════
    // B-19a1c3a (session 95) — remoteLadderClimbing + isAccessAccessible + isDoorFrontBlocked
    // Original: Orientation.java L1115-L1118 (remoteLadderClimbing),
    //       L2477-L2484 (isAccessAccessible), L2300-L2323 (isDoorFrontBlocked).
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Original L1115-L1118 `remoteLadderClimbing(int j_offset)` — ladder exists at remote position and
     * that ladder is attached in **opposite player direction (back)**.
     *
     * Meaning: when searching in this Orientation direction, if ladder at remote position is back-facing then
     * considered "currently climbing" (blocks passage in this direction).
     *
     * Dependencies: B-19a1b `isBehindLadder` + `isOnLadderBack`.
     */
    protected boolean remoteLadderClimbing(int j_offset) {
        return isBehindLadder(j_offset) && isOnLadderBack(j_offset);
    }

    /**
     * Original L2477-L2484 `isAccessAccessible(int j_offset)` — needed for diagonal direction approach.
     * Always true for orthogonal. For diagonal, both perpendicular boundaries (remote_i/base_k
     * and base_i/remote_k) must be empty — diagonal entry requires both adjacent orthogonal
     * positions passable.
     */
    protected boolean isAccessAccessible(int j_offset) {
        if (!_isDiagonal) return true;
        return isEmpty(remote_i, j_offset, base_k)
            && isEmpty(base_i, j_offset, remote_k);
    }

    // ════════════════════════════════════════════════════════════════════════
    // B-19a1c3b (session 96) — wall-flag infrastructure
    // Original: Orientation.java L1727-L1738 (isFenceGateFront), L1801-L1807 (headedToWall),
    //       L1953-L1999 (getWallFlag), L2001-L2004 (getAllWallsOnNoWall),
    //       L2006-L2009 (isTopHalf).
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Orientation → 1.21.1 Direction mapping (orthogonal 4 directions only). diagonal/ZZ is null.
     * Internal use only. Needed for `getWallFlag` / fence gate etc. property queries.
     *
     * Reference:
     *   NZ (-X, west search) ↔ Direction.WEST
     *   PZ (+X, east search) ↔ Direction.EAST
     *   ZN (-Z, north search) ↔ Direction.NORTH
     *   ZP (+Z, south search) ↔ Direction.SOUTH
     */
    private Direction toBlockDirection() {
        if (this == NZ) return Direction.WEST;
        if (this == PZ) return Direction.EAST;
        if (this == ZN) return Direction.NORTH;
        if (this == ZP) return Direction.SOUTH;
        return null;
    }

    /**
     * Original L1727-L1738 `isFenceGateFront(int metaData)` — whether fence gate is aligned with
     * this Orientation search direction.
     *
     * Original `metadata % 4`:
     *   0 = SOUTH facing, 1 = WEST, 2 = NORTH, 3 = EAST
     *   NZ/PZ → 0 or 2 (EW axis gate = SOUTH/NORTH facing) — passable from west/east
     *   ZP/ZN → 1 or 3 (NS axis gate = WEST/EAST facing) — passable from south/north
     *
     * 1.21.1 mapping: `FenceGateBlock.FACING` Direction. Diagonal directions false (same as original).
     */
    private boolean isFenceGateFront(BlockState state) {
        if (!isFenceGate(state)) return false;
        Direction facing = state.getValue(FenceGateBlock.FACING);
        if (this == NZ || this == PZ)
            return facing == Direction.SOUTH || facing == Direction.NORTH;
        if (this == ZP || this == ZN)
            return facing == Direction.WEST  || facing == Direction.EAST;
        return false;
    }

    /**
     * Original L1801-L1807 `headedToWall(Orientation base, boolean result)` — if this Orientation
     * equals `base` or ±45° rotation result, return `result`, otherwise false.
     *
     * Example: base=NZ, if this is one of NZ/NN/NP then return result.
     */
    private boolean headedToWall(Orientation base, boolean result) {
        if (this == base || this == base.rotate(45) || this == base.rotate(-45))
            return result;
        return false;
    }

    /**
     * Original L2001-L2004 `getAllWallsOnNoWall(Block block)` — `block instanceof BlockPane`.
     *
     * Meaning: pane block returns 4-direction "wall flag" all off when no adjacent connection targets,
     * signaling caller to reinterpret as "all-direction wall assumption". Used in `headedToFrontWall`.
     */
    private static boolean getAllWallsOnNoWall(BlockState state) {
        // 🔴 User request: fence ≡ iron_bars identical behavior.
        //   Original checks `block instanceof BlockPane` only (iron_bars/glass_pane). fence alone
        //   `allOnNone=false` → headedToFrontWall 4-direction force true not applied → fence climbing
        //   itself hasHalfHold unified branch L2421 (isEmpty+headedToFrontWall) not met → different branch
        //   fall through → different grabRemote/location → different motion → different speed from iron_bars.
        //   Solution: add fence so fence alone also force true applied → same branch as iron_bars.
        return state != null && (state.getBlock() instanceof IronBarsBlock || isFence(state));
    }

    /**
     * Original L2006-L2009 `isTopHalf(double d)` — coordinate decimal part's half-block grid top/bottom judgment.
     *
     *   d = coordinate (i or k). Determines top status by odd/even of doubled absolute value floor.
     *   ex: d = 0.3 → 0.6 → 0 (bottom), d = 0.7 → 1.4 → 1 (top).
     *
     * Pure math — 1:1 port.
     */
    private static boolean isTopHalf(double d) {
        return (int)Math.abs(Math.floor(d * 2D)) % 2 == 1;
    }

    /**
     * 원본 L1953-L1999 `getWallFlag(Orientation direction, int i, int j_offset, int k,
     * Block block)` — 주어진 위치의 wall/pane/fence/gate 블록이 `direction` 방향으로
     * "연결되어 있는지" 판정.
     *
     * 원본 분기:
     *   (1) BlockPane → `canPaneConnectToBlock(neighbor)` 동적 계산
     *   (2) isFenceBase (Fence/Wall) → `canConnectFenceTo/WallTo(world, x, y, z)` 동적 계산
     *   (3) BetterMisc reflection → mod 근사 생략
     *   (4) isFenceGate → `isClosedFenceGate && isFenceGateFront(metaData)`
     *   (5) Carpenters → mod 근사 생략
     *   (6) default → false
     *
     * **§7 근사** (B-19a1c3b-approx-1): 1.21.1 에서는 Pane/Fence 는 `ConnectingBlock.NORTH/
     * SOUTH/EAST/WEST` BooleanProperty 를 이미 설정 저장. Wall 은 `WallBlock.NORTH_SHAPE`
     * 등 `EnumProperty<WallShape>` — `!= WallShape.NONE` 이면 연결. 원본은 호출 시점
     * 동적 계산, 1.21.1 은 BlockState 캐시 조회 — 대부분의 경우 동치 (neighbor 변경 후
     * 같은 tick 안에서는 약간 차이 가능).
     *
     * BetterMisc reflection (`_canConnectFenceTo`) + Carpenters (`getCarpentersBlockData`)
     * 분기 생략.
     */
    private boolean getWallFlag(Orientation direction, int i, int j_offset, int k, BlockState state) {
        if (state == null) return false;
        Direction d = direction.toBlockDirection();
        if (d == null) return false;

        Block block = state.getBlock();

        // 원본 분기 (1): BlockPane — 1.21.1: ConnectingBlock NORTH/SOUTH/EAST/WEST property
        if (block instanceof IronBarsBlock) {
            // 근사 이식 — 원본과 차이: canPaneConnectToBlock 동적 → BlockState property 캐시
            return getConnectingFlag(state, d);
        }

        // 원본 분기 (2): isFenceBase = FenceBlock || WallBlock
        if (isFenceBase(state)) {
            if (block instanceof FenceBlock) {
                // 근사 이식 — 원본과 차이: canConnectFenceTo 동적 → ConnectingBlock property 캐시
                return getConnectingFlag(state, d);
            }
            if (block instanceof WallBlock) {
                // 근사 이식 — 원본과 차이: canConnectWallTo 동적 → WallBlock *_SHAPE != NONE
                return getWallShapeFlag(state, d);
            }
            // 근사 이식 — 원본과 차이: BetterMisc `_canConnectFenceTo` reflection 분기 생략
            return false;
        }

        // 원본 분기 (4): FenceGate
        if (isFenceGate(state)) {
            return isClosedFenceGate(state) && isFenceGateFront(state);
        }

        // 원본 분기 (5): Carpenters — 근사 이식 — 원본과 차이: _blockCarpentersLadder 분기 생략
        return false;
    }

    /** `ConnectingBlock.NORTH/SOUTH/EAST/WEST` BooleanProperty 기반 방향 연결 조회. */
    private static boolean getConnectingFlag(BlockState state, Direction d) {
        switch (d) {
            case NORTH: return state.getValue(PipeBlock.NORTH);
            case SOUTH: return state.getValue(PipeBlock.SOUTH);
            case EAST:  return state.getValue(PipeBlock.EAST);
            case WEST:  return state.getValue(PipeBlock.WEST);
            default:    return false;
        }
    }

    /** `WallBlock.NORTH_SHAPE/SOUTH_SHAPE/EAST_SHAPE/WEST_SHAPE` WallShape 기반 연결 조회. */
    private static boolean getWallShapeFlag(BlockState state, Direction d) {
        switch (d) {
            case NORTH: return state.getValue(WallBlock.NORTH) != WallSide.NONE;
            case SOUTH: return state.getValue(WallBlock.SOUTH) != WallSide.NONE;
            case EAST:  return state.getValue(WallBlock.EAST)  != WallSide.NONE;
            case WEST:  return state.getValue(WallBlock.WEST)  != WallSide.NONE;
            default:    return false;
        }
    }

    /**
     * 원본 L2300-L2323 `isDoorFrontBlocked(int i, int j_offset, int k)` — 이 Orientation
     * 방향 이동 시 door 로 막히는지.
     *
     * 원본 vanilla 1.7.10 door metadata 매핑:
     *   case 8: upper half → lower half 재귀
     *   case 4 (EAST open) / case 1 (SOUTH closed):  return this._k < 0
     *   case 5 (SOUTH open) / case 2 (WEST closed): return this._i > 0
     *   case 6 (WEST open) / case 3 (NORTH closed):  return this._k > 0
     *   case 7 (NORTH open) / case 0 (EAST closed):  return this._i < 0
     *   default: return true (방어적)
     *
     * 1.21.1 매핑: `DoorBlock.FACING` Direction + `DoorBlock.OPEN` Boolean +
     * `DoorBlock.HALF` DoubleBlockHalf 조합으로 각 case 식별.
     */
    private boolean isDoorFrontBlocked(int i, int j_offset, int k) {
        BlockState state = getBlock(i, j_offset, k);
        if (!isDoor(state)) return true;

        // 원본 case 8 — upper half 이면 lower half 재귀 체크
        if (state.getValue(DoorBlock.HALF) == DoubleBlockHalf.UPPER)
            return isDoorFrontBlocked(i, j_offset - 1, k);

        Direction facing = state.getValue(DoorBlock.FACING);
        boolean open = state.getValue(DoorBlock.OPEN);

        // 원본 case 4 (EAST open) / case 1 (SOUTH closed): this._k < 0
        if ((facing == Direction.EAST  &&  open) || (facing == Direction.SOUTH && !open))
            return this._k < 0;
        // 원본 case 5 (SOUTH open) / case 2 (WEST closed): this._i > 0
        if ((facing == Direction.SOUTH &&  open) || (facing == Direction.WEST  && !open))
            return this._i > 0;
        // 원본 case 6 (WEST open) / case 3 (NORTH closed): this._k > 0
        if ((facing == Direction.WEST  &&  open) || (facing == Direction.NORTH && !open))
            return this._k > 0;
        // 원본 case 7 (NORTH open) / case 0 (EAST closed): this._i < 0
        if ((facing == Direction.NORTH &&  open) || (facing == Direction.EAST  && !open))
            return this._i < 0;

        return true;
    }

    // ════════════════════════════════════════════════════════════════════════
    // B-19a1c3c (세션 97) — headedToFrontWall + headedToRemoteFlatWall + isRemoteAccessible
    // 원본: Orientation.java L1741-L1757 (headedToFrontWall),
    //       L1941-L1951 (headedToRemoteFlatWall), L2401-L2475 (isRemoteAccessible).
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 원본 L1741-L1757 `headedToFrontWall(int i, int j_offset, int k, Block block)` —
     * (i, j_offset, k) 위치 벽/펜스 블록이 4방향 중 어느 방향으로 연결되어 있고, 이 플레이어
     * Orientation 이 그 방향 반대쪽 (front 에서 접근) 이면 true.
     *
     * 논리:
     *   1. 4 방향 wall flag 수집 (`zn`/`zp`/`nz`/`pz` — 블록이 해당 방향으로 연결)
     *   2. `allOnNone` 블록 (pane) 이고 전부 false 면 4 방향 전부 true 처리 (고립 pane
     *      은 4 방향 연결 가정)
     *   3. 이 Orientation 이 NZ/PZ/ZN/ZP (및 ±45°) 중 어디면, 그 반대 방향 wall flag 반환
     *      - NZ 탐색 시 PZ(동쪽) 연결 확인
     *      - PZ 탐색 시 NZ(서쪽) 연결 확인
     *      - ZN 탐색 시 ZP(남쪽) 연결 확인
     *      - ZP 탐색 시 ZN(북쪽) 연결 확인
     */
    private boolean headedToFrontWall(int i, int j_offset, int k, BlockState state) {
        boolean zn = getWallFlag(ZN, i, j_offset, k, state);
        boolean zp = getWallFlag(ZP, i, j_offset, k, state);
        boolean nz = getWallFlag(NZ, i, j_offset, k, state);
        boolean pz = getWallFlag(PZ, i, j_offset, k, state);
        boolean allOnNone = getAllWallsOnNoWall(state);

        if (allOnNone && !zn && !zp && !nz && !pz)
            zn = zp = nz = pz = true;

        return headedToWall(NZ, pz)
            || headedToWall(PZ, nz)
            || headedToWall(ZN, zp)
            || headedToWall(ZP, zn);
    }

    /**
     * 원본 L1941-L1951 `headedToRemoteFlatWall(Block block, int j_offset)` — remote 위치
     * 블록이 "평평한 벽 (flat wall)" 패턴 — 플레이어 진행 방향과 직각으로 연결되고 다른
     * 방향으로는 연결 안 된 모양 — 인지.
     *
     *   !this-dir && +rotate(90) && !rotate(180) && +rotate(-90)
     *
     * 즉, 플레이어 탐색 방향 (this) 으로도, 반대 방향 (rotate 180) 으로도 연결 안 되고,
     * 양 옆 (rotate ±90) 으로만 연결됨.
     */
    private boolean headedToRemoteFlatWall(BlockState state, int j_offset) {
        return !getWallFlag(this,            remote_i, j_offset, remote_k, state)
            &&  getWallFlag(this.rotate(90), remote_i, j_offset, remote_k, state)
            && !getWallFlag(this.rotate(180),remote_i, j_offset, remote_k, state)
            &&  getWallFlag(this.rotate(-90),remote_i, j_offset, remote_k, state);
    }

    /**
     * 원본 L2401-L2475 `isRemoteAccessible(int j_offset)` — remote (이 Orientation 방향 인접)
     * 위치가 플레이어 점유 가능한지. 여러 분기의 OR 누적.
     *
     * 분기 그룹:
     *   (1) `isEmpty(remote_i, j_offset, remote_k)` — 빈 공간
     *   (2) [§7 근사 생략] RedPower wire 특수 판정
     *   (3) accessible 상태에서의 역체크 (base 위치의 trap door/door/ladder 가 막는지)
     *       - base `isTrapDoor && isTrapDoorFront` → 막힘
     *       - base `isDoor && isDoorFrontBlocked` → 막힘
     *       - `remoteLadderClimbing` → 막힘
     *   (4) remote trap door + closed → 접근 가능
     *   (5) !accessible 상태에서의 wall/fence/door/rope 복합 분기
     *       - remote wall block + !headedToFrontWall + !fence below
     *       - remote 아래 fence + !headedToFrontWall (+ base 아래 wall block) + cobblestone
     *         예외
     *       - remote door + !rotate(180).isDoorFrontBlocked
     *       - [§7 근사 생략] ASRope + !rotate(180).isASGrapplingHookFront
     *
     * **§7 근사 2건**:
     *   - RedPower 분기 생략 (B-19a1c2-approx-1 과 동일 패턴)
     *   - ASRope 분기 생략 (B-19a1b `isRope`/`isOnWallRope` false 와 연동)
     */
    protected boolean isRemoteAccessible(int j_offset) {
        boolean accessible = isEmpty(remote_i, j_offset, remote_k);

        // 근사 이식 — 원본과 차이: RedPower wire 분기 (원본 L2404-L2422) 생략

        if (accessible) {
            BlockState baseState = getBaseBlockId(j_offset);
            if (isTrapDoor(baseState))
                accessible = !isTrapDoorFront(baseState);

            if (accessible && isDoor(baseState))
                accessible = !isDoorFrontBlocked(base_i, j_offset, base_k);

            if (remoteLadderClimbing(j_offset))
                accessible = false;
        }

        if (!accessible && isTrapDoor(remote_i, j_offset, remote_k))
            accessible = isClosedTrapDoor(remote_i, j_offset, remote_k);

        if (!accessible) {
            BlockState remoteState = getRemoteBlockId(j_offset);
            if (isWallBlock(remoteState)
                    && !headedToFrontWall(remote_i, j_offset, remote_k, remoteState)
                    && !isFence(remote_i, j_offset - 1, remote_k))
                accessible = true;

            BlockState belowState = getRemoteBlockId(j_offset - 1);
            if (!accessible && isFence(belowState)
                    && (!headedToFrontWall(remote_i, j_offset - 1, remote_k, belowState)
                        || isWallBlock(getBaseBlockId(j_offset - 1))))
                if (belowState.getBlock() != Blocks.COBBLESTONE_WALL
                        || headedToRemoteFlatWall(belowState, -1))
                    accessible = true;

            if (!accessible && isDoor(remoteState)
                    && !rotate(180).isDoorFrontBlocked(remote_i, j_offset, remote_k))
                accessible = true;

            // 근사 이식 — 원본과 차이: ASRope 분기 (원본 L2467-L2471) 생략
        }

        return accessible;
    }

    // ════════════════════════════════════════════════════════════════════════
    // B-19a1c4 (세션 98) — isFullAccessible + isFullExtentAccessible +
    //                      isJustLowerHalfExtentAccessible + isUpperHalfFrontEmpty
    // 원본: Orientation.java L2486-L2535 + L2325-L2331 + L2543-L2586.
    // **B-19a1c (accessibility 판정) 최종 서브** — 완료 시 B-19a1 완료.
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 원본 L2325-L2331 `getWallBlockId(int i, int j_offset, int k)` — 좌표 위치 블록이
     * wall block 이면 BlockState 반환, 아니면 null.
     */
    private static BlockState getWallBlockId(int i, int j_offset, int k) {
        BlockState state = getBlock(i, j_offset, k);
        return isWallBlock(state) ? state : null;
    }

    /**
     * 원본 L2528-L2535 `isFullAccessible(int j_offset, boolean grabRemote)`.
     *
     * grabRemote=true  → `isBaseAccessible && isRemoteAccessible && isAccessAccessible`
     * grabRemote=false → `isEmpty(base_i, j_offset, base_k)` — 단순 base 빈 공간 체크
     *
     * 의존 전수 이식: B-19a1c2 isBaseAccessible/isEmpty + B-19a1c3c isRemoteAccessible +
     * B-19a1c3a isAccessAccessible.
     */
    protected boolean isFullAccessible(int j_offset, boolean grabRemote) {
        if (grabRemote)
            return isBaseAccessible(j_offset)
                && isRemoteAccessible(j_offset)
                && isAccessAccessible(j_offset);
        return isEmpty(base_i, j_offset, base_k);
    }

    /**
     * 원본 L2486-L2512 `isFullExtentAccessible(int j_offset, boolean grabRemote)`.
     *
     * 원본: `isFullAccessible` 반환값에 RedPower 추가 체크 (AND 조건).
     *
     * **§7 근사** (B-19a1c4-approx-1): RedPower wire 분기 (원본 L2490-L2510) 전체 생략 —
     * RedPower mod 1.21.1 미이식. 결과: `isFullAccessible` 그대로 반환.
     */
    protected boolean isFullExtentAccessible(int j_offset, boolean grabRemote) {
        boolean accessible = isFullAccessible(j_offset, grabRemote);
        // 근사 이식 — 원본과 차이: RedPower wire 분기 (원본 L2490-L2510) 생략
        return accessible;
    }

    /**
     * 원본 L2514-L2526 `isJustLowerHalfExtentAccessible(int j_offset)` — remote 위치가
     * top half (slab top / stair top front) 이면 lower half 공간이 비어있음 → 접근 가능.
     *
     * 의존: B-19a1c1 `isTopHalfBlock` / `isStairCompact` / `isTopStairCompactFront`.
     * 근사 없음.
     */
    protected boolean isJustLowerHalfExtentAccessible(int j_offset) {
        BlockState remoteState = getRemoteBlockId(j_offset);
        boolean accessible = false;
        if (!accessible)
            accessible = isTopHalfBlock(remoteState);
        if (!accessible)
            accessible = isStairCompact(remoteState) && isTopStairCompactFront(remoteState);
        return accessible;
    }

    /**
     * 원본 L2543-L2586 `isUpperHalfFrontEmpty(int i, int j_offset, int k)` — 해당 위치의
     * upper half (Y+0.5~+1.0) 가 이 Orientation 방향 전방에서 비어있는지.
     *
     * 원본 분기 순서 (empty OR 누적):
     *   (1) `isFullEmpty(block)` — 블록 전체 빈 공간
     *   (2) `isBottomHalfBlock(block, meta)` — bottom slab (upper half 비어있음)
     *   (3) `isStairCompact && isBottomStairCompactFront` — bottom stair front
     *   (4) [§7 근사 생략] RedPower wire 특수 판정
     *   (5) `isTrapDoor(block)` — trap door (open 여부 무관 upper half 비어있음)
     *   (6) wallBlock + (!headedToFrontWall || 반대쪽도 wallBlock) — wall 패턴 관통 가능
     *   (7) [§7 근사 생략] LadderKit + `rotate(180).hasLadderOrientation` → empty=false 복귀
     *
     * **§7 근사** (B-19a1c4-approx-2 / approx-3): RedPower + LadderKit 분기 생략.
     */
    protected boolean isUpperHalfFrontEmpty(int i, int j_offset, int k) {
        BlockState state = getBlock(i, j_offset, k);
        BlockPos pos = new BlockPos(i, local_offset + j_offset, k);
        boolean empty = isFullEmpty(state, world, pos);

        if (!empty) {
            if (isBottomHalfBlock(state))
                empty = true;

            if (!empty && isStairCompact(state) && isBottomStairCompactFront(state))
                empty = true;
        }

        // 근사 이식 — 원본과 차이: RedPower wire 분기 (원본 L2559-L2567) 생략

        if (!empty && isTrapDoor(state))
            empty = true;

        if (!empty) {
            BlockState wallState = getWallBlockId(i, j_offset, k);
            if (wallState != null
                    && (!headedToFrontWall(i, j_offset, k, wallState)
                        || isWallBlock(getBlock(i - _i, j_offset, k - _k))))
                empty = true;
        }

        // 근사 이식 — 원본과 차이: LadderKit 분기 (원본 L2581-L2583) 생략 —
        // `isBlockIdOfType(block, _ladderKitLadderTypes) && rotate(180).hasLadderOrientation(...)`
        // 이 true 면 empty=false 복귀. vanilla ladder 는 `isLadderOrVine` 에 포함되어
        // `isFullEmpty` 에서 이미 non-empty 처리됨 — 기본 vanilla 동작은 보존됨.

        return empty;
    }

    // ════════════════════════════════════════════════════════════════════════
    // B-19a2a1 (세션 99) — wall 판정 보조 (headedToFrontSideWall + headedToBaseWall
    //                       3 오버로드 + headedToBaseGrabWall 2 오버로드)
    // 원본: Orientation.java L1759-L1798 (headedToFrontSideWall),
    //       L1809-L1862 (headedToBaseWall 3), L1865-L1938 (headedToBaseGrabWall 2).
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 원본 L1759-L1798 `headedToFrontSideWall(int i, int j_offset, int k, Block block)` —
     * wall/pane/fence 블록이 이 Orientation 의 **측면** 방향 (base_id/base_kd 의 half 위치
     * 조합에 따른 오프셋 방향) 으로 연결되어 있는지.
     *
     * 논리: 4방향 wall flag 수집 → `base_id`/`base_kd` 의 topHalf 조합 4가지 중 하나
     * 선택 → 4방향 `headedToWall(NZ/PZ/ZN/ZP, ...)` OR. 원본과 동일.
     */
    private boolean headedToFrontSideWall(int i, int j_offset, int k, BlockState state) {
        boolean zn = getWallFlag(ZN, i, j_offset, k, state);
        boolean zp = getWallFlag(ZP, i, j_offset, k, state);
        boolean nz = getWallFlag(NZ, i, j_offset, k, state);
        boolean pz = getWallFlag(PZ, i, j_offset, k, state);
        boolean allOnNone = getAllWallsOnNoWall(state);

        if (allOnNone && !zn && !zp && !nz && !pz)
            zn = zp = nz = pz = true;

        boolean iTop = isTopHalf(base_id);
        boolean kTop = isTopHalf(base_kd);
        if (iTop) {
            if (kTop) {
                return headedToWall(NZ, zp) || headedToWall(PZ, zp)
                    || headedToWall(ZN, pz) || headedToWall(ZP, pz);
            } else {
                return headedToWall(NZ, zn) || headedToWall(PZ, zn)
                    || headedToWall(ZN, pz) || headedToWall(ZP, pz);
            }
        } else {
            if (kTop) {
                return headedToWall(NZ, zp) || headedToWall(PZ, zp)
                    || headedToWall(ZN, nz) || headedToWall(ZP, nz);
            } else {
                return headedToWall(NZ, zn) || headedToWall(PZ, zn)
                    || headedToWall(ZN, nz) || headedToWall(ZP, nz);
            }
        }
    }

    /**
     * 원본 L1809-L1839 `headedToBaseWall(int j_offset, Block block)` — base 위치 wall/pane
     * 블록이 이 Orientation 의 대각/직교 패턴에 맞게 연결되어 있는지.
     *
     * base_id/base_kd 의 topHalf 2x2 조합에 따라 4 diagonal 중 하나 선택, 그 diagonal 과
     * 인접 2 orthogonal 조합을 `headedToBaseWall(diagonal, left, right, ...)` 로 전달.
     */
    private boolean headedToBaseWall(int j_offset, BlockState state) {
        boolean zn = getWallFlag(ZN, base_i, j_offset, base_k, state);
        boolean zp = getWallFlag(ZP, base_i, j_offset, base_k, state);
        boolean nz = getWallFlag(NZ, base_i, j_offset, base_k, state);
        boolean pz = getWallFlag(PZ, base_i, j_offset, base_k, state);
        boolean allOnNone = getAllWallsOnNoWall(state);

        if (allOnNone && !zn && !zp && !nz && !pz)
            zn = zp = nz = pz = true;

        boolean leaf = zn || zp || nz || pz;
        boolean coreOnly = !allOnNone && !leaf;

        boolean iTop = isTopHalf(base_id);
        boolean kTop = isTopHalf(base_kd);
        if (iTop) {
            if (kTop)  return headedToBaseWall(NN, NZ, ZN, zp, nz, pz, zn, coreOnly, leaf);
            else       return headedToBaseWall(NP, NZ, ZP, zn, nz, pz, zp, coreOnly, leaf);
        } else {
            if (kTop)  return headedToBaseWall(PN, PZ, ZN, zp, pz, nz, zn, coreOnly, leaf);
            else       return headedToBaseWall(PP, PZ, ZP, zn, pz, nz, zp, coreOnly, leaf);
        }
    }

    /**
     * 원본 L1841-L1855 `headedToBaseWall(Orientation diagonal, Orientation left, Orientation
     * right, ...)` — 이 Orientation 이 diagonal/left/right 중 어느 하나와 매치하는지로
     * 결과 분기.
     *
     * diagonal 이면 `leaf || coreOnly`.
     * left 이면 `headedToBaseWall(leftFront, rightFrontOpposite, rightFront,
     *                              leftFrontOpposite, coreOnly)`.
     * right 이면 `headedToBaseWall(rightFront, leftFrontOpposite, leftFront,
     *                               rightFrontOpposite, coreOnly)`.
     */
    private boolean headedToBaseWall(Orientation diagonal, Orientation left, Orientation right,
                                     boolean leftFront, boolean rightFrontOpposite,
                                     boolean rightFront, boolean leftFrontOpposite,
                                     boolean co, boolean leaf) {
        if (this == diagonal) return leaf || co;
        if (this == left)
            return headedToBaseWall(leftFront, rightFrontOpposite, rightFront,
                    leftFrontOpposite, co);
        if (this == right)
            return headedToBaseWall(rightFront, leftFrontOpposite, leftFront,
                    rightFrontOpposite, co);
        return false;
    }

    /**
     * 원본 L1857-L1863 `headedToBaseWall(boolean front, boolean sideOpposite, boolean side,
     * boolean frontOpposite, boolean coreOnly)` — 5 개 flag 의 OR 조합.
     *
     *   front || (sideOpposite && !side) || (frontOpposite && !front && !side) || coreOnly
     */
    private static boolean headedToBaseWall(boolean front, boolean sideOpposite, boolean side,
                                            boolean frontOpposite, boolean coreOnly) {
        return front
            || (sideOpposite && !side)
            || (frontOpposite && !front && !side)
            || coreOnly;
    }

    /**
     * 원본 L1865-L1915 `headedToBaseGrabWall(int j_offset, Block block)` — grab 방향 wall
     * 패턴 판정. base + above (j_offset+1) 위치의 wall flag 를 조합하여 결정.
     *
     * 논리:
     *   1. base 위치 4방향 wall flag 수집
     *   2. above 블록이 fullEmpty → 4방향 above 전부 false
     *      isWallBlock → 4방향 above flag 수집
     *      기타 → 4방향 above 전부 true
     *   3. base_id/base_kd topHalf 2x2 조합으로 `headedToBaseGrabWall(i, k, ...)` 호출
     *      (front/side/frontOpposite/sideOpposite 및 above 4개 파라미터 전달)
     */
    private boolean headedToBaseGrabWall(int j_offset, BlockState state) {
        boolean zn = getWallFlag(ZN, base_i, j_offset, base_k, state);
        boolean zp = getWallFlag(ZP, base_i, j_offset, base_k, state);
        boolean nz = getWallFlag(NZ, base_i, j_offset, base_k, state);
        boolean pz = getWallFlag(PZ, base_i, j_offset, base_k, state);
        boolean allOnNone = getAllWallsOnNoWall(state);

        if (allOnNone && !zn && !zp && !nz && !pz)
            zn = zp = nz = pz = true;

        boolean azn, azp, anz, apz;
        BlockState aboveState = getBlock(base_i, j_offset + 1, base_k);
        BlockPos abovePos = new BlockPos(base_i, local_offset + j_offset + 1, base_k);
        if (isFullEmpty(aboveState, world, abovePos)) {
            azn = azp = anz = apz = false;
        } else if (isWallBlock(aboveState)) {
            azn = getWallFlag(ZN, base_i, j_offset + 1, base_k, aboveState);
            azp = getWallFlag(ZP, base_i, j_offset + 1, base_k, aboveState);
            anz = getWallFlag(NZ, base_i, j_offset + 1, base_k, aboveState);
            apz = getWallFlag(PZ, base_i, j_offset + 1, base_k, aboveState);
            boolean aboveAllOnNone = getAllWallsOnNoWall(aboveState);

            if (aboveAllOnNone && !azn && !azp && !anz && !apz)
                azn = azp = anz = apz = true;
        } else {
            azn = azp = anz = apz = true;
        }

        boolean iTop = isTopHalf(base_id);
        boolean kTop = isTopHalf(base_kd);
        if (iTop) {
            if (kTop) return headedToBaseGrabWall(-this._i, -this._k, zp, pz, nz, zn, azp, apz, anz, azn);
            else      return headedToBaseGrabWall(-this._i,  this._k, pz, zn, zp, nz, apz, azn, azp, anz);
        } else {
            if (kTop) return headedToBaseGrabWall( this._i, -this._k, nz, zp, zn, pz, anz, azp, azn, apz);
            else      return headedToBaseGrabWall( this._i,  this._k, zn, nz, pz, zp, azn, anz, apz, azp);
        }
    }

    /**
     * 원본 L1917-L1938 `headedToBaseGrabWall(int i, int k, ...)` — 10 boolean flag 조합으로
     * grab wall 판정. i/k 는 플레이어 방향 부호 (± 1 / 0).
     *
     * 5 분기 OR:
     *   (1) sideOpposite && !aboveSideOpposite && !front && !aboveFront && i == 1
     *   (2) frontOpposite && !aboveFrontOpposite && !side && !aboveSide && k == 1
     *   (3) side && !aboveSide && k >= 0
     *   (4) front && !aboveFront && k >= 0
     *   (5) frontOpposite && !aboveFrontOpposite && !aboveFront && i == 1 && k >= 0
     *   (6) sideOpposite && !aboveSideOpposite && !aboveSide && k == 1 && i >= 0
     */
    private static boolean headedToBaseGrabWall(int i, int k,
                                                boolean front, boolean side,
                                                boolean frontOpposite, boolean sideOpposite,
                                                boolean aboveFront, boolean aboveSide,
                                                boolean aboveFrontOpposite, boolean aboveSideOpposite) {
        if (sideOpposite && !aboveSideOpposite && !front && !aboveFront && i == 1)
            return true;
        if (frontOpposite && !aboveFrontOpposite && !side && !aboveSide && k == 1)
            return true;
        if (side && !aboveSide && k >= 0)
            return true;
        if (front && !aboveFront && k >= 0)
            return true;
        if (frontOpposite && !aboveFrontOpposite && !aboveFront && i == 1 && k >= 0)
            return true;
        if (sideOpposite && !aboveSideOpposite && !aboveSide && k == 1 && i >= 0)
            return true;
        return false;
    }

    // ════════════════════════════════════════════════════════════════════════
    // B-19a2a2 (세션 100) — vine 보조 (baseVineClimbing + remoteVineClimbing 각 2 오버로드)
    // 원본: Orientation.java L1087-L1103 + L1105-L1113 (baseVineClimbing),
    //       L1120-L1132 + L1134-L1146 (remoteVineClimbing).
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 원본 L1087-L1103 `baseVineClimbing(int j_offset)` — base 위치에 vine 이 있을 때,
     * 이 Orientation 방향으로 등반 가능한 vine meta 타입 반환.
     *
     *   (1) `isOnVine(j_offset)` false → `DefaultMeta` (-1)
     *   (2) `isOnVineFront(j_offset)` (vine 이 이 방향으로 붙어있음) → `VineFrontMeta` (0)
     *   (3) 4 orthogonal (PZ/NZ/ZP/ZN) 중 어느 하나가 `baseVineClimbing(j_offset, orientation)` true →
     *       `VineSideMeta` (1)
     *   (4) 외 → `DefaultMeta`
     */
    protected int baseVineClimbing(int j_offset) {
        boolean result = isOnVine(j_offset);
        if (result) {
            result = isOnVineFront(j_offset);
            if (result)
                return VineFrontMeta;

            if (baseVineClimbing(j_offset, PZ)
                    || baseVineClimbing(j_offset, NZ)
                    || baseVineClimbing(j_offset, ZP)
                    || baseVineClimbing(j_offset, ZN))
                return VineSideMeta;
        }
        return DefaultMeta;
    }

    /**
     * 원본 L1105-L1113 `baseVineClimbing(int j_offset, Orientation orientation)` — 지정한
     * orientation 기준 vine 측면 등반 가능 판정.
     *
     *   (1) `orientation == this` → false (자기 자신은 front 판정에서 처리됨)
     *   (2) `orientation.rotate(180).hasVineOrientation(world, base_i, local_offset + j_offset, base_k)` AND
     *       `orientation.getHorizontalBorderGap() >= 0.65` → true
     *
     * 의미: vine 이 orientation 의 반대쪽에 붙어있고 (rotate(180)), 플레이어가 orientation
     * 방향 경계에 충분히 가까움 (0.65 이상) → 그 orientation 으로 등반 가능.
     */
    protected boolean baseVineClimbing(int j_offset, Orientation orientation) {
        if (orientation == this) return false;

        return orientation.rotate(180).hasVineOrientation(world, base_i,
                local_offset + j_offset, base_k)
            && orientation.getHorizontalBorderGap() >= 0.65;
    }

    /**
     * 원본 L1120-L1132 `remoteVineClimbing(int j_offset)` — remote 위치 vine 등반 meta.
     *
     *   (1) `isBehindVine && isOnVineBack` → `VineFrontMeta`
     *   (2) 4 orthogonal 중 어느 하나 `remoteVineClimbing(j_offset, orientation)` true →
     *       `VineSideMeta`
     *   (3) 외 → `DefaultMeta`
     */
    protected int remoteVineClimbing(int j_offset) {
        if (isBehindVine(j_offset) && isOnVineBack(j_offset))
            return VineFrontMeta;

        if (remoteVineClimbing(j_offset, PZ)
                || remoteVineClimbing(j_offset, NZ)
                || remoteVineClimbing(j_offset, ZP)
                || remoteVineClimbing(j_offset, ZN))
            return VineSideMeta;

        return DefaultMeta;
    }

    /**
     * 원본 L1134-L1146 `remoteVineClimbing(int j_offset, Orientation orientation)`.
     *
     *   (1) `orientation == this` → false
     *   (2) `(base_i - orientation._i, j_offset, base_k - orientation._k)` 좌표에서
     *       vine 블록 + `orientation.hasVineOrientation` + `getHorizontalBorderGap >= 0.65F`
     *
     * 의미: orientation 의 반대쪽 한 칸 위치에 vine 이 그 orientation 방향으로 붙어있고,
     * 경계 거리 조건 만족.
     */
    protected boolean remoteVineClimbing(int j_offset, Orientation orientation) {
        if (orientation == this) return false;

        int i = base_i - orientation._i;
        int k = base_k - orientation._k;
        BlockState state = getBlock(i, j_offset, k);
        return isVine(state)
            && orientation.hasVineOrientation(world, i, local_offset + j_offset, k)
            && orientation.getHorizontalBorderGap() >= 0.65F;
    }

    // ════════════════════════════════════════════════════════════════════════
    // B-19a2a3 (세션 101) — half-solid 판정 (isLowerHalfFrontFullEmpty +
    //                       isUpperHalfFrontAnySolid + isUpperHalfFrontFullSolid)
    // 원본: Orientation.java L2065-L2115 + L2117-L2125 + L2127-L2153.
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 원본 L2065-L2115 `isLowerHalfFrontFullEmpty(int i, int j_offset, int k)` — 해당 위치의
     * lower half (Y~Y+0.5) 가 이 Orientation 방향 전방에서 비어있는지.
     *
     * 원본 분기 순서 (empty OR 누적, LadderKit 만 empty=false 복귀):
     *   (1) `isFullEmpty(block)` — 블록 전체 빈 공간
     *   (2) [§7 근사 생략] RedPower wire
     *   (3) [§7 근사 생략] BetterThanWolves anchor (metadata==0)
     *   (4) `isStairCompact && isTopStairCompactFront` — top stair front (아래 빔)
     *   (5) `isHalfBlock && SlabType == TOP` (원본 `isHalfBlockTopMetaData`)
     *   (6) wallBlock + !headedToFrontWall — wall 통과 가능
     *   (7) door + !rotate(180).isDoorFrontBlocked — door 열림
     *   (8) [§7 근사 생략] ASRope + !rotate(180).isASGrapplingHookFront
     *   (9) [§7 근사 생략] LadderKit + rotate(180).hasLadderOrientation → empty=false 복귀
     *
     * **§7 근사** (B-19a2a3-approx-1): RedPower + BetterThanWolves + ASRope + LadderKit 4
     * mod 분기 생략. vanilla ladder 는 `isFullEmpty` 에서 non-empty 처리됨.
     */
    protected boolean isLowerHalfFrontFullEmpty(int i, int j_offset, int k) {
        BlockState state = getBlock(i, j_offset, k);
        boolean empty = isFullEmpty(i, j_offset, k);

        // 근사 이식 — 원본과 차이: RedPower wire 분기 (원본 L2070-L2078) 생략
        // 근사 이식 — 원본과 차이: BetterThanWolves anchor 분기 (원본 L2080-L2086) 생략

        if (!empty && isStairCompact(state) && isTopStairCompactFront(state))
            empty = true;

        if (!empty && isTopHalfBlock(state))
            empty = true;

        if (!empty && isWallBlock(state)
                && !headedToFrontWall(i, j_offset, k, state))
            empty = true;

        if (!empty && isDoor(state)
                && !rotate(180).isDoorFrontBlocked(i, j_offset, k))
            empty = true;

        // 근사 이식 — 원본과 차이: ASRope 분기 (원본 L2104-L2108) 생략
        // 근사 이식 — 원본과 차이: LadderKit 분기 (원본 L2110-L2112) 생략 —
        // vanilla ladder 는 `isLadderOrVine` 에 포함되어 `isFullEmpty` 에서 non-empty 처리됨.

        return empty;
    }

    /**
     * 원본 L2117-L2125 `isUpperHalfFrontAnySolid(int i, int j_offset, int k)` —
     * upper half 가 solid 이나, wall block 이고 이 Orientation 방향으로 연결 안 된 경우
     * (headedToFrontWall false) solid=false 로 감쇠.
     */
    protected boolean isUpperHalfFrontAnySolid(int i, int j_offset, int k) {
        BlockState state = getBlock(i, j_offset, k);
        boolean solid = isUpperHalfFrontFullSolid(i, j_offset, k);
        if (solid && isWallBlock(state)
                && !headedToFrontWall(i, j_offset, k, state))
            solid = false;
        return solid;
    }

    /**
     * 원본 L2127-L2153 `isUpperHalfFrontFullSolid(int i, int j_offset, int k)` —
     * `isSolid(material)` 기본 + 얇은/관통 블록 예외.
     *
     *   (1) null → false
     *   (2) `isSolid` 기본
     *   (3) standing_sign / wall_sign / pressurePlate / trapDoor → solid=false
     *   (4) [§7 근사 생략] ASGrapplingHook → solid=false
     *   (5) openFenceGate → solid=false
     *   (6) [§7 근사 생략] Carpenters `_blockCarpentersLadder` → solid=false
     *
     * **§7 근사** (B-19a2a3-approx-2): ASGrapplingHook / Carpenters mod 1.21.1 미이식 생략.
     */
    protected static boolean isUpperHalfFrontFullSolid(int i, int j_offset, int k) {
        BlockState state = getBlock(i, j_offset, k);
        if (state == null) return false;

        BlockPos pos = new BlockPos(i, local_offset + j_offset, k);
        // 🔴 fix #74 (BUG #1, 2026-05-10): "공중에 떠있는 top slab grip 인식 X" 직접 fix.
        //   기존 isSolid(state, world, pos) → state.isFullCube(world, pos) = "1×1×1 풀큐브 검사".
        //   slab (0.5×1×1) / stair / wall / fence 등 부분 솔리드 = isFullCube=false → 누락.
        //   원본 1.7.10 isSolid(material) = "Material.isSolid() && blocksMovement()" — 즉
        //   "공간을 solid 로 채움 + 이동 막음" 검사. 부분 솔리드 모두 매치.
        //
        //   1.21.1 정확 등가 = collision shape 비어있지 않음 (= 어떤 충돌 박스든 존재).
        //   slab/stair/wall/fence/iron_bars 등 모두 collision shape 있음 → 매치.
        //   air/water/lava/grass(부유) 등 collision shape 없음 → 매치 X.
        //
        //   isSolid 헬퍼 자체는 그대로 유지 (isFullEmpty / isRemoteSolid 등 다른 호출 위치
        //   회귀 차단). 본 메서드만 collision shape 직접 검사로 별도 매핑.
        //
        //   영향 위치 (호출 2곳):
        //   - L2030 isUpperHalfFrontAnySolid → hasBottomHold L2651 분기 메인 → BUG fix
        //   - L2667 hasBottomHold (8) stair compact 분기 → 더 매치됨 (= grip 추가, 회귀 X)
        boolean solid = !state.getCollisionShape(world, pos).isEmpty();

        if (solid) {
            Block block = state.getBlock();
            if (block instanceof SignBlock) solid = false;   // standing_sign + wall_sign 통합
            else if (block instanceof WallSignBlock) solid = false;  // 중복 안전
            else if (block instanceof PressurePlateBlock) solid = false;
            else if (isTrapDoor(state)) solid = false;
            // 근사 이식 — 원본과 차이: ASGrapplingHook 분기 (원본 L2143-L2145) 생략
            else if (isOpenFenceGate(state)) solid = false;
            // 근사 이식 — 원본과 차이: Carpenters _blockCarpentersLadder 분기 (원본 L2149-L2151) 생략
        }
        return solid;
    }

    // ════════════════════════════════════════════════════════════════════════
    // B-19a2a4 (세션 102) — 잔여 보조
    //   vanilla 1:1: getTriple / isHeadedToRope
    //   mod 근사 (false/null): isOnMiddleLadderFront / getCarpentersBlockData /
    //                           isOnAnchorFront / isASGrapplingHookFront /
    //                           getRopeId / getAnchorId / isASRope / isASGrapplingHook
    // 원본: Orientation.java L1241-L1259 / L1261-L1272 / L1386-L1412 / L1414-L1440 /
    //       L1443-L1484 / L1487-L1511 / L2011-L2024.
    // ════════════════════════════════════════════════════════════════════════

    // ── vanilla 1:1 ─────────────────────────────────────────────────────────

    /**
     * 원본 L2011-L2024 `getTriple(double primary, double secondary)` — 두 좌표 소수부
     * 중심 오프셋 비교로 -1 / 0 / 1 반환.
     *
     *   primary = primary - floor(primary) - 0.5
     *   secondary = secondary - floor(secondary) - 0.5
     *   |primary|*2 < |secondary| → 0
     *   primary > 0 → 1
     *   primary < 0 → -1
     *   0 → 0
     *
     * Pure math — 근사 없음.
     */
    private static int getTriple(double primary, double secondary) {
        primary = primary - Math.floor(primary) - 0.5;
        secondary = secondary - Math.floor(secondary) - 0.5;

        if (Math.abs(primary) * 2 < Math.abs(secondary))
            return 0;
        else if (primary > 0)
            return 1;
        else if (primary < 0)
            return -1;
        else
            return 0;
    }

    /**
     * 원본 L1386-L1412 `isHeadedToRope()` — `base_id`/`base_kd` 의 (i, k) triple 조합을
     * 9 분기 (3x3) 로 매칭하여 Orientation 과 비교.
     *
     *   iTriple > 0, kTriple > 0 → this == NN
     *   iTriple > 0, kTriple < 0 → this == NP
     *   iTriple > 0, kTriple = 0 → this == NZ
     *   iTriple < 0, kTriple > 0 → this == PN
     *   iTriple < 0, kTriple < 0 → this == PP
     *   iTriple < 0, kTriple = 0 → this == PZ
     *   iTriple = 0, kTriple > 0 → this == ZN
     *   iTriple = 0, kTriple < 0 → this == ZP
     *   iTriple = 0, kTriple = 0 → this == ZZ
     *
     * rope mod 판정용이지만 자체는 pure logic — 근사 없이 1:1.
     */
    protected boolean isHeadedToRope() {
        int iTriple = getTriple(base_id, base_kd);
        int kTriple = getTriple(base_kd, base_id);

        if (iTriple > 0) {
            if (kTriple > 0)      return this == NN;
            else if (kTriple < 0) return this == NP;
            else                  return this == NZ;
        } else if (iTriple < 0) {
            if (kTriple > 0)      return this == PN;
            else if (kTriple < 0) return this == PP;
            else                  return this == PZ;
        } else {
            if (kTriple > 0)      return this == ZN;
            else if (kTriple < 0) return this == ZP;
            else                  return this == ZZ;
        }
    }

    // ── mod 근사 (§7 B-19a2a4-approx 1/2/3) ────────────────────────────────

    /**
     * 원본 L1261-L1272 `getCarpentersBlockData(i, j_offset, k)` — Carpenters mod
     * `TileEntity` 에서 reflection 으로 블록 data 추출.
     *
     * **§7 근사** (B-19a2a4-approx-1): Carpenters mod 1.21.1 미이식 → **항상 -1 반환**.
     */
    private static int getCarpentersBlockData(int i, int j_offset, int k) {
        // 근사 이식 — 원본과 차이: Carpenters mod 미이식 → 항상 -1
        return -1;
    }

    /**
     * 원본 L1241-L1259 `isOnMiddleLadderFront(j_offset)` — Carpenters block 의 metadata
     * switch 만 의존. `getCarpentersBlockData` 가 항상 -1 반환하므로 switch 매치 없음 →
     * **항상 false**.
     *
     * **§7 근사** (B-19a2a4-approx-1 연동): Carpenters mod 미이식.
     */
    protected boolean isOnMiddleLadderFront(int j_offset) {
        // 근사 이식 — 원본과 차이: Carpenters mod 미이식 → 항상 false
        return false;
    }

    /**
     * 원본 L1414-L1440 `isOnAnchorFront(j_offset)` — BetterThanWolves anchor 블록의 metadata
     * switch.
     *
     * **§7 근사** (B-19a2a4-approx-2): BetterThanWolves mod 1.21.1 미이식 → **항상 false**.
     * anchor 블록 존재 자체가 없음 (`isAnchorId` → false).
     */
    protected boolean isOnAnchorFront(int j_offset) {
        // 근사 이식 — 원본과 차이: BetterThanWolves anchor mod 미이식 → 항상 false
        return false;
    }

    /**
     * 원본 L1487-L1511 `isASGrapplingHookFront(int metaData)` — ASGrapplingHook mod 의 8
     * metadata 비트 조합 판정.
     *
     * **§7 근사** (B-19a2a4-approx-3): ASGrapplingHook mod 1.21.1 미이식 → **항상 false**.
     * `isASGrapplingHook` 자체가 false 반환되어 선행 조건에서 제거되나 안전상 직접 false.
     *
     * 원본은 `int metaData` 파라미터이나 1.21.1 에선 사용 지점이 근사 삭제되므로 파라미터
     * 형태와 무관 — `BlockState` 로 표면 매핑 (호출부 일관성).
     */
    protected boolean isASGrapplingHookFront(BlockState state) {
        // 근사 이식 — 원본과 차이: ASGrapplingHook mod 미이식 → 항상 false
        return false;
    }

    /**
     * 원본 L1443+ `getRopeId(j_offset)` — BetterThanWolves `fcRopeBlock` / RopesPlus
     * `blockRopeCentral` 판정 후 block 반환.
     *
     * **§7 근사** (B-19a2a4-approx-3): 두 mod 모두 미이식 → **항상 null**.
     */
    protected static BlockState getRopeId(int j_offset) {
        // 근사 이식 — 원본과 차이: BetterThanWolves/RopesPlus rope 미이식 → null
        return null;
    }

    /**
     * 원본 `getAnchorId(j_offset)` — BetterThanWolves `fcAnchor` 판정.
     *
     * **§7 근사** (B-19a2a4-approx-2): BetterThanWolves mod 미이식 → **항상 null**.
     */
    protected static BlockState getAnchorId(int j_offset) {
        // 근사 이식 — 원본과 차이: BetterThanWolves anchor mod 미이식 → null
        return null;
    }

    /**
     * 원본 `isASRope(Block block)` — ASRope mod `blockRope` 판정.
     *
     * **§7 근사** (B-19a2a4-approx-3): ASRope mod 미이식 → **항상 false**.
     */
    protected static boolean isASRope(BlockState state) {
        // 근사 이식 — 원본과 차이: ASRope mod 미이식 → 항상 false
        return false;
    }

    /**
     * 원본 `isASGrapplingHook(Block block)` — ASGrapplingHook mod `blockGrHk` 판정.
     *
     * **§7 근사** (B-19a2a4-approx-3): ASGrapplingHook mod 미이식 → **항상 false**.
     */
    protected static boolean isASGrapplingHook(BlockState state) {
        // 근사 이식 — 원본과 차이: ASGrapplingHook mod 미이식 → 항상 false
        return false;
    }

    // ════════════════════════════════════════════════════════════════════════
    // B-19a2b (세션 103) — grab 상태 세팅 + initialize 헬퍼
    // 원본: Orientation.java L937-L995 (setHalfGrabType/setBottomGrabType/setGrabType),
    //       L2686-L2720 (initialize / initializeOffset / initializeLocal).
    // ════════════════════════════════════════════════════════════════════════

    // ── initialize 계열 (원본 L2686-L2720) ─────────────────────────────────

    /**
     * 원본 L2686-L2699 `initialize(World w, int i, double id, double jhd, int k, double kd)`.
     * 이 Orientation 기반 ladder gap 탐색 시작 시 static 상태 초기화.
     *
     *   world = w
     *   base_i/base_id/base_jhd/base_k/base_kd 설정
     *   remote_i = base_i + _i
     *   remote_k = base_k + _k
     */
    protected void initialize(Level w, int i, double id, double jhd, int k, double kd) {
        world = w;

        base_i = i;
        base_id = id;
        base_jhd = jhd;
        base_k = k;
        base_kd = kd;

        remote_i = i + _i;
        remote_k = k + _k;
    }

    /**
     * 원본 L2701-L2713 `initializeOffset(double offset_halfs, boolean isClimbCrawling,
     * boolean isCrawlClimbing, boolean isCrawling)`.
     *
     *   crawl = isClimbCrawling || isCrawlClimbing || isCrawling
     *   offset_jhd = base_jhd + offset_halfs
     *   offset_jh = floor(offset_jhd)
     *   jh_offset = offset_jhd - offset_jh
     *   all_j = offset_jh / 2
     *   all_offset = offset_jh % 2
     *
     * 1.21.1 매핑: `MathHelper.floor_double(x)` → `MathHelper.floor(x)` (yarn 표면 매핑).
     */
    protected static void initializeOffset(double offset_halfs,
                                           boolean isClimbCrawling,
                                           boolean isCrawlClimbing,
                                           boolean isCrawling) {
        crawl = isClimbCrawling || isCrawlClimbing || isCrawling;

        double offset_jhd = base_jhd + offset_halfs;
        int offset_jh = Mth.floor(offset_jhd);
        jh_offset = offset_jhd - offset_jh;

        all_j = offset_jh / 2;
        all_offset = offset_jh % 2;
    }

    /**
     * 원본 L2715-L2720 `initializeLocal(int localOffset)`.
     *
     *   local_halfOffset = localOffset + all_offset
     *   local_half = |local_halfOffset| % 2
     *   local_offset = all_j + (local_halfOffset - local_half) / 2
     */
    protected static void initializeLocal(int localOffset) {
        local_halfOffset = localOffset + all_offset;
        local_half = Math.abs(local_halfOffset) % 2;
        local_offset = all_j + (local_halfOffset - local_half) / 2;
    }

    // ── setGrabType 계열 (원본 L937-L995) ──────────────────────────────────

    /**
     * 원본 L987-L995 `setGrabType(int type, Block block, boolean remote, boolean hasGrab,
     * int metaClimb)` — 최종 static 필드 할당 + hasGrab 반환.
     *
     *   grabRemote = remote
     *   grabType = hasGrab ? type : NoGrab
     *   grabBlock = block
     *   grabMeta = metaClimb
     *   return hasGrab
     *
     * 1.21.1 매핑: `Block` → `BlockState` (B-19a1a 필드 타입).
     */
    private static boolean setGrabType(int type, BlockState block, boolean remote,
                                       boolean hasGrab, int metaClimb) {
        grabRemote = remote;
        grabType = hasGrab ? type : NoGrab;
        grabBlock = block;
        grabMeta = metaClimb;
        return hasGrab;
    }

    /** 원본 L937-L940 `setHalfGrabType(int type, Block block)` — 래퍼 (`remote=true`). */
    protected boolean setHalfGrabType(int type, BlockState block) {
        return setHalfGrabType(type, block, true);
    }

    /** 원본 L942-L945 `setHalfGrabType(int type, Block block, boolean remote)` — 래퍼
     *  (`metaClimb=-1`). */
    protected boolean setHalfGrabType(int type, BlockState block, boolean remote) {
        return setHalfGrabType(type, block, remote, -1);
    }

    /**
     * 원본 L947-L960 `setHalfGrabType(int type, Block block, boolean remote, int metaClimb)` —
     * half-grab 판정 본체.
     *
     *   hasGrab = type != NoGrab
     *   hasGrab && remote && _isDiagonal 일 때 대각 진입 측면 2방향 `isUpperHalfFrontEmpty`
     *   (CCW=rotate(90)/CW=rotate(-90)) 둘 다 true 여야 grab 유지. 아니면 hasGrab=false.
     *   결과 setGrabType 위임.
     */
    protected boolean setHalfGrabType(int type, BlockState block, boolean remote, int metaClimb) {
        boolean hasGrab = type != NoGrab;
        if (hasGrab && remote && _isDiagonal) {
            boolean edgeConnectCCW = rotate(90).isUpperHalfFrontEmpty(base_i, 0, remote_k);
            boolean edgeConnectCW  = rotate(-90).isUpperHalfFrontEmpty(remote_i, 0, base_k);
            hasGrab &= edgeConnectCCW && edgeConnectCW;
        }
        return setGrabType(type, block, remote, hasGrab, metaClimb);
    }

    /** 원본 L962-L965 `setBottomGrabType(int type, Block block)` — 래퍼. */
    protected boolean setBottomGrabType(int type, BlockState block) {
        return setBottomGrabType(type, block, true);
    }

    /** 원본 L967-L970 `setBottomGrabType(int type, Block block, boolean remote)` — 래퍼. */
    protected boolean setBottomGrabType(int type, BlockState block, boolean remote) {
        return setBottomGrabType(type, block, remote, -1);
    }

    /**
     * 원본 L972-L985 `setBottomGrabType(int type, Block block, boolean remote, int metaClimb)` —
     * bottom-grab 판정 본체.
     *
     * setHalfGrabType 과 유사하나 diagonal 엣지 체크에 `isLowerHalfFrontFullEmpty` 사용
     * (upper half 가 아닌 lower half 관통 여부).
     */
    protected boolean setBottomGrabType(int type, BlockState block, boolean remote, int metaClimb) {
        boolean hasGrab = type != NoGrab;
        if (hasGrab && remote && _isDiagonal) {
            boolean edgeConnectCCW = rotate(90).isLowerHalfFrontFullEmpty(base_i, 0, remote_k);
            boolean edgeConnectCW  = rotate(-90).isLowerHalfFrontFullEmpty(remote_i, 0, base_k);
            hasGrab &= edgeConnectCCW && edgeConnectCW;
        }
        return setGrabType(type, block, remote, hasGrab, metaClimb);
    }

    // ════════════════════════════════════════════════════════════════════════
    // B-19a2c (세션 104) — hasHalfHold 본체
    // 원본: Orientation.java L608-L726 (120줄).
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 원본 L608-L726 `hasHalfHold()` — 플레이어 중앙 (y=0) 레벨 grab 가능 판정.
     *
     * 분기 순서 (첫 return 에서 종료, 미매치면 최종 `NoGrab`):
     *   (1) FreeBaseClimb: isOnLadder+isOnLadderFront → AroundGrab (base)
     *   (2) FreeBaseClimb: remoteLadderClimbing → AroundGrab (remote)
     *   (3) [§7 근사 생략] BetterThanWolves/RopesPlus rope/anchor
     *   (4) isEmpty(base) + remote==iron_bars + headedToFrontWall → HalfGrab (remote)
     *   (5) wallId==iron_bars + headedToBaseWall(0) → HalfGrab (base)
     *   (6) wallId != null + isOnMiddleLadderFront → AroundGrab (base, remoteId)
     *   (7) freeFenceClimbing 블록 (7 서브 분기):
     *       a) remote fence + front + (!baseFence → HalfGrab / baseFence+sideWall → HalfGrab)
     *       b) remoteBelow fence + front + (!baseBelowFence → HalfGrab /
     *          baseBelowFence+sideWall → HalfGrab)
     *       c) wallId fence + headedToBaseWall → HalfGrab
     *       d) belowWallId fence + headedToBaseWall → HalfGrab
     *       e) remote==cobblestone_wall + !headedToRemoteFlatWall → HalfGrab
     *       f) remoteBelow==cobblestone_wall + !headedToRemoteFlatWall → HalfGrab
     *   (8) isBottomHalfBlock(remote) OR (stair + bottomNotBack + !(baseBelow stair
     *       bottomFront)) → HalfGrab (remote)
     *   (9) trapDoor closed (remote) → HalfGrab (remote)
     *   (10) trapDoor open (base) → HalfGrab (base)
     *   (11) [§7 근사 생략] ASGrapplingHook/RopesPlus isASRope + isASGrapplingHookFront
     *   (12) FreeBaseClimb: baseVineClimbing(0) > -1 → HalfGrab (vine, meta)
     *   (13) FreeBaseClimb: remoteVineClimbing(0) > -1 → HalfGrab (vine, meta)
     *   (14) 외 → NoGrab
     *
     * **§7 근사** (B-19a2c-approx-1): mod 3 카테고리 분기 (BetterThanWolves rope/anchor /
     * RopesPlus / ASRope / ASGrapplingHook) 생략 — B-19a2a4 의 getRopeId/getAnchorId/isASRope/
     * isASGrapplingHook false/null 근사와 연동.
     */
    protected boolean hasHalfHold() {
        SmartMovingConfig cfg = SmartMovingConfig.Config;

        if (cfg.isFreeBaseClimb()) {
            if (isOnLadder(0) && isOnLadderFront(0))
                return setHalfGrabType(AroundGrab, getBaseBlockId(0), false);

            if (remoteLadderClimbing(0))
                return setHalfGrabType(AroundGrab, getRemoteBlockId(0), true);
        }

        // 근사 이식 — 원본과 차이: BetterThanWolves/RopesPlus rope+anchor 분기 (원본 L621-L629) 생략

        BlockState remoteState = getRemoteBlockId(0);
        if (isEmpty(base_i, 0, base_k)) {
            // 🔴 사용자 요구: fence ≡ iron_bars 동일 동작. iron_bars 무조건 분기에 fence 통합.
            //   원본은 fence 를 cfg.freeFenceClimbing 게이트 안 (L571-598) 에서 별도 처리하나
            //   사용자 의도는 "두 블록 동일 작동" 이므로 게이트 무관 통합.
            if ((remoteState.getBlock() == Blocks.IRON_BARS || isFence(remoteState))
                    && headedToFrontWall(remote_i, 0, remote_k, remoteState))
                return setHalfGrabType(HalfGrab, remoteState);
        }

        BlockState wallState = getWallBlockId(base_i, 0, base_k);
        if (wallState != null && (wallState.getBlock() == Blocks.IRON_BARS || isFence(wallState))
                && headedToBaseWall(0, wallState))
            return setHalfGrabType(HalfGrab, wallState, false);
        if (wallState != null && isOnMiddleLadderFront(0))
            return setHalfGrabType(AroundGrab, remoteState, false);

        // 🔴 b 옵션 (사용자 요구 fence ≡ iron_bars 통합 + 끝까지 등반 보장):
        //   원본 L573-591 fence sub-branch 4개를 iron_bars 통합 형태로 복원.
        //   기존 통합 분기 (L2434-2440 isEmpty+remote / L2443-2446 wallState) 는 base 가
        //   비어있는 케이스만 처리 — 사용자 위치 base 가 fence/iron_bars 인 케이스 (수직
        //   fence/iron_bars 줄 등반 마지막 블록 직전) 매핑 fail → handsClimbing=NONE →
        //   wantClimbUp 분기 fallback HOLD_MOTION → 위로 못 올라감 (사용자 보고).
        //   이 분기들이 base/baseBelow 가 fence/iron_bars 인 모서리 케이스 처리.
        if (cfg.freeFenceClimbing) {
            BlockState remoteBelowState = getRemoteBlockId(-1);
            BlockState baseStateLocal = getBaseBlockId(0);
            BlockState baseBelowState = getBaseBlockId(-1);

            // 1. 원본 L573-577: remote=fence/iron_bars + base 모서리 케이스
            boolean remoteIsBars = remoteState.getBlock() == Blocks.IRON_BARS || isFence(remoteState);
            boolean baseIsBars = baseStateLocal.getBlock() == Blocks.IRON_BARS || isFence(baseStateLocal);
            if (remoteIsBars && headedToFrontWall(remote_i, 0, remote_k, remoteState)) {
                if (!baseIsBars)
                    return setHalfGrabType(HalfGrab, remoteState);
                else if (headedToFrontSideWall(remote_i, 0, remote_k, remoteState))
                    return setHalfGrabType(HalfGrab, remoteState);
            }

            // 2. 원본 L579-584: 수직 fence/iron_bars 줄 등반 (remoteBelow 매핑)
            boolean remoteBelowIsBars = remoteBelowState.getBlock() == Blocks.IRON_BARS || isFence(remoteBelowState);
            boolean baseBelowIsBars = baseBelowState.getBlock() == Blocks.IRON_BARS || isFence(baseBelowState);
            if (remoteBelowIsBars && headedToFrontWall(remote_i, -1, remote_k, remoteBelowState)) {
                if (!baseBelowIsBars)
                    return setHalfGrabType(HalfGrab, remoteState);
                else if (headedToFrontSideWall(remote_i, -1, remote_k, remoteBelowState))
                    return setHalfGrabType(HalfGrab, remoteState);
            }

            // 3. 원본 L589-591: base 아래 fence/iron_bars wall
            BlockState belowWallState = getWallBlockId(base_i, -1, base_k);
            if (belowWallState != null) {
                boolean belowWallIsBars = belowWallState.getBlock() == Blocks.IRON_BARS || isFence(belowWallState);
                if (belowWallIsBars && headedToBaseWall(-1, belowWallState))
                    return setHalfGrabType(HalfGrab, belowWallState, false);
            }

            // 4. cobblestone_wall (기존)
            if (remoteState.getBlock() == Blocks.COBBLESTONE_WALL
                    && !headedToRemoteFlatWall(remoteState, 0))
                return setHalfGrabType(HalfGrab, remoteState);

            if (remoteBelowState.getBlock() == Blocks.COBBLESTONE_WALL
                    && !headedToRemoteFlatWall(remoteBelowState, -1))
                return setHalfGrabType(HalfGrab, remoteBelowState);
        }

        // (8) bottom half block OR (stair bottom-not-back AND !(baseBelow stair bottom-front))
        if (isBottomHalfBlock(remoteState)
                || (isStairCompact(remoteState)
                    && isBottomStairCompactNotBack(remoteState)
                    && !(isStairCompact(getBaseBlockId(-1))
                         && isBottomStairCompactFront(getBaseBlockId(-1)))))
            return setHalfGrabType(HalfGrab, remoteState);

        // (9) remote trap door closed
        if (isTrapDoor(remoteState) && isClosedTrapDoor(remoteState))
            return setHalfGrabType(HalfGrab, remoteState);

        // (10) base trap door open
        BlockState baseState = getBaseBlockId(0);
        if (isTrapDoor(baseState) && !isClosedTrapDoor(baseState))
            return setHalfGrabType(HalfGrab, baseState, false);

        // 근사 이식 — 원본과 차이: ASGrapplingHook/RopesPlus isASRope + isASGrapplingHookFront
        //                        분기 (원본 L701-L711) 생략

        if (cfg.isFreeBaseClimb()) {
            int meta = baseVineClimbing(0);
            if (meta > DefaultMeta)
                return setHalfGrabType(HalfGrab, Blocks.VINE.defaultBlockState(), false, meta);
            meta = remoteVineClimbing(0);
            if (meta > DefaultMeta)
                return setHalfGrabType(HalfGrab, Blocks.VINE.defaultBlockState(), false, meta);
        }

        return setHalfGrabType(NoGrab, null);
    }

    // ════════════════════════════════════════════════════════════════════════
    // B-19a2d (세션 105) — hasBottomHold 본체
    // 원본: Orientation.java L728-L935 (200+줄).
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 원본 L728-L935 `hasBottomHold()` — 플레이어 하부 (y=-1) 레벨 grab 가능 판정.
     * `hasHalfHold` 와 유사 구조이나 하부 전용 복합 중첩 분기 많음.
     *
     * 주요 분기 (미매치면 최종 `NoGrab`):
     *   (1) FreeBaseClimb: 4 ladder 체크 (base-1 / base 0 / remote-1 / remote 0) → AroundGrab
     *   (2) [§7 근사 생략] BetterThanWolves/RopesPlus rope/anchor 분기
     *   (3) [§7 근사 생략] RedPower wire 4 sub 분기
     *   (4) isEmpty(-1) + remoteBelow iron_bars + frontWall → HalfGrab
     *   (5) freeFenceClimbing 3 서브:
     *       a) remoteBelow fence + frontWall (baseBelow fence 여부로 2갈래)
     *       b) remoteBelow cobblestone_wall + !flatWall
     *       c) remote cobblestone_wall + !flatWall
     *   (6) belowWallId 존재 시 중첩 분기:
     *       a) isEmpty 양옆(0, -1) + iron_bars + baseWall(-1) → BottomGrab
     *       b) isOnMiddleLadderFront(-1) → AroundGrab (remote, HALF)
     *       c) headedToBaseGrabWall(-1) → BottomGrab
     *       d) freeFenceClimbing + fence + baseWall(-1) → BottomGrab
     *   (7) 복합 중첩 (remoteLowerHalfEmpty + isBaseAccessible(-1, true, false) + 6 AND 체크):
     *       → BottomGrab
     *   (8) stair compact remote + topStair+!topBack + upperHalfFrontFullSolid → BottomGrab
     *   (9) baseBelow open trap door → BottomGrab
     *   (10) baseBelow door top + frontBlocked + isBaseAccessible(0) → BottomGrab
     *   (11) [§7 근사 생략] ASGrapplingHook/RopesPlus 4 sub 분기
     *   (12)-(15) FreeBaseClimb: 4 vine (base-1 / base 0 / remote-1 / remote 0) → HalfGrab
     *   (16) 외 → NoGrab
     *
     * **§7 근사** (B-19a2d-approx-1): RedPower + BetterThanWolves + ASGrapplingHook +
     * RopesPlus + ASRope mod 분기 전부 생략. B-19a2a4 false/null 근사 및 B-19a2c 의
     * 동일 패턴과 일관.
     */
    protected boolean hasBottomHold() {
        SmartMovingConfig cfg = SmartMovingConfig.Config;

        if (cfg.isFreeBaseClimb()) {
            if (isOnLadder(-1) && isOnLadderFront(-1))
                return setBottomGrabType(AroundGrab, getBaseBlockId(-1), false);

            if (isOnLadder(0) && isOnLadderFront(0))
                return setBottomGrabType(AroundGrab, getBaseBlockId(0), false);

            if (remoteLadderClimbing(-1))
                return setBottomGrabType(AroundGrab, getRemoteBlockId(-1), true);

            if (remoteLadderClimbing(0))
                return setBottomGrabType(AroundGrab, getRemoteBlockId(0), true);
        }

        // 근사 이식 — 원본과 차이: BetterThanWolves/RopesPlus rope+anchor 분기 (원본 L749-L759) 생략

        BlockState remoteState = getRemoteBlockId(0);
        BlockState remoteBelowState = getRemoteBlockId(-1);
        boolean remoteLowerHalfEmpty = isLowerHalfFrontFullEmpty(remote_i, 0, remote_k);

        // 근사 이식 — 원본과 차이: RedPower wire 4 sub 분기 (원본 L765-L795) 생략

        if (isEmpty(base_i, -1, base_k)) {
            // 🔴 사용자 요구: fence ≡ iron_bars 동일 동작. iron_bars 무조건 분기에 fence 통합.
            if ((remoteBelowState.getBlock() == Blocks.IRON_BARS || isFence(remoteBelowState))
                    && headedToFrontWall(remote_i, -1, remote_k, remoteBelowState))
                return setBottomGrabType(HalfGrab, remoteBelowState);
        }

        // 🔴 b 옵션 (사용자 요구 fence ≡ iron_bars 통합 + 끝까지 등반 보장):
        //   원본 L698-702 fence sub-branch 를 iron_bars 통합 형태로 복원.
        //   기존 통합 분기 (L2562-2564) 는 base 아래가 비어있는 케이스만. 사용자 발 아래
        //   fence/iron_bars 가 있고, 그 아래도 fence/iron_bars 인 수직 줄 등반 매핑 fail →
        //   끝까지 못 올라감 (사용자 보고).
        if (cfg.freeFenceClimbing) {
            BlockState baseBelowStateLocal = getBaseBlockId(-1);

            // 1. 원본 L698-702: 수직 fence/iron_bars 줄 등반 (remoteBelow + baseBelow 모서리)
            boolean remoteBelowIsBars = remoteBelowState.getBlock() == Blocks.IRON_BARS || isFence(remoteBelowState);
            boolean baseBelowIsBars = baseBelowStateLocal.getBlock() == Blocks.IRON_BARS || isFence(baseBelowStateLocal);
            if (remoteBelowIsBars && headedToFrontWall(remote_i, -1, remote_k, remoteBelowState)) {
                if (!baseBelowIsBars)
                    return setBottomGrabType(HalfGrab, remoteBelowState);
                else if (headedToFrontSideWall(remote_i, -1, remote_k, remoteBelowState))
                    return setBottomGrabType(HalfGrab, remoteBelowState);
            }

            // 2. cobblestone_wall (기존)
            if (remoteBelowState.getBlock() == Blocks.COBBLESTONE_WALL
                    && !headedToRemoteFlatWall(remoteBelowState, -1))
                return setHalfGrabType(HalfGrab, remoteBelowState);

            if (remoteState.getBlock() == Blocks.COBBLESTONE_WALL
                    && !headedToRemoteFlatWall(remoteState, 0))
                return setHalfGrabType(HalfGrab, remoteState);
        }

        BlockState belowWallBlockState = getWallBlockId(base_i, -1, base_k);
        if (belowWallBlockState != null) {
            if (isEmpty(base_i - _i, 0, base_k - _k)
                    && isEmpty(base_i - _i, -1, base_k - _k)) {
                // 🔴 사용자 요구: fence ≡ iron_bars 동일 동작. iron_bars 무조건 분기에 fence 통합.
                if ((belowWallBlockState.getBlock() == Blocks.IRON_BARS || isFence(belowWallBlockState))
                        && headedToBaseWall(-1, belowWallBlockState))
                    return setBottomGrabType(HalfGrab, belowWallBlockState, false);
                if (isOnMiddleLadderFront(-1))
                    return setHalfGrabType(AroundGrab, remoteState, false);

                if (headedToBaseGrabWall(-1, belowWallBlockState))
                    return setBottomGrabType(HalfGrab, belowWallBlockState, false);
            }

            // 🔴 사용자 요구: fence ≡ iron_bars 동일 동작. fence 전용 belowWall 분기 제거 —
            //   위 통합 분기 (L2574 IRON_BARS || isFence) 에서 처리됨.
        }

        // (7) 복합 중첩 — 원본 L853-L865
        if (remoteLowerHalfEmpty && isBaseAccessible(-1, true, false))
            if (isUpperHalfFrontAnySolid(remote_i, -1, remote_k))
                if (!isBottomHalfBlock(remoteBelowState))
                    if (!isStairCompact(remoteBelowState)
                            || !isBottomStairCompactFront(remoteBelowState))
                        if (!isDoor(remoteBelowState) || isDoorTop(remoteBelowState))
                            if (!isDoor(getBaseBlockId(0))
                                    || !isDoorFrontBlocked(base_i, 0, base_k))
                                if (cfg.freeFenceClimbing
                                        || !isFence(remote_i, -1, remote_k))
                                    return setBottomGrabType(HalfGrab, remoteBelowState);

        // (8) 원본 L867-L874 stair compact top-front + upper half front full solid
        if (isStairCompact(remoteState)) {
            if (isTopStairCompact(remoteState)
                    && !isTopStairCompactBack(remoteState)
                    && isUpperHalfFrontFullSolid(remote_i, -1, remote_k))
                return setBottomGrabType(HalfGrab, remoteBelowState);
        }

        BlockState baseBelowState = getBaseBlockId(-1);

        // (9) baseBelow open trap door — 원본 L879-L881
        if (isTrapDoor(baseBelowState) && !isClosedTrapDoor(baseBelowState))
            return setBottomGrabType(HalfGrab, baseBelowState, false);

        // (10) baseBelow door top + frontBlocked + baseAccessible(0) — 원본 L883-L886
        if (isDoor(baseBelowState) && isDoorTop(baseBelowState)
                && isDoorFrontBlocked(base_i, -1, base_k)
                && isBaseAccessible(0))
            return setBottomGrabType(HalfGrab, baseBelowState, false);

        // 근사 이식 — 원본과 차이: ASGrapplingHook/RopesPlus 4 sub 분기 (원본 L888-L909) 생략

        if (cfg.isFreeBaseClimb()) {
            int meta = baseVineClimbing(-1);
            if (meta != DefaultMeta)
                return setHalfGrabType(HalfGrab, Blocks.VINE.defaultBlockState(), false, meta);

            meta = baseVineClimbing(0);
            if (meta != DefaultMeta)
                return setHalfGrabType(HalfGrab, Blocks.VINE.defaultBlockState(), false, meta);

            meta = remoteVineClimbing(-1);
            if (meta != DefaultMeta)
                return setHalfGrabType(HalfGrab, Blocks.VINE.defaultBlockState(), false, meta);

            meta = remoteVineClimbing(0);
            if (meta != DefaultMeta)
                return setHalfGrabType(HalfGrab, Blocks.VINE.defaultBlockState(), false, meta);
        }

        return setBottomGrabType(NoGrab, null);
    }

    // ════════════════════════════════════════════════════════════════════════
    // B-19a2e (세션 106) — isLadderSubstitute 본체 + 4 메서드 / B-19 도미노 해소 실체
    // 원본: Orientation.java L299-L327 (public Feet/Hands + internal wrapper),
    //       L477-L606 (isLadderSubstitute 본체 130줄 gap 1-5 계산).
    //
    // **핵심**: gap 계산 + `ClimbGap.canStand=gap>3` / `mustCrawl=gap>1 && gap<4` 설정 —
    // B-17/B-18/B-16c 공식의 `hasClimbGap`/`hasClimbCrawlGap` 무력화를 해소.
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 원본 L299-L306 `isFeetLadderSubstitute(World world, int bi, int j, int bk)` — 발 레벨
     * ladder 대체 가능 여부. `(bi+_i, j, bk+_k)` 원격 위치에서 middle/base 두 offset gap 체크.
     */
    public boolean isFeetLadderSubstitute(Level w, int bi, int j, int bk) {
        int i = bi + _i;
        int k = bk + _k;
        return isLadderSubstitute(w, i, j, k, middle) > 0
            || isLadderSubstitute(w, i, j, k, base) > 0;
    }

    /**
     * 원본 L308-L316 `isHandsLadderSubstitute(World world, int bi, int j, int bk)` — 손 레벨
     * ladder 대체 가능. middle/base/sub 3 offset gap 체크.
     */
    public boolean isHandsLadderSubstitute(Level w, int bi, int j, int bk) {
        int i = bi + _i;
        int k = bk + _k;
        return isLadderSubstitute(w, i, j, k, middle) > 0
            || isLadderSubstitute(w, i, j, k, base) > 0
            || isLadderSubstitute(w, i, j, k, sub) > 0;
    }

    /**
     * 원본 L318-L327 `isLadderSubstitute(World worldObj, int i, int j, int k, int halfOffset)` —
     * 외부 API 래퍼. static 상태 필드 (world/remote_i/all_j/remote_k/all_offset) 를 설정하고
     * 내부 본체 호출.
     */
    private int isLadderSubstitute(Level worldObj, int i, int j, int k, int halfOffset) {
        world = worldObj;
        remote_i = i;
        all_j = j;
        remote_k = k;
        all_offset = 0;
        return isLadderSubstitute(halfOffset, null);
    }

    /**
     * 원본 L477-L606 `isLadderSubstitute(int local_Offset, ClimbGap out_climbGap)` — gap 계산
     * 본체 (130줄). local_half (0/1) 로 upper/lower half 분기, 각각 hasHalfHold/hasBottomHold
     * 로 grab 가능 확인 후 `overLadder`/`overAccessible`/`overFullAccessible` 조합으로
     * gap 1-5 결정.
     *
     * 최종 `ClimbGap` 설정 (gap > 0 시):
     *   state = grabBlock
     *   canStand = gap > 3  ← B-17/B-18 `hasClimbGap` 의 원천
     *   mustCrawl = gap > 1 && gap < 4  ← `hasClimbCrawlGap` 의 원천
     *   direction = this
     *
     * 1.21.1 매핑:
     *   - `out_climbGap.Block = grabBlock` → `out_climbGap.state = grabBlock` (BlockState)
     *   - `out_climbGap.Meta = grabMeta` **생략** — 1.21.1 ClimbGap 에 meta 필드 없음
     *     (BlockState 내재 표면 매핑).
     */
    protected int isLadderSubstitute(int local_Offset, ClimbGap out_climbGap) {
        initializeLocal(local_Offset);

        int gap;
        if (local_half == 1) {
            // upper half — hasHalfHold 로 grab 가능 여부 확인
            if (hasHalfHold()) {
                if (!grabRemote) {
                    // base grab (플레이어 자신이 잡음)
                    boolean overLadder = isOnLadderOrVine(0)
                                      || isOnOpenTrapDoor(0)
                                      || isRope(0)
                                      || isOnWallRope(0);
                    boolean overOverLadder = isOnLadderOrVine(1)
                                          || isOnOpenTrapDoor(1)
                                          || isRope(1)
                                          || isOnWallRope(1);
                    boolean overAccessible = isBaseAccessible(1, false, true);
                    boolean overOverAccessible = isBaseAccessible(2, false, true);
                    boolean overFullAccessible = overAccessible
                            && isFullAccessible(1, grabRemote);
                    boolean overOverFullAccessible = overAccessible
                            && isFullExtentAccessible(2, grabRemote);

                    if (overLadder) {
                        if (overOverLadder)
                            gap = 1;
                        else if (overOverAccessible)
                            gap = 1;
                        else
                            gap = 1;
                    } else if (overAccessible) {
                        if (overFullAccessible) {
                            if (overOverFullAccessible)
                                gap = 5;
                            else
                                gap = crawl ? 3 : 5;
                        } else if (overOverLadder)
                            gap = 5;
                        else
                            gap = 1;
                    } else
                        gap = 1;
                } else if (isBaseAccessible(0)) {
                    // remote grab (이 방향 블록을 잡음)
                    if (isUpperHalfFrontEmpty(remote_i, 0, remote_k)) {
                        if (isFullAccessible(1, grabRemote)) {
                            if (isFullExtentAccessible(2, grabRemote))
                                gap = 5;
                            else if (isJustLowerHalfExtentAccessible(2))
                                gap = 4;
                            else
                                gap = 3;
                        } else
                            gap = 1;
                    } else
                        gap = 1;
                } else
                    gap = 0;
            } else
                gap = 0;
        } else {
            // lower half — hasBottomHold 로 grab 가능 확인
            if (hasBottomHold()) {
                if (!grabRemote) {
                    boolean overLadder = isOnLadderOrVine(0)
                                      || isOnOpenTrapDoor(0)
                                      || isRope(0)
                                      || isOnWallRope(0);
                    // 원본 L548-L550: 마지막은 `isOnWallRope(0)` (0 이지 1 아님) — 원본 버그
                    // 가능성 있으나 **1:1 이식** 원칙 유지.
                    boolean overOverLadder = isOnLadderOrVine(1)
                                          || isOnOpenTrapDoor(1)
                                          || isRope(1)
                                          || isOnWallRope(0);
                    boolean overAccessible = isBaseAccessible(0, false, true);
                    boolean overOverAccessible = isBaseAccessible(1, false, true);
                    boolean overFullAccessible = overAccessible
                            && isFullAccessible(0, grabRemote);
                    boolean overOverFullAccessible = overAccessible
                            && isFullExtentAccessible(1, grabRemote);

                    if (overLadder) {
                        if (overOverLadder)
                            gap = 1;
                        else if (overOverAccessible)
                            gap = 1;
                        else
                            gap = 1;
                    } else if (overAccessible) {
                        if (overFullAccessible) {
                            if (overOverAccessible) {
                                if (overOverFullAccessible)
                                    gap = 4;
                                else
                                    gap = crawl ? 2 : 4;
                            } else
                                gap = 2;
                        } else if (overOverLadder)
                            gap = 2;
                        else
                            gap = 1;
                    } else
                        gap = 1;
                } else if (isBaseAccessible(0)) {
                    if (isFullAccessible(0, grabRemote)) {
                        if (isFullExtentAccessible(1, grabRemote))
                            gap = 4;
                        else
                            gap = 2;
                    } else
                        gap = 1;
                } else
                    gap = 0;
            } else
                gap = 0;
        }

        if (out_climbGap != null && gap > 0) {
            out_climbGap.state = grabBlock;
            // 근사 이식 — 원본과 차이: out_climbGap.Meta = grabMeta 생략 (1.21.1 BlockState
            // 에 metadata 내재, ClimbGap 에 Meta 필드 없음).
            out_climbGap.canStand  = gap > 3;
            out_climbGap.mustCrawl = gap > 1 && gap < 4;
            out_climbGap.direction = this.toBlockDirection();
        }
        return gap;
    }

    // ════════════════════════════════════════════════════════════════════════
    // B-19a3 (세션 107) — handsClimbing() + feetClimbing() 판정
    // 원본: Orientation.java L329-L395 (handsClimbing) + L398-L475 (feetClimbing).
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 원본 L329-L395 `handsClimbing(isClimbCrawling, isCrawlClimbing, isCrawling, out_climbGap)`.
     *
     * 손 레벨 ladder 상태 판정. `initializeOffset(3D, ...)` 후 4 halfOffset (middle/base/
     * sub/subSub) 별 `isLadderSubstitute` 호출 결과로 HandsClimbing + ClimbGap 결정.
     *
     *   middle: gap > 0 → jh_offset 기반 Up 또는 None
     *   base: gap > 0 → jh_offset 기반 BottomHold 또는 Up
     *   sub (SkipGaps 설정 후): gap > 0 && !(isCrawling && gap > 1) → 4 갈래
     *     (FastUp / TopHold / Up / Sink) — isClimbCrawling / grabType / jh_offset 조합
     *   subSub: gap > 0 && !isCrawling → 3 갈래 (TopHold / FastUp / Sink)
     */
    protected HandsClimbing handsClimbing(boolean isClimbCrawling, boolean isCrawlClimbing,
                                          boolean isCrawling, ClimbGap out_climbGap) {
        out_climbGap.reset();
        _climbGapTemp.reset();

        initializeOffset(3D, isClimbCrawling, isCrawlClimbing, isCrawling);

        HandsClimbing result = HandsClimbing.NONE;
        int gap;

        ClimbGap[] outArr = { out_climbGap };

        if ((gap = isLadderSubstitute(middle, _climbGapTemp)) > 0) {
            if (jh_offset > 1D - _handClimbingHoldGap())
                result = result.max(HandsClimbing.UP, outArr, _climbGapTemp);
            else
                result = result.max(HandsClimbing.NONE, outArr, _climbGapTemp);
        }

        if ((gap = isLadderSubstitute(base, _climbGapTemp)) > 0) {
            if (jh_offset < _handClimbingHoldGap())
                result = result.max(HandsClimbing.BOTTOM_HOLD, outArr, _climbGapTemp);
            else
                result = result.max(HandsClimbing.UP, outArr, _climbGapTemp);
        }

        _climbGapTemp.skipGaps = isClimbCrawling || isCrawlClimbing;

        if ((gap = isLadderSubstitute(sub, _climbGapTemp)) > 0
                && !(isCrawling && gap > 1)) {
            if (!isClimbCrawling && gap > 2) {
                result = result.max(HandsClimbing.FAST_UP, outArr, _climbGapTemp);
            } else if (isClimbCrawling && gap > 1) {
                result = result.max(HandsClimbing.FAST_UP, outArr, _climbGapTemp);
            } else {
                if (jh_offset < _handClimbingHoldGap()) {
                    if (grabType == AroundGrab)
                        result = result.max(HandsClimbing.UP, outArr, _climbGapTemp);
                    else
                        result = result.max(HandsClimbing.TOP_HOLD, outArr, _climbGapTemp);
                } else {
                    if (grabType == AroundGrab)
                        result = result.max(HandsClimbing.TOP_HOLD, outArr, _climbGapTemp);
                    else
                        result = result.max(HandsClimbing.SINK, outArr, _climbGapTemp);
                }
            }
        }

        if ((gap = isLadderSubstitute(subSub, _climbGapTemp)) > 0 && !isCrawling) {
            if ((gap > 2 && !isCrawlClimbing)
                    || grabType == AroundGrab
                    || (gap > 1 && isClimbCrawling)) {
                if (jh_offset < _handClimbingHoldGap() && !isClimbCrawling)
                    result = result.max(HandsClimbing.TOP_HOLD, outArr, _climbGapTemp);
                else if (isClimbCrawling)
                    result = result.max(HandsClimbing.FAST_UP, outArr, _climbGapTemp);
                else
                    result = result.max(HandsClimbing.SINK, outArr, _climbGapTemp);
            }
        }

        return result;
    }

    /**
     * 원본 L398-L475 `feetClimbing(isClimbCrawling, isCrawlClimbing, isCrawling, out_climbGap)`.
     *
     * 발 레벨 ladder 상태 판정. `initializeOffset(0D, ...)` 후 4 halfOffset (top/middle/
     * base/sub) 별 `isLadderSubstitute` 호출 결과로 FeetClimbing + ClimbGap 결정.
     *
     *   top: gap > 0 → None 선택 (placeholder — ClimbGap 만 업데이트)
     *   middle (SkipGaps 설정 후): gap > 0 && !isCrawling → 4 갈래
     *     * gap > 3 && !isClimbCrawling: FastUp 또는 None (isCrawlClimbing 여부)
     *     * (isClimbCrawling || isCrawlClimbing) && gap > 1: BaseWithHands/FastUp
     *     * gap > 2: SlowUpWithHoldWithoutHands / None
     *     * else: TopWithHands
     *   base: gap > 0 → jh_offset / gap / isCrawl 조합 5 갈래
     *   sub: gap > 0 → None
     *   최종: isCrawlClimbing || isCrawling → BaseWithHands 강제 승격
     */
    protected FeetClimbing feetClimbing(boolean isClimbCrawling, boolean isCrawlClimbing,
                                        boolean isCrawling, ClimbGap out_climbGap) {
        out_climbGap.reset();
        _climbGapTemp.reset();

        initializeOffset(0D, isClimbCrawling, isCrawlClimbing, isCrawling);
        FeetClimbing result = FeetClimbing.NONE;
        int gap;

        ClimbGap[] outArr = { out_climbGap };

        if ((gap = isLadderSubstitute(top, _climbGapTemp)) > 0)
            result = result.max(FeetClimbing.NONE, outArr, _climbGapTemp);

        _climbGapTemp.skipGaps = isClimbCrawling || isCrawlClimbing;

        if ((gap = isLadderSubstitute(middle, _climbGapTemp)) > 0 && !isCrawling) {
            if (gap > 3 && !isClimbCrawling) {
                if (!isCrawlClimbing)
                    result = result.max(FeetClimbing.FAST_UP, outArr, _climbGapTemp);
                else
                    result = result.max(FeetClimbing.NONE, outArr, _climbGapTemp);
            } else if ((isClimbCrawling || isCrawlClimbing) && gap > 1) {
                if (isCrawlClimbing)
                    result = result.max(FeetClimbing.BASE_WITH_HANDS, outArr, _climbGapTemp);
                else
                    result = result.max(FeetClimbing.FAST_UP, outArr, _climbGapTemp);
            } else if (gap > 2) {
                if (!isClimbCrawling)
                    result = result.max(FeetClimbing.SLOW_UP_WITH_HOLD_WITHOUT_HANDS,
                            outArr, _climbGapTemp);
                else
                    result = result.max(FeetClimbing.NONE, outArr, _climbGapTemp);
            } else {
                result = result.max(FeetClimbing.TOP_WITH_HANDS, outArr, _climbGapTemp);
            }
        }

        if ((gap = isLadderSubstitute(base, _climbGapTemp)) > 0) {
            if (gap > 3 && !isCrawling && !isCrawlClimbing) {
                result = result.max(FeetClimbing.FAST_UP, outArr, _climbGapTemp);
            } else if (gap > 2 && !isCrawling) {
                if (!isClimbCrawling) {
                    if (jh_offset < _handClimbingHoldGap())
                        result = result.max(FeetClimbing.SLOW_UP_WITH_HOLD_WITHOUT_HANDS,
                                outArr, _climbGapTemp);
                    else
                        result = result.max(FeetClimbing.SLOW_UP_WITH_SINK_WITHOUT_HANDS,
                                outArr, _climbGapTemp);
                } else {
                    result = result.max(FeetClimbing.NONE, outArr, _climbGapTemp);
                }
            } else {
                if (jh_offset < 1D - _handClimbingHoldGap())
                    result = result.max(FeetClimbing.BASE_WITH_HANDS, outArr, _climbGapTemp);
                else
                    result = result.max(FeetClimbing.BASE_HOLD, outArr, _climbGapTemp);
            }
        }

        if (isLadderSubstitute(sub, _climbGapTemp) > 0)
            result = result.max(FeetClimbing.NONE, outArr, _climbGapTemp);

        if (isCrawlClimbing || isCrawling)
            result = result.max(FeetClimbing.BASE_WITH_HANDS, outArr, _climbGapTemp);

        return result;
    }

    // ════════════════════════════════════════════════════════════════════════
    // B-19a4 (세션 108) — seekClimbGap 메서드 이식
    // 원본: Orientation.java L207-L224.
    // **B-19 도미노 해소의 외부 API 진입점** — Climber 에서 각 방향 호출.
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 원본 L207-L224 `seekClimbGap(rotation, world, i, id, jhd, k, kd, isClimbCrawling,
     * isCrawlClimbing, isCrawling, inout_handsClimbing, inout_feetClimbing,
     * out_handsClimbGap, out_feetClimbGap)`.
     *
     * 이 Orientation 이 플레이어 회전각 범위 (`isRotationForClimbing`) 에 맞을 때만
     * 내부 상태 (`initialize`) 설정하고 `handsClimbing`/`feetClimbing` 판정 결과를
     * inout 파라미터에 max 누적. 각 방향별 호출 결과를 합산하는 패턴.
     *
     * 1.21.1 매핑: `HandsClimbing.max` / `FeetClimbing.max` 의 `inout_thisGap` 이
     * `ClimbGap[]` 배열 래퍼이므로 `out_handsClimbGap` / `out_feetClimbGap` 을 배열
     * 래핑해서 전달.
     */
    public void seekClimbGap(float rotation, Level w, int i, double id,
                             double jhd, int k, double kd,
                             boolean isClimbCrawling, boolean isCrawlClimbing, boolean isCrawling,
                             HandsClimbing[] inout_handsClimbing, FeetClimbing[] inout_feetClimbing,
                             ClimbGap out_handsClimbGap, ClimbGap out_feetClimbGap) {
        if (isRotationForClimbing(rotation)) {
            initialize(w, i, id, jhd, k, kd);

            ClimbGap[] handsArr = { out_handsClimbGap };
            inout_handsClimbing[0] = inout_handsClimbing[0].max(
                    handsClimbing(isClimbCrawling, isCrawlClimbing, isCrawling, _climbGapOuterTemp),
                    handsArr, _climbGapOuterTemp);

            ClimbGap[] feetArr = { out_feetClimbGap };
            inout_feetClimbing[0] = inout_feetClimbing[0].max(
                    feetClimbing(isClimbCrawling, isCrawlClimbing, isCrawling, _climbGapOuterTemp),
                    feetArr, _climbGapOuterTemp);
        }
    }
}
