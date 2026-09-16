package choco.ratel.smartmoving.client;

import choco.ratel.smartmoving.config.SmartMovingConfig;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Client-side sliding physics logic.
 * Original: SmartMovingSelf.landMotion() sliding branch, SmartMoving.spawnParticles() ported.
 *
 * Included items:
 *   9-4: handleSliding() — sliding damping formula + strafe forced rotation + travel() replacement
 *   9-5: spawnSlidingParticle() — BlockState-based particle spawning
 *
 * Exit conditions (sneak release / speed threshold) handled at SlideToHeadJumping next position
 * in SmartMovingClientState main tick. Original L2563-L2567 1:1.
 */
@Environment(EnvType.CLIENT)
public final class SmartMovingSlider {

    private SmartMovingSlider() {}

    // ── [9-4] handleSliding ──────────────────────────────────────────────────

    /**
     * Apply sliding horizontal damping.
     * Replace vanilla travel() by calling move() directly.
     *
     * Damping formula (original SmartMovingSelf.landMotion() L729 isSliding branch):
     *   damping = 1 / (((1/slip - 1) / 25) * _slideSlipperinessFactor + 1) * 0.98F
     *   - slip: slipperiness of block below feet (ice=0.98, normal=0.6)
     *   - _slideSlipperinessFactor: Config value (default 1.0F)
     *   - 0.98F: air resistance-like coefficient
     *
     * Exit conditions handled in SmartMovingClientState main tick (original L2563-L2567 1:1).
     *
     * @return true if SM handles it (travel() cancel target)
     */
    public static boolean handleSliding(LocalPlayer player, SmartMovingClientState sm) {
        if (!sm.isSliding) return false;
        SmartMovingConfig cfg = SmartMovingConfig.Config;
        if (!cfg.slide) {
            sm.isSliding = false;
            return false;
        }

        Vec3 vel = player.getDeltaMovement();
        // Block slipperiness below feet
        BlockState below = player.level().getBlockState(player.getOnPos());
        float slip = below.getBlock().getFriction();

        // Original damping formula
        float damping = 1F / (((1F / slip - 1F) / 25F) * cfg.slideSlipperinessFactor + 1F) * 0.98F;

        double newVx = vel.x * damping;
        double newVz = vel.z * damping;
        // 수직: 중력 적용 (슬라이딩 중 공중에 있으면 낙하)
        double newVy = vel.y - player.getAttributeValue(Attributes.GRAVITY);
        newVy *= 0.98D;

        // 원본 L730-L744: strafe 강제 회전 (좌우 키로 슬라이딩 방향 컨트롤).
        //   moveStrafing != 0 && _slideControlDegrees > 0 일 때 motionX/Z 를 회전.
        //   _slideControlDegrees: tick 당 회전량 (deg). 기본 1F.
        //   moveStrafing 부호 = Math.signum() → 좌/우 회전 방향 결정.
        //   원본 RadiantToAngle = 180/π → Math.toRadians 로 1:1.
        //   damping 적용 후 newVx/newVz 에 회전 (스칼라 damping × 단위 보존 회전 = commute → 결과 동일).
        float moveStrafing = player.input.getMoveVector().x;
        if (moveStrafing != 0F && cfg.slideControlDegrees > 0F) {
            double angle = -Math.atan(newVx / newVz);
            if (!Double.isNaN(angle)) {
                if (newVz < 0) angle += Math.PI;
                angle -= Math.toRadians(cfg.slideControlDegrees) * Math.signum(moveStrafing);
                double hMotion = Math.sqrt(newVx * newVx + newVz * newVz);
                newVx = hMotion * -Math.sin(angle);
                newVz = hMotion *  Math.cos(angle);
            }
        }

        player.setDeltaMovement(newVx, newVy, newVz);
        player.move(MoverType.SELF, player.getDeltaMovement());

        // 🔴 BUG fix (2026-05-08, 사용자 보고 "슬라이딩 → 절벽 → 자동 헤드점프 전환 안 됨"):
        //   sm_travel_client 의 ci.cancel() 으로 vanilla LivingEntity.travel 본체 skip →
        //   본체 안의 fallDistance 갱신 식 (`if onGround → fallDistance=0; else if vy<0 →
        //   fallDistance -= vy`) 도 skip. 슬라이딩 중 절벽 떨어짐 → fallDistance=0 유지 →
        //   SlideToHeadJumping 임계 (0.05F) 도달 X → 자동 전환 X.
        //   원본 1.7.10 흐름: SM superMoveEntityWithHeading 가 vanilla moveEntity 호출 →
        //   moveEntity 안에서 fallDistance 자동 갱신. 1.21.1 vanilla travel cancel 이라
        //   직접 매핑 필요.
        //   해결: vanilla 1.21.1 LivingEntity.travel 의 fallDistance 갱신 식 1:1 매핑.
        // 🔴 fix #67 (2026-05-10, dump 분석 — root cause 확정):
        //   isSliding 진행 중 box 발 = ground top 정확 동등 시 vanilla *touching* 으로 onGround=false.
        //   기존 식: onGround false → else if (vy<0) → fall += -vy 누적 → cycle BUG.
        //   해결: box.minY ≈ solid top (epsilon 1e-3 매치) 시도 fall=0 reset (= touching=ground 간주).
        net.minecraft.world.phys.AABB _bbS67 = player.getBoundingBox();
        double _solidTopS67 = SmartMovingClientState.getMaxPlayerSolidBetween(player, _bbS67.minY - 1.0, _bbS67.minY, 0);
        boolean _atSolidTop = Math.abs(_bbS67.minY - _solidTopS67) < 1.0E-3;
        if (player.onGround() || _atSolidTop) {
            player.fallDistance = 0.0F;
        } else if (player.getDeltaMovement().y < 0.0) {
            player.fallDistance -= (float) player.getDeltaMovement().y;
        }

        // [9-5] 슬라이딩 파티클
        spawnSlidingParticle(player, sm, new Vec3(newVx, 0, newVz));

        // 종료 조건은 SmartMovingClientState 메인 tick (SlideToHeadJumping 다음 위치, 원본 L2563-L2567)
        // 에서 sneak 떼기 + horizontalSpeedSquare < stopFactor*0.01 + wasCrawling=toCrawling() 1:1 처리.
        return true;
    }

