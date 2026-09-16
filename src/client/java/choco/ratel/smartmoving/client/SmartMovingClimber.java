package choco.ratel.smartmoving.client;

import choco.ratel.smartmoving.climbing.ClimbGap;
import choco.ratel.smartmoving.climbing.FeetClimbing;
import choco.ratel.smartmoving.climbing.HandsClimbing;
import choco.ratel.smartmoving.climbing.Orientation;
import choco.ratel.smartmoving.client.input.SmartMovingKeys;
import choco.ratel.smartmoving.config.SmartMovingConfig;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.stats.Stats;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import choco.ratel.smartmoving.climbing.CeilingClimbBlocks;

/**
 * Client-side climbing physics logic.
 * Original: Ported climbing-related methods from SmartMovingBase / SmartMovingSelf.
 *
 * Contents:
 *   1-7: getOnLadderOrVine() — ladder/vine detection
 *   7-1: climbing speed constants
 *   7-2: setShouldClimbSpeed() / handleClimbing()
 *   7-3: handleCeilingClimbing()
 */
@Environment(EnvType.CLIENT)
public final class SmartMovingClimber {

    // ── 7-1: Climbing speed constants ──────────────────────────────────────
    // Original: SmartMovingContext static constants

    /** Fast climbing (FAST_UP state) */
    public static final double FAST_UP_MOTION        = 0.2D;
    /** Speed cap when entering crawl gap */
    public static final double CATCH_CRAWL_GAP_MOTION = 0.17D;
    /** Medium climbing */
    public static final double MEDIUM_UP_MOTION       = 0.14D;
    /** Slow climbing */
    public static final double SLOW_UP_MOTION         = 0.1D;
    /** Stationary hold (holding) */
    public static final double HOLD_MOTION            = 0.08D;
    /** Slow descending */
    public static final double SINK_DOWN_MOTION       = 0.05D;
    /** Descending ladder */
    public static final double CLIMB_DOWN_MOTION      = 0.01D;
    /** Vine pulling moveForward value */
    public static final float  CLIMB_PULL_MOTION      = 0.3F;

    private SmartMovingClimber() {}

    // ── B-16a: isFacedToLadder / isFacedToSolidVine (session 69) ──────────

    /**
     * Original SmartMovingBase L184-L186 `isFacedToLadder(boolean isSmall)`:
     *   return getOnLadder(1, true, isSmall) > 0;
     *   where getOnLadder(maxResult, faceOnly=true, isSmall) =
     *     getOnLadderOrVine(maxResult, faceOnly=true, onlyLadder=true, onlyVine=false, isSmall)
     *
     * 1.21.1 `getOnLadderOrVine` has no ladder/vine filter parameters, so it detects both,
     * but `out_handsVine[0]` / `out_feetVine[0]` flags allow distinguishing vine → approximation
     * **selects ladder only** (excluding vine). `faceOnly=true` searches only the direction
     * player is facing among 4 orthogonal directions.
     *
     * Usage: 3rd branch of `wouldWantClimb` 4-OR (original L2471) — automatic ladder entry.
     * B-16a (session 69).
     */
    public static boolean isFacedToLadder(LocalPlayer player, boolean isSmall) {
        Level world = player.level();
        HandsClimbing[] h = {HandsClimbing.NONE};
        FeetClimbing[]  f = {FeetClimbing.NONE};
        ClimbGap[] hg = {new ClimbGap()};
        ClimbGap[] fg = {new ClimbGap()};
        boolean[]  hv = {false};
        boolean[]  fv = {false};
        getOnLadderOrVine(player, world, isSmall, true, h, f, hg, fg, hv, fv);
        // Approximation: ladder-only filter unavailable → select only relevant cases without vine.
        return (h[0].isRelevant() && !hv[0]) || (f[0].isRelevant() && !fv[0]);
    }

    /**
     * Original SmartMovingBase L209-L312 `getOnLadder(maxResult, faceOnly, isSmall)` 1:1.
     *   = getOnLadderOrVine(maxResult, faceOnly, ladder=true, vine=false, isSmall).
     *
     * Returns ladder count (up to maxResult). Called from setOnlyShouldClimbSpeed's (ladder/vine
     * unified 1-2) MediumUpMotion branch — 1 ladder applies freeOneLadderClimbUpSpeedFactor
     * (1.0153), 2+ ladders apply freeBothLadderClimbUpSpeedFactor (1.43).
     *
     * User intent: "apply original ladder behavior equally to regular vines" — also count VineBlocks.
     */
    public static int getOnLadderOrVineCount(LocalPlayer player, int maxResult,
                                             boolean faceOnly, boolean isSmall) {
        Level world = player.level();
        int px = (int) Math.floor(player.getX());
        int minj = (int) Math.floor(player.getBoundingBox().minY);
        int pz = (int) Math.floor(player.getZ());

        if (isSmall) minj--;

        Direction facedDir = faceOnly ? player.getDirection() : null;

        int result = 0;
        int maxj = (int) Math.floor(player.getBoundingBox().minY
                + Math.ceil(player.getBoundingBox().maxY - player.getBoundingBox().minY)) - 1;

        for (int j = minj; j <= maxj; j++) {
            if (result >= maxResult) return result;

            // Center position LadderBlock or VineBlock
            BlockState centerState = world.getBlockState(new BlockPos(px, j, pz));
            Direction localLadderFacing = null;
            if (centerState.getBlock() instanceof LadderBlock) {
                localLadderFacing = centerState.getValue(LadderBlock.FACING);
                if (facedDir == null || facedDir == localLadderFacing) {
                    result++;
                }
            } else if (centerState.getBlock() instanceof VineBlock) {
                // User intent: regular vines counted same as ladder.
                if (facedDir == null) {
                    result++;
                }
            }

            if (result >= maxResult) return result;

            // 4 orthogonal adjacent LadderBlocks (original L260-269) — ladder faces toward us.
            for (Direction dir : Direction.Plane.HORIZONTAL) {
                if (result >= maxResult) return result;
                if (faceOnly && dir != facedDir) continue;
                if (dir == localLadderFacing) continue;  // Exclude same direction as center ladder

                BlockState s = world.getBlockState(new BlockPos(
                        px + dir.getStepX(), j, pz + dir.getStepZ()));
                if (s.getBlock() instanceof LadderBlock) {
                    if (s.getValue(LadderBlock.FACING).getOpposite() == dir) {
                        result++;
                    }
                } else if (s.getBlock() instanceof VineBlock) {
                    // Adjacent vine: face property faces toward us (opposite of dir).
                    boolean hasFace = switch (dir) {
                        case NORTH -> s.getValue(VineBlock.SOUTH);  // North adjacent vine's south face = toward us
                        case SOUTH -> s.getValue(VineBlock.NORTH);
                        case EAST  -> s.getValue(VineBlock.WEST);
                        case WEST  -> s.getValue(VineBlock.EAST);
                        default    -> false;
                    };
                    if (hasFace) result++;
                }
            }
        }
        return result;
    }

    /**
     * Original SmartMovingBase L189-L192 `isFacedToSolidVine(boolean isSmall)`:
     *   return getOnVine(1, true, isSmall) > 0;
     *   where getOnVine(maxResult, faceOnly=true, isSmall) =
     *     getOnLadderOrVine(maxResult, faceOnly=true, onlyLadder=false, onlyVine=true, isSmall)
     *
     * 1.21.1 `getOnLadderOrVine` L143-L144 already detects only "vine behind solid block" → solid vine
     * condition built-in. `out_handsVine/out_feetVine=true` = solid vine detected.
     *
     * Usage: 4th branch of `wouldWantClimb` 4-OR (original L2472) — automatic solid vine entry.
     * B-16a (session 69).
     */
    public static boolean isFacedToSolidVine(LocalPlayer player, boolean isSmall) {
        Level world = player.level();
        HandsClimbing[] h = {HandsClimbing.NONE};
        FeetClimbing[]  f = {FeetClimbing.NONE};
        ClimbGap[] hg = {new ClimbGap()};
        ClimbGap[] fg = {new ClimbGap()};
        boolean[]  hv = {false};
        boolean[]  fv = {false};
        getOnLadderOrVine(player, world, isSmall, true, h, f, hg, fg, hv, fv);
        return (h[0].isRelevant() && hv[0]) || (f[0].isRelevant() && fv[0]);
    }

    // ── 1-7: getOnLadderOrVine ────────────────────────────────────────────

