package choco.ratel.smartmoving.mixin.client;

import choco.ratel.smartmoving.client.SmartMovingClimber;
import choco.ratel.smartmoving.client.SmartMovingClientState;
import choco.ratel.smartmoving.client.SmartMovingFlyer;
import choco.ratel.smartmoving.client.SmartMovingJumper;
import choco.ratel.smartmoving.client.SmartMovingMover;
import choco.ratel.smartmoving.client.SmartMovingSlider;
import choco.ratel.smartmoving.client.SmartMovingSwimmer;
import choco.ratel.smartmoving.climbing.ClimbGap;
import choco.ratel.smartmoving.climbing.FeetClimbing;
import choco.ratel.smartmoving.climbing.HandsClimbing;
import choco.ratel.smartmoving.config.SmartMovingConfig;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 5-1 (Client): travel() intercept — cancel vanilla + apply SM physics during SM climbing
 * 5-2: jump() intercept — set jumpAvoided/jumpPending + cancel vanilla
 * 5-3: jumpingCooldown bypass — block cooldown trigger itself by canceling jump()
 * 5-4: jumping field @Shadow — reference when SM condition filter is applied (inside tickEssential)
 * 5-5 (Client): isClimbing() override — return false during SM climbing
 * 5-6: applyClimbingSpeed() cancel — double block x/z ±0.15F clamp during SM climbing
 * 6-2 (Client): isInSwimmingPose() override — return false during SM crawling
 */
@Mixin(LivingEntity.class)
@Environment(EnvType.CLIENT)
public abstract class MixinLivingEntityClient {

