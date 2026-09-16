package choco.ratel.smartmoving.client;

import choco.ratel.smartmoving.config.SmartMovingConfig;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Client-side swimming/diving physics logic.
 * Original: SmartMovingSelf.handleSwimming(), SmartMovingBase.moveFlying() porting.
 *
 * Included items:
 *   8-1: updateSwimState() — isDipping / isSwimming_sm / isDiving 3-classification
 *   8-2: handleSwimming() — underwater movement physics (damping, moveFlying, motionYDiff)
 *   8-5: isJumpingOutOfWater — horizontal collision + waterMovementTicks > 10 → motionY rise
 *   8-6: swimming sound accumulation → handled in MixinEntityClient.sm_afterMove_client
 */
@Environment(EnvType.CLIENT)
public final class SmartMovingSwimmer {

    // ── 8-1: offset threshold ──────────────────────────────────────────────────
    // Original: SmartMovingContext SwimCrawlWaterBorder constants
    // offset = getFluidHeight(WATER) + 0.1625D
    private static final double OFFSET_SWIMMING = 1.4D;  // isDipping/isSwimming boundary
    private static final double OFFSET_DIVING   = 1.9D;  // isSwimming/isDiving boundary

    // SwimCrawlWater boundary constants (SmartMovingContext L41-L44, R-06)
    private static final float SWIM_CRAWL_MAX    = 1.0F;    // SwimCrawlWaterMaxBorder
    private static final float SWIM_CRAWL_TOP    = 0.65F;   // SwimCrawlWaterTopBorder
    private static final float SWIM_CRAWL_MEDIUM = 0.6F;    // SwimCrawlWaterMediumBorder (B-36 branch b/c)
    private static final float SWIM_CRAWL_BOTTOM = 0.55F;   // SwimCrawlWaterBottomBorder (B-11 condition)

    // ── 8-2: underwater movement constants ──────────────────────────────────
    // Original: motionX/Y/Z damping values in SmartMovingSelf.handleSwimming()
    private static final double DAMPING_DIPPING_XZ = 0.80D;
    private static final double DAMPING_DIPPING_Y  = 0.83D;
    private static final double DAMPING_SWIMMING    = 0.85D;
    private static final double DAMPING_DIVING      = 0.83D;

    // Original: SM swim speed factor base movement speed
    private static final float  BASE_SWIM_SPEED    = 0.02F;

    // Original: isJumpingOutOfWater motionY value (maintain exact SM constant)
    private static final double JUMP_OUT_OF_WATER_VELOCITY = 0.30000001192092896D;

    private SmartMovingSwimmer() {}

    // ── [8-1] updateSwimState ──────────────────────────────────────────────

    /**
     * Called every travel() tick to update the 3-classification swim state.
     * Original: isDipping/isSwimming/isDiving update logic in SmartMoving.java.
     *
     * offset = getFluidHeight(WATER) + 0.1625D
     *   offset <  1.4 → isDipping   (below water surface boundary, feet submerged only)
     *   1.4 ≤ offset < 1.9 → isSwimming_sm (surface swimming)
     *   offset ≥  1.9 → isDiving    (fully submerged)
     */
    /**
     * Complete port of original SmartMovingSelf L1488-L1498 `resetSwimming()` (B-10-reset-post session 114).
     *
     * Original 8 fields to reset:
     *   dippingDepth = -1
     *   isDipping = false
     *   isSwimming = false
     *   isDiving = false
     *   isLevitating = false
     *   isShallowDiveOrSwim = false
     *   isFakeShallowWaterSneaking = false
     *   isJumpingOutOfWater = false
     *
     * Corresponds to one of 6 original call points: updateSwimState entry `!isInWater` branch (original L242/L253).
     * Internal handleSwimming calls (L555/L585/L607/L645) planned for B-9 Phase 5 rewrite.
     */
    private static void resetSwimming(SmartMovingClientState sm) {
        sm.dippingDepth                = -1F;
        sm.isDipping                   = false;
        sm.isSwimming_sm               = false;
        sm.isDiving                    = false;
        sm.isLevitating                = false;
        sm.isShallowDiveOrSwim         = false;
        sm.isFakeShallowWaterSneaking  = false;
        sm.isJumpingOutOfWater         = false;
    }

