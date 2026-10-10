package choco.ratel.smartmoving.mixin.client;

import choco.ratel.smartmoving.client.SmartMovingClientState;
import choco.ratel.smartmoving.client.SmartMovingAvatarRenderState;
import choco.ratel.smartmoving.client.SmartMovingRenderContext;
import choco.ratel.smartmoving.config.SmartMovingConfig;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 9-6: getPositionOffset() — crawling/head jump render Y offset.
 * 12-3: setupTransforms() — SM movement state body X tilt + bodyYaw stub.
 * 12-6: getPositionOffset() — apply heightOffset rendering.
 * renderName(): other players' name tag — hide crawl + heightOffset Y correction.
 */
@Mixin(AvatarRenderer.class)
@Environment(EnvType.CLIENT)
public class MixinPlayerEntityRenderer {

    @Unique private AvatarRenderState sm_currentRenderState;

    // 🔴 (Phase 2 fix-3-1) Move 3 mixin statics → SmartMovingClientState instance field.
    //   Previous smBodyYawActive, smBodyYawOverride, smFlyingExtraYaw are mixin single instance
    //   so mix to last value during multiplayer two-player simultaneous render → bodyYaw force wrong (BUG-C).
    //   Uses: sm_captureBodyYaw sets via sm instance, sm_modifyBodyYaw / sm_setupTransforms_TAIL
    //   (ModifyArg / TAIL) read via SmartMovingClientState.currentRenderTargetSm().

    /**
     * [9-6][12-6] getPositionOffset() override.
     *
     * SM crawling (self): SWIMMING pose offset (setupTransforms translate(0,-1,0.3) removed)
     *                     return vanilla sneak offset instead (-scale × 0.125).
     * SM head jump (self): apply heightOffset(-1F) rendering.
     * SM other players sneak+crawl (B-14 / §16-22): correct render Y position +0.125 (prevent ground clipping).
     *
     * Original: SmartMovingRender.renderPlayerAt() → d1 += heightOffset
     *           SmartMovingPlayerBase.getYOffset() → -0.125F
     *           SmartMovingRender L124-L125: other players sneak+crawl → d1 += 0.125
     */
    @Inject(method = "getRenderOffset(Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;)Lnet/minecraft/world/phys/Vec3;",
            at = @At("HEAD"), cancellable = true)
    private void sm_getPositionOffset(AvatarRenderState renderState,
                                       CallbackInfoReturnable<Vec3> cir) {
        if (!((Object) renderState instanceof SmartMovingAvatarRenderState smartState)) return;
        AbstractClientPlayer entity = smartState.sm_getPlayer();
        if (entity == null) return;
        // 🔴 (Phase 2 multi BUG-3) Unify ClientPlayerEntity branch → handle all players.
        //   Previous: self only apply -1m correction when isCrawling/isCrawlClimbing/isSliding. remote not applied →
        //             user report "sliding floating 1 block above" + crawling/CC also same BUG.
        //   Change: process all players. Previous remote +0.125 (= prevent ground clipping) is separate case.
        SmartMovingClientState sm = SmartMovingClientState.get(entity);

        // Head jump: model origin = entity.y. Box handling (mixin offset active + POSE=SLIDING)
        //   box.minY = entity.y +1m. When applying additional -1m offset model head is 0.5m below box feet
        //   = below box (user report "1 block lower", 2026-05-11).
        //   Same pattern as isSliding (= entity.y origin) → model root = entity.y +1.501m = inside box.
        //   See feedback_sliding_entity_y_push_offset_zero.md.
        if (sm.isHeadJumping && sm.heightOffset != 0f) {
            cir.setReturnValue(Vec3.ZERO);
            return;
        }

        // Crawling: SM crawling offset instead of SWIMMING pose offset
        // Original SmartMovingRender L156-L161: apply heightOffset (= both self + remote).
        if (sm.isCrawling) {
            cir.setReturnValue(new Vec3(0D, -1.0D - entity.getScale() * 0.06D, 0D));
            return;
        }
        if (sm.isCrawlClimbing) {
            cir.setReturnValue(new Vec3(0D, -1.0D, 0D));
            return;
        }
        if (sm.isSliding) {
            // 🔴 fix #61 v3 (2026-05-10):
            //   On isSliding entry entity.y -= 1m (= move(0,-1,0) effect, original 1.7.10 1:1).
            //   mixin offset guard matches → box feet = entity.y + 1m = ground (normal).
            //   vanilla render formula = model origin = entity.y + offsetY. When offsetY=0 model feet
            //   = box feet (= same behavior as STANDING when entity.y=ground).
            //   isCrawling branch -1.06m because on isCrawling entry entity.y unchanged (= move not called,
            //   original 1:1) so additional -1m correction needed. isSliding entity.y already -1m pushed →
            //   no additional correction.
            cir.setReturnValue(Vec3.ZERO);
            return;
        }
        // 🔵 (2026-05-20, fix #111 — correct fix #110) swim/dive model position.
        //   Previous fix #110 mapping `-1.0 - scale*0.06` borrowed isCrawling pattern but
        //   missed box difference 1m → model 1m further below box (= user report "1 block lower" lingers).
        //   - isCrawling: box = (entity.y, entity.y+0.8) (mixin offset blocked, eye=0.62).
        //     model root correction = -1.06m → align inside box after model rotation.
        //   - swim/dive: box = (entity.y+1, entity.y+1.8) (mixin offset active, eye=1.62 fix #102).
        //     box pushed up 1m → model also +1m further up = isCrawling formula + 1m.
        //   Result: -1.06 + 1.0 = -0.06 ≈ Vec3d.ZERO (= same as sliding pattern).
        //   Sliding case: entity.y itself -1m pushed → align inside box with ZERO offset.
        //   swim/dive: entity.y unchanged + box +1m up → align same visual with ZERO offset.
        if (sm.isSwimming_sm || sm.isDiving) {
            cir.setReturnValue(Vec3.ZERO);
            return;
        }

        // Other players (B-14 / §16-22 / original SmartMovingRender L124-L125):
        // !isOwnPlayer && entity.isSneaking() && isCrawl → d1 += 0.125 (prevent ground clipping)
        //   Already handled in isCrawling branch above so unreachable. (preserve legacy only)
    }