    /**
     * Original: SmartMovingBase.getOnLadderOrVine(isSmall, faceOnly)
     *
     * Searches 4 directions around player (North/South/East/West) and
     * returns ladder/vine climbing availability and HandsClimbing/FeetClimbing states.
     *
     * Search range:
     *   - Hands position: player.getY() + 1 block
     *   - Feet position: player.getY()
     *   - isSmall=true: extends search down to player.getY() - 1 block
     *
     * Ladder detection:
     *   LadderBlock.FACING.getOpposite() == dir → confirms ladder attached from search direction
     *
     * Vine detection:
     *   VineBlock.DIR property in that direction = true
     *   AND block behind that direction is solid → climbable
     *
     * If faceOnly=true, searches only direction player is facing.
     */
    public static void getOnLadderOrVine(
            LocalPlayer player, Level world,
            boolean isSmall, boolean faceOnly,
            HandsClimbing[] out_hands, FeetClimbing[] out_feet,
            ClimbGap[] out_handsGap, ClimbGap[] out_feetGap,
            boolean[] out_handsVine, boolean[] out_feetVine) {

        out_hands[0] = HandsClimbing.NONE;
        out_feet[0]  = FeetClimbing.NONE;
        out_handsGap[0].reset();
        out_feetGap[0].reset();
        out_handsVine[0] = false;
        out_feetVine[0]  = false;

        int px = (int) Math.floor(player.getX());
        int py = (int) Math.floor(player.getY());
        int pz = (int) Math.floor(player.getZ());

        // Search Y range: feet(py) ~ hands(py+1), extends to py-1 if isSmall
        int minY = isSmall ? py - 1 : py;
        int maxY = py + 1; // hands position

        Direction playerFacing = player.getDirection();

        // 🔴 Ladder/vine automatic entry BUG: missing check of player's own position (px, by, pz).
        //   Original SmartMovingBase L260-286 flow:
        //     for(j = minj; j <= maxj; j++) {
        //         Block block = world.getBlock(i, j, k);  // Player's own position
        //         if (ladder && isKnownLadder(block) && faceMatch) result++;  // Inside ladder block
        //         if (vine && isVine(block)) result++;
        //         for (Orientation dir : Orthogonals) { ... adjacent check }
        //     }
        //   Our code only does adjacent check → when player is inside ladder block (ladder collision
        //   box is thin so player can enter), ladder not detected → climbing stops.
        //   Solution: Add check of player's own position (with ladder facing match if faceOnly).
        for (int by = minY; by <= maxY; by++) {
            BlockState centerState = world.getBlockState(new BlockPos(px, by, pz));
            boolean isHandsLevel = (by == maxY);

            // Ladder at player's own position
            if (centerState.getBlock() instanceof LadderBlock) {
                Direction ladderFacing = centerState.getValue(LadderBlock.FACING);
                // 🔴 BUG fix: vanilla LadderBlock.FACING = ladder front (facing player) direction.
                //   Player facing=NORTH (facing ladder) → ladder front=SOUTH → facing player.
                //   Match: ladderFacing.getOpposite() == playerFacing (= ladder front opposite = player front).
                if (!faceOnly || ladderFacing.getOpposite() == playerFacing) {
                    ClimbGap gap = new ClimbGap();
                    gap.state = centerState;
                    gap.direction = ladderFacing;
                    if (isHandsLevel) {
                        out_hands[0] = out_hands[0].max(HandsClimbing.UP, out_handsGap, gap);
                    } else {
                        out_feet[0] = out_feet[0].max(FeetClimbing.SLOW_UP_WITH_HOLD_WITHOUT_HANDS, out_feetGap, gap);
                    }
                }
            }

            // Vine at player's own position — active if any of 4 faces is true.
            //   If faceOnly=true, only player's facing face (= dir match), otherwise all.
            if (centerState.getBlock() instanceof VineBlock) {
                boolean hasAnyFace;
                if (faceOnly) {
                    hasAnyFace = switch (playerFacing) {
                        case NORTH -> centerState.getValue(VineBlock.NORTH);
                        case SOUTH -> centerState.getValue(VineBlock.SOUTH);
                        case EAST  -> centerState.getValue(VineBlock.EAST);
                        case WEST  -> centerState.getValue(VineBlock.WEST);
                        default    -> false;
                    };
                } else {
                    hasAnyFace = centerState.getValue(VineBlock.NORTH)
                              || centerState.getValue(VineBlock.SOUTH)
                              || centerState.getValue(VineBlock.EAST)
                              || centerState.getValue(VineBlock.WEST);
                }
                if (hasAnyFace) {
                    // Original vine check at own position: no solid block verification (L289-292 facedOnlyTo == null
                    // branch just result++. Only when faceOnly does hasVineOrientation && isRemoteSolid).
                    ClimbGap gap = new ClimbGap();
                    gap.state = centerState;
                    if (isHandsLevel) {
                        out_hands[0] = out_hands[0].max(HandsClimbing.UP, out_handsGap, gap);
                        out_handsVine[0] = true;
                    } else {
                        out_feet[0] = out_feet[0].max(FeetClimbing.SLOW_UP_WITH_HOLD_WITHOUT_HANDS, out_feetGap, gap);
                        out_feetVine[0] = true;
                    }
                }
            }
        }

        // 🔴 Ladder/vine automatic entry BUG 2: 4-direction adjacent check disabled.
        //   User report: "should not activate climbing from 1 block away from ladder (2 blocks forward)".
        //   Original SmartMovingBase L271-279 (adjacent check) is SM 1.7.10 behavior — climbable when
        //   1 block adjacent to ladder. Vanilla 1.21.1 behavior = isClimbing=true only when player's
        //   boundingBox touches ladder collision box (= floor(player.x)/floor(player.z) equals ladder position).
        //   User intent = vanilla behavior. → Disable adjacent check. Enable only player position check.
        //   (Vanilla ladder collision box is thin → automatic entry only when player's floor coordinate ==
        //    ladder position just before touching ladder face).
    }

    // ── 7-2: setShouldClimbSpeed / setOnlyShouldClimbSpeed ───────────────

    /**
     * Original: SmartMovingSelf.setShouldClimbSpeed(value, isUp, factor)
     *
     * Sets climbing Y velocity with factor interpolation.
     *
     * Condition for relevance: value < 0 || value > motionY
     * (Apply only when slower or descending. Leave unchanged if already fast enough.)
     *
     * Upward interpolation: motionY = (value - HOLD_MOTION) * upFactor * combinedFactor + HOLD_MOTION
     * Downward interpolation: motionY = HOLD_MOTION - (HOLD_MOTION - value) * downFactor * combinedFactor
     *
     * _freeClimbingUpSpeedFactor / _freeClimbingDownSpeedFactor original defaults = 1F (PositiveFactor).
     * Factored reading per Standard/Simple/Smart mode implemented in C-29.
     *
     * @return true if relevant (velocity changed)
     */
    /**
     * 🔴 (2026-04-27) Original SmartMovingSelf L1500-1551 1:1 reconstruction:
     *   Original call flow: setShouldClimbSpeed → setOnlyShouldClimbSpeed → `isClimbing=true` always.
     *   Previous mapping set `isClimbing=true` only when relevant → when motionY already sufficient,
     *   isClimbing not set → travel inject proceeds vanilla → no ladder damping → fall →
     *   next tick fires again → on/off oscillation = stuttering (user report BUG-1).
     */
    public static boolean setShouldClimbSpeed(LocalPlayer player, SmartMovingClientState sm,
                                               double value, boolean isUp, double combinedFactor) {
        // Original L1502: setShouldClimbSpeed(value) → setShouldClimbSpeed(value, UpGrab, DownStep).
        // We don't separately handle hands/feet type (only affects R-01 packet encoding) — call setOnly directly.
        return setOnlyShouldClimbSpeed(player, sm, value, isUp, combinedFactor);
    }

    /**
     * Original: SmartMovingSelf.setOnlyShouldClimbSpeed L1513-1551.
     *   `isClimbing = true` always upon entry. Apply motionY only when relevant.
     */
    public static boolean setOnlyShouldClimbSpeed(LocalPlayer player, SmartMovingClientState sm,
                                                   double value, boolean isUp, double combinedFactor) {
        // Original L1515: isClimbing=true always (any call with climbing activation intent).
        sm.isClimbing = true;

        // Original L1517-1518: If climbIntoCount>0, force to HoldMotion.
        if (sm.climbIntoCount > 0) {
            value = HOLD_MOTION;
            isUp = true;
        }

        double motionY = player.getDeltaMovement().y;

        // Original L1520-1543: Factor interpolation if value != HoldMotion. If HoldMotion, set isClimbingStill.
        if (value != HOLD_MOTION) {
            // Original L1541-1542: Cap to catchCrawlGap if hasClimbCrawlGap && isClimbCrawling.
            if (sm.hasClimbCrawlGap && sm.isClimbCrawling && value > HOLD_MOTION) {
                value = Math.min(CATCH_CRAWL_GAP_MOTION, value);
            }
            SmartMovingConfig cfg2 = SmartMovingConfig.Config;

            // 🔴 Ladder/vine 1-2: Original SmartMovingSelf L1525-1534 ladder correction branch 1:1.
            //   If isFreeBaseClimb && value == MediumUpMotion (0.14), apply based on ladder count:
            //     1 ladder → factor *= freeOneLadderClimbUpSpeedFactor (1.0153)
            //     2+ ladders → factor *= freeBothLadderClimbUpSpeedFactor (1.43)
            //   User intent: "apply ladder behavior equally to vines" → also count vines (getOnLadderOrVineCount).
            float ladderFactor = 1.0F;
            if (cfg2.isFreeBaseClimb() && value == MEDIUM_UP_MOTION) {
                int ladderCount = getOnLadderOrVineCount(player, Integer.MAX_VALUE, false, sm.isClimbCrawling);
                if (ladderCount == 1) {
                    ladderFactor = cfg2.freeOneLadderClimbUpSpeedFactor;
                } else if (ladderCount >= 2) {
                    ladderFactor = cfg2.freeBothLadderClimbUpSpeedFactor;
                }
            }

            if (isUp) {
                value = (value - HOLD_MOTION) * cfg2.freeClimbingUpSpeedFactor * ladderFactor * combinedFactor + HOLD_MOTION;
            } else {
                value = HOLD_MOTION - (HOLD_MOTION - value) * cfg2.freeClimbingDownSpeedFactor * combinedFactor;
            }
        } else {
            sm.isClimbingStill = true;
        }

        // Original L1547-1550: relevant = value<0 || value>motionY. Apply motionY only if relevant.
        boolean relevant = value < 0 || value > motionY;
        if (relevant) {
            player.setDeltaMovement(player.getDeltaMovement().x, value, player.getDeltaMovement().z);
        }
        sm.isClimbJumping = !relevant && !sm.isClimbHolding;
        return relevant;
    }