    public static void updateSwimState(LocalPlayer player, SmartMovingClientState sm) {
        // 🔵 (2026-05-20, BUG 1 fix #103) calculateDimensions call trigger.
        //   Original 1.7.10 `setHeightOffset(-1F)` directly changes boundingBox.minY → immediate box update.
        //   1.21.1 mapping uses dim provider pattern → cache updates + boundingBox recompute only when
        //   `Entity.calculateDimensions()` is called. updateSwimState only sets isSwimming_sm/isDiving
        //   and misses the call → POSE/dim update 1+ tick lag → user report "collision not immediate on water entry,
        //   shrinks after jump key" (= vanilla updatePose finally sets SWIMMING next tick).
        //   Solution: save prev on entry → check change before return → call calculateDimensions.
        //   Memory [[feedback_processStatePacket_calculateDimensions]] pattern applied 1:1.
        boolean prevSwim103 = sm.isSwimming_sm;
        boolean prevDive103 = sm.isDiving;

        // B-10b-pre (session 110): Original L105 `boolean wasJumpingOutOfWater = isJumpingOutOfWater`
        // Local snapshot. 1.21.1 splits updateSwimState + handleSwimming → promoted to field.
        // §7 B-10b-pre approximation. B-10b-post formula `isJumpingOutOfWater = ... || wasJumpingOutOfWater`
        // On porting, reference this previous tick value.
        sm.wasJumpingOutOfWater = sm.isJumpingOutOfWater;

        // **B-7c Resolution (session 127)**: Precise restoration of original L232 entry condition.
        //   boolean handleSwimming = !isFlying && !isLiquidClimbing
        //                         && (isInWater() || (wasSwimming && isInLiquid())
        //                             || (lavaLikeWater && handleLavaMovement()));
        // - isFlying (SM flight) / isLiquidClimbing (B-7a session 127) gate added
        // - wasSwimming is sm.isSwimming_sm not yet updated at updateSwimState entry point
        //   (equivalent to snapshot at MixinLivingEntityClient L77 `boolean wasSwimming = sm.isSwimming_sm;`)
        // - isInLiquid (B-7d session 127) — AABB liquid detection (more precise than vanilla isTouchingWater)
        // - player.isInLava() → corresponds to original handleLavaMovement() (vanilla liquid cross detection)
        SmartMovingConfig cfg7c = SmartMovingConfig.Config;
        // 🔴 (2026-05-22) isLavaLikeWaterEnabled(player) overload used — Creative mode auto true.
        // 🔴 (2026-05-22) User report fix v4 — 1-block space + dipping timing precise mapping.
        //
        //   Log measurement (line 371~): sm[cr=false dip=true] per tick + handleSwimming=true → ci.cancel →
        //     fromSwimmingOrDiving unreachable → no auto crawl entry path. **Previous fix v3's !isCrawling guard effect
        //     X** (= when isCrawling=false).
        //
        //   All 3 user verbatim scenarios cause = 1-block space swim processing cycle during dipping active:
        //   1. swim → 1-block entry dipping active frame briefly STANDING.
        //   2. 1-block space + dipping persist → crawl conversion X (= no auto crawl path).
        //   3. crawl + water entry → swim entry → isCrawling reset → STANDING.
        //
        //   Solution: When 1-block space (= box feet+1m above ceiling) detected, force handleSwim=false. Skip swim processing →
        //     resetSwimming → dipping=false → fromSwimmingOrDiving branch enter → branch 1 match → auto crawl.
        //   Check only at dipping=true moment (= performance avoidance, normal swim unaffected).
        //   Box formula = swim box assumption (entity.y+1, entity.y+1.8). Consistent result regardless of box dim persistence.
        boolean inSmallHole = false;
        if (sm.isDipping) {
            double standMinY = player.getY() + 1.0;
            double standMaxY = standMinY + 0.8;
            double cBot = SmartMovingClientState.getMaxPlayerSolidBetween(player, standMinY - 1D, standMinY, 0);
            double cCeil = SmartMovingClientState.getMinPlayerSolidBetween(player, standMaxY, standMaxY + 1.1D, 0);
            float plH = player.getDimensions(net.minecraft.world.entity.Pose.STANDING).height();
            inSmallHole = (cCeil - cBot) < plH;
        }
        boolean handleSwim = !sm.isFlying
                && !sm.isLiquidClimbing
                && !sm.isCrawling
                && !inSmallHole
                && (player.isInWater()
                    || (sm.isSwimming_sm && SmartMovingClientState.isInLiquid(player))
                    || (cfg7c.isLavaLikeWaterEnabled(player) && player.isInLava()));
        if (!handleSwim) {
            // B-10-reset-post (Session 114): Call original L1488-L1498 `resetSwimming()` method.
            // Corresponds to `!handleSwim` branch at updateSwimState entry (original L242/L253) — transition out of water.
            // Batch reset of 8 underwater-related fields (resolves omission of isLevitating / isFakeShallowWaterSneaking
            // / isJumpingOutOfWater 3 fields in previous individual assignments).
            resetSwimming(sm);
            // B-10c-post (Session 112): Original L550 `isStillSwimmingJump = false` — separate reset for useStandard path.
            // Not in original resetSwimming itself, but 1.21.1 `!handleSwim` path corresponds to useStandard,
            // so reset together here.
            sm.isStillSwimmingJump = false;
            // 1.21.1 addition — reset waterMovementTicks (not in original resetSwimming; consistent with B-12 correction
            // path, set to 0 outside water).
            sm.waterMovementTicks  = 0;
            // 🔵 (BUG 1 fix #103) calculateDimensions on dim change — swim/dive→false on water exit.
            if (sm.isSwimming_sm != prevSwim103 || sm.isDiving != prevDive103) {
                player.refreshDimensions();
            }
            return;
        }

        // **B-9a Resolution (Session 128)**: Replace `dippingDepth` semantics with original `playerSwimWaterBorder`
        //   (AABB precision, 0~∞ range). Existing `fluidHeight` approximation is liquid height within block
        //   (0~1 range) — semantics differ from original.
        //   Original L270: `playerSwimWaterBorder = totalSwimWaterBorder - j - j_offset`
        //   Original L416: `dippingDepth = (float)playerSwimWaterBorder`
        //   Consume B-42d `SwimBorderValues`. Share dippingDepth field with branch formula below.
        SmartMovingClientState.SwimBorderValues sbv9a =
                SmartMovingClientState.computeSwimBorderValues(player);
        sm.dippingDepth = (float) sbv9a.playerSwimWaterBorder;

        // Original L301 3-OR: isCrawling || isClimbCrawling || isCrawlClimbing → force isDipping
        // (handleSwimming L308-L309). B-6 (Session 60): Added missing `sm.isClimbCrawling`.
        // isClimbCrawling formula porting is in B-18 scope — currently maintained as always false, but condition restored 1:1.
        if (sm.isCrawling || sm.isClimbCrawling || sm.isCrawlClimbing) {
            sm.isDipping     = true;
            sm.isSwimming_sm = false;
            sm.isDiving      = false;
            // B-10a-post (Session 109): Original L507 formula `couldStandUp && (isDiving || isSwimming)`.
            // Forced isDipping path has both swim/dive false → automatically false.
            sm.isShallowDiveOrSwim = false;
            // B-12 (Session 64): Original L481-L484 `if(swimming||diving) ticks++; else ticks=0;`.
            //   Forced dipping path is not swimming/diving → reset ticks=0.
            sm.waterMovementTicks = 0;
            // 🔵 (BUG 1 fix #103) calculateDimensions on dim change — entry forced dipping (crawl, etc).
            if (sm.isSwimming_sm != prevSwim103 || sm.isDiving != prevDive103) {
                player.refreshDimensions();
            }
            return;
        }

        // **B-9a Resolution (Session 128)**: Restore original L305 `offset = playerSwimWaterBorder + 0.1625D`
        //   formula. Replace fluidHeight approximation with AABB-precise playerSwimWaterBorder.
        //   A/B sub-branches + (2, ∞) interval + (<0) branch reconstructed in subsequent atoms B-9b/c/d/e/f.
        //   Currently maintain only A path thresholds (1.4 / 1.9).
        double offset = sbv9a.playerSwimWaterBorder + 0.1625D;
        sm.isDipping     = offset < OFFSET_SWIMMING;
        sm.isSwimming_sm = offset >= OFFSET_SWIMMING && offset < OFFSET_DIVING;
        sm.isDiving      = offset >= OFFSET_DIVING;

        // B-8 (Session 67): Port original L436-L441 Config gate.
        //   swimming = !useStandard && swimming && Config.isSwimmingEnabled();
        //   diving   = !useStandard && diving   && Config.isDivingEnabled();
        //   dipping  = !useStandard && dipping  && Config.isSwimmingEnabled();
        // Purify state flags to false on Config disable → prevent consumers (#1/#3/#4) from referencing false values.
        // Existing mapping only approximates via return false in handleSwimming —
        // state flags themselves not purified (mistranslation).
        // useStandard is in B-9 scope (main classification rewrite) — here only purify flags.
        SmartMovingConfig cfg = SmartMovingConfig.Config;
        if (!cfg.isSwimmingEnabled()) {
            sm.isSwimming_sm = false;
            sm.isDipping     = false;
        }
        if (!cfg.isDivingEnabled()) {
            sm.isDiving = false;
        }

        // B-10d (Session 71): Port original L474 + L505 `isLevitating = levitating`.
        //   levitating = diving && !diveUp && !diveDown && moveStrafing==0 && moveForward==0
        // Original L468-L469:
        //   diveUp   = isp.getIsJumpingField()       → player.input.jumping
        //   diveDown = sneak && Config._diveDownOnSneak.value
        // Determine completely static diving state (no input at all). Used in animation (isDive Quarter-Sixteenth
        // vertical angle application condition), etc. Resolves R-11 A-2 mismatch #11. Field already ported in L179.
        // 🔵 (BUG 5 fix #107) Use raw sneak key — bypass player.isSneaking() override.
        //   MixinClientPlayerEntity.isSneaking() override always returns false while swimming
        //   (= memory [[feedback_clientplayer_isSneaking_override]] + [[feedback_movementInput_vs_isSneaking]]
        //   pattern). Use raw `player.input.sneaking` → original `esp.movementInput.sneak` 1:1.
        // 🔴 fix #201 (2026-05-29): swim/dive sneak+jump simultaneous → jump priority mapping.
        //   Reference: 1.7.10 L445-446 `if(diveUp) motionY -= 0.04` formula vs 1.12.2 L443-455 (removed).
        //   project_swimming_complete fix #109 = 1.12.2 1:1 mapping. So removing motionY -= 0.04 is 1.12.2 reference.
        //   Addition: jump guard for swimDown/diveDown → ignore sneak effect on swim+sneak+jump.
        //   User verbatim "originally worked → jump priority upward + to water surface extent" mapping.
        boolean diveUp16   = player.input.keyPresses.jump();
        boolean diveDown16 = player.input.keyPresses.shift() && cfg.diveDownOnSneak && !player.input.keyPresses.jump();
        sm.isLevitating = sm.isDiving
                && !diveUp16
                && !diveDown16
                && player.input.getMoveVector().x == 0F
                && player.input.getMoveVector().y == 0F;

        // B-12 (Session 64): Original L481-L484 correction — increment only swimming/diving, not dipping.
        //   else resets ticks=0. Existing unconditional increment (including dipping) is mistranslation.
        //   This ticks value must be accurate when porting isJumpingOutOfWater formula (original L486-L487).
        if (sm.isSwimming_sm || sm.isDiving) {
            sm.waterMovementTicks++;
        } else {
            sm.waterMovementTicks = 0;
        }

        // 🔵 (BUG 7 fix #108) isJumpingOutOfWater formula position — 1.12.2 1:1 mapping.
        //   1.7.10 L486-L487 formula evaluated only inside `if(swim||dive)` branch → not evaluated on dipping →
        //     1-block water + adjacent block + jump + wasd: surface jump (motionY=0.3) not triggered.
        //   1.12.2 L470-L473 formula inside `if(!useStandard)` (= swim/dive/dipping all) → even on dipping
        //     attempt evaluate isJumpingOutOfWater → match `onGround` branch → motionY=0.3 step up.
        //   Solution: Move formula position *outside* `if(swim||dive)` branch. Our mapping has separate
        //     updateSwimState + handleSwimming structure → formula evaluation here, motion application
        //     at end of handleSwimming final override (fix #101) automatic match.
        //   Regression safety: ticks=0 remains (on dipping), but matches via onGround / wasJumpingOutOfWater branch.
        //     When swim/dive entered, ticks>10 condition still works.
        boolean wantJumpOutOfWater = (player.input.getMoveVector().y != 0F
                                   || player.input.getMoveVector().x != 0F)
                && player.horizontalCollision
                && diveUp16
                && !sm.isSlow;
        sm.isJumpingOutOfWater = wantJumpOutOfWater
                && (sm.waterMovementTicks > 10
                    || player.onGround()
                    || sm.wasJumpingOutOfWater);

        // B-10a-post (Session 109): Port original L507 `isShallowDiveOrSwim = couldStandUp && (isDiving ||
        // isSwimming);`. `couldStandUp` = original L276 formula.
        // B-42-B5 Resolution (Session 121): Restore original formula via `SwimBorderValues` from existing
        // `dippingDepth >= 0F && dippingDepth <= 1.5F` approximation — `playerSwimWaterBorder >= 0 &&
        // minPlayerSwimWaterDepth <= 1.5` (AABB-precision derived values).
        // Consumers: B-36 branch (a) shallow water swim/dive → walking transition activation. Also consumed
        // in B-11 Phase 5 (shallow water special branch) as entry gate.
        SmartMovingClientState.SwimBorderValues swimVals =
                SmartMovingClientState.computeSwimBorderValues(player);
        boolean couldStandUp = swimVals.playerSwimWaterBorder >= 0
                            && swimVals.minPlayerSwimWaterDepth <= 1.5;
        sm.isShallowDiveOrSwim = couldStandUp && (sm.isDiving || sm.isSwimming_sm);

        // 🔵 (BUG 1 fix #103) End of main branch - call calculateDimensions on dim change.
        //   Immediately update bounding box on first swim/dive entry frame → same timing as original setHeightOffset(-1F).
        if (sm.isSwimming_sm != prevSwim103 || sm.isDiving != prevDive103) {
            // 🔴 (2026-05-22 fix #152) User report: swim → dipping transition box feet 1m down BUG.
            //   Log measurement (tick 219→220): mixin offset active when swimming (dim swim h=0.8 eye=1.62) →
            //     box +1m up → box feet = entity.y+1 = ground top. entity.y = ground - 1.
            //     swim → dipping transition: dim change (swim 0.8 → STANDING 1.8, orphan SWIMMING guard)
            //     → mixin offset blocked → box feet = entity.y = ground - 1. **Box top doesn't embed in ground**.
            //     vanilla push out blocked (= sm_travel_client ci.cancel) → entity.y persists.
            //   Original 1.7.10 spec: no posY change on swim (= only box.minY += 1 in setHeightOffset).
            //     On dipping, box feet = posY = ground. Our mapping: entity.y on swim = ground-1 → mismatch.
            //   Solution: At swim end point (= prev=true → cur=false) push entity.y to ground top.
            boolean swimEnded = (prevSwim103 || prevDive103) && !sm.isSwimming_sm && !sm.isDiving;
            if (swimEnded) {
                double upperGround = SmartMovingClientState.getMaxPlayerSolidBetween(
                        player, player.getY(), player.getY() + 1.5, 0);
                if (upperGround > player.getY() + 0.001D
                        && (upperGround - player.getY()) <= 1.5D) {
                    double pushY = upperGround - player.getY();
                    player.setPos(player.getX(), upperGround, player.getZ());
                    player.yOld += pushY;
                    player.yo += pushY;
                }
            }
            player.refreshDimensions();
        }
    }

