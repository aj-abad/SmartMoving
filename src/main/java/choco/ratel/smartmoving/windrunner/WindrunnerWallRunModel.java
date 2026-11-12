package choco.ratel.smartmoving.windrunner;

import net.minecraft.world.phys.Vec3;

/** The boots' wall run as plain math, shared by the client controls and the simulation test. */
public final class WindrunnerWallRunModel {
    public static final int NONE = 0, UP = 1, ALONG = 2;
    /** Hits at or past this angle to the wall's face run up it; at or under {@link #ALONG_MAX} run along. */
    public static final double UP_MIN = 70, ALONG_MAX = 40;
    /** A run up starts at this speed and loses {@link #UP_DECEL} a tick, topping out about four blocks up. */
    public static final double UP_SPEED = 0.4, UP_DECEL = 0.02;
    public static final double ALONG_SPEED = 0.32, ALONG_SINK = 0.06;
    public static final int ALONG_TICKS = 30;
    /** Pressing into the wall keeps the collision, and so the sprint, alive every tick. */
    public static final double WALL_PRESS = 0.08;
    public static final double KICK_AWAY = 0.4, KICK_LIFT = 0.5;
    /** Reaching the top: the climb's least speed once the head is clear, and the push onto it. */
    public static final double CREST_LIFT = 0.35, CREST_PUSH = 0.25;

    private WindrunnerWallRunModel() {}

    /** Degrees between a horizontal heading and the wall's face; {@code into} points from the player into the wall. */
    public static double incidence(Vec3 heading, Vec3 into) {
        double length = heading.horizontalDistance();
        if (length < 1.0E-6) return 0;
        double dot = (heading.x * into.x + heading.z * into.z) / length;
        return Math.toDegrees(Math.asin(Math.max(-1, Math.min(1, dot))));
    }

    public static int classify(double incidence) {
        if (incidence >= UP_MIN) return UP;
        if (incidence > 0 && incidence <= ALONG_MAX) return ALONG;
        return NONE;
    }

    public static double upSpeed(int tick) { return UP_SPEED - UP_DECEL * tick; }

    /** Along-wall unit direction of {@code heading}, or zero when it points straight at the wall. */
    public static Vec3 tangent(Vec3 heading, Vec3 into) {
        double dot = heading.x * into.x + heading.z * into.z;
        Vec3 along = new Vec3(heading.x - into.x * dot, 0, heading.z - into.z * dot);
        double length = along.horizontalDistance();
        return length < 1.0E-6 ? Vec3.ZERO : along.scale(1 / length);
    }

    public static Vec3 along(Vec3 velocity, Vec3 tangent, Vec3 into) {
        double speed = Math.max(velocity.x * tangent.x + velocity.z * tangent.z, ALONG_SPEED);
        return new Vec3(tangent.x * speed + into.x * WALL_PRESS, -ALONG_SINK, tangent.z * speed + into.z * WALL_PRESS);
    }

    /** Once the head is over the top, the climb keeps at least crest speed until the feet clear it too. */
    public static Vec3 up(int tick, Vec3 into, boolean headClear) {
        double speed = headClear ? Math.max(upSpeed(tick), CREST_LIFT) : upSpeed(tick);
        return new Vec3(into.x * WALL_PRESS, speed, into.z * WALL_PRESS);
    }

    /** Away from the wall and up, keeping whatever the run carried along the wall. */
    public static Vec3 kick(Vec3 velocity, Vec3 into, double jumpScale) {
        double pressing = velocity.x * into.x + velocity.z * into.z;
        double alongX = velocity.x - into.x * pressing, alongZ = velocity.z - into.z * pressing;
        return new Vec3(alongX - into.x * KICK_AWAY, KICK_LIFT * jumpScale, alongZ - into.z * KICK_AWAY);
    }
}
