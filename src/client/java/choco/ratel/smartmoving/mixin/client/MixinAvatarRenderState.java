package choco.ratel.smartmoving.mixin.client;

import choco.ratel.smartmoving.client.SmartMovingAvatarRenderState;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Environment(EnvType.CLIENT)
@Mixin(AvatarRenderState.class)
public class MixinAvatarRenderState implements SmartMovingAvatarRenderState {
    @Unique private AbstractClientPlayer sm_player;
    @Unique private float sm_tickDelta;

    @Override
    public AbstractClientPlayer sm_getPlayer() {
        return sm_player;
    }

    @Override
    public void sm_setPlayer(AbstractClientPlayer player) {
        sm_player = player;
    }

    @Override
    public float sm_getTickDelta() {
        return sm_tickDelta;
    }

    @Override
    public void sm_setTickDelta(float tickDelta) {
        sm_tickDelta = tickDelta;
    }
}
