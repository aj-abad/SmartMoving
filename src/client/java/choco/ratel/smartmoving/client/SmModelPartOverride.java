package choco.ratel.smartmoving.client;

import org.joml.Quaternionf;

/**
 * Interface to bypass vanilla ModelPart ZYX rotation matrix.
 *
 * vanilla ModelPart.rotate is `rotationZYX(roll, yaw, pitch)` hardcoded.
 * During large yaw (±π/2) like sliding/back jump, JOML getEulerAnglesZYX decomposition is inaccurate (gimbal lock).
 * Avoid decomposition loss by applying intended quaternion directly.
 *
 * Usage:
 *   ((SmModelPartOverride)(Object)part).sm_setOverrideQuat(q);
 *   ((SmModelPartOverride)(Object)part).sm_clearOverrideQuat();
 */
public interface SmModelPartOverride {
    void sm_setOverrideQuat(Quaternionf q);
    void sm_clearOverrideQuat();
    boolean sm_hasOverrideQuat();
    Quaternionf sm_getOverrideQuat();
}