    // ── [9-5] spawnSlidingParticle ───────────────────────────────────────────

    /**
     * 슬라이딩 파티클 생성.
     * 원본: SmartMoving.spawnParticles() isSliding 분기.
     *
     * 원본 동작:
     *   spawnSlindingParticle += dt (타이머 누적)
     *   if > _slideParticlePeriodFactor * 0.1F (= 0.5F * 0.1F = 0.05F):
     *       파티클: blockcrack_<id>_<meta> → 1.21.1 ParticleTypes.BLOCK + BlockState
     *       motionX/Z = 이동방향 * -4D, motionY = 1.5D
     *       타이머 리셋
     */
    private static void spawnSlidingParticle(LocalPlayer player, SmartMovingClientState sm, Vec3 horizontal) {
        if (!player.level().isClientSide()) return;

        SmartMovingConfig cfg = SmartMovingConfig.Config;
        sm.spawnSlindingParticle += 1F;
        float threshold = cfg.slideParticlePeriodFactor * 0.1F;

        if (sm.spawnSlindingParticle > threshold) {
            sm.spawnSlindingParticle = 0F;

            BlockState below = player.level().getBlockState(player.blockPosition().below());
            if (below.isAir()) return;

            double speed = horizontal.horizontalDistance();
            if (speed < 0.01D) return;

            // 이동 방향 반대로 파티클 발사 (원본: motionX/Z = direction * -4D)
            double nx = horizontal.x / speed;
            double nz = horizontal.z / speed;

            player.level().addParticle(
                    new BlockParticleOption(ParticleTypes.BLOCK, below),
                    player.getX(), player.getY(), player.getZ(),
                    nx * -4D, 1.5D, nz * -4D
            );
        }
    }
}