    /**
     * 5-1 (Client): travel() intercept — SM climbing physics pipeline.
     *
     * When a climbable surface (ladder/vine) is detected, vanilla travel() is canceled
     * and SM climbing physics is directly applied.
     *
     * Applied physics:
     *   - handleClimbing(): Set Y velocity + reset fallDistance
     *   - handleCeilingClimbing(): Handle ceiling climbing conditions
     *   - x/z deceleration: 0.91F factor (air resistance)
     *   - motionY floor: max(motionY, -0.15D) (prevent excessive falling)
     *
     */
    @Inject(method = "travel", at = @At("HEAD"), cancellable = true)
    private void sm_travel_client(Vec3 movementInput, CallbackInfo ci) {
        if (!((Object) this instanceof LocalPlayer player)) return;
        SmartMovingClientState sm = SmartMovingClientState.get(player);
        SmartMovingConfig cfg = SmartMovingConfig.Config;

        // Block incompatible states (original: Compat.isBlockedByIncompatibility — isSpectator + isElytraFlying)
        // 1.7.10 Compat spectator: Et Futurum Requiem only → 1.21.1 uses vanilla isSpectator()
        // 1.7.10 Compat elytra: Et Futurum Requiem IElytraPlayer → 1.21.1 uses vanilla isFallFlying()
        if (player.isSpectator() || player.isFallFlying()) return;

        // BUG-11 (Session 36): When SM disabled, travel doesn't work properly → vanilla correct.
        //   Issue: Without cfg.enabled guard, L67 vanilla flying motionY 0.6 attenuation activates → SM disabled
        //   state still weakens creative flight (50%). Also all SM branches like handleSwimming/handleLava/handleSliding/
        //   handleFlying/climbing can be entered (sm.* fields are false but calculations run uselessly).
        //   Fix: If cfg.enabled false, return immediately — vanilla travel works correctly.
        if (!cfg.enabled) return;

        // 🔴 User intent: Weeping vines / twisting vines should work as vanilla
        //   (only grab animation to be added later). Skip SM processing entirely → vanilla
        //   travel proceeds (motion clamp ±0.15 + sneak motionY=0 etc vanilla ladder climbing).
        // 🔴 User report fix (2026-05-04 — "flying through vine collision descends continuously"):
        //   During flight, skip SM processing → only vanilla travel runs → negative vy occurs (= falling).
        //   fix: Add !flying guard → during flight, proceed with SM processing → handleFlying normal flight motion.
        net.minecraft.world.level.block.state.BlockState _stateAtPos = player.level()
                .getBlockState(player.blockPosition());
        net.minecraft.world.level.block.Block _blockAtPos = _stateAtPos.getBlock();
        boolean _isVineBlock = _blockAtPos == net.minecraft.world.level.block.Blocks.WEEPING_VINES
                || _blockAtPos == net.minecraft.world.level.block.Blocks.WEEPING_VINES_PLANT
                || _blockAtPos == net.minecraft.world.level.block.Blocks.TWISTING_VINES
                || _blockAtPos == net.minecraft.world.level.block.Blocks.TWISTING_VINES_PLANT;
        if (_isVineBlock && !player.getAbilities().flying) {
            return;  // vanilla travel 본체 진행 (cancel 안 함)
        }

        // [11-4] Flying suppression — suppress vanilla creative flight motionY when SM flight disabled
        // 1.21.1: No jumpMovementFactor field → getOffGroundSpeed() method(PlayerEntity) (verified A-30)
        // C-41: getOffGroundSpeed() @HEAD Mixin returns 0.05F to suppress aerial movement
        if (player.getAbilities().flying && !cfg.fly) {
            Vec3 vel = player.getDeltaMovement();
            player.setDeltaMovement(vel.x, vel.y * 0.5999755859375D, vel.z);
        }

        // [10-1] Jump judgment — execute every tick before swimming check
        SmartMovingJumper.handleJumping(player, sm);

        // Original SmartMovingSelf.superMoveEntityWithHeading L97-L100: isSwimming/isDiving snapshot right after entry.
        // Used in handleSwimming's isFakeShallowWaterSneaking judgment (original L243) + detect fromSwimmingOrDiving transition.
        boolean wasSwimming = sm.isSwimming_sm;
        boolean wasDiving   = sm.isDiving;
        // 🔴 (2026-05-22) User report fix: Auto crawl doesn't trigger when exiting swim in 1-block space.
        //   Log measurement cause: In our mapping, dive → dipping transition doesn't occur in handleSwimming (= same frame
        //     sm.isDiving=true → false set). Next tick entry has wasDive=false → wasShortInWater=false.
        //   User scenario: isTouchingWater=true persists inside 1-block space (= vanilla box partially in water) →
        //     handleSwim=true → dipping maintained each tick. Water disappears on side, isTouchingWater=false → handleSwim=false
        //     → resetSwimming → isDipping=false. That frame's wasShortInWater=false (= previous tick swim/dive
        //     already false) → fromSwimmingOrDiving branch not entered → auto crawl doesn't trigger.
        //   Fix: Include wasDipping in wasShortInWater expression. Also include isDipping in isShortInWater (= block branch entry
        //     every frame during dipping). Extends original spec (= original 1.7.10 only isSwim||isDive).
        boolean wasDipping  = sm.isDipping;
        boolean wasShortInWater = wasSwimming || wasDiving || wasDipping;
        // 🔴 fix #155 (2026-05-22): Block locked visual after sliding rapid toggle — detect lock every tick.
        //   Log measurement: sliding rapid toggle → SlideToHeadJumping auto-transition → falling → next ground reach
        //     → box feet = ground - 1 stuck. fix #70 v4 (inside mustCrawl formula) only matches at isCrawling time →
        //     handleCrash → toCrawling call after locked cycle possible.
        //   Fix: Every tick on sm_travel_client entry, (isCrawling || isHeadJumping || isSliding) + onGround=true
        //     time detect box stuck below ground → entity.y push + velocity Y/fallDistance reset.
        //   Guard: groundTop > bbMin + 0.005 (= lock detection) + groundTop <= bbMin + 1.0 (= block 1-block ceiling match).
        if (player.onGround() && (sm.isCrawling || sm.isHeadJumping || sm.isSliding || sm.isClimbCrawling)) {
            net.minecraft.world.phys.AABB _stuckBb = player.getBoundingBox();
            double _stuckBbMin = _stuckBb.minY;
            double _stuckGround = choco.ratel.smartmoving.client.SmartMovingClientState
                    .getMaxPlayerSolidBetween(player, _stuckBbMin - 0.5, _stuckBbMin + 1.5, 0);
            if (_stuckGround > _stuckBbMin + 0.005 && _stuckGround <= _stuckBbMin + 1.0) {
                double _pushY = _stuckGround - _stuckBbMin;
                player.setPos(player.getX(), player.getY() + _pushY, player.getZ());
                player.yOld += _pushY;
                player.yo += _pushY;
                player.setDeltaMovement(player.getDeltaMovement().x, 0, player.getDeltaMovement().z);
                player.fallDistance = 0;
            }
        }
        // 🔴 fix #155 끝.

        // B-7a (Session 127): Promote original SmartMovingSelf L132 `isLiquidClimbing` local variable.
        //   isLiquidClimbing = Config.isFreeClimbingEnabled() && sp.fallDistance <= 3.0
        //                   && wantClimbUp && sp.isCollidedHorizontally && !isDiving;
        // For enabling `!isLiquidClimbing` term in updateSwimState L232 entry condition (restore in B-7c).
        sm.isLiquidClimbing = (cfg.freeClimb && cfg.enabled)
                            && player.fallDistance <= 3.0
                            && sm.wantClimbUp
                            && player.horizontalCollision
                            && !sm.isDiving;

        // [8-1] Update 3-tier swimming state every tick
        SmartMovingSwimmer.updateSwimState(player, sm);

        // [8-2] Underwater movement handling — cancel vanilla travel() if SM handles it
        if (SmartMovingSwimmer.handleSwimming(player, sm, movementInput, this.jumping,
                                               wasSwimming, wasDiving)) {
            // 🔴 (2026-05-22) User report BUG fix: isClimbing persists when releasing grab while in water.
            //   Cause: Release grab key, wantClimb=false → isLiquidClimbing=false → handleSwimming entry
            //     → ci.cancel + return → below G-01 reset (L172-, sm.isClimbing=false) never reached
            //     → next tick handleSwimming match cycle → isClimbing=true permanently remains
            //     → sm_setAngles L424 (sm.isClimbing priority) matches → sm_animateClimbing → grab pose remains.
            //   Fix: Explicitly call resetClimbing() at swim handling point (= equivalent to every tick reset inside handleClimbing).
            //   Regression prevention: resetClimbing itself is the every-tick reset infrastructure of grab climbing completion area.
            //     Both normal wall grab + ladder/vine grab cases have isClimbing=false correct before/during swim entry
            //     (= swim pose intact). reset effect = clean up persistence.
            sm.resetClimbing();
            ci.cancel();
            return;
        }

        // [8-2b] Lava movement handling (Phase C-3, focus #2.6 session 4) — handleSwimming false branch.
        //   Direct port of original SmartMovingSelf L578-L600 `handleLava` 1:1.
        //   Entry condition (original L580): !isFlying && !handledSwimming && !isLiquidClimbing && isInLava
        //   - lavaLikeWater=true (Creative): handleSwimming handles lava → don't enter here
        //   - lavaLikeWater=false (Survival): handleSwimming passes → enter here → SM own lava motion
        //   Side effects: 0 (full investigation complete): swimUpward / damage / sound / particle all outside travel()
        if (SmartMovingSwimmer.handleLava(player, sm, movementInput)) {
            ci.cancel();
            return;
        }

        // Original handleLand L648: Called under condition `!handledSwimming && !grabButton.Pressed`.
        // 1.21.1 reaches handleSwimming=false point corresponding to original `!handledSwimming` — call here.
        // grab branch (original L1354 landMotionPost) is now unified into single call point (same effect).
        sm.fromSwimmingOrDiving(player, wasShortInWater);

        // [9-4] Sliding handling — cancel vanilla travel() if SM handles it
        if (SmartMovingSlider.handleSliding(player, sm)) {
            ci.cancel();
            return;
        }

        // [10-4] Head jump landing detection — restore pose
        // 🔴 BUG fix (2026-05-08, head jump in-game verification round 1):
        //   handleJumping (L98) tryJump head branch fires → same tick same function in main guard
        //   `onGround && isHeadJumping` immediately matches → resetHeightOffset → isHeadJumping=false +
        //   restore STANDING → head jump dies after 1 tick (user report BUG: "briefly crouched
        //   then immediately stand"). vanilla travel body not yet applied, onGround=true persists race.
        //   fix: `&& sm.wasHeadJumping` — reset only when isHeadJumping was already true previous tick.
        //   Fire tick has wasHeadJumping=false so blocked. If onGround reached during head jump,
        //   tickEssential 5-AND re-evaluation + handleCrash + standupIfPossible flow handles it (B-24/B-N).
        //   Checklist Phase I found separately (duplicate with resetHeightOffset 5-AND flow) consistent — L149 itself
        //   removal deferred to separate cycle after dead code analysis.
        if (player.onGround() && sm.isHeadJumping && sm.wasHeadJumping) {
            SmartMovingJumper.resetHeightOffset(player, sm);
        }

        // [10-5] Wall jump handling (before climbing)
        // ── Wall jump state reset every tick (G-01) ─────────────────────────────────
        // Original: resetState() this.isWallJumping = false → handleWallJumping() re-sets if conditions met
        sm.isWallJumping = false;
        // Original SmartMovingSelf L2863-2897: Update canWallJumping/wallJumpCount/triggerWallJumping/
        // wantWallJumping just before handleWallJumping entry.
        SmartMovingJumper.updateWallJumpState(player, sm);
        SmartMovingJumper.handleWallJumping(player, sm);

        // ── Climbing state reset every tick (G-01) ─────────────────────────────────
        // Original: SmartMovingSelf.resetClimbing() + resetState() call (top of updateEntityActionState)
        // Prevent state remaining true after leaving climbing surface
        // 🔴 User report fix (grab climbing sneak hold doesn't work):
        //   isClimbHolding is not a reset target in original resetClimbing() (L1474-L1486) — incorrectly
        //   included in our G-01 reset → resets to false every tick → wantClimbHolding expression wch1 (`isClimbHolding && sneak`)
        //   forever false → self-holding mechanism breaks (wouldWantClimb branch 2 + wantClimbHolding branch 1).
        //   Release grab, hold doesn't work → user report BUG. Remove isClimbHolding reset = original 1:1.
        sm.isClimbing        = false;
        // 🔴 Remove isCrawlClimbing reset (user report fix — grab climbing released after just 1 tick while crouched):
        //   Original SmartMovingSelf.resetClimbing() (L1474-L1486) does not include isCrawlClimbing.
        //   Original expression (L2737) first term `(wasCrawling || isCrawlClimbing)` OR second term maintains
        //   expression self-perpetuating even after forced `wasCrawling=false` (L2749-L2753 entry edge).
        //   This is key mechanism. Reset every tick breaks self-perpetuation → released immediately after 1 tick entry.
        //   Release is handled by expression itself (naturally becomes false when all 5 conditions clear) + canStandUp auto-release (L2744) +
        //   ICC EXIT iccExitJustToCrawl guard sufficient. (2026-05-03)
        // sm.isCrawlClimbing   = false;
        sm.isCeilingClimbing = false;
        sm.isClimbJumping    = false;
        // 🔴 isClimbCrawling is updated by SmartMovingClientState L1877 main expression every tick + entry/release
        //   edge (L1880-1908) handles heightOffset. Forced G-01 reset prevents wasClimbCrawling tracking
        //   → release edge doesn't fire → heightOffset=-1 persists → combined with vanilla CROUCHING pose
        //   box persistence becomes user report "crouching" pose. Original resetClimbing (L1474-1486) also
        //   doesn't include isClimbCrawling → delete line for 1:1 match.
        // sm.isClimbCrawling   = false;
        sm.isClimbBackJumping = false;

        // [11-5] SM flight physics — 3D movement in pitch direction when isFlying && cfg.fly
        // Original: handleAlternativeFlying() → !handledSwimming && !handledLava && isFlying && Config.isFlyingEnabled()
        // Swimming/sliding already returned with ci.cancel(), so this point = conditions !handledSwimming && !handledLava met
        if (SmartMovingFlyer.handleFlying(player, sm, movementInput, this.jumping)) {
            ci.cancel();
            return;
        }

        // **Focus #4 B-4 (Session 3) — defect #2 correction**: vanilla Creative flight + sprint+jump acceleration.
        // Original SmartMovingSelf L633-L640 `handleLand` entry `!handledAlternativeFlying` branch:
        //   if (esp.movementInput.jump && Config.isSprintingEnabled() && sprintButton.Pressed
        //       && sp.capabilities.isFlying)
        //       sp.motionY += sprintFactorLevitate * sprintFactorLevitateVertical;
        //
        // 1.21.1 mapping location: After Flyer.handleFlying returns false (= SM flight inactive, vanilla
        // Creative flight possibly active). cfg.sprint && abilities.flying && jumping && sprint key.
        // Apply every tick — accelerate motionY while jump key held (default 1.5 * 0.185 = 0.2775F).
        if (cfg.sprint && this.jumping
                && net.minecraft.client.Minecraft.getInstance().options.keySprint.isDown()
                && player.getAbilities().flying) {
            net.minecraft.world.phys.Vec3 _v = player.getDeltaMovement();
            double _boost = cfg.sprintFactorLevitate * cfg.sprintFactorLevitateVertical;
            player.setDeltaMovement(_v.x, _v.y + _boost, _v.z);
        }

        // [5-1] Climbing handling
        Level world = player.level();
        boolean isSmall = sm.isSmall || sm.isCrawling;

        HandsClimbing[] hands = {HandsClimbing.NONE};
        FeetClimbing[]  feet  = {FeetClimbing.NONE};
        ClimbGap[] handsGap   = {new ClimbGap()};
        ClimbGap[] feetGap    = {new ClimbGap()};
        boolean[] handsVine = {false};
        boolean[] feetVine  = {false};
        SmartMovingClimber.getOnLadderOrVine(player, world, isSmall, false, hands, feet, handsGap, feetGap, handsVine, feetVine);

        // R-01: Save climbing type for State packet encoding
        sm.actualHandsClimbType = hands[0].ordinal();
        sm.actualFeetClimbType  = feet[0].ordinal();
        sm.isHandsVineClimbing  = handsVine[0];
        sm.isFeetVineClimbing   = feetVine[0];

        boolean onClimbable = hands[0].isRelevant() || feet[0].isRelevant();
        // 🔴 (2026-04-27) Free Climb (normal wall grab) entry gate — user report
        //   core cause of "grab key climbing doesn't work".
        //   Original SmartMovingSelf L657 unconditionally calls handleClimbing. Inside it,
        //   8-direction seekClimbGap check detects normal walls → updates handsClimbing/feetClimbing.
        //   We only guard onClimbable (adjacent ladder/vine) so can't enter on normal wall.
        //   sm.wantClimb is updated every tick in tickEssential L1262 as `freeClimb && enabled && wouldWantClimb`
        //   (= grab key or auto ladder/vine check). Adding it allows handleClimbing entry on normal walls →
        //   8-direction seekClimbGap check inside fires → Free Climb works normally.
        boolean wantFreeClimb = sm.wantClimb;
        if (!onClimbable && !sm.isCeilingClimbing && !wantFreeClimb) {
            // 🔴 (2026-04-28) Land branch — same pattern as flying (cancel vanilla travel entirely + SM own expression).
            //   Original SmartMovingSelf.handleLand + landMotion + setLandMotions 1:1 mapping.
            //   User intent "1:1 like flying": Fundamentally solve vanilla flow + partial inject multiplication accumulation problem.
            SmartMovingMover.handleLand(player, sm, movementInput);
            ci.cancel();
            return;
        }

        if (onClimbable || wantFreeClimb) SmartMovingClimber.handleClimbing(player, sm);
        SmartMovingClimber.handleCeilingClimbing(player, sm);

        // If climbing doesn't trigger, attempt land branch.
        // 🔴 Add onClimbable guard (user report: while climbing ladder, rotate mouse or sneak hold +
        //   rotate mouse → suddenly falls). Cause: When wantClimbUp/Down both false in handleClimbing,
        //   isClimbing not updated → this branch calls handleLand → free fall.
        //   Original SmartMovingSelf.landMotion (L757-794) ladder/vine guard independent of isClimbing
        //   `if (isOnLadder || isOnVine)` position-based → descends slowly (motion clamp + vertical
        //   clamp -0.15*factor + sneak motionY=0). Our mapping has equivalent guard in L342-363 onClimbable branch.
        //   So if onClimbable=true block handleLand and proceed with that branch.
        if (!sm.isClimbing && !sm.isCeilingClimbing && !onClimbable) {
            SmartMovingMover.handleLand(player, sm, movementInput);
            ci.cancel();
            return;
        }

        // 🔴 (2026-04-28) Precise 1:1 mapping of original SmartMovingSelf.handleLand + landMotion.
        //   Original flow:
        //     1. landMotion (L675-810): Determine horizontalDamping + moveFlying (movementInput → motion).
        //     2. move (L655): vanilla move() — update position.
        //     3. handleClimbing (L657): Set motionY anew.
        //     4. handleCeilingClimbing.
        //     5. setLandMotions (L659): motionY -= 0.08, *= 0.98, horizontalDamping.
        //
        //   We have HEAD inject so order changes:
        //     a. handleClimbing (set motionY) — already called.
        //     b. movementInput → motion (equivalent to original L718 moveFlying).
        //     c. On ladder/vine motion clamp -0.15 ~ 0.15 (original L757-775).
        //     d. When notTotalFreeClimbing fallDistance=0 + vertical clamp (original L776-781).
        //     e. On sneak motionY=0 (original L782-794).
        //     f. setLandMotions (original L1176-1182): motionY -= 0.08, *= 0.98, horizontal *= 0.91.
        //     g. player.move (equivalent to original L655).

        // b. 🔴 (2026-04-28) 1:1 of original L718 `sp.moveFlying(strafe, forward, rawSpeed * speedFactor)`.
        //   Existing hardcoded `0.1F` is vanilla 1.21.1 walk speed. User report "faster than original".
        //   Original expression: rawSpeed * speedFactor.
        //     rawSpeed = onGround ? 0.1 * f3 : jumpMovementFactor / sprintDiv.
        //     speedFactor = configFactor * potionFactor * nonSlowFactor * slowFactor.
        //     + landMotion runFactor (isRunning && !isFast).
        //     + when !onGround /= potionFactor.
        //
        //   Same expression as handleLand.
        SmartMovingConfig cfg2 = SmartMovingConfig.Config;
        float climbSpeedFactor = SmartMovingMover.getConfigSpeedFactor(player, cfg2)
                * SmartMovingMover.getPotionSpeedFactor(player)
                * SmartMovingMover.getNonSlowInputSpeedFactor(player, sm, cfg2);
        float climbSlowFactor = SmartMovingMover.getSlowInputSpeedFactor(player, sm, cfg2);
        if (sm.vanilla()) {
            // vanilla mode 시 movementInput 자체 곱 — 본 매핑 무관 (cfg.enabled 일 때 vanilla=false).
        } else {
            climbSpeedFactor *= climbSlowFactor;
        }
        // Determine damping/rawSpeed.
        float climbDamping;
        if (player.onGround() && (!sm.isJumping || sm.vanilla())) {
            net.minecraft.world.level.block.state.BlockState below = player.level().getBlockState(player.getOnPos());
            float slip = below.getBlock().getFriction();
            climbDamping = slip > 0F ? slip * 0.91F : 0.546F;
        } else {
            climbDamping = 0.91F;
        }
        float climbF3 = 0.1627714F / (climbDamping * climbDamping * climbDamping);
        float climbRawSpeed;
        if (player.onGround()) {
            climbRawSpeed = 0.1F * climbF3;
        } else {
            float jumpMovementFactor = 0.02F;
            climbRawSpeed = jumpMovementFactor / (player.isSprinting() && !player.getAbilities().flying ? 1.3F : 1F);
        }
        // Phase F 1:1 correction: Remove jumpControlFactor / headJumpControlFactor application branch.
        //   Original SmartMovingSelf L688-L719 flow is if/else chain:
        //     if (isClimbing && climbingUpIsBlockedBy*) { /* climb handling, factor not applied */ }
        //     else if (!isSliding) { /* land handling, factor applied (L703-L706) */ }
        //   So jump Control/head Jump ControlFactor not applied in climbing branch.
        //   Applying at this climbMotion location violates original. wouldWantClimb expression (`!isHeadJumping` guard)
        //   blocks climbing entry during head jump making it dead code, but explicit 1:1 match.
        // runFactor (isRunning && !isFast).
        if (cfg2.run && SmartMovingMover.isRunning(player, sm, cfg2) && !sm.isFast) {
            climbSpeedFactor *= cfg2.runFactor;
        }
        // Normalize potionFactor when !onGround.
        if (!player.onGround()) {
            float potion = SmartMovingMover.getPotionSpeedFactor(player);
            if (potion > 0F) climbSpeedFactor /= potion;
        }
        player.moveRelative(climbRawSpeed * climbSpeedFactor, movementInput);

        // c. Original L757-775: ladder/vine motion clamp -0.15 ~ 0.15.
        //   Apply only when onClimbable=true (adjacent ladder/vine). Don't apply to free climb normal walls.
        if (onClimbable) {
            Vec3 v = player.getDeltaMovement();
            double clampH = 0.15D;
            double mx = Math.max(-clampH, Math.min(clampH, v.x));
            double mz = Math.max(-clampH, Math.min(clampH, v.z));
            if (mx != v.x || mz != v.z) {
                player.setDeltaMovement(mx, v.y, mz);
            }
        }

        // d. fallDistance reset (prevent fall damage while hanging on ladder/vine)
        if (onClimbable) {
            player.fallDistance = 0;
        }

        // f. Original setLandMotions L1176-1182:
        //   sp.motionY -= 0.08;  sp.motionY *= 0.98;
        //   sp.motionX *= horizontalDamping;  (= 0.91F air damping)
        //   sp.motionZ *= horizontalDamping;
        Vec3 vel = player.getDeltaMovement();
        double newY = (vel.y - 0.08D) * 0.98D;
        player.setDeltaMovement(vel.x * 0.91F, newY, vel.z * 0.91F);

        // 🔴 Ladder/vine motion guard — 1:1 original L757-794 (landMotion).
        //   Original flow: landMotion (motion guard) → move (player.move) → setLandMotions.
        //   Our flow: setLandMotions → player.move (reversed order). So motion guard applied
        //   after setLandMotions and before player.move → guarantee motion at player.move time.
        //
        //   Guard 1 (L757-775): motionX/Z ±0.15 clamp.
        //   Guard 2 (L779-780): fallDistance=0 + motionY = max(motionY, -0.15*factor).
        //   Guard 3 (L782-794): sneak motionY=0 (prevent falling).
        //
        // 🔴 Add ICC (user report — grab climbing + sneak vibration/jitter BUG fix):
        //   When ICC=true box = (entity.y+1, entity.y+1.8) (mixin offset applied). Box above ladder
        //   area (after passing ladder end) reaches when onClimbable=false (ladder not recognized) →
        //   existing guard not entered → motion negatively accumulates after setLandMotions → box falls → ICC vibration.
        //   Original 1.7.10 handles vanilla ladder grip itself (motion clamp -0.15, sneak my=0) →
        //   prevents box falling. Our mapping cancels vanilla travel with sm_travel_client so
        //   vanilla grip doesn't work → need direct mapping.
        if (onClimbable || sm.isClimbCrawling) {
            Vec3 v = player.getDeltaMovement();
            double clampH = 0.15D;
            double mx = Math.max(-clampH, Math.min(clampH, v.x));
            double mz = Math.max(-clampH, Math.min(clampH, v.z));
            double my = v.y;

            boolean sneakKeyPressed = net.minecraft.client.Minecraft.getInstance()
                    .options.keyShift.isDown();
            if (sneakKeyPressed && my < 0) {
                my = 0;
            } else {
                double clampFactor = -0.15D * SmartMovingMover.getCombinedSpeedFactor(
                        player, SmartMovingConfig.Config);
                if (my < clampFactor) my = clampFactor;
            }

            if (mx != v.x || mz != v.z || my != v.y) {
                player.setDeltaMovement(mx, my, mz);
            }
            player.fallDistance = 0;
        }

        // g. Original L655 equivalent: update position by calling vanilla move().
        player.move(MoverType.SELF, player.getDeltaMovement());

        ci.cancel();
    }