    /**
     * [12-3] setupTransforms() HEAD injection — SM bodyYaw override calculation.
     *
     * In isClimbing/isCrawlClimbing/isCeilingClimbing/isSwimming_sm/isDiving/isSliding/
     * isHeadJumping/isCrawling states, force bodyYaw with forwardRotation (movement direction angle).
     * Calculation results stored in smBodyYawActive/smBodyYawOverride for @ModifyArg to read.
     *
     * Original: SmartMovingRender.rotatePlayer() → forwardRotation = Math.atan2(-vel.x, vel.z)
     */
    @Inject(method = "setupRotations(Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;FF)V", at = @At("HEAD"))
    private void sm_captureBodyYaw(AvatarRenderState renderState, PoseStack matrices,
                                    float bodyYaw, float scale,
                                    CallbackInfo ci) {
        sm_currentRenderState = renderState;
        if (!((Object) renderState instanceof SmartMovingAvatarRenderState smartState)) return;
        AbstractClientPlayer player = smartState.sm_getPlayer();
        if (player == null) return;
        float tickDelta = smartState.sm_getTickDelta();
        float animationProgress = renderState.ageInTicks;
        // 🔴 (Phase 2 fix-3-1) flag reset also per-player instance — separate two players in multiplayer.
        SmartMovingClientState sm = SmartMovingClientState.get(player);

        sm.smBodyYawActive = false;
        sm.smFlyingExtraYaw = 0f;
        sm.smStandardFadeActive = false;  // falling/standard state fade flag reset.
        sm.smFallingFadeMode = false;     // falling mode flag reset.
        sm.smCrawlMode = false;            // isCrawl-only flag reset (skip head correction).
        // 🔴 (session 52b): cache partial tick (bypass Mixin private static constraint — use SmartMovingClientState).
        //   for calling getCurrentSpeed/getTotalDistance lerped getters in setAngles inject (sm_animateFlying, etc.).
        //   tickDelta is player-agnostic cursor so keep static.
        SmartMovingClientState.globalCachedTickDelta = tickDelta;
        // 🔴 (2026-04-27) cache animationProgress — for calculating deltaT in falling/standard state body fade formula.
        sm.smCachedAnimationProgress = animationProgress;
        // body fade adjustment skip guard (true when force branch).
        sm.smBodyYawActive_publicShared = false;
        // 🔴 (Phase 1-A) bodyYaw force/fade handle local only.
        //   force value = sm.stats.currentHorizontalAngle, etc. — stats calculate local only per tick,
        //   not synced to other players → apply force default 0 → wrong yaw → "front/back reversed" BUG.
        //   Other players keep vanilla bodyYaw as-is (= use server-sync entity.bodyYaw).

        // 🔴 (Phase 2 fix-3-2) Remove ClientPlayerEntity guard — process force/fade branches for all players.
        //   force values (sm.stats.currentXxx) are position delta-based stats from fix-2 so remote also accurate.
        //   Keep localPlayer variable name as-is but actually = all players (local + remote, AbstractClientPlayerEntity).
        AbstractClientPlayer localPlayer = player;
        // 🔴 (Phase 2 fix-3-2) Make cfg.enabled check self-only — consistent with BUG-CONFIG-2 fix.
        //   self disabled → all own SM states false → automatically vanilla.
        //   remote enters SM branch regardless of cfg → apply force by SM state.
        if (!choco.ratel.smartmoving.client.SmartMovingClient.isSmRenderEnabled(player)) return;
        // 🔴 (Phase 2 fix-3-1) sm instance already defined in reset stage (L139). Remove redeclaration.

        // Original SmartMovingRender.rotatePlayer L271-L274 condition 1:1.
        // Original doesn't have isRopeSliding/isCrawling alone, instead includes isClimbCrawling.
        // Dodges (isAngleJumping) are left out on purpose: the body keeps its normal lagged yaw instead of
        // snapping to the camera on takeoff, and the dodge pose is computed relative to that yaw.
        boolean smActive = sm.isClimbing || sm.isClimbCrawling || sm.isCrawlClimbing
                || sm.isFlying || sm.isSwimming_sm || sm.isDiving
                || sm.isCeilingClimbing || sm.isHeadJumping
                || sm.isSliding || sm.isRolling;

        // Landing roll (docs/research_movement_prototypes.md): the body faces the roll direction for
        // the whole tumble; mouse yaw does not turn it. Same head handling as sliding.
        if (sm.isRolling) {
            sm.smBodyYawActive = true;
            sm.smBodyYawOverride = sm.rollYaw;
            sm.smCrawlMode = true;
            return;
        }

        // 🔴 User report fix refinement (2026-05-03 — "after max body rotates sharply following mouse, need smooth lag"):
        //   Original SmartMovingRender L147 force branch doesn't include isCrawl alone → vanilla 1.21.1
        //   LivingEntity.turnHead natural behavior (= getMaxRelativeHeadRotation = 50°).
        //
        //   Original SmartRenderModel L208-L209: bipedOuter.rotateAngleY = actualRotation /
        //     RadiantToAngle (= bodyYaw radians), bipedOuter.fadeRotateAngleY = true (player).
        //   Original fadeIntermediate (ModelRotationRenderer L323-L325) + GetIntermediateAngle (L347-L364):
        //     result = prev + (target - prev) * deltaT * 0.2F
        //   = bipedOuter.Y gets additional 0.2 lerp → body follows head smoothly.
        //
        //   1st fix (smStandardFadeActive=false) → vanilla behavior as-is → max works OK but body
        //     rotates sharply following head (user report).
        //   2nd fix (smStandardFadeActive=true + as-is) → fade lag applies but sm_modifyNetHeadYaw
        //     head correction (netHeadYaw + bodyYaw_diff) affects head.roll → max 50° breaks.
        //   Real fix: smStandardFadeActive=true for body fade lag + smCrawlMode=true to skip head correction.
        if (sm.isCrawling && !sm.isClimbing) {
            sm.smStandardFadeActive = true;   // body fade lag (= smoothness)
            sm.smFallingFadeMode = false;
            sm.smCrawlMode = true;             // skip head correction (= maintain max 50°)
            return;
        }

        if (!smActive) {
            // 🔴 (2026-04-27) falling/standard state branch — two modes:
            //   - falling: force mapping (smBodyYawActive=true; smBodyYawOverride=0) + head force.
            //             same as flying pattern. head/body fade lag together.
            //   - standard state (on ground): keep smBodyYawActive=false → ModifyArg receives vanilla bodyYaw,
            //             applies fade lerp then force (inside sm_modifyBodyYaw).
            //             = vanilla natural behavior (keyboard movement rotates body) + additional fade lag (mouse smoothness).
            //   User report flow:
            //     1) "smooth head/body rotation speed difference during mouse rotation" → need fade lag.
            //     2) "body stiff during keyboard left/right movement" → shouldn't disable vanilla force 0.
            //     3) "lag disappears during mouse rotation" → re-enable fade.
            //   Solution: ModifyArg doesn't force=0, only adds fade to vanilla bodyYaw.
            //   During elytra gliding exclude from falling mode to preserve vanilla FALL_FLYING rotation.
            boolean isFalling = !localPlayer.onGround()
                    && localPlayer.fallDistance > SmartMovingConfig.Config.fallAnimationDistanceMinimum
                    && !localPlayer.isFallFlying()
                    && !localPlayer.isInWater();
            if (isFalling) {
                // 🔴 (2026-04-27) falling mapping change — same ModifyArg lerp mode as standard.
                //   Previous (force=0 + additional R_y) mapping neutralized vanilla setupTransforms natural rotation
                //   → missing immediate part of original's "vanilla immediate + bipedOuter fade" dual effect.
                //   User report: WASD rotation during falling more lag than original.
                //   Solution: keep smBodyYawActive=false → sm_modifyBodyYaw applies only
                //             fade lerp over vanilla bodyYaw → vanilla immediate effect + additional fade lag (original 1:1).
                //   Keep smFallingFadeMode=true — for triggering sm_animateFalling head.yaw=0 force +
                //   sm_modifyNetHeadYaw correction skip (head/body rotate together).
                sm.smStandardFadeActive = true;
                sm.smFallingFadeMode = true;
            } else {
                // standard state: ModifyArg in fade lerp apply mode. Keep smBodyYawActive=false.
                sm.smStandardFadeActive = true;
                sm.smFallingFadeMode = false;
            }
            return;
        }

        // 🔵 (2026-05-21, fix #119) Remove isLevitating branch — guarantee swim/dive branch matches first.
        //   Log measurement cause (docs/log_temp.txt):
        //   - tick 237/238 (= movement end, isLevitating=false): swim/dive branch match → fix #118
        //     4-stage pattern applied (smBodyYawOverride=0 + extraYaw fade + entity.bodyYaw force).
        //   - tick 239+ (= idle isLevitating=true): existing isLevitating branch match + early return →
        //     fix #118 swim/dive branch unreached.
        //   Existing isLevitating branch formula `smBodyYawOverride = currentCameraAngle (deg)`:
        //     → vanilla matrix R_y(180 - camera_deg) → matrix itself tracks mouse immediately (no fade).
        //     → smSwimDiveExtraYaw_target not updated → last value persists.
        //     → entity.bodyYaw no force → vanilla netHeadYaw persists.
        //   = User report "idle mouse rotation → body also rotates" exact cause.
        //   Solution: Remove isLevitating branch. swim/dive branch maintains equivalent formula `dist < threshold ? camera : horizontal`
        //     (= idle dist=0 < 0.015 → camera match) + fix #118 pattern (matrix cancel +
        //     extraYaw fade + entity.bodyYaw force) applied.
        //   Original 1:1: SmartMovingRender L132-L134 "levitating forces currentHorizontalAngle = camera"
        //     naturally matches via swim/dive branch idle threshold (= formula equivalent).

        // ── Original SmartMovingModel per-state bipedOuter.rotateAngleY 1:1 port ──────
        // 1.21.1 has no bipedOuter hierarchy → approximate via single bodyYaw path. Radian→degree conversion.
        // Original if-else chain priority order:
        //   1. isRopeSliding (L279) — currentHorizontalAngle
        //   2. isClimb/isClimbCrawling (L308) — forwardRotation / RadiantToAngle
        //   4. isCeilingClimb (L463+L476) — rotateY + horizontalAngle (threshold 0.015)
        //   5. isSwim (L495/L511) — horizontalAngle (threshold 0.005/0.015 isGenericSneaking)
        //   6. isDive (L553/L564) — horizontalAngle (based on totalDistance)
        //   7. isCrawl (L668) — currentHorizontalAngle
        //   9. isFlying (L704/L713) — horizontalAngle (threshold 0.05)
        //  10. isHeadJumping (L740) — currentHorizontalAngle

        // isCeilingClimb (original L463 + L475-L476):
        //   distance = totalHorizontalDistance * 0.7F
        //   walkFactor = Factor(currentHorizontalSpeed, 0, 0.12951545F)  // 0→1 interpolation
        //   horizontalAngle = horizontalDistance < 0.015F ? currentCameraAngle : currentHorizontalAngle
        //   rotateY = cos(distance) * 0.44F * walkFactor
        //   bipedOuter.rotateAngleY = rotateY + horizontalAngle
        if (sm.isCeilingClimbing) {
            float distance = sm.stats.totalHorizontalDistance * 0.7F;
            float x = sm.stats.currentHorizontalSpeed;
            float walkFactor = (x >= 0.12951545F) ? 1F : (x <= 0F ? 0F : x / 0.12951545F);
            float horizontalAngle = sm.stats.horizontalDistance < 0.015F
                    ? sm.stats.currentCameraAngle
                    : sm.stats.currentHorizontalAngle;
            float rotateY = (float) Math.cos(distance) * 0.44F * walkFactor;
            // 🔴 Apply fade interpolation (user report — body rotation interpolation differs from original):
            //   Original SmartRenderModel L209: bipedOuter.fadeRotateAngleY = true (default).
            //   ModelRotationRenderer.fadeIntermediate lerps every frame target by 0.2*deltaTime.
            //   1.21.1 mapping: use lerpFadeAngle helper (radian unit, 0.2 factor). Adopted flying pattern.
            float targetYawRad = rotateY + horizontalAngle;
            float laggedYawRad = lerpFadeAngle(sm.smCeilingYaw_prev, targetYawRad,
                                               sm.smCeilingFade_prevTime, animationProgress);
            sm.smCeilingYaw_prev = laggedYawRad;
            sm.smCeilingFade_prevTime = animationProgress;
            sm.smBodyYawActive = true;
            sm.smBodyYawOverride = (float) Math.toDegrees(laggedYawRad);
            return;
        }

        // isSwim/isDive (원본 L495/L553)
        // 🔵 (2026-05-21, fix #118) mouse 회전 + bodyYaw + 머리 회전 BUG fix — isFlying L333-L352
        //   검증된 4 단계 패턴 1:1 차용.
        //   사용자 보고: (1) 머리 mouse 추적 X, (2) 가만히 시 카메라만 (몸 회전 X = fade lag),
        //     (3) wasd + mouse 시 이동 방향 (lag).
        //   원본 mechanism (agent 실측 cause):
        //     - SmartMovingRender L148: renderYawOffset = forwardRotation (= lerpedYaw, camera).
        //     - SmartMovingModel L333/L375: bipedOuter.rotateAngleY = horizontalAngle (= 가만히 camera,
        //       이동 시 이동방향, fadeRotateAngleY=true 매 frame 0.2*deltaT lerp).
        //     - SmartRenderRender L167: actualRotation=0 → vanilla glRotatef(180) → bodyYaw 시각 효과 0.
        //     = 두 다른 yaw 값. 우리 매핑은 단일 smBodyYawOverride 으로 혼동 + fade 누락 + vanilla
        //       matrix cancel 누락.
        //   isFlying 패턴 1:1:
        //     - smBodyYawOverride = 0f → vanilla POSITIVE_Y(180) → 모델 정면 정상 (matrix cancel 등가).
        //     - smSwimDiveExtraYaw_target = horizontalAngle → sm_setupTransforms TAIL 에서 fade Y 회전.
        //     - entity.bodyYaw = lerpedYaw force → vanilla setAngles 의 netHeadYaw = headYaw-bodyYaw ≈ 0
        //       → 머리 mouse 추적 X.
        if (sm.isSwimming_sm || sm.isDiving) {
            float threshold = sm.isSlow ? 0.005F : 0.015F;
            double dist = sm.isDiving ? sm.stats.totalDistance : sm.stats.horizontalDistance;
            float horizontalAngle = dist < threshold
                    ? sm.stats.currentCameraAngle
                    : sm.stats.currentHorizontalAngle;
            sm.smBodyYawActive = true;
            sm.smBodyYawOverride = 0f;                              // matrix cancel 등가
            sm.smSwimDiveExtraYaw_target = horizontalAngle;         // fade 보간 target (radian)

            // entity.bodyYaw force — 원본 SmartMovingRender L148 1:1.
            //   vanilla netHeadYaw = (headYaw_lerped - bodyYaw_lerped) → bodyYaw=lerpedYaw 시 ≈ 0.
            float lerpedYaw = localPlayer.yRotO + (localPlayer.getYRot() - localPlayer.yRotO) * tickDelta;
            localPlayer.setYBodyRot(lerpedYaw);
            localPlayer.yBodyRotO = lerpedYaw;
            return;
        }

        // 🔴 BUG-27/32 진짜 원인 정정 (Flying Phase / 세션 47): 원본 1:1 정확 매핑.
        //   원본 SmartMovingRender.rotatePlayer L145-L148:
        //     forwardRotation = prevRotationYaw + (rotationYaw - prevRotationYaw) * f2 (= lerpedYaw)
        //     if (isFlying || ...) entityplayer.renderYawOffset = forwardRotation
        //   = **player.bodyYaw 를 lerpedYaw (마우스 yaw) 로 강제** — 이동 방향 (horizontalAngle) 아님!
        //   이로 인해 vanilla setAngles 의 head.yaw = headYaw - bodyYaw = headYaw - lerpedYaw ≈ 0
        //   = 머리가 몸과 정렬 = 마우스 좌우 시 몸+머리 같이 회전 = 슈퍼맨 자세 자연스러움.
        //
        //   원본 SmartMovingModel.isFlying L486:
        //     bipedOuter.rotateAngleY = horizontalAngle (이동 방향)
        //   = 모델 전체 추가 Y 회전 — 모델 root pivot (= head pivot) 기준.
        //
        //   이전 1.21.1 잘못 매핑: bodyYaw 인자만 ModifyArg 로 horizontalAngle 변경.
        //     1) entity.bodyYaw field 변경 안 함 → vanilla setAngles 의 head.yaw 가 자연 값
        //        (마우스 좌우 시 head.yaw 따로 변함) → 사용자 보고 BUG-32 "머리 이상 고정/이상".
        //     2) bipedOuter.Y (= horizontalAngle 추가 회전) 효과 부재 → 모델이 이동 방향으로
        //        안 따라감 → 사용자 보고 "전진 시 몸 중심점 다름" + BUG-27 효과.
        //   정정:
        //     - smBodyYawOverride = lerpedYaw (마우스 yaw, 도)
        //     - localPlayer.bodyYaw = localPlayer.prevBodyYaw = lerpedYaw 직접 강제 (vanilla
        //       setAngles 의 head.yaw 계산 영향)
        //     - 추가 Y 회전 (horizontalAngle - lerpedYaw) 은 sm_setupTransforms TAIL 에서 처리
        //       (smFlyingExtraYaw 캐시 통해 전달).
        // 🔴 마우스 회전 delay 정밀 매핑 (Flying Phase / 세션 61):
        //   원본 흐름:
        //     1. SmartRenderRender.rotatePlayer L162-L167: actualRotation=0 강제
        //        → vanilla super.rotatePlayer 가 GL 회전 cancel.
        //     2. SmartMovingModel.isFlying L486: bipedOuter.rotateAngleY = horizontalAngle
        //        (절대값, fade 보간 적용).
        //     3. SmartRenderModel L247: fadeIntermediate → bipedOuter.Y_lerped 추적.
        //     4. 모델 회전 = bipedOuter.Y_lerped = horizontalAngle 추적 (fade).
        //   = 마우스 회전 시 모델 fade 천천히 추적 (delay + smooth).
        //
        //   1.21.1 vanilla 차이: setupTransforms 의 POSITIVE_Y(180-bodyYaw) cancel 하면
        //   (bodyYaw=180 시 POSITIVE_Y(0)) 모델 정면 = vanilla model default (north = -Z 좌표) →
        //   정면 반대 (세션 58 사용자 보고).
        //   1.21.1 vanilla 가 POSITIVE_Y(180) 적용 시 모델 정면 = south (정상). 즉 vanilla 의
        //   "no rotation" 효과 = bodyYaw=0 (POSITIVE_Y(180-0)=POSITIVE_Y(180)).
        //
        //   매핑:
        //     - smBodyYawOverride = 0f → vanilla POSITIVE_Y(180) = 모델 정면 정상.
        //     - entity.bodyYaw / prevBodyYaw 강제 제거 (vanilla 자연 처리, ModifyArg 가 0 으로 무력화).
        //     - smFlyingExtraYaw = horizontalAngle (절대값) — fade 보간 추적.
        //     - sm_animateFlying head.yaw = 0 강제 (세션 60 — bipedHead reset 효과).
        //   결과: 모델 회전 = POSITIVE_Y(180) (정면 정상) + extraYaw_lerped (= horizontalAngle 추적, fade).
        //         vanilla 자연 bodyYaw 무력화 (ModifyArg 0).
        //         마우스 회전 시 horizontalAngle 변화 → fade 천천히 추적 → 모델 delay.
        if (sm.isFlying) {
            float horizontalAngle = sm.stats.horizontalDistance < 0.05F
                    ? sm.stats.currentCameraAngle
                    : sm.stats.currentHorizontalAngle;
            sm.smBodyYawActive = true;
            sm.smBodyYawOverride = 0f;  // vanilla POSITIVE_Y(180-0)=POSITIVE_Y(180) → 모델 정면 정상.
            // bipedOuter.rotateAngleY 효과: horizontalAngle (절대값, fade 보간).
            sm.smFlyingExtraYaw = horizontalAngle;
            // 🔴 (2026-04-27) 원본 SmartMovingRender L145-L148 1:1 복구.
            //   `entity.renderYawOffset = forwardRotation (=lerpedYaw)` 매 frame 강제.
            //   비행 동작 자체엔 영향 없음 (sm_modifyBodyYaw ModifyArg 가 0 으로 덮어씀,
            //   sm_animateFlying TAIL 이 head.yaw=0 force).
            //   효과: 비행 종료 → standard/falling 진입 시 vanilla netHeadYaw ≈ 0 →
            //   setAngles 의 head.yaw 점프 사라짐 (사용자 보고 "비행→낙하 사이 머리 끊김" 해소).
            //   세션 61 에서 제거됐던 코드를 원본 1:1 분석 후 복구.
            float lerpedYaw = localPlayer.yRotO + (localPlayer.getYRot() - localPlayer.yRotO) * tickDelta;
            localPlayer.setYBodyRot(lerpedYaw);
            localPlayer.yBodyRotO = lerpedYaw;
            return;
        }

        // (2026-05-03 정리) 이전 isCrawling 단독 가드는 위 !smActive 분기에서 이미 처리됨 → 도달 X.
        //   진짜 fix 는 sm_captureBodyYaw 의 !smActive 분기 위 isCrawl && !isClimbing 가드 (위 참조).
        // isHeadJumping/isRopeSliding (원본 L740/L279) — threshold 없이 currentHorizontalAngle
        // 🔴 fix #91 (2026-05-13, 사용자 보고 "헤드점프 중 벽 박을 때 몸 회전 중간 이어짐 없음"):
        //   원본 SmartRenderModel L208-209: bipedOuter.fadeRotateAngleY = true (기본).
        //   ModelRotationRenderer.fadeIntermediate → 0.2 * deltaTime lerp. 벽 박은 후
        //   vx 반전 시 currentHorizontalAngle 점진 변화 → 모델 자연 회전 추적.
        //   isCeilingClimbing 동일 패턴 (= L250-256) 차용. 외 분기 prev 갱신은 아래 (= 비행 prev 갱신 패턴).
        // 🔵 (2026-05-14, fix #98 위치 기반) smRemoteHJVisualHold OR 추가 — REMOTE 공중 자세 hold.
        //   사용자 보고 3번 BUG (= 콜리전 땅 닿기 전 헤드점프 끝남) fix. SmartMovingClient packet
        //   처리 안 공중 상태 (= curY > groundY+0.005) 검출 시 hold=true.
        //   sm_tickStatsForRemote TAIL 의 위치 비교 (= REMOTE.y <= groundY+0.005) 시 hold=false.
        if (sm.isHeadJumping || sm.isRopeSliding || sm.smRemoteHJVisualHold) {
            float targetYawRad = sm.stats.currentHorizontalAngle;
            // 🔴 fix #94/#95 (2026-05-13, 사용자 보고 "여우무빙 시 원래 몸 방향 → 바라보는 방향 보간"):
            //   fix #94: wasSelfSlideFire 가드 추가. fix #95: 발사 frame 만 snap.
            //   원인: fix #91 의 fade lerp 가 wasSelfSlideFire 가드 누락 → 여우무빙 발사 시
            //     prev=직전 vanilla bodyYaw → 5 tick lerp.
            //   fix #94 (= 매 frame prev=target): 발사 즉시 도달 ✓. 그러나 wasSelfSlideFire 잔존
            //     동안 매 frame instant → 벽 박을 때 currentHorizontalAngle 변화 시 fade 사라짐.
            //   fix #95 (= 발사 frame 만 snap + flag): 발사 frame 만 prev=target. 그 후 자연 fade
            //     (= fix #91 효과). 벽 박을 때 자연 보간 유지.
            //   X 회전 (fix #81 v2) 은 target=π/2 고정이라 매 frame snap 무관. Y 회전 target=
            //     currentHorizontalAngle 동적이라 snap 1회 한정 필요.
            if (sm.wasSelfSlideFire && !sm.smHeadJumpYawSnapDone) {
                sm.smHeadJumpYaw_prev = targetYawRad;
                sm.smHeadJumpYawFade_prevTime = animationProgress;
                sm.smHeadJumpYawSnapDone = true;
            }
            float laggedYawRad = lerpFadeAngle(sm.smHeadJumpYaw_prev, targetYawRad,
                                                sm.smHeadJumpYawFade_prevTime, animationProgress);
            sm.smHeadJumpYaw_prev = laggedYawRad;
            sm.smHeadJumpYawFade_prevTime = animationProgress;
            sm.smBodyYawActive = true;
            sm.smBodyYawOverride = (float) Math.toDegrees(laggedYawRad);
            return;
        }

        // 🔴 BUG-Slide-Anim-1 fix (2026-05-04): 원본 SmartMovingModel L447 1:1 복원.
        //   원본 isSlide 분기:
        //     L446 bipedOuter.fadeRotateAngleY = false   (fade 보간 비활성)
        //     L447 bipedOuter.rotateAngleY = currentHorizontalAngle   (이동 방향, 절대값)
        //   원본 rotatePlayer L147 의 renderYawOffset = forwardRotation 은 vanilla setupTransforms
        //   가 R_y(-forwardRotation) cancel 효과 → 그 후 bipedOuter.Y = currentHorizontalAngle 직접
        //   적용 → 최종 모델 yaw = currentHorizontalAngle (이동 방향). 마우스 회전 무관 → 모델은
        //   진입 시점 이동 방향 유지 = 슬라이딩 미끄러짐 자연스러움.
        //
        //   이전 1.21.1 매핑은 fallthrough (smBodyYawOverride = forwardRotation = 마우스 yaw lerp)
        //   라 마우스 따라 모델 같이 회전 → 사용자 보고 BUG.
        //   fix: smBodyYawOverride = currentHorizontalAngle (도) — 원본 1:1.
        //   fade 비활성 (원본 L446) → isHeadJumping/isRopeSliding 패턴 동일 (단순 적용).
        // 🔴 BUG-Slide-Anim-A fix (2026-05-04 사용자 보고 — "마우스 회전 시 머리 좌우 흔들림"):
        //   원본 1.7.10: SR 의 renderYawOffset = forwardRotation 강제 → entity.bodyYaw 자체
        //     force → vanilla netHeadYaw = headYaw - bodyYaw = 마우스 - 마우스 ≈ 0 → head.rotateAngleZ
        //     ≈ 0 → 흔들림 X.
        //   1.21.1 우리 매핑: sm_modifyBodyYaw (ModifyArg) 만 force. entity.bodyYaw field 자연 lerp
        //     (마우스 yaw 추적). vanilla netHeadYaw ≈ 0 (자연 lerp 결과).
        //     ★ 그러나 sm_modifyNetHeadYaw 가 추가 보정 = netHeadYaw + (natural - lagged) =
        //       0 + (마우스 yaw - 이동 방향) = 마우스 회전 시 큰 값 → head.roll = -netHeadYaw_rad
        //       변화 → 머리 좌우 기울어짐 BUG.
        //   엎드리기 isCrawling 분기 (위 L179) 와 동일 패턴: smCrawlMode=true → sm_modifyNetHeadYaw
        //     보정 skip → vanilla netHeadYaw (≈ 0) 그대로 → head.roll ≈ 0.
        if (sm.isSliding) {
            sm.smBodyYawActive = true;
            sm.smBodyYawOverride = (float) Math.toDegrees(sm.stats.currentHorizontalAngle);
            sm.smCrawlMode = true;   // ★ sm_modifyNetHeadYaw 보정 skip
            // 🔴 BUG-Slide-Anim-A2 fix (2026-05-04 사용자 보고 — "마우스 회전 시 머리 흔들림 안 사라짐"):
            //   원본 SmartMovingRender.rotatePlayer L145-L148 1:1: SM 활성 분기 (isSliding 포함)
            //   에서 `entity.renderYawOffset = forwardRotation (=lerpedYaw)` 매 frame 강제.
            //   메모리 feedback_entity_bodyyaw_force_branch_transition.md 패턴.
            //   비행 분기 (위 L332-L334) 적용 패턴 동일 차용.
            //
            //   원리:
            //     - vanilla setAngles `netHeadYaw = entity.headYaw_lerp - entity.bodyYaw_lerp`.
            //     - 이전 매핑: entity.bodyYaw 자연 lerp (이동 방향 따라 vanilla 처리) → 마우스
            //       yaw 와 차이 → netHeadYaw ≠ 0 → head.roll = -netHeadYaw_rad 변화 → 머리 좌우
            //       흔들림 BUG.
            //     - fix: entity.bodyYaw 를 마우스 yaw 로 force → headYaw_lerp = bodyYaw_lerp →
            //       netHeadYaw = 0 → head.roll = 0 → 흔들림 X.
            //   smBodyYawOverride (= currentHorizontalAngle, ModifyArg) 는 setupTransforms 의
            //   bodyYaw 인자만 수정 → 모델 회전은 이동 방향 (별개). entity.bodyYaw force 와 무관.
            float lerpedYaw = localPlayer.yRotO
                    + (localPlayer.getYRot() - localPlayer.yRotO) * tickDelta;
            localPlayer.setYBodyRot(lerpedYaw);
            localPlayer.yBodyRotO = lerpedYaw;
            return;
        }

        // 나머지(isClimb/isClimbCrawling/isCeilingClimb) — 원본 rotatePlayer L269:
        //   forwardRotation = prevRotationYaw + (rotationYaw - prevRotationYaw) * f2 (player yaw 보간, 도)
        // isClimb/ClimbCrawling 은 원본 L308 forwardRotation/RadiantToAngle(라디안) 과 동등.
        // isCeilingClimb 의 `rotateY + horizontalAngle`(L476) 은 rotateY 공식 이식 필요 — 후속.
        sm.smBodyYawActive = true;
        sm.smBodyYawOverride = localPlayer.yRotO + (localPlayer.getYRot() - localPlayer.yRotO) * tickDelta;
    }

