package choco.ratel.smartmoving.config;

import net.fabricmc.loader.api.FabricLoader;

import java.io.*;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/**
 * SmartMoving configuration file load/save.
 * Original net.smart.properties system simplified to java.util.Properties.
 * Config file: config/smart_moving_options.properties
 */
public class SmartMovingConfig {

    // ── Version (config_system.md: SmartMovingConfig._sm_current = "3.2" → 1.21.1 port is "1.0") ──
    public static final String SM_VERSION = "1.0";

    // ═══════════════════════════════════════════════════════════════════════════
    // Phase B (focus #2.5) — Jumper determination helper static infrastructure
    // Original SmartMovingClientConfig L172-L192 (Speed/Type constants) +
    //      SmartMovingSelf L2148-L2163 (getJumpSpeed helper).
    // ═══════════════════════════════════════════════════════════════════════════

    // === Speed constants (original SmartMovingClientConfig L172-L176, B-2) ===
    //   getJumpSpeed return + isJumpingEnabled / getJumpHorizontalFactor /
    //   getJumpVerticalFactor / getMaxHorizontalMotion speed parameter domain.
    public static final int SPEED_SPRINTING = 0;
    public static final int SPEED_RUNNING   = 1;
    public static final int SPEED_WALKING   = 2;
    public static final int SPEED_SNEAKING  = 3;
    public static final int SPEED_STANDING  = 4;

    // === Jump Type constants (original SmartMovingClientConfig L178-L192, B-2.5) ===
    //   tryJump / isJumpingEnabled / getJumpHorizontalFactor / getJumpVerticalFactor /
    //   getMaxHorizontalMotion / getHeadJumpFactor type parameter domain (15 types).
    //   Note: Mapping alignment with 1.21.1 SmartMovingJumper existing jumpType constants is handled in Phase E.
    public static final int JUMP_TYPE_UP                     = 0;
    public static final int JUMP_TYPE_CHARGE_UP              = 1;
    public static final int JUMP_TYPE_ANGLE                  = 2;
    public static final int JUMP_TYPE_HEAD_UP                = 3;
    public static final int JUMP_TYPE_SLIDE_DOWN             = 4;
    public static final int JUMP_TYPE_CLIMB_UP               = 5;
    public static final int JUMP_TYPE_CLIMB_UP_HANDS_ONLY    = 6;
    public static final int JUMP_TYPE_CLIMB_BACK_UP          = 7;
    public static final int JUMP_TYPE_CLIMB_BACK_UP_HANDS_ONLY = 8;
    public static final int JUMP_TYPE_CLIMB_BACK_HEAD        = 9;
    public static final int JUMP_TYPE_CLIMB_BACK_HEAD_HANDS_ONLY = 10;
    public static final int JUMP_TYPE_WALL_UP                = 11;
    public static final int JUMP_TYPE_WALL_HEAD              = 12;
    public static final int JUMP_TYPE_WALL_UP_SLIDE          = 13;
    public static final int JUMP_TYPE_WALL_HEAD_SLIDE        = 14;

    /**
     * Phase B-1 — getJumpSpeed helper.
     *
     * Original `SmartMovingSelf.java` L2148-L2163 (1:1 translation):
     * <pre>
     * private static int getJumpSpeed(boolean isStanding, boolean isSneaking,
     *                                 boolean isRunning, boolean isSprinting, Float angle) {
     *     isSprinting &amp;= angle == null;
     *     isRunning   &amp;= angle == null;
     *     if (isSprinting)      return Config.Sprinting;
     *     else if (isRunning)   return Config.Running;
     *     else if (isSneaking)  return Config.Sneaking;
     *     else if (isStanding)  return Config.Standing;
     *     else                  return Config.Walking;
     * }
     * </pre>
     *
     * When angle != null (side/back jump) forcibly disable sprint/run gating and only determine by sneaking/standing
     * /walking — original intent: angle jumps are always classified as base speed.
     *
     * Call site: tryJump (Phase D-4) — `int speed = getJumpSpeed(sm.isStanding, sm.isSlow,
     *                                                          isRunning, sm.isFast, angle);`
     */
    public static int getJumpSpeed(boolean isStanding, boolean isSneaking,
                                    boolean isRunning, boolean isSprinting, Float angle) {
        isSprinting &= angle == null;
        isRunning   &= angle == null;

        if (isSprinting)      return SPEED_SPRINTING;
        else if (isRunning)   return SPEED_RUNNING;
        else if (isSneaking)  return SPEED_SNEAKING;
        else if (isStanding)  return SPEED_STANDING;
        else                  return SPEED_WALKING;
    }

    /**
     * Phase B-3 — isJumpingEnabled(speed, type).
     *
     * Original `SmartMovingClientConfig.java` L194-L227 (1:1 translation):
     * <pre>
     * public boolean isJumpingEnabled(int speed, int type) {
     *     if (!enabled)                                  return true;   // SM disabled = vanilla behavior = allow all
     *     if (type == ChargeUp)                          return _jumpCharge.value;
     *     if (type == SlideDown)                         return _slide.value;
     *     if (type == ClimbUp || type == ClimbUpHandsOnly)             return _climbUpJump.value;
     *     if (type == ClimbBackUp || type == ClimbBackUpHandsOnly)     return _climbBackUpJump.value;
     *     if (type == ClimbBackHead || type == ClimbBackHeadHandsOnly) return _climbBackHeadJump.value;
     *     if (type == WallUp)                            return _wallUpJump.value;
     *     if (type == WallHead)                          return _wallHeadJump.value;
     *     if (speed == Sprinting)                        return _sprintJump.value;
     *     else if (speed == Running)                     return _runJump.value;
     *     else if (speed == Walking)                     return _walkJump.value;
     *     else if (speed == Sneaking)                    return _sneakJump.value;
     *     else if (speed == Standing)                    return _standJump.value;
     *     return true;   // type=Up/HeadUp/Angle/WallUpSlide/WallHeadSlide & speed branch not matched
     * }
     * </pre>
     *
     * Original intent: !enabled (SM disabled) → delegate all jumps to vanilla (true). type priority branch →
     * speed branch → fallthrough true.
     *
     * Call site: tryJump (Phase D-4) — `boolean enabled = cfg.isJumpingEnabled(speed, type);`
     */
    public boolean isJumpingEnabled(int speed, int type) {
        if (!enabled)
            return true;

        if (type == JUMP_TYPE_CHARGE_UP)
            return jumpCharge;
        if (type == JUMP_TYPE_SLIDE_DOWN)
            return slide;
        if (type == JUMP_TYPE_CLIMB_UP || type == JUMP_TYPE_CLIMB_UP_HANDS_ONLY)
            return climbUpJump;
        if (type == JUMP_TYPE_CLIMB_BACK_UP || type == JUMP_TYPE_CLIMB_BACK_UP_HANDS_ONLY)
            return climbBackUpJump;
        if (type == JUMP_TYPE_CLIMB_BACK_HEAD || type == JUMP_TYPE_CLIMB_BACK_HEAD_HANDS_ONLY)
            return climbBackHeadJump;

        if (type == JUMP_TYPE_WALL_UP)
            return wallUpJump;
        if (type == JUMP_TYPE_WALL_HEAD)
            return wallHeadJump;

        if (speed == SPEED_SPRINTING)
            return sprintJump;
        else if (speed == SPEED_RUNNING)
            return runJump;
        else if (speed == SPEED_WALKING)
            return walkJump;
        else if (speed == SPEED_SNEAKING)
            return sneakJump;
        else if (speed == SPEED_STANDING)
            return standJump;

        return true;
    }

    /**
     * Phase B-6 — getMaxHorizontalMotion(speed, type, inWater).
     *
     * Original `SmartMovingClientConfig.java` L508-L525 (1:1 translation):
     * <pre>
     * public float getMaxHorizontalMotion(int speed, int type, boolean inWater) {
     *     float maxMotion = 0.117852041920949F;   // ground base (vanilla default horizontal speed limit)
     *     if (!enabled) return speed == Running ? maxMotion * 1.3F : maxMotion;
     *     if (inWater) maxMotion = 0.07839602977037292F;   // water override
     *     if (speed == Sprinting)  maxMotion *= _sprintFactor.value;
     *     else if (speed == Running)   maxMotion *= _runFactor.value;
     *     else if (speed == Sneaking)  maxMotion *= _sneakFactor.value;
     *     return maxMotion;   // no Walking/Standing branch — base as-is
     * }
     * </pre>
     *
     * @param type unused (original also `@SuppressWarnings("unused")` — signature compatibility only).
     *
     * Call site: tryJump (Phase D-8) — `maxHorizontalMotion = (double) cfg.getMaxHorizontalMotion(speed, type, inWater) * SmartMovingMover.getCombinedSpeedFactor(player, cfg);`
     */
    @SuppressWarnings("unused")
    public float getMaxHorizontalMotion(int speed, int type, boolean inWater) {
        float maxMotion = 0.117852041920949F;
        if (!enabled)
            return speed == SPEED_RUNNING ? maxMotion * 1.3F : maxMotion;

        if (inWater)
            maxMotion = 0.07839602977037292F;

        if (speed == SPEED_SPRINTING)
            maxMotion *= sprintFactor;
        else if (speed == SPEED_RUNNING)
            maxMotion *= runFactor;
        else if (speed == SPEED_SNEAKING)
            maxMotion *= sneakFactor;

        return maxMotion;
    }

    /**
     * Phase B-7 — getJumpChargeFactor(jumpCharge).
     *
     * Original `SmartMovingClientConfig.java` L401-L408 (1:1 translation):
     * <pre>
     * public float getJumpChargeFactor(float jumpCharge) {
     *     if (!enabled || !_jumpCharge.value) return 1F;
     *     jumpCharge = Math.min(jumpCharge, _jumpChargeMaximum.value);
     *     return 1F + jumpCharge / _jumpChargeMaximum.value * (_jumpChargeFactor.value - 1F);
     * }
     * </pre>
     *
     * Formula: charge=0 → 1F, charge=max → jumpChargeFactor (1.3F default). Linear interpolation.
     *
     * Call site: tryJump (Phase D-6) — `float jumpChargeFactor = charged ? cfg.getJumpChargeFactor(sm.jumpCharge) : 1F;`
     */
    public float getJumpChargeFactor(float jumpCharge) {
        if (!enabled || !this.jumpCharge)
            return 1F;

        jumpCharge = Math.min(jumpCharge, jumpChargeMaximum);
        return 1F + jumpCharge / jumpChargeMaximum * (jumpChargeFactor - 1F);
    }

    /**
     * Phase B-8 — getHeadJumpFactor(headJumpCharge).
     *
     * Original `SmartMovingClientConfig.java` L410-L416 (1:1 translation):
     * <pre>
     * public float getHeadJumpFactor(float headJumpCharge) {
     *     if (!enabled || !_headJump.value) return 1F;
     *     headJumpCharge = Math.min(headJumpCharge, _headJumpChargeMaximum.value);
     *     return (headJumpCharge - 1) / (_headJumpChargeMaximum.value - 1);
     * }
     * </pre>
     *
     * Formula: charge=1 → 0, charge=max → 1. Head jump normalAngle rotation ratio (Phase D-10).
     *
     * Caution: When charge=0 result is -1 / (max-1) (negative). Call site (Phase D-10) assumes
     * charge >= 1 is already guaranteed before entering head branch (as original).
     *
     * Call site: tryJump head recalculation (Phase D-10) — `double newAngle = cfg.getHeadJumpFactor(sm.headJumpCharge) * normalAngle;`
     */
    public float getHeadJumpFactor(float headJumpCharge) {
        if (!enabled || !this.headJump)
            return 1F;

        headJumpCharge = Math.min(headJumpCharge, headJumpChargeMaximum);
        return (headJumpCharge - 1) / (headJumpChargeMaximum - 1);
    }

    /**
     * Phase B-5 — getJumpVerticalFactor(speed, type).
     *
     * Original `SmartMovingClientConfig.java` L418-L463 (1:1 translation):
     * <pre>
     * public float getJumpVerticalFactor(int speed, int type) {
     *     if (!enabled) return 1F;
     *     float result = _jumpVerticalFactor.value;
     *     if (type == Angle)                                              return result * _angleJumpVerticalFactor.value;   // immediate early return
     *     if (type == ClimbUp || type == ClimbUpHandsOnly)                result *= _climbUpJumpVerticalFactor.value;
     *     if (type == ClimbUpHandsOnly)                                   result *= _climbUpJumpHandsOnlyVerticalFactor.value;
     *     if (type == ClimbBackUp || type == ClimbBackUpHandsOnly)        result *= _climbBackUpJumpVerticalFactor.value;
     *     if (type == ClimbBackUpHandsOnly)                               result *= _climbBackUpJumpHandsOnlyVerticalFactor.value;
     *     if (type == ClimbBackHead || type == ClimbBackHeadHandsOnly)    result *= _climbBackHeadJumpVerticalFactor.value;
     *     if (type == ClimbBackHeadHandsOnly)                             result *= _climbBackHeadJumpHandsOnlyVerticalFactor.value;
     *     if (type == WallUp || type == WallHead)                         result *= _wallUpJumpVerticalFactor.value;        // ※ both use wallUp
     *     if (type == WallHead)                                           result *= _wallHeadJumpVerticalFactor.value;       // ※ WallHead = base × wallUp × wallHead accumulated
     *     if (type == Angle || type == ClimbUp || ... || type == WallHead) return result;   // type match skip speed branch
     *     if (speed == Sprinting)  result *= _sprintJumpVerticalFactor.value;
     *     else if (speed == Running)  result *= _runJumpVerticalFactor.value;
     *     else if (speed == Walking)  result *= _walkJumpVerticalFactor.value;
     *     else if (speed == Sneaking) result *= _sneakJumpVerticalFactor.value;
     *     else if (speed == Standing) result *= _standJumpVerticalFactor.value;
     *     return result;
     * }
     * </pre>
     *
     * Call site: tryJump (Phase D-6) — `float verticalJumpFactor = cfg.getJumpVerticalFactor(speed, type) * jumpFactor;`
     */
    public float getJumpVerticalFactor(int speed, int type) {
        if (!enabled)
            return 1F;

        float result = jumpVerticalFactor;

        if (type == JUMP_TYPE_ANGLE)
            return result * angleJumpVerticalFactor;

        if (type == JUMP_TYPE_CLIMB_UP || type == JUMP_TYPE_CLIMB_UP_HANDS_ONLY)
            result *= climbUpJumpVerticalFactor;
        if (type == JUMP_TYPE_CLIMB_UP_HANDS_ONLY)
            result *= climbUpJumpHandsOnlyVerticalFactor;

        if (type == JUMP_TYPE_CLIMB_BACK_UP || type == JUMP_TYPE_CLIMB_BACK_UP_HANDS_ONLY)
            result *= climbBackUpJumpVerticalFactor;
        if (type == JUMP_TYPE_CLIMB_BACK_UP_HANDS_ONLY)
            result *= climbBackUpJumpHandsOnlyVerticalFactor;

        if (type == JUMP_TYPE_CLIMB_BACK_HEAD || type == JUMP_TYPE_CLIMB_BACK_HEAD_HANDS_ONLY)
            result *= climbBackHeadJumpVerticalFactor;
        if (type == JUMP_TYPE_CLIMB_BACK_HEAD_HANDS_ONLY)
            result *= climbBackHeadJumpHandsOnlyVerticalFactor;

        if (type == JUMP_TYPE_WALL_UP || type == JUMP_TYPE_WALL_HEAD)
            result *= wallUpJumpVerticalFactor;
        if (type == JUMP_TYPE_WALL_HEAD)
            result *= wallHeadJumpVerticalFactor;

        if (type == JUMP_TYPE_ANGLE
            || type == JUMP_TYPE_CLIMB_UP || type == JUMP_TYPE_CLIMB_UP_HANDS_ONLY
            || type == JUMP_TYPE_CLIMB_BACK_UP || type == JUMP_TYPE_CLIMB_BACK_UP_HANDS_ONLY
            || type == JUMP_TYPE_CLIMB_BACK_HEAD || type == JUMP_TYPE_CLIMB_BACK_HEAD_HANDS_ONLY
            || type == JUMP_TYPE_WALL_UP || type == JUMP_TYPE_WALL_HEAD)
            return result;

        if (speed == SPEED_SPRINTING)
            result *= sprintJumpVerticalFactor;
        else if (speed == SPEED_RUNNING)
            result *= runJumpVerticalFactor;
        else if (speed == SPEED_WALKING)
            result *= walkJumpVerticalFactor;
        else if (speed == SPEED_SNEAKING)
            result *= sneakJumpVerticalFactor;
        else if (speed == SPEED_STANDING)
            result *= standJumpVerticalFactor;

        return result;
    }

