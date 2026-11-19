package choco.ratel.smartmoving.stat;

/**
 * Tracks player movement data per render tick.
 * Directly ported original SmartStatistics field list (based on animation_system.md).
 * Calculation: corresponds to afterMoveEntityWithHeading → called after ClientPlayerEntity.move() TAIL.
 */
public class SmartStatistics {

    // ── Cumulative distance ────────────────────────────────────
    public float totalHorizontalDistance;   // equivalent to limbSwing (input to animateArmSwinging)
    public float totalVerticalDistance;
    public float totalDistance;

    // ── Current speed (0~1 normalized) ─────────────────────────
    public float currentHorizontalSpeed;         // equivalent to limbSwingAmount
    public float currentHorizontalSpeedFlattened;  // ← unused (replaced by FIR history).
    public float currentVerticalSpeed;
    public float currentSpeed;

    // 🔴 (2026-05-04 user request — "exact 1:1 mapping"): currentHorizontalSpeedFlattened
    //   = exact 1:1 with original SmartStatistics.getCurrentHorizontalSpeedFlattened(pt, -1).
    //   Original = average of datas[10] history's getCurrentHorizontalSpeed().
    //   Each data's getCurrentHorizontalSpeed = min(1, prevLegYaw + (legYaw-prev)*pt).
    //   Previous EMA(0.5) mapping → FIR 10-tick average.
    private static final int FLATTENED_HISTORY_SIZE = 10;
    private final float[] flattenedLegYawHistory = new float[FLATTENED_HISTORY_SIZE];
    private final float[] flattenedPrevLegYawHistory = new float[FLATTENED_HISTORY_SIZE];
    private final boolean[] flattenedHistoryReady = new boolean[FLATTENED_HISTORY_SIZE];
    private int flattenedHistoryIndex = -1;

    // ── 🔴 prev fields (Flying Phase / session 48): for partial tick lerp interpolation ────
    //   Original SmartStatisticsData (SmartRender) has two fields prevLegYaw / legYaw + per-frame
    //   getter applies `prevLegYaw + (legYaw - prevLegYaw) * partialTicks` lerp.
    //   Per-tick EMA update + per-frame lerp processing = 60Hz smoothness.
    //   Previous 1.21.1 mapping used single field (current only) → per-tick 20Hz stuttering = user report
    //   "not as smooth as original" direct cause. Added prev fields + lerp getter mapping.
    public float prevCurrentHorizontalSpeed;
    public float prevCurrentVerticalSpeed;
    public float prevCurrentSpeed;
    public float prevTotalHorizontalDistance;
    public float prevTotalVerticalDistance;
    public float prevTotalDistance;
    /** 🔴 (2026-05-03 crawl) prev for currentHorizontalSpeedFlattened partial ticks lerp interpolation. */
    public float prevCurrentHorizontalSpeedFlattened;

    /**
     * 🔴 (2026-05-22, fix #149) monotonic guard to block dist backward jump at tick boundary.
     *
     * Cause (measured from logs):
     *   At each tick boundary (= render frame partial=0.000 moment) getTotalHorizontalDistance result
     *   backward jump by 1 cSpd compared to previous frame (partial≈0.82).
     *   Example: tT=384.82 dist=34.294 → tT=385.00 dist=33.855 (-0.439). tT=385.16 dist=34.476 (forward resumes).
     *
     * Formula reverse engineering: at partial=0.000 frame call `prevTotalHorizontalDistance` field is 1 tick stale
     *   (= prev_total_384 value. expected = total_384). That is, vanilla 1.21.1 render frame partial=0.000
     *   moment is before entity tick (= Entity.move() TAIL calculate() call) call sequence.
     *
     * Result: swim arm formula `sawtooth(dist × 0.5) × walk` dist backward → input.pitch backward →
     *   user report "arm rotation plicking" is the real cause.
     *
     * Solution: if dist getter result per frame is less than previous call result (= backward) keep previous value.
     *   → only tick boundary frame stays stationary 1 frame (≈ 50ms). visually natural.
     *
     * Regression guard: in all branches (swim/dive/flying/sliding/crawling/ceilingclimbing)
     *   dist accumulates monotonically (SmartStatistics.reset() not called). guard effect = block backward only.
     */
    private float lastDistResult = 0f;
    private float lastHorizontalDistResult = 0f;
    private float lastVerticalDistResult = 0f;

    // ── Per-frame movement amount ──────────────────────────────
    public double horizontalDistance;
    public double verticalDistance;
    public double distance;

