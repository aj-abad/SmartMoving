package choco.ratel.smartmoving.mixin;

import choco.ratel.smartmoving.server.SmartMovingServer;
import java.util.List;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 5-5 (server): isClimbing() override — return false during SM custom climbing.
 * 6-2 (server): isInSwimmingPose() override — return false during SM crawling.
 * 3-3-C: tickStatusEffects HEAD/TAIL — potion update cycle reversal handling.
 * 3-7: tickMovement TAIL — extend item pickup range when isSmall.
 *
 * Note: Server movement pipeline (floatKick/fall distance/position checks) handled in MixinServerPlayNetworkHandler.
 * Client-only items (jump, applyClimbingSpeed, isClimbing) → MixinLivingEntityClient
 */
@Mixin(LivingEntity.class)
public abstract class MixinLivingEntity {

    /**
     * 5-5 (server): Force isClimbing() = false during SM custom climbing.
     *
     * Purpose 1: block applyClimbingSpeed() x/z ±0.15F clamp
     * Purpose 2: block vanilla y=0.2 forced application when isClimbing() in applyMovementInput()
     *
     * Check ServerPlayerEntity instance → determine based on SmartMovingServer state.
     * Client-side equivalent logic → MixinLivingEntityClient.sm_isClimbing_client()
     */
    @Inject(method = "onClimbable", at = @At("HEAD"), cancellable = true)
    private void sm_isClimbing_server(CallbackInfoReturnable<Boolean> cir) {
        if (!((Object) this instanceof ServerPlayer player)) return;
        SmartMovingServer sm = SmartMovingServer.get(player);
        if (sm.isClimbing || sm.isCrawlClimbing || sm.isCeilingClimbing) {
            cir.setReturnValue(false);
        }
    }

    /**
     * 6-2 (server): Force isInSwimmingPose() = false during SM crawling.
     *
     * SM crawling uses SWIMMING pose, but vanilla isInSwimmingPose()=true causes:
     *   - setupTransforms Branch 2: X-90° rotation + translate(0,-1,0.3) → render broken
     *   - updateLeaningPitch(): leaningPitch rises to 1.0 → auto-rotation occurs
     *
     * Server side: check ServerPlayerEntity instance.
     * Client side → MixinLivingEntityClient.sm_isInSwimmingPose_client()
     * Yarn: isInSwimmingPose (intermediary: method_20232 confirmed)
     */
    @Inject(method = "isVisuallySwimming", at = @At("HEAD"), cancellable = true)
    private void sm_isInSwimmingPose_server(CallbackInfoReturnable<Boolean> cir) {
        if (!((Object) this instanceof ServerPlayer player)) return;
        SmartMovingServer sm = SmartMovingServer.get(player);
        if (sm.isCrawling || sm.isCrawlClimbing) {
            cir.setReturnValue(false);
        }
    }

    /**
     * 3-7: afterOnLivingUpdate() — isSmall 시 아이템 습득 범위 Y+0.25F 확장.
     * 크롤링 중 바닥에 가까워 기본 AABB로 닿지 않는 아이템을 획득할 수 있도록 보정.
     * 원본: SmartMovingServer.afterOnLivingUpdate() / SmallSizeItemGrabHeight = 0.25F
     *
     * 알고리즘:
     *   offsetBox  = playerBox + (1, 0.25, 1) — Y방향 0.25 확장
     *   standardBox = offsetBox.expand(0, -0.25, 0) — Y 원상복구
     *   offsetBox에만 있고 standardBox에는 없는 엔티티 → onPlayerCollision
     */
    @Inject(method = "aiStep", at = @At("TAIL"))
    private void sm_afterTickMovement(CallbackInfo ci) {
        if (!((Object) this instanceof ServerPlayer player)) return;
        SmartMovingServer sm = SmartMovingServer.get(player);
        if (!sm.isSmall) return;
        if (player.getHealth() <= 0) return;

        AABB offsetBox = player.getBoundingBox().inflate(1.0, SmartMovingServer.SMALL_SIZE_ITEM_GRAB_HEIGHT, 1.0);
        List<Entity> offsetEntities = player.level().getEntities(player, offsetBox);
        if (offsetEntities.isEmpty()) return;

        AABB standardBox = offsetBox.inflate(0.0, -SmartMovingServer.SMALL_SIZE_ITEM_GRAB_HEIGHT, 0.0);
        List<Entity> standardEntities = player.level().getEntities(player, standardBox);

        for (Entity entity : offsetEntities) {
            if (standardEntities.contains(entity)) continue;
            if (!entity.isRemoved()) entity.playerTouch(player);
        }
    }

    // ── C-23: tickStatusEffects() HEAD/TAIL — 포션 업데이트 사이클 역전 처리 ──
    // 원본: beforeUpdatePotionEffects→afterAddMovingHungerBatch,
    //       afterUpdatePotionEffects→beforeAddMovingHungerBatch (의도적 역전, A-28 확인)
    // HEAD: afterAddMovingHungerBatch (depth 감소)
    // TAIL: beforeAddMovingHungerBatch (depth 증가)

    @Inject(method = "tickEffects", at = @At("HEAD"))
    private void sm_beforeTickStatusEffects(CallbackInfo ci) {
        if (!((Object) this instanceof ServerPlayer player)) return;
        SmartMovingServer.get(player).afterAddMovingHungerBatch();
    }

    @Inject(method = "tickEffects", at = @At("TAIL"))
    private void sm_afterTickStatusEffects(CallbackInfo ci) {
        if (!((Object) this instanceof ServerPlayer player)) return;
        SmartMovingServer.get(player).beforeAddMovingHungerBatch();
    }

    /**
     * 3-5: isInsideWall() 억제.
     * 크롤링 종료 직후 crawlingCooldown 틱 동안 블록 내부 판정을 억제하여
     * 크롤링 출구에서 플레이어가 벽 안에 갇히는 현상을 방지한다.
     *
     * isInsideWall()은 LivingEntity에서 마지막으로 override되므로 여기서 inject.
     * ServerPlayerEntity에는 선언되어 있지 않아 MixinServerPlayerEntity에서 inject 불가.
     */
    @Inject(method = "isInWall", at = @At("HEAD"), cancellable = true)
    private void sm_isInsideWall(CallbackInfoReturnable<Boolean> cir) {
        if (!((Object) this instanceof ServerPlayer player)) return;
        SmartMovingServer sm = SmartMovingServer.get(player);
        if (sm.crawlingCooldown > 0) {
            cir.setReturnValue(false);
        }
    }
}
