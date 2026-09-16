package choco.ratel.smartmoving.client;

import choco.ratel.smartmoving.config.SmartMovingConfig;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Client-side movement speed factor calculation.
 * Original: SmartMovingSelf.getConfigSpeedFactor / getPotionSpeedFactor /
 *           getNonSlowInputSpeedFactor / getSlowInputSpeedFactor ported.
 *
 * Included items:
 *   11-1: Speed factor 4-stage structure
 */
@Environment(EnvType.CLIENT)
public final class SmartMovingMover {

    private SmartMovingMover() {}

    // ── [11-1] Speed Factor 4-Stage ─────────────────────────────────────────

    /**
     * Reproduces the original `_speedUser = Creative("move.speed.user")` factory meaning.
     * The `Creative` factory returns true only in Creative game mode → In 1.21.1, this is handled
     * via runtime gameMode detection. B-6 (session 26) new addition.
     *
     * In the original `isUserSpeedEnabled() = enabled && _speedUser.value`, `_speedUser.value` is
     * true only in Creative mode, so this combination becomes a Creative check gate.
     *
     * 1.21.1 adaptation:
     *   isUserSpeedEnabled = cfg.enabled && cfg.speedUser && (gameMode == CREATIVE)
     *   cfg.speedUser is user-configured in config file (default false, Easy 1:1).
     *   gameMode check is determined at runtime on each call.
     */
    private static boolean isCreative(LocalPlayer player) {
        if (player == null) return false;
        MultiPlayerGameMode im = Minecraft.getInstance().gameMode;
        return im != null && im.getPlayerMode() == GameType.CREATIVE;
    }

    /**
     * Config-based speed multiplier.
     * Original `SmartMovingSelf.getConfigSpeedFactor()` (L154-157):
     *   `Config.enabled ? Config._speedFactor.value * Config.getUserSpeedFactor() : 1F`
     *
     * B-1 (session 25): Added `cfg.enabled` guard.
     * B-6 (session 26): Added Creative-exclusive gate. `cfg.speedUser` corresponds to original
     *   `_speedUser.value` — runtime gameMode check also performed to restore Creative factory meaning.
     *   getUserSpeedFactor() itself returns 1F when `!speedUser`, so in non-Creative mode,
     *   no matter how much the user presses keys, factor=1F. However, explicit gating is closer to original structure.
     *
     * Formula (after B-6):
     *   cfg.enabled && (gameMode == CREATIVE) ? speedFactor × (1+speedUserFactor)^exp : 1F
     *
     * In non-Creative game modes, cfg.speedFactor (default 1F) is not applied — same as original.
     *   (`_speedFactor` itself is not Creative-dependent, but `getConfigSpeedFactor` has only
     *    `Config.enabled` gate, and speedFactor × userSpeedFactor where userSpeedFactor is
     *    non-1F only in Creative mode). speedFactor is a global multiplier that should apply outside Creative too — needs review.
     *
     * Warning: Strict 1:1: Original `getConfigSpeedFactor` checks only `Config.enabled` and
     *    returns `_speedFactor.value * getUserSpeedFactor()`. `getUserSpeedFactor()` returns
     *    1F when `!_speedUser.value`. i.e., outside Creative only `_speedFactor.value` applies.
     *    (`_speedFactor` is a global multiplier independent of Creative.)
     *    → In this method, the Creative gate should only block the userSpeedFactor part.
     *    Below implementation: speedFactor applies globally + userSpeedFactor gates on Creative.
     */
    public static float getConfigSpeedFactor(LocalPlayer player, SmartMovingConfig cfg) {
        if (!cfg.enabled) return 1F;
        float userFactor = isCreative(player) ? cfg.getUserSpeedFactor() : 1F;
        return cfg.speedFactor * userFactor;
    }

