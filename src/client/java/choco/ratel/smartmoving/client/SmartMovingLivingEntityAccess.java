package choco.ratel.smartmoving.client;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

/** Access to the current client interpolation target for a remote living entity. */
@Environment(EnvType.CLIENT)
public interface SmartMovingLivingEntityAccess {
    double sm_getServerX();

    double sm_getServerY();

    double sm_getServerZ();

    void sm_setServerY(double value);

    int sm_getBodyTrackingIncrements();

    void sm_setBodyTrackingIncrements(int value);
}
