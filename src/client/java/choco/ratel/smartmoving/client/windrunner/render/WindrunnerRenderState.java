package choco.ratel.smartmoving.client.windrunner.render;

/** Immutable flight data captured during native avatar render-state extraction. */
public interface WindrunnerRenderState {
    record Flight(boolean equipped, int mode, float yaw, float pitch, float roll,
                  float airspeed, float pitchRate, float rollRate, float deployment) {
        public static final Flight NONE = new Flight(false, 0, 0, 0, 0, 0, 0, 0, 0);
    }
    Flight windrunner$flight();
    void windrunner$flight(Flight flight);
}
