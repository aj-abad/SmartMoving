package choco.ratel.smartmoving.mixin.client;

import choco.ratel.smartmoving.client.SmartMovingClientState;
import choco.ratel.smartmoving.config.SmartMovingConfig;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 6-1 (client): getBaseDimensions() returns custom EntityDimensions.
 * 6-4 (client): updatePose() Mixin — forcibly set pose per SM state.
 *
 * getDimensions() is final → getBaseDimensions() is the only override entry point.
 * Yarn: getBaseDimensions (intermediary: method_55694 verified)
 * Yarn: updatePose (intermediary: method_7318 verified)
 *
 * Server side → MixinPlayerEntity.sm_getBaseDimensions_server(), sm_updatePose_server()
 */
@Mixin(Player.class)
@Environment(EnvType.CLIENT)
public abstract class MixinPlayerEntityClient {

    /**
     * 6-1 (client): returns custom EntityDimensions per SM pose.
     *
     * **1:1 translation principle** (session 137): original `setHeightOffset(-1F)` semantics
     *   = `boundingBox.minY -= -1 = +1` + `height += -1` → bbox height 1.8 → 0.8 reduced
     *   = original `getEyeHeight() = sp.height - 0.18F` → 0.62F
     *   → **all SM active states**: unified bbox 0.6 × 0.8 + eyeHeight 0.62F.
     *
     * SM active states (original setHeightOffset(-1F) set):
     *   - isCrawling        (original L2829 crawl entry / L2798 climbCrawl entry etc.)
     *   - isClimbCrawling   (original L2798)
     *   - isHeadJumping     (original L2129 tryJump head)
     *   - isSliding         (original L2555 slide direct entry)
     *   - isSwimming_sm     (original L511 handleSwimming)
     *   - isDiving          (original L511)
     *   - isFlying (SM)     (original L2512 `isFlying && !wasFlying` edge)
     *   - isLevitating      (original L2519 `isLevitating && !wasLevitating` edge)
     *
     * Inactive (heightOffset=0 maintained, vanilla bbox):
     *   - isDipping: water surface touching state. Original has no setHeightOffset → STANDING 1.8 maintained.
     *
     * POSE itself is intercepted in `sm_updatePose_client` to appropriate SWIMMING/SLIDING.
     * dimensions returns checking only SM state regardless of POSE (original bbox semantics 1:1).
     */
    @Inject(method = "getDefaultDimensions", at = @At("HEAD"), cancellable = true, require = 0)
    private void sm_getBaseDimensions_client(Pose pose, CallbackInfoReturnable<EntityDimensions> cir) {
        // 🔴 (Phase 2 multi BUG-12/14) ClientPlayerEntity guard → AbstractClientPlayerEntity.
        //   Previous: self only → remote dim mapping depends only on vanilla pose → packet timing diff
        //             sm.isCrawling=true + pose=STANDING residual frame → STANDING dim mapping → our
        //             sm_setupTransforms R_x rotation applied → 1.8 height model rotates → odd pose (BUG-12).
        //   Change: all player dim determination depends on sm.* bits → dim updates immediately upon packet A (sm relay) arrival.
        if (!((Object) this instanceof net.minecraft.client.player.AbstractClientPlayer player)) return;
        // BUG-20 (session 36): use vanilla dimensions as-is when SM disabled.
        // 🔴 (Phase 2 fix-3-2) cfg.enabled → isSmRenderEnabled (BUG-CONFIG-2 consistency).
        //   self → Config.enabled / remote → always true. when self disabled, own dim vanilla,
        //   remote's dim determined by that player's SM state.
        if (!choco.ratel.smartmoving.client.SmartMovingClient.isSmRenderEnabled(player)) return;
        SmartMovingClientState sm = SmartMovingClientState.get(player);

        // 🔴 BUG-29 real root corrected (Flying Phase / session 45): remove flying/Levitate branch.
        //   Original SmartMovingSelf.setHeightOffset (L1694-L1704) = `boundingBox.minY -= heightOffset
        //   + height += heightOffset` — **only changes collision box, does not change eyeHeight**. 1.7.10 vanilla
        //   has no EntityPose system itself so during flight STANDING model + eyeHeight 1.62 maintained as-is.
        //   1.21.1 incorrect mapping: isFlying/isLevitating forces 0.6 × 0.8 + eyeHeight 0.62F →
        //   vanilla applies camera position to 0.62 → camera drops to feet + model drawn small.
        //   User report BUG-29 "body center shifts to feet during flight" direct cause.
        //   Correction: remove isFlying/isLevitating condition → vanilla 1.21.1 STANDING POSE + 1.8 height +
        //   1.62 eyeHeight as-is → original 1.7.10 behavior 1:1 matching (collision box also vanilla handled).
        //
        // Original setHeightOffset(-1F) state transmitted — Crawling/Sliding/Swimming all 0.6 × 0.8 +
        // eyeHeight 0.62F. isDipping/isFlying/isLevitating pass vanilla.
        // 🔴 isClimbCrawling separation: original 1.7.10 ladder hanging pose maintains STANDING (no POSE system).
        //   setHeightOffset(-1F) only changes box, does not change eyeHeight (BUG-29 same pattern).
        //   1.21.1 incorrect mapping: forces 0.6×0.8 + eyeHeight 0.62F → camera 1m away from feet →
        //   user perceives as "crouch".
        //   Correction: isClimbCrawling only changes box to 0.6×0.8, eyeHeight 1.62F (vanilla STANDING maintained).
        // 🔴 isCrawling && isClimbing case added: toCrawling() call at isClimbCrawling release edge
        //   → 1 tick isCrawling=true → during climbing eyeHeight forced to 0.62F → camera
        //   1.62↔0.62 toggle = user report "body animation periodic wobble". during climbing keep STANDING
        //   eyeHeight.
        // 🔴 ICC separation: original 1.7.10 ladder hanging pose maintains STANDING (no POSE system).
        //   setHeightOffset(-1F) only changes box, does not change eyeHeight (BUG-29 same pattern).
        //   1.21.1 incorrect mapping: forces 0.6×0.8 + eyeHeight 0.62F → camera 1m away from feet →
        //   user perceives as "crouch".
        //   Correction: isClimbCrawling only changes box to 0.6×0.8, eyeHeight 1.62F (vanilla STANDING maintained).
        // 🔴 isCrawling && isClimbing case added: toCrawling() call at isClimbCrawling release edge
        //   → 1 tick isCrawling=true → during climbing eyeHeight forced to 0.62F → camera
        //   1.62↔0.62 toggle = user report "body animation periodic wobble". during climbing keep STANDING
        //   eyeHeight.
        // 🔴 (isCrawling && isClimbing) branch guard added (user report fix — cutoff, 2026-05-03):
        //   original intent (memory comment): ICC release edge limited to 1 tick (= isCrawling=true residual
        //   after toCrawling() while maintaining climbing pose). however without guard matches even during
        //   isCrawlClimbing self-maintenance → box +1m offset → grip area above → wantClimbHolding match →
        //   unintended ICC entry → entity.y +1m change on ICC EXIT → user perceives jump (= cutoff).
        //   Solution: add `&& wasClimbCrawling` guard → matches only during ICC active + release edge 1 tick.
        //   during isCrawlClimbing self-maintenance wasClimbCrawling=false → falls to smSmall branch → box stable.
        if (sm.isClimbCrawling || (sm.isCrawling && sm.isClimbing && sm.wasClimbCrawling)) {
            cir.setReturnValue(EntityDimensions.scalable(0.6F, 0.8F).withEyeHeight(1.62F));
            return;
        }
        // 🔴 Flying collision — user explicit (2026-05-02): "don't touch flying".
        //   maintain existing mapping (567dbec): dim=(0.6, 0.8, 1.62) + MixinEntity.sm_offsetBoundingBoxForFlying
        //   guard (eyeHeight > 1) passes → box +1 applied → user viewpoint preserved mapping as-is.
        //   Different mapping from ICC (ICC is setPos(y+1) + eyeHeight 0.62 original 1:1).
        if (sm.isFlying || sm.isLevitating) {
            cir.setReturnValue(EntityDimensions.scalable(0.6F, 0.8F).withEyeHeight(1.62F));
            return;
        }
        // 🔴 isCrawlClimbing added (user report fix — STANDING collision size BUG, 2026-05-03):
        //   At isCrawlClimbing entry edge (original L2752 1:1) isCrawling=false forced → isCrawling=false
        //   residual until next tick → smSmall branch no match → vanilla pose dim (CROUCHING 1.5) passes
        //   = "STANDING size collision" user report. During ICC active + ladder grip at isCrawling=false moment
        //   same problem box even smaller (vanilla SWIMMING 0.6).
        //   Solution: add isCrawlClimbing directly to smSmall → always guarantee (0.8, 0.62) during self-maintenance.
        //   eye=0.62 → mixin offset guard (eye>1) no match → box +1m offset not applied → after ICC EXIT
        //   box at entity.y +1m position (entity.y, entity.y+0.8) = (old+1, old+1.8) = original 1:1.
        // 🔴 v26.12 — v26.5 guard removed (= STANDING dim passing BUG root).
        //   User report "ladder-type climbing-crawl, before entry collision becomes STANDING once then crouch":
        //   v26.5's `&& !(isCrawling && isClimbing && !isClimbCrawling)` guard in transition frame
        //   smSmall no match → vanilla STANDING dim fall through → bb 1.8h. next frame ICC
        //   dim entry → bb 0.8h. user perceives "STANDING → crouch" transition.
        //   Solution: remove v26.5 guard. restore memory *project_isCrawlClimbing_complete* Fix 7 intent
        //   (= add isCrawlClimbing to smSmall → always guarantee 0.8/0.62) 1:1.
        // 🔴 isHeadJumping separate branch (2026-05-08, settled after multi-stage fix):
        //   Original setHeightOffset(-1F) = boundingBox.minY +=1m + height -=1m + **posY unchanged**.
        //   camera = posY + 1.62F (STANDING eyeHeight). no camera change on entry (smooth).
        //
        //   1st attempt: dim (0.6, 0.8, 1.62F) → MixinEntity.sm_offsetBoundingBoxForFlying
        //     guard (height<1 && eyeHeight>1) match → box +1m offset auto-applied → box bottom =
        //     player.y +1m → onGround check wrong → infinite loop BUG (user report "hover 1 block in air" +
        //     "no sliding after landing").
        //
        //   2nd fix (current): dim (0.6, **1.8**, 1.62F) — height same as STANDING.
        //     - offset guard no match (height>=1 → early return) → box +1m offset not applied.
        //     - box = (player.y, player.y+1.8) = STANDING box same.
        //     - camera = player.y + 1.62F = STANDING camera same (no viewpoint change on entry/exit).
        //     - POSE = SLIDING (only model pose flat), dim height=1.8 (only box STANDING).
        //     - toSlidingOrCrawling works normally → headjump → grab+sneak hold → sliding OK.
        //
        //   Note: box bottom = player.y (same as STANDING) — no +1m effect from original box.
        //         narrow space passage scenarios may need separate fix later.
        if (sm.isHeadJumping) {
            // 🔴 fix #26 revert (user report "stuck in ground can't get out"):
            //   `isHeadJumping || justEndedHeadJump` branch forced dim eye=1.62 but cached bb not updated
            //   → toSlidingOrCrawling calculates gap=1.0 (mixin match cached bb minY=ground+1)
            //   → player.move(-1, 0) → box -1m → cycle. revert.
            // 🔴 fix #13 (2026-05-08, user report "collision STANDING unchanged"):
            //   fix #12 (height 1.8) prevented infinite loop by mixin offset guard no match effect but
            //   box collision = STANDING (1.8) contradicts user intent (headjump small box).
            //   real fix: revert dim to small box (0.8) + eyeHeight 1.62, add POSE=SLIDING exception
            //   to MixinEntity's offset guard → block mixin offset → no infinite loop +
            //   box small + camera STANDING.
            cir.setReturnValue(EntityDimensions.scalable(0.6F, 0.8F).withEyeHeight(1.62F));
            return;
        }
        // 🔴 fix #59 (2026-05-10, dump analysis — user report "eye height below collision during fox movement"):
        //   during isSliding fix #56's POSE.SLIDING guard activates mixin offset → box +1m up.
        //   however dim eyeHeight=0.62 remains → camera = entity.y + 0.62 → 0.38m below box.
        //   1.7.10/1.12.2's setHeightOffset(-1) = boundingBox +1m up + position unchanged.
        //   correct camera position = entity.y + 1.62 (= STANDING eyeHeight, aligned with box).
        //   Solution: isSliding dim eyeHeight=1.62 → align with mixin offset (+1m).
        if (sm.isSliding) {
            cir.setReturnValue(EntityDimensions.scalable(0.6F, 0.8F).withEyeHeight(1.62F));
            return;
        }
        // 🔵 (2026-05-20, Phase 0 verification fix #102) swim/dive separate branch — adopt isSliding pattern 1:1.
        //   Original 1.7.10 `setHeightOffset(-1F)` (L510-511 `if(isDiving||isSwimming)`) effect =
        //     boundingBox.minY -= 1 (box +1m up) + height 0.8 + eyeHeight unchanged (STANDING 1.62
        //     remains, position unchanged). same intent as isSliding/isHeadJumping mapping.
        //   Existing mapping BUG: swim/dive enters smSmall (eye=0.62) blocks box +1m up (mixin offset
        //     guard `eyeHeight>1 || pose==SLIDING` doesn't pass) + camera 1m below STANDING.
        //   Solution: like isSliding force eye=1.62 → pass mixin offset guard (eye>1) → box +1m up auto-applied
        //     → box bottom = entity.y + 1m + camera = entity.y + 1.62 (same as STANDING).
        if (sm.isSwimming_sm || sm.isDiving) {
            cir.setReturnValue(EntityDimensions.scalable(0.6F, 0.8F).withEyeHeight(1.62F));
            return;
        }
        boolean smSmall = sm.isCrawling || sm.isCrawlClimbing;
        if (smSmall) {
            cir.setReturnValue(EntityDimensions.scalable(0.6F, 0.8F).withEyeHeight(0.62F));
            return;
        }

        // SLIDING POSE entered via non-vanilla path (external mods etc.) reinforcement handling
        if (pose == Pose.SLIDING) {
            cir.setReturnValue(EntityDimensions.scalable(0.6F, 0.8F).withEyeHeight(0.62F));
            return;
        }

        // 🔴 orphan SWIMMING pose guard (2026-05-04 user report — "sometimes before 1-block entry when releasing sliding,
        //   collision smaller than normal crouch + animation STANDING"):
        //   Root cause: server→client pose sync lag (1~4 frames) between sliding → standing 1 frame
        //              pose=SWIMMING remains. all SM smSmall branches (isCrawling/isCrawlClimbing/
        //              isHeadJumping/isSliding/isSwimming_sm/isDiving) false → above branch no match
        //              → vanilla SWIMMING dim (0.6×0.6) passes → box smaller than 0.6×0.8.
        //              animation all SM branches false so vanilla setAngles → STANDING pose.
        //   Solution: pose=SWIMMING + smSmall no match = orphan → force STANDING dim (sync releases
        //             normal standing). memory feedback_smSmall_dim_omission.md same pattern.
        if (pose == Pose.SWIMMING) {
            cir.setReturnValue(EntityDimensions.scalable(0.6F, 1.8F).withEyeHeight(1.62F));
            return;
        }
    }

