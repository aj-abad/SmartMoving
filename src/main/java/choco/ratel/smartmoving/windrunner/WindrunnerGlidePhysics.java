package choco.ratel.smartmoving.windrunner;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/** Shared 20 Hz wearable aerodynamics. No player, renderer, networking or world dependencies. */
public final class WindrunnerGlidePhysics {
    public static final double GRAVITY = 0.045;
    /** Settled sink used to turn horizontal entry momentum into the initial glide attitude. */
    private static final double DEPLOY_REFERENCE_SINK = 0.15;
    /** A fresh glide opens no steeper than this nose-down (or nose-up), whatever the entry. */
    private static final float DEPLOY_PITCH_DOWN = 5, DEPLOY_PITCH_UP = -10;
    /** Share of the entry speed the opening keeps, and the least it leaves for control authority. */
    private static final double DEPLOY_KEEP = 0.85;
    public static final double DEPLOY_MIN_SPEED = 1.0;

    private WindrunnerGlidePhysics() {}

    public record Environment(Vec3 wind, double density) {
        public static final Environment CALM = new Environment(Vec3.ZERO, 1);
        public static final Environment VACUUM = new Environment(Vec3.ZERO, 0);
    }

    /** {@code boost} is whether the dash key is held; only a fresh press fires a boost. */
    public record Input(float pitch, float turn, boolean boost) {
        public static final Input NONE = new Input(0, 0);
        public Input {
            pitch = Float.isFinite(pitch) ? Mth.clamp(pitch, -1, 1) : 0;
            turn = Float.isFinite(turn) ? Mth.clamp(turn, -1, 1) : 0;
        }
        public Input(float pitch, float turn) { this(pitch, turn, false); }
    }

    /** Angular rates are part of the state, including when reconciling client prediction. */
    public static final class State {
        public float yaw, pitch, roll, yawRate, pitchRate, rollRate;
        public float recovery;
        public int boostTicks, boostCooldown;
        /** Ticks left of the opening brake. */
        public int brakeTicks;

        public State copy() {
            State s = new State();
            s.yaw = yaw; s.pitch = pitch; s.roll = roll;
            s.yawRate = yawRate; s.pitchRate = pitchRate; s.rollRate = rollRate;
            s.recovery = recovery; s.boostTicks = boostTicks; s.boostCooldown = boostCooldown;
            s.brakeTicks = brakeTicks;
            return s;
        }

        /** Deployment reads horizontal momentum for its attitude and never modifies entry velocity. */
        public static State deploy(Vec3 velocity, float lookYaw) {
            State s = new State();
            double horizontal = velocity.horizontalDistance();
            s.yaw = horizontal > 0.05
                    ? (float) Math.toDegrees(Math.atan2(-velocity.x, velocity.z)) : lookYaw;
            s.pitch = Mth.clamp((float) Math.toDegrees(
                    Math.atan2(DEPLOY_REFERENCE_SINK, horizontal)) - WindrunnerGlideModel.TRIM, DEPLOY_PITCH_UP, DEPLOY_PITCH_DOWN);
            // Only a fall faster than the opening speed is braked; slower entries already open at it.
            if (velocity.length() * DEPLOY_KEEP > DEPLOY_MIN_SPEED) s.brakeTicks = WindrunnerGlideModel.BRAKE_TICKS;
            return s;
        }

        /**
         * The robes catch the air: the entry is turned onto the flight path {@code TRIM} below the nose, so
         * the glide opens at its trim angle of attack and passive stability has nothing to push the nose
         * down with. Without this, a mostly downward entry meets the nose at a steep angle and the glide
         * keeps diving for a second or more while it builds speed.
         */
        public static Vec3 deployVelocity(Vec3 entry, State s) {
            double speed = Math.max(entry.length() * DEPLOY_KEEP, DEPLOY_MIN_SPEED);
            return nose(s.pitch + WindrunnerGlideModel.TRIM, s.yaw).scale(speed);
        }
    }

    /** Air thins above y 128, so a glide needs more airspeed for the same support up high. */
    static double density(double altitude) {
        return Mth.clamp(1.0 - (altitude - 128.0) * 0.003, 0.35, 1.0);
    }