    /**
     * 5-4: LivingEntity.jumping (field_6282).
     * Force set this field to false when SM condition filter is applied.
     * Check conditions in sm_jumpingFilter(), then set false.
     */
    @Shadow protected boolean jumping;

    /**
     * 5-4: jumping field suppression — SM state condition filter.
     * Original: SmartMovingSelf.updateEntityActionState() isp.setIsJumpingField() logic.
     *
     * Suppression conditions:
     *   isCrawling || isSliding || isHeadJumping || jumpCharge>0 || blockJumpTillButtonRelease
     *
     * Execute in LivingEntity.tickMovement() HEAD → definitely set before jump() call inside travel().
     */
    @Inject(method = "aiStep", at = @At("HEAD"))
    private void sm_jumpingFilter(CallbackInfo ci) {
        if (!((Object) this instanceof LocalPlayer player)) return;
        // BUG-18 (Session 36): When SM disabled, don't filter jumping → preserve vanilla jumping.
        //   Issue: When sm.* itself disabled it's false so little impact, but adds explicitness +
        //   blocks possible jumpCharge/blockJumpTillButtonRelease persistence (resetState resets but safety net).
        if (!SmartMovingConfig.Config.enabled) return;
        SmartMovingClientState sm = SmartMovingClientState.get(player);
        if (sm.isCrawling || sm.isSliding || sm.isHeadJumping
                || sm.jumpCharge > 0 || sm.blockJumpTillButtonRelease) {
            this.jumping = false;
        }
        // 🔴 Missing correction (Flying Phase F-6 / Session 40): Reset fallDistance during flight.
        //   Original SmartMovingSelf L1803-L1804: `if (sp.capabilities.isFlying) sp.fallDistance = 0F;`
        //   1.21.1 mapping absent → accumulated fallDistance after flight ends can cause fall damage on landing.
        if (player.getAbilities().flying) {
            player.fallDistance = 0F;
        }
    }


