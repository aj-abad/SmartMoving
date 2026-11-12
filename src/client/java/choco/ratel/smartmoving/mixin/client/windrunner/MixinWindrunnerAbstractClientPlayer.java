package choco.ratel.smartmoving.mixin.client.windrunner;

import choco.ratel.smartmoving.windrunner.WindrunnerMoves;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * View and hand bobbing step with walked distance, which a ground dash's slide piles up at several times
 * a sprint's rate; while the dash is on the ground the local view takes much shorter steps.
 */
@Mixin(AbstractClientPlayer.class)
public abstract class MixinWindrunnerAbstractClientPlayer {
    private static final float GROUND_DASH_BOB = 0.3F;

    @ModifyVariable(method = "addWalkedDistance", at = @At("HEAD"), argsOnly = true)
    private float windrunner$slowDashBob(float distance) {
        AbstractClientPlayer self = (AbstractClientPlayer) (Object) this;
        return self instanceof LocalPlayer && self.onGround() && WindrunnerMoves.of(self).groundDashing()
                ? distance * GROUND_DASH_BOB : distance;
    }
}