    /**
     * [12-3] setupTransforms() @ModifyArg — super.setupTransforms() 호출 시 bodyYaw(index=3) 교체.
     *
     * sm_captureBodyYaw에서 smBodyYawActive=true인 경우에만 forwardRotation으로 대체한다.
     * B-17 확인: index=3 = bodyYaw (entity=0, matrices=1, animationProgress=2, bodyYaw=3, tickDelta=4, scale=5)
     */
    @ModifyArg(
        method = "setupRotations(Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;FF)V",
        at = @At(value = "INVOKE",
                 target = "Lnet/minecraft/client/renderer/entity/LivingEntityRenderer;setupRotations(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;FF)V"),
        index = 2
    )
    private float sm_modifyBodyYaw(float bodyYaw) {
        AvatarRenderState renderState = sm_currentRenderState;
        if (renderState == null
                || !((Object) renderState instanceof SmartMovingAvatarRenderState smartState)
                || smartState.sm_getPlayer() == null) return bodyYaw;
        SmartMovingClientState sm = SmartMovingClientState.get(smartState.sm_getPlayer());

        // 🔴 fix #170 (2026-05-27): 인벤토리 화면 그리기 시점 fade lerp skip — vanilla bodyYaw 통과.
        //   사용자 보고: "인벤토리 창 좌상단에 보이는 플레이어 모델의 몸이 막 뒤틀리고 있어".
        //   dump 분석 (docs/log_temp.txt [INV-PRE]/[INV-POST]):
        //     매 frame 2 setupTransforms 호출 (= 일반 render + InventoryScreen.drawEntity 자체 호출).
        //     Pattern A (bodyYaw=180, vanilla 인벤토리 임시 set) ↔ Pattern B (bodyYaw=164.9, 사용자
        //     실제 yaw). sm.applyFadeAngleDegrees prev 갱신 매 호출 → 두 target 사이 prev oscillate
        //     → matrix bodyYaw 매 frame 변동 → 모델 회전 매 frame 변동 + setAngles headYaw 인자
        //     변동 → head.yaw 매 frame 변동 = "막 뒤틀림" 시각.
        //   해결: 인벤토리 화면 시점 fade 적용 X (= vanilla bodyYaw 통과). prev 갱신 안 함 →
        //     일반 render 만 fade prev 누적 → oscillate 차단.
        net.minecraft.client.Minecraft _mc = net.minecraft.client.Minecraft.getInstance();
        if (_mc.gui.screen() instanceof net.minecraft.client.gui.screens.inventory.InventoryScreen) {
            smartState.sm_setBodyYaw(bodyYaw);
            return bodyYaw;
        }
        // vanilla 1 frame lerped bodyYaw 캐시 (sm_modifyNetHeadYaw 의 head 보정에 사용).
        sm.smCachedBodyYawNaturalDeg = bodyYaw;
        float result;
        if (sm.smBodyYawActive) {
            sm.smBodyYawActive_publicShared = true;
            // 🔴 (Phase 2 multi BUG-10 v2) FORCE + FADE 결합 분기:
            //   smBodyYawActive=true + smStandardFadeActive=true 동시 → force 값에 fade lerp 적용.
            //   사용처: isCrawling remote (force = currentHorizontalAngle, fade 로 보간 유지).
            if (sm.smStandardFadeActive) {
                float lagged = sm.applyFadeAngleDegrees(sm.smBodyYawOverride);
                sm.smCachedBodyYawLaggedDeg = lagged;
                result = lagged;
            } else {
                sm.smStandardBodyYawPrev = sm.smBodyYawOverride;
                sm.smStandardFadeTimePrev = sm.smCachedAnimationProgress;
                // 비행 외 분기에서 비행 fade prev 갱신용 — ModifyArg 의 실제 적용 결과 캐시.
                sm.smCachedBodyYawLaggedDeg = sm.smBodyYawOverride;
                result = sm.smBodyYawOverride;
            }
        } else if (sm.smStandardFadeActive) {
            // 🔴 (2026-04-27) 기본 상태 fade lerp — vanilla bodyYaw 위에 추가 lag (factor 0.2).
            float lagged = sm.applyFadeAngleDegrees(bodyYaw);
            // head 보정용 캐시 — sm_modifyNetHeadYaw 가 lagged - natural 차이만큼 보정.
            sm.smCachedBodyYawLaggedDeg = lagged;
            result = lagged;
        } else {
            // vanilla 통과 — 비행 fade prev 갱신용 캐시도 vanilla bodyYaw.
            sm.smCachedBodyYawLaggedDeg = bodyYaw;
            result = bodyYaw;
        }
        if (!sm.smCrawlMode && sm.smStandardFadeActive && !sm.smFallingFadeMode) {
            renderState.yRot += sm.smCachedBodyYawNaturalDeg - sm.smCachedBodyYawLaggedDeg;
        }
        smartState.sm_setBodyYaw(result);   // the dodge pose leans relative to this
        return result;
    }