    // ── Angle (radians) ────────────────────────────────────────
    public float currentCameraAngle;
    public float currentVerticalAngle;
    public float currentHorizontalAngle;
    /**
     * Original: SmartRenderRender.statistics.prevHorizontalAngle — previous calculated value.
     * Used in fallback path when atan(xDiff/zDiff) is NaN (xDiff=0 && zDiff=0).
     * Initial value NaN → use currentCameraAngle, then use prev afterwards.
     */
    public float prevHorizontalAngle = Float.NaN;

    // ── Miscellaneous ──────────────────────────────────────────
    public float smallOverGroundHeight;

    /**
     * Called per tick after move() TAIL — updates movement statistics with position delta.
     * Original: SmartStatisticsPlayerBase.afterMoveEntityWithHeading() → statistics.calculateAllStats(false)
     *
     * prevX/prevY/prevZ are saved as current position in tickMovement() HEAD,
     * so after move() delta = currentPos - prevPos = actual movement amount this tick.
     */
    public void calculate(double prevX, double prevY, double prevZ,
                          double x, double y, double z, float yawDegrees) {
        double diffX = x - prevX;
        double diffY = y - prevY;
        double diffZ = z - prevZ;

        horizontalDistance = Math.sqrt(diffX * diffX + diffZ * diffZ);
        verticalDistance = Math.abs(diffY);
        distance = Math.sqrt(diffX * diffX + diffY * diffY + diffZ * diffZ);

        // 🔴 (session 48): update prev fields — save previous values before applying per-tick EMA (for lerp interpolation).
        //   Original SmartStatisticsDatas.initialize: prevLegYaw = previous.legYaw, legYaw = previous.legYaw.
        //   1.21.1 equivalent: prev = current at tick start (= previous tick EMA result).
        prevCurrentHorizontalSpeed = currentHorizontalSpeed;
        prevCurrentVerticalSpeed   = currentVerticalSpeed;
        prevCurrentSpeed           = currentSpeed;
        prevTotalHorizontalDistance = totalHorizontalDistance;
        prevTotalVerticalDistance   = totalVerticalDistance;
        prevTotalDistance           = totalDistance;
        prevCurrentHorizontalSpeedFlattened = currentHorizontalSpeedFlattened;

        // Original: SmartStatisticsData.calcualte() — distance *= 4F; legYaw += (dist - legYaw) * 0.4F
        // legYaw = EMA(rawDistance * 4, factor=0.4). Normal walk (~0.22 b/t) → ~0.88, flying (~0.3 b/t) → 1.0(clamp).
        // Used in sm_setupTransforms/sm_animateFlying as walkFactor = min(1, currentSpeed).
        currentHorizontalSpeed += ((float) horizontalDistance * 4f - currentHorizontalSpeed) * 0.4f;
        currentVerticalSpeed   += ((float) verticalDistance   * 4f - currentVerticalSpeed)   * 0.4f;
        currentSpeed           += ((float) distance           * 4f - currentSpeed)           * 0.4f;

        // 🔴 (session 57): total accumulation 1:1 correction — original SmartStatisticsData.calcualte L50:
        //     `total += legYaw;` (legYaw = EMA result, not raw distance!)
        //   Previous 1.21.1 mapping `totalDistance += raw distance` was wrong → pendulum cycle at 1/2 speed
        //   of original (raw distance ~2x EMA result) → arm/leg pendulum movement detail difference.
        //   User report "arm/leg pendulum movement detail feels different from original" direct cause.
        //   Correction: total += currentSpeed (EMA result, before clamp).

        // 🔴 (2026-05-04 user request "exact 1:1") — save Flattened FIR 10-tick history.
        //   Original SmartStatistics.calculateAllStats: new entry at datas[currentDataIndex] +
        //     initialize with prev datas. Each entry's prevLegYaw/legYaw initialized to previous entry's legYaw
        //     then updated in calcualte().
        //   Our mapping uses single currentHorizontalSpeed field, so save prev/curr values per tick
        //     in ring buffer. getCurrentHorizontalSpeedFlattened(pt) averages history.
        flattenedHistoryIndex++;
        if (flattenedHistoryIndex >= FLATTENED_HISTORY_SIZE) flattenedHistoryIndex = 0;
        flattenedPrevLegYawHistory[flattenedHistoryIndex] = prevCurrentHorizontalSpeed;
        flattenedLegYawHistory[flattenedHistoryIndex] = currentHorizontalSpeed;
        flattenedHistoryReady[flattenedHistoryIndex] = true;

        // Original SmartRenderRender L95: currentCameraAngle = rotationYaw / RadiantToAngle (= Math.toRadians)
        currentCameraAngle = (float) Math.toRadians(yawDegrees);

        // 🔴 (session 53): vertical movement angle (radians) 1:1 correction.
        //   Original SmartRenderRender L77-L79:
        //     currentVerticalAngle = (float)Math.atan(yDiff / horizontalDistance);
        //     if(Float.isNaN(currentVerticalAngle)) currentVerticalAngle = Quarter;
        //   - yDiff > 0, h == 0: atan(+∞) = +π/2 (moving up).
        //   - yDiff < 0, h == 0: atan(-∞) = -π/2 (moving down). ★ sign matter
        //   - yDiff == 0, h == 0: atan(0/0) = NaN → fallback +π/2.
        //   = atan2(diffY, h) (h≥0) equivalent + +π/2 fallback at (0,0).
        //   Previous 1.21.1 mapping `(h > 1e-4) ? atan2 : +π/2` guard was wrong — always +π/2 when h small
        //   → mouse down + W move verticalAngle sign reversed (= body tilts up incorrectly).
        //   User report "mouse up/down → W movement not tilting body in movement direction" direct cause.
        if (horizontalDistance < 1e-9 && Math.abs(diffY) < 1e-9) {
            currentVerticalAngle = (float) (Math.PI / 2f);  // 0/0 fallback
        } else {
            currentVerticalAngle = (float) Math.atan2(diffY, horizontalDistance);
        }

        // Original SmartRenderRender L100-111: -atan(xDiff/zDiff). When NaN (xDiff=0&&zDiff=0)
        //   if prevHorizontalAngle NaN → use currentCameraAngle, else use prev.
        //   If normal value and zDiff<0 add +π correction. Minecraft rotationYaw convention (+Z=0, +X=-π/2, -X=+π/2).
        float newH = (float) -Math.atan(diffX / diffZ);
        if (Float.isNaN(newH)) {
            newH = Float.isNaN(prevHorizontalAngle) ? currentCameraAngle : prevHorizontalAngle;
        } else if (diffZ < 0) {
            newH += (float) Math.PI;
        }
        prevHorizontalAngle = newH;
        currentHorizontalAngle = newH;

        // 🔴 (session 57): exact 1:1 — total += EMA result (currentSpeed, not raw distance).
        //   Original SmartStatisticsData.calcualte L49-L50:
        //     legYaw += (distance - legYaw) * 0.4F;   // EMA update (handled above)
        //     total += legYaw;                          // accumulate = EMA result
        totalHorizontalDistance += currentHorizontalSpeed;
        totalVerticalDistance   += currentVerticalSpeed;
        totalDistance           += currentSpeed;
    }

