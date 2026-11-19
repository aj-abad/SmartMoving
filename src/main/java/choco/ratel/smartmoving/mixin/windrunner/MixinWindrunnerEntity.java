package choco.ratel.smartmoving.mixin.windrunner;

import choco.ratel.smartmoving.windrunner.WindrunnerMoves;
import java.util.List;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A ground dash steps up like walking even mid-hop. Vanilla steps only from the ground or onto a
 * landing, and looks for steps no lower than the feet, so a hop coming down just under a lip (a dirt
 * path's edge onto grass, say) hit its side in the air and stopped dead.
 */
@Mixin(Entity.class)
public abstract class MixinWindrunnerEntity {
    @Shadow public abstract AABB getBoundingBox();
    @Shadow public abstract float maxUpStep();
    @Shadow public abstract Level level();

    @Inject(method = "collide", at = @At("RETURN"), cancellable = true)
    private void windrunner$dashSteps(Vec3 movement, CallbackInfoReturnable<Vec3> cir) {
        Vec3 result = cir.getReturnValue();
        if (result.x == movement.x && result.z == movement.z) return;
        Entity self = (Entity) (Object) this;
        if (!(self instanceof Player player) || !WindrunnerMoves.of(player).groundDashing()) return;
        // From where the vertical move ended: rise by the step height, cross, then settle back down.
        AABB box = getBoundingBox().move(0, result.y, 0);
        double step = maxUpStep();
        List<VoxelShape> entities = level().getEntityCollisions(self, box.expandTowards(movement.x, step, movement.z));
        Vec3 up = Entity.collideBoundingBox(self, new Vec3(0, step, 0), box, level(), entities);
        Vec3 across = Entity.collideBoundingBox(self, new Vec3(movement.x, 0, movement.z), box.move(up), level(), entities);
        if (across.horizontalDistanceSqr() <= result.horizontalDistanceSqr()) return;
        Vec3 down = Entity.collideBoundingBox(self, new Vec3(0, -up.y, 0), box.move(up).move(across), level(), entities);
        cir.setReturnValue(new Vec3(across.x, result.y + up.y + down.y, across.z));
    }
}