    /**
     * Potion effect-inclusive movement speed inverse multiplier.
     * Original `SmartMovingSelf.getPotionSpeedFactor()` (L160-162) 1:1:
     *   `Config.enabled ? getLandMovementFactor() * 10F / (sp.isSprinting() ? 1.3F : 1F) : 1F`
     * B-1 (session 25): Added `cfg.enabled` guard.
     *
     * Potion effects are already reflected in the GENERIC_MOVEMENT_SPEED attribute → beware of double application.
     * Sprint modifier (+0.3) is also included, so divide by 1.3F during sprinting to remove it.
     */
    public static float getPotionSpeedFactor(LocalPlayer player) {
        if (!SmartMovingConfig.Config.enabled) return 1F;
        // 🔴 fix #44 (2026-05-09, user report "Fox movement distance insufficient + Jump Boost pronounced"):
        //   vanilla 1.21.1's ADD_MULTIPLIED_TOTAL operation sums all modifier values then
        //   multiplies once. vanilla 1.7.10's MULTIPLY_TOTAL (op2) multiplies cumulatively per modifier.
        //   → Sprint + Speed LV5: 1.7.10 = 0.1×1.3×2.0=0.26, 1.21.1 attribute = 0.1×(1+0.3+1.0)=0.23.
        //   → 11.5% less → maxHorizontalMotion smaller → shorter distance (= user perceives "pronounced").
        //   Solution: bypass vanilla attribute formula + direct calculation using 1.7.10 formula (= base × sprint × speed × slow).
        //   Read docs/research_fox_movement_distance.md §12 carefully.
        float base = 0.1F;
        float sprintMul = player.isSprinting() ? 1.3F : 1F;
        net.minecraft.world.effect.MobEffectInstance speed =
                player.getEffect(net.minecraft.world.effect.MobEffects.SPEED);
        net.minecraft.world.effect.MobEffectInstance slow  =
                player.getEffect(net.minecraft.world.effect.MobEffects.SLOWNESS);
        float speedMul = (speed != null) ? 1F + (speed.getAmplifier() + 1) * 0.2F : 1F;
        float slowMul  = (slow  != null) ? 1F + (slow.getAmplifier()  + 1) * (-0.15F) : 1F;
        float landMovementFactor = base * sprintMul * speedMul * slowMul;
        return landMovementFactor * 10F / sprintMul;
    }

    /**
     * Original `SmartMovingSelf.getCombinedSpeedFactor()` (L149-152) 1:1:
     *   `return getConfigSpeedFactor() * getPotionSpeedFactor();`
     * B-1 (session 25) new addition — replaces inline multiplication in 6 places like Climber/Jumper.
     * B-6 (session 26): player parameter passed due to getConfigSpeedFactor signature change.
     */
    public static float getCombinedSpeedFactor(LocalPlayer player, SmartMovingConfig cfg) {
        return getConfigSpeedFactor(player, cfg) * getPotionSpeedFactor(player);
    }

    /**
     * Non-slow input multiplier (ice/sprint/running).
     * Factor application priority: ice > sprint > running (no nesting).
     *
     * isRunning = isSprinting() && !isFast (verified value A-21).
     * iceSpeedFactor: not in original config — fixed at 1.5F (verified A-25).
     */
    public static float getNonSlowInputSpeedFactor(LocalPlayer player, SmartMovingClientState sm,
                                                    SmartMovingConfig cfg) {
        // 🔴 (2026-04-28) Original SmartMovingSelf L197-227 precise 1:1 mapping.
        //   Original: isFast returns sprintFactor (1.5). !isFast returns 1.0.
        //   Separate in landMotion L712: isRunning && !isFast multiplies runFactor (1.3).
        float speedFactor = 1.0F;
        if (sm.isFast) {
            // 원본: !isLevitating ? sprintFactor : sprintFactorLevitate. Levitate 는 SmartMovingFlyer 에서 별도 처리.
            speedFactor *= cfg.sprintFactor;  // 1.5
        }
        // 🔴 원본 SmartMovingSelf L203-205: isClimbing 시 _freeClimbingHorizontalSpeedFactor 곱.
        //   원본 가드 `if(moveStrafing != 0F || moveForward != 0F)` 는 movementInput 정보 필요.
        //   우리 호출자의 climbSpeedFactor 는 updateVelocity(speed, movementInput) 매개로만
        //   motion 에 영향 → 입력 0 시 곱셈 결과 무관. 가드 생략해도 동일 효과.
        //   사용자 보고 #2/#5: 우리 코드에 이 곱셈 누락 → fence/iron_bars 등반·횡이동 속도 원본과 다름.
        if (sm.isClimbing) {
            speedFactor *= cfg.freeClimbingHorizontalSpeedFactor;
        }
        return speedFactor;
    }

