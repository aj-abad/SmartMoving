package choco.ratel.smartmoving.mixin.client.windrunner;

import choco.ratel.smartmoving.client.windrunner.WindrunnerControls;
import choco.ratel.smartmoving.client.windrunner.WindrunnerGlideControls;
import choco.ratel.smartmoving.windrunner.WindrunnerGlide;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** The glide's camera: neck-cone free look, smoothed position, banked view, speed FOV and the dash dart. */
@Mixin(Camera.class)
public abstract class MixinWindrunnerCamera {
    private static final float RAD_PER_DEG = (float) (Math.PI / 180.0);

    @Shadow private float xRot;
    @Shadow private float yRot;
    @Shadow private int matrixPropertiesDirty;
    @Shadow private Vec3 position;
    @Shadow @Final private Quaternionf rotation;

    @Shadow protected abstract void setRotation(float yRot, float xRot);
    @Shadow protected abstract void setPosition(Vec3 position);

    // Mouse look is applied per rendered frame, so enforce the neck cone here rather than once a tick.
    @Inject(method = "alignWithEntity", at = @At("HEAD"))
    private void windrunner$clampLook(float partialTicks, CallbackInfo ci) {
        if (Minecraft.getInstance().getCameraEntity() instanceof Player player) {
            WindrunnerGlideControls.clampFirstPersonLook(player);
        }
    }

    // Offset the tracking origin before vanilla's third-person wall-occlusion trace.
    @Inject(method = "alignWithEntity", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/Camera;setPosition(DDD)V", shift = At.Shift.AFTER))
    private void windrunner$smoothGlidePosition(float partialTicks, CallbackInfo ci) {
        if (Minecraft.getInstance().getCameraEntity() instanceof Player player && WindrunnerGlide.of(player).active()) {
            setPosition(position.add(WindrunnerGlide.of(player).visualPositionOffset(partialTicks)));
        }
    }

    @Inject(method = "alignWithEntity", at = @At("TAIL"))
    private void windrunner$bankWithGlide(float partialTicks, CallbackInfo ci) {
        Minecraft client = Minecraft.getInstance();
        if (!client.options.getCameraType().isFirstPerson()
                || !(client.getCameraEntity() instanceof Player player) || !WindrunnerGlide.of(player).active()) return;
        setRotation(yRot + WindrunnerGlideControls.cameraYawOffset(player, partialTicks),
                Mth.clamp(xRot + WindrunnerGlideControls.cameraPitchOffset(player, partialTicks), -90, 90));
        rotation.rotateZ(WindrunnerGlide.of(player).roll(partialTicks) * RAD_PER_DEG * 0.6F);
        matrixPropertiesDirty |= 3;
    }

    @Inject(method = "calculateFov", at = @At("RETURN"), cancellable = true)
    private void windrunner$glideFov(float partialTicks, CallbackInfoReturnable<Float> cir) {
        Minecraft client = Minecraft.getInstance();
        float effectScale = client.options.fovEffectScale().get().floatValue();
        if (client.getCameraEntity() instanceof Player player && WindrunnerGlide.of(player).active()) {
            double speed = WindrunnerGlide.of(player).visualAirspeed(partialTicks);
            float boost = (float) Math.clamp((speed - 0.5) / 1.2, 0, 1) * 0.20F;
            cir.setReturnValue(cir.getReturnValue() * (1 + boost * effectScale));
        }
        // A Windrunner dash darts the view, on the ground or in the air.
        float dash = WindrunnerControls.fovDart(partialTicks);
        if (dash > 0 && client.getCameraEntity() == client.player) {
            cir.setReturnValue(cir.getReturnValue() * (1.0F + dash * effectScale));
        }
    }
}
