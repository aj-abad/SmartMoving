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
        // damping/rawSpeed 결정.
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
        // Phase F 1:1 정정: jumpControlFactor / headJumpControlFactor 적용 분기 제거.
        //   원본 SmartMovingSelf L688-L719 흐름은 if/else 체인:
        //     if (isClimbing && climbingUpIsBlockedBy*) { /* climb 처리, factor 미적용 */ }
        //     else if (!isSliding) { /* land 처리, factor 적용 (L703-L706) */ }
        //   즉 등반 분기에서는 jumpControlFactor / headJumpControlFactor 미적용.
        //   이 climbMotion 위치에 적용은 원본 위반. wouldWantClimb 식 (`!isHeadJumping` 가드)
        //   로 헤드점프 중 등반 진입 차단되어 dead code 였으나 1:1 정합 명시.
        // runFactor (isRunning && !isFast).
        if (cfg2.run && SmartMovingMover.isRunning(player, sm, cfg2) && !sm.isFast) {
            climbSpeedFactor *= cfg2.runFactor;
        }
        // !onGround 시 potionFactor 정상화.
        if (!player.onGround()) {
            float potion = SmartMovingMover.getPotionSpeedFactor(player);
            if (potion > 0F) climbSpeedFactor /= potion;
        }
        player.moveRelative(climbRawSpeed * climbSpeedFactor, movementInput);

        // c. 원본 L757-775: ladder/vine 시 motion clamp -0.15 ~ 0.15.
        //   onClimbable=true (사다리/덩굴 인접) 시만 적용. free climb 일반 벽엔 미적용.
        if (onClimbable) {
            Vec3 v = player.getDeltaMovement();
            double clampH = 0.15D;
            double mx = Math.max(-clampH, Math.min(clampH, v.x));
            double mz = Math.max(-clampH, Math.min(clampH, v.z));
            if (mx != v.x || mz != v.z) {
                player.setDeltaMovement(mx, v.y, mz);
            }
        }

        // d. fallDistance reset (사다리/덩굴 매달림 시 낙하 데미지 방지)
        if (onClimbable) {
            player.fallDistance = 0;
        }

        // f. 원본 setLandMotions L1176-1182:
        //   sp.motionY -= 0.08;  sp.motionY *= 0.98;
        //   sp.motionX *= horizontalDamping;  (= 0.91F air damping)
        //   sp.motionZ *= horizontalDamping;
        Vec3 vel = player.getDeltaMovement();
        double newY = (vel.y - 0.08D) * 0.98D;
        player.setDeltaMovement(vel.x * 0.91F, newY, vel.z * 0.91F);

        // 🔴 사다리/덩굴 motion 가드 — 원본 L757-794 (landMotion) 1:1.
        //   원본 흐름: landMotion (motion 가드) → move (player.move) → setLandMotions.
        //   우리 흐름: setLandMotions → player.move (역순). 따라서 motion 가드는
        //   setLandMotions 후 player.move 전 위치에서 적용 → player.move 시점 motion 보장.
        //
        //   가드 1 (L757-775): motionX/Z ±0.15 clamp.
        //   가드 2 (L779-780): fallDistance=0 + motionY = max(motionY, -0.15*factor).
        //   가드 3 (L782-794): sneak 시 motionY=0 (떨어지지 않게).
        //
        // 🔴 ICC 추가 (사용자 보고 — 그랩 클라이밍 + sneak 진동/덜컹 BUG fix):
        //   ICC=true 시 박스 = (entity.y+1, entity.y+1.8) (mixin offset 적용). 박스가 사다리
        //   영역 위 (사다리 끝 통과 후) 도달 시 onClimbable=false (사다리 인접 인식 X) →
        //   기존 가드 미진입 → setLandMotions 후 motion 음수 누적 → 박스 떨어짐 → ICC 진동.
        //   원본 1.7.10 은 vanilla 사다리 grip (motion clamp -0.15, sneak my=0) 자체 처리 →
        //   박스 떨어짐 차단. 우리 매핑은 sm_travel_client 가 vanilla travel cancel 하므로
        //   vanilla grip 미작동 → 직접 매핑 필요.
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

        // g. 원본 L655 등가: vanilla move() 호출로 위치 갱신.
        player.move(MoverType.SELF, player.getDeltaMovement());

        ci.cancel();
    }

    /**
     * 5-4: LivingEntity.jumping (field_6282).
     * SM 조건 필터가 적용될 때 이 필드를 false로 강제 설정한다.
     * sm_jumpingFilter()에서 조건 체크 후 false 설정.
     */
    @Shadow protected boolean jumping;

    /**
     * 5-4: jumping 필드 억제 — SM 상태 조건 필터.
     * 원본: SmartMovingSelf.updateEntityActionState() isp.setIsJumpingField() 로직.
     *
     * 억제 조건:
     *   isCrawling || isSliding || isHeadJumping || jumpCharge>0 || blockJumpTillButtonRelease
     *
     * LivingEntity.tickMovement() HEAD에서 실행 → travel() 내 jump() 호출 전에 확실히 세팅됨.
     */
    @Inject(method = "aiStep", at = @At("HEAD"))
    private void sm_jumpingFilter(CallbackInfo ci) {
        if (!((Object) this instanceof LocalPlayer player)) return;
        // BUG-18 (세션 36): SM disabled 시 jumping 필터 안 함 → vanilla jumping 그대로 보존.
        //   기존: sm.* 자체가 disabled 시 false 라 영향 거의 없으나 명시성 + jumpCharge/
        //   blockJumpTillButtonRelease 잔존 가능성 차단 (resetState 가 reset 하지만 안전망).
        if (!SmartMovingConfig.Config.enabled) return;
        SmartMovingClientState sm = SmartMovingClientState.get(player);
        if (sm.isCrawling || sm.isSliding || sm.isHeadJumping
                || sm.jumpCharge > 0 || sm.blockJumpTillButtonRelease) {
            this.jumping = false;
        }
        // 🔴 누락 정정 (Flying Phase F-6 / 세션 40): 비행 중 fallDistance reset.
        //   원본 SmartMovingSelf L1803-L1804: `if (sp.capabilities.isFlying) sp.fallDistance = 0F;`
        //   1.21.1 매핑 부재 → 비행 종료 직후 착지 시 누적 fallDistance 로 낙하 데미지 가능.
        if (player.getAbilities().flying) {
            player.fallDistance = 0F;
        }
    }


    /**
     * 5-2 + 5-3: jump() 인터셉트.
     *
     * ClientPlayerEntity에 대해서만 동작한다.
     * SM이 vanilla jump()를 차단하고 jumpPending 플래그를 세팅한다.
     * 실제 점프 처리는 tickEssential() → handleJumping() → tryJump()에서 수행된다.
     *
     * vanilla jump() 차단 부작용:
     *   - addExhaustion(0.05F * 2): SM 자체 소진 시스템으로 대체
     *   - Stats.JUMP 통계: SM tryJump()에서 별도 기록
     *   - 스프린트 점프 +0.2F: SM tryJump()에서 자체 계산
     *   - jumpingCooldown=10 (5-3): jump() 미실행으로 자동 차단
     */
    /**
     * 🔴 (세션 145 BUG-sprint): 크롤/슬라이드 등 SM small state 시 vanilla sprint 효과 차단.
     *
     * vanilla `LivingEntity.setSprinting(true)` 가 두 작업 수행:
     *   1. super.Entity.setSprinting → SPRINTING flag set.
     *   2. GENERIC_MOVEMENT_SPEED attribute 에 SPRINTING_SPEED_BOOST modifier 추가 (1.3x).
     * Entity.setSprinting inject 만으로는 attribute modifier 추가 (#2) 차단 불가 →
     * 스프린트 속도 효과 그대로 발동 (사용자 보고 "엎드린 채 sprint 발동").
     *
     * 원본 SmartMovingSelf L2700 `setSprinting(isStandupSprintingOrRunning())` 1:1 매핑.
     * `isStandupSprintingOrRunning() = ... && !isCrawling` 가드 (L3236) 적용 효과 = 크롤
     * 시 setSprinting(true) 호출 자체 차단 → SPRINTING flag false + 1.3x modifier 미추가.
     *
     * 1.21.1 vanilla 가 매 tick `setSprinting(true)` 자동 호출 (sprint 키 hold 시) →
     * 우리 SM 의 엣지 set `setSprinting(false)` (SmartMovingClientState L1518) 무력화.
     * 이 inject 가 vanilla 자동 호출 자체 차단해 원본 1:1 효과.
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
        // 🔴 BUG-17 (세션 36): SM disabled 시 vanilla jump() 차단 안 함 → vanilla 점프 정상 작동.
        //   기존: cfg.enabled 가드 부재 → SM disabled 시에도 ci.cancel() 으로 vanilla 점프
        //   완전 차단 + jumpAvoided/jumpPending 세팅 → 점프 자체 작동 안 함 (사용자 보고 직접 원인).
        //   해결: cfg.enabled false 시 즉시 return → vanilla 점프 ci.cancel 안 됨 → 정상 작동.
        if (!SmartMovingConfig.Config.enabled) return;
        // 🔴 BUG-28 정정 (Flying Phase / 세션 42): vanilla 1.21.1 ClientPlayerEntity.tickMovement
        //   디컴파일 L791-L800 — double-tap fly 진입 시 isOnGround=true 면 this.jump() 직접
        //   호출 (1.21.1 특화 코드). 원본 1.7.10 EntityPlayerSP 에는 없음.
        //   기존: 가드 부재 → 첫 비행 진입 (지면에서 더블탭) 시 sm_jump 발화 → jumpAvoided=true →
        //   handleJumping 의 tryJump(UP) → motionY=0.42 + 매 틱 vanilla 비행 boost +0.15 +
        //   SM handleFlying = 첫 비행 무한 상승 (사용자 BUG-28 직접 원인).
        //   원본 1.7.10 의 vanilla jump() 호출 없음 = handleJumping 에 비행 가드 불필요.
        //   해결: 1.21.1 차이 보정 — vanilla 비행 중 sm_jump 처리 skip → 원본 1:1 동작.
        if (player.getAbilities().flying) return;
        SmartMovingClientState sm = SmartMovingClientState.get(player);
        sm.jumpAvoided = true;
        sm.jumpPending = true;
        ci.cancel();
    }

    /**
     * 5-5 (클라이언트): SM 커스텀 클라이밍 중 isClimbing() = false 강제.
     *
     * 목적 1: applyClimbingSpeed() x/z ±0.15F 클램프 차단
     * 목적 2: applyMovementInput()에서 isClimbing() 시 y=0.2 강제 적용 차단
     *
     * ClientPlayerEntity 인스턴스 확인 → SmartMovingClientState 상태 기반 판정.
     * 서버 측 동등 로직 → MixinLivingEntity.sm_isClimbing_server()
     */
    @Inject(method = "onClimbable", at = @At("HEAD"), cancellable = true)
    private void sm_isClimbing_client(CallbackInfoReturnable<Boolean> cir) {
        if (!((Object) this instanceof LocalPlayer player)) return;
        // BUG-23 (세션 36): SM disabled 시 vanilla isClimbing 그대로 — sm.* 잔존 timing 차단.
        if (!SmartMovingConfig.Config.enabled) return;
        SmartMovingClientState sm = SmartMovingClientState.get(player);
        if (sm.isClimbing || sm.isCrawlClimbing || sm.isCeilingClimbing) {
            cir.setReturnValue(false);
            return;
        }
        // 🔴 사용자 보고 fix (2026-05-04 — "비행 시 휘어진/늘어진 덩굴 안 타지도록"):
        //   비행 중 vine block 위 = vanilla isClimbing()=true → travel 안 climbing clamp ±0.15
        //   적용 → 비행 속도 제한. 사용자 의도 = 비행 자유.
        //   fix: isFlying + vine block 시 isClimbing()=false 강제 → clamp skip → 비행 정상.
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
     * 5-6: applyClimbingSpeed() 이중 차단 — SM 클라이밍 시 Vec3d 그대로 반환.
     * Yarn: applyClimbingSpeed (intermediary: method_18801 확인 완료)
     *
     * 5-5(isClimbing=false)로 이미 applyClimbingSpeed 내부 클램프가 차단되지만,
     * applyClimbingSpeed가 isClimbing()을 우회해 직접 호출되는 경로 대비 이중 방어.
     */
    @Inject(method = "handleOnClimbable", at = @At("HEAD"), cancellable = true)
    private void sm_applyClimbingSpeed(Vec3 velocity, CallbackInfoReturnable<Vec3> cir) {
        if (!((Object) this instanceof LocalPlayer player)) return;
        // BUG-23 (세션 36): SM disabled 시 vanilla applyClimbingSpeed 그대로 — sm.* 잔존 차단.
        if (!SmartMovingConfig.Config.enabled) return;
        SmartMovingClientState sm = SmartMovingClientState.get(player);
        if (sm.isClimbing || sm.isCrawlClimbing || sm.isCeilingClimbing) {
            cir.setReturnValue(velocity);
            return;
        }
        // 🔴 사용자 보고 fix (2026-05-04 — "비행 시 vine 자동 등반 + clamp"):
        //   isFlying + vine block 시 vanilla clamp ±0.15 차단 (= isClimbing mixin 의 우회 경로 대비
        //   이중 방어). velocity 그대로 반환 → 비행 속도 유지.
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
     * 9-3: 크롤링/슬라이딩 중 LimbAnimator 갱신 억제.
     * vanilla updateLimbs()는 보행 진행 속도로 LimbAnimator를 갱신하는데,
     * 크롤링/슬라이딩 포즈에서 이를 그대로 실행하면 다리 애니메이션이 비정상 재생된다.
     */
    @Inject(method = "calculateEntityAnimation(Z)V", at = @At("HEAD"), cancellable = true)
    private void sm_updateLimbs_client(boolean serverSide, CallbackInfo ci) {
        if (!((Object) this instanceof LocalPlayer player)) return;
        // BUG-23 (세션 36): SM disabled 시 vanilla updateLimbs 그대로 — sm.* 잔존 차단.
        if (!SmartMovingConfig.Config.enabled) return;
        SmartMovingClientState sm = SmartMovingClientState.get(player);
        if (sm.isCrawling || sm.isSliding) {
            ci.cancel();
        }
    }

    /**
     * HorizontalAerodynamicDamping — 슬라이드→헤드점프 전환 직후 수평 감속 보정.
     * 원본: SmartMovingBase.landMotion() (행 751-755) — isAerodynamic && !onGround && !isSliding 시
     *       motionX/Z *= HorizontalAerodynamicDamping(0.999F)
     *
     * 1.21.1: vanilla travel() 내 공기저항 0.91F 이후에 삽입.
     * isAerodynamic=true일 때 vanilla가 적용한 0.91F를 되돌리고 0.999F를 적용.
     * correction factor = 0.999F / 0.91F
     */
    @Inject(method = "travel", at = @At("TAIL"))
    private void sm_aerodynamicDamping(Vec3 movementInput, CallbackInfo ci) {
        // 🔴 (2026-04-28) 임시 비활성. land + 점프 동작 검증 후 head jump 별도 매핑.
    }

    /**
     * B-2 (세션 26): Land 이동 User 배율 이식. 원본 `SmartMovingSelf.java` L119
     *   `speedFactor = getConfigSpeedFactor() * getPotionSpeedFactor() * ...`
     * 의 `getConfigSpeedFactor()` 부분을 vanilla travel() 이 처리하는 Land 경로에 주입.
     *
     * **옵션 A (Mixin inject on getMovementSpeed)** 선택: vanilla 의 `LivingEntity.
     * getMovementSpeed()` HEAD inject 로 반환값에 `getConfigSpeedFactor(player, cfg)` 곱셈.
     *
     * 적용 범위:
     *   - Land (걷기/달리기/스프린트/점프) — vanilla travel() 이 getMovementSpeed() 참조 → 영향
     *   - 비행/수영/잠수/클라이밍 — SM 이 `sm_travel_client` 에서 vanilla travel() cancel
     *     → getMovementSpeed 안 불림 → 영향 0 (각자 경로에서 getCombinedSpeedFactor 별도 적용)
     *
     * Creative 게이트는 `Mover.getConfigSpeedFactor` 가 내부 `isCreative()` 로 처리 —
     *   Creative 아니면 userSpeedFactor=1F → speedFactor(기본 1F) 만 반영. 체감 변화 0.
     *
     * ⚠️ 이중 적용 주의: SM 경로 (Swimmer/Climber/Flyer) 는 getPotionSpeedFactor 에서
     *   GENERIC_MOVEMENT_SPEED attribute 를 별도 읽음. 이 mixin 은 getMovementSpeed 반환값만
     *   바꾸는데, getPotionSpeedFactor 는 attribute 를 직접 읽으므로 이중 곱 안 됨. OK.
     *
     * ClientPlayerEntity 만 대상 — 서버 플레이어는 server-side travel 처리 (클라→서버 동기).
     */
    @Inject(method = "getSpeed", at = @At("HEAD"), cancellable = true)
    private void sm_getMovementSpeed(CallbackInfoReturnable<Float> cir) {
        // 🔴 (2026-04-28) 비활성화 — handleLand 가 vanilla travel 을 cancel + 자체 식 적용
        //   하므로 vanilla travel 의 getMovementSpeed 호출 경로 없음.
        //   다른 서브시스템 (애니메이션/발자국/네트워크) 이 호출 시 sprint 1.154x 부수효과
        //   → 원본보다 살짝 빠른 느낌의 가능성. handleLand 에서 sprintFactor 직접 적용하므로
        //   여기서 추가 곱은 이중 적용 위험.
    }

    /**
     * 6-2 (클라이언트): SM 크롤링 중 isInSwimmingPose() = false 강제.
     *
     * SM 크롤링은 SWIMMING 포즈를 사용하지만, vanilla isInSwimmingPose()=true가 되면:
     *   - setupTransforms Branch 2: X-90° 회전 + translate(0,-1,0.3) → 렌더 깨짐
     *   - updateLeaningPitch(): leaningPitch가 1.0까지 상승 → 자동 회전 발생
     *   - animateModel(): model.leaningPitch 반영 → setAngles 전 pitch 왜곡
     *
     * 클라이언트 측: ClientPlayerEntity 인스턴스 확인.
     * 서버 측 → MixinLivingEntity.sm_isInSwimmingPose_server()
     * Yarn: isInSwimmingPose (intermediary: method_20232 확인 완료)
     */
    @Inject(method = "isVisuallySwimming", at = @At("HEAD"), cancellable = true)
    private void sm_isInSwimmingPose_client(CallbackInfoReturnable<Boolean> cir) {
        // 🔴 (Phase 2 multi BUG-2/2.5) ClientPlayerEntity 가드 → AbstractClientPlayerEntity.
        //   기존: self only → remote 는 vanilla swimming pose 적용 → 90도 꺾임 + orphan SWIMMING.
        //   변경: 모든 player 처리. sm 인스턴스 = entity uuid 기반.
        if (!((Object) this instanceof net.minecraft.client.player.AbstractClientPlayer player)) return;
        // BUG-23 (세션 36): SM disabled 시 vanilla isInSwimmingPose 그대로 — sm.* 잔존 차단.
        // 🔴 (Phase 2 fix-3-2) cfg.enabled → isSmRenderEnabled 로 self-only 화 (BUG-CONFIG-2 일관).
        if (!choco.ratel.smartmoving.client.SmartMovingClient.isSmRenderEnabled(player)) return;
        SmartMovingClientState sm = SmartMovingClientState.get(player);
        // [6-2][8-3] 크롤링/수영/잠수/크롤클라이밍 중 setupTransforms Branch 2 진입 차단.
        // isCrawling/isCrawlClimbing: SWIMMING 포즈 사용하되 vanilla -90° 자동 회전 방지
        // isSwimming_sm/isDiving: SM 자체 body X 기울기 애니메이션과 충돌 방지
        if (sm.isCrawling || sm.isCrawlClimbing || sm.isSwimming_sm || sm.isDiving) {
            cir.setReturnValue(false);
            return;
        }
        // 🔴 (2026-05-04 사용자 보고 — "슬라이딩 → standing 시 잠깐 수영 애니"):
        //   원인: SM crawl 종료 후 standing 진입 시 1 frame 동안 server→client pose sync
        //         lag 로 pose=SWIMMING 잔존 (SM swim state 모두 false 인데도). vanilla
        //         updateLeaningPitch 가 isInSwimmingPose=true → leaningPitch +=0.09.
        //         다음 6~7 frame 동안 lerp down 하며 vanilla setupTransforms 가 -90° X
        //         회전 lerp 적용 → 잠깐 수영 자세 보임.
        //   해결: SM enabled + pose=SWIMMING + SM swim 분기 모두 false = orphan pose.
        //         isInSwimmingPose=false 강제 → leaningPitch +=0.09 차단 → 누적 X.
        if (player.getPose() == net.minecraft.world.entity.Pose.SWIMMING) {
            cir.setReturnValue(false);
        }
    }

    /**
     * 🔴 사용자 보고 fix (2026-05-04 — "1칸 공간 재진입 시 잠깐 SWIMMING 모델 보임"):
     *   원인: vanilla PlayerEntityRenderer.setupTransforms L261-L282 의
     *     `m = lerp(leaningPitch, 0, -90); matrices.rotate(POSITIVE_X(m));` →
     *     leaningPitch>0 시 모델 X 회전. 우리 SM crawl R_x(-78.75°) 매핑과 합산.
     *   leaningPitch field 는 LivingEntity.updateLeaningPitch 에서 매 tick lerp.
     *     SM mixin 의 isInSwimmingPose=false 강제로 -=0.09 → 0 까지 감소. 근데 게임
     *     재진입 시 첫 client tick 전 (= server sync 직후) entity 의 leaningPitch 가
     *     이미 일정 값 잔존 가능 (= server tick 누적 값 sync).
     *   fix: SM crawl 활성 시 getLeaningPitch HEAD inject 로 0 강제 → setupTransforms
     *     의 R_x(0) → 회전 영향 X. anySmState 모두 적용.
     */
    @Inject(method = "getSwimAmount", at = @At("HEAD"), cancellable = true)
    private void sm_getLeaningPitch(float tickDelta, CallbackInfoReturnable<Float> cir) {
        // 🔴 (Phase 2 multi BUG-2/2.5) ClientPlayerEntity 가드 → AbstractClientPlayerEntity.
        if (!((Object) this instanceof net.minecraft.client.player.AbstractClientPlayer player)) return;
        if (!choco.ratel.smartmoving.client.SmartMovingClient.isSmRenderEnabled(player)) return;
        SmartMovingClientState sm = SmartMovingClientState.get(player);
        if (sm.isCrawling || sm.isCrawlClimbing || sm.isSwimming_sm || sm.isDiving
                || sm.isHeadJumping || sm.isSliding) {
            cir.setReturnValue(0F);
            return;
        }
        // 🔴 orphan SWIMMING pose 가드 (sm_isInSwimmingPose_client 와 동일 가드):
        //   pose=SWIMMING 인데 SM swim 분기 모두 false → leaningPitch field 잔존 가능 →
        //   setupTransforms 의 swim 회전 lerp 적용 차단.
        if (player.getPose() == net.minecraft.world.entity.Pose.SWIMMING) {
            cir.setReturnValue(0F);
        }
    }

}