    /**
     * 5-2 + 5-3: jump() intercept.
     *
     * Works only for ClientPlayerEntity.
     * SM blocks vanilla jump() and sets jumpPending flag.
     * Actual jump handling is performed in tickEssential() → handleJumping() → tryJump().
     *
     * Side effects of blocking vanilla jump():
     *   - addExhaustion(0.05F * 2): Replaced with SM own exhaustion system
     *   - Stats.JUMP statistic: Recorded separately in SM tryJump()
     *   - Sprint jump +0.2F: Self-calculated in SM tryJump()
     *   - jumpingCooldown=10 (5-3): Automatically blocked by not executing jump()
     */
    /**
     * 🔴 (Session 145 BUG-sprint): Block vanilla sprint effect when SM small state like crawl/slide.
     *
     * vanilla `LivingEntity.setSprinting(true)` performs two tasks:
     *   1. super.Entity.setSprinting → set SPRINTING flag.
     *   2. Add SPRINTING_SPEED_BOOST modifier to GENERIC_MOVEMENT_SPEED attribute (1.3x).
     * Only Entity.setSprinting inject cannot block attribute modifier addition (#2) →
     * Sprint speed effect activates as-is (user report "sprint activates while crouched").
     *
     * 1:1 mapping of original SmartMovingSelf L2700 `setSprinting(isStandupSprintingOrRunning())`.
     * Effect of applying `isStandupSprintingOrRunning() = ... && !isCrawling` guard (L3236) = block
     * setSprinting(true) call itself during crawl → SPRINTING flag false + 1.3x modifier not added.
     *
     * 1.21.1 vanilla automatically calls `setSprinting(true)` every tick (when sprint key held) →
     * Our SM's edge set `setSprinting(false)` (SmartMovingClientState L1518) becomes ineffective.
     * This inject blocks vanilla automatic call itself achieving original 1:1 effect.
     */
    @Inject(method = "setSprinting", at = @At("HEAD"), cancellable = true)
    private void sm_blockVanillaSprintInSmStates(boolean sprinting, CallbackInfo ci) {
        if (!((Object) this instanceof LocalPlayer player)) return;
        if (!SmartMovingConfig.Config.enabled) return;
        if (!sprinting) return;  // setSprinting(false) 는 항상 통과.
        SmartMovingClientState sm = SmartMovingClientState.get(player);
        if (sm.isCrawling || sm.isSliding || sm.isHeadJumping
                || sm.isSwimming_sm || sm.isDiving) {
            ci.cancel();
        }
    }

