package choco.ratel.smartmoving.mixin.client;

import choco.ratel.smartmoving.client.SmartMovingClientState;
import choco.ratel.smartmoving.config.SmartMovingConfig;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 5-8 (client-side): move() HEAD/TAIL hook — STEP_HEIGHT suppression/restoration + accumulated movement distance.
 * 8-6: swimming sound accumulation — distance-based playback.
 *
 * Client-side implementation corresponding to server-side implementation of MixinEntity (main).
 */
@Mixin(Entity.class)
@Environment(EnvType.CLIENT)
public abstract class MixinEntityClient {

    /**
     * 5-8 (client-side) after:
     *   - accumulate climbing movement distance (for exhaustion calculation)
     *   - accumulate swimming sound (8-6)
     *
     * Original: afterMoveEntity() — distanceSwom accumulation, playback when SwimSoundDistance exceeded.
     * SwimSoundDistance = 1/0.7F ≈ 1.4286F (swim_dive.md recorded value).
     * Sound: volume=0.05F, pitch=1.0F ± rand*0.4F.
     *
     * Red circle fix #73 phase 2 (2026-05-10): remove all STEP_HEIGHT=0 suppression/restoration logic.
     *   See MixinEntity.sm_afterMove comment for details.
     */
    @Inject(method = "move", at = @At("TAIL"))
    private void sm_afterMove_client(MoverType type, Vec3 movement, CallbackInfo ci) {
        if (!((Object) this instanceof LocalPlayer player)) return;

        // BUG-24 (session 36): when SM disabled, skip SmartStatistics update + heightOffset correction + climb distance
        //   accumulation + swim sound → vanilla normal. block player.setPos impact from remaining sm.heightOffset
        //   (possible cause 1 of flight entry stutter).
        if (!SmartMovingConfig.Config.enabled) return;

        SmartMovingClientState sm = SmartMovingClientState.get(player);

        // C-25: SmartStatistics update — prevX/Y/Z are previous positions saved in tickMovement HEAD
        // yaw: original SmartRenderRender.currentCameraAngle = rotationYaw / RadiantToAngle for
        sm.stats.calculate(player.xo, player.yo, player.zo,
                           player.getX(), player.getY(), player.getZ(),
                           player.getYRot());

        // Accumulate climbing movement distance
        if (sm.isClimbing || sm.isCrawlClimbing || sm.isCeilingClimbing) {
            sm.distanceClimbedModified += movement.length() * (sm.isClimbing ? 1.2 : 0.9);
        }

        // Red circle fix #14 revert (2026-05-08): attempted mixin offset + sm_afterMove setPos correction mapping →
        //   incompatible with vanilla `Entity.move` collision processing (box feet = player.y +1m collision check basis →
        //   when ground reached player.y = bb.minY - 1m = ground -1m → push out → vibration).
        //   To 1:1 map original (box feet +1m + posY unchanged) to 1.21.1 requires complex mapping with
        //   calculateBoundingBox interception + setPos/prevY/lastRenderY/Camera baseline sync etc. — separate
        //   cycle. Current setPos correction is inactive + MixinEntity's POSE=SLIDING guard blocks mixin offset
        //   → box feet = player.y (same as STANDING), box head = player.y +0.8 (1m lower = less height).
        // if (sm.heightOffset != 0F && sm.isHeadJumping) {
        //     player.setPos(player.getX(), player.getY() + sm.heightOffset, player.getZ());
        // }

        // Accumulate swimming sound (8-6)
        // isSwimming_sm: SM swimming state (field name separated to distinguish from vanilla isSwimming())
        if (sm.isSwimming_sm) {
            sm.distanceSwom += movement.horizontalDistance();
            // SwimSoundDistance = 1/0.7F (swim_dive.md: "distanceSwom > SwimSoundDistance (= 1/0.7F ≈ 1.4286F)")
            final float SWIM_SOUND_DISTANCE = 1.0F / 0.7F;
            if (sm.distanceSwom > SWIM_SOUND_DISTANCE) {
                sm.distanceSwom -= SWIM_SOUND_DISTANCE;
                // Original sound: "random.splash" → 1.21.1: SoundEvents.ENTITY_PLAYER_SWIM
                // volume=0.05F, pitch=1.0F ± rand*0.4F (swim_dive.md recorded value)
                player.playSound(
                        SoundEvents.PLAYER_SWIM,
                        0.05F,
                        1.0F + (player.getRandom().nextFloat() - 0.5F) * 0.4F
                );
            }
        }
    }

