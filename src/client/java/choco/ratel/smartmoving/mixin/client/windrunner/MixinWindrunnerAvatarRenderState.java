package choco.ratel.smartmoving.mixin.client.windrunner;

import choco.ratel.smartmoving.client.windrunner.render.WindrunnerRenderState;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(AvatarRenderState.class)
public class MixinWindrunnerAvatarRenderState implements WindrunnerRenderState {
    @Unique private Flight windrunner$flight = Flight.NONE;
    @Override public Flight windrunner$flight() { return windrunner$flight; }
    @Override public void windrunner$flight(Flight flight) { windrunner$flight = flight; }
}