    // ── 🔴 partial tick lerp getter (Flying Phase / session 48): 60Hz smoothness interpolation ───
    //   Original SmartStatisticsData.getCurrentSpeed:
    //     return Math.min(1.0F, prevLegYaw + (legYaw - prevLegYaw) * renderPartialTicks);
    //   Original SmartStatisticsData.getTotalDistance:
    //     return total - legYaw * (1.0F - renderPartialTicks);
    //   Per-frame call interpolates between prev/current at partial tick ratio = smoothness.

    public float getCurrentSpeed(float partialTicks) {
        return Math.min(1.0F, prevCurrentSpeed + (currentSpeed - prevCurrentSpeed) * partialTicks);
    }

    /**
     * 🔴 (2026-05-04 user request "exact 1:1") original SmartStatistics.getCurrentHorizontalSpeedFlattened
     *   (partialTicks, -1) = data history average + partialTicks interpolation. FIR 10-tick.
     *   Each history entry's getCurrentSpeed = min(1.0, prevLegYaw + (legYaw-prev)*pt).
     *   Previous EMA(0.5) on EMA(0.4) mapping had transient differences user reported → FIR 10-tick exact 1:1 mapping.
     */
    public float getCurrentHorizontalSpeedFlattened(float partialTicks) {
        if (flattenedHistoryIndex < 0) return 0f;
        float sum = 0f;
        int count = 0;
        for (int i = 0, idx = flattenedHistoryIndex; i < FLATTENED_HISTORY_SIZE; i++, idx--) {
            if (idx < 0) idx = FLATTENED_HISTORY_SIZE - 1;
            if (!flattenedHistoryReady[idx]) break;
            float prevLegYaw = flattenedPrevLegYawHistory[idx];
            float legYaw = flattenedLegYawHistory[idx];
            sum += Math.min(1.0F, prevLegYaw + (legYaw - prevLegYaw) * partialTicks);
            count++;
        }
        return count > 0 ? sum / count : 0f;
    }