    /**
     * Phase B-4 — getJumpHorizontalFactor(speed, type).
     *
     * Original `SmartMovingClientConfig.java` L465-L505 (1:1 translation):
     * <pre>
     * public float getJumpHorizontalFactor(int speed, int type) {
     *     if (!enabled) return speed == Running ? 2F : 1F;
     *
     *     float result = _jumpHorizontalFactor.value;
     *
     *     if (type == Angle)                                                 result *= _angleJumpHorizontalFactor.value;
     *
     *     if (type == ClimbBackUp || type == ClimbBackUpHandsOnly)           result *= _climbBackUpJumpHorizontalFactor.value;
     *     if (type == ClimbBackUpHandsOnly)                                  result *= _climbBackUpJumpHandsOnlyHorizontalFactor.value;
     *
     *     if (type == ClimbBackHead || type == ClimbBackHeadHandsOnly)       result *= _climbBackHeadJumpHorizontalFactor.value;
     *     if (type == ClimbBackHeadHandsOnly)                                result *= _climbBackHeadJumpHandsOnlyHorizontalFactor.value;
     *
     *     if (type == WallUp)                                                result *= _wallUpJumpHorizontalFactor.value;
     *     if (type == WallHead)                                              result *= _wallHeadJumpHorizontalFactor.value;
     *
     *     if (type == Angle || type == ClimbUp || type == ClimbUpHandsOnly
     *         || type == ClimbBackUp || type == ClimbBackUpHandsOnly
     *         || type == ClimbBackHead || type == ClimbBackHeadHandsOnly
     *         || type == WallUp || type == WallHead)
     *         return result;   // type match skip speed branch (early return)
     *
     *     if (speed == Sprinting)                                            result *= _sprintJumpHorizontalFactor.value;
     *     else if (speed == Running)                                         result *= _runJumpHorizontalFactor.value;
     *     else if (speed == Walking)                                         result *= _walkJumpHorizontalFactor.value;
     *     else if (speed == Sneaking)                                        result *= _sneakJumpHorizontalFactor.value;
     *     else if (speed == Standing && type != ClimbBackUp && type != ClimbBackUpHandsOnly
     *                                && type != ClimbBackHead && type != ClimbBackHeadHandsOnly)
     *         result *= 0F;   // L501 — Standing jump horizontal 0 (Climb 4 types early return above)
     *     return result;
     * }
     * </pre>
     *
     * Call site: tryJump (Phase D-6) — `float horizontalJumpFactor = cfg.getJumpHorizontalFactor(speed, type) * jumpFactor;`
     */
    public float getJumpHorizontalFactor(int speed, int type) {
        if (!enabled)
            return speed == SPEED_RUNNING ? 2F : 1F;

        float result = jumpHorizontalFactor;

        if (type == JUMP_TYPE_ANGLE)
            result *= angleJumpHorizontalFactor;

        if (type == JUMP_TYPE_CLIMB_BACK_UP || type == JUMP_TYPE_CLIMB_BACK_UP_HANDS_ONLY)
            result *= climbBackUpJumpHorizontalFactor;
        if (type == JUMP_TYPE_CLIMB_BACK_UP_HANDS_ONLY)
            result *= climbBackUpJumpHandsOnlyHorizontalFactor;

        if (type == JUMP_TYPE_CLIMB_BACK_HEAD || type == JUMP_TYPE_CLIMB_BACK_HEAD_HANDS_ONLY)
            result *= climbBackHeadJumpHorizontalFactor;
        if (type == JUMP_TYPE_CLIMB_BACK_HEAD_HANDS_ONLY)
            result *= climbBackHeadJumpHandsOnlyHorizontalFactor;

        if (type == JUMP_TYPE_WALL_UP)
            result *= wallUpJumpHorizontalFactor;
        if (type == JUMP_TYPE_WALL_HEAD)
            result *= wallHeadJumpHorizontalFactor;

        if (type == JUMP_TYPE_ANGLE
            || type == JUMP_TYPE_CLIMB_UP || type == JUMP_TYPE_CLIMB_UP_HANDS_ONLY
            || type == JUMP_TYPE_CLIMB_BACK_UP || type == JUMP_TYPE_CLIMB_BACK_UP_HANDS_ONLY
            || type == JUMP_TYPE_CLIMB_BACK_HEAD || type == JUMP_TYPE_CLIMB_BACK_HEAD_HANDS_ONLY
            || type == JUMP_TYPE_WALL_UP || type == JUMP_TYPE_WALL_HEAD)
            return result;

        if (speed == SPEED_SPRINTING)
            result *= sprintJumpHorizontalFactor;
        else if (speed == SPEED_RUNNING)
            result *= runJumpHorizontalFactor;
        else if (speed == SPEED_WALKING)
            result *= walkJumpHorizontalFactor;
        else if (speed == SPEED_SNEAKING)
            result *= sneakJumpHorizontalFactor;
        else if (speed == SPEED_STANDING
                 && type != JUMP_TYPE_CLIMB_BACK_UP && type != JUMP_TYPE_CLIMB_BACK_UP_HANDS_ONLY
                 && type != JUMP_TYPE_CLIMB_BACK_HEAD && type != JUMP_TYPE_CLIMB_BACK_HEAD_HANDS_ONLY)
            result *= 0F;

        return result;
    }


    // ── Server deployment control flags (config_system.md 2-1, 5-3) ───────────
    /** true → Force this config on all connected clients. */
    public boolean globalConfig = false;
    /** true → Enable per-player personal config management (currently unimplemented — globalConfig takes priority). */
    public boolean serverConfig = false;

    // ── Global Speed ────────────────────────────────────────────
    public float speedFactor = 1F;
    /**
     * Original `_speedUser` L96 = `Creative("move.speed.user").defaults(true, _pre_sm_3_2)`.
     * `Creative(key)` factory = `Modified(key).defaults(Value(false).c(true))`.
     *   → by difficulty: default=false, Easy=false, Medium=false, Hard=false, Creative=true.
     *   → After SM 3.2: true only in Creative, all others (including Easy) false.
     * Version fallback `_pre_sm_3_2`: In SM 3.1 and earlier, all difficulties fixed to true.
     * Easy 1:1 → **false** (session 17 correction, existing `true` was mistranslation).
     * Consumer: `getUserSpeedFactor()` L580 — `!speedUser || speedUserFactor==1F || exponent==0`
     *   early return 1F. If Easy=false then entire user speed adjustment function OFF.
     */
    public boolean speedUser = false;
    public float speedUserFactor = 0.2F;
    public int speedUserExponent = 0;
    /**
     * Original: Property<Map<String,Integer>> _speedUsersExponents = IntegerMap("move.speed.users.exponents").singular();
     * Per-player speed exponent map. username → exponent.
     * File save format: corresponding to original Value.tryParseIntegerMap() — CSV `"user1,5,user2,-2,..."`.
     * When toArray(username) is called, move.speed.user.exponent value is replaced with that player's personal value.
     */
    public Map<String, Integer> playerSpeedExponents = new HashMap<>();

    // ── Movement Modes ──────────────────────────────────────────
    public boolean vanillaStyle = false;
    public float sneakFactor = 0.3F;
    public float crawlFactor = 0.15F;
    public float runFactor = 1.3F;
    public float sprintFactor = 1.5F;

    /**
     * 원본 SmartMovingConfig L180 `_sprintFactorLevitate = PositiveFactor("move.sprint.factor.levitate")
     *   .defaults(Value(1.5F).c(3F))` — 부유/비행 중 sprint 가속 배율. Survival 1.5F / Creative 3F.
     * **포커스 #4 B-4 (세션 3)**: vanilla Creative 비행 + sprint+jump 가속 분기 미이식 정정.
     *   (#4 감사 결과 결함 #2 — 원본 SmartMovingSelf L637-L639)
     */
    public float sprintFactorLevitate = 1.5F;

    /**
     * 원본 SmartMovingConfig L181 `_sprintFactorLevitateVertical = PositiveFactor("move.sprint.factor.levitate.vertical")
     *   .defaults(0.185F)` — 부유/비행 중 sprint 시 수직 상승 속도 배율 (sprintFactorLevitate 와 곱).
     * 매 tick 적용: motionY += sprintFactorLevitate * sprintFactorLevitateVertical.
     */
    public float sprintFactorLevitateVertical = 0.185F;

    // ── Climbing ────────────────────────────────────────────────
    // 원본: Options._baseClimb = "standard" (processBlockCode §0 코드). "standard" → true, 그 외 → false
    public boolean baseClimb = true;
    public boolean freeClimb = true;
    /** 원본: _baseClimb = "simple" — grab 없이 표면 접촉만으로 자동 클라이밍 */
    public boolean simpleClimb = false;
    /** 원본: _baseClimb = "smart" — 인접 블록 substitute 판정으로 자동 클라이밍 */
    public boolean smartClimb = false;
    /**
     * Original SmartMovingConfig L119 `_freeClimbingAutoLaddder` Unmodified — default `true`.
     * "Auto climbing entry without grab key when facing ladder". 3rd branch activation gate in `wouldWantClimb` 4-OR
     * `(isFreeClimbAutoLadderEnabled && isFacedToLadder)`. B-16b (session 69).
     */
    public boolean freeClimbAutoLadder = true;
    /**
     * Original SmartMovingConfig L120 `_freeClimbingAutoVine` Unmodified — default `true`.
     * "Auto climbing entry without grab key when facing solid vine". 4th branch activation gate in `wouldWantClimb` 4-OR
     * `(isFreeClimbAutoVineEnabled && isFacedToSolidVine)`. B-16b (session 69).
     */
    public boolean freeClimbAutoVine = true;
    public float freeClimbingUpSpeedFactor   = 1.0F;  // PositiveFactor default 1F (A-18 confirmed)
    public float freeClimbingDownSpeedFactor = 1.0F;  // PositiveFactor default 1F (A-18 confirmed)
    /**
     * Original SmartMovingConfig L115 `_freeClimbingHorizontalSpeedFactor = PositiveFactor("move.climb.free.horizontal.speed.factor")` — default `1F`.
     * (Properties.java L185-186: PositiveFactor type → default 1F)
     * Factor multiplied to lateral movement (moveStrafing/moveForward) input during climbing in
     * SmartMovingSelf.java L205 `if(isClimbing) speedFactor *= _freeClimbingHorizontalSpeedFactor.value`.
     */
    public float freeClimbingHorizontalSpeedFactor = 1.0F;
    /**
     * Original SmartMovingConfig L121 `_freeOneLadderClimbUpSpeedFactor = PositiveFactor("move.climb.free.ladder.one.up.speed.factor").defaults(1.0153F)`.
     * Factor multiplied for single ladder in MediumUpMotion branch of setOnlyShouldClimbSpeed.
     * Slight additional speed when climbing one ladder rung. Ladder/vine integration task 1-2.
     */
    public float freeOneLadderClimbUpSpeedFactor = 1.0153F;
    /**
     * Original SmartMovingConfig L122 `_freeBothLadderClimbUpSpeedFactor = IncreasingFactor("move.climb.free.ladder.two.up.speed.factor").defaults(1.43F)`.
     * Factor multiplied for dual ladders in MediumUpMotion branch of setOnlyShouldClimbSpeed.
     * Large additional speed when climbing two rungs (both sides). Ladder/vine integration task 1-2.
     */
    public float freeBothLadderClimbUpSpeedFactor = 1.43F;
    /**
     * Original SmartMovingConfig L104 `_freeBaseLadderClimb = Modified().key("move.climb.free.base.ladder")`
     * (Modified type default = false). isTotalFreeLadderClimb() = isFreeBaseClimb && _freeBaseLadderClimb.
     * Used in notTotalFreeClimbing guard of MixinLivingEntityClient.travel:
     *   true → no motion clamp / fallDistance reset / sneak hold on ladder (vanilla free hanging).
     *   false (default) → SM protected hanging (clamp + hold applied).
     */
    public boolean freeBaseLadderClimb = false;
    /**
     * Original SmartMovingConfig L105 `_freeBaseVineClimb = Modified().key("move.climb.free.base.vine").defaults(true, _pre_sm_1_11)`
     * (current default = false, pre 1.11 default true). isTotalFreeVineClimb() = isFreeBaseClimb && _freeBaseVineClimb.
     */
    public boolean freeBaseVineClimb = false;
    /**
     * Original SmartMovingConfig L129 `_freeClimbingOrthogonalDirectionAngle` Positive — default `90F`.
     * "Climbing N/S/E/W grip angle (degrees)". Used by `Orientation.setClimbingAngles` to calculate
     * valid climbing angle range for orthogonal directions (PZ/NZ/ZP/ZN). Default 90F → climbing possible
     * when looking within ±45 degree range for each direction. B-19a0 (session 90).
     */
    /**
     * Original SmartMovingConfig L123 `_freeFenceClimbing = Unmodified("move.climb.free.fence")` — default `true`.
     * (Properties.java L171-172: Unmodified type → default true)
     * "Allow fence climbing" option. Activation gate for fence/wall-based grab paths in
     * `Orientation.hasHalfHold` / `hasBottomHold`. Both fence self-climbing and top grip are controlled by this gate.
     */
    public boolean freeFenceClimbing = true;
    public float freeClimbingOrthogonalDirectionAngle = 90F;
    /**
     * Original SmartMovingConfig L130 `_freeClimbingDiagonalDirectionAngle` Positive — default `80F`.
     * "Climbing NE/NW/SE/SW grip angle (degrees)". Used by `Orientation.setClimbingAngles` to calculate
     * valid climbing angle range for diagonal directions (PP/NN/PN/NP). Default 80F → climbing possible
     * within ±40 degree range for each diagonal direction. B-19a0 (session 90).
     */
    public float freeClimbingDiagonalDirectionAngle = 80F;
    public float ceilingClimbingSpeedFactor = 0.2F;
    // Original: Config._freeClimbFallDamageStartDistance = 2F, _freeClimbFallDamageFactor = 2F
    public float freeClimbFallDamageStartDistance = 2.0F;
    public float freeClimbFallDamageFactor        = 2.0F;
    // Original: Options._climbJumpBackHeadOnGrab — false=headJump when grabNotPressed, true=headJump when grabbed
    public boolean climbJumpBackHead = false;
    public boolean climbExhaustion = false;
    public float climbExhaustionStart = 60F;
    public float climbExhaustionStop = 100F;
    public boolean ceilingClimbExhaustion = false;
    public float ceilingClimbExhaustionStart = 40F;
    public float ceilingClimbExhaustionStop = 100F;

    // ── Swimming / Diving ───────────────────────────────────────
    public boolean swim = true;
    public float swimSpeedFactor = 1F;
    public boolean dive = true;
    public float diveSpeedFactor = 1F;
    /**
     * Original: _swimDownOnSneak = Unmodified("move.swim.down.sneak") → default true
     * true → descend when sneaking while swimming. Used in SmartMovingSelf wouldWantSneak and swimDown calculation.
     */
    public boolean swimDownOnSneak = true;
    /**
     * Original: _diveDownOnSneak = Unmodified("move.dive.down.sneak") → default true
     * true → descend when sneaking while diving. Used in SmartMovingSelf wouldWantSneak and diveDown calculation.
     */
    public boolean diveDownOnSneak = true;
    /**
     * Original: _lavaLikeWater = Creative("move.lava.water") → Survival default false / Creative default true.
     *
     * RED (2026-05-22) User explicit: "Can Survival be the same as Creative?"
     *   → default false → changed to true. When config file user overrides to false, overload(player)
     *     activates Creative only (= maintains partial original spec mapping).
     *   = default behavior = all modes lava swim enabled. Can be disabled via config.
     *
     * Consumers:
     *   - `Swimmer.updateSwimState` entry condition (original L232 `Config.isLavaLikeWaterEnabled() &&
     *     sp.handleLavaMovement()`) — restored in B-7c.
     *   - `ClientState.getLiquidBorder` lava branch (original SmartMovingBase L140/L144) — currently 0F
     *     approximation (§7 B-42c approximation (2)). Lava border calculation restorable after activating this field (B-7b follow-up).
     * B-7b (session 127).
     */
    public boolean lavaLikeWater = true;
    /**
     * Original: `_lavaSwimParticlePeriodFactor = PositiveFactor("move.lava.swim.particle.period.factor")
     *   .defaults(4F)` (SmartMovingConfig L164).
     * Multiplier for splash/bubble particle spawn period when swimming in lava.
     * Formula (original SmartMoving.java L123): `maxSpawnSwimmingParticle = factor × 0.01F`.
     * Particles spawned when `spawnSwimmingParticle += horizontalSpeedSquare` exceeds threshold.
     *   - water (`_swimParticlePeriodFactor`, default `1F`): threshold `0.01`
     *   - lava (this field, default `4F`): threshold `0.04` → 4x less frequently than water.
     * 1.21.1 usage: 0 instances (`spawnSwimmingParticle` system not ported). When porting future SM particle
     *   system, use together with `_swimParticlePeriodFactor`. Focus #2.6 A-5 (session 2).
     */
    public float lavaSwimParticlePeriodFactor = 4F;
    /**
     * Original: _runOnSprintRelease = _sprintKeyReleaseAction.is("run").and(_run)
     *   → _sprintKeyReleaseAction default "run" + _run default true → default true.
     * Applied on sprint release edge (original L2702-L2704): `setSprinting(runOnSprintRelease ||
     * wasRunningWhenSprintStarted)`. B-48b-dep (session 131).
     */
    public boolean runOnSprintRelease = true;
    /**
     * Original: _walkOnSprintRelease = _sprintKeyReleaseAction.is("walk").andNot(_runOnSprintRelease)
     *   → "walk" not default → default false. Two fields are mutually exclusive.
     * Forces `setSprinting(false)` on sprintKeyStopPressed edge (original L2706).
     * B-48b-dep (session 131).
     */
    public boolean walkOnSprintRelease = false;
    /**
     * Original: _levitateSmall = Unmodified("move.levitate.small") → default true.
     * "standard flying small size" option. Used in isLevitating transition edge (original L2516-L2522) when vanilla creative flying enters SM flying disabled state + mustCrawl=false condition
     * (original L2404).
     * B-51 (session 133).
     */
    public boolean levitateSmall = true;