    @Inject(method = "jumpFromGround", at = @At("HEAD"), cancellable = true)
    private void sm_jump(CallbackInfo ci) {
        if (!((Object) this instanceof LocalPlayer player)) return;
        // 🔴 BUG-17 (Session 36): When SM disabled, don't block vanilla jump() → vanilla jump works normally.
        //   Issue: Missing cfg.enabled guard → even when SM disabled, ci.cancel() completely blocks vanilla jump
        //   + sets jumpAvoided/jumpPending → jump itself doesn't work (direct user report cause).
        //   Fix: When cfg.enabled false, return immediately → vanilla jump not canceled → works normally.
        if (!SmartMovingConfig.Config.enabled) return;
        // 🔴 BUG-28 correction (Flying Phase / Session 42): vanilla 1.21.1 ClientPlayerEntity.tickMovement
        //   Decompiled L791-L800 — when entering double-tap fly with isOnGround=true, directly calls this.jump()
        //   (1.21.1 specific code). Not present in original 1.7.10 EntityPlayerSP.
        //   Issue: Missing guard → on first flight entry (double-tap from ground), sm_jump fires → jumpAvoided=true →
        //   handleJumping tryJump(UP) → motionY=0.42 + every tick vanilla flight boost +0.15 +
        //   SM handleFlying = infinite ascent on first flight (direct cause of user BUG-28).
        //   Original 1.7.10 has no vanilla jump() call = flying guard not needed in handleJumping.
        //   Fix: Compensate 1.21.1 difference — skip sm_jump processing during vanilla flight → 1:1 original behavior.
        if (player.getAbilities().flying) return;
        SmartMovingClientState sm = SmartMovingClientState.get(player);
        sm.jumpAvoided = true;
        sm.jumpPending = true;
        ci.cancel();
    }