    /**
     * 11-4 (client): suppress air movement when SM flight disabled.
     *
     * vanilla: flying → flySpeed, !flying+sprinting → 0.026F, otherwise → 0.02F.
     * SM: flying && !cfg.fly → 0.05F (verified A-30).
     * limiting this method to 0.05F suppresses air XZ acceleration in vanilla travel().
     */
    /**
     * 🔴 fix #84 (2026-05-12, block fix #83 side effects):
     *   vanilla PlayerEntity.clipAtLedge() = return isSneaking(). fix #83 adds isSliding so
     *   during sliding isSneaking()=true → clipAtLedge()=true → adjustMovementForSneaking match →
     *   step-back beyond cliff → sliding → fall scenario block BUG.
     *   Solution: during sliding clipAtLedge() override → return false. block step-back.
     *   bobbing/footstep/flight toggle (= fix #83 effect) only uses isSneaking() so no impact.
     *   @Mixin(PlayerEntity.class) target — ClientPlayerEntity inherits.
     */
    @Inject(method = "isStayingOnGroundSurface", at = @At("HEAD"), cancellable = true)
    private void sm_clipAtLedge_slidingException(CallbackInfoReturnable<Boolean> cir) {
        if (!SmartMovingConfig.Config.enabled) return;
        if (!((Object) this instanceof LocalPlayer player)) return;
        SmartMovingClientState sm = SmartMovingClientState.get(player);
        if (sm.isSliding) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "getFlyingSpeed", at = @At("HEAD"), cancellable = true)
    private void sm_getOffGroundSpeed(CallbackInfoReturnable<Float> cir) {
        if (!((Object) this instanceof LocalPlayer player)) return;
        SmartMovingConfig cfg = SmartMovingConfig.Config;
        if (!cfg.enabled) return;
        if (player.getAbilities().flying && !cfg.fly) {
            cir.setReturnValue(0.05F);
            return;
        }
        // 🔴 (2026-04-28) SM air acceleration temporarily disabled — debug user report "extremely fast on jump".
        //   apply SM factor only on ground (getMovementSpeed) + keep air (getOffGroundSpeed) vanilla as-is.
        //   redesign air mapping formula once acceleration disappears.
    }