    // ── Jumping ─────────────────────────────────────────────────
    // === Jump base factor (original SmartMovingConfig L228-L231) ===
    // Original L228: _jumpControlFactor = DecreasingFactor("move.jump.control.factor").defaults(1F)
    //   in-air jump control movement factor (>= 0, <= 1). DecreasingFactor default 1F.
    public float jumpControlFactor = 1F;
    // Original L230: _jumpHorizontalFactor = IncreasingFactor("move.jump.horizontal.factor")
    //   base factor for tryJump getJumpHorizontalFactor (>= 1). IncreasingFactor default 1F.
    public float jumpHorizontalFactor = 1F;
    // Original L231: _jumpVerticalFactor = PositiveFactor("move.jump.vertical.factor")
    //   base factor for tryJump getJumpVerticalFactor (>= 0). PositiveFactor default 1F.
    public float jumpVerticalFactor = 1F;

    // === Speed-based Jump enable + factor (original SmartMovingConfig L233-L251) ===
    // Original L234: _standJump = Unmodified("move.jump.stand") → default true
    //   isJumpingEnabled(speed=Standing, type) branch entry.
    public boolean standJump = true;
    // Original L235: _standJumpVerticalFactor = PositiveFactor("move.jump.stand.vertical.factor")
    //   Standing jump vertical factor (>= 0). PositiveFactor default 1F.
    //   Note: Standing has no horizontal — getJumpHorizontalFactor multiplies by 0F when speed=Standing.
    public float standJumpVerticalFactor = 1F;

    // Original L237: _sneakJump = Unmodified("move.jump.sneak") → default true
    public boolean sneakJump = true;
    // Original L238: _sneakJumpHorizontalFactor = IncreasingFactor("move.jump.sneak.horizontal.factor")
    //   Sneaking jump horizontal factor (>= 1). IncreasingFactor default 1F.
    public float sneakJumpHorizontalFactor = 1F;
    // Original L239: _sneakJumpVerticalFactor = PositiveFactor("move.jump.sneak.vertical.factor")
    //   Sneaking jump vertical factor (>= 0). PositiveFactor default 1F.
    public float sneakJumpVerticalFactor = 1F;

    // Original L241: _walkJump = Unmodified("move.jump.walk") → default true
    public boolean walkJump = true;
    // Original L242: _walkJumpHorizontalFactor = IncreasingFactor("move.jump.walk.horizontal.factor")
    //   Walking jump horizontal factor (>= 1). IncreasingFactor default 1F.
    public float walkJumpHorizontalFactor = 1F;
    // Original L243: _walkJumpVerticalFactor = PositiveFactor("move.jump.walk.vertical.factor")
    //   Walking jump vertical factor (>= 0). PositiveFactor default 1F.
    public float walkJumpVerticalFactor = 1F;

    // Original L245: _runJump = Unmodified("move.jump.run") → default true
    public boolean runJump = true;
    // Original L246: _runJumpHorizontalFactor = IncreasingFactor("move.jump.run.horizontal.factor").defaults(2F) ★ override
    //   Running jump horizontal factor (>= 1). Default 2F (same as sprint).
    public float runJumpHorizontalFactor = 2F;
    // Original L247: _runJumpVerticalFactor = PositiveFactor("move.jump.run.vertical.factor")
    //   Running jump vertical factor (>= 0). PositiveFactor default 1F.
    public float runJumpVerticalFactor = 1F;

    // Original L249: _sprintJump = Unmodified("move.jump.sprint") → default true
    public boolean sprintJump = true;
    // Original L250: _sprintJumpHorizontalFactor = IncreasingFactor("move.jump.sprint.horizontal.factor").defaults(2F) ★ override
    //   Sprinting jump horizontal factor (>= 1). Default 2F.
    public float sprintJumpHorizontalFactor = 2F;
    // Original L251: _sprintJumpVerticalFactor = PositiveFactor("move.jump.sprint.vertical.factor")
    //   Sprinting jump vertical factor (>= 0). PositiveFactor default 1F.
    public float sprintJumpVerticalFactor = 1F;

    // Original: _wallUpJump = Unmodified("move.jump.wall") → default true
    public boolean wallUpJump = true;
    // Original: _wallHeadJump = Unmodified("move.jump.wall.head") → default true
    public boolean wallHeadJump = true;
    // Original: _wallUpJumpFallMaximumDistance = Positive(...).defaults(2F)
    public float wallUpJumpFallMaximumDistance = 2F;
    // Original: _wallHeadJumpFallMaximumDistance = Positive(...).defaults(3F)
    public float wallHeadJumpFallMaximumDistance = 3F;
    // Original: _wallUpJumpOrthogonalTolerance = Positive(...).defaults(5F)
    public float wallUpJumpOrthogonalTolerance = 5F;
    // Original: _wallUpJumpVerticalFactor = DecreasingFactor(...).defaults(0.4F)
    // tryJump(WallUp, angle!=null): verticalMotion = -0.078 + 0.498 * wallUpJumpVerticalFactor
    public float wallUpJumpVerticalFactor = 0.4F;
    // Original: _wallHeadJumpVerticalFactor = DecreasingFactor(...).defaults(0.3F)
    // tryJump(WallHead): verticalMotion = -0.078 + 0.498 * (wallUpJumpVerticalFactor + wallHeadJumpVerticalFactor)
    public float wallHeadJumpVerticalFactor = 0.3F;
    // Original: _wallUpJumpHorizontalFactor = DecreasingFactor(...).defaults(0.15F)
    public float wallUpJumpHorizontalFactor = 0.15F;
    // Original: _wallHeadJumpHorizontalFactor = DecreasingFactor(...).defaults(0.15F)
    public float wallHeadJumpHorizontalFactor = 0.15F;
    // === ChargeUp fields (original SmartMovingConfig L254-L257) ===
    // Original L254: _jumpCharge = Unmodified("move.jump.charge") → default true
    public boolean jumpCharge = true;
    // Original L255: _jumpChargeMaximum = Positive("move.jump.charge.maximum").defaults(20F)
    //   ChargeUp maximum count (increment 1 per tick, >= 0).
    public float jumpChargeMaximum = 20F;
    // Original L256: _jumpChargeFactor = IncreasingFactor("move.jump.charge.factor").defaults(1.3F)
    //   ChargeUp full charge jump speed multiplier (>= 1).
    public float jumpChargeFactor = 1.3F;
    // Original L257: _jumpChargeCancelOnSneakRelease = Modified("move.jump.charge.sneak.release.cancel") → default false (Modified)
    //   true=execute charge jump when releasing sneak key, false=cancel charge when releasing sneak key.
    public boolean jumpChargeCancelOnSneakRelease = false;
    public boolean headJump = true;
    public float headJumpControlFactor = 0.2F;
    public float headJumpChargeMaximum = 10F;
    // === Angle (Forward/Side/Back) jumps (original SmartMovingConfig L268-L271 + forward extension) ===
    // Forward input for dedicated dodge key. Not in original config, extension option default true.
    public boolean angleJumpForward = true;
    // Original L268: _angleJumpSide = Unmodified("move.jump.angle.side") → default true
    public boolean angleJumpSide = true;
    // Original L269: _angleJumpBack = Unmodified("move.jump.angle.back") → default true
    public boolean angleJumpBack = true;
    // Original L270: _angleJumpHorizontalFactor = PositiveFactor(...).defaults(0.3F).defaults(0.4F, _sm_1_3)
    //   ★ Override for _sm_1_3 and later = 0.4F (previous 0.3F for sm_1_3 before — 1.7.10 is after sm_1_3 so use 0.4F).
    public float angleJumpHorizontalFactor = 0.4F;
    // Original L271: _angleJumpVerticalFactor = PositiveFactor(...).defaults(0.2F)
    public float angleJumpVerticalFactor = 0.2F;

    // === ClimbUp jumps (original SmartMovingConfig L273-L276) ===
    // Original L274: _climbUpJump = Unmodified("move.jump.climb.up") → default true
    public boolean climbUpJump = true;
    // Original L275: _climbUpJumpVerticalFactor = DecreasingFactor("move.jump.climb.up.vertical.factor")
    //   ClimbUp jump vertical factor (>= 0, <= 1). DecreasingFactor default 1F.
    public float climbUpJumpVerticalFactor = 1F;
    // Original L276: _climbUpJumpHandsOnlyVerticalFactor = DecreasingFactor(...).defaults(0.8F) ★
    //   ClimbUp Hands-only additional vertical factor (>= 0, <= 1). Default 0.8F.
    //   getJumpVerticalFactor(speed, ClimbUpHandsOnly) = ... × (climbUpJumpVerticalFactor × climbUpJumpHandsOnlyVerticalFactor).
    public float climbUpJumpHandsOnlyVerticalFactor = 0.8F;

    // === ClimbBackUp jumps (original SmartMovingConfig L278-L283) ===
    // Original L279: _climbBackUpJump = Unmodified("move.jump.climb.back.up") → default true
    public boolean climbBackUpJump = true;
    // Original L280: _climbBackUpJumpVerticalFactor = DecreasingFactor(...).defaults(0.2F).defaults(1F, _pre_sm_3_1) ★
    //   ClimbBackUp vertical factor (>= 0, <= 1). 0.2F (sm_3_1 and later). Only _pre_sm_3_1 is 1F — 1.7.10 is after sm_3_1 so use 0.2F.
    public float climbBackUpJumpVerticalFactor = 0.2F;
    // Original L281: _climbBackUpJumpHorizontalFactor = DecreasingFactor(...).defaults(0.3F).defaults(1F, _pre_sm_3_1) ★
    //   ClimbBackUp horizontal factor (>= 0, <= 1). 0.3F (sm_3_1 and later). Only _pre_sm_3_1 is 1F.
    public float climbBackUpJumpHorizontalFactor = 0.3F;
    // Original L282: _climbBackUpJumpHandsOnlyVerticalFactor = DecreasingFactor(...).defaults(0.8F) ★
    //   ClimbBackUp Hands-only additional vertical factor (>= 0, <= 1). Default 0.8F.
    public float climbBackUpJumpHandsOnlyVerticalFactor = 0.8F;
    // Original L283: _climbBackUpJumpHandsOnlyHorizontalFactor = DecreasingFactor("move.jump.climb.back.up.hands.only.horizontal.factor")
    //   ClimbBackUp Hands-only additional horizontal factor (>= 0, <= 1). DecreasingFactor default 1F.
    public float climbBackUpJumpHandsOnlyHorizontalFactor = 1F;

    // === ClimbBackHead jumps (original SmartMovingConfig L285-L290) — structure identical to ClimbBackUp ===
    // Original L286: _climbBackHeadJump = Unmodified("move.jump.climb.back.head") → default true
    public boolean climbBackHeadJump = true;
    // Original L287: _climbBackHeadJumpVerticalFactor = DecreasingFactor(...).defaults(0.2F).defaults(1F, _pre_sm_3_1) ★
    //   ClimbBackHead vertical factor (>= 0, <= 1). 0.2F (sm_3_1 and later). Only _pre_sm_3_1 is 1F.
    public float climbBackHeadJumpVerticalFactor = 0.2F;
    // Original L288: _climbBackHeadJumpHorizontalFactor = DecreasingFactor(...).defaults(0.3F).defaults(1F, _pre_sm_3_1) ★
    //   ClimbBackHead horizontal factor (>= 0, <= 1). 0.3F (sm_3_1 and later). Only _pre_sm_3_1 is 1F.
    public float climbBackHeadJumpHorizontalFactor = 0.3F;
    // Original L289: _climbBackHeadJumpHandsOnlyVerticalFactor = DecreasingFactor(...).defaults(0.8F) ★
    //   ClimbBackHead Hands-only additional vertical factor (>= 0, <= 1). Default 0.8F.
    public float climbBackHeadJumpHandsOnlyVerticalFactor = 0.8F;
    // Original L290: _climbBackHeadJumpHandsOnlyHorizontalFactor = DecreasingFactor("move.jump.climb.back.head.hands.only.horizontal.factor")
    //   ClimbBackHead Hands-only additional horizontal factor (>= 0, <= 1). DecreasingFactor default 1F.
    public float climbBackHeadJumpHandsOnlyHorizontalFactor = 1F;

    /**
     * Original: _angleJumpDoubleClickTicks = Positive("move.jump.angle.double.click.ticks").singular().up(3F, 2F)
     * Angle jump double-click detection timer (ticks). Triggers on second click within this time after first click.
     * Default 3F, minimum 2F. Use as (int)Math.ceil(value).
     */
    public float angleJumpDoubleClickTicks = 3F;
    /**
     * Original: _wallJumpDoubleClick = Unmodified("move.jump.wall.double.click").singular()
     * true=wall jump triggered by double-click+hold / false=single-click+hold. Unmodified default true.
     */
    public boolean wallJumpDoubleClick = true;
    /**
     * Original: _wallJumpDoubleClickTicks = Positive("move.jump.wall.double.click.ticks").singular().up(3F, 2F)
     * Wall jump double-click timer (ticks). Default 3F, minimum 2F.
     */
    public float wallJumpDoubleClickTicks = 3F;

    // ── Sliding ─────────────────────────────────────────────────
    // Original: SmartMovingConfig._slideSlipperinessFactor (PositiveFactor, default 1F)
    public float slideSlipperinessFactor = 1.0F;
    // Original: SmartMovingConfig._slideParticlePeriodFactor (PositiveFactor, default 0.5F)
    public float slideParticlePeriodFactor = 0.5F;
    // Original: SmartMovingConfig._slidingSpeedStopFactor (PositiveFactor, default 1F)
    public float slidingSpeedStopFactor = 1.0F;
    // Original: SmartMovingConfig._slideControlDegrees (PositiveFactor, default 1F, deg/tick)
    //   Direction rotation by left/right input during sliding. SmartMovingSelf L730-L744.
    public float slideControlDegrees = 1.0F;

    // ── Flying ──────────────────────────────────────────────────
    // Original: Config._flyingSpeedFactor (PositiveFactor, default 1F)
    public float flyingSpeedFactor = 1.0F;
    // Original: Options._flyControlVertical — enable 3D movement in pitch direction (default true)
    public boolean flyControlVertical = true;
    /**
     * Original: _diveControlVertical = Unmodified("move.dive.control.vertical").singular()
     * Default true. Enable 3D movement in pitch direction while diving.
     * Passed as moveFlying 5-argument treeDimensional parameter in SmartMovingSelf.handleSwimming (isDiving branch).
     */
    public boolean diveControlVertical = true;

    // ── Crawl edge protection ──────────────────────────────────
    // Original: SmartMovingConfig._crawlOverEdge = Unmodified("move.crawl.edge") → default true
    // false → force isSneaking=true at edge during crawl (prevent fall)
    public boolean crawlOverEdge = true;

    // ── Crawl / Sneak toggle ────────────────────────────────────
    // Original: Options._crawlToggle = Modified("move.crawl.toggle") → default false
    // false=hold (keep sneak key pressed), true=toggle (press grab once to toggle crawl on/off)
    public boolean crawlToggle = false;
    /**
     * Original: _sneakToggle = Modified("move.sneak.toggle") → default false
     * false=hold (keep sneak key pressed), true=toggle (press sneak once to toggle sneak on/off).
     * Original isSneakToggleEnabled() = _sneakToggle.value && enabled.
     */
    public boolean sneakToggle = false;

    // ── Name tag display ────────────────────────────────────────
    // Original: _sneakNameTag = Modified("move.sneak.name") → default true
    // true → use 64 distance for name tag visibility while sneaking (vanilla is 32)
    public boolean sneakNameTag = true;
    // Original: _crawlNameTag = Modified("move.crawl.name") → default true
    // true → show name tag while crawling, false → hide
    public boolean crawlNameTag = true;

    // ── HUD display ────────────────────────────────────────────
    /**
     * Original: _displayExhaustionBar = Unmodified("move.gui.exhaustion.bar").singular()
     * Default true (Unmodified). Whether to display exhaustion bar HUD.
     * SmartMovingRender.renderGuiIngame entry condition:
     *   Config.enabled && (displayExhaustionBar || displayJumpChargeBar)
     */
    public boolean displayExhaustionBar = true;
    /**
     * Original: _displayJumpChargeBar = Unmodified("move.gui.jump.charge.bar").singular()
     * Default true. Whether to display jump charge bar HUD.
     */
    public boolean displayJumpChargeBar = true;

    // ── Flying close-to-ground ─────────────────────────────────
    // RED BUG-26 correction (Flying Phase / session 43): default value corrected 1:1.
    //   Original SmartMovingOptions L68 `Modified("move.fly.ground.close")` (defaults unspecified) →
    //   net.smart.properties.Properties.getDefaultValue(Modified) = **false** (Properties.java
    //   L173-L174). Previous 1.21.1 incorrect mapping: true → tryLanding guard `!flyCloseToGround` is false →
    //   auto landing permanently disabled = flying doesn't stop even when idle (BUG-26 direct cause).
    //   Correction: changed to false → original 1:1 behavior (auto land when idle + vanilla auto land on ground touch).
    public boolean flyCloseToGround = false;
    // RED BUG-26 correction (Flying Phase / session 43): original default = false.
    //   _flyWhileOnGround = enable option for "can fly while on ground". If true, sm_flyWhileOnGround restores
    //   abilities.flying after vanilla auto landing (ClientPlayerEntity.tickMovement L1293-L1331)
    //   → maintain flying even when touching ground. If false (original default) vanilla auto landing works as-is.
    public boolean flyWhileOnGround = false;