    /**
     * [12-3] setupTransforms() TAIL 주입 — SM 이동 상태별 body X 기울기.
     *
     * vanilla setupTransforms()의 super.setupTransforms()가 이미 Y회전(bodyYaw)을
     * 적용했으므로, 여기에 추가로 SM 고유 X 기울기를 적용한다.
     *
     * 원본: SmartMovingRender.rotatePlayer() → bipedOuter.rotateAngleY 설정
     *       SmartMovingModel.setRotationAngles() → bipedOuter.rotateAngleX 설정
     */
    @Inject(method = "setupRotations(Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;FF)V", at = @At("TAIL"))
    private void sm_setupTransforms(AvatarRenderState renderState, PoseStack matrices,
                                     float bodyYaw, float scale,
                                     CallbackInfo ci) {
        if (!((Object) renderState instanceof SmartMovingAvatarRenderState smartState)) return;
        AbstractClientPlayer player = smartState.sm_getPlayer();
        if (player == null) return;
        float tickDelta = smartState.sm_getTickDelta();
        float animationProgress = renderState.ageInTicks;
        // 🔴 (Phase 1-A) ClientPlayerEntity 가드 제거 — 다른 player render 도 SM 자세 적용.
        //   localPlayer 변수명 그대로 두지만 실제 = 모든 player (local + remote).
        AbstractClientPlayer localPlayer = player;
        SmartMovingClientState sm = SmartMovingClientState.get(localPlayer);

        // BUG-12 (세션 36): SM disabled 시 모든 SM 분기 skip + smOuterTiltX = 0 reset →
        //   매 호출마다 0 으로 정리하므로 cape 클램프도 vanilla fallback (BUG-7 확장).
        sm.smOuterTiltX = 0f;
        // 🔴 (2026-05-05) self → Config.enabled / remote → 항상 true.
        if (!choco.ratel.smartmoving.client.SmartMovingClient.isSmRenderEnabled(player)) return;

        // B-17 capture (§16-25): outer.X 등가 tiltAngle 을 SmartMovingClientState.smOuterTiltX 에
        //   저장해 MixinCapeFeatureRenderer 에서 망토 X 회전 클램프 (70.523° - outerX_deg) 적용.
        //   진입 시 reset — 5 분기 중 한 분기만 활성 시 = 으로 할당, 비활성 시 0 (vanilla 망토 그대로).

        // SM 수영(isSwimming_sm): bipedOuter.rotateAngleX = Quarter - Sixteenth * standSneakFactor
        // 원본: fadeRotateAngleX = true + rotateAngleX = Quarter - Sixteenth * standSneakFactor
        // standSneakFactor: 정지/스니킹=1 → 67.5°, 보행=0 → 90°(완전 수평)
        // 🔵 (2026-05-20, BUG-Swim-Anim-1/3 fix #110) 슬라이딩 L554-565 패턴 1:1 적용:
        //   - pivotY = 1.5 - 3/16 = 1.3125 (= 엎드리기/슬라이딩 동일 머리 중심 회전).
        //   - translate(0, pivotY, 0) → rotate(-tiltAngle) → translate(0, -pivotY, 0)
        //     매트릭스 스택 — pivot 기준 회전. 사용자 보고 "회전 중심 머리 부분" fix.
        //   - POSITIVE_X.rotation(-tiltAngle) 부호 반전 — vanilla scale(-1,-1,1) 보정
        //     (= [[feedback_render_scale_negation]] + BUG-Slide-Anim-2 "하늘 봄" 정확 매치).
        //     사용자 보고 "180도 뒤집혀 하늘 보고 있음" fix.
        // 🔵 (2026-05-20, fix #115) isSwimming_sm + isDiving 통합 fade 분기.
        //   사용자 보고 BUG 1 (수면 도달 시 1자) + BUG 2 (wasd 시 팔다리 이상) 공통 cause =
        //     fadeRotateAngleX 매핑 누락.
        //   원본 SmartMovingModel L331 (isSwim) + L373 (isDive): `bipedOuter.fadeRotateAngleX = true`.
        //   원본 ModelRotationRenderer.fadeIntermediate L315-L345: 매 frame `current = prev +
        //     (target - prev) * deltaTime * 0.2F` 점진 lerp. 진입/전환 시 부드러운 자세 변화.
        //   기존 매핑 = 즉시 R_x(tilt) 적용 → vAngle / sSF 동적 변화 시 즉시 자세 변화 → 사용자
        //     "이상한 흔들림" + "1자 변환" 인지.
        //   해결: lerpFadeAngle 헬퍼 (= 비행/헤드점프 분기 검증 패턴) 차용. swim/dive 통합 분기 +
        //     별도 prev field (smSwimDiveTiltX_prev) 사용.
        //
        //   isJumping 매핑 정정:
        //   원본 isDive `isJump` 변수 = `moving.isJumping()` override 메서드 (SmartMovingSelf L3264)
        //     = vanilla player.jumping field (= raw space bar 누름).
        //   기존 매핑 `sm.isJumping` = SM 자체 1-tick flag (= tryJump 한정, 거의 항상 false) →
        //     dive 분기에서 사용자 jump 꾹누름 시 tilt=0° 도달 안 함.
        //   해결: vanilla LivingEntity.jumping field 직접 사용 (= local player raw key).
        if (sm.isSwimming_sm || sm.isDiving) {
            // 🔵 (2026-05-21, fix #120) Sixteenth 상수 매핑 정정 (π/16 → π/8).
            //   agent 실측 원본 SmartRenderUtilities.java L22-L26 체인 정의:
            //     Whole = π * 2, Half = π, Quarter = Half/2 = π/2,
            //     Eighth = Quarter/2 = π/4, Sixteenth = Eighth/2 = π/8 (≈ 22.5°).
            //   "Sixteenth" 이름은 Whole(2π) 의 16분의 1 = π/8 (= MixinPlayerEntityModelClient L62
            //     의 SIXTEENTH 상수 = HALF/8 동일 정의). 본 분기는 hardcode `Math.PI/16f` (= π/16
            //     = 11.25°) 잘못 사용 → fix #111 의 오역. 사용자 보고 "가만히 시 덜 기움" cause.
            //   원본 식 (SmartMovingModel L332 isSwim, L374 isDive): `Quarter - Sixteenth × sSF`
            //     또는 `Quarter - Sixteenth`. 가만히 (sSF=1) / levitate → π/2 - π/8 = 3π/8 = 67.5°.
            //   이동 (sSF=0) → π/2 = 90°. 사용자 보고 매치.
            float targetTilt;
            if (sm.isDiving) {
                if (sm.isLevitating) {
                    targetTilt = (float) Math.PI / 2f - (float) Math.PI / 8f;
                } else if (sm_isPlayerJumping(player)) {  // ★ vanilla jumping = raw space bar
                    targetTilt = 0f;
                } else {
                    targetTilt = (float) Math.PI / 2f - sm.stats.currentVerticalAngle;
                }
            } else {  // isSwimming_sm
                targetTilt = (float) Math.PI / 2f - (float) Math.PI / 8f * sm.swimStandSneakFactor;
            }

            // 🔵 fade lerp (= ModelRotationRenderer.fadeIntermediate 1:1).
            //   prev 시작값 = 진입 직전 자세 (= 0, vanilla STANDING). [[feedback_fade_prev_pre_entry_pose]].
            //   진입 frame: prevTime < -100f → lerpFadeAngle 즉시 target return (= snap).
            //   정상 frame: deltaTime * 0.2 lerp → 5 frame 후 ~99% 도달.
            float laggedTilt = lerpFadeAngle(sm.smSwimDiveTiltX_prev, targetTilt,
                                              sm.smSwimDiveFade_prevTime, animationProgress);

            // 🔵 (2026-05-21, fix #118-B) swim/dive Y 회전 fade — 마우스 회전 lag.
            //   원본 bipedOuter.rotateAngleY = horizontalAngle (fadeRotateAngleY=true).
            //   isFlying 분기 (L779-L780, L786-L788) 의 fade Y 회전 패턴 1:1 차용.
            //   가만히 시 horizontalAngle = camera → 즉시 force 시 사용자 보고 "몸 회전 X" 매치 안 됨
            //   → fade 적용 시 매 frame 0.2 lerp → 천천히 추적 (= 사용자 보고 매치).
            //   wasd + mouse 시 horizontalAngle = 이동방향 (motion vector atan2) → mouse 변화 시
            //   motion 변경 → horizontalAngle 추적 + fade lag (= 사용자 보고 매치).
            float yawTarget = sm.smSwimDiveExtraYaw_target;
            float yawLerped = lerpFadeAngle(sm.smSwimDiveExtraYaw_prev, yawTarget,
                                             sm.smSwimDiveFade_prevTime, animationProgress);

            sm.smSwimDiveTiltX_prev = laggedTilt;
            sm.smSwimDiveExtraYaw_prev = yawLerped;
            sm.smSwimDiveFade_prevTime = animationProgress;

            // 매트릭스 stack 적용 (= fix #114 pivotY=1.5 동일).
            //   isFlying 패턴 (L782-L790): translate → R_y(-yaw, scale 부호 반전) → R_x(-tilt) → translate.
            float pivotY = 1.5f;
            matrices.translate(0f, pivotY, 0f);
            if (yawLerped != 0f) {
                // vanilla scale(-1,-1,1) 의 Y axis 부호 반전 보정 — isFlying L786-L788 패턴.
                matrices.rotate(Axis.YP.rotation(-yawLerped));
            }
            matrices.rotate(Axis.XP.rotation(-laggedTilt));
            matrices.translate(0f, -pivotY, 0f);
            sm.smOuterTiltX = laggedTilt;
        } else {
            // swim/dive 비활성 시 fade prev reset (= 다음 진입 시 prev=0 시작).
            //   비행 fade prev reset 패턴 (L866-868) 동일.
            sm.smSwimDiveTiltX_prev = 0f;
            sm.smSwimDiveExtraYaw_prev = 0f;
            sm.smSwimDiveFade_prevTime = animationProgress;
        }

        // SM 슬라이딩(isSliding): bipedOuter.rotateAngleX = Quarter
        // 🔴 BUG-Slide-Anim-2 fix (2026-05-04 — "하늘 봄"): tilt 부호 반전 (scale -1,-1,1 보정).
        // 🔴 BUG-Slide-Anim-3/4 정정 (2026-05-04 사용자 보고 — "몸통이 다리쪽으로 너무 가있음"):
        //   이전 매핑은 엎드리기 isCrawling 분기 (`bipedTorso.rotationPointY=3F` 매핑) 를 슬라이딩에
        //   그대로 따라했음 — 원본 isCrawl 분기 매핑이지 isSlide 분기 매핑 아님.
        //   원본 isSlide L448: `bipedOuter.rotationPointY = 5F` (bipedTorso 가 아니라 bipedOuter).
        //   즉 슬라이딩 정확 매핑:
        //     pivotY = 1.5 - 5/16 = 1.1875 (= bipedOuter pivot world Y, vanilla biped pivot 보다 5/16 위).
        //     추가 translate = -5/16 (= bipedOuter.rotationPointY=+5F 자식 효과).
        //   엎드리기 (3/16) vs 슬라이딩 (5/16) 차이 = 2/16 = 12.5cm 회전 origin 위쪽 + body 위치
        //     12.5cm 회전 후 다리쪽 추가.
        //   ※ 모델 위치 -1m 보정 (박스 안) 은 sm_getPositionOffset 분기에서 처리 (BUG#5).
        if (sm.isSliding) {
            float tiltAngle = (float) Math.PI / 2f; // Quarter
            // 🔴 (2026-05-04 사용자 요청 — "슬라이딩 머리 위치 엎드리기와 통일"):
            //   원본 isSlide L448 = bipedOuter.rotationPointY=5F (= pivotY 1.1875).
            //   원본 isCrawl L405 = bipedTorso.rotationPointY=3F (= pivotY 1.3125).
            //   원본은 두 자세 회전 중심 12.5cm 차이 → 슬라이딩 머리가 엎드리기보다 앞.
            //   사용자 명시 일관성 우선 — 슬라이딩 pivotY 를 엎드리기와 동일 (1.3125) 로 통일.
            float pivotY = 1.5f - 3f / 16f;    // = 1.3125 (엎드리기와 동일 회전 중심)

            matrices.translate(0f, pivotY, 0f);
            matrices.rotate(Axis.XP.rotation(-tiltAngle));
            matrices.translate(0f, -pivotY, 0f);
            sm.smOuterTiltX = tiltAngle;

            // 🔴 fix #86 v4 (2026-05-12, 사용자 보고 "엎드리기보다 슬라이딩 발쪽 치우침, 머리쪽으로 올려"):
            //   엎드리기 R_x(-78.75°) vs 슬라이딩 R_x(-90°) 회전 각도 차이 11.25° 로 슬라이딩
            //   발 vertex 가 더 정면으로 멀리 → 모델 중심이 발쪽으로 치우침.
            //   사용자 의도: 수평 누운 자세에서 *머리쪽* (= 모델 머리-발 축의 머리 방향) 으로 보정.
            //   매트릭스 분석: setupTransforms TAIL 의 translate Y → vertex 회전 전 적용 →
            //     R_x(-π/2) 후 world Z (= 수평 정면-뒤쪽) 영향. dy 양수 방향 (= 덜 음수) 으로
            //     변경 시 모델이 머리쪽 (정면) 으로 이동.
            //   값: -3/16 (발쪽 치우침) ↔ -1/16 (머리쪽 과보정) 중간 = -2/16.
            matrices.translate(0f, -2f / 16f, 0f);  // Y: 슬라이딩 머리쪽 보정 (회전 각도 차이 ⊃ 발쪽 치우침)

            // 🔴 fix #176 (2026-05-27, 사용자 보고 "슬라이딩 시 모델 전체 살짝 공중에 떠있음"):
            //   matrices.translate 의 Z axis → R_x(-π/2) 후 world Y (= 위-아래) 영향 (메모리
            //   feedback_setupTransforms_translate_axis.md). 사용자 의도 = 모델 살짝 내림.
            //   시도 이력: -1/16 → -2/16 → -3/16 (= 더 내림).
            matrices.translate(0f, 0f, -3f / 16f);

            // 🔴 여우무빙 (= 슬라이딩 ↔ 헤드점프 직접 cycle, standing 안 거침) 헤드점프 prev
            //   자연 시작점 매핑 (2026-05-11, 사용자 보고 "여우무빙 시 몸 거의 회전 안 함,
            //   바로 수평 세팅"):
            //   원본 SmartMovingModel L449 (1.7.10) / L486 (1.12.2) isSlide 분기 식
            //     = `bipedOuter.rotateAngleX = Quarter` (= π/2 = 90° 고정).
            //   원본 ModelRotationRenderer prev 매 frame 자동 갱신 → 슬라이딩 phase 동안
            //     prev = Quarter 유지 → 다음 헤드점프 phase 진입 시 lerp = prev + (target -
            //     prev) * 0.2 ≈ Quarter (target 도 Quarter 부근, 큰 hDist 라 angle 작음) →
            //     모델 90° 수평 유지.
            //   우리 매핑은 비행/헤드점프와 분리된 smHeadJumpTiltX_prev 사용 → 슬라이딩
            //     분기에서 매 frame 직접 갱신 필요. 갱신 안 하면 prev reset 가드로 0 → 헤드점프
            //     phase 매번 0° 시작 → 큰 회전 변화.
            //   일반 슬라이딩 → standing → 헤드점프 시나리오 = standing 분기에서 prev=0 reset
            //     으로 자연 시작 (= 영향 X).
            sm.smHeadJumpTiltX_prev = (float) (Math.PI / 2f);
            sm.smHeadJumpFade_prevTime = animationProgress;
            // 🔴 fix #85 (2026-05-12, 사용자 보고 "슬라이딩 후 절벽 떨어짐 자동 cycle 시 여우무빙 시각"):
            //   슬라이딩 phase 진입 시 wasSelfSlideFire 무조건 false reset. 그 후 자동 cycle 매치 시
            //   wasSelfSlideFire=false 잔존 → 일반 헤드점프 시각.
            //   자체 발사 frame: tickEssential 안 L2271 자동 cycle 매치 *후* isSliding=false →
            //   setupTransforms 호출 시 슬라이딩 분기 매치 X → reset 안 됨 → wasSelfSlideFire=true 유지.
            sm.wasSelfSlideFire = false;
        }

        // 🔴 SM 엎드리기(isCrawling) — Phase 3 자식 효과 매핑 (2026-05-03):
        //   원본 SmartMovingModel L404: bipedTorso.rotateAngleX = Quarter - Thirtytwoth = 78.75°.
        //   원본 SmartMovingModel L405: bipedTorso.rotationPointY = 3F.
        //   원본 1.7.10 SR 모델 = bipedTorso 가 head/body/arm/leg 부모 → 회전/위치가 자식 모두 적용.
        //   1.21.1 평탄 모델 → entity 회전 + translate 로 모든 노드 일괄 처리.
        //   진입 가드: `(isCrawling && !isClimbing)` = 원본 isCrawl 진입 조건 (SmartMovingRender L75).
        // 🔴 부호 반전 (사용자 보고 fix — "배가 하늘 보고 있음", 2026-05-03):
        //   메모리 `feedback_render_scale_negation.md`: vanilla LivingEntityRenderer.render 의
        //   matrices.scale(-1,-1,1) 가 setupTransforms TAIL 다음에 적용 → POSITIVE_X.rotation(+θ)
        //   시각 결과 = R_x(-θ). 즉 +tiltAngle 입력 = 시각상 머리 뒤로 회전 (= 등이 땅, 배가 위).
        //   원본 isCrawling 의도 = 배가 땅 (엎드림) → 시각 R_x(+θ) 필요 → 입력 -θ 적용.
        //   isFlying 분기도 같은 부호 반전 사용 (메모리 BUG-31).
        if (sm.isCrawling && !sm.isClimbing) {
            float tiltAngle = (float)(Math.PI / 2 - Math.PI / 16);  // Quarter - Thirtytwoth = 78.75°
            // 🔴 회전 중심 정정 (사용자 보고 fix 2026-05-03 — "엎드리면 모델 몸통이 좀 더 뒤로 옴"):
            //   원본 SmartMovingModel L405 `bipedTorso.rotationPointY = 3F` → 원본 회전 중심
            //     = bipedTorso pivot = vanilla biped head pivot - 3F/16 (modelpart Y down).
            //     scale(-1,-1,1) 후 world Y +1.407 - 0.176 = entity.y + 1.231 (= 어깨 부근).
            //   이전 매핑 (matrices.translate(0, 1.5, 0)) → 회전 중심 = entity.y + 1.406 (= 머리 부근).
            //     17.5 cm 위쪽. 회전 후 몸통 vertex 가 더 큰 호 그리며 회전 → 몸통이 더 뒤쪽 이동.
            //   fix: pivotY = 1.5 - 3/16 = 1.3125 → 회전 중심 = entity.y + 1.231 = 원본 일치.
            //   하단 추가 translate(0, -3/16, 0) 는 모델 전체 위치 보정 (= 별도 효과) 유지.
            float pivotY = 1.5f - 3f / 16f;  // = 1.3125 = 21/16

            // 🔴 사용자 보고 fix (2026-05-03 — "팔/다리/몸통/머리 디테일 미세 차이"):
            //   원본 SmartMovingModel L406: bipedTorso.rotateAngleZ = cos(distance + Quarter) * Sixtyfourth * walkFactor.
            //   bipedTorso 는 head/body/arm/leg 모두의 부모 → Z 회전이 모든 자식에 영향.
            //   이전 매핑 = body.roll 만 적용 (= bipedBody 자체 회전) → head/arm/leg 미적용 → 차이.
            //   fix: matrices stack 의 R_z 추가 → 모든 자식 일괄 적용 (= 원본 부모 효과 1:1).
            //   원본 YZX rotationOrder + Y=0 → vertex 적용 = R_z first → R_x. matrices stack 적용
            //     순서 = matrices.rotate(R_x) → matrices.rotate(R_z) → vertex 적용 = R_z → R_x. 일치.
            //   sm_animateCrawling 의 body.roll 인자는 0 으로 변경 (= 자식 효과 중복 방지).
            float partialTicks = SmartMovingClientState.globalCachedTickDelta;
            float distance = sm.stats.getTotalHorizontalDistance(partialTicks) * 1.3f;
            // 🔴 (2026-05-03 crawl 디테일 fix): partial ticks lerp getter — sm_animateCrawling 동일.
            float speed = sm.stats.getCurrentHorizontalSpeedFlattened(partialTicks);
            float walkFactor = (speed >= 0.12951545f) ? 1f : (speed <= 0f ? 0f : speed / 0.12951545f);
            float zAngle = (float)(Math.cos(distance + Math.PI / 2) * (Math.PI / 64)) * walkFactor;

            matrices.translate(0f, pivotY, 0f);
            matrices.rotate(Axis.XP.rotation(-tiltAngle));   // 부호 반전 (scale -1,-1,1 보정)
            matrices.rotate(Axis.ZP.rotation(zAngle));        // L406 매핑 — 그대로 (scale commute)
            matrices.translate(0f, -pivotY, 0f);
            sm.smOuterTiltX = tiltAngle;
            // 🔴 bipedTorso.rotationPointY = 3F 매핑 — 부호 반전 (사용자 보고 fix — "여전히 살짝 떠있음",
            //   2026-05-03):
            //   원본 SmartMovingModel L405 의 +3F (= +3 px = +3/16 블록) 자식 효과를 우리 매핑에서
            //   matrices.translate 로 직접 적용 시 vanilla scale(-1,-1,1) 의 Y 부호 반전 영향:
            //   - 원본 model 좌표계 +3/16 (자식 효과) = world 시점 +3/16 위
            //   - 그러나 1.21.1 vanilla scale(-1,-1,1) 가 setupTransforms TAIL 후 적용 →
            //     TAIL inject 의 translate(0, +3/16, 0) 가 시각상 -3/16 효과 (= world 위로 +3/16
            //     **반대로** 시각 결과 - 사용자 보고 시각상 위로 0.1m 떠있음).
            //   해결: 메모리 `feedback_render_scale_negation.md` 패턴 = -3/16 입력 → 시각 +3/16
            //     원본 자식 효과와 일치 (= 모델 -3/16 아래로 보정 = 사용자 보고 fix).
            matrices.translate(0f, -3f / 16f, 0f);
        }

        // 🔴 (2026-05-04) crawl-climbing 옵션 D 정확 매핑 (사용자 명시 "옵션 D 거부 해제"):
        //   원본 SmartMovingModel L265 `bipedTorso.rotateAngleX = bodyAngleX` (= 부모 회전).
        //   bipedTorso = head/body/arm/shoulder/pelvic/leg 모두의 부모 → 모든 자식이 R_x(B) 누적.
        //   ModelPart 단일 노드로는 R_z(Z) 가 두 R_x 사이에 있는 구조 표현 X.
        //   해결: setupTransforms 의 root R_x(-bodyAngleX) (= 부호 반전 by scale) → 부모 효과 정확.
        //   setAngles 의 leg.pitch/leg.roll/body/head/arm 매핑 변경 (= 부모 + cancel = 원본 1:1).
        //   회전 중심 = bipedTorso pivot = modelpart (0, 0, 0) ≈ matrices stack (0, 1.5, 0).
        else if (sm.isCrawlClimbing) {
            float height = sm.smallOverGroundHeight + 0.25f;
            float bodyLength = 0.7f;
            float bodyAngleX_target;
            if (height < bodyLength) {
                bodyAngleX_target = Math.max(0f, (float) Math.acos(height / bodyLength));
            } else {
                bodyAngleX_target = 0f;
            }
            // 🔴 fade 보간 (2026-05-04 — 사용자 명시 "이 로직에 맞는 페이드"):
            //   첫 호출 = 즉시 target (= 진입 시 jump 허용 — 원본과 동일).
            //   그 후 매 frame height 변화 → target 변화 → lerp 부드러움 (= jitter 차단).
            //   원본 ModelRotationRenderer.GetIntermediateAngle 식 0.2 * deltaT 1:1.
            float bodyAngleX = sm.applyCrawlClimbFade(bodyAngleX_target, animationProgress);

            // 부모 R_x 매핑 (원본 L265 bipedTorso.X = bodyAngleX 1:1).
            // 부호 반전 (메모리 feedback_render_scale_negation.md): input -bodyAngleX → 시각 +bodyAngleX.
            matrices.translate(0f, 1.5f, 0f);
            matrices.rotate(Axis.XP.rotation(-bodyAngleX));
            matrices.translate(0f, -1.5f, 0f);
        }
        // 🔴 fade reset (2026-05-04): isCrawlClimbing 종료 시 모든 fade prev field reset →
        //   다음 진입 시 첫 호출 = CRAWL_TILT_ANGLE 시작 (= 부드러운 전환).
        if (!sm.isCrawlClimbing) {
            sm.smCrawlClimbBodyAngleXFaded = Float.NaN;
            sm.smCrawlClimbFadeTimePrev = Float.NaN;
            sm.smCrawlClimbLegAngleXFaded = Float.NaN;
            sm.smCrawlClimbLegAngleXFadeTimePrev = Float.NaN;
            sm.smCrawlClimbLegAngleZFaded = Float.NaN;
            sm.smCrawlClimbLegAngleZFadeTimePrev = Float.NaN;
        }

        // isFlying body X 기울기: θ = (Quarter - verticalAngle) * walkFactor (C-42, A-30 SmartStatistics)
        // walkFactor = Factor(currentSpeed, 0F, 1) — 속도 0~1 범위 정규화 (SmartMovingModel.md 9번 분기)
        // 🔴 BUG-31 (Flying Phase F-6 / 세션 40): X 회전 부호 반전.
        //   원인: vanilla LivingEntityRenderer.setupTransforms L44-L59 = POSITIVE_Y.rotation(180-bodyYaw)
        //   기본 적용 (entity Y 180° 뒤집음). SM 의 sm_setupTransforms TAIL inject 로 추가 X 회전
        //   적용 시 좌표계 뒤집힘 영향 받음 — Y 180° 후 POSITIVE_X 회전 = 모델 좌표계의
        //   NEGATIVE_X 회전 등가 → 회전 방향 반대 (사용자 보고 "몸 앞쪽이 하늘").
        //   해결: -theta 적용 (POSITIVE_X 부호 반전) → 슈퍼맨 자세 (배 아래, 등 위).
        //   smOuterTiltX 는 그대로 (cape 클램프 — B-17 — 별도 의미 보존).
        if (sm.isFlying) {
            // 🔴 (세션 48): partial tick lerp 적용 — 60Hz 부드러움 (원본 매 프레임 보간 1:1).
            float walkFactor = sm.stats.getCurrentSpeed(tickDelta);
            // 🔴 1:1 정정 (세션 47b): 원본 SmartMovingModel L481 `verticalAngle =
            //   isJump ? Math.abs(currentVerticalAngle) : currentVerticalAngle`. 이전 매핑 누락.
            float verticalAngle = sm.isJumping
                    ? Math.abs(sm.stats.currentVerticalAngle)
                    : sm.stats.currentVerticalAngle;
            float thetaTarget = ((float) Math.PI / 2f - verticalAngle) * walkFactor;
            float yawTarget = sm.smFlyingExtraYaw;

            // 🔴 fade 보간 적용 (Flying Phase / 세션 49): 원본 ModelRotationRenderer.fadeIntermediate
            //   매 프레임 호출 1:1 매핑.
            //   원본 GetIntermediateAngle (L347-L364):
            //     return prev + (target - prev) * (currentTime - prevTime) * 0.2F
            //   가드 (L317): currentTime - prevTime <= 2F 시만 보간 (그 이상은 즉시).
            //   원본 비행 분기 (L484): bipedOuter.fadeRotateAngleX = true → X 회전 보간.
            //   원본 rotatePlayer (L209): bipedOuter.fadeRotateAngleY = true (default) → Y 회전 보간.
            //   사용자 보고 "동작 사이 중간 처리 부재" / "프레임 드랍 느낌" 직접 원인 = fade 부재.
            //   정정: prev 저장 + 매 프레임 lerp factor 0.2 * deltaTime 적용.
            //     큰 각도 차이 (π 등) 의 wrapping 처리 (원본 L352-L362) 도 적용.
            float thetaLerped = lerpFadeAngle(sm.smOuterTiltX_prev, thetaTarget,
                                              sm.smOuterFade_prevTime, animationProgress);
            float yawLerped = lerpFadeAngle(sm.smOuterExtraYaw_prev, yawTarget,
                                            sm.smOuterFade_prevTime, animationProgress);

            matrices.translate(0f, 1.5f, 0f);
            // 🔴 BUG-27/32 좌우 바뀜 정정 (Flying Phase / 세션 47b): Y 회전 부호 반전.
            //   vanilla LivingEntityRenderer.render() L342: setupTransforms 후 scale(-1,-1,1) 적용
            //   → Y axis 반전 → POSITIVE_Y rotation mirror → 부호 반전 보정.
            if (yawLerped != 0f) {
                matrices.rotate(Axis.YP.rotation(-yawLerped));
            }
            matrices.rotate(Axis.XP.rotation(-thetaLerped));
            matrices.translate(0f, -1.5f, 0f);

            // fade prev 갱신 (다음 프레임 보간용)
            sm.smOuterTiltX_prev = thetaLerped;
            sm.smOuterExtraYaw_prev = yawLerped;
            sm.smOuterFade_prevTime = animationProgress;

            sm.smOuterTiltX = thetaLerped;  // cape 클램프 (B-17) 도 보간된 값 사용

            // 🔴 (2026-04-27) 비행 → 비행 외 전환 시 standard fade prev 자연 시작점 매핑.
            //   비행 모델 회전 = POSITIVE_Y(180-0) + POSITIVE_Y(-yawLerped) = POSITIVE_Y(180-yawLerped_deg).
            //   = setupTransforms 등가 bodyYaw 인자 = yawLerped_deg.
            //   sm_modifyBodyYaw force 분기는 smBodyYawOverride=0 으로 갱신 → 잘못된 값.
            //   여기서 yawLerped_deg 로 덮어쓰기 → 비행 → standard 전환 시 lerp 자연 시작.
            //   사용자 보고 "비행 릴리즈 시 몸통 한번 돌아감" 해소.
            float yawLerpedDeg = (float) Math.toDegrees(yawLerped);
            sm.smCachedBodyYawLaggedDeg = yawLerpedDeg;
            sm.smStandardBodyYawPrev = yawLerpedDeg;
            sm.smStandardFadeTimePrev = animationProgress;
        }

        // isHeadJumping body X 기울기: θ = Quarter - currentVerticalAngle (원본 SmartMovingModel L508).
        // 비행 분기와 동일 패턴: head pivot 기준 회전 (translate ±1.5) + scale(-1,-1,1) 부호 반전 (-theta).
        //   feedback_rotation_pivot_pattern.md / feedback_render_scale_negation.md.
        // 🔴 fade lerp (2026-05-11, 사용자 보고 "하강 시 몸 너무 수직, 원본 더 수평적"):
        //   원본 SmartMovingModel L507 `bipedOuter.fadeRotateAngleX = true` (= 5-tick lerp) 1:1.
        //   ModelRotationRenderer.GetIntermediateAngle = prev + (target - prev) * deltaT * 0.2F.
        //   누락 시 빠른 각도 변화 (10°/tick) → 모델 거의 거꾸로 (164°). fade 적용 시 130°.
        //   비행 prev 인프라와 분리 (= smHeadJumpTiltX_prev) — 비행 ↔ 헤드점프 전환 시 leak 차단.
        // 🔵 (2026-05-14, fix #98 위치 기반) smRemoteHJVisualHold OR 추가 — REMOTE 공중 자세 hold.
        if (sm.isHeadJumping || sm.smRemoteHJVisualHold) {
            // 🔴 fix #81 (2026-05-12, 사용자 보고 "여우무빙 진입 시 몸 수평 안 됨"):
            //   자체 슬라이딩 발사 → 같은 tick L2271 자동 cycle 매치 → isHeadJumping=true 진입 시
            //   thetaTarget = Quarter 고정 강제 → 원본 isSlide 분기 (= rotateAngleX = Quarter)
            //   시각 1:1. 일반 헤드점프 (wasSelfSlideFire=false) 는 기존 식 그대로.
            // 🔴 fix #81 v2 (2026-05-12): prev 도 π/2 강제 set — fade lerp 즉시 도달.
            //   prev=0 시작 시 점진 lerp 12 tick 후도 도달 못 함 (= 사용자 시각 절반 회전 잔존).
            //   prev=π/2 강제 set 시 lerp = π/2 즉시 (= 발사 frame 부터 완전 수평).
            float thetaTarget = sm.wasSelfSlideFire
                    ? (float) Math.PI / 2f
                    : (float) Math.PI / 2f - sm.stats.currentVerticalAngle;
            if (sm.wasSelfSlideFire) {
                sm.smHeadJumpTiltX_prev = (float) Math.PI / 2f;
                sm.smHeadJumpFade_prevTime = animationProgress;
            }
            float thetaLerped = lerpFadeAngle(sm.smHeadJumpTiltX_prev, thetaTarget,
                                               sm.smHeadJumpFade_prevTime, animationProgress);
            matrices.translate(0f, 1.5f, 0f);
            matrices.rotate(Axis.XP.rotation(-thetaLerped));
            matrices.translate(0f, -1.5f, 0f);
            sm.smHeadJumpTiltX_prev = thetaLerped;
            sm.smHeadJumpFade_prevTime = animationProgress;
            sm.smOuterTiltX = thetaLerped;   // cape 클램프 (B-17) 도 보간된 값 사용
        }

        // Dodge (angle jump, in the air and through the landing recovery): whole-body lean along the dodge,
        //   about the hips, plus the landing crouch's model drop.
        //   Axis conventions in this pre-scale space (see swim/slide/fly branches above):
        //   XP.rotation(-θ) pitches the model forward, ZP.rotation(+φ) rolls it toward the player's left.
        //   SmartMovingClientState.sampleDodgePose supplies the same eased values the model's limbs use.
        if (sm.canShowDodgePose() && sm.sampleDodgePose(animationProgress, smartState.sm_getBodyYaw())) {
            // Vanilla sneaking drops the model by 2/16 (AvatarRenderer.getRenderOffset); match the partial
            // crouch, unless the player is really sneaking and vanilla already did.
            if (sm.dodgeCrouch > 0f && !renderState.isCrouching) {
                matrices.translate(0f, -2f / 16f * sm.dodgeCrouch, 0f);
            }
            float pivotY = 1.5f - 12f / 16f;   // hips (model leg pivot y=12)
            matrices.translate(0f, pivotY, 0f);
            if (sm.dodgeRoll  != 0f) matrices.rotate(Axis.ZP.rotation(sm.dodgeRoll));
            if (sm.dodgePitch != 0f) matrices.rotate(Axis.XP.rotation(-sm.dodgePitch));
            matrices.translate(0f, -pivotY, 0f);
            sm.smOuterTiltX = sm.dodgePitch;
        }

        // Landing roll (docs/research_movement_prototypes.md): one full forward tumble about the hips
        // over the roll, dropped toward the ground in the middle so the ball stays near the feet.
        if (sm.isRolling && sm.rollStartTick != Integer.MIN_VALUE) {
            float p = sm.rollProgress(animationProgress);
            float k = (float) Math.sin(p * Math.PI);
            float pivotY = 1.5f - 12f / 16f;   // hips, same pivot as the dodge lean
            matrices.translate(0f, -0.35f * k, 0f);
            matrices.translate(0f, pivotY, 0f);
            matrices.rotate(Axis.XP.rotation(-(float) (2.0 * Math.PI * p)));
            matrices.translate(0f, -pivotY, 0f);
        }

        // Sprint vault (docs/research_movement_prototypes.md): lean forward over the planted hand.
        if (sm.isVaulting && sm.vaultStartTick != Integer.MIN_VALUE) {
            float t = animationProgress - sm.vaultStartTick;
            float k = Math.min(1f, Math.max(0f, t / 3f));
            float pivotY = 1.5f - 12f / 16f;
            matrices.translate(0f, pivotY, 0f);
            matrices.rotate(Axis.XP.rotation(-0.55f * k));
            matrices.rotate(Axis.ZP.rotation(0.20f * k));
            matrices.translate(0f, -pivotY, 0f);
            sm.smOuterTiltX = 0.55f * k;
        }

        // 🔴 D-4 매트릭스 변환 시도 → 회전 중심 차이로 자세 잘못 (사용자 보고 5회차).
        //   원본 bipedTorso 회전 중심 = bipedTorso pivot (0,0,-6) = 허리/머리 위치 (작은 visual 효과).
        //   1.21.1 matrices 회전 중심 = vanilla setupTransforms 후 위치 = entity 발 부근 (큰 효과).
        //   같은 0.5rad 이라도 visual 차이 → 매트릭스 방식 폐기.
        //   이전 직접 매핑 (body.pitch, pivotZ, arm 누적) 으로 복원. 분리는 그대로 (수용 가능 수준).
        //   sm_animateClimbing 의 D-4 분기 본체 참조.

        // 🔴 (2026-04-27) falling matrix 분기 비활성화 — sm_modifyBodyYaw 의 fade lerp 로 통합.
        //   이전: force=0 + 추가 R_y(-yawLerped) → vanilla setupTransforms 무력화 → 원본 동작 차이.
        //   새 매핑: smBodyYawActive=false → sm_modifyBodyYaw 가 vanilla bodyYaw 에 fade lerp 만
        //   적용 → vanilla 즉시 + 추가 fade (원본 1:1).
        //   추가 R_y 불필요. smCachedYawLerpedRad / smStandardFadeYaw_prev 도 사용 안 함.

        // 🔴 (2026-04-27) 비행 외 분기에서 비행 fade prev 매 frame 갱신.
        //   사용자 보고: 비행 진입 시 부드럽게 안 됨, 중간 끊김.
        //   prev = ModifyArg 의 실제 적용 결과 (smCachedBodyYawLaggedDeg) 라디안.
        //   비행 외 모델 회전 = POSITIVE_Y(180 - applied_deg) = π - applied_rad.
        //   비행 첫 frame 모델 회전 = POSITIVE_Y(180) + POSITIVE_Y(-yawLerped) = π - yawLerped_rad.
        //   yawLerped 시작 = applied_rad → 같음 → 끊김 없음.
        //   원본 SmartRender bipedOuter.previous 가 모든 분기 공통 단일 변수인 것을 매핑.
        if (!sm.isFlying) {
            sm.smOuterTiltX_prev = 0f;
            sm.smOuterExtraYaw_prev = (float) Math.toRadians(sm.smCachedBodyYawLaggedDeg);
            sm.smOuterFade_prevTime = animationProgress;
        }

        // 🔴 Head jump fade prev updated every frame (= flying prev update pattern identical).
        //   Outside head jump/sliding branches prev=0 reset → next head jump entry natural start point
        //   (= standing).
        //   Sliding branch has prev=Quarter directly set above (smooth natural cycle).
        // 🔴 fix #81 v3 (2026-05-12, user report "sliding→flying transition animation distortion"):
        //   Existing wasSelfSlideFire reset = SmartMovingClientState L2222 head jump end branch.
        //   Flying transition onGround=false no match → flag persists → next head jump fire
        //   thetaTarget=π/2 incorrectly forced or flying phase visual effect.
        //   Solution: Enter outside head jump/sliding branches (= flying/standing/falling etc) flag reset.
        // 🔵 (2026-05-14, fix #98 position-based) smRemoteHJVisualHold !hold AND added — block reset during hold.
        if (!sm.isHeadJumping && !sm.isSliding && !sm.smRemoteHJVisualHold) {
            sm.smHeadJumpTiltX_prev = 0f;
            sm.smHeadJumpFade_prevTime = animationProgress;
            sm.wasSelfSlideFire = false;
        }

        // 🔴 Ceiling climb fade prev updated every frame (flying prev update pattern identical).
        //   Outside ceiling climb branches prev = previous vanilla or SM bodyYaw (radian).
        //   Entry first frame fade natural start (previous value → target lerp).
        if (!sm.isCeilingClimbing) {
            sm.smCeilingYaw_prev = (float) Math.toRadians(sm.smCachedBodyYawLaggedDeg);
            sm.smCeilingFade_prevTime = animationProgress;
        }

        // 🔴 fix #91 (2026-05-13): Head jump fade prev updated every frame (= flying prev update pattern identical).
        //   Outside head jump/rope sliding branches prev = previous vanilla or SM bodyYaw (radian).
        //   Entry first frame fade natural start (= previous pose → currentHorizontalAngle target lerp).
        //   See feedback_fade_prev_pre_entry_pose.md.
        // 🔴 fix #95 (2026-05-13): smHeadJumpYawSnapDone reset — reset on entering other branches.
        //   Next head jump entry (= wasSelfSlideFire matrix check) first frame snap active.
        // 🔵 (2026-05-14, fix #98 position-based) smRemoteHJVisualHold !hold AND added — block reset during hold.
        if (!sm.isHeadJumping && !sm.isRopeSliding && !sm.smRemoteHJVisualHold) {
            sm.smHeadJumpYaw_prev = (float) Math.toRadians(sm.smCachedBodyYawLaggedDeg);
            sm.smHeadJumpYawFade_prevTime = animationProgress;
            sm.smHeadJumpYawSnapDone = false;
        }

    }

