package choco.ratel.smartmoving.mixin.windrunner;

import choco.ratel.smartmoving.windrunner.WindrunnerArmor;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The boots' lethal fall save. hurtServer only asks for death protection after the damage is applied,
 * so the save sees the final, post-reduction hit, and it runs ahead of the totem so none is spent.
 */
@Mixin(LivingEntity.class)
public abstract class MixinWindrunnerLivingEntity {
    @Unique private float windrunner$healthBeforeHit;

    @Inject(method = "hurtServer", at = @At("HEAD"))
    private void windrunner$noteHealth(ServerLevel level, DamageSource source, float amount, CallbackInfoReturnable<Boolean> cir) {
        windrunner$healthBeforeHit = ((LivingEntity) (Object) this).getHealth();
    }

    @Inject(method = "checkTotemDeathProtection", at = @At("HEAD"), cancellable = true)
    private void windrunner$bootsSave(DamageSource source, CallbackInfoReturnable<Boolean> cir) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (WindrunnerArmor.savesFall(self, source, windrunner$healthBeforeHit)) {
            self.setHealth(1);
            cir.setReturnValue(true);
        }
    }
}