    // ── Perspective (FOV) ──────────────────────────────────────
    // Original: _perspectiveFadeFactor = PositiveFactor.values(0.5F, 0.1F, 1F)
    public float perspectiveFadeFactor = 0.5F;
    // Original: _perspectiveSpeedFactor = Float.defaults(1F)
    public float perspectiveSpeedFactor = 1F;
    // Original: _perspectiveSpeedFactorMax = PositiveFactor.defaults(0F)
    public float perspectiveSpeedFactorMax = 0F;
    // Original: _perspectiveRunFactor = Float.defaults(1F)
    public float perspectiveRunFactor = 1F;
    // Original: _perspectiveSprintFactor = Float.defaults(1.5F)
    public float perspectiveSprintFactor = 1.5F;

    // ── Exhaustion/Hunger factor stage 1 (original getFactor L563-L575 movement speed multiplier) ──
    // Property<Float> factor fields from original SmartMovingConfig.java ported to 1.21.1 as simple fields.
    // Easy 1:1 principle: base 2 with difficulty chain `.e(x).h(y)` use Easy value as default. Rest use
    // original top-level defaults(Value(x)) value. All original `Value(default).e(...).h(...)` values per key
    // recorded in javadoc. WARNING: stage 1 has `Exhaution` typo — kept as-is from original field name/key (1:1 compatibility).
    // up() dynamic lower bound dependency absent in Property system, so simple default approximation — original
    // lower bound checks do not function when editing config.

    /**
     * Original `_baseExhautionLossFactor` L399 = `PositiveFactor("move.exhaustion.loss.factor")
     *   .defaults(Value(1F).e(1.2F).h(0.8F)).defaults(1F, _pre_sm_1_5)`.
     * By difficulty: default=1F, Easy=**1.2F**, Hard=0.8F. Version fallback `_pre_sm_1_5` → fixed 1F.
     * Easy 1:1 → adopt 1.2F.
     */
    public float baseExhautionLossFactor = 1.2F;

    /**
     * Original `_baseHungerGainFactor` L423 = `PositiveFactor("move.hunger.gain.factor")
     *   .defaults(Value(1F).e(0.8F).h(1.2F)).defaults(1F, _pre_sm_1_5)`.
     * By difficulty: default=1F, Easy=**0.8F**, Hard=1.2F. Version fallback _pre_sm_1_5 → 1F.
     * Easy 1:1 → adopt 0.8F.
     */
    public float baseHungerGainFactor = 0.8F;

    /**
     * Original `_fallExhautionLossFactor` L406 = `PositiveFactor("move.exhaustion.fall.loss.factor")
     *   .up(2.5F, _standingExhautionLossFactor)`.
     * default=2.5F. No difficulty chain. up() lower bound = >= `_standingExhautionLossFactor` value — approximation.
     * WARNING: No corresponding field for airBorne + hunger — hardcoded to `0F` in `getFactor` L565.
     */
    public float fallExhautionLossFactor = 2.5F;

    /**
     * Original `_sprintingHungerGainFactor` L425 = `PositiveFactor("move.hunger.sprint.gain.factor")
     *   .key("move.hunger.gain.sprint.factor", _pre_sm_1_3)`.
     * default=PF default (1F). No difficulty chain. _pre_sm_1_3 key rename (past save compatibility).
     */
    public float sprintingHungerGainFactor = 1F;

    /**
     * Original `_sprintingExhautionLossFactor` L401 = `PositiveFactor("move.exhaustion.sprint.loss.factor")
     *   .defaults(0F)`.
     * default=0F. No difficulty chain.
     */
    public float sprintingExhautionLossFactor = 0F;

    /**
     * Original `_runningHungerGainFactor` L426 = `PositiveFactor("move.hunger.run.gain.factor")
     *   .key("move.hunger.gain.run.factor", _pre_sm_1_3).defaults(10F)`.
     * default=10F. No difficulty chain. WARNING: field name `running`, key is `run`.
     */
    public float runningHungerGainFactor = 10F;

    /**
     * Original `_runningExhautionLossFactor` L402 = `PositiveFactor("move.exhaustion.run.loss.factor")
     *   .up(0.5F, _sprintingExhautionLossFactor)`.
     * default=0.5F. No difficulty chain. up() lower bound = >= `_sprintingExhautionLossFactor` value — approximation.
     */
    public float runningExhautionLossFactor = 0.5F;

    /**
     * Original `_sneakingHungerGainFactor` L428 = `PositiveFactor("move.hunger.sneak.gain.factor")
     *   .key("move.hunger.gain.sneak.factor", _pre_sm_1_3)`.
     * default=PF default (1F). No difficulty chain.
     */
    public float sneakingHungerGainFactor = 1F;

    /**
     * Original `_sneakingExhautionLossFactor` L404 = `PositiveFactor("move.exhaustion.sneak.loss.factor")
     *   .up(1.5F, _walkingExhautionLossFactor).defaults(1F, _sm_1_1)`.
     * default=1.5F. No difficulty chain. up() lower bound = >= `_walkingExhautionLossFactor` value — approximation.
     * Version fallback `_sm_1_1` → 1F.
     */
    public float sneakingExhautionLossFactor = 1.5F;

    /**
     * Original `_standingHungerGainFactor` L429 = `PositiveFactor("move.hunger.stand.gain.factor")
     *   .defaults(0F)`.
     * default=0F. No difficulty chain.
     */
    public float standingHungerGainFactor = 0F;

    /**
     * Original `_standingExhautionLossFactor` L405 = `PositiveFactor("move.exhaustion.stand.loss.factor")
     *   .up(2F, _sneakingExhautionLossFactor.maximum(1F))`.
     * default=2F. No difficulty chain. up() lower bound = >= `_sneakingExhautionLossFactor` value clamped to max 1F
     * — approximation.
     */
    public float standingExhautionLossFactor = 2F;

    /**
     * Original `_walkingHungerGainFactor` L427 = `PositiveFactor("move.hunger.walk.gain.factor")
     *   .key("move.hunger.gain.walk.factor", _pre_sm_1_3)`.
     * default=PF default (1F). No difficulty chain.
     */
    public float walkingHungerGainFactor = 1F;

    /**
     * Original `_walkingExhautionLossFactor` L403 = `PositiveFactor("move.exhaustion.walk.loss.factor")
     *   .up(1F, _runningExhautionLossFactor)`.
     * default=1F. No difficulty chain. up() lower bound = >= `_runningExhautionLossFactor` value — approximation.
     */
    public float walkingExhautionLossFactor = 1F;

    // ── Exhaustion/Hunger factor stage 2 (original getFactor L577-L592 action state multiplier) ──
    // WARNING: stage 2 uses `Exhaustion` correct spelling (mixed with stage 1 `Exhaution` typo). Kept as original.
    // WARNING: `ceilClimbing` abbreviated field name (not `ceilingClimbing`). Kept as original.
    // No difficulty chain — all difficulties identical (default = Easy).

    /**
     * Original `_climbingHungerGainFactor` L431 = `PositiveFactor("move.hunger.climb.gain.factor")
     *   .key("move.hunger.gain.climb.factor", _pre_sm_1_3)`. default=PF default (1F).
     */
    public float climbingHungerGainFactor = 1F;

    /**
     * Original `_climbingExhaustionLossFactor` L409 = `PositiveFactor("move.exhaustion.climb.loss.factor")`.
     * default=PF default (1F).
     */
    public float climbingExhaustionLossFactor = 1F;

    /**
     * Original `_crawlingHungerGainFactor` L432 = `PositiveFactor("move.hunger.crawl.gain.factor")
     *   .key("move.hunger.gain.crawl.factor", _pre_sm_1_3)`. default=PF default (1F).
     */
    public float crawlingHungerGainFactor = 1F;

    /**
     * Original `_crawlingExhaustionLossFactor` L410 = `PositiveFactor("move.exhaustion.crawl.loss.factor")`.
     * default=PF default (1F).
     */
    public float crawlingExhaustionLossFactor = 1F;

    /**
     * Original `_ceilClimbingHungerGainFactor` L433 = `PositiveFactor("move.hunger.climb.gain.ceiling.factor")
     *   .key("move.hunger.gain.climb.ceiling.factor", _pre_sm_1_3)`. default=PF default (1F).
     * WARNING: field name `ceilClimbing` abbreviated. Key name order `climb.gain.ceiling` (gain before ceiling).
     */
    public float ceilClimbingHungerGainFactor = 1F;

    /**
     * Original `_ceilClimbingExhaustionLossFactor` L408 = `PositiveFactor("move.exhaustion.climb.ceiling.loss.factor")`.
     * default=PF default (1F). Key name order `climb.ceiling.loss` (ceiling before loss).
     */
    public float ceilClimbingExhaustionLossFactor = 1F;

    /**
     * Original `_swimmingHungerGainFactor` L434 = `PositiveFactor("move.hunger.swim.gain.factor")
     *   .key("move.hunger.gain.swim.factor", _pre_sm_1_3).defaults(1.5F)`. default=**1.5F**.
     */
    public float swimmingHungerGainFactor = 1.5F;

    /**
     * Original `_swimmingExhaustionLossFactor` L412 = `PositiveFactor("move.exhaustion.swim.loss.factor")`.
     * default=PF default (1F).
     */
    public float swimmingExhaustionLossFactor = 1F;

    /**
     * Original `_divingHungerGainFactor` L435 = `PositiveFactor("move.hunger.dive.gain.factor")
     *   .key("move.hunger.gain.dive.factor", _pre_sm_1_3).defaults(1.5F)`. default=**1.5F**.
     */
    public float divingHungerGainFactor = 1.5F;

    /**
     * Original `_divingExhaustionLossFactor` L413 = `PositiveFactor("move.exhaustion.dive.loss.factor")`.
     * default=PF default (1F).
     */
    public float divingExhaustionLossFactor = 1F;

    /**
     * Original `_dippingHungerGainFactor` L436 = `PositiveFactor("move.hunger.dip.gain.factor")
     *   .key("move.hunger.gain.dip.factor", _pre_sm_1_3).defaults(1.5F)`. default=**1.5F**.
     * WARNING: key uses `dip` abbreviation (not dipping).
     */
    public float dippingHungerGainFactor = 1.5F;

    /**
     * Original `_dippingExhaustionLossFactor` L411 = `PositiveFactor("move.exhaustion.dip.loss.factor")`.
     * default=PF default (1F). Key uses `dip` abbreviation.
     */
    public float dippingExhaustionLossFactor = 1F;

    /**
     * Original `_normalHungerGainFactor` L437 = `PositiveFactor("move.hunger.normal.gain.factor")
     *   .key("move.hunger.gain.ground.factor", _pre_sm_1_3)`. default=PF default (1F).
     * WARNING: getFactor L589 onGround + L591 else both referenced (intentional duplicate).
     */
    public float normalHungerGainFactor = 1F;

    /**
     * Original `_normalExhaustionLossFactor` L414 = `PositiveFactor("move.exhaustion.normal.loss.factor")`.
     * default=PF default (1F). getFactor L590/L592 onGround + else both referenced (intentional duplicate).
     */
    public float normalExhaustionLossFactor = 1F;

    // ── handleExhaustion misc fields (original SmartMovingSelf L849-L916 hunger formula) ─────

    /**
     * Original `_alwaysHungerGain` L439 = `Positive("move.hunger.always.gain")
     *   .defaults(Value(0F).h(0.005F)).defaults(0F, _pre_sm_1_5)`.
     * By difficulty: default=0F, Easy=0F, Hard=0.005F. Version fallback `_pre_sm_1_5` → fixed 0F.
     * Easy 1:1 → 0F (default = Easy).
     * Consumer: `SmartMovingSelf.handleExhaustion` L863 —
     *   `hungerIncrease += _alwaysHungerGain.value + relevantMovementFactor * 0.0001F * hungerGainFactor`.
     * Every tick passive hunger increase (Easy = 0 → no passive increase, Hard = 0.005 accumulation).
     */
    public float alwaysHungerGain = 0F;

    /**
     * Original `_exhaustionLossHungerFactor` L417 = `PositiveFactor("move.exhaustion.hunger.factor")
     *   .defaults(Value(0.05F).e(0.02F).h(0.08F)).defaults(0.05F, _pre_sm_1_5)`.
     * By difficulty: default=0.05F, Easy=**0.02F**, Hard=0.08F. Version fallback `_pre_sm_1_5` → fixed 0.05F.
     * Easy 1:1 → adopt 0.02F.
     * Consumer: `SmartMovingSelf.handleExhaustion` L893 —
     *   `hungerIncrease += _exhaustionLossHungerFactor.value * exhaustionLoss`.
     * Exhaustion loss × this factor = additional hunger increase.
     */
    public float exhaustionLossHungerFactor = 0.02F;

    // ── Misc ────────────────────────────────────────────────────
    public boolean fly = true;
    public boolean slide = true;
    public boolean crawl = true;
    public boolean sneak = true;
    public boolean run = true;
    public boolean sprint = true;
    public boolean ceilingClimbing = true;

    /**
     * Original `SmartMovingConfig.java` L313:
     *   `public final Property<Boolean> _sprintEnableStanding = Unmodified("move.sprint.enable.ground");`
     * default false — `Unmodified` sets `value=false` in default constructor.
     * Usage: isFast formula `isGroundSprinting && (!standing || _sprintEnableStanding)` (original L2689).
     * When true, Ground Sprint recognized even while standing (allow stationary sprint).
     * B-1a (session 40).
     *
     * RED (2026-04-28) default false → changed to true. User intent "SM mode faster than vanilla".
     *   Original default false: onGround normal sprint = isFast=false → nonSlow=1.0 → vanilla identical.
     *   With true: isFast=true → nonSlow=sprintFactor (1.5) → vanilla * 1.154x.
     *   Changed default to apply original formula (isFast = 1.5x) as-is.
     */
    public boolean sprintEnableStanding = true;

    /**
     * Original `SmartMovingConfig.java` L348:
     *   `public final Property<Float> _fallingDistanceMinimum = Positive("move.fall.distance.minimum").defaults(3F);`
     * default 3F. Usage:
     *   - canCrawl (original L2439) `sp.fallDistance < _fallingDistanceMinimum` — block crawl entry
     *   - isSliding fallDistance branch (original L2569) — release slide + convert to crawl
     * B-32 (session 44).
     */
    public float fallingDistanceMinimum = 3F;

    /**
     * Original `SmartMovingConfig.java` L223:
     *   `public final Property<Float> _fallAnimationDistanceMinimum = Positive("move.fall.animation.distance.minimum").min(_fallingDistanceMinimum).defaults(3F, _pre_sm_1_6);`
     * default 3F. Separate from `_fallingDistanceMinimum` — animation trigger only.
     * Usage: doFallingAnimation (original SmartMovingSelf.java L3281)
     *   `!sp.onGround && sp.fallDistance > _fallAnimationDistanceMinimum.value`.
     * 1.21.1 mapping: MixinPlayerEntityModelClient.sm_setAngles isFalling entry condition.
     */
    public float fallAnimationDistanceMinimum = 3F;

    // ── B-1c1 (session 50) — original SmartMovingConfig.java exhaustion/sprint related fields ─────────

    /**
     * Original L298 `_runExhaustionStart = Positive("move.exhaustion.run.start").defaults(75F)`.
     * Usage: exhaustionAllowsRunning (original L2621-L2622) determination lower bound.
     */
    public float runExhaustionStart = 75F;

    /**
     * Original L299 `_runExhaustionStop = Positive("move.exhaustion.run.stop").up(100F, _runExhaustionStart)`.
     * `up(100F, ...)` — default 100F, minimum runExhaustionStart. Usage: maxExhaustionForAction upper bound.
     */
    public float runExhaustionStop = 100F;

    /**
     * Original L314 `_sprintExhaustionStart = Positive(...).defaults(50F)`.
     * Usage: exhaustionAllowsSprinting (original L2640) determination lower bound.
     */
    public float sprintExhaustionStart = 50F;

    /**
     * Original L315 `_sprintExhaustionStop = Positive(...).up(100F, _sprintExhaustionStart)`.
     * Default 100F. Usage: exhaustionAllowsSprinting (original L2639) + maxExhaustionForAction upper bound.
     */
    public float sprintExhaustionStop = 100F;

    /**
     * Original L589 `_sprintDuringItemUsage = Modified("move.usage.sprint")`.
     * Modified default false. Usage: canAnySprint (original L2673) — if true, allow sprint even during item use.
     */
    public boolean sprintDuringItemUsage = false;

    /**
     * Original `SmartMovingClientConfig.java` L90 `isRunExhaustion = Modified(...)`.
     * 1.21.1 default false (SM default disabled).
     */
    public boolean runExhaustion = false;

    /**
     * Original `SmartMovingClientConfig.java` L94 `_sprintExhaustion = Modified(...)`.
     * 1.21.1 default false.
     */
    public boolean sprintExhaustion = false;

    /**
     * Original `SmartMovingClientConfig.java` L92 `isRunExhaustionEnabled() = _runExhaustion && enabled`.
     * AND pattern. B-1c1 (session 50).
     */
    public boolean isRunExhaustionEnabled() {
        return runExhaustion && enabled;
    }