    /**
     * renderName() correspondence — other player name tag Y correction + hide name during crawl.
     *
     * Original: SmartMovingRender.renderName()
     *   - isCrawling && !isClimbing && !crawlNameTag → hide name
     *   - heightOffset == -1 → d1 -= 0.2F
     *   - originalSneaking && sneakNameTag → d1 -= 0.05F (y correction when treating as non-sneaking)
     *
     * sneakNameTag (64 distance expansion during sneaking) handled in MixinLivingEntityRenderer.
     */
    @Inject(method = "submitNameDisplay(Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V",
            at = @At("HEAD"), cancellable = true)
    private void sm_renderLabel(AvatarRenderState renderState,
            PoseStack matrices, SubmitNodeCollector nodeCollector, CameraRenderState cameraState,
            CallbackInfo ci) {
        if (!((Object) renderState instanceof SmartMovingAvatarRenderState smartState)) return;
        AbstractClientPlayer entity = smartState.sm_getPlayer();
        if (entity == null) return;
        if (entity instanceof LocalPlayer) return;
        SmartMovingClientState sm = SmartMovingClientState.get(entity.getUUID());
        SmartMovingConfig cfg = SmartMovingConfig.Config;
        // 🔴 (2026-05-05) self → Config.enabled / remote → always true.
        if (!choco.ratel.smartmoving.client.SmartMovingClient.isSmRenderEnabled(entity)) return;

        // Hide name during crawl (crawlNameTag=false)
        if (sm.isCrawling && !sm.isClimbing && !cfg.crawlNameTag) {
            ci.cancel();
            return;
        }

        // heightOffset == -1: head jump state name tag y -0.2F correction
        if (sm.heightOffset == -1f) {
            matrices.translate(0.0, -0.2, 0.0);
        }
        // During sneaking sneakNameTag=true: treat as non-sneaking display name → y -0.05F correction
        // (original: name tag position difference correction during sneaking→non-sneaking pose transition)
        else if (entity.isShiftKeyDown() && cfg.sneakNameTag) {
            matrices.translate(0.0, -0.05, 0.0);
        }
    }

