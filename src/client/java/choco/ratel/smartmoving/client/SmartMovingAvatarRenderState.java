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
}
