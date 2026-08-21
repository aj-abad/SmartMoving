package choco.ratel.smartmoving.mixin;

import choco.ratel.smartmoving.config.SmartMovingConfig;
import choco.ratel.smartmoving.server.SmartMovingServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Avatar;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.Pose;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Server-side Smart Moving dimensions on the 26.2 {@link Avatar} owner. */
@Mixin(Avatar.class)
public abstract class MixinAvatarDimensions {
    @Inject(method = "getDefaultDimensions", at = @At("HEAD"), cancellable = true)
    private void sm_getServerDimensions(Pose pose, CallbackInfoReturnable<EntityDimensions> cir) {
        if (!((Object) this instanceof ServerPlayer player)) return;
        SmartMovingServer sm = SmartMovingServer.get(player);

        if (sm.isClimbCrawling || sm.isHeadJumping || sm.isSliding
                || sm.isSwimming || sm.isDiving) {
            cir.setReturnValue(EntityDimensions.scalable(0.6F, 0.8F).withEyeHeight(1.62F));
            return;
        }
        if (sm.isCrawling || sm.isCrawlClimbing) {
            cir.setReturnValue(EntityDimensions.scalable(0.6F, 0.8F).withEyeHeight(0.62F));
            return;
        }

        boolean flying = SmartMovingConfig.Config.fly
                && player.getAbilities().flying
                && !sm.isSwimming
                && !sm.isDiving;
        if (flying || sm.isLevitating) {
            cir.setReturnValue(EntityDimensions.scalable(0.6F, 0.8F).withEyeHeight(1.62F));
            return;
        }
        if (pose == Pose.SLIDING) {
            cir.setReturnValue(EntityDimensions.scalable(0.6F, 0.8F).withEyeHeight(0.62F));
        } else if (pose == Pose.SWIMMING) {
            cir.setReturnValue(EntityDimensions.scalable(0.6F, 1.8F).withEyeHeight(1.62F));
        }
    }
}
