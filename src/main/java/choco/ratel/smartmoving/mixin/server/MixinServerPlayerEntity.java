package choco.ratel.smartmoving.mixin.server;

import choco.ratel.smartmoving.server.SmartMovingServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 서버 플레이어 전용 동작을 Player 선언 메서드에 inject하고 ServerPlayer로 제한한다.
 *
 * - 3-1-B: tick() HEAD — crawlingCooldown 카운트다운
 * - C-22: travel() HEAD/TAIL — SM 소진 배치 처리
 *
 * 주의: isSneaking(Entity), isInsideWall(LivingEntity), addExhaustion(PlayerEntity)은
 * ServerPlayer에 선언되어 있지 않으므로 선언 클래스인 Player를 대상으로 한다.
 */
@Mixin(Player.class)
public abstract class MixinServerPlayerEntity {

    /**
     * 3-1-B: crawlingCooldown 카운트다운 (beforeOnUpdate 해당).
     * 크롤링 종료 후 10틱간 isInsideWall() 억제 타이머를 매 틱 감소한다.
     * 원본: SmartMovingServer.beforeOnUpdate() → if (crawlingCooldown > 0) crawlingCooldown--
     */
    @Inject(method = "tick", at = @At("HEAD"))
    private void sm_beforeTick(CallbackInfo ci) {
        if (!((Object) this instanceof ServerPlayer player)) return;
        SmartMovingServer sm = SmartMovingServer.get(player);
        if (sm.crawlingCooldown > 0) sm.crawlingCooldown--;
    }

    // ── C-22: travel() HEAD/TAIL — SM 소진 배치 처리 ─────────────────────────
    // 원본: addMovementStat()이 없어짐 → travel() 인라인 처리 (A-27 확인)
    // HEAD: beforeAddMovingHungerBatch() — 소진 차단 시작
    // TAIL: hunger 반영 후 afterAddMovingHungerBatch() — 소진 차단 해제

    @Inject(method = "travel", at = @At("HEAD"))
    private void sm_beforeTravel(Vec3 movementInput, CallbackInfo ci) {
        if (!((Object) this instanceof ServerPlayer player)) return;
        SmartMovingServer.get(player).beforeAddMovingHungerBatch();
    }

    @Inject(method = "travel", at = @At("TAIL"))
    private void sm_afterTravel(Vec3 movementInput, CallbackInfo ci) {
        if (!((Object) this instanceof ServerPlayer player)) return;
        SmartMovingServer sm = SmartMovingServer.get(player);
        if (sm.hunger > 0F) {
            sm.disableAddExhaustion = false;
            player.causeFoodExhaustion(sm.hunger);
            sm.hunger = 0F;
        }
        sm.afterAddMovingHungerBatch();
    }
}