    /** Unit vector along the nose for a pitch (positive nose-down) and yaw, in degrees. */
    public static Vec3 nose(float pitch, float yaw) {
        double p = Math.toRadians(pitch), y = Math.toRadians(yaw);
        return new Vec3(-Math.sin(y) * Math.cos(p), -Math.sin(p), Math.cos(y) * Math.cos(p));
    }

    /** Unit vector out of the back of the wing for a pitch, yaw and bank, in degrees. */
    public static Vec3 up(float pitch, float yaw, float roll) {
        double p = Math.toRadians(pitch), y = Math.toRadians(yaw), r = Math.toRadians(roll);
        return new Vec3(-Math.sin(y) * Math.sin(p) * Math.cos(r) + Math.cos(y) * Math.sin(r),
                Math.cos(p) * Math.cos(r),
                Math.cos(y) * Math.sin(p) * Math.cos(r) + Math.sin(y) * Math.sin(r));
    }

    static float angularRate(float rate, float target, float response, float acceleration) {
        float change = (target - rate) * response;
        return rate + Mth.clamp(change, -acceleration, acceleration);
    }

    static void rotate(State s, float pitchTarget, float rollTarget, float yawTarget,
                       float pitchAcceleration, float rollAcceleration) {
        rotate(s, pitchTarget, rollTarget, yawTarget, 0.18F, pitchAcceleration, rollAcceleration,
                0.16F, 0.12F, -65, 75, 55);
    }

    static void rotate(State s, float pitchTarget, float rollTarget, float yawTarget,
                       float response, float pitchAcceleration, float rollAcceleration,
                       float yawResponse, float yawAcceleration, float minPitch, float maxPitch, float maxRoll) {
        s.pitchRate = angularRate(s.pitchRate, pitchTarget, response, pitchAcceleration);
        s.rollRate = angularRate(s.rollRate, rollTarget, response, rollAcceleration);
        s.yawRate = angularRate(s.yawRate, yawTarget, yawResponse, yawAcceleration);
        s.pitch = Mth.clamp(s.pitch + s.pitchRate, minPitch, maxPitch);
        s.roll = Mth.clamp(s.roll + s.rollRate, -maxRoll, maxRoll);
        s.yaw = Mth.wrapDegrees(s.yaw + s.yawRate);
        if (s.pitch == minPitch || s.pitch == maxPitch) s.pitchRate = 0;
        if (Math.abs(s.roll) == maxRoll) s.rollRate = 0;
    }

    /**
     * Lift rotates the air-relative velocity without adding kinetic energy. Drag removes energy;
     * gravity supplies it. Substeps keep a fast entry stable.
     */
    static Vec3 forces(Vec3 velocity, Vec3 up, Environment air, double area,
                       double liftCoefficient, double dragCoefficient, double maxLift) {
        if (air.density <= 0) return velocity.add(0, -GRAVITY, 0);
        double dt = 0.25;
        for (int i = 0; i < 4; i++) {
            velocity = velocity.add(0, -GRAVITY * dt * 0.5, 0);
            Vec3 relative = velocity.subtract(air.wind);
            double speed = relative.length();
            if (speed > 1.0E-7) {
                Vec3 track = relative.scale(1 / speed);
                Vec3 perpendicular = up.subtract(track.scale(up.dot(track))).normalize();
                double pressure = area * air.density * speed * speed;
                double lift = Mth.clamp(pressure * liftCoefficient, -maxLift, maxLift);
                double angle = lift * dt / speed;
                if (perpendicular.lengthSqr() > 0.5) {
                    relative = track.scale(Math.cos(angle)).add(perpendicular.scale(Math.sin(angle))).scale(speed);
                }
                // The analytic quadratic-drag step cannot reverse velocity, even on a fast entry.
                relative = relative.scale(1 / (1 + area * air.density * Math.max(0, dragCoefficient) * speed * dt));
            }
            velocity = relative.add(air.wind).add(0, -GRAVITY * dt * 0.5, 0);
        }
        return velocity;
    }

    public static float impactDamage(Vec3 intended, Vec3 actual, boolean ground, boolean wall) {
        double vertical = ground ? Math.max(0, -intended.y) : 0;
        double horizontal = wall ? Math.max(0, intended.horizontalDistance() - actual.horizontalDistance()) : 0;
        // Walls take vanilla's elytra crash rule (lost speed x10 - 3): squared, a cruise-speed hit was fatal.
        return (float) Math.max(0, Math.max(vertical * vertical * 18 - 3, horizontal * 10 - 3));
    }
}
