package choco.ratel.smartmoving.windrunner;

import choco.ratel.smartmoving.windrunner.WindrunnerGlidePhysics.State;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/** Render-only reconciliation: physics accepts corrections immediately, the view eases them out. */
public final class WindrunnerGlideVisuals {
    private State previous = new State();
    private float yawOffset, pitchOffset, rollOffset;
    private Vec3 positionOffset = Vec3.ZERO, previousPositionOffset = Vec3.ZERO;
    private double speed, previousSpeed;

    public void reset(State state, double airspeed) {
        previous = state.copy();
        yawOffset = pitchOffset = rollOffset = 0;
        positionOffset = previousPositionOffset = Vec3.ZERO;
        speed = previousSpeed = airspeed;
    }

    public void beginTick(State state) {
        previous = state.copy();
        previous.yaw += yawOffset;
        previous.pitch += pitchOffset;
        previous.roll += rollOffset;
        previousPositionOffset = positionOffset;
        // About 90% of an ordinary correction disappears in a quarter second.
        yawOffset *= 0.65F;
        pitchOffset *= 0.65F;
        rollOffset *= 0.65F;
        positionOffset = positionOffset.scale(0.65);
        previousSpeed = speed;
    }

    public void updateSpeed(double airspeed) { speed += (airspeed - speed) * 0.35; }

    public void correct(State before, State after, Vec3 positionDelta, double airspeed) {
        Vec3 offset = positionOffset.add(positionDelta);
        if (offset.lengthSqr() > 16) {
            reset(after, airspeed); // Large relocations must not leave the view far from the hitbox.
            return;
        }
        yawOffset = Mth.wrapDegrees(yawOffset + Mth.wrapDegrees(before.yaw - after.yaw));
        pitchOffset += before.pitch - after.pitch;
        rollOffset += before.roll - after.roll;
        positionOffset = offset;
    }

    public float yaw(State state, float partial) { return Mth.rotLerp(partial, previous.yaw, state.yaw + yawOffset); }
    public float pitch(State state, float partial) { return Mth.lerp(partial, previous.pitch, state.pitch + pitchOffset); }
    public float roll(State state, float partial) { return Mth.lerp(partial, previous.roll, state.roll + rollOffset); }
    public Vec3 positionOffset(float partial) { return previousPositionOffset.lerp(positionOffset, partial); }
    public double airspeed(float partial) { return Mth.lerp(partial, previousSpeed, speed); }
}