    /**
     * Original `SmartMovingClientConfig.java` L93 `isClimbExhaustionEnabled() = _climbExhaustion && enabled`.
     * AND pattern. B-1c1 (session 50).
     */
    public boolean isClimbExhaustionEnabled() {
        return climbExhaustion && enabled;
    }

    /**
     * Original `SmartMovingClientConfig.java` L96 `isSprintExhaustionEnabled() = _sprintExhaustion && enabled`.
     * AND pattern. B-1c1 (session 50).
     */
    public boolean isSprintExhaustionEnabled() {
        return sprintExhaustion && enabled;
    }

    // ── B-24 (session 53) — isHeadJumping release edge handleCrash Config fields ───────────

    /**
     * Original `SmartMovingConfig.java` L264 (focus #2.5 A-5d porting complete):
     *   `_headFallDamageStartDistance = Positive("move.fall.head.damage.start.distance").values(2F, 1F, 3F);`
     * default 2F (values: default=2F, min=1F, max=3F). Usage: `handleCrash(startDistance, factor)`
     * (original L2538) — head jump release edge + free climbing fall damage start distance.
     */
    public float headFallDamageStartDistance = 2F;

    /**
     * Original `SmartMovingConfig.java` L265 (focus #2.5 A-5e porting complete):
     *   `_headFallDamageFactor = IncreasingFactor("move.fall.head.damage.factor").defaults(2F);` ★ override
     * default 2F (IncreasingFactor default 1F → defaults(2F) override). Usage: handleCrash fall damage multiplier.
     */
    public float headFallDamageFactor = 2F;

    // ── Activation flag ──────────────────────────────────────────
    /**
     * SM enabled state. Original SmartMovingProperties.enabled (L33).
     * Derived as `toggler != -1` on `update()` call — direct writing prohibited, use toggle()/setCurrentKey instead.
     */
    public boolean enabled = true;

    // ── B 단계 Phase 1 헬퍼 (세션 39) — 원본 SmartMovingClientConfig.java ────────
    // 원본 `enabled` 패턴 (리서치 L428):
    //   SM 비활성 시 vanilla 동작 허용 → `|| !enabled` (isSneakingEnabled 등)
    //   SM 전용 기능 → `&& enabled` (isSwim/Diving/Sprinting 등)

    /**
     * 원본 SmartMovingClientConfig L69 `isSneakingEnabled() = _sneak.value || !enabled`.
     * **OR 패턴** — SM 비활성 시에도 vanilla sneak 허용.
     * 사용처: wantSneak (원본 L2588-L2590) → isSlow 공식 (원본 L2718).
     * B-2 의존.
     */
    public boolean isSneakingEnabled() {
        return sneak || !enabled;
    }

    /**
     * 원본 SmartMovingClientConfig L95 `isSprintingEnabled() = _sprint.value && enabled`.
     * **AND 패턴** — SM 비활성 시 SM sprint 비허용.
     * 사용처: wantSprint 6조건 OR (원본 L2595-L2615) → wouldIsSneaking (L2712).
     * B-3a 의존.
     */
    public boolean isSprintingEnabled() {
        return sprint && enabled;
    }

    /**
     * 원본 SmartMovingClientConfig L87 `isSwimmingEnabled() = _swim.value && enabled`.
     * 사용처: handleSwimming 진입 게이트 (원본 L239/L438) — useStandard 진입 판정.
     * B-7 / B-8 의존.
     */
    public boolean isSwimmingEnabled() {
        return swim && enabled;
    }

    /**
     * 원본 SmartMovingClientConfig L88 `isDivingEnabled() = _dive.value && enabled`.
     * 사용처: handleSwimming 진입 게이트 + dive 분류 활성화 판정.
     * B-7 / B-8 의존.
     */
    public boolean isDivingEnabled() {
        return dive && enabled;
    }

    /**
     * 원본 SmartMovingClientConfig L87-L90 `isLavaLikeWaterEnabled() = _lavaLikeWater.value && enabled`.
     * 사용처: Swimmer.updateSwimState 진입 조건 (원본 L232 `Config.isLavaLikeWaterEnabled()
     * && sp.handleLavaMovement()`). B-7c 에서 복원. Lava 를 물처럼 수영/잠수 가능한지.
     * B-7b (세션 127).
     */
    public boolean isLavaLikeWaterEnabled() {
        return lavaLikeWater && enabled;
    }

    /**
     * 🔴 (2026-05-22) 사용자 보고 BUG fix: 용암 진입 시 SM 수영 기능/애니메이션 적용 안 됨.
     *   원본 1.7.10 `_lavaLikeWater = Creative(...)` — Creative default true / Survival default false.
     *
     * 사용자 명시 ("Survival 도 Creative 와 같게 해줄 수 있나?"):
     *   SM enabled 시 mode/config 무관 항상 lava swim 활성. `lavaLikeWater` config option 무시.
     *   원본 spec 부분 손상이지만 사용자 의도 명확.
     *
     * 인자 없는 method `isLavaLikeWaterEnabled()` 는 호환 유지 (외부 호출 가능성).
     */
    public boolean isLavaLikeWaterEnabled(net.minecraft.world.entity.player.Player player) {
        return enabled;
    }

    /**
     * 원본 SmartMovingClientConfig L97-L100 `isLevitateSmallEnabled() = _levitateSmall.value && enabled`.
     * 사용처: mustCrawl=false 조건 확장 (원본 L2404) + isLevitating 전환 엣지 블록 (원본 L2516-L2522).
     * B-51 (세션 133).
     */
    public boolean isLevitateSmallEnabled() {
        return levitateSmall && enabled;
    }

    /**
     * 원본 SmartMovingClientConfig L92-L95 `isFlyingEnabled() = _fly.value && enabled`.
     * 사용처: isFlying 공식 (원본 L2510) + isLevitating 전환 엣지 (원본 L2516) +
     * mustCrawl=false 조건 (원본 L2404). B-51 (세션 133).
     */
    public boolean isFlyingEnabled() {
        return fly && enabled;
    }

    /**
     * 원본 SmartMovingClientConfig L57-L60 `isFreeClimbAutoLaddderEnabled() =
     *   _freeClimbingAutoLaddder.value && enabled`.
     * 사용처: `wouldWantClimb` 4-OR 의 3번째 분기 (원본 L2471) 자동 사다리 진입 게이트.
     * B-16b (세션 69).
     */
    public boolean isFreeClimbAutoLadderEnabled() {
        return freeClimbAutoLadder && enabled;
    }

    /**
     * 원본 SmartMovingClientConfig L62-L65 `isFreeClimbAutoVineEnabled() =
     *   _freeClimbingAutoVine.value && enabled`.
     * 사용처: `wouldWantClimb` 4-OR 의 4번째 분기 (원본 L2472) 자동 솔리드 덩굴 진입 게이트.
     * B-16b (세션 69).
     */
    public boolean isFreeClimbAutoVineEnabled() {
        return freeClimbAutoVine && enabled;
    }

    /**
     * 원본 SmartMovingConfig `_isFreeBaseClimb = _baseClimb.is("free").and(_freeClimb)` Property
     * (`baseClimb` String 이 "free" AND `freeClimb` true).
     *
     * **§7 근사** (B-19a2c-approx-config): 1.21.1 에서는 `baseClimb` 를 String 대신
     * 4 boolean (freeClimb/simpleClimb/smartClimb/standardClimb) 으로 이식. `freeClimb`
     * 단독으로 "Free Base Climb 모드" 판정 가능 — String "free" 일 때 `freeClimb=true`
     * 설정이라 동치. B-19a2c (세션 104).
     */
    public boolean isFreeBaseClimb() {
        // 근사 이식 — 원본과 차이: `_baseClimb.is("free")` 체크 생략 (boolean 4 필드 이식 구조)
        return freeClimb;
    }

    /**
     * 원본 SmartMovingClientConfig L47-50 `isTotalFreeLadderClimb()`:
     *   return isFreeBaseClimb() && _freeBaseLadderClimb.value;
     * MixinLivingEntityClient.travel notTotalFreeClimbing 가드에서 사용.
     */
    public boolean isTotalFreeLadderClimb() {
        return isFreeBaseClimb() && freeBaseLadderClimb;
    }

    /**
     * 원본 SmartMovingClientConfig L52-55 `isTotalFreeVineClimb()`:
     *   return isFreeBaseClimb() && _freeBaseVineClimb.value;
     */
    public boolean isTotalFreeVineClimb() {
        return isFreeBaseClimb() && freeBaseVineClimb;
    }

    /**
     * 원본 `SmartMovingOptions.java` L449-L455 `isSneakToggleEnabled()`:
     *   return _sneakToggle.value && enabled;
     * **AND 패턴** — SM 비활성 시 토글 모드 비허용.
     * 사용처: sneakContinueInput (원본 L2576) / R-09 블록 진입 (원본 L2968) / wantSneak_
     * 계산 (원본 L2981 부근).
     * B-45a (세션 45).
     */
    public boolean isSneakToggleEnabled() {
        return sneakToggle && enabled;
    }

    /**
     * 원본 `SmartMovingOptions.java` L462-L469 `isCrawlToggleEnabled()`:
     *   return _crawlToggle.value && enabled;
     * **AND 패턴**. 사용처: inputContinueCrawl (원본 L2407) / R-09 블록 (원본 L2969) /
     * toCrawling() (원본 L3050).
     * B-45a (세션 45).
     */
    public boolean isCrawlToggleEnabled() {
        return crawlToggle && enabled;
    }

    // ── Config key 토글 시스템 (원본 SmartMovingProperties L26-L31) ─────────────────
    // H-19 (세션 23): CONFIG_KEY_ENABLED/DISABLED 상수 삭제. getCurrentKey/getKey/getNextKey/
    //   hasKey/setCurrentKey 메서드 5개도 삭제 (외부 호출처 0건 확인). DEFAULT_KEYS 만 유지
    //   — setKeys(null) 시 fallback 으로 사용됨.
    /**
     * 원본 SmartMovingProperties._defaultKeys = new String[1] = {null}.
     * 단일 null 요소 — `keys[0] == null` 이면 단순 on/off 토글 모드.
     */
    private static final String[] DEFAULT_KEYS = new String[] { null };

    /**
     * 원본 SmartMovingProperties.keys (L31) 대응.
     * 현재 유효한 config key 배열. 초기값 {null} (단순 on/off) — `setKeys(...)` 호출로
     * 게임타입별 배열({"e","m","h"} / {"c"} 등) 로 교체. `initializeForGameIfNeccessary()`
     * 이식(F 섹션)에서 gameType 판정 후 setKeys 호출.
     */
    public String[] configKeys = DEFAULT_KEYS;

    /**
     * 원본 SmartMovingProperties.toggler (L30) 대응. 초기값 -2 (load 전 센티넬).
     * 상태표:
     *   -2 : 초기화 전 (load() 미호출)
     *   -1 : disabled (enabled = false)
     *   0..configKeys.length-1 : enabled, 현재 key 인덱스
     * 파생: `enabled = (toggler != -1)` — `update()` 에서 동기화. 직접 쓰기 금지, toggle()/
     *   setCurrentKey() 경유.
     */
    public int toggler = -2;

    // H-18 (세션 23): configKeyName Map 삭제. 2상태 토글(configKeys={null}) 에서 currentKey
    // 는 항상 null 이므로 "Easy"/"Medium"/"Hard" 라벨 표시 경로 전부 제거됨. Medium/Hard
    // 프리셋 복원 시(`focus_09_difficulty_presets.md` 후속) 재도입.
    //
    // H-20 (세션 23): gameType 시스템 전체 삭제. 2상태 토글에선 gameType 감지/전환이
    // 불필요. 원본 SmartMovingOptions.initializeForGameIfNeccessary / resetForNewGame /
    // gameType 캐시 + GAME_TYPE_UNKNOWN/SURVIVAL/CREATIVE/ADVENTURE 4상수 + 게임타입별
    // 6필드 (survival/creative/adventure × configKeys+defaultConfigKey) 전부 제거.
    // 세이브 파일 호환: 기존 키(move.config.{survival,creative,adventure}.keys{,.default})
    // 는 단순히 무시됨 (readFrom 호출 삭제). Medium/Hard 프리셋 복원 시 재도입.


    // ── Singleton / Config 전환 ────────────────────────────────
    /** 클라이언트 파일 기반 설정 (Options). 불변 싱글톤. */
    public static final SmartMovingConfig INSTANCE = new SmartMovingConfig();

    /**
     * 서버 수신 설정 인스턴스. loadFromArray()로 갱신.
     * 연결 해제 시 resetServerConfig() 호출로 새 인스턴스 교체 — 원본 SmartMovingServerConfig.reset() 대응.
     * volatile — 클라이언트 렌더/틱 스레드 간 가시성 보장.
     */
    public static volatile SmartMovingConfig SERVER_CONFIG = new SmartMovingConfig();

    /**
     * 현재 활성 설정 참조 (원본: SmartMovingContext.Config).
     * 초기값: INSTANCE (클라이언트 자체 설정).
     * 서버 설정 수신 시: SERVER_CONFIG로 전환.
     * 서버 설정 해제 시: INSTANCE로 복원.
     * volatile — 클라이언트 렌더/틱 스레드 간 가시성 보장.
     */
    public static volatile SmartMovingConfig Config = INSTANCE;

    private static final String FILE_NAME = "smart_moving_options.properties";

    private SmartMovingConfig() {}

    public static void load() {
        Path configFile = FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
        File file = configFile.toFile();
        if (!file.exists()) {
            save();
            return;
        }
        Properties props = new Properties();
        try (FileInputStream in = new FileInputStream(file)) {
            props.load(in);
        } catch (IOException e) {
            return;
        }
        INSTANCE.readFrom(props);
        // 🔴 BUG-26 자동 마이그레이션 (Flying Phase / 세션 44): 이전 잘못된 default (true) 가
        //   사용자 환경에 저장된 경우 강제 false 로 마이그레이션 + save.
        //   원본 net.smart.properties.Properties.getDefaultValue(Modified) = false 가 정답인데
        //   1.21.1 매핑이 true 로 잘못 저장되어 있으면 vanilla 자동 착지 무효화 (BUG-26 직접 원인).
        //   마이그레이션 마커: move.config.migration.flying = "session_44_done".
        String marker = props.getProperty("move.config.migration.flying", "");
        if (!"session_44_done".equals(marker)) {
            INSTANCE.flyCloseToGround = false;
            INSTANCE.flyWhileOnGround = false;
            save();
            try (FileOutputStream out = new FileOutputStream(configFile.toFile(), true)) {
                Properties marker2 = new Properties();
                marker2.setProperty("move.config.migration.flying", "session_44_done");
                marker2.store(out, null);
            } catch (IOException ignored) {}
        }
        // 🔴 BUG-27 자동 마이그레이션 (Flying Phase / 세션 48): _flyControlVertical / _diveControlVertical
        //   default = true (원본 Unmodified default = true, Properties.java L171-L172).
        //   사용자 환경 disk 에 이전 false 저장됐을 가능성 → pitch 방향 W 진행 효과 무효 (BUG-27 직접 원인).
        //   마이그레이션 마커: move.config.migration.flying.vertical = "session_48_done".
        // 세션 49 재실행 강제 — 세션 48 마이그레이션 효과 없을 가능성 대응 (사용자 환경 disk
        //   에 marker 가 이미 있으면 skip 됐을 수 있음). 새 marker 로 한 번 더 강제.
        String marker48 = props.getProperty("move.config.migration.flying.vertical", "");
        if (!"session_49_done".equals(marker48)) {
            INSTANCE.flyControlVertical = true;
            INSTANCE.diveControlVertical = true;
            save();
            try (FileOutputStream out = new FileOutputStream(configFile.toFile(), true)) {
                Properties marker2 = new Properties();
                marker2.setProperty("move.config.migration.flying.vertical", "session_49_done");
                marker2.store(out, null);
            } catch (IOException ignored) {}
        }
        // 🔴 fence 클라이밍 자동 마이그레이션 — _freeFenceClimbing default = true 강제.
        //   원본 SmartMovingConfig L123 `_freeFenceClimbing = Unmodified("move.climb.free.fence")`
        //   → Properties.java L171-172 Unmodified default true. 우리 1.21.1 이식 초기 default 가
        //   false 로 잘못 저장돼 있는 환경 → fence 자체등반/위잡기 작동 안 함 (사용자 보고 #1, #3).
        //   BUG-26/27 동일 패턴: 마커 없으면 default true 강제 + save + 마커 추가.
        //   사용자가 마이그레이션 후 의도적으로 false 로 바꾸면 그건 존중 (1:1 옵션 보존).
        String fenceMarker = props.getProperty("move.config.migration.fence", "");
        if (!"session_104_done".equals(fenceMarker)) {
            INSTANCE.freeFenceClimbing = true;
            save();
            try (FileOutputStream out = new FileOutputStream(configFile.toFile(), true)) {
                Properties marker2 = new Properties();
                marker2.setProperty("move.config.migration.fence", "session_104_done");
                marker2.store(out, null);
            } catch (IOException ignored) {}
        }
        // 🔴 사다리/덩굴 자동 진입 마이그레이션 — _freeClimbingAutoLaddder /
        //   _freeClimbingAutoVine default = true 강제.
        //   원본 SmartMovingConfig L119-120 `Unmodified("move.climb.free.ladder.auto" /
        //   "move.climb.free.vine.auto")` → Properties.java L171-172 Unmodified default true.
        //   사용자 환경 disk 에 이전 false 저장됐을 가능성 → 사다리/덩굴 자동 진입
        //   (grab 키 없이 정면 사다리/덩굴 시) 작동 안 함.
        //   fence 마이그레이션 동일 패턴 — 마커 없으면 default true 강제.
        String autoLadderVineMarker = props.getProperty("move.config.migration.ladder.vine.auto", "");
        if (!"session_ladder_vine_auto_done".equals(autoLadderVineMarker)) {
            INSTANCE.freeClimbAutoLadder = true;
            INSTANCE.freeClimbAutoVine   = true;
            save();
            try (FileOutputStream out = new FileOutputStream(configFile.toFile(), true)) {
                Properties marker2 = new Properties();
                marker2.setProperty("move.config.migration.ladder.vine.auto", "session_ladder_vine_auto_done");
                marker2.store(out, null);
            } catch (IOException ignored) {}
        }
    }

