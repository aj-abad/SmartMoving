package choco.ratel.smartmoving.mixin.client;

import choco.ratel.smartmoving.client.SmartMovingClientState;
import choco.ratel.smartmoving.config.SmartMovingConfig;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.item.Items;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Override getFovMultiplier() — smooth FOV multiplier based on fadingPerspectiveFactor.
 * Original: SmartMovingSelf.getFOVMultiplier (SmartMovingSelf.md L2036-2044)
 *
 * getFovMultiplier() is defined in AbstractClientPlayerEntity — cannot target ClientPlayerEntity directly.
 *
 * vanilla logic (reverse-engineered from AbstractClientPlayerEntity.getFovMultiplier() bytecode):
 *   f = 1.0; if flying f*=1.1;
 *   f *= (MOVEMENT_SPEED / walkSpeed + 1) / 2;
 *   bow: f *= 1 - t²*0.15; spyglass first-person: return 0.1;
 *   return lerp(fovEffectScale, 1.0, f)
 */
@Mixin(AbstractClientPlayer.class)
@Environment(EnvType.CLIENT)
public abstract class MixinAbstractClientPlayerEntityClient {

    @Inject(method = "getFieldOfViewModifier", at = @At("HEAD"), cancellable = true)
    private void sm_getFovMultiplier(CallbackInfoReturnable<Float> cir) {
        if (!((Object) this instanceof LocalPlayer player)) return;
        SmartMovingConfig cfg = SmartMovingConfig.Config;
        if (!cfg.enabled) return;
        SmartMovingClientState sm = SmartMovingClientState.get(player);
        if (sm.fadingPerspectiveFactor == -1F) return;

        float f = 1.0F;
        if (player.getAbilities().flying) f *= 1.1F;
        float walkSpeed = player.getAbilities().getWalkingSpeed();
        if (walkSpeed != 0F) {
            f *= (sm.fadingPerspectiveFactor / walkSpeed + 1F) / 2F;
        }
        if (walkSpeed == 0F || Float.isNaN(f) || Float.isInfinite(f)) f = 1.0F;

        if (player.isUsingItem()) {
            var activeItem = player.getUseItem();
            if (activeItem.is(Items.BOW)) {
                float t = Math.min(player.getTicksUsingItem() / 20.0F, 1.0F);
                t = t * t;
                f *= 1.0F - t * 0.15F;
            } else if (Minecraft.getInstance().options.getCameraType().isFirstPerson()
                    && player.isScoping()) {
                cir.setReturnValue(0.1F);
                return;
            }
        }

        float fovScale = ((Double) Minecraft.getInstance().options.fovEffectScale().get()).floatValue();
        cir.setReturnValue(Mth.lerp(fovScale, 1.0F, f));
    }

}
