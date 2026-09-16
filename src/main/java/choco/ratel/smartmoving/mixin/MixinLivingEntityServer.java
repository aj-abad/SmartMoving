package choco.ratel.smartmoving.mixin;

import choco.ratel.smartmoving.config.SmartMovingConfig;
import choco.ratel.smartmoving.server.SmartMovingServer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 🔴 v26.18 (BUG-7 server tick order race real root fix) — add head/below ClimbableBlock guard.
 *
 * vanilla `MinecraftServer.tickWorlds` exact order:
 *   1. playerManager.forEach(playerTick) → ServerPlayerEntity.playerTick → super.tick →
 *      PlayerEntity.tick → LivingEntity.tick → tickMovement → travel → our inject.
 *   2. ServerWorld.tick (= entity tick + EntityTrackerEntry broadcast).
 *   3. networkIo.tick (= packet processing, processStatePacket, onPlayerMove).
 *
 * ★★★ packet processing occurs after our inject + broadcast. That is, at our inject point:
 *   - sm.isClimbCrawling etc = previous server tick's packet processing result (= 1 tick lag).
 *   - server.player.x/y/z = previous server tick's PlayerMoveC2SPacket result.
 *
 * True root of "sometimes works": depending on client packet arrival timing, it reaches
 * server tick N-1 or N networkIo. N-1 arrival = guard passes on server tick N. N arrival = lag until server tick N+1.
 *
 * v26.17 limitations:
 *   - vanilla `player.isClimbing()` = BlockState check of player.getBlockPos() (= feet position).
 *   - ladder end + 0.5m ICC entry point = floor(y) → AIR → isClimbing()=false → guard fails.
 *   - self side `onClimbable` = SmartMovingClimber checks both hands(head=y+1)/feet(y). Broader.
 *
 * v26.18 fix:
 *   - add head/feet/below ClimbableBlock checks to server-side guard.
 *   - head BlockPos (y+1) ClimbableBlock check → covers ladder end reach case.
 *   - below BlockPos (y-1) ClimbableBlock check → covers immediately after ladder end case.
 *   - this guard = server-side substitute of self side onClimbable, more accurate.
 *
 * vanilla travel cancel + clamp + move mapping kept as v26.16.
 */
@Mixin(LivingEntity.class)
public abstract class MixinLivingEntityServer {

    @Inject(method = "travel", at = @At("HEAD"), cancellable = true)
    private void sm_iccVelClampServer(Vec3 movementInput, CallbackInfo ci) {
        if (!((Object) this instanceof ServerPlayer player)) return;
        if (!SmartMovingConfig.Config.enabled) return;
        SmartMovingServer sm = SmartMovingServer.get(player);

        // 🔴 v26.18 가드 확장: head/feet/below ClimbableBlock 검사 추가.
        //   - sm bit OR = SM relay packet 결과 (= 이전 server tick 의 networkIo 결과, lag 1 tick).
        //   - vanilla isClimbing() = 발 BlockPos 의 ClimbableBlock 검사 (즉시).
        //   - climbHead = 머리 BlockPos (y+1) ClimbableBlock 검사 (= 사다리 끝 도달 case cover).
        //   - climbBelow = 발 아래 BlockPos (y-1) ClimbableBlock 검사 (= 사다리 끝 통과 직후 case).
        boolean smBitClimb = sm.isClimbCrawling || sm.isClimbing || sm.isCrawlClimbing
                || sm.isCeilingClimbing;
        boolean vanillaClimb = player.onClimbable();
        boolean climbHead = false;
        boolean climbBelow = false;
        if (!smBitClimb && !vanillaClimb) {
            BlockPos head = BlockPos.containing(player.getX(), player.getY() + 1.0, player.getZ());
            BlockPos below = BlockPos.containing(player.getX(), player.getY() - 1.0, player.getZ());
            climbHead = player.level().getBlockState(head).is(BlockTags.CLIMBABLE);
            climbBelow = player.level().getBlockState(below).is(BlockTags.CLIMBABLE);
        }
        if (!(smBitClimb || vanillaClimb || climbHead || climbBelow)) return;

        // self side `MixinLivingEntityClient.sm_travel_client` L385-411 1:1.
        // 1) velocity clamp.
        Vec3 v = player.getDeltaMovement();
        double clampH = 0.15D;
        double mx = Math.max(-clampH, Math.min(clampH, v.x));
        double mz = Math.max(-clampH, Math.min(clampH, v.z));
        double my = v.y;
        if (sm.isSneakButtonPressed && my < 0) {
            my = 0;
        } else if (my < -clampH) {
            my = -clampH;
        }
        if (mx != v.x || mz != v.z || my != v.y) {
            player.setDeltaMovement(mx, my, mz);
        }
        player.fallDistance = 0;

        // 2) self side L409 동등: 우리 clamp velocity 로 직접 move (vanilla 의 추가 friction/
        //    gravity 적용 전 위치 갱신). collision 검사 진행.
        player.move(MoverType.SELF, player.getDeltaMovement());

        // 3) self side L411 동등: vanilla travel 자체 cancel — 추가 motion 차단.
        ci.cancel();
    }
}