    /**
     * 서버 수신 설정 인스턴스를 기본값으로 리셋한다.
     * 원본: SmartMovingServerConfig.reset() — properties/topProperties 저장소 clear.
     * 1.21.1: 단일 저장소 구조이므로 새 인스턴스로 교체하여 모든 필드를 기본값으로 복원.
     * 호출: 서버 연결 해제 시 (SmartMovingClient.DISCONNECT).
     */
    public static void resetServerConfig() {
        SERVER_CONFIG = new SmartMovingConfig();
    }

    public static void save() {
        Path configFile = FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
        Properties props = new Properties();
        INSTANCE.writeTo(props);
        try (FileOutputStream out = new FileOutputStream(configFile.toFile())) {
            props.store(out, "SmartMoving configuration");
        } catch (IOException ignored) {}
    }

    /**
     * 서버로부터 수신한 flat String[] 배열 [k1,v1,k2,v2,...] 을 이 인스턴스에 적용한다.
     * 원본: SmartMovingServerConfig.loadFromProperties(String[] content, boolean blockCode)
     * 패리티 수준: 홀수 길이 배열은 malformed로 간주하고 처리 중단.
     */
    public void loadFromArray(String[] content) {
        if (content.length % 2 != 0) return;
        Properties props = new Properties();
        for (int i = 0; i + 1 < content.length; i += 2) {
            props.setProperty(content[i], content[i + 1]);
        }
        readFrom(props);
    }

    /**
     * 이 인스턴스의 설정을 flat String[] [k1,v1,k2,v2,...] 배열로 직렬화한다.
     * 원본: SmartMovingServerOptions.writeToProperties() 정상 경로 (config_system.md M-12)
     * 서버가 클라이언트에게 ConfigContent 패킷으로 전송할 때 사용한다.
     */
    public String[] toArray() {
        return toArray(null);
    }

    /**
     * 플레이어별 속도 치환 포함 직렬화.
     * 원본: SmartMovingServerOptions.writeToProperties(mp, key) 내부 iteration 중
     *       `entry.setValue(config._speedUserExponent.getValueString(userExponent))` 로직.
     * username != null + playerSpeedExponents 에 값 존재 시 `move.speed.user.exponent` 키 값을
     * 해당 플레이어 개인 지수로 치환. 전역 전송(username=null)은 모든 플레이어 공통값.
     */
    public String[] toArray(String username) {
        Properties props = new Properties();
        writeTo(props);
        if (username != null) {
            Integer userExponent = playerSpeedExponents.get(username);
            if (userExponent != null) {
                props.setProperty("move.speed.user.exponent", String.valueOf(userExponent));
            }
        }
        String[] result = new String[props.size() * 2];
        int i = 0;
        for (String key : props.stringPropertyNames()) {
            result[i++] = key;
            result[i++] = props.getProperty(key);
        }
        return result;
    }

    private void readFrom(Properties p) {
        enabled                  = getBool(p,   "move.enabled",                   enabled);
        // H-20 (세션 23): 게임타입별 6키 (move.config.{survival,creative,adventure}.keys{,.default})
        // 읽기 삭제. 필드/메서드 제거와 함께. 기존 세이브 파일의 해당 키는 무시됨.
        speedFactor              = getFloat(p,  "move.speed.factor",              speedFactor);
        speedUser                = getBool(p,   "move.speed.user",                speedUser);
        speedUserFactor          = getFloat(p,  "move.speed.user.factor",         speedUserFactor);
        speedUserExponent        = getInt(p,    "move.speed.user.exponent",       speedUserExponent);
        playerSpeedExponents     = parseIntegerMap(p.getProperty("move.speed.users.exponents"));
        vanillaStyle             = getBool(p,   "move.general.vanilla",           vanillaStyle);
        sneakFactor              = getFloat(p,  "move.sneak.factor",              sneakFactor);
        crawlFactor              = getFloat(p,  "move.crawl.factor",              crawlFactor);
        runFactor                = getFloat(p,  "move.run.factor",                runFactor);
        sprintFactor             = getFloat(p,  "move.sprint.factor",             sprintFactor);
        sprintFactorLevitate         = getFloat(p, "move.sprint.factor.levitate",          sprintFactorLevitate);
        sprintFactorLevitateVertical = getFloat(p, "move.sprint.factor.levitate.vertical", sprintFactorLevitateVertical);
        baseClimb                = getBool(p,   "move.climb.base",                baseClimb);
        freeClimb                = getBool(p,   "move.climb.free",                freeClimb);
        simpleClimb              = getBool(p,   "move.climb.simple",              simpleClimb);
        smartClimb               = getBool(p,   "move.climb.smart",               smartClimb);
        freeClimbingUpSpeedFactor   = getFloat(p, "move.climb.free.up.factor",   freeClimbingUpSpeedFactor);
        freeClimbingDownSpeedFactor = getFloat(p, "move.climb.free.down.factor", freeClimbingDownSpeedFactor);
        freeClimbingHorizontalSpeedFactor = getFloat(p, "move.climb.free.horizontal.speed.factor", freeClimbingHorizontalSpeedFactor);
        freeOneLadderClimbUpSpeedFactor   = getFloat(p, "move.climb.free.ladder.one.up.speed.factor",  freeOneLadderClimbUpSpeedFactor);
        freeBothLadderClimbUpSpeedFactor  = getFloat(p, "move.climb.free.ladder.two.up.speed.factor",  freeBothLadderClimbUpSpeedFactor);
        freeBaseLadderClimb = getBool(p, "move.climb.free.base.ladder", freeBaseLadderClimb);
        freeBaseVineClimb   = getBool(p, "move.climb.free.base.vine",   freeBaseVineClimb);
        freeFenceClimbing = getBool(p, "move.climb.free.fence", freeFenceClimbing);
        freeClimbingOrthogonalDirectionAngle = getFloat(p, "move.climb.free.direction.orthogonal.angle", freeClimbingOrthogonalDirectionAngle);
        freeClimbingDiagonalDirectionAngle   = getFloat(p, "move.climb.free.direction.diagonal.angle",   freeClimbingDiagonalDirectionAngle);
        ceilingClimbingSpeedFactor = getFloat(p, "move.climb.ceiling.speed.factor", ceilingClimbingSpeedFactor);
        freeClimbFallDamageStartDistance = getFloat(p, "move.climb.free.fall.damage.start", freeClimbFallDamageStartDistance);
        freeClimbFallDamageFactor        = getFloat(p, "move.climb.free.fall.damage.factor", freeClimbFallDamageFactor);
        climbJumpBackHead        = getBool(p,   "move.climb.jump.back.head",      climbJumpBackHead);
        climbExhaustion          = getBool(p,   "move.climb.exhaustion",          climbExhaustion);
        climbExhaustionStart     = getFloat(p,  "move.climb.exhaustion.start",    climbExhaustionStart);
        climbExhaustionStop      = getFloat(p,  "move.climb.exhaustion.stop",     climbExhaustionStop);
        ceilingClimbExhaustion   = getBool(p,   "move.climb.ceiling.exhaustion",  ceilingClimbExhaustion);
        ceilingClimbExhaustionStart = getFloat(p, "move.climb.ceiling.exhaustion.start", ceilingClimbExhaustionStart);
        ceilingClimbExhaustionStop  = getFloat(p, "move.climb.ceiling.exhaustion.stop",  ceilingClimbExhaustionStop);
        swim                     = getBool(p,   "move.swim",                      swim);
        swimSpeedFactor          = getFloat(p,  "move.swim.speed.factor",         swimSpeedFactor);
        dive                     = getBool(p,   "move.dive",                      dive);
        diveSpeedFactor          = getFloat(p,  "move.dive.speed.factor",         diveSpeedFactor);
        swimDownOnSneak          = getBool(p,   "move.swim.down.sneak",           swimDownOnSneak);
        diveDownOnSneak          = getBool(p,   "move.dive.down.sneak",           diveDownOnSneak);
        lavaLikeWater            = getBool(p,   "move.lava.water",                lavaLikeWater);
        lavaSwimParticlePeriodFactor = getFloat(p, "move.lava.swim.particle.period.factor", lavaSwimParticlePeriodFactor);
        runOnSprintRelease       = getBool(p,   "move.sprint.key.release.run",    runOnSprintRelease);
        walkOnSprintRelease      = getBool(p,   "move.sprint.key.release.walk",   walkOnSprintRelease);
        levitateSmall            = getBool(p,   "move.levitate.small",            levitateSmall);
        jumpControlFactor              = getFloat(p, "move.jump.control.factor",          jumpControlFactor);
        jumpHorizontalFactor           = getFloat(p, "move.jump.horizontal.factor",       jumpHorizontalFactor);
        jumpVerticalFactor             = getFloat(p, "move.jump.vertical.factor",         jumpVerticalFactor);
        standJump                      = getBool(p,  "move.jump.stand",                   standJump);
        standJumpVerticalFactor        = getFloat(p, "move.jump.stand.vertical.factor",   standJumpVerticalFactor);
        sneakJump                      = getBool(p,  "move.jump.sneak",                   sneakJump);
        sneakJumpHorizontalFactor      = getFloat(p, "move.jump.sneak.horizontal.factor", sneakJumpHorizontalFactor);
        sneakJumpVerticalFactor        = getFloat(p, "move.jump.sneak.vertical.factor",   sneakJumpVerticalFactor);
        walkJump                       = getBool(p,  "move.jump.walk",                    walkJump);
        walkJumpHorizontalFactor       = getFloat(p, "move.jump.walk.horizontal.factor",  walkJumpHorizontalFactor);
        walkJumpVerticalFactor         = getFloat(p, "move.jump.walk.vertical.factor",    walkJumpVerticalFactor);
        runJump                        = getBool(p,  "move.jump.run",                     runJump);
        runJumpHorizontalFactor        = getFloat(p, "move.jump.run.horizontal.factor",   runJumpHorizontalFactor);
        runJumpVerticalFactor          = getFloat(p, "move.jump.run.vertical.factor",     runJumpVerticalFactor);
        sprintJump                     = getBool(p,  "move.jump.sprint",                  sprintJump);
        sprintJumpHorizontalFactor     = getFloat(p, "move.jump.sprint.horizontal.factor", sprintJumpHorizontalFactor);
        sprintJumpVerticalFactor       = getFloat(p, "move.jump.sprint.vertical.factor",  sprintJumpVerticalFactor);
        wallUpJump                     = getBool(p,  "move.jump.wall",                     wallUpJump);
        wallHeadJump                   = getBool(p,  "move.jump.wall.head",                wallHeadJump);
        wallUpJumpFallMaximumDistance  = getFloat(p, "move.jump.wall.fall.maximum.distance", wallUpJumpFallMaximumDistance);
        wallHeadJumpFallMaximumDistance = getFloat(p, "move.jump.wall.head.fall.maximum.distance", wallHeadJumpFallMaximumDistance);
        wallUpJumpOrthogonalTolerance   = getFloat(p, "move.jump.wall.orthogonal.tolerance", wallUpJumpOrthogonalTolerance);
        wallUpJumpVerticalFactor       = getFloat(p, "move.jump.wall.vertical.factor",     wallUpJumpVerticalFactor);
        wallHeadJumpVerticalFactor     = getFloat(p, "move.jump.wall.head.vertical.factor", wallHeadJumpVerticalFactor);
        wallUpJumpHorizontalFactor     = getFloat(p, "move.jump.wall.horizontal.factor",   wallUpJumpHorizontalFactor);
        wallHeadJumpHorizontalFactor   = getFloat(p, "move.jump.wall.head.horizontal.factor", wallHeadJumpHorizontalFactor);
        jumpCharge               = getBool(p,   "move.jump.charge",               jumpCharge);
        jumpChargeMaximum        = getFloat(p,  "move.jump.charge.maximum",       jumpChargeMaximum);
        jumpChargeFactor         = getFloat(p,  "move.jump.charge.factor",        jumpChargeFactor);
        jumpChargeCancelOnSneakRelease = getBool(p, "move.jump.charge.sneak.release.cancel", jumpChargeCancelOnSneakRelease);
        headJump                 = getBool(p,   "move.jump.head.charge",          headJump);
        headJumpControlFactor    = getFloat(p,  "move.forward.jump.control.factor", headJumpControlFactor);
        headJumpChargeMaximum    = getFloat(p,  "move.forward.jump.charge.maximum", headJumpChargeMaximum);
        headFallDamageStartDistance = getFloat(p, "move.fall.head.damage.start.distance", headFallDamageStartDistance);
        headFallDamageFactor     = getFloat(p,  "move.fall.head.damage.factor",   headFallDamageFactor);
        angleJumpForward         = getBool(p,   "move.jump.angle.forward",        angleJumpForward);
        angleJumpSide            = getBool(p,   "move.jump.angle.side",           angleJumpSide);
        angleJumpBack            = getBool(p,   "move.jump.angle.back",           angleJumpBack);
        angleJumpHorizontalFactor = getFloat(p, "move.jump.angle.horizontal.factor", angleJumpHorizontalFactor);
        angleJumpVerticalFactor  = getFloat(p,  "move.jump.angle.vertical.factor", angleJumpVerticalFactor);
        climbUpJump              = getBool(p,   "move.jump.climb.up",             climbUpJump);
        climbUpJumpVerticalFactor = getFloat(p, "move.jump.climb.up.vertical.factor", climbUpJumpVerticalFactor);
        climbUpJumpHandsOnlyVerticalFactor = getFloat(p, "move.jump.climb.up.hands.only.vertical.factor", climbUpJumpHandsOnlyVerticalFactor);
        climbBackUpJump          = getBool(p,   "move.jump.climb.back.up",        climbBackUpJump);
        climbBackUpJumpVerticalFactor = getFloat(p, "move.jump.climb.back.up.vertical.factor", climbBackUpJumpVerticalFactor);
        climbBackUpJumpHorizontalFactor = getFloat(p, "move.jump.climb.back.up.horizontal.factor", climbBackUpJumpHorizontalFactor);
        climbBackUpJumpHandsOnlyVerticalFactor = getFloat(p, "move.jump.climb.back.up.hands.only.vertical.factor", climbBackUpJumpHandsOnlyVerticalFactor);
        climbBackUpJumpHandsOnlyHorizontalFactor = getFloat(p, "move.jump.climb.back.up.hands.only.horizontal.factor", climbBackUpJumpHandsOnlyHorizontalFactor);
        climbBackHeadJump        = getBool(p,   "move.jump.climb.back.head",      climbBackHeadJump);
        climbBackHeadJumpVerticalFactor = getFloat(p, "move.jump.climb.back.head.vertical.factor", climbBackHeadJumpVerticalFactor);
        climbBackHeadJumpHorizontalFactor = getFloat(p, "move.jump.climb.back.head.horizontal.factor", climbBackHeadJumpHorizontalFactor);
        climbBackHeadJumpHandsOnlyVerticalFactor = getFloat(p, "move.jump.climb.back.head.hands.only.vertical.factor", climbBackHeadJumpHandsOnlyVerticalFactor);
        climbBackHeadJumpHandsOnlyHorizontalFactor = getFloat(p, "move.jump.climb.back.head.hands.only.horizontal.factor", climbBackHeadJumpHandsOnlyHorizontalFactor);
        angleJumpDoubleClickTicks = getFloat(p, "move.jump.angle.double.click.ticks", angleJumpDoubleClickTicks);
        wallJumpDoubleClick      = getBool(p,   "move.jump.wall.double.click",    wallJumpDoubleClick);
        wallJumpDoubleClickTicks = getFloat(p,  "move.jump.wall.double.click.ticks", wallJumpDoubleClickTicks);
        slideSlipperinessFactor  = getFloat(p,  "move.slide.slipperiness.factor", slideSlipperinessFactor);
        slideParticlePeriodFactor = getFloat(p, "move.slide.particle.period.factor", slideParticlePeriodFactor);
        slidingSpeedStopFactor   = getFloat(p,  "move.slide.speed.stop.factor",  slidingSpeedStopFactor);
        slideControlDegrees      = getFloat(p,  "move.slide.control.angle",      slideControlDegrees);
        flyingSpeedFactor        = getFloat(p,  "move.fly.speed.factor",          flyingSpeedFactor);
        flyControlVertical       = getBool(p,   "move.fly.control.vertical",      flyControlVertical);
        diveControlVertical      = getBool(p,   "move.dive.control.vertical",     diveControlVertical);
        flyCloseToGround         = getBool(p,   "move.fly.ground.close",          flyCloseToGround);
        flyWhileOnGround         = getBool(p,   "move.fly.ground.collide",        flyWhileOnGround);
        fly                      = getBool(p,   "move.fly",                       fly);
        slide                    = getBool(p,   "move.slide",                     slide);
        crawl                    = getBool(p,   "move.crawl",                     crawl);
        crawlOverEdge            = getBool(p,   "move.crawl.edge",                crawlOverEdge);
        crawlToggle              = getBool(p,   "move.crawl.toggle",              crawlToggle);
        sneakToggle              = getBool(p,   "move.sneak.toggle",              sneakToggle);
        sneakNameTag             = getBool(p,   "move.sneak.name",                sneakNameTag);
        crawlNameTag             = getBool(p,   "move.crawl.name",                crawlNameTag);
        displayExhaustionBar     = getBool(p,   "move.gui.exhaustion.bar",        displayExhaustionBar);
        displayJumpChargeBar     = getBool(p,   "move.gui.jump.charge.bar",       displayJumpChargeBar);
        sneak                    = getBool(p,   "move.sneak",                     sneak);
        run                      = getBool(p,   "move.run",                       run);
        sprint                   = getBool(p,   "move.sprint",                    sprint);
        ceilingClimbing          = getBool(p,   "move.climb.ceiling",             ceilingClimbing);
        perspectiveFadeFactor    = getFloat(p,  "move.perspective.fade.factor",   perspectiveFadeFactor);
        perspectiveSpeedFactor   = getFloat(p,  "move.perspective.speed.factor",  perspectiveSpeedFactor);
        perspectiveSpeedFactorMax= getFloat(p,  "move.perspective.speed.factor.max", perspectiveSpeedFactorMax);
        perspectiveRunFactor     = getFloat(p,  "move.perspective.run.factor",    perspectiveRunFactor);
        perspectiveSprintFactor  = getFloat(p,  "move.perspective.sprint.factor", perspectiveSprintFactor);
        // Exhaustion/Hunger factor 1단계 (원본 getFactor L563-L575). ⚠️ Exhaution 오타 유지.
        baseExhautionLossFactor       = getFloat(p, "move.exhaustion.loss.factor",       baseExhautionLossFactor);
        baseHungerGainFactor          = getFloat(p, "move.hunger.gain.factor",           baseHungerGainFactor);
        fallExhautionLossFactor       = getFloat(p, "move.exhaustion.fall.loss.factor",  fallExhautionLossFactor);
        sprintingHungerGainFactor     = getFloat(p, "move.hunger.sprint.gain.factor",    sprintingHungerGainFactor);
        sprintingExhautionLossFactor  = getFloat(p, "move.exhaustion.sprint.loss.factor", sprintingExhautionLossFactor);
        runningHungerGainFactor       = getFloat(p, "move.hunger.run.gain.factor",       runningHungerGainFactor);
        runningExhautionLossFactor    = getFloat(p, "move.exhaustion.run.loss.factor",   runningExhautionLossFactor);
        sneakingHungerGainFactor      = getFloat(p, "move.hunger.sneak.gain.factor",     sneakingHungerGainFactor);
        sneakingExhautionLossFactor   = getFloat(p, "move.exhaustion.sneak.loss.factor", sneakingExhautionLossFactor);
        standingHungerGainFactor      = getFloat(p, "move.hunger.stand.gain.factor",     standingHungerGainFactor);
        standingExhautionLossFactor   = getFloat(p, "move.exhaustion.stand.loss.factor", standingExhautionLossFactor);
        walkingHungerGainFactor       = getFloat(p, "move.hunger.walk.gain.factor",      walkingHungerGainFactor);
        walkingExhautionLossFactor    = getFloat(p, "move.exhaustion.walk.loss.factor",  walkingExhautionLossFactor);
        // Exhaustion/Hunger factor 2단계 (원본 getFactor L577-L592). 2단계는 `Exhaustion` 정타.
        // 키 순서 이상 주의: ceilClimbing 은 `climb.gain.ceiling` / `climb.ceiling.loss` (원본).
        climbingHungerGainFactor      = getFloat(p, "move.hunger.climb.gain.factor",         climbingHungerGainFactor);
        climbingExhaustionLossFactor  = getFloat(p, "move.exhaustion.climb.loss.factor",     climbingExhaustionLossFactor);
        crawlingHungerGainFactor      = getFloat(p, "move.hunger.crawl.gain.factor",         crawlingHungerGainFactor);
        crawlingExhaustionLossFactor  = getFloat(p, "move.exhaustion.crawl.loss.factor",     crawlingExhaustionLossFactor);
        ceilClimbingHungerGainFactor  = getFloat(p, "move.hunger.climb.gain.ceiling.factor", ceilClimbingHungerGainFactor);
        ceilClimbingExhaustionLossFactor = getFloat(p, "move.exhaustion.climb.ceiling.loss.factor", ceilClimbingExhaustionLossFactor);
        swimmingHungerGainFactor      = getFloat(p, "move.hunger.swim.gain.factor",          swimmingHungerGainFactor);
        swimmingExhaustionLossFactor  = getFloat(p, "move.exhaustion.swim.loss.factor",      swimmingExhaustionLossFactor);
        divingHungerGainFactor        = getFloat(p, "move.hunger.dive.gain.factor",          divingHungerGainFactor);
        divingExhaustionLossFactor    = getFloat(p, "move.exhaustion.dive.loss.factor",      divingExhaustionLossFactor);
        dippingHungerGainFactor       = getFloat(p, "move.hunger.dip.gain.factor",           dippingHungerGainFactor);
        dippingExhaustionLossFactor   = getFloat(p, "move.exhaustion.dip.loss.factor",       dippingExhaustionLossFactor);
        normalHungerGainFactor        = getFloat(p, "move.hunger.normal.gain.factor",        normalHungerGainFactor);
        normalExhaustionLossFactor    = getFloat(p, "move.exhaustion.normal.loss.factor",    normalExhaustionLossFactor);
        // handleExhaustion 기타 (원본 SmartMovingSelf L863/L893). Easy 값 default.
        alwaysHungerGain              = getFloat(p, "move.hunger.always.gain",               alwaysHungerGain);
        exhaustionLossHungerFactor    = getFloat(p, "move.exhaustion.hunger.factor",         exhaustionLossHungerFactor);
    }

