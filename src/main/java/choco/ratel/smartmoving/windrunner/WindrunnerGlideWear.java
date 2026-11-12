package choco.ratel.smartmoving.windrunner;

/** Durability costs for loaded fabric, expressed in blocks per tick at 20 Hz. */
public final class WindrunnerGlideWear {
    public static final int DURABILITY = 240;
    public static final int PASSIVE_INTERVAL = 100;
    public static final int OVERSPEED_INTERVAL = 20;
    public static final double OVERSPEED = 1.25;

    /** Each glide boost strains the membranes once. */
    public static final int BOOST = 1;

    private static final double SEVERE_OVERSPEED = 1.75;

    private WindrunnerGlideWear() {}

    /** Every deployment flexes the membranes; a severe-speed opening adds stress wear. */
    public static int deployment(double airspeed) {
        return Double.isFinite(airspeed) && airspeed > SEVERE_OVERSPEED ? 2 : 1;
    }


    /** Loaded fabric wears slowly, with an extra cost for each second spent overspeed. */
    public static int periodic(int activeTicks, double airspeed) {
        int wear = activeTicks % PASSIVE_INTERVAL == 0 ? 1 : 0;
        if (activeTicks % OVERSPEED_INTERVAL == 0 && Double.isFinite(airspeed)) {
            if (airspeed > OVERSPEED) wear++;
            if (airspeed > SEVERE_OVERSPEED) wear++;
        }
        return wear;
    }

    /** Recovery keeps one stall episode latched until clean airflow returns. */
    public static boolean deepStall(float stall, float recovery) {
        return stall >= 0.6F || recovery > 0.25F;
    }
}
