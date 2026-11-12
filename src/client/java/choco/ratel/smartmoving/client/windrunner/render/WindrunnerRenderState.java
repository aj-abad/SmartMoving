package choco.ratel.smartmoving.client.windrunner.render;

/** Immutable flight data captured during native avatar render-state extraction. */
public interface WindrunnerRenderState {
    record Flight(boolean equipped, int mode, float yaw, float pitch, float roll,
                  float airspeed, float pitchRate, float rollRate, float deployment) {
        public static final Flight NONE = new Flight(false, 0, 0, 0, 0, 0, 0, 0, 0);
    }

    /** A wall run's pose: {@code weight} blends it in and out, {@code wallOnRight} only matters running along. */
    record WallRun(int mode, float weight, boolean wallOnRight) {
        public static final WallRun NONE = new WallRun(0, 0, false);
    }

    Flight windrunner$flight();
    void windrunner$flight(Flight flight);
    WallRun windrunner$wallRun();
    void windrunner$wallRun(WallRun wallRun);
}