    private void writeTo(Properties p) {
        p.setProperty("move.enabled",                    String.valueOf(enabled));
        // H-20 (세션 23): 게임타입별 6키 writeTo 삭제 (readFrom 과 대칭).
        p.setProperty("move.speed.factor",               String.valueOf(speedFactor));
        p.setProperty("move.speed.user",                 String.valueOf(speedUser));
        p.setProperty("move.speed.user.factor",          String.valueOf(speedUserFactor));
        p.setProperty("move.speed.user.exponent",        String.valueOf(speedUserExponent));
        p.setProperty("move.speed.users.exponents",      serializeIntegerMap(playerSpeedExponents));
        p.setProperty("move.general.vanilla",            String.valueOf(vanillaStyle));
        p.setProperty("move.sneak.factor",               String.valueOf(sneakFactor));
        p.setProperty("move.crawl.factor",               String.valueOf(crawlFactor));
        p.setProperty("move.run.factor",                 String.valueOf(runFactor));
        p.setProperty("move.sprint.factor",              String.valueOf(sprintFactor));
        p.setProperty("move.sprint.factor.levitate",          String.valueOf(sprintFactorLevitate));
        p.setProperty("move.sprint.factor.levitate.vertical", String.valueOf(sprintFactorLevitateVertical));
        p.setProperty("move.climb.base",                 String.valueOf(baseClimb));
        p.setProperty("move.climb.free",                 String.valueOf(freeClimb));
        p.setProperty("move.climb.simple",               String.valueOf(simpleClimb));
        p.setProperty("move.climb.smart",                String.valueOf(smartClimb));
        p.setProperty("move.climb.free.up.factor",       String.valueOf(freeClimbingUpSpeedFactor));
        p.setProperty("move.climb.free.down.factor",     String.valueOf(freeClimbingDownSpeedFactor));
        p.setProperty("move.climb.free.horizontal.speed.factor", String.valueOf(freeClimbingHorizontalSpeedFactor));
        p.setProperty("move.climb.free.ladder.one.up.speed.factor", String.valueOf(freeOneLadderClimbUpSpeedFactor));
        p.setProperty("move.climb.free.ladder.two.up.speed.factor", String.valueOf(freeBothLadderClimbUpSpeedFactor));
        p.setProperty("move.climb.free.base.ladder", String.valueOf(freeBaseLadderClimb));
        p.setProperty("move.climb.free.base.vine",   String.valueOf(freeBaseVineClimb));
        p.setProperty("move.climb.free.fence", String.valueOf(freeFenceClimbing));
        p.setProperty("move.climb.free.direction.orthogonal.angle", String.valueOf(freeClimbingOrthogonalDirectionAngle));
        p.setProperty("move.climb.free.direction.diagonal.angle",   String.valueOf(freeClimbingDiagonalDirectionAngle));
        p.setProperty("move.climb.ceiling.speed.factor", String.valueOf(ceilingClimbingSpeedFactor));
        p.setProperty("move.climb.free.fall.damage.start", String.valueOf(freeClimbFallDamageStartDistance));
        p.setProperty("move.climb.free.fall.damage.factor", String.valueOf(freeClimbFallDamageFactor));
        p.setProperty("move.climb.jump.back.head",       String.valueOf(climbJumpBackHead));
        p.setProperty("move.climb.exhaustion",           String.valueOf(climbExhaustion));
        p.setProperty("move.climb.exhaustion.start",     String.valueOf(climbExhaustionStart));
        p.setProperty("move.climb.exhaustion.stop",      String.valueOf(climbExhaustionStop));
        p.setProperty("move.climb.ceiling.exhaustion",   String.valueOf(ceilingClimbExhaustion));
        p.setProperty("move.climb.ceiling.exhaustion.start", String.valueOf(ceilingClimbExhaustionStart));
        p.setProperty("move.climb.ceiling.exhaustion.stop",  String.valueOf(ceilingClimbExhaustionStop));
        p.setProperty("move.swim",                       String.valueOf(swim));
        p.setProperty("move.swim.speed.factor",          String.valueOf(swimSpeedFactor));
        p.setProperty("move.dive",                       String.valueOf(dive));
        p.setProperty("move.dive.speed.factor",          String.valueOf(diveSpeedFactor));
        p.setProperty("move.swim.down.sneak",            String.valueOf(swimDownOnSneak));
        p.setProperty("move.dive.down.sneak",            String.valueOf(diveDownOnSneak));
        p.setProperty("move.lava.water",                 String.valueOf(lavaLikeWater));
        p.setProperty("move.lava.swim.particle.period.factor", String.valueOf(lavaSwimParticlePeriodFactor));
        p.setProperty("move.sprint.key.release.run",     String.valueOf(runOnSprintRelease));
        p.setProperty("move.sprint.key.release.walk",    String.valueOf(walkOnSprintRelease));
        p.setProperty("move.levitate.small",             String.valueOf(levitateSmall));
        p.setProperty("move.jump.control.factor",            String.valueOf(jumpControlFactor));
        p.setProperty("move.jump.horizontal.factor",         String.valueOf(jumpHorizontalFactor));
        p.setProperty("move.jump.vertical.factor",           String.valueOf(jumpVerticalFactor));
        p.setProperty("move.jump.stand",                     String.valueOf(standJump));
        p.setProperty("move.jump.stand.vertical.factor",     String.valueOf(standJumpVerticalFactor));
        p.setProperty("move.jump.sneak",                     String.valueOf(sneakJump));
        p.setProperty("move.jump.sneak.horizontal.factor",   String.valueOf(sneakJumpHorizontalFactor));
        p.setProperty("move.jump.sneak.vertical.factor",     String.valueOf(sneakJumpVerticalFactor));
        p.setProperty("move.jump.walk",                      String.valueOf(walkJump));
        p.setProperty("move.jump.walk.horizontal.factor",    String.valueOf(walkJumpHorizontalFactor));
        p.setProperty("move.jump.walk.vertical.factor",      String.valueOf(walkJumpVerticalFactor));
        p.setProperty("move.jump.run",                       String.valueOf(runJump));
        p.setProperty("move.jump.run.horizontal.factor",     String.valueOf(runJumpHorizontalFactor));
        p.setProperty("move.jump.run.vertical.factor",       String.valueOf(runJumpVerticalFactor));
        p.setProperty("move.jump.sprint",                    String.valueOf(sprintJump));
        p.setProperty("move.jump.sprint.horizontal.factor",  String.valueOf(sprintJumpHorizontalFactor));
        p.setProperty("move.jump.sprint.vertical.factor",    String.valueOf(sprintJumpVerticalFactor));
        p.setProperty("move.jump.wall",                      String.valueOf(wallUpJump));
        p.setProperty("move.jump.wall.head",                 String.valueOf(wallHeadJump));
        p.setProperty("move.jump.wall.fall.maximum.distance", String.valueOf(wallUpJumpFallMaximumDistance));
        p.setProperty("move.jump.wall.head.fall.maximum.distance", String.valueOf(wallHeadJumpFallMaximumDistance));
        p.setProperty("move.jump.wall.orthogonal.tolerance", String.valueOf(wallUpJumpOrthogonalTolerance));
        p.setProperty("move.jump.wall.vertical.factor",      String.valueOf(wallUpJumpVerticalFactor));
        p.setProperty("move.jump.wall.head.vertical.factor", String.valueOf(wallHeadJumpVerticalFactor));
        p.setProperty("move.jump.wall.horizontal.factor",    String.valueOf(wallUpJumpHorizontalFactor));
        p.setProperty("move.jump.wall.head.horizontal.factor", String.valueOf(wallHeadJumpHorizontalFactor));
        p.setProperty("move.jump.charge",                String.valueOf(jumpCharge));
        p.setProperty("move.jump.charge.maximum",        String.valueOf(jumpChargeMaximum));
        p.setProperty("move.jump.charge.factor",         String.valueOf(jumpChargeFactor));
        p.setProperty("move.jump.charge.sneak.release.cancel", String.valueOf(jumpChargeCancelOnSneakRelease));
        p.setProperty("move.jump.head.charge",           String.valueOf(headJump));
        p.setProperty("move.forward.jump.control.factor", String.valueOf(headJumpControlFactor));
        p.setProperty("move.forward.jump.charge.maximum", String.valueOf(headJumpChargeMaximum));
        p.setProperty("move.fall.head.damage.start.distance", String.valueOf(headFallDamageStartDistance));
        p.setProperty("move.fall.head.damage.factor",    String.valueOf(headFallDamageFactor));
        p.setProperty("move.jump.angle.forward",         String.valueOf(angleJumpForward));
        p.setProperty("move.jump.angle.side",            String.valueOf(angleJumpSide));
        p.setProperty("move.jump.angle.back",            String.valueOf(angleJumpBack));
        p.setProperty("move.jump.angle.horizontal.factor", String.valueOf(angleJumpHorizontalFactor));
        p.setProperty("move.jump.angle.vertical.factor", String.valueOf(angleJumpVerticalFactor));
        p.setProperty("move.jump.climb.up",              String.valueOf(climbUpJump));
        p.setProperty("move.jump.climb.up.vertical.factor", String.valueOf(climbUpJumpVerticalFactor));
        p.setProperty("move.jump.climb.up.hands.only.vertical.factor", String.valueOf(climbUpJumpHandsOnlyVerticalFactor));
        p.setProperty("move.jump.climb.back.up",         String.valueOf(climbBackUpJump));
        p.setProperty("move.jump.climb.back.up.vertical.factor", String.valueOf(climbBackUpJumpVerticalFactor));
        p.setProperty("move.jump.climb.back.up.horizontal.factor", String.valueOf(climbBackUpJumpHorizontalFactor));
        p.setProperty("move.jump.climb.back.up.hands.only.vertical.factor", String.valueOf(climbBackUpJumpHandsOnlyVerticalFactor));
        p.setProperty("move.jump.climb.back.up.hands.only.horizontal.factor", String.valueOf(climbBackUpJumpHandsOnlyHorizontalFactor));
        p.setProperty("move.jump.climb.back.head",       String.valueOf(climbBackHeadJump));
        p.setProperty("move.jump.climb.back.head.vertical.factor", String.valueOf(climbBackHeadJumpVerticalFactor));
        p.setProperty("move.jump.climb.back.head.horizontal.factor", String.valueOf(climbBackHeadJumpHorizontalFactor));
        p.setProperty("move.jump.climb.back.head.hands.only.vertical.factor", String.valueOf(climbBackHeadJumpHandsOnlyVerticalFactor));
        p.setProperty("move.jump.climb.back.head.hands.only.horizontal.factor", String.valueOf(climbBackHeadJumpHandsOnlyHorizontalFactor));
        p.setProperty("move.jump.angle.double.click.ticks", String.valueOf(angleJumpDoubleClickTicks));
        p.setProperty("move.jump.wall.double.click",     String.valueOf(wallJumpDoubleClick));
        p.setProperty("move.jump.wall.double.click.ticks", String.valueOf(wallJumpDoubleClickTicks));
        p.setProperty("move.slide.slipperiness.factor",  String.valueOf(slideSlipperinessFactor));
        p.setProperty("move.slide.particle.period.factor", String.valueOf(slideParticlePeriodFactor));
        p.setProperty("move.slide.speed.stop.factor",    String.valueOf(slidingSpeedStopFactor));
        p.setProperty("move.slide.control.angle",        String.valueOf(slideControlDegrees));
        p.setProperty("move.fly.speed.factor",           String.valueOf(flyingSpeedFactor));
        p.setProperty("move.fly.control.vertical",       String.valueOf(flyControlVertical));
        p.setProperty("move.dive.control.vertical",      String.valueOf(diveControlVertical));
        p.setProperty("move.fly.ground.close",           String.valueOf(flyCloseToGround));
        p.setProperty("move.fly.ground.collide",         String.valueOf(flyWhileOnGround));
        p.setProperty("move.fly",                        String.valueOf(fly));
        p.setProperty("move.slide",                      String.valueOf(slide));
        p.setProperty("move.crawl",                      String.valueOf(crawl));
        p.setProperty("move.crawl.edge",                 String.valueOf(crawlOverEdge));
        p.setProperty("move.crawl.toggle",               String.valueOf(crawlToggle));
        p.setProperty("move.sneak.toggle",               String.valueOf(sneakToggle));
        p.setProperty("move.sneak.name",                 String.valueOf(sneakNameTag));
        p.setProperty("move.crawl.name",                 String.valueOf(crawlNameTag));
        p.setProperty("move.gui.exhaustion.bar",         String.valueOf(displayExhaustionBar));
        p.setProperty("move.gui.jump.charge.bar",        String.valueOf(displayJumpChargeBar));
        p.setProperty("move.sneak",                      String.valueOf(sneak));
        p.setProperty("move.run",                        String.valueOf(run));
        p.setProperty("move.sprint",                     String.valueOf(sprint));
        p.setProperty("move.climb.ceiling",              String.valueOf(ceilingClimbing));
        p.setProperty("move.perspective.fade.factor",    String.valueOf(perspectiveFadeFactor));
        p.setProperty("move.perspective.speed.factor",   String.valueOf(perspectiveSpeedFactor));
        p.setProperty("move.perspective.speed.factor.max", String.valueOf(perspectiveSpeedFactorMax));
        p.setProperty("move.perspective.run.factor",     String.valueOf(perspectiveRunFactor));
        p.setProperty("move.perspective.sprint.factor",  String.valueOf(perspectiveSprintFactor));
        // Exhaustion/Hunger factor 1단계 (원본 getFactor L563-L575). ⚠️ Exhaution 오타 유지.
        p.setProperty("move.exhaustion.loss.factor",         String.valueOf(baseExhautionLossFactor));
        p.setProperty("move.hunger.gain.factor",             String.valueOf(baseHungerGainFactor));
        p.setProperty("move.exhaustion.fall.loss.factor",    String.valueOf(fallExhautionLossFactor));
        p.setProperty("move.hunger.sprint.gain.factor",      String.valueOf(sprintingHungerGainFactor));
        p.setProperty("move.exhaustion.sprint.loss.factor",  String.valueOf(sprintingExhautionLossFactor));
        p.setProperty("move.hunger.run.gain.factor",         String.valueOf(runningHungerGainFactor));
        p.setProperty("move.exhaustion.run.loss.factor",     String.valueOf(runningExhautionLossFactor));
        p.setProperty("move.hunger.sneak.gain.factor",       String.valueOf(sneakingHungerGainFactor));
        p.setProperty("move.exhaustion.sneak.loss.factor",   String.valueOf(sneakingExhautionLossFactor));
        p.setProperty("move.hunger.stand.gain.factor",       String.valueOf(standingHungerGainFactor));
        p.setProperty("move.exhaustion.stand.loss.factor",   String.valueOf(standingExhautionLossFactor));
        p.setProperty("move.hunger.walk.gain.factor",        String.valueOf(walkingHungerGainFactor));
        p.setProperty("move.exhaustion.walk.loss.factor",    String.valueOf(walkingExhautionLossFactor));
        // Exhaustion/Hunger factor 2단계 (원본 getFactor L577-L592). 2단계는 `Exhaustion` 정타.
        p.setProperty("move.hunger.climb.gain.factor",           String.valueOf(climbingHungerGainFactor));
        p.setProperty("move.exhaustion.climb.loss.factor",       String.valueOf(climbingExhaustionLossFactor));
        p.setProperty("move.hunger.crawl.gain.factor",           String.valueOf(crawlingHungerGainFactor));
        p.setProperty("move.exhaustion.crawl.loss.factor",       String.valueOf(crawlingExhaustionLossFactor));
        p.setProperty("move.hunger.climb.gain.ceiling.factor",   String.valueOf(ceilClimbingHungerGainFactor));
        p.setProperty("move.exhaustion.climb.ceiling.loss.factor", String.valueOf(ceilClimbingExhaustionLossFactor));
        p.setProperty("move.hunger.swim.gain.factor",            String.valueOf(swimmingHungerGainFactor));
        p.setProperty("move.exhaustion.swim.loss.factor",        String.valueOf(swimmingExhaustionLossFactor));
        p.setProperty("move.hunger.dive.gain.factor",            String.valueOf(divingHungerGainFactor));
        p.setProperty("move.exhaustion.dive.loss.factor",        String.valueOf(divingExhaustionLossFactor));
        p.setProperty("move.hunger.dip.gain.factor",             String.valueOf(dippingHungerGainFactor));
        p.setProperty("move.exhaustion.dip.loss.factor",         String.valueOf(dippingExhaustionLossFactor));
        p.setProperty("move.hunger.normal.gain.factor",          String.valueOf(normalHungerGainFactor));
        p.setProperty("move.exhaustion.normal.loss.factor",      String.valueOf(normalExhaustionLossFactor));
        // handleExhaustion 기타 (원본 SmartMovingSelf L863/L893).
        p.setProperty("move.hunger.always.gain",                 String.valueOf(alwaysHungerGain));
        p.setProperty("move.exhaustion.hunger.factor",           String.valueOf(exhaustionLossHungerFactor));
    }