    // ── [8-2] handleSwimming ─────────────────────────────────────────────────

    /**
     * Directly handle underwater movement physics. Completely replaces vanilla travel() underwater branch.
     * Original: SmartMovingSelf.handleSwimming(moveForward, moveStrafing, speedFactor,
     *                                      wasSwimming, wasDiving, isLiquidClimbing, wasJumpingOutOfWater)
     *
     * @param jumping jump key currently pressed state (LivingEntity.jumping)
     * @param wasSwimming snapshot of isSwimming at superMoveEntityWithHeading entry (original L97).
     * @param wasDiving   snapshot of isDiving at same time point (original L99).
     * @return true if SM handled it (caller cancels travel())
     */
    public static boolean handleSwimming(
            LocalPlayer player,
            SmartMovingClientState sm,
            Vec3 movementInput,
            boolean jumping,
            boolean wasSwimming,
            boolean wasDiving) {

        // ── SwimCrawlWater transition (original: handleSwimming L274-279, L416-432, R-06) ──────
        // wasHeightOffset = heightOffset (capture crawling hitbox offset).
        // 1.21.1: no bounding box minY movement → wasHeightOffset=0, playerCrawlWaterBorder=dippingDepth
        boolean wasCrawling = sm.isCrawling;

        // ① Equivalent to standupIfPossible: water depth > TopBorder → attempt crawl exit
        if (sm.isCrawling && sm.dippingDepth > SWIM_CRAWL_TOP) {
            sm.isCrawling = false;
        }

        // ② playerCrawlWaterBorder determination (when crawling in previous tick)
        // B-13 (Session 61): Align with original L2434 `(isCrawling || isSliding) && playerCrawlWaterBorder <
        // SwimCrawlWaterMaxBorder` by adding `|| sm.isSliding`. Original isCrawling
        // released after L2415 branch, so timing is equivalent to 1.21.1 wasCrawling snapshot at entry.
        // isSliding not modified at L121, so same timing as original.
        if ((wasCrawling || sm.isSliding) && sm.dippingDepth >= 0F) {
            float playerCrawlWaterBorder = sm.dippingDepth;
            if (playerCrawlWaterBorder < SWIM_CRAWL_MAX) {
                if (playerCrawlWaterBorder < SWIM_CRAWL_TOP) {
                    // Shallow water — continue crawling (handleSwimmingRejected = true)
                    sm.isCrawling = true;
                    return false;
                } else {
                    // Crawl → swimming transition (original: isCrawling=false; isSwimming=true; isDipping=false)
                    sm.isCrawling    = false;
                    sm.isDiving      = false;
                    sm.isSwimming_sm = true;
                    sm.isDipping     = false;
                }
            }
        }
        // ────────────────────────────────────────────────────────────────────────────

        if (!sm.isDipping && !sm.isSwimming_sm && !sm.isDiving) return false;

        SmartMovingConfig cfg = SmartMovingConfig.Config;
        if (sm.isDiving && !cfg.dive) return false;
        if ((sm.isSwimming_sm || sm.isDipping) && !cfg.swim) return false;

        // ── Original SmartMovingSelf L226-L246 (isFakeShallowWaterSneaking = true setting path) ──
        // Original:
        //   couldStandUp = playerSwimWaterBorder >= 0 && minPlayerSwimWaterDepth <= 1.5
        //   swimDown = sneak && _swimDownOnSneak
        //   wantShallowSwim = couldStandUp && (wasSwimming || wasDiving)
        //   if (wantShallowSwim) {
        //       for (Orientation o : getClimbingOrientations(sp, true, true))
        //           if (o.isTunnelAhead(world, i, j, k)) wantShallowSwim = false;   // Block ahead tunnel
        //   }
        //   if (wasSwimming && wantShallowSwim && swimDown) { swimDown=false; isFakeShallowWaterSneaking=true; }
        //
        // B-5 (Session 63) → **B-42-B5 Resolution (Session 121)**: Approximation (1) couldStandUp water depth measurement
        //   Restore original L276 `playerSwimWaterBorder >= 0 && minPlayerSwimWaterDepth <= 1.5`.
        //   AABB-precise derived values using `SwimBorderValues` (B-42d).
        // Remaining 2 approximations (§7 B-5 update — these outside B-42 scope):
        //   2) getClimbingOrientations direction set — original 8-way, 1.21.1 4-way (separate atom)
        //   3) swimDown=false (original L244) not ported — no behavior impact (1.21.1 swim vertical velocity
        //      unrelated to swimDown). Revisit in B-9 main classification rewrite.
        //   isTunnelAhead → private helper below.
        SmartMovingClientState.SwimBorderValues swimVals =
                SmartMovingClientState.computeSwimBorderValues(player);
        boolean couldStandUp = swimVals.playerSwimWaterBorder >= 0
                            && swimVals.minPlayerSwimWaterDepth <= 1.5;
        boolean wantShallowSwim = couldStandUp && (wasSwimming || wasDiving);
        if (wantShallowSwim) {
            int px = (int) Math.floor(player.getX());
            int py = (int) Math.floor(player.getY());
            int pz = (int) Math.floor(player.getZ());
            Level world = player.level();
            // **B-5 (2) Resolution (Session 135)**: Original `getClimbingOrientations(sp, true, true)` is
            //   8-way including diagonals (L285 `true, true` parameter). Existing 4-way (Direction.HORIZONTAL)
            //   approximation → add 4-way diagonals. Correspond to 8 directional constants of Orientation:
            //   PZ/NZ/ZP/ZN (4 straight) + PP/PN/NP/NN (4 diagonals).
            int[][] dirs8 = {
                    { 1, 0}, {-1, 0}, {0,  1}, {0, -1},   // 4-HORIZONTAL (PZ/NZ/ZP/ZN)
                    { 1, 1}, { 1,-1}, {-1, 1}, {-1, -1}   // 4-diagonals  (PP/PN/NP/NN)
            };
            for (int[] d : dirs8) {
                if (isTunnelAhead(world, px, py, pz, d[0], d[1])) {
                    wantShallowSwim = false;
                    break;
                }
            }
        }
        // **B-9h Resolution (Session 130)**: Integrate original L243-L244 `swimDown = sneak && _swimDownOnSneak` +
        //   L292-L296 `if (wasSwimming && wantShallowSwim && swimDown) { swimDown=false;
        //   isFakeShallowWaterSneaking=true; }`. swimDown consumed during swimming A path
        //   motionYDiff calculation (original L320-L321).
        // 🔵 (BUG 5 fix #107) raw sneak key — bypass isSneaking override.
        // 🔴 fix #201: swimDown jump guard. swim+sneak+jump miss swim branch → upward normal.
        boolean swimDown = player.input.keyPresses.shift() && cfg.swimDownOnSneak && !player.input.keyPresses.jump();
        if (wasSwimming && wantShallowSwim && swimDown) {
            swimDown = false;
            sm.isFakeShallowWaterSneaking = true;
        } else {
            sm.isFakeShallowWaterSneaking = false;
        }

        float moveForward = (float) movementInput.z;
        float moveStrafe  = (float) movementInput.x;

        // 🔵 (2026-05-20, BUG 3 fix #105) speedFactor 4-factor multiplication + swim/dive branch separate multiplication.
        //   Original SmartMovingSelf L119: `speedFactor = getConfigSpeedFactor() * getPotionSpeedFactor()
        //     * getNonSlowInputSpeedFactor(moveForward, moveStrafing)` then L129
        //     `speedFactor *= getSlowInputSpeedFactor()` (when not vanilla).
        //   Original L477-479: `if(diving) sf *= _diveSpeedFactor; if(swimming) sf *= _swimSpeedFactor;` —
        //     separate multiply only for swim/dive branch. Not applied during dipping.
        //   Existing mapping BUG:
        //     1. Using only `getCombinedSpeedFactor` (= config × potion) → nonSlow (sprint 1.5×)
        //        + slow factor missing → ~33% slower than original (= user report "speed slow").
        //     2. Dipping also multiply `swimSpeedFactor` (= sm.isDiving ? dive : swim, match dipping false) —
        //        original does not apply during dipping.
        //   Resolution: Use `Mover.getSpeedFactor` (= 4 factor multiply 1:1) + branch multiply only on swim/dive match.
        float speedFactor = SmartMovingMover.getSpeedFactor(player, sm, cfg);
        if (sm.isDiving)         speedFactor *= cfg.diveSpeedFactor;
        else if (sm.isSwimming_sm) speedFactor *= cfg.swimSpeedFactor;
        // Do not apply branch factor during dipping (original L477-479)

        Vec3 vel = player.getDeltaMovement();
        double motionX = vel.x;
        double motionY = vel.y;
        double motionZ = vel.z;

        // Jump (diveUp) / Sneak (diveDown) keys
        // 🔵 (BUG 5 fix #107) diveDown formula original L279 1:1 — raw sneak + cfg gate.
        //   Existing: `player.isSneaking() && sm.isDiving` — doesn't bypass isSneaking override +
        //         missing cfg.diveDownOnSneak guard + wrongly added `sm.isDiving` guard
        //         (original L279 = `esp.movementInput.sneak && Config._diveDownOnSneak.value` only).
        //   Solution: raw sneak + cfg.diveDownOnSneak. isDiving guard handled naturally in subsequent motion branch
        //         (diveDown formula matches only inside dive branch).
        boolean diveUp   = jumping;
        // 🔴 fix #201: jump guard for diveDown. dive+sneak+jump simultaneous → diveDown=false → cancel formula not triggered → diveUp remains → motionYDiff=0.05 (upward).
        boolean diveDown = player.input.keyPresses.shift() && cfg.diveDownOnSneak && !player.input.keyPresses.jump();

        // 🔵 (BUG 6 fix #109) jump + sneak simultaneous press both cancel — original L298-L299 / 1.12.2 L306-307 1:1.
        //   `if (isDiving && diveUp && diveDown) diveUp = diveDown = false;`
        //   = stop on simultaneous jump+sneak during dive (= levitating posture or default motion formula).
        //   Existing mapping missing → jump priority behavior (= cancel formula not triggered, diveUp=true remains, motion formula
        //     `if(diveUp || diveDown || levitating) motionY = (motionY+diff)*0.6` enters → rises).
        //   User perception "original=jump priority" mistook our mapping behavior for original. Actual original = stop.
        if (sm.isDiving && diveUp && diveDown) {
            diveUp = false;
            diveDown = false;
        }

        // **B-9b Resolution (Session 129)**: Port original L306 `moveSwim` + A/B path determination.
        //   moveSwim = pitch < 0 && forward > 0 || pitch > 0 && forward < 0
        //   (opposite pitch and forward signs — looking up going backward or looking down going forward = entry posture)
        //   isPathA = diveUp || moveSwim || wantShallowSwim
        //     A path: active swimming (< 1.4 dipping, < 1.9 swimming, else diving)
        //     B path: passive floating (< 1.5 dipping, else diving — no swimming)
        boolean moveSwim = (player.getXRot() < 0F && moveForward > 0F)
                        || (player.getXRot() > 0F && moveForward < 0F);
        boolean isPathA = diveUp || moveSwim || wantShallowSwim;

        // **B-9b State reclassification (Session 129)**: Reclassify isDipping/isSwimming_sm/isDiving from
        //   basic A path classification of updateSwimState based on A/B path. Only reclassify in [0, 2] range
        //   (updateSwimState is default A path threshold 1.4/1.9. B path 1.5 + no swimming).
        //   Forced dipping from isCrawling||isClimbCrawling||isCrawlClimbing (original L301) already
        //   processed in updateSwimState L135 — do not modify here.
        double playerSwimWaterBorder9b = swimVals.playerSwimWaterBorder;
        boolean isForceDipping9b = sm.isCrawling || sm.isClimbCrawling || sm.isCrawlClimbing;
        if (!isForceDipping9b
                && playerSwimWaterBorder9b >= 0 && playerSwimWaterBorder9b <= 2) {
            double offset9b = playerSwimWaterBorder9b + 0.1625D;
            if (isPathA) {
                // A path (original L307-L358): 1.4 / 1.9 threshold
                sm.isDipping     = offset9b < 1.4D;
                sm.isSwimming_sm = offset9b >= 1.4D && offset9b < 1.9D;
                sm.isDiving      = offset9b >= 1.9D;
            } else {
                // B path (original L360-L398): 1.5 threshold, no swimming
                sm.isDipping     = offset9b < 1.5D;
                sm.isSwimming_sm = false;
                sm.isDiving      = offset9b >= 1.5D;
            }
            // Reapply Config gate (original L436-L441)
            if (!cfg.isSwimmingEnabled()) {
                sm.isSwimming_sm = false;
                sm.isDipping     = false;
            }
            if (!cfg.isDivingEnabled()) {
                sm.isDiving = false;
            }

            // 🔵 (2026-05-20, BUG-Swim-Anim-Surface-1char fix #116) Reevaluate isLevitating in handleSwimming
            //   — original SmartMovingSelf L474+L505 1:1.
            //   Log measurement cause: tick 374 updateSwimState L218 formula based on initial classification (offset 1.9 threshold,
            //     `offset=1.8976` < 1.9 → isDiving=false) → isLevitating=false. However,
            //     after B path reclassification in handleSwimming (above L476: offset>=1.5 → isDiving=true)
            //     even if isDiving changes to true, isLevitating formula not reevaluated → setupTransforms timing
            //     `dv=true + lv=false` → DIVE-VANG branch → tilt = π/2 - vAng = π/2 - π/2 = 0 →
            //     user report "suddenly 1-char at water surface".
            //   Original SmartMovingSelf L474 `boolean levitating = diving && !diveUp && !diveDown &&
            //     moveStrafing == 0F && moveForward == 0F` evaluated inside handleSwimming (= based on final isDiving value).
            //     L505 `isLevitating = levitating` updates field.
            //   Solution: Reevaluate isLevitating after B-9b reclassification + Config gate. Keep updateSwimState
            //     L218 formula as-is (= affects other branch refs), but handleSwimming reassignment is
            //     final value (= value at setupTransforms timing).
            sm.isLevitating = sm.isDiving
                    && !diveUp
                    && !diveDown
                    && moveStrafe == 0F
                    && moveForward == 0F;
        }
        // **B-9f Resolution (Session 130)**: (<0) range handleSwimmingRejected (original L413-L414).
        //   If playerSwimWaterBorder < 0 then above/outside water → SM not handling, delegate vanilla travel().
        //   Forced dipping path (isCrawling type) exception (original L301-L302 already isDipping=true).
        else if (!isForceDipping9b && playerSwimWaterBorder9b < 0) {
            return false;
        }

        // 🔴 fix #202 (2026-05-29): Partial apply 1.7.10 L445-446 formula — sneak guard.
        //   User verbatim "just standing when jumped up to water surface" = swim+jump solo
        //   doesn't go up (= water surface not reached = SM swim state maintained) user intent.
        //   User verbatim "sneak+jump simultaneous → upward normal" = when sneak pressed -= 0.04 not applied.
        //
        //   Formula: jump solo (= !sneak) motion -= 0.04 → doesn't go up → water surface not reached.
        //           jump+sneak (= sneak) -= 0.04 not applied + swimDown jump guard (= swim branch no match)
        //           → motion += motionYDiff → upward normal.
        if (diveUp && !player.input.keyPresses.shift()) {
            motionY -= 0.039999999105930328D;
        }

        // **B-9a Resolution (Session 128)**: Also AABB-precision offset calculation inside handleSwimming.
        //   `dippingDepth` already `playerSwimWaterBorder` semantics, so use directly.
        //   Original L305: `offset = playerSwimWaterBorder + 0.1625D`
        if (sm.isDipping) {
            // **B-9c dipping branch (Session 129)**: Original L309-L316 (A) / L362-L369 (B).
            //   A path: offset < 1.0 → -0.02D, else → -0.01D
            //   B path: both branches -0.02D (original L365-L368 — redundant but original as-is)
            // 🔵 (BUG 4 fix #106) Correct damping order — original L450-471 (damping first) →
            //   L502 (motionY += motionYDiff). Existing (motion+diff)*damp formula terminal velocity
            //   ~15% smaller. Damp first → add diff/fly formula 1:1 mapping.
            double dippingOffset = sm.dippingDepth + 0.1625D;
            double dippingYDiff;
            if (isPathA) {
                dippingYDiff = dippingOffset < 1.0D ? -0.02D : -0.01D;
            } else {
                dippingYDiff = -0.02D;
            }
            motionX *= DAMPING_DIPPING_XZ;
            motionY *= DAMPING_DIPPING_Y;
            motionZ *= DAMPING_DIPPING_XZ;
            Vec3 fly = moveFlying(player, moveStrafe, moveForward, BASE_SWIM_SPEED * speedFactor);
            motionX += fly.x;
            motionY += dippingYDiff;
            motionZ += fly.z;

        } else if (sm.isSwimming_sm) {
            // **B-9c Resolution (Session 129, B-9h update Session 130)**: Original L317-L348 A path swimming
            //   Restore 11-step table + swimDown branch (original L320-L321).
            //   B path reclassified as isSwimming_sm=false, so this branch not entered.
            //   Original L320: if (swimDown) motionYDiff = -0.05 * (isFast ? sprintFactor : 1)
            //   From original L322 onwards `if (offset < 1.6)` etc. separate if after swimDown branch — even if swimDown
            //   true, overwrite if offset condition met (original structure as-is ported).
            double offset = sm.dippingDepth + 0.1625D;
            double motionYDiff;
            if (swimDown) {
                motionYDiff = -0.05D * (sm.isFast ? cfg.sprintFactor : 1F);
            } else if (offset < 1.5D) {
                motionYDiff = -0.02D;
            } else {
                motionYDiff = 0D;   // Initial value for path not entering original L320
            }
            // After original L324 is `if` (not else-if) — overwrite if offset condition met
            if      (offset < 1.6D)   motionYDiff = -0.01D;
            else if (offset < 1.62D)  motionYDiff = -0.005D;
            else if (offset < 1.64D)  motionYDiff = -0.0025D;
            else if (offset < 1.66D)  motionYDiff = -0.00125D;
            else if (offset < 1.664D) motionYDiff = -0.000625D;
            else if (offset < 1.668D) motionYDiff =  0D;
            else if (offset < 1.672D) motionYDiff =  0.000625D;
            else if (offset < 1.676D) motionYDiff =  0.00125D;
            else if (offset < 1.68D)  motionYDiff =  0.0025D;
            else if (offset < 1.7D)   motionYDiff =  0.005D;
            else if (offset < 1.8D)   motionYDiff =  0.01D;
            else                       motionYDiff =  0.02D;

            // 🔵 (BUG 4 fix #106) Correct damping order — original L450-471 (damping first) →
            //   L502 (motionY += motionYDiff). Damp first → add fly + diff.
            motionX *= DAMPING_SWIMMING;
            motionY *= DAMPING_SWIMMING;
            motionZ *= DAMPING_SWIMMING;
            Vec3 fly = moveFlying(player, moveStrafe, moveForward, BASE_SWIM_SPEED * speedFactor);
            motionX += fly.x;
            motionY += motionYDiff;
            motionZ += fly.z;

            sm.heightOffset = -1F;

        } else { // isDiving
            // **B-9d Resolution (Session 129, B-9e update Session 130)**: Restore original L349-L358 (A path diving
            //   [1.9, 2]) + L370-L397 (B path diving 10-step [1.5, 2]) + L400-L412 ((2, ∞)
            //   range diving) all.
            //   A path: diveUp / diveDown / default = moveSwim ? 0.04 : 0.02
            //   B path: diveDown / offset 10-step (< 1.8 -0.02 ~ else 0.01)
            //   (2, ∞): on diveUp if isFast+playerSwimWaterBorder<2.5+isAir(j+3) → 0.11/sprintFactor
            //          / else → 0.01+0.1*sf / diveDown → 0.01-0.1*sf / default → 0.01
            double motionYDiff = 0D;
            double offset9d = sm.dippingDepth + 0.1625D;
            double psw9e = swimVals.playerSwimWaterBorder;
            if (psw9e > 2) {
                // **B-9e Resolution (Session 130)**: (2, ∞) range diving (original L400-L412)
                if (diveUp) {
                    // Original L404: isFast && psw < 2.5 && isAirBlock(i, j + 3, k) → sprint jump boost
                    if (sm.isFast && psw9e < 2.5D
                            && player.level().isEmptyBlock(new net.minecraft.core.BlockPos(
                                    swimVals.i, swimVals.j + 3, swimVals.k))) {
                        motionYDiff = 0.11D / cfg.sprintFactor;
                    } else {
                        motionYDiff = 0.01D + 0.1D * speedFactor;
                    }
                } else if (diveDown) {
                    motionYDiff = 0.01D - 0.1D * speedFactor;
                } else {
                    motionYDiff = 0.01D;
                }
            } else if (isPathA) {
                // A path diving (original L349-L358)
                if (diveUp) {
                    motionYDiff = 0.05D * (sm.isFast ? cfg.sprintFactor : 1F);
                } else if (diveDown) {
                    motionYDiff = 0.01D - 0.1D * speedFactor;
                } else {
                    motionYDiff = moveSwim ? 0.04D : 0.02D;
                }
            } else {
                // B path diving (original L370-L397) — offset 10-step
                if (diveDown) {
                    motionYDiff = 0.01D - 0.1D * speedFactor;
                } else if (offset9d < 1.8D)  motionYDiff = -0.02D;
                else if (offset9d < 1.82D) motionYDiff = -0.01D;
                else if (offset9d < 1.84D) motionYDiff = -0.005D;
                else if (offset9d < 1.86D) motionYDiff = -0.0025D;
                else if (offset9d < 1.864D) motionYDiff = -0.00125D;
                else if (offset9d < 1.868D) motionYDiff = 0D;
                else if (offset9d < 1.872D) motionYDiff = 0.00125D;
                else if (offset9d < 1.876D) motionYDiff = 0.0025D;
                else if (offset9d < 1.88D)  motionYDiff = 0.005D;
                else if (offset9d < 1.9D)   motionYDiff = 0.01D;
                else                        motionYDiff = 0.01D;
            }

            // 🔵 (BUG 4 fix #106) Separate handling for diving levitating branch + correct damping order.
            //   Original L489-496:
            //     if(diving) {
            //         if(diveUp || diveDown || levitating)
            //             motionY = (motionY + motionYDiff) * 0.6;  // Strong vertical control + horizontal 0
            //         else
            //             moveFlying((float)motionYDiff, ..., _diveControlVertical);
            //         moveFlying = false;  // Block subsequent horizontal moveFlying
            //     }
            //   Existing mapping BUG: No levitating branch, always 5-arg moveFlying → stay still + jump hold not
            //     reaching surface (= user report (5) "breath not consumed water surface position" not achieved).
            //   Solution: (a) diveUp || diveDown || levitating → (motionY + diff) * 0.6 formula + horizontal damping
            //           only (= moveFlying not called). (b) Normal → 5-arg moveFlying (= mouse direction movement).
            //   Also correct damping order: motion *= 0.83 (first) → add fly (= original L450 → L494).
            motionX *= DAMPING_DIVING;
            motionY *= DAMPING_DIVING;
            motionZ *= DAMPING_DIVING;
            if (diveUp || diveDown || sm.isLevitating) {
                // Original L491-492: (motionY * 0.83 + motionYDiff) * 0.6 formula → terminal v = 1.2 m/s
                //   reach surface fast. No horizontal motion change (= original moveFlying=false effect).
                motionY = (motionY + motionYDiff) * 0.6D;
            } else {
                // Original L494: 5-arg moveFlying (pitch-reflected vertical + horizontal). moveUpward=motionYDiff.
                Vec3 fly = moveFlying(player, (float) motionYDiff, moveStrafe, moveForward,
                        BASE_SWIM_SPEED * speedFactor, cfg.diveControlVertical);
                motionX += fly.x;
                motionY += fly.y;
                motionZ += fly.z;
            }

            sm.heightOffset = -1F;
        }

        // **B-11 Resolution (Session 130)**: Original L513-L536 shallow water special branch.
        //   isShallowDiveOrSwim && realMinPlayerSwimWaterDepth < SwimCrawlWaterBottomBorder(0.55)
        //   → if isSlow convert to crawl, else convert to walking + move to ground position.
        //   isShallowDiveOrSwim recalculated after B-9b reclassification (original L507).
        //   Consume B-42a `getMaxPlayerSolidBetween` + B-42d `realMinPlayerSwimWaterDepth`.
        boolean isShallowDiveOrSwim11 = couldStandUp && (sm.isDiving || sm.isSwimming_sm);
        sm.isShallowDiveOrSwim = isShallowDiveOrSwim11;
        if (isShallowDiveOrSwim11
                && swimVals.realMinPlayerSwimWaterDepth < SWIM_CRAWL_BOTTOM) {
            if (sm.isSlow) {
                // Shallow water swim/dive → convert to crawl (original L515-L524)
                sm.heightOffset          = -1F;
                sm.isCrawling            = true;
                sm.isDiving              = false;
                sm.isSwimming_sm         = false;
                sm.isShallowDiveOrSwim   = false;
                sm.isDipping             = true;
            } else {
                // Shallow water swim/dive → convert to walking (original L525-L535)
                sm.heightOffset = 0F;
                double minY11 = player.getBoundingBox().minY;
                double maxY11 = player.getBoundingBox().maxY;
                double groundY11 = SmartMovingClientState.getMaxPlayerSolidBetween(
                        player, minY11, maxY11, 0);
                player.move(MoverType.SELF, new Vec3(0, groundY11 - minY11, 0));
                sm.isCrawling            = false;
                sm.isDiving              = false;
                sm.isSwimming_sm         = false;
                sm.isShallowDiveOrSwim   = false;
                sm.isDipping             = true;
            }
        }

        // ── [8-5] isJumpingOutOfWater ────────────────────────────────────────
        // 🔵 (2026-05-20, Phase 0 validation fix): Original L499-500 `else if(isJumpingOutOfWater) sp.motionY = 0.3`
        //   1:1 mapping. sm.isJumpingOutOfWater field is already updated at updateSwimState L212-220 with original L486-487
        //   complete formula (including `!isSlow` + `wasJumpingOutOfWater`).
        //   Previous mapping recalculated formula — missing `!sm.isSlow` guard + missing `sm.wasJumpingOutOfWater` OR term →
        //   jump not blocked during sneak hold + continuous frame jump match failed. User-perceptible
        //   difference (unexpected surface jump on corner wall collision during sneak dive).
        if (sm.isJumpingOutOfWater) {
            motionY = JUMP_OUT_OF_WATER_VELOCITY;
        }

        // 🔴 fix #128 reverted (2026-05-21, user report "swim speed itself slowed down"):
        //   fix #128 applying 0.91 friction after swim changed swim speed (= 1.5× slower). Not user intent.
        //   User verbatim "only look at head rotation speed" — avoid swim physics change.
        player.setDeltaMovement(motionX, motionY, motionZ);
        player.move(MoverType.SELF, player.getDeltaMovement());
        player.fallDistance = 0F;
        return true;
    }