    /**
     * 6-4 (client): cancel vanilla updatePose() in SM movement state and forcibly set SM pose.
     *
     * **1:1 translation principle** (session 137): original SM designed for era without vanilla POSE system
     * so manual bbox manipulation per state. 1.21.1 manages bbox based on POSE. SM exclusively sets POSE per state
     * to prevent vanilla from overwriting.
     *
     * Priority mapping (original heightOffset=-1F active state transmitted):
     *   isCrawling / isClimbCrawling             → SWIMMING (crouch anim)
     *   isHeadJumping / isSliding                 → SLIDING  (flat pose)
     *   isSwimming_sm / isDiving                  → SWIMMING (swim anim)
     *   isFlying (SM) / isLevitating              → SLIDING  (maintain bbox 0.8 during flight)
     *   isDipping                                 → STANDING (heightOffset=0, standing on water)
     *   others                                    → vanilla updatePose() pass (elytra/trident etc. maintained)
     *
     * dimensions values (0.6 × 0.8 + eyeHeight 0.62) reflected in full by sm_getBaseDimensions_client.
     */
    @Inject(method = "updatePlayerPose", at = @At("HEAD"), cancellable = true)
    private void sm_updatePose_client(CallbackInfo ci) {
        if (!((Object) this instanceof LocalPlayer player)) return;
        // BUG-22 (session 36): use vanilla updatePose as-is when SM disabled — block sm.* residual timing
        //   effect. SLIDING POSE may forcibly remain on first flight entry frame → affects motion.
        if (!SmartMovingConfig.Config.enabled) return;
        SmartMovingClientState sm = SmartMovingClientState.get(player);

        if (sm.isCrawling && !sm.isClimbing) {
            // Crouch pose — reuse SWIMMING POSE (share vanilla swim anim)
            // 🔴 isClimbCrawling branch removed: original 1.7.10 vanilla has no EntityPose system →
            //   during isClimbCrawling keep pose STANDING (only box reduces 0.6×0.8). when forcing SWIMMING POSE
            //   user visually sees horizontal crouch at end of ladder → perceive as "crouch".
            //   original 1:1 mapping is STANDING POSE + small box. dimensions sm_getBaseDimensions
            //   also applies 0.6×0.8 during isClimbCrawling (original setHeightOffset(-1F) 1:1).
            // 🔴 !isClimbing guard: toCrawling() call at isClimbCrawling release edge
            //   → 1 tick isCrawling=true → during climbing SWIMMING POSE activates → STANDING↔SWIMMING toggle →
            //   user report "body animation periodic wobble". during climbing keep STANDING pose.
            player.setPose(Pose.SWIMMING);
            ci.cancel();
        } else if (sm.isHeadJumping || sm.isSliding) {
            // Flat pose — SLIDING POSE
            player.setPose(Pose.SLIDING);
            ci.cancel();
        } else if (sm.isSwimming_sm || sm.isDiving) {
            // Swimming/diving — SWIMMING POSE (vanilla same, explicit lock)
            player.setPose(Pose.SWIMMING);
            ci.cancel();
        }
        // 🔴 BUG-29 real root corrected (Flying Phase / session 45): remove isFlying/isLevitating branch.
        //   Original 1.7.10 vanilla has no EntityPose system → during flight keep STANDING model as-is.
        //   1.21.1 incorrect mapping: force SLIDING POSE → vanilla applies small eyeHeight → camera
        //   drops to feet + model drawn small. user report BUG-29 direct cause.
        //   Correction: remove branch → vanilla 1.21.1 STANDING POSE as-is → camera normal (1.62 eyeHeight)
        //   + model normal (1.8 height) = original 1.7.10 behavior 1:1 matching.
        // isFlying/isLevitating/isDipping/others → vanilla updatePose() pass (STANDING etc.)
    }

