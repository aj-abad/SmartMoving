package choco.ratel.smartmoving.mixin.client;

import choco.ratel.smartmoving.client.SmartMovingClient;
import choco.ratel.smartmoving.client.SmartMovingClientState;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.entity.Avatar;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.Pose;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Client-side Smart Moving dimensions on the 26.2 {@link Avatar} owner. */
@Environment(EnvType.CLIENT)
@Mixin(Avatar.class)
public abstract class MixinAvatarDimensionsClient {
    @Inject(method = "getDefaultDimensions", at = @At("HEAD"), cancellable = true)
    private void sm_getClientDimensions(Pose pose, CallbackInfoReturnable<EntityDimensions> cir) {
        if (!((Object) this instanceof AbstractClientPlayer player)
                || !SmartMovingClient.isSmRenderEnabled(player)) return;
        SmartMovingClientState sm = SmartMovingClientState.get(player);

        if (sm.isClimbCrawling || (sm.isCrawling && sm.isClimbing && sm.wasClimbCrawling)
                || sm.isFlying || sm.isLevitating || sm.isSliding
                || sm.isSwimming_sm || sm.isDiving) {
            cir.setReturnValue(EntityDimensions.scalable(0.6F, 0.8F).withEyeHeight(1.62F));
            return;
        }
        if (sm.isCrawling || sm.isCrawlClimbing) {
            cir.setReturnValue(EntityDimensions.scalable(0.6F, 0.8F).withEyeHeight(0.62F));
            return;
        }
        if (pose == Pose.SLIDING) {
            cir.setReturnValue(EntityDimensions.scalable(0.6F, 0.8F).withEyeHeight(0.62F));
        } else if (pose == Pose.SWIMMING) {
            cir.setReturnValue(EntityDimensions.scalable(0.6F, 1.8F).withEyeHeight(1.62F));
        }
    }
}
