package choco.ratel.smartmoving.client;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.player.AbstractClientPlayer;

/**
 * Smart Moving data attached to Minecraft 26.2's detached avatar render state.
 *
 * <p>The renderer no longer passes the player entity into model animation methods,
 * so the mixins retain the source player and extraction partial tick on the state
 * object that is submitted to the renderer.</p>
 */
@Environment(EnvType.CLIENT)
public interface SmartMovingAvatarRenderState {
    AbstractClientPlayer sm_getPlayer();

    void sm_setPlayer(AbstractClientPlayer player);

    float sm_getTickDelta();

    void sm_setTickDelta(float tickDelta);

    /**
     * Body yaw (degrees) the renderer actually used, after Smart Moving's overrides. Set in
     * setupRotations and read by the model's setupAnim, which 26.3 runs later (deferred submit).
     */
    float sm_getBodyYaw();

    void sm_setBodyYaw(float bodyYaw);
}