    /**
     * 🔴 BUG-27 real root corrected (Flying Phase / session 51): block vanilla PlayerEntity.travel() flying
     *   branch motionY overwrite.
     *
     * vanilla PlayerEntity.travel(Vec3d) decompiled:
     * <pre>
     *   if (this.getAbilities().flying && !this.hasVehicle()) {
     *       double d = this.getVelocity().y;          // save previous motionY
     *       super.travel(movementInput);               // → LivingEntity.travel
     *                                                  //   → sm_travel_client (HEAD inject)
     *                                                  //   → handleFlying (change motionY) + ci.cancel
     *                                                  //   → LivingEntity.travel body skip
     *       Vec3d vec3d = this.getVelocity();          // our modified velocity
     *       this.setVelocity(vec3d.x, d * 0.6, vec3d.z); // ← motionY = previous * 0.6 forced!
     *       this.onLanding();
     *       this.setFlag(7, false);
     *   }
     * </pre>
     *
     * setVelocity(vec3d.x, **d * 0.6**, vec3d.z) overwrites our SM motionY change → during SM flight
     * mouse pitch → W progress direction (motionY +0.0322) effect completely ignored = user report BUG-27 direct cause.
     *
     * Correction: intercept setVelocity call, ignore y argument when SM flight active (preserve current SM motionY).
     * X/Z handled vanilla as-is.
     */
    @Redirect(
        method = "travel",
        at = @At(value = "INVOKE",
                 target = "Lnet/minecraft/world/entity/player/Player;setDeltaMovement(Lnet/minecraft/world/phys/Vec3;)V")
    )
    private void sm_travel_setVelocity_flyFix(Player self, net.minecraft.world.phys.Vec3 velocity) {
        if (self instanceof LocalPlayer player
                && SmartMovingConfig.Config.enabled
                && SmartMovingClientState.get(player).isFlying) {
            // Block motionY *= 0.6 during SM flight — preserve motionY set by handleFlying.
            // X/Z vanilla arguments as-is (same as handleFlying result).
            self.setDeltaMovement(velocity.x, self.getDeltaMovement().y, velocity.z);
            return;
        }
        // Non-SM flight (vanilla) → original behavior as-is (motionY *= 0.6).
        self.setDeltaMovement(velocity);
    }

