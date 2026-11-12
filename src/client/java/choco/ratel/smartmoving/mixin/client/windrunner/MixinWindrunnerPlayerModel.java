package choco.ratel.smartmoving.mixin.client.windrunner;

import choco.ratel.smartmoving.windrunner.WindrunnerGlide;
import choco.ratel.smartmoving.client.windrunner.model.WindrunnerGlideAnimation;
import choco.ratel.smartmoving.client.windrunner.render.WindrunnerRenderState;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PlayerModel.class)
public class MixinWindrunnerPlayerModel {
    @Inject(method = "setupAnim(Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;)V", at = @At("TAIL"))
    private void windrunner$flightPose(AvatarRenderState state, CallbackInfo ci) {
        int mode = ((WindrunnerRenderState) state).windrunner$flight().mode();
        if (mode == WindrunnerGlide.CLOSED) return;
        PlayerModel model = (PlayerModel) (Object) this;
        if (mode == WindrunnerGlide.GLIDING) {
            WindrunnerGlideAnimation.apply(model, state);
            return;
        }
        model.body.xRot = 0;
        model.rightArm.xRot = model.leftArm.xRot = -2.7F;
        model.rightArm.yRot = model.leftArm.yRot = 0;
        model.rightArm.zRot = -0.20F;
        model.leftArm.zRot = -model.rightArm.zRot;
        model.rightLeg.xRot = model.leftLeg.xRot = 0.08F;
        model.rightLeg.yRot = model.leftLeg.yRot = 0;
        model.rightLeg.zRot = 0.08F;
        model.leftLeg.zRot = -0.08F;
    }
}