    /**
     * 감속 입력 배율 (크롤링/스니킹/천장클라이밍).
     * 팩터들은 순서대로 누적 곱셈 적용.
     *
     * 🔴 fix (third-party gun mod compatibility, 2026-09-13): item-use 항 제거.
     *
     * 이전엔 여기서 `if (player.isUsingItem()) f *= 0.2F` 로 아이템 사용 중 감속을 자체
     * 하드코딩했음. 이는 위 sneak 항목의 "BUG-sneak속도" (MixinClientPlayerEntity 참고)
     * 와 같은 계열의 이중 감속 버그: vanilla `LocalPlayer.modifyInput()` 가 `isUsingItem()`
     * 시 이미 해당 아이템의 실제 `DataComponents.USE_EFFECTS` → `speedMultiplier()` 를
     * xxa/zza 에 곱해 놓고 (LivingEntity.aiStep: L3055 applyInput → L3101 movementInput 구성
     * → L3109 travel() 호출), 그 movementInput 이 그대로 sm_travel_client 의 HEAD 를 거쳐
     * handleLand/climbing/swimming 까지 전달됨. 여기서 하드코딩 0.2F 를 또 곱하면:
     *   1. 이중 적용 — vanilla 몫 × SM 몫 (기본 아이템 기준 0.2×0.2=0.04, 즉 vanilla 활
     *      당길 때 예상보다 훨씬 심하게 감속).
     *   2. 아이템별 실제 배율 무시 — 모든 아이템이 무조건 0.2배 취급됨. 총기 mod 처럼
     *      아이템마다 다른 `speedMultiplier` (예: 0.8/0.9/1.0 — 1.0 은 "조준 중에도 감속
     *      없음"을 의도한 값) 를 등록해 둔 경우, 그 값이 완전히 무시되고 전부 균일하게
     *      과하게 느려짐 (감속이 아예 없어야 할 아이템까지 0.2배로 묶임).
     *
     * sneak 은 vanilla `isMovingSlowly()` 자체를 sm_blockVanillaSlowDown 로 차단해 이중
     * 적용을 막지만, item-use 감속은 `isMovingSlowly()` 와 무관한 별도 vanilla 분기라 그
     * 차단이 적용되지 않음 — 따라서 vanilla 쪽을 막는 대신 SM 쪽 재적용을 제거해 vanilla
     * 계산(이미 movementInput 에 반영됨) 하나만 남긴다.
     */
    public static float getSlowInputSpeedFactor(LocalPlayer player, SmartMovingClientState sm,
                                                 SmartMovingConfig cfg) {
        float f = 1.0F;
        if (sm.isCrawling) f *= cfg.crawlFactor;
        else if (player.isShiftKeyDown()) f *= cfg.sneakFactor;
        if (sm.isCeilingClimbing) f *= cfg.ceilingClimbingSpeedFactor;
        return f;
    }

    /**
     * 최종 속도 팩터 = 4단계 곱셈.
     * 호출 위치: travel() SM 파이프라인 내 각 handle*() 메서드에서 speedFactor 계산 시.
     * B-6 (세션 26): getConfigSpeedFactor 시그니처 변경에 따른 player 전달.
     */
    public static float getSpeedFactor(LocalPlayer player, SmartMovingClientState sm,
                                        SmartMovingConfig cfg) {
        return getConfigSpeedFactor(player, cfg)
             * getPotionSpeedFactor(player)
             * getNonSlowInputSpeedFactor(player, sm, cfg)
             * getSlowInputSpeedFactor(player, sm, cfg);
    }

    // ── 상수 (원본 SmartMovingContext.java) ───────────────────────────────
    private static final float HORIZONTAL_GROUND_DAMPING       = 0.546F;
    private static final float HORIZONTAL_AIR_DAMPING          = 0.91F;
    private static final float HORIZONTAL_AIRODYNAMIC_DAMPING  = 0.999F;

    // ── isRunning (원본 SmartMovingSelf.isRunning) ────────────────────────
    /**
     * 원본 `SmartMovingSelf.isRunning()` (L3239-3242) 1:1:
     *   `return sp.isSprinting() && !isFast && (sp.onGround || vanilla())`.
     *
     * cfg.run 체크는 호출 측 (handleLand L712) 에서 별도로 수행 — 원본과 동일.
     */
    public static boolean isRunning(LocalPlayer player, SmartMovingClientState sm,
                                     SmartMovingConfig cfg) {
        return player.isSprinting() && !sm.isFast && (player.onGround() || sm.vanilla());
    }