    /**
     * 🔴 Fade interpolation helper (Flying Phase / session 49): Original ModelRotationRenderer.GetIntermediateAngle
     *   1:1 mapping (L347-L364).
     *
     * Original:
     * <pre>
     *   private static float GetIntermediateAngle(float prev, float should, boolean fade,
     *                                              float lastTotalTime, float totalTime) {
     *       if(!fade || should == prev) return should;
     *       while(prev >= Whole) prev -= Whole;
     *       while(prev < 0F) prev += Whole;
     *       while(should >= Whole) should -= Whole;
     *       while(should < 0F) should += Whole;
     *       if(should > prev && (should - prev) > Half) prev += Whole;
     *       if(should < prev && (prev - should) > Half) should += Whole;
     *       return prev + (should - prev) * (totalTime - lastTotalTime) * 0.2F;
     *   }
     * </pre>
     *
     * Call site guard (fadeIntermediate L317): interpolate only when `totalTime - prevTotalTime <= 2F`.
     * Whole = 2π (radian). Half = π. Wrapping handling for large angle difference (opposite side).
     *
     * Our mapping: fade always active (flying fadeRotateAngleX/Y = true). If prevTime uninitialized (-999)
     *   apply immediately (interpolation skip).
     */
    @Unique
    /**
     * 🔵 (fix #115) vanilla LivingEntity.jumping (= raw space bar) check helper.
     *   LivingEntity.jumping = protected — cannot access directly. For ClientPlayerEntity
     *   equivalent to input.jumping (= inside ClientPlayerEntity.tickMovement `jumping = input.jumping`).
     *   For remote player isJumping field not synced → return false (= future packet sync update).
     */
    private static boolean sm_isPlayerJumping(net.minecraft.client.player.AbstractClientPlayer player) {
        if (player instanceof net.minecraft.client.player.LocalPlayer localPlayer) {
            return localPlayer.input.keyPresses.jump();
        }
        return false;
    }