    /**
     * 5-5 (Client): Force isClimbing() = false during SM custom climbing.
     *
     * Purpose 1: Block applyClimbingSpeed() x/z ±0.15F clamp
     * Purpose 2: Block forcing y=0.2 in applyMovementInput() when isClimbing()
     *
     * Check ClientPlayerEntity instance → judge based on SmartMovingClientState status.
     * Equivalent server-side logic → MixinLivingEntity.sm_isClimbing_server()
     */
    @Inject(method = "onClimbable", at = @At("HEAD"), cancellable = true)
    private void sm_isClimbing_client(CallbackInfoReturnable<Boolean> cir) {
        if (!((Object) this instanceof LocalPlayer player)) return;
        // BUG-23 (Session 36): When SM disabled, vanilla isClimbing as-is — block sm.* persistence timing.
        if (!SmartMovingConfig.Config.enabled) return;
        SmartMovingClientState sm = SmartMovingClientState.get(player);
        if (sm.isClimbing || sm.isCrawlClimbing || sm.isCeilingClimbing) {
            cir.setReturnValue(false);
            return;
        }
        // 🔴 User report fix (2026-05-04 — "don't climb weeping/twisting vines while flying"):
        //   Above vine block while flying = vanilla isClimbing()=true → apply climbing clamp ±0.15 in travel
        //   → limit flight speed. User intent = flight freedom.
        //   fix: Force isClimbing()=false when isFlying + vine block → skip clamp → flight normal.
        if (player.getAbilities().flying) {
            net.minecraft.world.level.block.Block blockAtPos = player.level()
                    .getBlockState(player.blockPosition()).getBlock();
            if (blockAtPos == net.minecraft.world.level.block.Blocks.WEEPING_VINES
                    || blockAtPos == net.minecraft.world.level.block.Blocks.WEEPING_VINES_PLANT
                    || blockAtPos == net.minecraft.world.level.block.Blocks.TWISTING_VINES
                    || blockAtPos == net.minecraft.world.level.block.Blocks.TWISTING_VINES_PLANT) {
                cir.setReturnValue(false);
            }
        }
    }


