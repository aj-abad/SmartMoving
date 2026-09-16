package choco.ratel.smartmoving.mixin;

import choco.ratel.smartmoving.config.SmartMovingConfig;
import choco.ratel.smartmoving.server.SmartMovingServer;
import net.minecraft.server.level.ServerPlayer;
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
 * 6-1 (server): getBaseDimensions() returns custom EntityDimensions.
 * 6-4 (server): updatePose() Mixin — Forces pose setting by SM state.
 *
 * LivingEntity.getDimensions() is final → getBaseDimensions() is the only override entry point.
 * Yarn: getBaseDimensions (intermediary: method_55694 verified)
 * Yarn: updatePose (intermediary: method_7318 verified)
 *
 * Client side → MixinPlayerEntityClient.sm_getBaseDimensions_client(), sm_updatePose_client()
 */
@Mixin(Player.class)
public abstract class MixinPlayerEntity {

    /**
     * 6-1 (server): Returns custom EntityDimensions by SM pose.
     *
     * **Focus #2.7 Phase C rewrite (session 4, 2026-04-25)**: Completely symmetric with client (option 3).
     *   Original `setHeightOffset(-1F)` (SmartMovingSelf L1694-L1704) → `sp.height = 1.8F + (-1F)
     *   = 0.8F` + `getEyeHeight() = height - 0.18F = 0.62F` (ServerPlayerBase L142-L145).
     *   → All small states (isCrawling/isCrawlClimbing/isHeadJumping/isSliding/isSwimming/
     *   isDiving/isFlying/isLevitating) unified 0.6×0.8 + 0.62F.
     *
     * Same 8 SM OR branches as client (MixinPlayerEntityClient.sm_getBaseDimensions_client).
     * isFlying is vanilla `abilities.flying + cfg.fly + !isSwimming + !isDiving` original formula
     * (SmartMovingSelf L2509-L2515) 1:1.
     */
    @Inject(method = "getDefaultDimensions", at = @At("HEAD"), cancellable = true, require = 0)
    private void sm_getBaseDimensions_server(Pose pose, CallbackInfoReturnable<EntityDimensions> cir) {
        if (!((Object) this instanceof ServerPlayer player)) return;
        SmartMovingServer sm = SmartMovingServer.get(player);

        // 🔴 BUG-29 real cause fix (Flying Phase / session 45): Remove flying/Levitate branch (server side).
        //   Same fix as client side sm_getBaseDimensions_client — pass vanilla STANDING during flying.
        //   Original 1.7.10 vanilla has no EntityPose system → flying uses STANDING model + eyeHeight
        //   1.62 as-is. 1.21.1 incorrect mapping = force SLIDING → camera at feet.
        //   Keep client/server symmetry: both pass vanilla STANDING POSE during flying → datatracker consistent.
        //
        // Original setHeightOffset(-1F) state exhaustive — only Crawling/Sliding/Swimming etc. use 0.6 × 0.8 +
        // eyeHeight 0.62F. isFlying/isLevitating pass vanilla.
        // 🔴 Add isClimbCrawling branch (user report fix — block X/Z pulling server reconcile):
        //   During ICC active, client dim eyeHeight=1.62 (mixin offset box +1 applied) ↔ server vanilla
        //   dim 1.8 (mixin offset not applied) → box 1m Y difference → server box ladder collision
        //   not detected → block → server position correction packet → client position reset.
        //   Add isClimbCrawling branch same as client sm_getBaseDimensions_client → server box
        //   same as client (entity.y+1, entity.y+1.8) → no server reconcile.
        // 🔴 Revert attempt to add isCrawlClimbing ICC branch (2026-05-03):
        //   Same as client sm_getBaseDimensions_client — during isCrawlClimbing, box +1m mixin offset
        //   triggers → exceeds ladder grip area → unintended ICC trigger. Original 1:1 = apply smSmall dim
        //   (0.8, 0.62) (no original setHeightOffset call → previous isCrawling box remains).
        if (sm.isClimbCrawling) {
            cir.setReturnValue(EntityDimensions.scalable(0.6F, 0.8F).withEyeHeight(1.62F));
            return;
        }
        // 🔴 Separate isHeadJumping branch (2026-05-08, settled after multi-step fix) — 1:1 symmetric with client:
        //   Phase 1 dim (0.6, 0.8, 1.62F) → MixinEntity offset guard match → box +1m → infinite loop BUG.
        //   Phase 2 dim (0.6, **1.8**, 1.62F) → height STANDING → offset guard mismatch → box STANDING.
        //   Same mapping as client (MixinPlayerEntityClient) — block server reconcile.
        if (sm.isHeadJumping) {
            // 🔴 fix #13 (2026-05-08): Same mapping as client side — dim 0.6×0.8 + eyeHeight 1.62F.
            //   MixinEntity.sm_offsetBoundingBoxForFlying's POSE=SLIDING guard blocks mixin offset.
            cir.setReturnValue(EntityDimensions.scalable(0.6F, 0.8F).withEyeHeight(1.62F));
            return;
        }
        // 🔴 fix #59 (2026-05-10, dump analysis result — user report "during fox movement EYEHEIGHT set
        //   below collision"):
        //   During isSliding, fix #56's POSE.SLIDING guard activates mixin offset → box +1m up
        //   (= bb.minY = entity.y + 1m). However, dim eyeHeight=0.62 remains → camera = entity.y +
        //   0.62 → 0.38m below box (entity.y+1, entity.y+1.8).
        //   1.7.10/1.12.2's setHeightOffset(-1) effect = boundingBox +1m up + height 0.8 +
        //   eyeHeight unchanged (= STANDING 1.62 remains, position unchanged mapping).
        //   Solution: During isSliding, set eyeHeight=1.62 (= STANDING) → camera = entity.y + 1.62 →
        //   inside box (= entity.y+1.62 within entity.y+1 ~ entity.y+1.8) normal.
        if (sm.isSliding) {
            cir.setReturnValue(EntityDimensions.scalable(0.6F, 0.8F).withEyeHeight(1.62F));
            return;
        }
        // 🔵 (2026-05-20, Phase 0 validation fix #102) separate swim/dive branch — 1:1 symmetric with client mapping.
        //   Original 1.7.10 `setHeightOffset(-1F)` effect = box +1m up + keep eyeHeight 1.62 STANDING.
        //   Must sync 1:1 with swim/dive branch changes in client side `sm_getBaseDimensions_client`
        //   (= server reconcile + multi remote bb consistency).
        if (sm.isSwimming || sm.isDiving) {
            cir.setReturnValue(EntityDimensions.scalable(0.6F, 0.8F).withEyeHeight(1.62F));
            return;
        }
        // 🔴 v26.12 — Discard v26.5 guard (sync both client/server).
        //   STANDING dim fall through BUG root. Restore 1:1 intent of Fix 7 (= add isCrawlClimbing to smSmall).
        //   User report "block STANDING → lying down transition".
        boolean smSmall = sm.isCrawling || sm.isCrawlClimbing;
        if (smSmall) {
            cir.setReturnValue(EntityDimensions.scalable(0.6F, 0.8F).withEyeHeight(0.62F));
            return;
        }

        // 🔴 비행 콜리전 원본 1:1 복원 시도 (사용자 요청, 단계 1):
        //   원본 setHeightOffset(-1F) (L1694-L1704) → height 0.8, eyeHeight 변경 없음 (1.62).
        //   1.21.1 매핑: dimensions = (0.6, 0.8, eyeHeight=1.62).
        //   서버 측 isFlying 공식 (원본 L2510): cfg.fly && abilities.flying && !swim && !dive.
        //   클라/서버 hitbox 일관 위해 양측 동일 적용. instanceof ServerPlayerEntity 가드는
        //   메서드 진입부에 이미 있음 — player 변수 사용 가능.
        boolean serverIsFlying = SmartMovingConfig.Config.fly
                              && player.getAbilities().flying
                              && !sm.isSwimming
                              && !sm.isDiving;
        if (serverIsFlying || sm.isLevitating) {
            cir.setReturnValue(EntityDimensions.scalable(0.6F, 0.8F).withEyeHeight(1.62F));
            return;
        }

        // SLIDING POSE 가 vanilla 가 아닌 경로로 들어온 경우 보강 (외부 모드 등).
        if (pose == Pose.SLIDING) {
            cir.setReturnValue(EntityDimensions.scalable(0.6F, 0.8F).withEyeHeight(0.62F));
            return;
        }

        // 🔴 orphan SWIMMING pose 가드 (2026-05-04 사용자 보고 — "1칸 진입 직전 슬라이딩 풀면
        //   가끔 콜리전 정상 엎드리기보다 작음"):
        //   클라 sm_getBaseDimensions_client 와 동일 가드 — pose=SWIMMING + smSmall 미매치 시
        //   STANDING dim 강제. 클라/서버 대칭으로 박스 sync 일관 유지 (server reconcile 차단).
        if (pose == Pose.SWIMMING) {
            cir.setReturnValue(EntityDimensions.scalable(0.6F, 1.8F).withEyeHeight(1.62F));
        }
    }