    /** getUserSpeedFactor() 공식: (1 + speedUserFactor)^speedUserExponent */
    public float getUserSpeedFactor() {
        // 원본: isUserSpeedAlwaysDefault() = !speedUser || speedUserFactor==1F
        if (!speedUser || speedUserFactor == 1F || speedUserExponent == 0) return 1F;
        return (float) Math.pow(1F + speedUserFactor, speedUserExponent);
    }

    /**
     * 원본 `SmartMovingClientConfig.getFactor(hunger, 14상태)` L554-L595 1:1 이식.
     * 허기/소진 배율 2단계 공식. 호출자는 `handleExhaustion` L862(hunger=true) /
     * L888(hunger=false) 에서 현재 이동/행동 상태를 전달.
     *
     * **원본 본체** (focus_05 §5.4 참조, SmartMovingClientConfig.java L554-L595):
     *
     * <pre>
     * isClimbing |= isClimbCrawling;
     * isCrawling |= isCrawlClimbing;
     * boolean actionOverGound = isClimbing || isCeilingClimbing || isDiving || isSwimming;
     * boolean airBorne = !onGround && !actionOverGound;
     * isStanding = actionOverGound ? isStill : isStanding;
     * isSneaking = isSneaking & !isStanding;
     *
     * float factor = hunger ? _baseHungerGainFactor : _baseExhautionLossFactor;
     * // 1단계 — 이동속도 배율 (airBorne / sprinting / running / sneaking / standing / walking)
     * // ⚠️ airBorne + hunger 는 0F 하드코딩 (대응 필드 없음)
     * // 2단계 — 행동 배율 (climbing / crawling / ceilClimbing / swimming / diving / dipping / normal)
     * // ⚠️ L589 onGround + L591 else 모두 normal 참조 (의도된 중복)
     * </pre>
     *
     * 파라미터 순서는 원본과 동일(14개 boolean).
     *
     * @param hunger true → hunger gain 배율, false → exhaustion loss 배율
     * @return 배율값 (base × 1단계 × 2단계)
     */
    public float getFactor(boolean hunger, boolean onGround, boolean isStanding, boolean isStill,
            boolean isSneaking, boolean isRunning, boolean isSprinting,
            boolean isClimbing, boolean isClimbCrawling, boolean isCeilingClimbing,
            boolean isDipping, boolean isSwimming, boolean isDiving,
            boolean isCrawling, boolean isCrawlClimbing) {
        // 원본 L556-L561 전처리
        isClimbing |= isClimbCrawling;
        isCrawling |= isCrawlClimbing;
        boolean actionOverGound = isClimbing || isCeilingClimbing || isDiving || isSwimming;
        boolean airBorne = !onGround && !actionOverGound;
        isStanding = actionOverGound ? isStill : isStanding;
        isSneaking = isSneaking & !isStanding;

        // 원본 L563 base factor
        float factor = hunger ? baseHungerGainFactor : baseExhautionLossFactor;

        // 원본 L564-L575 1단계 — 이동속도 배율
        if (airBorne)
            factor *= hunger ? 0F : fallExhautionLossFactor;           // ⚠️ hunger=0F 하드코딩 (원본 L565)
        else if (isSprinting)
            factor *= hunger ? sprintingHungerGainFactor : sprintingExhautionLossFactor;
        else if (isRunning)
            factor *= hunger ? runningHungerGainFactor : runningExhautionLossFactor;
        else if (isSneaking)
            factor *= hunger ? sneakingHungerGainFactor : sneakingExhautionLossFactor;
        else if (isStanding)
            factor *= hunger ? standingHungerGainFactor : standingExhautionLossFactor;
        else
            factor *= hunger ? walkingHungerGainFactor : walkingExhautionLossFactor;

        // 원본 L577-L592 2단계 — 행동 배율
        if (isClimbing)
            factor *= hunger ? climbingHungerGainFactor : climbingExhaustionLossFactor;
        else if (isCrawling)
            factor *= hunger ? crawlingHungerGainFactor : crawlingExhaustionLossFactor;
        else if (isCeilingClimbing)
            factor *= hunger ? ceilClimbingHungerGainFactor : ceilClimbingExhaustionLossFactor;
        else if (isSwimming)
            factor *= hunger ? swimmingHungerGainFactor : swimmingExhaustionLossFactor;
        else if (isDiving)
            factor *= hunger ? divingHungerGainFactor : divingExhaustionLossFactor;
        else if (isDipping)
            factor *= hunger ? dippingHungerGainFactor : dippingExhaustionLossFactor;
        else if (onGround)
            factor *= hunger ? normalHungerGainFactor : normalExhaustionLossFactor;
        else
            factor *= hunger ? normalHungerGainFactor : normalExhaustionLossFactor;  // L591 의도된 중복

        return factor;
    }

    /**
     * 원본 SmartMovingProperties.update() (L163-L172) 1:1 근사 이식.
     *
     * 원본:
     *   protected void update() {
     *       List<Property<?>> properties = getProperties();
     *       Iterator<Property<?>> iterator = properties.iterator();
     *       String currentKey = getCurrentKey();
     *       while (iterator.hasNext())
     *           iterator.next().update(currentKey);   // ① Property key-scoped 값 재계산
     *       enabled = toggler != -1;                   // ② enabled 파생
     *   }
     *
     * 1.21.1: Property 계층 부재 → ① 는 N/A (단일 필드 값 구조). ② 만 이식.
     * 호출: toggle() / setKeys() / setCurrentKey() / load() 등 toggler 변경 이후.
     */
    private void updateToggler() {
        enabled = toggler != -1;
    }

    /**
     * 원본 SmartMovingProperties.toggle() (L81-L88) 1:1 이식.
     *
     * 원본:
     *   public void toggle() {
     *       int length = keys == null ? 0 : keys.length;
     *       toggler++;
     *       if (toggler == length) toggler = -1;
     *       update();
     *   }
     *
     * 순환 패턴:
     *   configKeys.length == 3 (survival/adventure, "e"/"m"/"h"):
     *     0 → 1 → 2 → -1 → 0 → ...       (Easy → Medium → Hard → disabled → Easy ...)
     *   configKeys.length == 1 (creative "c" 또는 DEFAULT_KEYS {null}):
     *     0 → -1 → 0 → ...                 (on/off 토글)
     *
     * save(): 원본 Properties.toggle() 에는 없음. 원본 SmartMovingOptions.toggle() (override,
     *   SmartMovingOptions.md L500-L531) 에서 saveToOptionsFile(optionsPath) 호출. 1.21.1 은
     *   Properties/Options/Config 계층이 SmartMovingConfig 하나로 통합 — 여기서 save() 호출.
     *   SmartMovingOptions.toggle() 의 다른 추가 동작(채팅, defaultKey 갱신)은 별도 원자 작업.
     */
    public void toggle() {
        int length = configKeys == null ? 0 : configKeys.length;
        toggler++;
        if (toggler == length) toggler = -1;
        updateToggler();
        save();
    }

    /**
     * 원본 SmartMovingProperties.setKeys(String[]) (L90-L97) 1:1 이식.
     *
     * 원본:
     *   public void setKeys(String[] keys) {
     *       if (keys == null || keys.length == 0)
     *           keys = _defaultKeys;          // 단순 on/off 모드 ({null})
     *       this.keys = keys;
     *       toggler = 0;                      // 항상 첫 key 로 초기화
     *       update();
     *   }
     *
     * 호출: initializeForGameIfNeccessary(gameType) 에서 게임타입별 keys 배열 전달 (F 섹션).
     * `save()` 호출은 원본 setKeys 에 없음 — 생략.
     */
    public void setKeys(String[] keys) {
        if (keys == null || keys.length == 0)
            keys = DEFAULT_KEYS;
        this.configKeys = keys;
        this.toggler = 0;
        updateToggler();
    }

    // H-19 (세션 23): getCurrentKey / getKey / getNextKey / hasKey / setCurrentKey 메서드
    // 5개 삭제. H-18 후 외부 호출처 0건 확인. 2상태 토글(toggle/setKeys/updateToggler 만
    // 유지)에 불필요. Medium/Hard 프리셋 복원 시 `focus_09_difficulty_presets.md` 에서 재도입.
    // 원본 메서드 본체는 `docs/research/original/smartmoving/config/SmartMovingProperties.md`
    // L99-L156 에 보존.

    // H-20 (세션 23): initializeForGameIfNeccessary / resetForNewGame 메서드 삭제.
    // 2상태 토글(configKeys={null}) 유지가 목표이므로 gameType 기반 setKeys 호출 경로 불필요.
    // 원본 함수 본체는 focus_05 §5 리서치 파일에 보존. Medium/Hard 프리셋 복원 시
    // `focus_09_difficulty_presets.md` 후속 포커스에서 재도입.

    public void changeSpeed(int difference) {
        speedUserExponent += difference;
    }

    /**
     * 플레이어별 속도 지수 조정.
     * 원본: SmartMovingServerOptions.changeSingleSpeed(player, difference):
     *   Integer exp = _speedUsersExponents.get(username);
     *   if (exp == null) exp = _speedUserExponent.value;
     *   exp += diff;
     *   _speedUsersExponents.put(username, exp);
     *   saveToOptionsFile();
     * 개인 값이 없으면 전역 지수를 기준으로 시작.
     */
    public synchronized void changeSingleSpeed(String username, int difference) {
        Integer exponent = playerSpeedExponents.get(username);
        if (exponent == null) exponent = speedUserExponent;
        exponent += difference;
        playerSpeedExponents.put(username, exponent);
    }

    /** 현재 속도를 정수 퍼센트 문자열로 반환 (원본: SmartMovingOptions.getSpeedPercent()) */
    public String getSpeedPercent() {
        return String.valueOf((int)(getUserSpeedFactor() * 100));
    }

    private static float getFloat(Properties p, String key, float def) {
        String v = p.getProperty(key);
        if (v == null) return def;
        try { return Float.parseFloat(v.trim()); } catch (NumberFormatException e) { return def; }
    }

    private static boolean getBool(Properties p, String key, boolean def) {
        String v = p.getProperty(key);
        if (v == null) return def;
        return Boolean.parseBoolean(v.trim());
    }

    private static int getInt(Properties p, String key, int def) {
        String v = p.getProperty(key);
        if (v == null) return def;
        try { return Integer.parseInt(v.trim()); } catch (NumberFormatException e) { return def; }
    }

    /**
     * 원본: Value.tryParseIntegerMap(String value) (Value.md L555-568).
     * `,`으로 분할 → 짝수 인덱스=키, 홀수=값. parseInt 실패 항목은 스킵(원본은 NPE 가능).
     */
    private static Map<String, Integer> parseIntegerMap(String raw) {
        Map<String, Integer> result = new HashMap<>();
        if (raw == null || raw.isEmpty()) return result;
        String[] parts = raw.split(",");
        for (int i = 0; i < parts.length; i++) {
            String key = parts[i++];
            if (i < parts.length) {
                try { result.put(key, Integer.parseInt(parts[i].trim())); }
                catch (NumberFormatException ignored) {}
            }
        }
        return result;
    }

    /** parseIntegerMap 역방향 — `"user1,5,user2,-2,..."` 형식 생성. */
    private static String serializeIntegerMap(Map<String, Integer> map) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, Integer> e : map.entrySet()) {
            if (!first) sb.append(',');
            sb.append(e.getKey()).append(',').append(e.getValue());
            first = false;
        }
        return sb.toString();
    }

    // H-20 (세션 23): getCsvArray / csvJoin 헬퍼 삭제. 게임타입별 String[] 필드 제거와
    // 함께 호출처 전부 사라짐. (playerSpeedExponents 는 별도 헬퍼 parseIntegerMap/
    // serializeIntegerMap 사용)
}
