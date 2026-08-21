package choco.ratel.smartmoving.mixin.client;

import choco.ratel.smartmoving.client.SmartMovingLivingEntityAccess;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.world.entity.InterpolationHandler;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Exposes the 26.2 interpolation handler through Smart Moving's stable interface. */
@Mixin(LivingEntity.class)
@Environment(EnvType.CLIENT)
public abstract class MixinLivingEntityAccessor implements SmartMovingLivingEntityAccess {
    @Shadow @Final protected InterpolationHandler interpolation;

    @Override
    public double sm_getServerX() {
        return interpolation.position().x;
    }

    @Override
    public double sm_getServerY() {
        return interpolation.position().y;
    }

    @Override
    public double sm_getServerZ() {
        return interpolation.position().z;
    }

    @Override
    public void sm_setServerY(double value) {
        Vec3 target = interpolation.position();
        interpolation.interpolateTo(
                new Vec3(target.x, value, target.z),
                interpolation.yRot(),
                interpolation.xRot()
        );
    }

    @Override
    public int sm_getBodyTrackingIncrements() {
        return interpolation.hasActiveInterpolation() ? 3 : 0;
    }

    @Override
    public void sm_setBodyTrackingIncrements(int value) {
        if (value <= 0) {
            interpolation.cancel();
        } else {
            interpolation.setInterpolationLength(value);
        }
    }
}