    /**
     * 6-4 (서버): SM 이동 상태에서 vanilla updatePose() 취소 후 SM 포즈 강제 설정.
     *
     * **포커스 #2.7 Phase D-1 (세션 4, 2026-04-25)**: 클라와 완전 대칭 (옵션 3).
     *   클라 (MixinPlayerEntityClient.sm_updatePose_client) 와 동일 4 분기 매핑:
     *     isCrawling || isCrawlClimbing            → SWIMMING (엎드림)
     *     isHeadJumping || isSliding               → SLIDING  (납작)
     *     isSwimming || isDiving                   → SWIMMING (수영)
     *     isFlying (원본 공식) || isLevitating     → SLIDING  (비행)
     *     이외                                     → vanilla 통과
     *
     * 클라/서버 POSE 동일 sync → datatracker 진동 0.
     */
    @Inject(method = "updatePlayerPose", at = @At("HEAD"), cancellable = true)
    private void sm_updatePose_server(CallbackInfo ci) {
        if (!((Object) this instanceof ServerPlayer player)) return;
        SmartMovingServer sm = SmartMovingServer.get(player);

        if (sm.isCrawling || sm.isCrawlClimbing) {
            player.setPose(Pose.SWIMMING);
            ci.cancel();
        } else if (sm.isHeadJumping || sm.isSliding) {
            player.setPose(Pose.SLIDING);
            ci.cancel();
        } else if (sm.isSwimming || sm.isDiving) {
            player.setPose(Pose.SWIMMING);
            ci.cancel();
        }
        // 🔴 BUG-29 진짜 원인 정정 (Flying Phase / 세션 45): 비행/Levitate 분기 제거 (서버측).
        //   클라측 sm_updatePose_client 와 동일 정정 — 비행 시 vanilla STANDING POSE 통과.
        //   원본 1.7.10 vanilla 에 POSE 시스템 없음 → 비행 시 STANDING 그대로. 1.21.1 잘못 매핑
        //   = SLIDING 강제 → 카메라 발끝. 정정: vanilla 통과 → STANDING POSE → eyeHeight 1.62.
        //   클라/서버 대칭 유지 → datatracker 진동 0.
        // isFlying/isLevitating/isDipping/그 외 → vanilla updatePose() 통과 (STANDING 등)
    }