    private static float lerpFadeAngle(float prev, float target, float prevTime, float currentTime) {
        // prevTime uninitialized or 2+ ticks difference → apply immediately (interpolation skip)
        if (prevTime < -100f) return target;
        float deltaTime = currentTime - prevTime;
        if (deltaTime > 2f || deltaTime < 0f) return target;
        if (target == prev) return target;
        // Large angle difference (opposite side) wrapping — original L352-L362
        final float WHOLE = (float) (2.0 * Math.PI);
        final float HALF = (float) Math.PI;
        float p = prev, s = target;
        while (p >= WHOLE) p -= WHOLE;
        while (p < 0f) p += WHOLE;
        while (s >= WHOLE) s -= WHOLE;
        while (s < 0f) s += WHOLE;
        if (s > p && (s - p) > HALF) p += WHOLE;
        if (s < p && (p - s) > HALF) s += WHOLE;
        return p + (s - p) * deltaTime * 0.2f;
    }

    // ── First person arm render context marking ────────────────────────────────────────────
    //
    // PlayerEntityRenderer.renderArm is called when HeldItemRenderer renders first person arm:
    //   model.setAngles(player, 0,0,0,0,0)
    //   arm.pitch = 0F; arm.render(...);
    //   sleeve.pitch = 0F; sleeve.render(...);
    // flow. If SM's sm_setAnglesHead/sm_setAngles trigger on this setAngles call,
    // first person arm disappears or becomes abnormal covered by SM branch pose.
    //
    // At HEAD set firstPersonArmRender=true → sm_setAngles returns immediately → arm vanilla
    // default pose. At RETURN restore false → same frame third person body render applies SM normally.
    //
    // private method but injectable via Mixin. Both renderRightArm/renderLeftArm delegate to
    // private renderArm so hooking one place covers both.
    @Inject(method = "renderHand(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;ILnet/minecraft/resources/Identifier;Lnet/minecraft/client/model/geom/ModelPart;Z)V",
            at = @At("HEAD"))
    private void sm_renderArmHead(PoseStack matrices, SubmitNodeCollector nodeCollector, int light,
                                  Identifier texture, ModelPart arm, boolean showSleeve,
                                  CallbackInfo ci) {
        SmartMovingRenderContext.firstPersonArmRender = true;
        ((choco.ratel.smartmoving.client.SmModelPartOverride)(Object) arm).sm_clearOverrideQuat();
    }

    @Inject(method = "renderHand(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;ILnet/minecraft/resources/Identifier;Lnet/minecraft/client/model/geom/ModelPart;Z)V",
            at = @At("RETURN"))
    private void sm_renderArmReturn(PoseStack matrices, SubmitNodeCollector nodeCollector, int light,
                                    Identifier texture, ModelPart arm, boolean showSleeve,
                                    CallbackInfo ci) {
        SmartMovingRenderContext.firstPersonArmRender = false;
    }

    @Inject(
            method = "setupRotations(Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;FF)V",
            at = @At("RETURN")
    )
    private void sm_clearCurrentRenderState(AvatarRenderState state, PoseStack poseStack,
                                            float bodyYaw, float scale, CallbackInfo ci) {
        sm_currentRenderState = null;
    }
}