    // ── moveFlying (Original SmartMovingBase.md L77-114 — 5-arg version 1:1) ──────

    /**
     * Original SmartMovingBase.moveFlying(moveUpward, strafe, forward, speed, treeDimensional)
     * 1:1 port. Also restore missing normalization [mistranslation] of existing 4-arg version (horizontal-only).
     *
     * Step 1: Horizontal direction vector (YAW-based) — includes normalization floor 1.0F
     *   total = sqrt(strafe² + forward²); if (total < 0.01) skip; if (total < 1.0) total = 1.0
     *   factor-based strafe/forward component × (cos, -sin, sin, cos) combination
     *
     * Step 2: Pitch-based vertical correction (when treeDimensional=true)
     *   rotation = toRadians(pitch); horizFactor = cos; vertFactor = -sin * signum(forward)
     *
     * Step 3: Final motion summation
     *   diffMY = sqrt(diffMXForward² + diffMZForward²) * vertFactor + moveUpward
     *
     * Step 4: Asymmetric total formula sqrt(sqrt(dx²+dz²) + dy²) apply factor
     */
    private static Vec3 moveFlying(LocalPlayer player, float moveUpward,
                                     float strafe, float forward, float speed, boolean treeDimensional) {
        float diffMXStrafing = 0F, diffMXForward = 0F, diffMZStrafing = 0F, diffMZForward = 0F;
        float total = (float) Math.sqrt(strafe * strafe + forward * forward);
        if (total >= 0.01F) {
            if (total < 1.0F) total = 1.0F;
            float strafeFactor  = strafe  / total;
            float forwardFactor = forward / total;
            float yawRad = (float) Math.toRadians(player.getYRot());
            float sin = (float) Math.sin(yawRad);
            float cos = (float) Math.cos(yawRad);
            diffMXStrafing =  strafeFactor  * cos;
            diffMXForward  = -forwardFactor * sin;
            diffMZStrafing =  strafeFactor  * sin;
            diffMZForward  =  forwardFactor * cos;
        }

        float rotation = treeDimensional ? (float) Math.toRadians(player.getXRot()) : 0F;
        float horizFactor = (float) Math.cos(rotation);
        float vertFactor  = (float) (-Math.sin(rotation) * Math.signum(forward));

        float diffMX = diffMXForward * horizFactor + diffMXStrafing;
        float diffMY = (float) Math.sqrt(diffMXForward * diffMXForward + diffMZForward * diffMZForward)
                       * vertFactor + moveUpward;
        float diffMZ = diffMZForward * horizFactor + diffMZStrafing;

        // Asymmetric total formula — original note: sqrt(sqrt(x²+z²) + y²), NOT sqrt(x²+y²+z²)
        float total2 = (float) Math.sqrt(Math.sqrt(diffMX * diffMX + diffMZ * diffMZ) + diffMY * diffMY);
        if (total2 > 0.01F) {
            float factor = speed / total2;
            return new Vec3(diffMX * factor, diffMY * factor, diffMZ * factor);
        }
        return Vec3.ZERO;
    }

