package choco.ratel.smartmoving.mixin.client.windrunner;

import choco.ratel.smartmoving.windrunner.WindrunnerItems;
import choco.ratel.smartmoving.windrunner.WindrunnerGlide;
import choco.ratel.smartmoving.client.windrunner.model.WindrunnerGlideAnimation;
import choco.ratel.smartmoving.client.windrunner.render.WindrunnerLayer;
import choco.ratel.smartmoving.client.windrunner.render.WindrunnerRenderState;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Avatar;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Standard and slim avatars share this layer and use the native model's bones. */
@Mixin(AvatarRenderer.class)
public abstract class MixinWindrunnerAvatarRenderer extends LivingEntityRenderer<Avatar, AvatarRenderState, PlayerModel> {
    protected MixinWindrunnerAvatarRenderer(EntityRendererProvider.Context context, PlayerModel model, float shadow) { super(context, model, shadow); }

    @Override protected AABB getBoundingBoxForCulling(Avatar entity, float partialTicks) {
        AABB box = super.getBoundingBoxForCulling(entity, partialTicks);
        return entity instanceof Player player && WindrunnerGlide.of(player).mode() == WindrunnerGlide.GLIDING
                ? box.inflate(1.25 * entity.getScale()) : box;
    }

    @Inject(method = "<init>", at = @At("TAIL"))
    private void windrunner$addFlightLayer(EntityRendererProvider.Context context, boolean slim, CallbackInfo ci) {
        addLayer(new WindrunnerLayer(this, context));
    }

    @Inject(method = "extractRenderState(Lnet/minecraft/world/entity/Avatar;Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;F)V", at = @At("TAIL"))
    private void windrunner$extractFlight(Avatar entity, AvatarRenderState state, float partial, CallbackInfo ci) {
        var data = WindrunnerRenderState.Flight.NONE;
        if (entity instanceof Player player) {
            var flight = WindrunnerGlide.of(player);
            boolean equipped = player.getItemBySlot(EquipmentSlot.CHEST).is(WindrunnerItems.WINDRUNNER_ROBES);
            data = new WindrunnerRenderState.Flight(equipped, flight.mode(), flight.yaw(partial),
                    flight.pitch(partial), flight.roll(partial),
                    (float) flight.visualAirspeed(partial), flight.pitchRate(partial),
                    flight.rollRate(partial), flight.deployment(partial));
            if (equipped) state.showCape = false;
            if (flight.active()) {
                var offset = flight.visualPositionOffset(partial);
                state.x += offset.x;
                state.y += offset.y;
                state.z += offset.z;
                float headYaw = state.bodyRot + state.yRot;
                state.bodyRot = data.yaw();
                state.yRot = Mth.clamp(Mth.wrapDegrees(headYaw - data.yaw()), -85, 85);
                state.xRot = Mth.clamp(state.xRot - data.pitch(), -60, 65);
                state.isCrouching = false;
                state.isVisuallySwimming = false;
                state.swimAmount = 0;
                state.walkAnimationSpeed = 0;
                state.isFallFlying = false; // custom attitude, with vanilla elytra transforms excluded
            }
        }
        ((WindrunnerRenderState) state).windrunner$flight(data);
    }

    @Inject(method = "setupRotations(Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;FF)V", at = @At("HEAD"), cancellable = true)
    private void windrunner$flightRotation(AvatarRenderState state, PoseStack pose, float bodyRot, float scale, CallbackInfo ci) {
        var flight = ((WindrunnerRenderState) state).windrunner$flight();
        if (flight.mode() == WindrunnerGlide.CLOSED) return;
        var animation = WindrunnerGlideAnimation.sample(flight, state.ageInTicks, state.id);
        float deployment = animation.opening();
        // The wing root at the upper torso stays centered over the prone collision box.
        // setupRotations precedes vanilla's 0.9375 model scale and 1.501 foot translation.
        pose.translate(0, 0.30 * deployment, 0);
        pose.rotateDegrees(Axis.YP, 180 - flight.yaw());
        pose.rotateDegrees(Axis.XP, -flight.pitch() * deployment - animation.buffetPitch());
        pose.rotateDegrees(Axis.ZP, flight.roll() * deployment + animation.buffetRoll());
        pose.rotateDegrees(Axis.XP, -90 * deployment);
        pose.translate(0, -(1.501 - 4.0 / 16) * 0.9375 * deployment, 0);
        ci.cancel();
    }
}