    /**
     * 낙하 중 sneak pose 차단 — vanilla updatePose() 의 isSneaking() 호출만 redirect.
     *
     * 원본 1.7.10 1:1 근거 (SmartMovingModel.animateSneaking L681-685):
     *   `if(isStandard && !isAngleJumping) imp.superAnimateSneaking(...);`
     *   isFalling 분기 진입 시 isStandard=false → vanilla sneak 시각 적용 SKIP.
     *
     * 1.21.1 EntityPose 시스템 차이:
     *   1.7.10 vanilla 는 sneak 시 boundingBox 변경 없음 + eyeHeight 0.08 차감만 → 떨림 미미.
     *   1.21.1 vanilla 는 pose=CROUCHING → boundingBox.height 1.5 (vs 1.8) + eyeHeight 1.27
     *     (vs 1.62) → 카메라/hitbox 토글 시 lerp 점프 발생.
     *
     * 해결: PlayerEntity.updatePose 의 isSneaking() 호출 (bytecode offset 68) 만 redirect →
     *   낙하 중에는 false 반환 → CROUCHING 분기 (offset 67-88) skip → STANDING fallthrough.
     *   vanilla 의 다른 분기 (FallFlying/Sleeping/Swimming/SpinAttack) 우선순위는 그대로.
     *
     * 위치: main mixin set 이라 client + server 양측 자동 적용 (instanceof 가드 불필요).
     *   - 클라: pose=STANDING → hitbox/eye height 토글 없음 + setModelPose 가 model.sneaking=false
     *     자동 set → BipedEntityModel.setAngles 의 sneak 분기 자동 SKIP.
     *   - 서버: pose=STANDING → 클라/서버 hitbox 동기화 → 좁은 천장 통과 등 위치 reject 회피.
     *
     * SM 상태 (Crawling/CrawlClimbing/HeadJumping/Sliding/Swimming/Diving) 는 sm_updatePose_*
     * 가 이미 ci.cancel() 후 setPose() 처리 → vanilla 본체 미실행 → 이 redirect 영향 없음.
     */
    @Redirect(method = "getDesiredPose",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/world/entity/player/Player;isShiftKeyDown()Z"))
    private boolean sm_redirectIsSneakingForFallingPose(Player self) {
        boolean original = self.isShiftKeyDown();
        if (!original) return false;
        if (!SmartMovingConfig.Config.enabled) return original;

        // isFalling 가드 (sm_setAnglesHead 와 동일 조건):
        //   !onGround && fallDistance > fallAnimationDistanceMinimum && !touchingWater &&
        //   !fallFlying && !abilities.flying.
        // SM 상태 (isClimbing 등) 는 SmartMovingClientState/Server 에 분리 저장이라 양측
        // 직접 참조 불가. 그러나 climbing 중에는 SmartMovingClimber 가 fallDistance=0 으로
        // reset 하므로 fallDistance > minimum 조건이 자연스레 climbing 제외.
        boolean isFalling = !self.onGround()
                && self.fallDistance > SmartMovingConfig.Config.fallAnimationDistanceMinimum
                && !self.isInWater()
                && !self.isFallFlying()
                && !self.getAbilities().flying;
        if (isFalling) return false;

        // 🔴 fix #77 (BUG #2.6, 2026-05-10, 사용자 보고 "weeping/twisting vines + sneak 시 박스 작아짐"):
        //   일반 사다리/덩굴 + sneak: sm_resetSneakInClimb 가드 (`sm.isClimbing` 등) 매치 →
        //     setPose(STANDING) 강제 → 박스 1.8m 유지. 매달림 = SM 자체 motion 처리.
        //   weeping/twisting vines (vanilla bypass = sm.isClimbing=false): sm_resetSneakInClimb 미트리거
        //     → fix #75 후 vanilla isSneaking()=true → vanilla updatePose → CROUCHING → 박스 1.5m.
        //
        //   해결: vanilla updatePose 안 isSneaking() 호출만 redirect → false 반환 → CROUCHING 분기
        //     skip → STANDING fallthrough. isHoldingOntoLadder() = isSneaking() 호출은 redirect 영향
        //     X (이건 vanilla 의 다른 메서드 안에서 호출) → 매달림 유지 (= 기능 보존).
        //   메모리 project_vine_animation_complete.md 의 "weeping/twisting vines = 사다리 자세
        //     + vanilla 매달림 motion" 의도와 정합.
        //   이 redirect 는 PlayerEntity (main mixin) 라 client + server 양측 자동 적용.
        net.minecraft.world.level.block.Block blockAtPos = self.level()
                .getBlockState(self.blockPosition()).getBlock();
        if (blockAtPos == net.minecraft.world.level.block.Blocks.WEEPING_VINES
                || blockAtPos == net.minecraft.world.level.block.Blocks.WEEPING_VINES_PLANT
                || blockAtPos == net.minecraft.world.level.block.Blocks.TWISTING_VINES
                || blockAtPos == net.minecraft.world.level.block.Blocks.TWISTING_VINES_PLANT) {
            return false;
        }

        return original;
    }

    /**
     * 3-3: addExhaustion() 소진 차단.
     * disableAddExhaustion이 true인 동안 vanilla 소진 추가를 전부 차단한다.
     *
     * addExhaustion()은 PlayerEntity에서 선언되므로 여기서 inject.
     * ServerPlayerEntity에는 선언되어 있지 않아 MixinServerPlayerEntity에서 inject 불가.
     */
    @Inject(method = "causeFoodExhaustion", at = @At("HEAD"), cancellable = true)
    private void sm_addExhaustion(float exhaustion, CallbackInfo ci) {
        if (!((Object) this instanceof ServerPlayer player)) return;
        SmartMovingServer sm = SmartMovingServer.get(player);
        if (sm.disableAddExhaustion) {
            ci.cancel();
        }
    }
}