    // ── 11-2: handleLand (원본 superMoveEntityWithHeading + landMotion + setLandMotions) ──

    /**
     * 🔴 (2026-04-28) 원본 SmartMovingSelf.superMoveEntityWithHeading + landMotion +
     *   setLandMotions 통째로 1:1 매핑.
     *
     * 원본 흐름 (`SmartMovingSelf.handleLand` L633-663):
     *   1. landMotion (L675-810): horizontalDamping 결정 + moveFlying (movementInput → motion).
     *   2. move (L655): vanilla move() 호출.
     *   3. handleClimbing (L657): motionY 조정 (별도 분기에서 호출).
     *   4. setLandMotions (L1176-1182): motionY -= 0.08, *= 0.98, motionX/Z *= damping.
     *
     * 비행과 동일 패턴 — vanilla travel 통째로 cancel + SM 자체 식 적용.
     * @return true = SM 처리 완료 (호출 측 ci.cancel 대상).
     */
    public static boolean handleLand(LocalPlayer player, SmartMovingClientState sm,
                                      Vec3 movementInput) {
        SmartMovingConfig cfg = SmartMovingConfig.Config;
        if (!cfg.enabled) return false;

        float moveStrafing = (float) movementInput.x;
        float moveForward  = (float) movementInput.z;

        // 원본 superMoveEntityWithHeading L119-130: speedFactor 계산.
        float speedFactor = getConfigSpeedFactor(player, cfg)
                * getPotionSpeedFactor(player)
                * getNonSlowInputSpeedFactor(player, sm, cfg);
        float slowFactor = getSlowInputSpeedFactor(player, sm, cfg);
        if (sm.vanilla()) {
            moveForward  *= slowFactor;
            moveStrafing *= slowFactor;
        } else {
            speedFactor *= slowFactor;
        }

        // 원본 landMotion L677-690: horizontalDamping 결정 + L686-687 sprintJumpVertical.
        float horizontalDamping;
        if (player.onGround() && (!sm.isJumping || sm.vanilla())) {
            BlockState below = player.level().getBlockState(player.getOnPos());
            float slip = below.getBlock().getFriction();
            horizontalDamping = slip > 0F ? slip * HORIZONTAL_AIR_DAMPING
                                          : HORIZONTAL_GROUND_DAMPING;
            // 원본 L686-687: jump 키 hold + isFast 시 sprintJumpVerticalFactor 적용.
            boolean jumpKeyPressed = Minecraft.getInstance().options.keyJump.isDown();
            if (jumpKeyPressed && sm.isFast) {
                speedFactor *= cfg.sprintJumpVerticalFactor;
            }
        } else if (sm.isAerodynamic) {
            // 🔴 fix #46 (2026-05-09, 사용자 보고 "원본은 점프강화 fox 시 착지까지 쭉, 우리는 끊김"):
            //   원본 SmartMovingSelf.landMotion L752-753:
            //     else if (isAerodynamic) horizontalDamping = HorizontalAirodynamicDamping;  // 0.999F
            //     else                    horizontalDamping = HorizontalAirDamping;          // 0.91F
            //   우리 매핑은 air 분기 항상 0.91 → fox movement 자동 전환 (SlideToHeadJumping) 발동
            //   직후 isAerodynamic=true 이지만 우리 매핑 0.91 그대로 → 매 tick × 0.91 → ~24 tick (1.2s)
            //   에 motion 거의 0 → 사용자 인지 "어디 부딪히지도 않았는데 끊김".
            //   원본 0.999F → 매 tick 99.9% 유지 → 거의 감속 X → "착지할 때까지 쭉".
            horizontalDamping = HORIZONTAL_AIRODYNAMIC_DAMPING;
        } else {
            horizontalDamping = HORIZONTAL_AIR_DAMPING;
        }

        // 원본 landMotion L703-706: jump control factor.
        // Phase F 1:1 정정: 원본 L705 `Config.enabled && ...` 가드 명시 추가 (cosmetic — 함수 시작
        //   `if (!cfg.enabled) return false` 로 이미 함수 단위 가드, 동작 동일).
        if (sm.isHeadJumping) {
            speedFactor *= cfg.headJumpControlFactor;
        } else if (cfg.enabled && !player.onGround()
                && !player.getAbilities().flying && !sm.isFlying) {
            speedFactor *= cfg.jumpControlFactor;
        }

        // 원본 landMotion L708-709: rawSpeed.
        float f3 = 0.1627714F / (horizontalDamping * horizontalDamping * horizontalDamping);
        float rawSpeed;
        if (player.onGround()) {
            // 🔴 fix #50 (2026-05-10, 사용자 100m 정확 측정 + 1.12.2 SMReboot 코드 line-by-line 비교):
            //   sm_original 1.7.10 SmartMovingSelf L709: rawSpeed = 0.1F × f3 (base hardcode).
            //   1.12.2 SMReboot SMSelf.java L682:        rawSpeed = getLandMovementFactor() × f3.
            //   = vanilla bl() = attribute getValue() = SPEED multiplier 반영된 값.
            //
            //   사용자 측정 환경 검증:
            //   - vanilla SPEED LV3 sprint+W: 1.7.10=11.06s / 1.21.1=11.11s (= vanilla 식 일치).
            //   - SM SPEED LV3 sprint+W:      1.7.10/1.12.2=5.96s / 1.21.1 우리=9.70s.
            //   - 1.12.2 SMReboot 식 적용 시: rawSpeed=0.208×1.0/1.3=0.16, speedFactor=2.4,
            //     ADD=0.376, preMove=0.829, 100m=6.03s ↔ 측정 5.96s 일치 (오차 ±0.07s).
            //
            //   sm_original 의 0.1F hardcode 식 = 잘못된 reference (= 다른 release/fork 기반).
            //   사용자 1.7.10/1.12.2 측정 환경 = 1.12.2 SMReboot 식 = SPEED multiplier 가
            //   rawSpeed 와 speedFactor (getPotionSpeedFactor) 양쪽 반영 (= double-apply 의도).
            //
            //   변경: 0.1F → player.getMovementSpeed() (= vanilla 1.21.1 attribute = 0.208).
            rawSpeed = player.getSpeed() * f3;
            // 🔴 fix #50: 1.12.2 SMReboot L683 sprint 약분 추가 (ground 분기만).
            //   원본: float rawSpeed = sp.isSprinting() ? f4 / 1.3F : f4;
            //   air 분기는 fix #45 의 0.02F hardcode 유지 (= 1.7.10 vanilla sprint 결과식 1:1).
            if (player.isSprinting()) rawSpeed /= 1.3F;
        } else {
            // 🔴 fix #45 (2026-05-09, 사용자 보고 "기본 여우무빙도 달라" / "신속 sprint 속도도 달라"):
            //   원본 SmartMovingSelf L709: rawSpeed = jumpMovementFactor / (sprint ? 1.3 : 1).
            //   vanilla 1.7.10 EntityPlayer.onLivingUpdate 가 sprint 시 jumpMovementFactor 를
            //   0.02 + 0.02×0.3 = 0.026 으로 설정 → SM 이 1.3 으로 나눠 정상화 → 결과 항상 0.02.
            //   vanilla 1.21.1 에는 jumpMovementFactor field 자체가 없어 우리는 0.02 hardcoded 후
            //   sprint 시 1.3 으로 또 나눠 0.01538 (= 23% 적음) → air 가속 부족 → headjump/fox 거리 짧음.
            //   해결: 1.7.10 결과식과 동일하게 항상 0.02. sprint 보너스는 SM 자체 sprintFactor 로 대체.
            rawSpeed = 0.02F;
        }

        // 원본 landMotion L712-713: isRunning && !isFast 시 runFactor 곱.
        // (원본 runFactorLevitate 는 levitate 시만 사용 — land 분기 무관, runFactor 만)
        if (cfg.run && isRunning(player, sm, cfg) && !sm.isFast) {
            speedFactor *= cfg.runFactor;
        }

        // 🔴 fix #51 (2026-05-10, 사용자 1.7.10/1.12.2 SmartMoving 측정 + 코드 line-by-line 비교):
        //   1.7.10 sm_original SmartMovingSelf.landMotion L714-716:
        //     if (!sp.onGround) speedFactor /= getPotionSpeedFactor();
        //   1.12.2 SMReboot SMSelf.landMotion: 이 식 자체가 없음.
        //   사용자 측정 환경 = 1.7.10 + 1.12.2 Forge SmartMoving = 1.12.2 SMReboot 식.
        //   → 분기 제거. (= 우리 매핑이 따르던 1.7.10 sm_original 가 잘못된 reference).
        //
        //   영향: 헤드점프/여우무빙 air 진행 시 SPEED multiplier 가 air 가속에 반영.
        //   - SPEED LV3 (= 1.6 multiplier): air ADD 1.62배 증가.
        //   - SPEED LV5 (= 2.0 multiplier): air ADD 2.0배 증가.
        //   - SPEED LV10 (= 3.0 multiplier): air ADD 3.0배 증가.
        //   fix #50 (ground rawSpeed) 와 의도 1:1: SPEED multiplier 가 ground/air 양쪽
        //   에 반영되는 1.12.2 SMReboot 식 1:1 매핑.
        //
        //   사용자 보고 "Jump Boost (= SPEED) 계열 시 거리 극명한 차이" + "여우무빙
        //   거리 1.7.10/1.12.2 더 멀리" 보고와 정확 일치.

        // 원본 L718: moveFlying(strafe, forward, rawSpeed * speedFactor) — motion 에 ADD.
        applyLandMoveFlying(player, moveStrafing, moveForward, rawSpeed * speedFactor);

        // 원본 L655: vanilla move() — 위치 갱신.
        player.move(MoverType.SELF, player.getDeltaMovement());

        // 원본 setLandMotions L1176-1182: motionY -= 0.08, *= 0.98, motionX/Z *= damping.
        Vec3 vel = player.getDeltaMovement();
        double newY = (vel.y - 0.08D) * 0.98D;
        double newX = vel.x * horizontalDamping;
        double newZ = vel.z * horizontalDamping;

        // vanilla LivingEntity.travel 마지막의 작은 motion 0 clamp (= 잔존 motion 정리).
        if (Math.abs(newX) < 0.003D) newX = 0.0D;
        if (Math.abs(newY) < 0.003D) newY = 0.0D;
        if (Math.abs(newZ) < 0.003D) newZ = 0.0D;

        player.setDeltaMovement(newX, newY, newZ);

        return true;
    }

