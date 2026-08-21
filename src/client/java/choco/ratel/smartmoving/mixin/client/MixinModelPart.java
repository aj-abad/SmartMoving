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
 * 슬라이딩 팔 회전 매핑 fix (2026-05-04):
 *   원본 1.7.10 YZX 매트릭스 = R_x(pitch) * R_z(roll) * R_y(yaw).
 *   1.21.1 ModelPart.rotate 는 ZYX hardcoded = R_z * R_y * R_x.
 *   원본 yaw=-π/2 정확히 ZYX gimbal lock 영역 → JOML getEulerAnglesZYX 분해
 *   부정확 → qDiff angle = π (정반대 회전) → 자세 가운데 모임 + 위아래 흔들림 X.
 *
 *   가이드라인 (memory feedback_zxy_zyx_rotation_order.md):
 *     "큰 yaw (±π/2 이상) 경로에선 quaternion 분해 헬퍼 사용 금지"
 *
 *   해결: ModelPart.rotate HEAD inject + cancel + 의도 q 직접 적용.
 *         sm_overrideQuat 가 null 이면 vanilla 동작 유지 (영향 없음).
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