    /**
     * isSneaking() 오버라이드 — SM 상태 기반 스니킹 판정.
     * 원본: SmartMovingSelf.isSneaking() (SmartMovingPlayerBase.java, 1972-1981줄)
     *
     * isSneaking()은 Entity에 정의됨 — LivingEntity/PlayerEntity 오버라이드 없음, Entity Mixin에서 처리.
     * Config 비활성 또는 탈것 탑승 시 vanilla에 위임.
     */
    @Inject(method = "isShiftKeyDown", at = @At("HEAD"), cancellable = true)
    private void sm_isSneaking(CallbackInfoReturnable<Boolean> cir) {
        if (!((Object) this instanceof LocalPlayer player)) return;
        SmartMovingClientState sm = SmartMovingClientState.get(player);
        SmartMovingConfig cfg = SmartMovingConfig.Config;

        if (!cfg.enabled || player.isPassenger()) return;

        if (sm.forceIsSneaking != null) { cir.setReturnValue(sm.forceIsSneaking); return; }

        // 🔴 fix #75 (BUG #2, 2026-05-10, 사용자 보고 "weeping/twisting vines + sneak 진동"):
        //   진동 사이클: pose toggle (STANDING ↔ CROUCHING) → 박스 height 토글 (1.8 ↔ 1.5)
        //     → vanilla move/collision 결과 onGround 토글 → 우리 sm_isSneaking 의
        //     `(isSlow && onGround)` 분기 결과 토글 → vanilla updatePose 가 isSneaking 따라
        //     pose 토글 → 무한 사이클.
        //   원본 SmartMovingSelf.isSneaking 식 자체는 1:1 매핑이지만 1.21.1 vanilla 의 박스
        //   height 변경 → onGround 토글 quirk + weeping/twisting vines 의 isHoldingOntoLadder
        //   특수 처리 결합으로 1.21.1 만 발생. 1.7.10 vanilla 에는 weeping/twisting vines 없음.
        //
        //   메모리 project_vine_animation_complete.md 의 의도 = "weeping/twisting vines 는
        //   vanilla 동작 그대로". sm_isSneaking 도 해당 시나리오에서 vanilla 위임 → vanilla 의
        //   안정된 SNEAKING flag (input.sneaking 그대로) 사용 → pose 안정 → 진동 X.
        net.minecraft.world.level.block.Block _blockAtPos = player.level()
                .getBlockState(player.blockPosition()).getBlock();
        if (_blockAtPos == net.minecraft.world.level.block.Blocks.WEEPING_VINES
                || _blockAtPos == net.minecraft.world.level.block.Blocks.WEEPING_VINES_PLANT
                || _blockAtPos == net.minecraft.world.level.block.Blocks.TWISTING_VINES
                || _blockAtPos == net.minecraft.world.level.block.Blocks.TWISTING_VINES_PLANT) {
            return;  // override skip → vanilla isSneaking 그대로
        }

        // 🔴 fix #83 (2026-05-12, 사용자 보고 "슬라이딩 시 화면 흔들림 + 발소리 + 비행 토글 가능"):
        //   1.21.1 vanilla bobbing/footstep/jump double-tap 비행 토글이 모두 isSneaking() 기반.
        //   원본 식 (= isSliding 가드 없음) 그대로 매핑하면 슬라이딩 시 isSneaking()=false →
        //   일반 walking 처리 → 사용자 보고 BUG.
        //   1.21.1 매핑에서는 isSliding 시 isSneaking()=true 강제 + clipAtLedge override (fix #84)
        //   로 step-back 부작용 차단.
        boolean result = (sm.isSlow && player.onGround())
                || (!cfg.sneak && sm.wouldIsSneaking && sm.jumpCharge > 0)
                || (!cfg.crawlOverEdge && sm.isCrawling && !sm.isClimbing)
                || sm.isSliding;
        cir.setReturnValue(result);
    }

    /**
     * 🔴 사다리/덩굴 등반 + W + sneak 시 SNEAKING flag 설정 차단.
     *
     * vanilla 1.21.1 자세 결정 (CROUCHING) 메커니즘이 SNEAKING flag 또는 input.sneaking
     * 직접 사용 (정확한 method mapping 다름) → 우리 isSneaking() override 만으로 부족.
     *
     * Entity.setSneaking(boolean) 은 매 틱 vanilla 가 input.sneaking 으로 호출 →
     * SNEAKING flag (DataTracker) 설정. 이 flag 자체를 차단하면 자세 결정 + isSneaking()
     * + 모든 sneak 영향 일괄 차단.
     *
     * 사용자 의도: "사다리 + W + sneak → 아무 동작 없이 그냥 등반".
     *   - sm.isClimbing && forward > 0 && sneaking=true → setSneaking(true) 차단.
     *   - 그 외 모든 케이스: vanilla 정상 처리.
     */
    @Inject(method = "setShiftKeyDown", at = @At("HEAD"), cancellable = true)
    private void sm_setSneaking(boolean sneaking, CallbackInfo ci) {
        if (!((Object) this instanceof LocalPlayer player)) return;
        if (!SmartMovingConfig.Config.enabled) return;
        SmartMovingClientState sm = SmartMovingClientState.get(player);
        if (sm.isClimbing && player.input.getMoveVector().y > 0F && sneaking) {
            ci.cancel();
        }
    }

    /**
     * pushOutOfBlocks: 서버→클라이언트 위치 동기화 직후 억제.
     * 원본: SmartMovingSelf.pushOutOfBlocks (SmartMovingSelf.md L1263-1277)
     *
     * multiPlayerInitialized > 0이면 실행 취소 후 카운터 1 감소.
     * 1.21.1: Entity.pushOutOfBlocks(double,double,double) — Entity에 정의됨.
     */
    // fix #175 v1/v2/v3 모두 시도 — inject 자체 활성 X (= dump FIX175-CHECK 매치 0). revert.
    // 다음 방향: main mixin (MixinEntity.sm_offsetBoundingBoxForFlying) 의 가드에 SM state 검사 추가.

    @Inject(method = "moveTowardsClosestSpace(DDD)V", at = @At("HEAD"), cancellable = true)
    private void sm_pushOutOfBlocks(double x, double y, double z, CallbackInfo ci) {
        if (!((Object) this instanceof LocalPlayer player)) return;
        SmartMovingClientState sm = SmartMovingClientState.get(player);
        if (sm.multiPlayerInitialized > 0) {
            sm.multiPlayerInitialized--;
            ci.cancel();
        }
    }

}
