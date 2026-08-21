package choco.ratel.smartmoving.mixin.client;

import choco.ratel.smartmoving.client.SmartMovingAvatarRenderState;
import choco.ratel.smartmoving.client.SmartMovingClientState;
import com.mojang.blaze3d.vertex.PoseStack;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.layers.CapeLayer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Applies Smart Moving's body-tilt cape clamp to the 26.2 cape render state. */
@Environment(EnvType.CLIENT)
@Mixin(CapeLayer.class)
public abstract class MixinCapeFeatureRenderer {

    @Inject(
            method = "submit(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;ILnet/minecraft/client/renderer/entity/state/AvatarRenderState;FF)V",
            at = @At("HEAD")
    )
    private void sm_clampCapeAngle(
            PoseStack poseStack,
            SubmitNodeCollector nodeCollector,
            int light,
            AvatarRenderState renderState,
            float yRot,
            float xRot,
            CallbackInfo ci
    ) {
        if (!((Object) renderState instanceof SmartMovingAvatarRenderState smartState)) return;
        AbstractClientPlayer player = smartState.sm_getPlayer();
        if (!(player instanceof LocalPlayer)) return;

        SmartMovingClientState sm = SmartMovingClientState.get(player.getUUID());
        if (sm.smOuterTiltX == 0F) return;

        float currentAngle = 6F + renderState.capeLean / 2F + renderState.capeFlap;
        float maximumAngle = Math.max(70.523F - (float) Math.toDegrees(sm.smOuterTiltX), 6F);
        if (currentAngle > maximumAngle) {
            renderState.capeFlap -= currentAngle - maximumAngle;
        }
    }
}