    /**
     * 🔴 fix #49 (2026-05-09, 사용자 보고 "신속 sprint 속도 원본과 달라"):
     *   원본 SmartMovingSelf.landMotion L718 `sp.moveFlying(strafe, forward, rawSpeed*speedFactor)`
     *   는 **3-인자 vanilla EntityLivingBase.moveFlying** 호출. `SmartMovingBase.moveFlying`
     *   5-인자 버전 (= sqrt(sqrt) 정규화) 와 다름.
     *
     *   vanilla 1.7.10 EntityLivingBase.moveFlying 식 (= land 분기 1:1):
     *     f3 = strafe² + forward²;
     *     if (f3 >= 0.0001F) {
     *         f3 = sqrt(f3); if (f3 < 1.0F) f3 = 1.0F;
     *         f3 = speed / f3;
     *         strafe *= f3; forward *= f3;
     *         sin = sin(yaw); cos = cos(yaw);
     *         motionX += strafe*cos - forward*sin;
     *         motionZ += forward*cos + strafe*sin;
     *     }
     *
     *   기존 우리 매핑은 `total2 = sqrt(sqrt(diffX²+diffZ²))` 추가 정규화 → W만 누름 시
     *   motion ADD = `speed × 0.98 / sqrt(0.98) ≈ speed × 0.99` (= 1% 더 큼). 매 tick 누적
     *   → 사용자 보고 "신속 sprint 속도 다름". Fix #49 = 단순 ADD 로 정정.
     */
    private static void applyLandMoveFlying(LocalPlayer player,
                                             float moveStrafing, float moveForward, float speed) {
        float f3 = moveStrafing * moveStrafing + moveForward * moveForward;
        if (f3 < 0.0001F) return;
        f3 = Mth.sqrt(f3);
        if (f3 < 1.0F) f3 = 1.0F;
        f3 = speed / f3;
        moveStrafing *= f3;
        moveForward  *= f3;

        float yawRad = player.getYRot() * (float) Math.PI / 180F;
        float sin = Mth.sin(yawRad);
        float cos = Mth.cos(yawRad);

        Vec3 vel = player.getDeltaMovement();
        player.setDeltaMovement(
                vel.x + moveStrafing * cos - moveForward * sin,
                vel.y,
                vel.z + moveForward * cos + moveStrafing * sin);
    }
}
