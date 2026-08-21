package choco.ratel.smartmoving.mixin.client;

import choco.ratel.smartmoving.client.SmartMovingAvatarRenderState;
import choco.ratel.smartmoving.client.SmartMovingClientState;
import choco.ratel.smartmoving.config.SmartMovingConfig;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Bridges player data into Minecraft 26.2's detached render states and replaces
 * the vanilla walk-animation inputs with Smart Moving's horizontal statistics.
 */
@Mixin(LivingEntityRenderer.class)
@Environment(EnvType.CLIENT)
public abstract class MixinLivingEntityRenderer {

    @Redirect(
            method = "shouldShowName(Lnet/minecraft/world/entity/LivingEntity;D)Z",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/LivingEntity;isDiscrete()Z"
            )
    )
    private boolean sm_isDiscreteForLabel(LivingEntity entity) {
        if (!(entity instanceof AbstractClientPlayer) || entity instanceof LocalPlayer) {
            return entity.isDiscrete();
        }
        SmartMovingConfig cfg = SmartMovingConfig.Config;
        if (!choco.ratel.smartmoving.client.SmartMovingClient.isSmRenderEnabled(entity)
                || !cfg.sneakNameTag) {
            return entity.isDiscrete();
        }
        return false;
    }

    @Inject(
            method = "extractRenderState(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;F)V",
            at = @At("HEAD")
    )
    private void sm_prepareRenderState(
            LivingEntity entity,
            LivingEntityRenderState renderState,
            float tickDelta,
            CallbackInfo ci
    ) {
        if (!(entity instanceof AbstractClientPlayer player)
                || !(renderState instanceof SmartMovingAvatarRenderState smartState)) {
            return;
        }

        smartState.sm_setPlayer(player);
        smartState.sm_setTickDelta(tickDelta);
        SmartMovingClientState.globalCachedTickDelta = tickDelta;

        // Initialize crawl state before vanilla snapshots the pose for the first frame.
        if (SmartMovingConfig.Config.enabled && player instanceof LocalPlayer localPlayer) {
            SmartMovingClientState sm = SmartMovingClientState.get(localPlayer);
            boolean remoteServer = Minecraft.getInstance().getSingleplayerServer() == null;
            if (!sm.initialized
                    && !(remoteServer && sm.multiPlayerInitialized != 0)
                    && !localPlayer.isPassenger()) {
                var box = localPlayer.getBoundingBox();
                double standingHeight = localPlayer.getDimensions(net.minecraft.world.entity.Pose.STANDING).height();
                double solidTop = SmartMovingClientState.getMaxPlayerSolidBetween(
                        localPlayer, box.minY, box.minY + standingHeight, 0
                );
                if (solidTop > box.minY) {
                    sm.initializeCrawling = true;
                    sm.toCrawling();
                    sm.heightOffset = -1F;
                    localPlayer.refreshDimensions();
                    localPlayer.setPose(net.minecraft.world.entity.Pose.SWIMMING);
                }
                sm.initialized = true;
            }
        }
    }

    @Inject(
            method = "extractRenderState(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;F)V",
            at = @At("TAIL")
    )
    private void sm_applyAnimationInputs(
            LivingEntity entity,
            LivingEntityRenderState renderState,
            float tickDelta,
            CallbackInfo ci
    ) {
        if (!(entity instanceof AbstractClientPlayer player)
                || !choco.ratel.smartmoving.client.SmartMovingClient.isSmRenderEnabled(player)) {
            return;
        }

        SmartMovingClientState sm = SmartMovingClientState.get(player.getUUID());
        renderState.walkAnimationPos = sm.stats.getTotalHorizontalDistance(tickDelta);

        boolean anySmState = sm.isFlying || sm.isCrawling || sm.isSliding || sm.isHeadJumping
                || sm.isClimbing || sm.isCrawlClimbing || sm.isCeilingClimbing
                || sm.isSwimming_sm || sm.isDiving || sm.isRopeSliding || sm.isClimbJumping
                || sm.doFallingAnimation || sm.isAngleJumping();
        renderState.walkAnimationSpeed = anySmState
                ? 0F
                : sm.stats.getCurrentHorizontalSpeed(tickDelta);
        if (anySmState && renderState instanceof HumanoidRenderState humanoidState) {
            humanoidState.swimAmount = 0F;
        }
    }
}
