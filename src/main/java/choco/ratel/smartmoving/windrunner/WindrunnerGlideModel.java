package choco.ratel.smartmoving.windrunner;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import choco.ratel.smartmoving.windrunner.WindrunnerGlidePhysics.Environment;
import choco.ratel.smartmoving.windrunner.WindrunnerGlidePhysics.Input;
import choco.ratel.smartmoving.windrunner.WindrunnerGlidePhysics.State;

/** Responsive arcade glide; turns and pull-outs spend the energy in the airflow. */
public final class WindrunnerGlideModel {
    public static final float TRIM = 10;
    private static final double AREA = 0.13;
    private static final double STALL_ANGLE = 32;
    // A clean membrane: low parasitic and induced drag give about 5 blocks forward per block down,
    // and TRIM sits at the resulting best-glide angle. Overspeed drag rises steeply enough that a
    // sustained dive still tops out near 30 blocks/s.
    private static final double PARASITIC_DRAG = 0.07, INDUCED_DRAG = 0.14, OVERSPEED_DRAG = 0.6;
    // Degrees per 20 Hz tick. Brief acceleration softens input edges without sluggish lag.
    private static final float PITCH_RATE = 3.8F, ROLL_RATE = 6.5F, BANK_ANGLE = 65;
    // A boost is a one-off kick along the nose on each fresh press of the dash key, then a short
    // cooldown; holding the key adds nothing, so speed comes from timing presses, not holding one.
    public static final double BOOST = 0.4;
    /**
     * The opening brake: for this many ticks after the robes open, airspeed above the opening speed
     * keeps only {@link #BRAKE_KEEP} of itself each tick, so a fast fall is caught within half a second.
     */
    public static final int BRAKE_TICKS = 10;
    public static final double BRAKE_KEEP = 0.75;
    public static final int BOOST_COOLDOWN = 15;

    public record Flow(double attack, double forward, float authority, float stall, double lift, double drag) {}

    public static Flow sample(Vec3 velocity, State s, Environment air) {
        Vec3 relative = velocity.subtract(air.wind());
        Vec3 nose = WindrunnerGlidePhysics.nose(s.pitch, s.yaw);
        Vec3 up = WindrunnerGlidePhysics.up(s.pitch, s.yaw, s.roll);
        double forward = relative.dot(nose);
        double attack = relative.lengthSqr() < 1.0E-8 ? 0 : Math.toDegrees(Math.atan2(-relative.dot(up), forward));
        double angle = Math.abs(attack);
        float stall = (float) Mth.clamp((angle - STALL_ANGLE) / 24, 0, 1);
        float authority = (float) Mth.clamp(Math.max(0, forward) * Math.max(0, forward) * air.density() / 0.49, 0, 1);
        double cl = 3.5 * Math.toRadians(Mth.clamp(attack, -STALL_ANGLE, STALL_ANGLE));
        if (angle > STALL_ANGLE) cl *= Math.exp(-(angle - STALL_ANGLE) / 24);
        double sideslip = relative.lengthSqr() < 1.0E-8 ? 0
                : 1 - Math.min(1, forward * forward / relative.lengthSqr());
        double overspeed = Math.max(0, relative.length() - 1.1);
        double cd = PARASITIC_DRAG + INDUCED_DRAG * cl * cl + 0.6 * Math.toRadians(Math.max(0, angle - STALL_ANGLE))
                + sideslip * 0.15 + overspeed * overspeed * OVERSPEED_DRAG;
        return new Flow(attack, forward, authority, stall, cl, cd);
    }

    public static Vec3 tick(Vec3 velocity, State s, Input input, Environment air) {
        if (air.density() <= 0) return velocity.add(0, -WindrunnerGlidePhysics.GRAVITY, 0);
        velocity = brake(velocity, s, air);
        Flow flow = sample(velocity, s, air);
        if (flow.stall >= 0.6) s.recovery = 1;
        else if (flow.stall < 0.05 && flow.authority > 0.5 && Math.abs(flow.attack) < 15) {
            s.recovery = Math.max(0, s.recovery - (float) Math.max(0, flow.forward) * 0.065F);
        }
        float control = flow.authority * (1 - flow.stall * 0.45F) * (1 - s.recovery * 0.25F);
        float tail = (float) Mth.clamp(velocity.subtract(air.wind()).lengthSqr() * air.density() / 0.25, 0, 1);
        float trim = (float) Mth.clamp((flow.attack - TRIM) * 0.14, -2.8, 2.8) * tail;
        float pitchTarget = input.pitch() * PITCH_RATE * control;
        // Passive stability can lower a stalled nose, but cannot pull out without airspeed.
        pitchTarget += trim * (input.pitch() == 0 ? 1 : flow.stall);
        float rollTarget = Mth.clamp((-input.turn() * BANK_ANGLE - s.roll) * 0.30F, -ROLL_RATE, ROLL_RATE) * control;
        float yawTarget = -s.roll * 0.065F * control;
        WindrunnerGlidePhysics.rotate(s, pitchTarget, rollTarget, yawTarget,
                0.5F, 0.9F, 1.8F, 0.4F, 0.6F, -75, 85, 70);
        velocity = boost(velocity, s, input);
        flow = sample(velocity, s, air);
        return WindrunnerGlidePhysics.forces(velocity, WindrunnerGlidePhysics.up(s.pitch, s.yaw, s.roll),
                air, AREA, flow.lift, flow.drag, WindrunnerGlidePhysics.GRAVITY * 4.5);
    }

    /** Sheds airspeed along the airflow, so the attitude the opening set up is left as it was. */
    static Vec3 brake(Vec3 velocity, State s, Environment air) {
        if (s.brakeTicks <= 0) return velocity;
        s.brakeTicks--;
        Vec3 relative = velocity.subtract(air.wind());
        double speed = relative.length(), target = WindrunnerGlidePhysics.DEPLOY_MIN_SPEED;
        if (speed <= target) return velocity;
        return relative.scale((target + (speed - target) * BRAKE_KEEP) / speed).add(air.wind());
    }

    static Vec3 boost(Vec3 velocity, State s, Input input) {
        if (s.boostCooldown > 0) s.boostCooldown--;
        s.boostTicks = input.boost() ? s.boostTicks + 1 : 0;
        if (s.boostTicks != 1 || s.boostCooldown > 0) return velocity;
        s.boostCooldown = BOOST_COOLDOWN;
        return velocity.add(WindrunnerGlidePhysics.nose(s.pitch, s.yaw).scale(BOOST));
    }

    /** True on the tick a boost fired; the cooldown is only full straight after it was set. */
    public static boolean boosted(State s) { return s.boostTicks == 1 && s.boostCooldown == BOOST_COOLDOWN; }
}