    /**
     * 5-6: Double block applyClimbingSpeed() — return Vec3 as-is during SM climbing.
     * Yarn: applyClimbingSpeed (intermediary: method_18801 verification complete)
     *
     * 5-5 (isClimbing=false) already blocks clamp inside applyClimbingSpeed, but
     * double defense against path where applyClimbingSpeed directly calls bypassing isClimbing().
     */
    @Inject(method = "handleOnClimbable", at = @At("HEAD"), cancellable = true)
    private void sm_applyClimbingSpeed(Vec3 velocity, CallbackInfoReturnable<Vec3> cir) {
        if (!((Object) this instanceof LocalPlayer player)) return;
        // BUG-23 (Session 36): When SM disabled, vanilla applyClimbingSpeed as-is — block sm.* persistence.
        if (!SmartMovingConfig.Config.enabled) return;
        SmartMovingClientState sm = SmartMovingClientState.get(player);
        if (sm.isClimbing || sm.isCrawlClimbing || sm.isCeilingClimbing) {
            cir.setReturnValue(velocity);
            return;
        }
        // 🔴 User report fix (2026-05-04 — "automatic vine climbing + clamp while flying"):
        //   Block vanilla clamp ±0.15 when isFlying + vine block (= double defense against isClimbing mixin bypass path).
        //   Return velocity as-is → maintain flight speed.
        if (player.getAbilities().flying) {
            net.minecraft.world.level.block.Block blockAtPos = player.level()
                    .getBlockState(player.blockPosition()).getBlock();
            if (blockAtPos == net.minecraft.world.level.block.Blocks.WEEPING_VINES
                    || blockAtPos == net.minecraft.world.level.block.Blocks.WEEPING_VINES_PLANT
                    || blockAtPos == net.minecraft.world.level.block.Blocks.TWISTING_VINES
                    || blockAtPos == net.minecraft.world.level.block.Blocks.TWISTING_VINES_PLANT) {
                cir.setReturnValue(velocity);
            }
        }
    }

    /**
     * 9-3: Suppress LimbAnimator update during crawling/sliding.
     * vanilla updateLimbs() updates LimbAnimator by walk progress speed,
     * but executing as-is during crawling/sliding pose causes abnormal leg animation playback.
     */
    @Inject(method = "calculateEntityAnimation(Z)V", at = @At("HEAD"), cancellable = true)
    private void sm_updateLimbs_client(boolean serverSide, CallbackInfo ci) {
        if (!((Object) this instanceof LocalPlayer player)) return;
        // BUG-23 (Session 36): When SM disabled, vanilla updateLimbs as-is — block sm.* persistence.
        if (!SmartMovingConfig.Config.enabled) return;
        SmartMovingClientState sm = SmartMovingClientState.get(player);
        if (sm.isCrawling || sm.isSliding) {
            ci.cancel();
        }
    }

    /**
     * HorizontalAerodynamicDamping — horizontal deceleration correction right after slide→head jump transition.
     * Original: SmartMovingBase.landMotion() (lines 751-755) — when isAerodynamic && !onGround && !isSliding
     *       motionX/Z *= HorizontalAerodynamicDamping(0.999F)
     *
     * 1.21.1: Inserted after vanilla air resistance 0.91F in travel().
     * When isAerodynamic=true reverse vanilla's 0.91F and apply 0.999F.
     * correction factor = 0.999F / 0.91F
     */
    @Inject(method = "travel", at = @At("TAIL"))
    private void sm_aerodynamicDamping(Vec3 movementInput, CallbackInfo ci) {
        // 🔴 (2026-04-28) Temporarily disabled. Map head jump separately after land + jump action verification.
    }

    /**
     * B-2 (Session 26): Port Land movement user factor. Original `SmartMovingSelf.java` L119
     *   `speedFactor = getConfigSpeedFactor() * getPotionSpeedFactor() * ...`
     * Inject `getConfigSpeedFactor()` part into Land path processed by vanilla travel().
     *
     * **Option A (Mixin inject on getMovementSpeed)** chosen: vanilla `LivingEntity.
     * getMovementSpeed()` HEAD inject multiply return value by `getConfigSpeedFactor(player, cfg)`.
     *
     * Application scope:
     *   - Land (walk/run/sprint/jump) — vanilla travel() references getMovementSpeed() → impact
     *   - Flying/swimming/diving/climbing — SM cancels vanilla travel() in `sm_travel_client`
     *     → getMovementSpeed not called → 0 impact (each path applies getCombinedSpeedFactor separately)
     *
     * Creative gate handled by `Mover.getConfigSpeedFactor` internally with `isCreative()` —
     *   If not Creative then userSpeedFactor=1F → only speedFactor(default 1F) reflected. No felt change.
     *
     * ⚠️ Double application caution: SM paths (Swimmer/Climber/Flyer) read
     *   GENERIC_MOVEMENT_SPEED attribute separately in getPotionSpeedFactor. This mixin only
     *   changes getMovementSpeed return value, but getPotionSpeedFactor reads attribute directly, so no double multiply. OK.
     *
     * ClientPlayerEntity only — server players handled by server-side travel (client→server sync).
     */
    @Inject(method = "getSpeed", at = @At("HEAD"), cancellable = true)
    private void sm_getMovementSpeed(CallbackInfoReturnable<Float> cir) {
        // 🔴 (2026-04-28) Disabled — handleLand cancels vanilla travel + applies own expression
        //   so no getMovementSpeed call path in vanilla travel.
        //   When other subsystems (animation/footstep/network) call, sprint 1.154x side effect
        //   → possibility of feeling slightly faster than original. handleLand directly applies sprintFactor so
        //   additional multiply here risks double application.
    }