    /**
     * Original: SmartMovingSelf.handleClimbing() — Free mode core logic.
     *
     * Processing order:
     * 1. Check exhaustion
     * 2. 8-direction search (4 orthogonal + 4 diagonal)
     * 3. Aggregate HandsClimbing/FeetClimbing states
     * 4. Determine wantClimbUp/wantClimbDown from grab key + movementForward
     * 5. Determine Y velocity via setShouldClimbSpeed
     * 6. fallDistance = 0 (prevent fall damage)
     */
    public static void handleClimbing(LocalPlayer player, SmartMovingClientState sm) {
        SmartMovingConfig cfg = SmartMovingConfig.Config;

        // B-14 (session 57): Original L816 `resetClimbing()` reset every tick.
        // Common to Standard/Simple/Smart/Free Base Climb — immediately after handleClimbing entry,
        // always reset 10 climbing fields to false/0. Actual values recalculated in branches.
        // B-21 (isCeilingClimbing release edge) automatically resolved.
        sm.resetClimbing();

        // C-33: SM-specific exhaustion check
        // Original: exhaustionAllowsClimbing = !enabled || (exhaustion<=stop && (wasClimbing||exhaustion<=start))
        if (cfg.climbExhaustion) {
            boolean allowed = sm.exhaustion <= cfg.climbExhaustionStop
                    && (sm.wasClimbing || sm.exhaustion <= cfg.climbExhaustionStart);
            if (!allowed) return;
        }

        if (!cfg.freeClimb && !cfg.simpleClimb && !cfg.smartClimb) {
            // 🔴 Ladder/vine 1-4: Original SmartMovingSelf L820-L823 Standard Base Climb precise 1:1.
            //   Original: if(isStandardBaseClimb && isCollidedHorizontally && isOnLadderOrVine)
            //               sp.motionY = 0.2 * getCombinedSpeedFactor();
            //   isOnLadderOrVine is implied by MixinLivingEntityClient onClimbable guard.
            //   Original directly sets motionY (no sm.isClimbing update) → setShouldClimbSpeed
            //   call (with relevant guard + isClimbing=true) has side effects → changed to direct set.
            if (player.horizontalCollision) {
                double combinedFactor = SmartMovingMover.getCombinedSpeedFactor(player, cfg);
                Vec3 v = player.getDeltaMovement();
                player.setDeltaMovement(v.x, 0.2D * combinedFactor, v.z);
                player.fallDistance = 0;
            }
            return;
        }

        Level world = player.level();
        boolean isSmall = sm.isSmall || sm.isCrawling;

        // Aggregate 8-direction search results
        HandsClimbing handsClimbing = HandsClimbing.NONE;
        FeetClimbing  feetClimbing  = FeetClimbing.NONE;
        ClimbGap[] handsGap = {new ClimbGap()};
        ClimbGap[] feetGap  = {new ClimbGap()};

        // 4-direction (N/S/E/W) search
        HandsClimbing[] tempH = {HandsClimbing.NONE};
        FeetClimbing[]  tempF = {FeetClimbing.NONE};
        ClimbGap[] tempHG = {new ClimbGap()};
        ClimbGap[] tempFG = {new ClimbGap()};

        boolean[] _hv = {false}, _fv = {false};
        getOnLadderOrVine(player, world, isSmall, false, tempH, tempF, tempHG, tempFG, _hv, _fv);

        if (tempH[0].isRelevant()) {
            handsClimbing = handsClimbing.max(tempH[0], handsGap, tempHG[0]);
        }
        if (tempF[0].isRelevant()) {
            feetClimbing = feetClimbing.max(tempF[0], feetGap, tempFG[0]);
        }

        // 4 diagonal directions (NE/NW/SE/SW) search — omitted if isSmall (C-30, SmartMovingSelf.md line 745 condition)
        if (!isSmall) {
            int px = (int) Math.floor(player.getX());
            int py = (int) Math.floor(player.getY());
            int pz = (int) Math.floor(player.getZ());
            int[][] diags = {{1, 1}, {-1, 1}, {1, -1}, {-1, -1}};
            for (int[] d : diags) {
                for (int by = py; by <= py + 1; by++) {
                    BlockState state = world.getBlockState(new BlockPos(px + d[0], by, pz + d[1]));
                    boolean isHandsLevel = (by == py + 1);
                    if (state.getBlock() instanceof LadderBlock) {
                        Direction facing = state.getValue(LadderBlock.FACING);
                        boolean relevant = (d[0] > 0 && facing == Direction.WEST)
                                        || (d[0] < 0 && facing == Direction.EAST)
                                        || (d[1] > 0 && facing == Direction.NORTH)
                                        || (d[1] < 0 && facing == Direction.SOUTH);
                        if (relevant) {
                            ClimbGap gap = new ClimbGap(); gap.state = state;
                            if (isHandsLevel) handsClimbing = handsClimbing.max(HandsClimbing.UP, handsGap, gap);
                            else feetClimbing = feetClimbing.max(FeetClimbing.SLOW_UP_WITH_HOLD_WITHOUT_HANDS, feetGap, gap);
                        }
                    } else if (state.getBlock() instanceof VineBlock) {
                        // Original Orientation.hasVineOrientation(): search direction == vine face direction
                        // PZ(East) → East, NZ(West) → West, ZP(South) → South, ZN(North) → North
                        boolean hasFace = (d[0] > 0 && Boolean.TRUE.equals(state.getValue(VineBlock.EAST)))
                                       || (d[0] < 0 && Boolean.TRUE.equals(state.getValue(VineBlock.WEST)))
                                       || (d[1] > 0 && Boolean.TRUE.equals(state.getValue(VineBlock.SOUTH)))
                                       || (d[1] < 0 && Boolean.TRUE.equals(state.getValue(VineBlock.NORTH)));
                        // 🔴 Ladder/vine 1-1: Add solid block verification same as 4-direction search
                        //   (original SmartMovingBase L284: isRemoteSolid check). Vine requires solid wall to hang from.
                        if (hasFace) {
                            BlockPos solidPos = new BlockPos(px + d[0] * 2, by, pz + d[1] * 2);
                            BlockState solidState = world.getBlockState(solidPos);
                            if (solidState.isRedstoneConductor(world, solidPos)) {
                                ClimbGap gap = new ClimbGap(); gap.state = state;
                                if (isHandsLevel) {
                                    handsClimbing = handsClimbing.max(HandsClimbing.UP, handsGap, gap);
                                    // 🔴 Ladder/vine 1-1 BUG: diagonal vine flag update missing → add.
                                    //   isHandsVineClimbing only updated by 4-direction search, so when diagonal vine grabbed,
                                    //   isFacedToSolidVine / isVineAnyClimbing misidentify → vine jump climbing fails.
                                    sm.isHandsVineClimbing = true;
                                } else {
                                    feetClimbing = feetClimbing.max(FeetClimbing.SLOW_UP_WITH_HOLD_WITHOUT_HANDS, feetGap, gap);
                                    sm.isFeetVineClimbing = true;
                                }
                            }
                        }
                    }
                }
            }
        }

        // B-19a4 (Session 108): 8-direction search based on Orientation.seekClimbGap → assign 5 ClientState fields.
        // Ported from original SmartMovingSelf L926-L961 (Free Climb preferClimb block).
        // **B-19 domino chain completed** — B-17 (isCrawlClimbing) / B-18 (isClimbCrawling) /
        // B-16c (wouldWantClimb 4-OR) formula now evaluates based on actual values.
        // Existing getOnLadderOrVine result variables (handsClimbing/feetClimbing) continue to be used for speed determination
        // — this block only adds field assignment (minimal behavioral impact).
        {
            double id = player.getX();
            double jd = player.getBoundingBox().minY;
            double kd = player.getZ();
            int ix = (int) Math.floor(id);
            int iz = (int) Math.floor(kd);

            // Original L922-L925: normalize rotation (0 ~ 360F)
            float rotation = player.getYRot() % 360F;
            if (rotation < 0) rotation += 360F;

            // Original L918: isSmallClimbing = isCrawling || isSliding
            boolean isSmallClimbing = sm.isCrawling || sm.isSliding;

            // Original L919-L920: `isClimbCrawling || isCrawlClimbing || isSmallClimbing → jd += -1D`
            //   ★ Adjust jd itself. jh depends on jd, so it's automatically adjusted by -2 (= jh = (jd-1)*2+1 = jh_orig-2).
            // 🔴 jd missing fix (user report — crawl climbing interrupted, 2026-05-03):
            //   Previous mapping = `jh += -2D` only (jd adjustment missing). seekClimbGap argument jd passed
            //   without adjustment → grip check box foot position wrong → handsClimbing=NONE → isClimbing=false →
            //   isCrawlClimbing formula cleared + ICC EXIT entity.y +1m → player POV jump (= interruption).
            //   Original 1:1 = adjust jd itself → seekClimbGap arguments jd / jh both accurate.
            if (sm.isClimbCrawling || sm.isCrawlClimbing || isSmallClimbing) {
                jd += -1D;
            }
            double jh = jd * 2D + 1;

            // 🔴 (2026-04-27) Original SmartMovingSelf L928-961 precise 1:1 mapping:
            //   Original: main handsClimbing/feetClimbing and inout variables are identical (separate NONE start,
            //   then assign to inout[0]). We start inout only as NONE → 8-direction results not accumulated in main
            //   → setShouldClimbSpeed decision cannot recognize general walls → user report "grab key stuck".
            //   Solution: initialize inout start value as main (getOnLadderOrVine result) + update main after 8-direction search.
            HandsClimbing[] inoutH = { handsClimbing };
            FeetClimbing[]  inoutF = { feetClimbing };
            ClimbGap outHandsGap = handsGap[0];
            ClimbGap outFeetGap  = feetGap[0];

            // Original L937-L940: call seekClimbGap in 4 directions (PZ/NZ/ZP/ZN)
            Orientation.PZ.seekClimbGap(rotation, world, ix, id, jh, iz, kd,
                    sm.isClimbCrawling, sm.isCrawlClimbing, isSmallClimbing,
                    inoutH, inoutF, outHandsGap, outFeetGap);
            Orientation.NZ.seekClimbGap(rotation, world, ix, id, jh, iz, kd,
                    sm.isClimbCrawling, sm.isCrawlClimbing, isSmallClimbing,
                    inoutH, inoutF, outHandsGap, outFeetGap);
            Orientation.ZP.seekClimbGap(rotation, world, ix, id, jh, iz, kd,
                    sm.isClimbCrawling, sm.isCrawlClimbing, isSmallClimbing,
                    inoutH, inoutF, outHandsGap, outFeetGap);
            Orientation.ZN.seekClimbGap(rotation, world, ix, id, jh, iz, kd,
                    sm.isClimbCrawling, sm.isCrawlClimbing, isSmallClimbing,
                    inoutH, inoutF, outHandsGap, outFeetGap);

            // Original L942-L943: retrieve main variables after 4-direction search
            handsClimbing = inoutH[0];
            feetClimbing  = inoutF[0];
            handsGap[0]   = outHandsGap;
            feetGap[0]    = outFeetGap;

            // Original L945-L947: 4-direction results → assign 3 ClientState fields
            sm.isNeighborClimbing = handsClimbing.isRelevant() || feetClimbing.isRelevant();
            sm.hasNeighborClimbGap = outHandsGap.canStand || outFeetGap.canStand;
            sm.hasNeighborClimbCrawlGap = outHandsGap.mustCrawl || outFeetGap.mustCrawl;

            // Original L949-L955: when not isSmallClimbing, perform additional diagonal 4-direction search (PP/NP/NN/PN)
            if (!isSmallClimbing) {
                Orientation.PP.seekClimbGap(rotation, world, ix, id, jh, iz, kd,
                        sm.isClimbCrawling, sm.isCrawlClimbing, isSmallClimbing,
                        inoutH, inoutF, outHandsGap, outFeetGap);
                Orientation.NP.seekClimbGap(rotation, world, ix, id, jh, iz, kd,
                        sm.isClimbCrawling, sm.isCrawlClimbing, isSmallClimbing,
                        inoutH, inoutF, outHandsGap, outFeetGap);
                Orientation.NN.seekClimbGap(rotation, world, ix, id, jh, iz, kd,
                        sm.isClimbCrawling, sm.isCrawlClimbing, isSmallClimbing,
                        inoutH, inoutF, outHandsGap, outFeetGap);
                Orientation.PN.seekClimbGap(rotation, world, ix, id, jh, iz, kd,
                        sm.isClimbCrawling, sm.isCrawlClimbing, isSmallClimbing,
                        inoutH, inoutF, outHandsGap, outFeetGap);
            }

            // Original L957-L958: retrieve main variables after 8-direction search
            handsClimbing = inoutH[0];
            feetClimbing  = inoutF[0];
            handsGap[0]   = outHandsGap;
            feetGap[0]    = outFeetGap;

            // Original L960-L961: 8-direction combined results → assign 2 ClientState fields
            sm.hasClimbGap = outHandsGap.canStand || outFeetGap.canStand;
            sm.hasClimbCrawlGap = outHandsGap.mustCrawl || outFeetGap.mustCrawl;
            // 🔴 fix #79 (BUG #4, 2026-05-11, user report "ladder/vine grab entry range = auto entry range"):
            //   Auto entry (isFacedToLadder/Vine) = `getOnLadderOrVine(faceOnly=true)` = player position only.
            //     (adjacent check disabled for 1:1 vanilla behavior, L297-305 memo.)
            //   Grab entry = handleClimbing 8-direction seekClimbGap → inside hasHalfHold/hasBottomHold
            //     remoteLadderClimbing/baseVineClimbing/remoteVineClimbing → **adjacent 1-block ladder/vine match**.
            //   → grab entry range = auto entry range + adjacent 1 block (user report "1 block further ahead").
            //
            //   User intent: grab entry range for ladder/vine = match auto entry range.
            //   (general solid = half-block/stair/wall etc. grab climbing is separate, maintain 8-direction adjacent match.)
            //
            //   Solution: when grip-matched block in 8-direction results is ladder/vine + facedLadder/Vine=false
            //     → invalidate results (handsClimbing/feetClimbing/Gap/vine flag reset).
            //     → general solid match (block is not ladder/vine) remains = no regression.
            //
            //   Dump validation ([BUG4-DBG]): in BUG scenario (Phase C/D, px=73 pz=-29/-28)
            //     directly confirmed `facedLadder=false facedVine=false hEdge/fEdge=ladder/vine`.
            boolean _facedLadderOrVine = isFacedToLadder(player, sm.isClimbCrawling)
                                       || isFacedToSolidVine(player, sm.isClimbCrawling);
            if (!_facedLadderOrVine) {
                boolean _handsLV = handsGap[0].state != null
                        && (handsGap[0].state.getBlock() instanceof LadderBlock
                         || handsGap[0].state.getBlock() instanceof VineBlock);
                boolean _feetLV = feetGap[0].state != null
                        && (feetGap[0].state.getBlock() instanceof LadderBlock
                         || feetGap[0].state.getBlock() instanceof VineBlock);
                if (_handsLV) {
                    handsClimbing = HandsClimbing.NONE;
                    handsGap[0] = new ClimbGap();
                    sm.isHandsVineClimbing = false;
                }
                if (_feetLV) {
                    feetClimbing = FeetClimbing.NONE;
                    feetGap[0] = new ClimbGap();
                    sm.isFeetVineClimbing = false;
                }
                // hasClimbGap / hasNeighborClimbing / hasNeighborClimbGap 도 재계산 (위 reset 반영).
                if (_handsLV || _feetLV) {
                    sm.isNeighborClimbing = handsClimbing.isRelevant() || feetClimbing.isRelevant();
                    sm.hasClimbGap = handsGap[0].canStand || feetGap[0].canStand;
                    sm.hasClimbCrawlGap = handsGap[0].mustCrawl || feetGap[0].mustCrawl;
                    sm.hasNeighborClimbGap = sm.hasClimbGap;
                    sm.hasNeighborClimbCrawlGap = sm.hasClimbCrawlGap;
                }
            }
        }

        // **Focus #3 B-7 (Session 6)**: port 2 missing branches from original L963-L976 (★ #3 credits found).
        // Original SmartMovingSelf L963-L976 — adjust climbing state after 8-direction search.
        {
            int ix2 = (int) Math.floor(player.getX());
            int jd2 = (int) Math.floor(player.getBoundingBox().minY);
            int iz2 = (int) Math.floor(player.getZ());

            // Original L963-L970: BottomHold + ladder 2 blocks above + non-solid 2 blocks to side → release handsClimbing
            //   if (handsClimbing == BottomHold && Orientation.isLadder(world.getBlock(i, j+2, k))) {
            //     ladderOrientation = getKnownLadderOrientation(...);
            //     remote_i = i + ladderOrientation._i; remote_k = k + ladderOrientation._k;
            //     if (!world.getBlock(remote_i, j, remote_k).isSolid()
            //         && !world.getBlock(remote_i, j+1, remote_k).isSolid())
            //         handsClimbing = None;
            //   }
            // RedPower wire / multi-layer ladder special case. 1.21.1 isSolid mapping = state.isSolidBlock().
            if (handsClimbing == HandsClimbing.BOTTOM_HOLD
                    && Orientation.isLadder(world.getBlockState(new BlockPos(ix2, jd2 + 2, iz2)))) {
                Orientation ladderOrientation = Orientation.getKnownLadderOrientation(
                        world, ix2, jd2 + 2, iz2);
                if (ladderOrientation != null) {
                    int remoteI = ix2 + ladderOrientation.getOffsetI();
                    int remoteK = iz2 + ladderOrientation.getOffsetK();
                    BlockPos pos0 = new BlockPos(remoteI, jd2, remoteK);
                    BlockPos pos1 = new BlockPos(remoteI, jd2 + 1, remoteK);
                    BlockState s0 = world.getBlockState(pos0);
                    BlockState s1 = world.getBlockState(pos1);
                    if (!s0.isRedstoneConductor(world, pos0) && !s1.isRedstoneConductor(world, pos1)) {
                        handsClimbing = HandsClimbing.NONE;
                    }
                }
            }

            // Original L972-L976: !grabPressed && handsClimbing==Up && feetClimbing==None →
            //   release handsClimbing when surrounding air + !horizontalCollision (prevent sticky climbing).
            if (!SmartMovingKeys.grab.isDown()
                    && handsClimbing == HandsClimbing.UP
                    && feetClimbing == FeetClimbing.NONE) {
                if (!player.horizontalCollision
                        && world.isEmptyBlock(new BlockPos(ix2, jd2, iz2))
                        && world.isEmptyBlock(new BlockPos(ix2, jd2 + 1, iz2))) {
                    handsClimbing = HandsClimbing.NONE;
                }
            }
        }

        // Do not process if there is no climbable surface
        if (!handsClimbing.isRelevant() && !feetClimbing.isRelevant()) {
            return;
        }

        // B-1 (세션 25): Mover.getCombinedSpeedFactor 단일 헬퍼로 교체.
        double combinedFactor = SmartMovingMover.getCombinedSpeedFactor(player, cfg);

        // **B-20b 해소 (세션 132)**: Simple Base Climb 원본 L825-L844 전면 정밀 재작성.
        //   원본: if (isSimpleBaseClimb && isCollidedHorizontally && isOnLadderOrVine) {
        //     feet = isClimbable(i, j, k); hands = isClimbable(i, j+1, k);
        //     feet&&hands → Fast / feet → Fast / hands → Slow / else → 0
        //     motionY *= combinedFactor
        //   }
        //   isOnLadderOrVine 은 상위 `onClimbable` 에 내포 (B-42-B20 확인). 남은 조건
        //   `isCollidedHorizontally` 복원 + feet/hands isClimbable 정밀 판정.
        if (cfg.simpleClimb) {
            // 🔴 사다리/덩굴 1-4: 원본 SmartMovingSelf L825-844 Simple Base Climb 정밀 1:1.
            //   원본은 motionY 직접 set (relevant 가드 없음, sm.isClimbing 갱신 없음).
            if (player.horizontalCollision) {
                int i = (int) Math.floor(player.getX());
                int j = (int) Math.floor(player.getBoundingBox().minY);
                int k = (int) Math.floor(player.getZ());
                boolean feet  = Orientation.isClimbable(world, i, j, k);
                boolean hands = Orientation.isClimbable(world, i, j + 1, k);
                double motionY;
                if (feet && hands)      motionY = FAST_UP_MOTION;
                else if (feet)          motionY = FAST_UP_MOTION;
                else if (hands)         motionY = SLOW_UP_MOTION;
                else                    motionY = 0.0D;
                motionY *= combinedFactor;
                Vec3 v = player.getDeltaMovement();
                player.setDeltaMovement(v.x, motionY, v.z);
                player.fallDistance = 0;
            }
            return;
        }

        // **B-20c 해소 (세션 132)**: Smart Base Climb 원본 L856-L894 전면 정밀 재작성.
        //   원본: if (isSmartBaseClimb && isOnLadderOrVine && isCollidedHorizontally) {
        //     feet = isClimbable(i, j, k); hands = isClimbable(i, j+1, k);
        //     feet&&hands → Fast
        //     feet only → handsSubstitute (PZ/NZ/ZP/ZN at j+1) ? Fast : Slow
        //     hands only → feetSubstitute (ZZ/PZ/NZ/ZP/ZN at j) ? Fast : Slow
        //     else → 0
        //     motionY *= combinedFactor
        //   }
        //   handsSubstitute 는 원본 L866-L869: PZ/NZ/ZP/ZN 4방향 (ZZ 없음).
        //   feetSubstitute 는 원본 L879-L883: ZZ/PZ/NZ/ZP/ZN 5방향.
        if (cfg.smartClimb) {
            // 🔴 사다리/덩굴 1-4: 원본 SmartMovingSelf L856-L894 Smart Base Climb 정밀 1:1.
            //   원본은 motionY 직접 set (relevant 가드 없음, sm.isClimbing 갱신 없음).
            if (player.horizontalCollision) {
                int i = (int) Math.floor(player.getX());
                int j = (int) Math.floor(player.getBoundingBox().minY);
                int k = (int) Math.floor(player.getZ());
                boolean feet  = Orientation.isClimbable(world, i, j, k);
                boolean hands = Orientation.isClimbable(world, i, j + 1, k);
                double motionY;
                if (feet && hands) {
                    motionY = FAST_UP_MOTION;
                } else if (feet) {
                    // 원본 L866-L869: 4방향 (PZ/NZ/ZP/ZN) at j+1
                    boolean handsSubstitute =
                            Orientation.PZ.isHandsLadderSubstitute(world, i, j + 1, k)
                         || Orientation.NZ.isHandsLadderSubstitute(world, i, j + 1, k)
                         || Orientation.ZP.isHandsLadderSubstitute(world, i, j + 1, k)
                         || Orientation.ZN.isHandsLadderSubstitute(world, i, j + 1, k);
                    motionY = handsSubstitute ? FAST_UP_MOTION : SLOW_UP_MOTION;
                } else if (hands) {
                    // 원본 L879-L883: 5방향 (ZZ/PZ/NZ/ZP/ZN) at j
                    boolean feetSubstitute =
                            Orientation.ZZ.isFeetLadderSubstitute(world, i, j, k)
                         || Orientation.PZ.isFeetLadderSubstitute(world, i, j, k)
                         || Orientation.NZ.isFeetLadderSubstitute(world, i, j, k)
                         || Orientation.ZP.isFeetLadderSubstitute(world, i, j, k)
                         || Orientation.ZN.isFeetLadderSubstitute(world, i, j, k);
                    motionY = feetSubstitute ? FAST_UP_MOTION : SLOW_UP_MOTION;
                } else {
                    motionY = 0.0D;
                }
                motionY *= combinedFactor;
                Vec3 v = player.getDeltaMovement();
                player.setDeltaMovement(v.x, motionY, v.z);
                player.fallDistance = 0;
            }
            return;
        }

        // 🔴 Ladder/vine 1-1 BUG: correct wantClimb / wantClimbUp / wantClimbDown decision.
        //   Previous: `wantClimb = SmartMovingKeys.grab.isPressed()` — only grab key checked.
        //   Original (SmartMovingSelf L2479-2500):
        //     wantClimb = isFreeClimbingEnabled && wouldWantClimb;
        //     wouldWantClimb = (grab || (climbHolding && sneak) || (autoLadder && faced)
        //                      || (autoVine && facedSolid)) && ...;
        //     wantClimbUp = (wantClimb && forward>0) || (vine + jump mechanism);
        //     wantClimbDown = wantClimb && forward<=0 && !wantCrawl;
        //   → auto entry active (auto ladder/vine branch) when facing ladder/vine without grab key.
        //   SmartMovingClientState.tickEssential updates sm.wantClimb / sm.wantClimbUp /
        //   sm.wantClimbDown every tick (L1262, L1289-1295) — includes all 4-OR + vine jump.
        //   Use as-is → auto entry works normally.
        boolean wantClimbUp   = sm.wantClimbUp;
        boolean wantClimbDown = sm.wantClimbDown;

        // 진입 게이트: 둘 다 false 시 등반 분기 진입 안 함 (원본 동등).
        if (!wantClimbUp && !wantClimbDown) {
            return;
        }

        // 🔴 (BUG 1+2 fix, user report): original SmartMovingSelf L979 guard 1:1 port.
        //   Original flow: wantClimbUp/Down branch in handleClimbing is inside `if (feetClimbing.IsRelevant()
        //     || handsClimbing.IsRelevant())`. On flat ground (NONE+NONE), branch not entered
        //     → setShouldClimbSpeed not called → isClimbing not changed.
        //   1.21.1 mapping missing → branch entered even on flat ground → fallback HOLD_MOTION → setOnlyShouldClimbSpeed
        //     → isClimbing=true → canCrawl `!isClimbing` guard false → isCrawling=false → crawl released.
        //   User report: shift+grab+W causes crawl ↔ stand vibration + W while crawled causes release directly.
        //   Solution: flat ground NONE+NONE guard → immediate return (handleClimbBackJump / handleCrash etc handled above
        //     — this guard only blocks motion/isClimbing set branch entry).
        if (!feetClimbing.isRelevant() && !handsClimbing.isRelevant()) {
            return;
        }

        double value = 0D;
        boolean isUp = true;
        // 🔴 (Session 145 BUG fix): track wantClimbUp/Down branch sub-branch matching.
        //   Original SmartMovingSelf L981-L1027 if-else chain: if no sub-branch matches,
        //   `setShouldClimbSpeed` call never happens (= isClimbing not set).
        //   Previous mapping forcibly called with fallback HOLD_MOTION → on flat ground crawl (feet=BaseWithHands
        //   forced promotion, hands=NONE) isClimbing=true → canCrawl=false → crawl release BUG.
        //   Solution: skip setOnlyShouldClimbSpeed if no matching branch (original 1:1).
        boolean matched = false;

        // 🔴 Original setShouldClimbSpeed (handsClimbType, feetClimbType) mapping variables.
        //   Original domain: hands={NoGrab=0, UpGrab=1, MiddleGrab=2}, feet={NoStep=0, DownStep=1}.
        //   Original setShouldClimbSpeed(value) 1-arg default = (UpGrab=1, DownStep=1).
        //   Only explicit branch calls differ. Below if-else chain is original SmartMovingSelf L991-L1052 1:1.
        int handsAnim = 1;  // default UpGrab
        int feetAnim  = 1;  // default DownStep

        if (wantClimbUp) {
            // 🔴 (2026-04-27) Original SmartMovingSelf L981-1027 precise 1:1 mapping.

            // Original L983-987: grab + forward while sliding → transition to crawl.
            if (sm.isSliding && handsClimbing.isRelevant()) {
                sm.isSliding  = false;
                sm.isCrawling = true;
            }

            // Original L989: handsClimbing = handsClimbing.ToUp(); (BottomHold → Up transition)
            handsClimbing = handsClimbing.toUp();

            // Original L991-995: feetClimbing.FastUp + special condition → fast climb.
            //   Original condition: !(handsClimbing == None && onGround && feetClimbGap.Block != bed)
            //   1.21.1 mapping: bed check omitted (approximation).
            boolean handsNoneOnGround = (handsClimbing == HandsClimbing.NONE) && player.onGround();
            if (feetClimbing == FeetClimbing.FAST_UP && !handsNoneOnGround) {
                value = FAST_UP_MOTION; isUp = true;
                handsAnim = 0; feetAnim = 1;  // Original L994: NoGrab + DownStep
                matched = true;
            }
            // ★ Original L996-1000: hasClimbGap || hasClimbCrawlGap + handsClimbing.FastUp +
            //   feetClimbing(None or BaseWithHands) → climb into crawl gap.
            //   = auto climb branch when gap above 2-block wall. User report "2-block wall won't climb" direct cause.
            else if ((sm.hasClimbGap || sm.hasClimbCrawlGap)
                    && handsClimbing == HandsClimbing.FAST_UP
                    && (feetClimbing == FeetClimbing.NONE
                            || feetClimbing == FeetClimbing.BASE_WITH_HANDS)) {
                value = (feetClimbing == FeetClimbing.NONE) ? SLOW_UP_MOTION : FAST_UP_MOTION;
                isUp = true;
                handsAnim = 2; feetAnim = 1;  // 원본 L999: MiddleGrab + DownStep
                matched = true;
            }
            // 원본 L1001-1005: feet.IsRelevant && hands.IsRelevant + 3 예외 조합 → MediumUp.
            else if (feetClimbing.isRelevant() && handsClimbing.isRelevant()
                    && !(feetClimbing == FeetClimbing.BASE_HOLD && handsClimbing == HandsClimbing.SINK)
                    && !(handsClimbing == HandsClimbing.SINK && feetClimbing == FeetClimbing.TOP_WITH_HANDS)
                    && !(handsClimbing == HandsClimbing.TOP_HOLD && feetClimbing == FeetClimbing.TOP_WITH_HANDS)) {
                value = MEDIUM_UP_MOTION; isUp = true;
                // 원본 L1004: (hasGap || hasCrawlGap) && !(Sink && BaseWithHands) ? MiddleGrab : UpGrab.
                boolean useMiddle = (sm.hasClimbGap || sm.hasClimbCrawlGap)
                        && !(handsClimbing == HandsClimbing.SINK
                                && feetClimbing == FeetClimbing.BASE_WITH_HANDS);
                handsAnim = useMiddle ? 2 : 1;
                feetAnim  = 1;
                matched = true;
            }
            // 원본 L1006-1010: handsClimbing.IsUp() → SlowUpMotion (default 1-arg = UpGrab+DownStep).
            else if (handsClimbing.isUp()) {
                value = SLOW_UP_MOTION; isUp = true;
                handsAnim = 1; feetAnim = 1;
                matched = true;
            }
            // 원본 L1011-1021: TopHold || BaseHold || (SlowUpWithHoldWithoutHands && hands None) → Hold.
            //   원본 L1016-1017 특수 조합 → MiddleGrab+DownStep 명시.
            //   else (L1019) → default UpGrab+DownStep.
            else if (handsClimbing == HandsClimbing.TOP_HOLD
                    || feetClimbing == FeetClimbing.BASE_HOLD
                    || (feetClimbing == FeetClimbing.SLOW_UP_WITH_HOLD_WITHOUT_HANDS
                            && handsClimbing == HandsClimbing.NONE)) {
                value = HOLD_MOTION; isUp = true;
                boolean special = (handsClimbing == HandsClimbing.SINK
                                && feetClimbing == FeetClimbing.BASE_HOLD)
                        || (handsClimbing == HandsClimbing.TOP_HOLD
                                && feetClimbing == FeetClimbing.TOP_WITH_HANDS);
                handsAnim = special ? 2 : 1;
                feetAnim  = 1;
                matched = true;
            }
            // 원본 L1022-1026: Sink || (SlowUpWithSinkWithoutHands && hands None) → SinkDown (default).
            else if (handsClimbing == HandsClimbing.SINK
                    || (feetClimbing == FeetClimbing.SLOW_UP_WITH_SINK_WITHOUT_HANDS
                            && handsClimbing == HandsClimbing.NONE)) {
                value = SINK_DOWN_MOTION; isUp = false;
                handsAnim = 1; feetAnim = 1;
                matched = true;
            }
            // 🔴 (세션 145 BUG fix): 어떤 sub-branch 도 매치 안 되면 setShouldClimbSpeed skip.
            //   원본 L981-L1027 1:1 — fallback 없음. matched=false 유지 → L955 skip.
            //   평지 크롤 (feet=BaseWithHands 강제 승격, hands=NONE) 시 위 5개 분기 모두 미매치
            //   → matched=false → isClimbing 안 set → canCrawl=true 유지 → 크롤 지속.
        } else {
            // 원본 L1028-1053 wantClimbDown 분기 매핑.
            handsClimbing = handsClimbing.toDown();

            if (handsClimbing == HandsClimbing.BOTTOM_HOLD && !feetClimbing.isIndependentlyRelevant()) {
                // 원본 L1035: setShouldClimbSpeed(HoldMotion) default = UpGrab+DownStep.
                value = HOLD_MOTION; isUp = false;
                handsAnim = 1; feetAnim = 1;
                matched = true;
            } else if (handsClimbing.isRelevant()) {
                if (feetClimbing == FeetClimbing.FAST_UP) {
                    // 원본 L1041: ClimbDown + NoGrab + DownStep
                    value = CLIMB_DOWN_MOTION; isUp = false;
                    handsAnim = 0; feetAnim = 1;
                } else if (feetClimbing == FeetClimbing.SLOW_UP_WITH_HOLD_WITHOUT_HANDS) {
                    // 원본 L1043: default
                    value = CLIMB_DOWN_MOTION; isUp = false;
                    handsAnim = 1; feetAnim = 1;
                } else if (feetClimbing == FeetClimbing.TOP_WITH_HANDS) {
                    // 원본 L1045: default
                    value = CLIMB_DOWN_MOTION; isUp = false;
                    handsAnim = 1; feetAnim = 1;
                } else if (feetClimbing == FeetClimbing.BASE_WITH_HANDS
                        || feetClimbing == FeetClimbing.BASE_HOLD) {
                    // 원본 L1046-1050: 양 분기 모두 default
                    if ((handsClimbing != HandsClimbing.NONE && handsClimbing != HandsClimbing.UP)
                            || (handsClimbing == HandsClimbing.UP && feetClimbing == FeetClimbing.BASE_HOLD)) {
                        value = CLIMB_DOWN_MOTION; isUp = false;
                    } else {
                        value = SINK_DOWN_MOTION; isUp = false;
                    }
                    handsAnim = 1; feetAnim = 1;
                } else {
                    // 원본 L1052: SinkDown + (handsFastUp ? MiddleGrab : UpGrab) + NoStep
                    value = SINK_DOWN_MOTION; isUp = false;
                    handsAnim = (handsClimbing == HandsClimbing.FAST_UP) ? 2 : 1;
                    feetAnim  = 0;
                }
                matched = true;
            }
            // 🔴 (세션 145 BUG fix): hands.isRelevant() = false 시 setShouldClimbSpeed skip
            //   (원본 L1037 `else if (handsClimbing.IsRelevant())` 블록만 분기 있음 → matched 유지 false).

            // 🔴 원본 L1055-1058 isClimbHolding HoldMotion 분기 매핑 (sneak 키 가드).
            //   원본 isClimbHolding = wantClimb && (sneak || crawlToggled) && isClimbing.
            //
            // 🔴 사용자 요구 (handsClimbing.SINK 통합) — grab 키 가드 추가:
            //   "iron_bars 위 잡고 그랩만 누르면 안떨어져야됨".
            //   원본 동작:
            //     일반 블록 grab → handsClimbing=BottomHold → wantClimbDown 분기 BottomHold +
            //                   !feet → HoldMotion (자동 hold).
            //     iron_bars/fence 위 잡기 → handsClimbing=Sink/TopHold → ToDown: TopHold→Sink,
            //                              Sink→Sink → SinkDownMotion (떨어짐).
            //   사용자 의도 = grab만으로 iron_bars hold → SINK 시 HoldMotion 강제.
            //
            //   🔴 부작용 정정 (사용자 보고: 사다리 끝 도달 시 자동 hold).
            //   사다리 끝 위 블록 (예: stone) 의 8방향 substitute 검사 결과 handsClimbing=SINK
            //   매핑 → SINK 무조건 가드가 grab 안 누른 케이스에도 HoldMotion 발동 → 떨어지지
            //   않고 그 자리 hold (사용자 보고).
            //   해결: SINK 가드에 `grabPressed` 추가 — grab 키 누른 케이스만 hold (iron_bars
            //   의도 유지) + 사다리 끝 grab 안 누름 케이스는 SINK_DOWN_MOTION 정상 적용 (천천히 내려옴).
            boolean grabPressed_holdGuard = SmartMovingKeys.grab.isDown();
            if (sm.isClimbHolding || (handsClimbing == HandsClimbing.SINK && grabPressed_holdGuard)) {
                value = HOLD_MOTION; isUp = true;
                matched = true;
            }

            // 🔴 사용자 요구 추가 (handsEdgeBlock/feetEdgeBlock = fence/iron_bars):
            //   handsClimbing 매핑이 SINK 가 아닌 다른 enum (TopHold/Up/None 등) 이어도
            //   잡은 블록이 fence/iron_bars 면 grab만으로 hold. 사용자 명시 의도 — 일반
            //   블록 grab hold 와 동일 동작.
            //   handsEdgeBlock/feetEdgeBlock 은 ClimbGap.state → 클라이밍 진입 시 잡은
            //   실제 블록 (BlockState). null 가드 필수.
            // 🔴 grab 키 가드 추가 (위 SINK 분기와 동일 이유): 사용자가 grab 안 누른 케이스에
            //   사다리 끝 우발적 fence/iron_bars 매핑되어도 자동 hold 차단.
            boolean grabbedFenceOrBars = grabPressed_holdGuard && (
                    (sm.handsEdgeBlock != null
                            && (sm.handsEdgeBlock.getBlock() == net.minecraft.world.level.block.Blocks.IRON_BARS
                                || Orientation.isFence(sm.handsEdgeBlock)))
                 || (sm.feetEdgeBlock != null
                            && (sm.feetEdgeBlock.getBlock() == net.minecraft.world.level.block.Blocks.IRON_BARS
                                || Orientation.isFence(sm.feetEdgeBlock))));
            if (grabbedFenceOrBars) {
                value = HOLD_MOTION; isUp = true;
                matched = true;
            }
        }

        // 🔴 (세션 145 BUG fix): matched=false (어떤 sub-branch 도 매치 안 됨) 시 원본 1:1 —
        //   setShouldClimbSpeed 호출 자체 skip. isClimbing 안 set + actualHandsClimbType /
        //   actualFeetClimbType 갱신 안 함. 평지 크롤 풀림 BUG 직접 fix.
        if (!matched) {
            return;
        }

        // 원본 L1522 factor = getCombinedSpeedFactor() + L1523-1524 isFast sprint factor.
        double freeFactor = SmartMovingMover.getCombinedSpeedFactor(player, cfg);
        if (sm.isFast) {
            freeFactor *= cfg.sprintFactor;
        }
        setOnlyShouldClimbSpeed(player, sm, value, isUp, freeFactor);

        // 🔴 원본 setShouldClimbSpeed 분기별 매핑 적용 (사용자 보고 2회차 후, 단계별 애니메이션 1:1).
        //   원본 도메인: hands={NoGrab=0, UpGrab=1, MiddleGrab=2}, feet={NoStep=0, DownStep=1}.
        //   sm_animateClimbing 의 분기도 같은 0/1/2 도메인으로 변경 (이전 ordinal 0~5 매핑 폐기).

        // 🔴 원본 SmartMovingSelf L1083-L1094 보정 매핑 (사용자 보고 — 진입 직후 다리 1자):
        //   `if (handsClimbing == None) actualHands=NoGrab; else if (feetClimbing == None) actualFeet=NoStep;`
        //   setShouldClimbSpeed 분기별 set 후 추가 보정 — 인스턴스가 None 인 케이스만 강제 0 set.
        //   진입 직후 발 공중 (feetClimbing=NONE) 시점 → actualFeet=NoStep 유지 →
        //   feetDistanceUpFactor=0 / feetDistanceSideFactor=0 → leg.pitch=0, leg.roll=0 → 다리 1자.
        //   발이 벽에 닿으면 (feetClimbing 인스턴스 변경) 보정 미트리거 → actualFeet=DownStep 유지.
        //   사용자 보고 "중간까지 다리 1자" 정확 일치.
        if (handsClimbing == HandsClimbing.NONE) {
            handsAnim = 0;  // NoGrab 강제
        } else if (feetClimbing == FeetClimbing.NONE) {
            feetAnim = 0;   // NoStep 강제 (else if 라 hands.None 시 feet 보정 안 함 — 원본 1:1)
        }

        sm.actualHandsClimbType = handsAnim;
        sm.actualFeetClimbType  = feetAnim;

        // fallDistance 리셋 (클라이밍 중 낙하 데미지 방지)
        player.fallDistance = 0;

        // C-33: 클라이밍 중 exhaustion 증가 (피로 누적, 매 틱 1F 감소보다 큰 값)
        if (sm.isClimbing && cfg.climbExhaustion) {
            sm.exhaustion += 2.0F;
        }

        // C-32: climbBackJump — isClimbHolding 상태에서 jump 시 뒤로 점프
        handleClimbBackJump(player, sm);

        // C-32: handleCrash — 자유 클라이밍 낙하 데미지
        if (sm.isClimbing) handleCrash(player, cfg.freeClimbFallDamageStartDistance, cfg.freeClimbFallDamageFactor);
    }

