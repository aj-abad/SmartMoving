package choco.ratel.smartmoving.mixin.client;

import choco.ratel.smartmoving.client.SmModelPartOverride;
import com.mojang.blaze3d.vertex.PoseStack;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.model.geom.ModelPart;
import org.joml.Quaternionf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Sliding arm rotation mapping fix (2026-05-04):
 *   Original 1.7.10 YZX matrix = R_x(pitch) * R_z(roll) * R_y(yaw).
 *   1.21.1 ModelPart.rotate is ZYX hardcoded = R_z * R_y * R_x.
 *   Original yaw=-π/2 exactly ZYX gimbal lock region → JOML getEulerAnglesZYX decomposition
 *   inaccurate → qDiff angle = π (opposite rotation) → pose converges + no up/down wobble.
 *
 *   Guideline (memory feedback_zxy_zyx_rotation_order.md):
 *     "Do not use quaternion decomposition helper for large yaw (±π/2 or more) paths"
 *
 *   Solution: ModelPart.rotate HEAD inject + cancel + apply intended q directly.
 *            if sm_overrideQuat is null, maintain vanilla behavior (no impact).
 */
@Environment(EnvType.CLIENT)
@Mixin(ModelPart.class)
public abstract class MixinModelPart implements SmModelPartOverride {
    @Shadow public float x;
    @Shadow public float y;
    @Shadow public float z;
    @Shadow public float xScale;
    @Shadow public float yScale;
    @Shadow public float zScale;

    @Unique
    private Quaternionf sm_overrideQuat = null;

    @Inject(method = "translateAndRotate", at = @At("HEAD"), cancellable = true)
    private void sm_rotate_override(PoseStack matrices, CallbackInfo ci) {
        if (sm_overrideQuat != null) {
            matrices.translate(this.x / 16f, this.y / 16f, this.z / 16f);
            matrices.mulPose(sm_overrideQuat);
            if (this.xScale != 1.0F || this.yScale != 1.0F || this.zScale != 1.0F) {
                matrices.scale(this.xScale, this.yScale, this.zScale);
            }
            ci.cancel();
        }
    }

    @Override
    public void sm_setOverrideQuat(Quaternionf q) {
        this.sm_overrideQuat = q;
    }

    @Override
    public void sm_clearOverrideQuat() {
        this.sm_overrideQuat = null;
    }

    @Override
    public boolean sm_hasOverrideQuat() {
        return sm_overrideQuat != null;
    }

    @Override
    public Quaternionf sm_getOverrideQuat() {
        return sm_overrideQuat;
    }
}