    /** 4-arg wrapper (horizontal-only) — moveUpward=0, treeDimensional=false. */
    private static Vec3 moveFlying(LocalPlayer player, float strafe, float forward, float speed) {
        return moveFlying(player, 0F, strafe, forward, speed, false);
    }

    // ── [Phase C-3] handleLava (Focus #2.6 Session 4) ───────────────────────────

    /**
     * Phase C-3 (Focus #2.6 Session 4) — handleLava main body 1:1 port.
     *
     * Original `SmartMovingSelf.handleLava()` L578-L600 (from research §2):
     * <pre>
     * private boolean handleLava(float moveForward, float moveStrafing,
     *                            boolean handledSwimming, boolean isLiquidClimbing) {
     *     boolean handleLava = !isFlying && !handledSwimming && !isLiquidClimbing
     *                       && sp.handleLavaMovement();
     *     if (handleLava) {
     *         standupIfPossible();
     *         resetClimbing();
     *         resetSwimming();
     *         double d1 = sp.posY;
     *         sp.moveFlying(moveStrafing, moveForward, 0.02F);
     *         sp.moveEntity(sp.motionX, sp.motionY, sp.motionZ);
     *         sp.motionX *= 0.5D;
     *         sp.motionY *= 0.5D;
     *         sp.motionZ *= 0.5D;
     *         sp.motionY -= 0.02D;
     *         if (sp.isCollidedHorizontally && sp.isOffsetPositionInLiquid(
     *                 sp.motionX, ((sp.motionY + 0.60000002384185791D) - sp.posY) + d1, sp.motionZ)) {
     *             sp.motionY = 0.30000001192092896D;
     *         }
     *     }
     *     return handleLava;
     * }
     * </pre>
     *
     * <p><b>Call Location</b>: {@link choco.ratel.smartmoving.mixin.client.MixinLivingEntityClient}
     * in {@code sm_travel_client} right after handleSwimming (handledSwimming=false branch).
     * On entry, block vanilla travel with ci.cancel().
     *
     * <p><b>Entry Conditions (4-AND)</b>:
     * <ul>
     *   <li>{@code !sm.isFlying} (no SM flying)</li>
     *   <li>{@code !handledSwimming} — guaranteed by call location (enter after handleSwimming false)</li>
     *   <li>{@code !sm.isLiquidClimbing} (no liquid climbing)</li>
     *   <li>{@code player.isInLava()} (inside lava)</li>
     * </ul>
     *
     * <p><b>Side Effect Evaluation</b> (exhaustive Focus #2.6 Session 4):
     * <ul>
     *   <li>swimUpward (jump +0.04F): outside tickMovement L2649 → vanilla auto-handled ✅</li>
     *   <li>lava damage (setOnFireFromLava): separate {@code Entity.tick} → not blocked ✅</li>
     *   <li>lava sound / particle: separate systems → not blocked ✅</li>
     *   <li>vanilla travel() lava branch (L2123-L2142): SM handles equivalently (wall jump motionY=0.3 included)</li>
     * </ul>
     *
     * <p><b>Subtle Differences from Vanilla (Original 1.7.10 Faithful)</b>:
     * <ul>
     *   <li>damping Y: original {@code 0.5D} vs vanilla {@code 0.8F} (SM has faster fall)</li>
     *   <li>gravity: original {@code -0.02D} fixed vs vanilla {@code -d/4} (gravity attribute, slow_falling effect)</li>
     * </ul>
     *
     * <p><b>Call Context</b>:
     * <ul>
     *   <li>{@code lavaLikeWater = true} (Creative): handleSwimming handles lava →
     *     handledSwimming=true → this method not entered</li>
     *   <li>{@code lavaLikeWater = false} (Survival default): handleSwimming false →
     *     this method entered → SM itself handles lava movement (brief motion before death)</li>
     * </ul>
     *
     * @return true when caller should ci.cancel() to block vanilla travel
     */
    public static boolean handleLava(LocalPlayer player, SmartMovingClientState sm,
                                      Vec3 movementInput) {
        // Entry condition (original L580): handledSwimming guaranteed by call location
        boolean handleLavaFlag = !sm.isFlying && !sm.isLiquidClimbing && player.isInLava();
        if (!handleLavaFlag) return false;

        // Original L582 standupIfPossible — check heightOffset >= 0, restore if -1F
        sm.standupIfPossible(player);
        // Original L583 resetClimbing — reset climbing state
        sm.resetClimbing();
        // Original L584 resetSwimming — reset swimming 8 fields (private static, same class)
        resetSwimming(sm);

        // Original L586: save d1 = posY (for wall jump isOffsetPositionInLiquid check using prev posY)
        double d1 = player.getY();

        // 🔴 (2026-05-22) User-reported BUG fix: player stops on lava entry (no motion accumulation).
        //   Cause: original vanilla 1.7.10 moveFlying = accumulates with += on motion. Our mapping SM's own moveFlying
        //     (Vec3 return) + setVelocity(moved) = overwrites previous motion → each frame reset to 0 → only small
        //     speed (0.02F) applied + damping 0.5 = nearly stopped.
        //   Original formula: sp.moveFlying(moveStrafing, moveForward, 0.02F);  // motion += fly
        //                     sp.moveEntity(motionX, motionY, motionZ);
        //   Solution: getVelocity().add(moved) = += accumulation. Consistent with other swim branches (L526/L566).
        Vec3 moved = moveFlying(player, (float) movementInput.x, (float) movementInput.z, 0.02F);
        player.setDeltaMovement(player.getDeltaMovement().add(moved));

        // Original L588: moveEntity(motionX, motionY, motionZ) — actual movement
        player.move(MoverType.SELF, player.getDeltaMovement());

        // Original L589-L591: damping 0.5 (X/Y/Z all)
        Vec3 vel = player.getDeltaMovement();
        double mx = vel.x * 0.5D;
        double my = vel.y * 0.5D;
        double mz = vel.z * 0.5D;

        // Original L592: gravity -0.02D (subtle difference from vanilla -gravity/4 — faithful to original)
        my -= 0.02D;

        // Original L594-L597: lava wall jump
        //   isCollidedHorizontally && isOffsetPositionInLiquid(motionX,
        //       ((motionY + 0.60000002384185791D) - posY) + d1, motionZ)
        //   → motionY = 0.30000001192092896D
        // 1.21.1 mapping: isOffsetPositionInLiquid → BoundingBox.offset() + world.containsFluid()
        if (player.horizontalCollision) {
            AABB bb = player.getBoundingBox().move(
                    mx,
                    ((my + 0.60000002384185791D) - player.getY()) + d1,
                    mz);
            if (player.level().containsAnyLiquid(bb)) {
                my = 0.30000001192092896D;
            }
        }

        // Final setVelocity (original directly changes motionX/Y/Z, 1.21.1 batch setVelocity)
        player.setDeltaMovement(mx, my, mz);

        // Original L599 return handleLava
        return true;
    }