    /**
     * 🔴 (2026-05-05 Phase 1-A) other players also update stats each tick — source for
     *   walkFactor/standFactor/totalDistance formulas like flying pose etc.
     *   self (= ClientPlayerEntity) updated in MixinEntityClient.sm_afterMove_client.
     *   only other players (= AbstractClientPlayerEntity, not ClientPlayerEntity) handled here.
     *   PlayerEntity.tickMovement TAIL — applied to all player ticks. remote only via instanceof branch.
     */
    @Inject(method = "tick", at = @At("TAIL"))
    private void sm_tickStatsForRemote(CallbackInfo ci) {
        if (!SmartMovingConfig.Config.enabled) return;
        Object self = (Object) this;
        // local (= ClientPlayerEntity) 은 MixinEntityClient 에서 이미 처리.
        if (self instanceof LocalPlayer) return;
        // remote 만 처리 — AbstractClientPlayerEntity (= 다른 player on client side).
        if (!(self instanceof net.minecraft.client.player.AbstractClientPlayer remote)) return;
        SmartMovingClientState sm = SmartMovingClientState.get(remote);

        // 🔴 (Phase 1-B fix-2) stats source: getVelocity() → position delta.
        //   Original SmartStatistics.calculateAllStats: `diffX = sp.posX - sp.prevPosX` (= delta).
        //   previous mapping assumed server-synced velocity but vanilla 1.21.1 server
        //   doesn't sync other player's velocity field → always (0,0,0) → stats input 0
        //   → other player limbs don't swing (BUG-D).
        //   fix: like original use prev/cur position delta. interpolation oscillation smoothed by stats own EMA.
        double dx = remote.getX() - remote.xo;
        double dy = remote.getY() - remote.yo;
        double dz = remote.getZ() - remote.zo;
        sm.stats.calculate(0, 0, 0,
                           dx, dy, dz,
                           remote.getYRot());

        // 🔵 (2026-05-14, Option F fix #97) StatsPayload 동기화 — self → REMOTE.
        //   자체 계산 (= 위 stats.calculate) 결과 중 currentVerticalAngle/HorizontalAngle 만
        //   packet 값으로 덮어쓰기 (= self trajectory 100% 일치).
        //   원인: vanilla lerpPosAndRotation 5-tick 분할이 REMOTE realDxYz 평탄화 →
        //     atan2 결과 thetaT 가 self 와 다른 흐름 → fade peak 추격 시간 부족.
        //   다른 field (currentSpeed/totalHorizontalDistance/EMA 등) 는 자체 계산 유지 — 이동
        //     관련 swing/walkFactor 등 안전.
        //   초기값 NaN (= packet 받기 전) 시 자체 계산 사용 → 회귀 차단.
        //   prevHorizontalAngle 도 함께 set (= 다음 tick atan NaN fallback 시 안정).
        if (!Float.isNaN(sm.smRemoteStatsVerticalAngle_fromPacket)) {
            sm.stats.currentVerticalAngle = sm.smRemoteStatsVerticalAngle_fromPacket;
        }
        if (!Float.isNaN(sm.smRemoteStatsHorizontalAngle_fromPacket)) {
            sm.stats.currentHorizontalAngle = sm.smRemoteStatsHorizontalAngle_fromPacket;
            sm.stats.prevHorizontalAngle = sm.smRemoteStatsHorizontalAngle_fromPacket;
        }

        // 🔵 (2026-05-14, fix #98 v4) REMOTE 헤드점프 자세 visual hold 위치 기반 해제 (잠긴 cause 정정).
        //   set 위치: SmartMovingClient.java packet 처리 안 공중 상태 (= prevY > groundY+0.005) 검출.
        //   해제 조건 v4: REMOTE.y >= smRemoteHJExitGroundY-0.005 (= ground 위 도달 = 회복 완료).
        //
        //   v3 (= getY <= groundY+0.005) 잘못: 잠긴 상태 (= REMOTE.y < ground) 도 매치 → 활성 즉시 해제.
        //     dump 검증 cycle 1-5: prevY > groundY (공중) + getY < groundY (잠긴 lerp 진행 중) →
        //     hold 활성 + 즉시 해제 → 효과 X.
        //
        //   v4 해제 식 분석:
        //     - server.y trajectory: 잠긴 시점 (71) → push +1m 도달 (72=ground) → 잔존 (= self side standUp 후).
        //     - REMOTE.y lerp 가 srvY 추격: 처음 잠긴 시도 (71 도달 진행) → srvY 갱신 (72) → 회복 추격 (72).
        //     - 해제 시점 = REMOTE.y >= groundY-0.005 (= ground 이상) → 회복 완료.
        //     - 잠긴 진행 중 (= getY < groundY): 해제 안 함 → hold 유지 → 자세 visual 유지.
        //
        //   사용자 원칙 "틱기반 답없다" 일치 — 위치 기반만.
        if (sm.smRemoteHJVisualHold) {
            if (remote.getY() >= sm.smRemoteHJExitGroundY - 0.005) {
                sm.smRemoteHJVisualHold = false;
            }
        }

    }

