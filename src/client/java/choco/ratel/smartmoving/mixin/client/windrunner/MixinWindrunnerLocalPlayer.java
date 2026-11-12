package choco.ratel.smartmoving.mixin.client.windrunner;

import choco.ratel.smartmoving.client.windrunner.WindrunnerWallRun;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LocalPlayer.class)
public abstract class MixinWindrunnerLocalPlayer {
    @Shadow private boolean isSprintingPossible(boolean flying) { throw new AssertionError(); }

    // Only the wall hit is waived: running out of food or letting go of forward still ends the sprint.
    @Inject(method = "shouldStopRunSprinting", at = @At("RETURN"), cancellable = true)
    private void windrunner$keepWallSprint(CallbackInfoReturnable<Boolean> cir) {
        LocalPlayer self = (LocalPlayer) (Object) this;
        if (cir.getReturnValueZ() && isSprintingPossible(self.getAbilities().flying) && self.input.hasForwardImpulse()
                && WindrunnerWallRun.keepsSprintIntoWall(self)) {
            cir.setReturnValue(false);
        }
    }
}