    /**
     * Original Orientation.isTunnelAhead(world, i, j, k) 1:1 port.
     *   remoteId = world.getBlock(i+_i, j+1, k+_k)
     *   if (isFullEmpty(remoteId)) {
     *     aboveMat = world.getBlock(i+_i, j+2, k+_k).getMaterial()
     *     if (aboveMat != null && isSolid(aboveMat)) return true
     *   }
     *   return false
     *
     * **B-5 (2) Resolution (Session 135)**: ox/oz offset parameter version — supports 8-way including diagonals.
     * Existing `Direction` parameter (4-way only) → receive offset directly corresponding to original `Orientation._i/_k`.
     *
     * 1.21.1 mapping:
     *   isFullEmpty(block) → collisionShape.isEmpty() (simplified; sign/pressure plate exceptions omitted)
     *   isSolid(material) → state.isOpaqueFullCube() approximation (no exact API available)
     */
    private static boolean isTunnelAhead(Level world, int i, int j, int k, int ox, int oz) {
        BlockPos one = new BlockPos(i + ox, j + 1, k + oz);
        BlockPos two = new BlockPos(i + ox, j + 2, k + oz);
        boolean emptyAtOne = world.getBlockState(one).getCollisionShape(world, one).isEmpty();
        if (!emptyAtOne) return false;
        return !world.getBlockState(two).getCollisionShape(world, two).isEmpty();
    }
}
