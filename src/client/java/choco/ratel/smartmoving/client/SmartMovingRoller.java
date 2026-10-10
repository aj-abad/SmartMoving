package choco.ratel.smartmoving.client;

import choco.ratel.smartmoving.config.SmartMovingConfig;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.Vec3;

/**
 * Landing roll (prototype, docs/research_movement_prototypes.md).
 *
 * A dodge key press while airborne arms a roll instead of being dropped. On the next landing the
 * player tumbles in the pressed direction, keeps some momentum and takes reduced fall damage
 * (server side, see MixinPlayerEntity.sm_rollFallDamage). A fall too short to roll turns the
 * buffered press into a regular dodge on the landing tick.
 *
 * Flow per tick:
 *   handleJumping (dodge key, airborne)  → armRoll
 *   tickEssential                        → tickArmed  (peak fall tracking, landing → startRoll)
 *   sm_travel_client HEAD                → handleRolling (owns the motion, cancels vanilla travel)
 */
@Environment(EnvType.CLIENT)
public final class SmartMovingRoller {

    private SmartMovingRoller() {}

    /** Horizontal speed kept per tick while rolling (0.86^10 ≈ 0.22 of the start speed). */
    private static final double ROLL_DECAY = 0.86D;

    /** True while any state that must never turn into a roll is active. */
    private static boolean isBlocked(LocalPlayer player, SmartMovingClientState sm) {
        return sm.isRolling || sm.isVaulting
                || sm.isClimbing || sm.isChainClimbing || sm.isCrawlClimbing || sm.isCeilingClimbing
                || sm.isSwimming_sm || sm.isDiving || player.isInWater() || player.isInLava()
                || sm.isFlying || player.getAbilities().flying || player.isFallFlying()
                || sm.isHeadJumping || sm.isSliding || sm.isCrawling
                || player.isSleeping() || player.isPassenger();
    }

    /**
     * Dodge key pressed while airborne: remember the direction and arm the roll for
     * {@code rollBufferTicks}. Direction follows the dodge convention (0° = forward); with no
     * movement key held the roll goes forward.
     */
    public static void armRoll(LocalPlayer player, SmartMovingClientState sm, SmartMovingConfig cfg,
                               int forward, int right) {
        if (!cfg.enabled || !cfg.roll) return;
        if (isBlocked(player, sm)) return;

        int relAngle = 0;
        if (forward != 0 || right != 0) {
            relAngle = Math.round((float) Math.toDegrees(Math.atan2(right, forward)) / 45F) * 45;
        }
        sm.rollArmedTicks = Math.max(1, (int) Math.ceil(cfg.rollBufferTicks));
        sm.rollArmForward = forward;
        sm.rollArmRight = right;
        sm.rollYaw = wrapYaw(player.getYRot() + Math.floorMod(relAngle, 360));
        sm.rollPeakFall = peakFallSample(player);
    }

    /**
     * Runs every tick from tickEssential. Keeps the largest fall distance seen while armed (vanilla
     * zeroes fallDistance in the landing move before we get here), counts the buffer down, and on
     * landing either starts the roll or fires the buffered dodge.
     */
    public static void tickArmed(LocalPlayer player, SmartMovingClientState sm, SmartMovingConfig cfg) {
        if (sm.rollArmedTicks <= 0) return;

        sm.rollPeakFall = Math.max(sm.rollPeakFall, peakFallSample(player));

        // Anything that catches the player (climbing, water, flight) cancels the buffered press.
        if (isBlocked(player, sm) || player.onClimbable()) {
            sm.rollArmedTicks = 0;
            return;
        }

        if (player.onGround()) {
            sm.rollArmedTicks = 0;
            if (cfg.roll && sm.rollPeakFall >= cfg.rollMinFallDistance) {
                startRoll(player, sm, cfg);
            } else {
                // Too short to roll: the press becomes an ordinary dodge on the landing tick.
                SmartMovingJumper.tryKeyboundDodge(player, sm, cfg, sm.rollArmForward, sm.rollArmRight);
            }
            return;
        }

        sm.rollArmedTicks--;
    }

    private static void startRoll(LocalPlayer player, SmartMovingClientState sm, SmartMovingConfig cfg) {
        sm.isRolling = true;
        sm.rollDuration = Math.max(2, (int) Math.ceil(cfg.rollDurationTicks));
        sm.rollTicks = sm.rollDuration;
        sm.rollStartTick = player.tickCount;
        sm.rollVy = 0D;

        double speed = cfg.rollSpeedFactor * SmartMovingMover.getCombinedSpeedFactor(player, cfg);
        double yawRad = Math.toRadians(sm.rollYaw);
        player.setDeltaMovement(-Math.sin(yawRad) * speed, 0D, Math.cos(yawRad) * speed);
        player.fallDistance = 0D;
    }

    /**
     * Owns the motion while a roll plays. Movement input is ignored (the roll is committed),
     * horizontal speed decays, gravity still applies so a roll off a ledge keeps falling.
     *
     * @return true when the roll handled this tick (caller cancels vanilla travel)
     */
    public static boolean handleRolling(LocalPlayer player, SmartMovingClientState sm) {
        if (!sm.isRolling) return false;
        SmartMovingConfig cfg = SmartMovingConfig.Config;
        if (!cfg.enabled || !cfg.roll) {
            endRoll(sm, cfg);
            return false;
        }

        Vec3 v = player.getDeltaMovement();
        // Vertical speed is tracked here so a jump press during the roll cannot override it.
        sm.rollVy = (sm.rollVy - player.getAttributeValue(Attributes.GRAVITY)) * 0.98D;
        if (player.onGround() && sm.rollVy < 0D) sm.rollVy = Math.max(sm.rollVy, -0.1D);
        player.setDeltaMovement(v.x * ROLL_DECAY, sm.rollVy, v.z * ROLL_DECAY);
        player.move(MoverType.SELF, player.getDeltaMovement());

        // Vanilla travel is cancelled, so keep its fallDistance bookkeeping by hand.
        if (player.onGround()) {
            player.fallDistance = 0D;
            sm.rollVy = 0D;
        } else if (player.getDeltaMovement().y < 0D) {
            player.fallDistance -= player.getDeltaMovement().y;
        }

        sm.rollTicks--;
        if (sm.rollTicks <= 0 || player.isInWater() || player.getAbilities().flying) {
            endRoll(sm, cfg);
        }
        return true;
    }

    private static void endRoll(SmartMovingClientState sm, SmartMovingConfig cfg) {
        sm.isRolling = false;
        sm.rollTicks = 0;
        // Same ground cooldown as a dodge so roll + dodge cannot chain into a speed boost.
        sm.dodgeCooldown = Math.max(sm.dodgeCooldown, (int) Math.ceil(cfg.angleJumpCooldownTicks));
    }

    /** fallDistance plus the part of this tick's descent vanilla has not added yet. */
    private static double peakFallSample(LocalPlayer player) {
        double vy = player.getDeltaMovement().y;
        return player.fallDistance + (vy < 0D ? -vy : 0D);
    }

    private static float wrapYaw(double yaw) {
        double w = yaw % 360D;
        if (w < 0D) w += 360D;
        return (float) w;
    }
}