    public float getCurrentHorizontalSpeed(float partialTicks) {
        return Math.min(1.0F, prevCurrentHorizontalSpeed
                + (currentHorizontalSpeed - prevCurrentHorizontalSpeed) * partialTicks);
    }

    public float getCurrentVerticalSpeed(float partialTicks) {
        return Math.min(1.0F, prevCurrentVerticalSpeed
                + (currentVerticalSpeed - prevCurrentVerticalSpeed) * partialTicks);
    }

    // 🔴 (2026-05-21, fix #147) sub-tick interpolation formula correction — explicit lerp(p, prev, cur).
    //   User verbatim "arm rotation gets very brief stuttering/stutter/artifact".
    //
    //   Cause:
    //     Existing formula `total - cSpd * (1 - p)` = `prev_total + cSpd * p` (assumes normal sequence).
    //     Assumption: total = prev_total + cSpd (= result of update L172 above).
    //     But when cSpd_field spikes (velocity spike in raw) or sequence race assumption breaks →
    //     `total - cSpd * (1-p)` and `prev_total + cSpd * p` give different results → at tick boundary
    //     partial 0.180 → 0.000 transition causes distance backward jump.
    //   Log validation (sample 22 partial 0.180 dist 1491.352 → sample 23 partial 0.000 dist 1491.235):
    //     With existing formula assumption sample 23 distance = total_1932 - cSpd_1932 = prev_total = total_1931.
    //     sample 22 distance = total_1931 - cSpd_1931 * 0.820 = expected < sample 23 distance.
    //     But log: sample 22 > sample 23 = 0.117 backward.
    //     = cSpd_1931 negative or assumption contradiction = field race / spike likely.
    //
    //   Solution: explicit lerp(p, prev_total_field, total_field).
    //     Normal sequence = equivalent formula. On inconsistency directly use prev_total_field → more robust.
    public float getTotalDistance(float partialTicks) {
        float result = prevTotalDistance + (totalDistance - prevTotalDistance) * partialTicks;
        // 🔴 (2026-05-22, fix #149) monotonic guard — block tick boundary backward jump.
        if (result < lastDistResult - 0.001f) {
            result = lastDistResult;
        }
        lastDistResult = result;
        return result;
    }

    public float getTotalHorizontalDistance(float partialTicks) {
        float result = prevTotalHorizontalDistance
                + (totalHorizontalDistance - prevTotalHorizontalDistance) * partialTicks;
        // 🔴 (2026-05-22, fix #149) monotonic guard — block tick boundary backward jump.
        if (result < lastHorizontalDistResult - 0.001f) {
            result = lastHorizontalDistResult;
        }
        lastHorizontalDistResult = result;
        return result;
    }

    public float getTotalVerticalDistance(float partialTicks) {
        float result = prevTotalVerticalDistance
                + (totalVerticalDistance - prevTotalVerticalDistance) * partialTicks;
        // 🔴 (2026-05-22, fix #149) monotonic guard — block tick boundary backward jump.
        if (result < lastVerticalDistResult - 0.001f) {
            result = lastVerticalDistResult;
        }
        lastVerticalDistResult = result;
        return result;
    }

    public void reset() {
        totalHorizontalDistance = 0;
        totalVerticalDistance = 0;
        totalDistance = 0;
        currentHorizontalSpeed = 0;
        currentHorizontalSpeedFlattened = 0;
        currentVerticalSpeed = 0;
        currentSpeed = 0;
        // 🔴 Flattened FIR history reset.
        flattenedHistoryIndex = -1;
        for (int i = 0; i < FLATTENED_HISTORY_SIZE; i++) flattenedHistoryReady[i] = false;
        prevCurrentHorizontalSpeed = 0;
        prevCurrentVerticalSpeed = 0;
        prevCurrentSpeed = 0;
        prevCurrentHorizontalSpeedFlattened = 0;
        prevTotalHorizontalDistance = 0;
        prevTotalVerticalDistance = 0;
        prevTotalDistance = 0;
        // 🔴 (fix #149) also reset monotonic guard.
        lastDistResult = 0f;
        lastHorizontalDistResult = 0f;
        lastVerticalDistResult = 0f;
        horizontalDistance = 0;
        verticalDistance = 0;
        distance = 0;
        currentCameraAngle = 0;
        currentVerticalAngle = 0;
        currentHorizontalAngle = 0;
        prevHorizontalAngle = Float.NaN;
        smallOverGroundHeight = 0;
    }
}
