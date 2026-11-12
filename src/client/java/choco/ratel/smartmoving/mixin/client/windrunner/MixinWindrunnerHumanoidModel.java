package choco.ratel.smartmoving.mixin.client.windrunner;

import choco.ratel.smartmoving.client.windrunner.model.WindrunnerWallRunAnimation;
import choco.ratel.smartmoving.client.windrunner.render.WindrunnerRenderState;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Armor models pose themselves from the avatar's state rather than copying the player model, so the
 * wall run poses every humanoid model. The higher priority runs it after SmartMoving's pose.
 */
@Mixin(value = HumanoidModel.class, priority = 1100)
public abstract class MixinWindrunnerHumanoidModel {
    @Inject(method = "setupAnim(Lnet/minecraft/client/renderer/entity/state/HumanoidRenderState;)V", at = @At("TAIL"))
    private void windrunner$wallRunPose(HumanoidRenderState state, CallbackInfo ci) {
        if (!(state instanceof AvatarRenderState avatar)) return;
        var run = ((WindrunnerRenderState) avatar).windrunner$wallRun();
        if (run.weight() > 0) WindrunnerWallRunAnimation.apply((HumanoidModel<?>) (Object) this, avatar, run);
    }
}