    /**
     * 6-2 (Client): Force isInSwimmingPose() = false during SM crawling.
     *
     * SM crawling uses SWIMMING pose, but if vanilla isInSwimmingPose()=true:
     *   - setupTransforms Branch 2: X-90° rotation + translate(0,-1,0.3) → render broken
     *   - updateLeaningPitch(): leaningPitch rises to 1.0 → automatic rotation occurs
     *   - animateModel(): reflect model.leaningPitch → pitch distorted before setAngles
     *
     * Client side: Check ClientPlayerEntity instance.
     * Server side → MixinLivingEntity.sm_isInSwimmingPose_server()
     * Yarn: isInSwimmingPose (intermediary: method_20232 verification complete)
     */
    @Inject(method = "isVisuallySwimming", at = @At("HEAD"), cancellable = true)
    private void sm_isInSwimmingPose_client(CallbackInfoReturnable<Boolean> cir) {
        // 🔴 (Phase 2 multi BUG-2/2.5) ClientPlayerEntity guard → AbstractClientPlayerEntity.
        //   Issue: self only → remote applies vanilla swimming pose → 90° bent + orphan SWIMMING.
        //   Change: handle all players. sm instance = entity uuid based.
        if (!((Object) this instanceof net.minecraft.client.player.AbstractClientPlayer player)) return;
        // BUG-23 (Session 36): When SM disabled, vanilla isInSwimmingPose as-is — block sm.* persistence.
        // 🔴 (Phase 2 fix-3-2) cfg.enabled → isSmRenderEnabled for self-only (BUG-CONFIG-2 consistent).
        if (!choco.ratel.smartmoving.client.SmartMovingClient.isSmRenderEnabled(player)) return;
        SmartMovingClientState sm = SmartMovingClientState.get(player);
        // [6-2][8-3] Block setupTransforms Branch 2 entry during crawling/swimming/diving/crawl climbing.
        // isCrawling/isCrawlClimbing: Use SWIMMING pose but prevent vanilla -90° automatic rotation
        // isSwimming_sm/isDiving: Prevent conflict with SM own body X lean angle animation
        if (sm.isCrawling || sm.isCrawlClimbing || sm.isSwimming_sm || sm.isDiving) {
            cir.setReturnValue(false);
            return;
        }
        // 🔴 (2026-05-04 user report — "brief swim animation when sliding → standing"):
        //   Cause: 1 frame of server→client pose sync lag after SM crawl ends and standing begins,
        //         pose=SWIMMING persists (despite all SM swim states false). vanilla
        //         updateLeaningPitch sees isInSwimmingPose=true → leaningPitch +=0.09.
        //         Over next 6~7 frames lerp down while vanilla setupTransforms applies -90° X
        //         rotation lerp → brief swim pose visible.
        //   Fix: SM enabled + pose=SWIMMING + all SM swim branches false = orphan pose.
        //        Force isInSwimmingPose=false → block leaningPitch +=0.09 → no accumulation.
        if (player.getPose() == net.minecraft.world.entity.Pose.SWIMMING) {
            cir.setReturnValue(false);
        }
    }

    /**
     * 🔴 User report fix (2026-05-04 — "brief SWIMMING model visible when re-entering 1-block space"):
     *   Cause: vanilla PlayerEntityRenderer.setupTransforms L261-L282
     *     `m = lerp(leaningPitch, 0, -90); matrices.rotate(POSITIVE_X(m));` →
     *     when leaningPitch>0 model X rotates. Adds to our SM crawl R_x(-78.75°) mapping.
     *   leaningPitch field lerps every tick in LivingEntity.updateLeaningPitch.
     *     SM mixin forces isInSwimmingPose=false so -= 0.09 → decreases to 0. But on game
     *     re-entry before first client tick (= right after server sync) entity leaningPitch
     *     may already have some value persisting (= synced accumulated server tick value).
     *   fix: Force getLeaningPitch HEAD inject to 0 when SM crawl active → setupTransforms
     *     R_x(0) → no rotation effect. Apply to all SM states.
     */
    @Inject(method = "getSwimAmount", at = @At("HEAD"), cancellable = true)
    private void sm_getLeaningPitch(float tickDelta, CallbackInfoReturnable<Float> cir) {
        // 🔴 (Phase 2 multi BUG-2/2.5) ClientPlayerEntity guard → AbstractClientPlayerEntity.
        if (!((Object) this instanceof net.minecraft.client.player.AbstractClientPlayer player)) return;
        if (!choco.ratel.smartmoving.client.SmartMovingClient.isSmRenderEnabled(player)) return;
        SmartMovingClientState sm = SmartMovingClientState.get(player);
        if (sm.isCrawling || sm.isCrawlClimbing || sm.isSwimming_sm || sm.isDiving
                || sm.isHeadJumping || sm.isSliding) {
            cir.setReturnValue(0F);
            return;
        }
        // 🔴 Orphan SWIMMING pose guard (same as sm_isInSwimmingPose_client):
        //   pose=SWIMMING but all SM swim branches false → leaningPitch field may persist →
        //   block swim rotation lerp in setupTransforms.
        if (player.getPose() == net.minecraft.world.entity.Pose.SWIMMING) {
            cir.setReturnValue(0F);
        }
    }

}