    /**
     * 🔴 (Phase 2 multi BUG-10) sm_correctOnUpdate 의 remote 버전 — 이동 방향 bodyYaw 보정.
     *   self (ClientPlayerEntity) 는 MixinClientPlayerEntity.sm_correctOnUpdate 가 처리.
     *   remote 도 동일 식 적용 → wasd 누름 시 "몸통 이동 방향 치우침" 동작 일관.
     *   원본 SmartMovingBase.correctOnUpdate(isSmall) 동일 식. 0.02~0.05 m/tick 느린 이동 보정.
     */
    @Inject(method = "tick", at = @At("TAIL"))
    private void sm_correctOnUpdateRemote(CallbackInfo ci) {
        if (!SmartMovingConfig.Config.enabled) return;
        Object self = (Object) this;
        if (self instanceof LocalPlayer) return;  // self 는 MixinClientPlayerEntity 가 처리.
        if (!(self instanceof net.minecraft.client.player.AbstractClientPlayer remote)) return;
        SmartMovingClientState sm = SmartMovingClientState.get(remote);

        boolean isSmall = sm.isSwimming_sm || sm.isDiving || sm.isDipping || sm.isCrawling;
        if (!isSmall) return;

        double d  = remote.getX() - remote.xo;
        double d1 = remote.getZ() - remote.zo;
        float f = (float) Math.sqrt(d * d + d1 * d1);
        if (f <= 0.02F || f >= 0.05F) return;

        float f1 = (float)(Math.atan2(d1, d) * 180.0 / Math.PI) - 90F;
        if (remote.attackAnim > 0.0F) f1 = remote.getYRot();

        float f4 = f1 - remote.yBodyRot;
        for (; f4 < -180F; f4 += 360F) {}
        for (; f4 >= 180F;  f4 -= 360F) {}
        float x = remote.yBodyRot + f4 * 0.3F;

        float f5 = remote.getYRot() - x;
        for (; f5 < -180F; f5 += 360F) {}
        for (; f5 >= 180F;  f5 -= 360F) {}
        if (f5 < -75F) f5 = -75F;
        if (f5 >= 75F)  f5 = 75F;

        remote.setYBodyRot(remote.getYRot() - f5);
        if (f5 * f5 > 2500F) remote.setYBodyRot(remote.yBodyRot + f5 * 0.2F);

        for (; remote.yBodyRot - remote.yBodyRotO < -180F; remote.yBodyRotO -= 360F) {}
        for (; remote.yBodyRot - remote.yBodyRotO >= 180F; remote.yBodyRotO += 360F) {}
    }

    // 🔴 (BUG D fix, 2026-05-13) sm_handleRemoteFlyingExitYSync 제거. SmartMovingClient packet
    //   lambda 안 "비행 종료 분기" 가 대체. self side standupIfPossible 의 분기 1/2/3 1:1 매핑.
}
