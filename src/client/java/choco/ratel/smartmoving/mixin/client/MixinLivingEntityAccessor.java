package choco.ratel.smartmoving.mixin.client;

import choco.ratel.smartmoving.client.SmartMovingLivingEntityAccess;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.core.PositionAndRotation;
import net.minecraft.world.entity.InterpolationHandler;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.PositionPath;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Exposes the 26.3 interpolation handler through Smart Moving's stable interface.
 *
 * 26.3 replaced 26.2's concrete handler (position()/yRot()/xRot(), interpolateTo(Vec3, ...),
 * setInterpolationLength) with an interface built by Entity.createInterpolationHandler().
 * Living entities get a SteppedInterpolationHandler whose step length is the entity type's
 * update interval, so these methods keep 26.2's meaning through the public Entity API.
 */
@Mixin(LivingEntity.class)
@Environment(EnvType.CLIENT)
public abstract class MixinLivingEntityAccessor implements SmartMovingLivingEntityAccess {

    @Override
    public double sm_getServerX() {
        return ((LivingEntity) (Object) this).getClientPosition().x;
    }

    @Override
    public double sm_getServerY() {
        return ((LivingEntity) (Object) this).getClientPosition().y;
    }

    @Override
    public double sm_getServerZ() {
        return ((LivingEntity) (Object) this).getClientPosition().z;
    }

    @Override
    public void sm_setServerY(double value) {
        LivingEntity self = (LivingEntity) (Object) this;
        InterpolationHandler interpolation = self.getInterpolation();
        PositionAndRotation target = self.getClientPositionAndRotation();
        Vec3 position = new Vec3(target.position().x, value, target.position().z);
        if (interpolation.hasActiveInterpolation() && target.is(position, target.yRot(), target.xRot())) {
            return;
        }
        // 26.2 restarted the lerp from the current position toward the new target. The stepped
        // handler appends steps instead, so drop the queued ones first.
        interpolation.cancel();
        interpolation.interpolateTo(PositionPath.of(position), target.yRot(), target.xRot(), true);
    }

    @Override
    public int sm_getBodyTrackingIncrements() {
        return ((LivingEntity) (Object) this).getInterpolation().hasActiveInterpolation() ? 3 : 0;
    }

    @Override
    public void sm_setBodyTrackingIncrements(int value) {
        // A positive value only ever re-set 26.2's default length of 3. The stepped handler takes
        // its length from the entity type, so only cancelling has an effect.
        if (value <= 0) {
            ((LivingEntity) (Object) this).getInterpolation().cancel();
        }
    }
}