    /**
     * Smart 모드: 주어진 Y 레벨에서 인접 블록에 사다리/넝쿨이 있는지 확인한다.
     * 원본: SmartMovingSelf isHandsLadderSubstitute / isFeetLadderSubstitute 판정.
     * includeCenter=true → 현재 위치(ZZ)도 포함 (feetSubstitute 용).
     */
    private static boolean hasSubstituteLadderOrVine(Level world, int px, int py, int pz, boolean includeCenter) {
        if (includeCenter) {
            BlockState s = world.getBlockState(new BlockPos(px, py, pz));
            if (s.getBlock() instanceof LadderBlock || s.getBlock() instanceof VineBlock) return true;
        }
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            BlockState s = world.getBlockState(new BlockPos(px + dir.getStepX(), py, pz + dir.getStepZ()));
            if (s.getBlock() instanceof LadderBlock || s.getBlock() instanceof VineBlock) return true;
        }
        return false;
    }

    /**
     * 클라이밍 중 뒤로 점프 (climbBackJump).
     * 원본: SmartMovingSelf.handleClimbing() isClimbHolding && jumpButton.StartPressed 분기.
     *
     * jumpAngle = rotationYaw + 180F (정면 반대 방향).
     * cfg.climbJumpBackHead=false(기본): grab 미입력 → HEAD 점프, grab 입력 → 일반 뒤로 점프.
     * continueWallJumping = !isHeadJumping — 뒤로 점프 후 연속 벽 점프 허용.
     */
    private static void handleClimbBackJump(LocalPlayer player, SmartMovingClientState sm) {
        // Phase H BUG-4 1:1 정정: 원본 L1060 `jumpButton.StartPressed` (점프 키 rising-edge).
        //   기존 sm.jumpPending 은 sm_jump 인터셉트 시 set 되는 매 점프 시도 플래그 — 의미 다름.
        //   1.21.1 측 sm.jumpKeyStartPressed (L1176 set: curJumpPressed && !prevJumpKeyPressed) 가 정확 매핑.
        if (!sm.isClimbHolding || !sm.jumpKeyStartPressed) return;

        SmartMovingConfig cfg = SmartMovingConfig.Config;
        boolean grabPressed = SmartMovingKeys.grab.isDown();

        // Phase H BUG-2 1:1 정정: 원본 L1062 `handsOnly = feetClimbing != FeetClimbing.None`.
        //   ★ 변수명 함정 (원본 그대로 보존): handsOnly==true 면 feet 도 등반 중 → 일반 ClimbBackHead/Up.
        //                                       handsOnly==false 면 feet 안 등반 → ClimbBackHead/UpHandsOnly.
        boolean handsOnly = sm.actualFeetClimbType != FeetClimbing.NONE.ordinal();

        // Phase H BUG-1 1:1 정정: 원본 L1064-L1066 3중 ternary type 결정.
        //   기존: 모든 분기 inline 동일 식 (vanilla 0.42 + 0.3 push). type 별 vertical/horizontal
        //         factor 적용 누락 (climbBackUp/HeadJumpVerticalFactor 등). tryJump 호출로 정정.
        boolean useHead = cfg.climbJumpBackHead ? grabPressed : !grabPressed;
        int type;
        if (useHead) {
            type = handsOnly ? SmartMovingJumper.CLIMB_BACK_HEAD
                             : SmartMovingJumper.CLIMB_BACK_HEAD_HANDS_ONLY;
        } else {
            type = handsOnly ? SmartMovingJumper.CLIMB_BACK
                             : SmartMovingJumper.CLIMB_BACK_HANDS_ONLY;
        }

        float jumpAngle = player.getYRot() + 180F;

        // Phase H BUG-3 1:1 정정: 원본 L1069 `if (tryJump(type, null, null, jumpAngle))` + 후처리.
        //   tryJump 가 D-15 head 분기 시 isHeadJumping/setPoseSmall/heightOffset 처리 + D-11 angle
        //   분기 시 motion 계산 (factor + getJumpMoving). 호출 측 inline motion 식 모두 제거.
        if (SmartMovingJumper.tryJump(player, sm, type, null, null, jumpAngle)) {
            sm.continueWallJumping = !sm.isHeadJumping;     // 원본 L1071
            sm.isClimbing = false;                            // 원본 L1072
            player.setYRot(jumpAngle);                         // 원본 L1073
            player.yBodyRot = jumpAngle;                       // 1.21.1 추가 — 회전 동기화 (Phase G 패턴)
            // 원본 L1074 onStartClimbBackJump():
            //   - prev rotateAngleY += isHeadJumping ? Half : Quarter  ← 보류 (애니메이션 사이클 / 사용자 원본 확인 대기)
            //   - isClimbBackJumping = true  (SmartMoving.java L147)
            sm.isClimbBackJumping = true;
        }
    }

    /**
     * 자유 클라이밍 낙하 데미지 처리.
     * 원본: SmartMovingSelf.handleCrash(startDistance, factor)
     * fallDistance > startDistance 시 초과분 * factor 데미지 적용.
     */
    private static void handleCrash(LocalPlayer player, float startDistance, float factor) {
        // Phase E BUG-2 1:1 정정 (handleCrash 두 번째 복사본 — free climb 용)
        if (player.fallDistance >= 2.0F) {
            player.awardStat(Stats.FALL_ONE_CM, (int) Math.round(player.fallDistance * 100D));
        }
        if (player.fallDistance >= startDistance) {
            float damage = (float) Math.ceil((player.fallDistance - startDistance) * factor);
            player.hurt(player.damageSources().fall(), damage);
        }
        // Phase E BUG-1 1:1 정정: 원본 L2242 fallDistance reset
        player.fallDistance = 0F;
    }

    // ── 7-3: handleCeilingClimbing ───────────────────────────────────────

    /**
     * 원본: SmartMovingSelf.handleCeilingClimbing()
     *
     * 천장 클라이밍 메인 로직.
     * 조건: wantClimbCeiling && !isClimbing && (!isCrawling || conflict) && !isCrawlClimbing
     *
     * jgap(머리 위 갭 크기)에 따른 수평 속도:
     *   jgap > 1.2  → 0.12
     *   jgap > 1.115 → 0.08
     *   else         → 0.04
     * fallDistance = 0
     *
     */
    public static void handleCeilingClimbing(LocalPlayer player, SmartMovingClientState sm) {
        SmartMovingConfig cfg = SmartMovingConfig.Config;

        if (!cfg.ceilingClimbing) return;

        // C-33: ceiling climb exhaustion 체크
        // 원본: exhaustion<=stop && (wasCeilingClimbing||exhaustion<=start)
        if (cfg.ceilingClimbExhaustion) {
            boolean allowed = sm.exhaustion <= cfg.ceilingClimbExhaustionStop
                    && (sm.isCeilingClimbing || sm.exhaustion <= cfg.ceilingClimbExhaustionStart);
            if (!allowed) return;
        }

        // C-34: wantClimbCeiling 조건 — grab 키 + !isCrawling + !isSneaking
        // 원본: grabButton.Pressed && !wantCrawlNotClimb && !isSneaking() && !disabled
        boolean wantClimbCeiling = SmartMovingKeys.grab.isDown()
                && !sm.isCrawling && !player.isShiftKeyDown();
        if (!wantClimbCeiling) return;

        // 조건: !isClimbing && (!isCrawling || conflict) && !isCrawlClimbing
        if (sm.isClimbing || sm.isCrawlClimbing) return;

        // 🔴 (2026-04-27) 천장 블록 종류 검사 — 원본 SmartMovingSelf L1139-1145 1:1.
        //   원본: topBlock = supportsCeilingClimbing(i, j, k);
        //         bottomBlock = supportsCeilingClimbing(i, j+1, k);
        //         if (topBlock != null || bottomBlock != null) { ... }
        //   기본 dictionary (SmartMovingConfig L142): iron bars + closed trapdoor.
        //   기존 CeilingClimbBlocks.supports(state) 헬퍼 호출 — 이미 dictionary 이식됨.
        Level world = player.level();
        AABB bb = player.getBoundingBox();
        int px = (int) Math.floor(player.getX());
        int pz = (int) Math.floor(player.getZ());
        int topY = (int) Math.floor(bb.maxY);
        BlockState topState    = world.getBlockState(new BlockPos(px, topY, pz));
        BlockState bottomState = world.getBlockState(new BlockPos(px, topY + 1, pz));
        boolean topClimb    = CeilingClimbBlocks.supports(topState);
        boolean bottomClimb = CeilingClimbBlocks.supports(bottomState);
        if (!topClimb && !bottomClimb) return;

        // C-35: jgap = 플레이어 머리(bb.maxY)에서 천장 블록까지의 거리
        // 원본: ceil(bb.maxY) 이상에서 첫 번째 솔리드 블록 Y - bb.maxY
        double jgap = computeJgap(player);

        // 🔴 원본 SmartMovingSelf L1151-1153 가드 2 + 3 — 천장 매달림 진입 범위 제한.
        //   가드 2: jgap < 1.9 — 매달림 위치가 너무 아래면 비활성. bottomCeilingClimbing
        //           시 jgap += 1 → bb.maxY 가 j+1 의 0.1 위쪽까지만 허용 (j+1 이 천장 블록).
        //   가드 3: actuallySolidHeight < jd + 0.5 — 머리 위 0.5 이내에 솔리드 블록 있어야.
        //           실제 천장 (fence/iron_bars + 일반 솔리드) 가 머리 가까이 있어야 매달림.
        //   사용자 보고 #: "천장 그랩 범위가 원본보다 더 아래까지 잡힘" 직접 원인 = 두 가드 누락.
        double jd = bb.maxY;
        double actuallySolidHeight = getMinPlayerSolidBetween(player, jd, jd + 0.6, 0.2);
        if (jgap >= 1.9 || actuallySolidHeight >= jd + 0.5) return;

        // 🔴 motionY = jgap 기반 anchor 보간 (원본 SmartMovingSelf L1145-1166 정밀 1:1).
        //   원본 메커니즘 = 자연스러운 anchor 수렴:
        //     jgap > 1.2  (천장에서 멀음) → motionY=0.12 → setLandMotions 후 +0.0392 (위로)
        //     jgap > 1.115 (anchor 범위)  → motionY=0.08 → 0 (정지)
        //     else        (천장 가까움)   → motionY=0.04 → -0.0392 (아래로 → jgap 회복)
        //   → 평형점 jgap=1.115~1.2 로 수렴. 사용자 가설 "부드러운 anchor 보간" 의 원본 구현.
        //   case 3 의 0.04 떨어짐이 anchor 보간의 핵심 (이전 0.08 변경은 anchor 보간 깨뜨림).
        double climbValue;
        if (jgap > 1.2) {
            climbValue = 0.12D;
        } else if (jgap > 1.115) {
            climbValue = HOLD_MOTION;  // 0.08
        } else {
            climbValue = 0.04D;  // 원본 1:1 — anchor 보간 (천천히 아래로 → jgap 증가)
        }

        // 🔴 motionX/Z: 원본 1:1 — handleCeilingClimbing 안에서 직접 set 하지 않음.
        //   원본 SmartMovingSelf L658 handleCeilingClimbing 은 motionY 만 set.
        //   motionX/Z 는 별도 흐름 (L653 landMotion 의 L718 sp.moveFlying — vanilla
        //   addVelocity 식). 우리 코드도 MixinLivingEntityClient L290 updateVelocity 가
        //   동일 식 처리 → handleCeilingClimbing 에서 추가 직접 set 하면 누적 → 원본보다 빠름.
        //   해결: 직접 set 제거. L290 만 사용 → 원본 1:1.

        // fallDistance = 0 (필수 — 낙하 데미지 방지)
        player.fallDistance = 0;

        // 🔴 motionY 만 setShouldClimbSpeed 호출 — 일반 grab hold 와 동일 메커니즘
        //   (relevant 가드: value < 0 || value > motionY).
        double combinedFactor = SmartMovingMover.getCombinedSpeedFactor(player, cfg);
        boolean climbIsUp = climbValue >= HOLD_MOTION;
        setShouldClimbSpeed(player, sm, climbValue, climbIsUp, combinedFactor);

        sm.isCeilingClimbing = true;
        // B-38 (세션 58): 원본 L1170 `isCrawling = false` — handleCeilingClimbing 성공
        // 분기 종료부에서 크롤 해제. 원본 L1162 isCeilingClimbing=true 와 함께 성공 분기
        // 내부 (L1162-L1170 사이 속도 세팅 L571/fallDistance L575 완료 후) 배치.
        sm.isCrawling = false;
    }

    /**
     * 천장 블록까지의 갭 계산 (C-35).
     * 원본: sp.boundingBox.maxY 기준, 위쪽으로 첫 솔리드 블록 Y - bb.maxY.
     * jgap이 클수록 방이 높음 → 천장 클라이밍 속도 증가.
     */
    private static double computeJgap(LocalPlayer player) {
        // 🔴 원본 SmartMovingSelf L1147-1149 정밀 1:1:
        //   double jgap = 1D - jd + j;
        //   if (bottomCeilingClimbing) jgap++;
        //
        //   jd = bb.maxY (사용자 머리 y), j = floor(jd).
        //   jgap = 1 - jd + j = (다음 정수 경계) - jd = 사용자 머리에서 다음 블록 경계까지 거리.
        //   bottomCeilingClimbing (= bottomBlock supports() true) 시 jgap += 1.
        //
        //   anchor 평형점 (motionY=0.08, jgap > 1.115 ~ 1.2) 으로 수렴:
        //     - 사용자 매달림 시 maxY = fence.y → jgap = 1 → case 3 → 떨어짐
        //     - 떨어지면서 bottom climbing 활성 + jgap 점차 1.116 진입 → case 2 → 정지
        //     - 평형점 = maxY = fence.y - 0.116 (사용자 머리가 fence 의 0.116 아래)
        //
        //   이전 식 (collision shape 거리 계산) 은 원본과 결과 다름 → 평형점 안 잡힘.
        Level world = player.level();
        AABB bb = player.getBoundingBox();
        int px = (int) Math.floor(player.getX());
        int pz = (int) Math.floor(player.getZ());

        double jd = bb.maxY;
        int j = (int) Math.floor(jd);
        double jgap = 1D - jd + j;

        // 원본 L1148-1149: bottomCeilingClimbing 시 jgap += 1
        BlockState bottomState = world.getBlockState(new BlockPos(px, j + 1, pz));
        if (CeilingClimbBlocks.supports(bottomState)) {
            jgap += 1.0D;
        }
        return jgap;
    }

    /**
     * 원본 SmartMovingBase L398-L409 `getMinPlayerSolidBetween(yMin, yMax, horizontalTolerance)` 1:1.
     *
     * player AABB 의 Y 범위를 [yMin, yMax] 로 임시 변경 + X/Z `horizontalTolerance` 만큼
     * 확장한 box 와 충돌하는 모든 solid 블록의 minY (블록 하단) 중 최솟값 반환.
     * 결과 ≥ yMin 으로 clamp.
     *
     * 1.21.1 매핑: world.getBlockCollisions(entity, box) → Iterable<VoxelShape> (world 좌표).
     * 각 shape 의 getBoundingBox().minY 중 최솟값.
     *
     * 천장 매달림 가드 3 에서 호출: getMinPlayerSolidBetween(jd, jd + 0.6, 0.2).
     * 머리 위 0.6 범위, X/Z 0.2 확장으로 머리 가까운 솔리드 블록 검출.
     */
    public static double getMinPlayerSolidBetween(LocalPlayer player,
                                                    double yMin, double yMax,
                                                    double horizontalTolerance) {
        AABB bb = player.getBoundingBox();
        AABB checkBox = new AABB(
                bb.minX - horizontalTolerance, yMin, bb.minZ - horizontalTolerance,
                bb.maxX + horizontalTolerance, yMax, bb.maxZ + horizontalTolerance
        );
        double result = yMax;
        Iterable<VoxelShape> collisions = player.level().getBlockCollisions(player, checkBox);
        for (VoxelShape shape : collisions) {
            if (shape.isEmpty()) continue;
            AABB shapeBox = shape.bounds();
            // shape 가 yMin~yMax 와 Y 범위 겹침 (X/Z 는 getBlockCollisions 가 처리)
            if (shapeBox.maxY > yMin && shapeBox.minY < yMax) {
                result = Math.min(result, shapeBox.minY);
            }
        }
        return Math.max(result, yMin);
    }
}
