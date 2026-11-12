package choco.ratel.smartmoving.client.windrunner.model;

import choco.ratel.smartmoving.client.windrunner.render.WindrunnerRenderState;
import choco.ratel.smartmoving.windrunner.WindrunnerMoves;
import choco.ratel.smartmoving.windrunner.WindrunnerWallRunModel;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;

/**
 * Wall run poses, blended over the model's own pose by the run's weight. Applied to every humanoid
 * model the avatar draws, so armor follows the skin. Limb angles are model space:
 * xRot below zero swings forward or up; an outward arm raise is rightArm.zRot above zero, leftArm below.
 */
public final class WindrunnerWallRunAnimation {
    /** Radians of stride per tick: quicker than a sprint's cycle. */
    private static final float STRIDE_RATE = 0.8F;
    /** Running along, the body leans away from the wall with its feet on it. */
    private static final float ALONG_LEAN = 20, UP_LEAN = 12;
    /** Running up, the body is drawn this far off the wall so the planted foot and raised hands meet its face. */
    private static final double UP_STANDOFF = 0.3;

    private WindrunnerWallRunAnimation() {}

    /**
     * Captures the run and turns the body to it: up faces the wall, along faces the way the player is
     * moving along it. The head keeps looking where the player looks.
     */
    public static WindrunnerRenderState.WallRun extract(Player player, AvatarRenderState state, float partial) {
        WindrunnerMoves moves = WindrunnerMoves.of(player);
        float weight = moves.wallRunWeight(partial);
        if (weight <= 0) return WindrunnerRenderState.WallRun.NONE;
        int mode = moves.wallRunPoseMode();
        Direction side = Direction.from2DDataValue(moves.wallRunPoseSide());
        float intoX = side.getStepX(), intoZ = side.getStepZ();
        float targetYaw;
        boolean wallOnRight = false;
        if (mode == WindrunnerWallRunModel.UP) {
            targetYaw = yaw(intoX, intoZ);
            state.x -= intoX * UP_STANDOFF * weight;
            state.z -= intoZ * UP_STANDOFF * weight;
        } else {
            double dx = player.getX() - player.xo, dz = player.getZ() - player.zo;
            double pressing = dx * intoX + dz * intoZ;
            double alongX = dx - intoX * pressing, alongZ = dz - intoZ * pressing;
            if (alongX * alongX + alongZ * alongZ < 1.0E-6) {
                float forwardX = -Mth.sin(state.bodyRot * Mth.DEG_TO_RAD), forwardZ = Mth.cos(state.bodyRot * Mth.DEG_TO_RAD);
                pressing = forwardX * intoX + forwardZ * intoZ;
                alongX = forwardX - intoX * pressing;
                alongZ = forwardZ - intoZ * pressing;
            }
            targetYaw = yaw((float) alongX, (float) alongZ);
            float rightX = -Mth.cos(targetYaw * Mth.DEG_TO_RAD), rightZ = -Mth.sin(targetYaw * Mth.DEG_TO_RAD);
            wallOnRight = rightX * intoX + rightZ * intoZ > 0;
        }
        float headYaw = state.bodyRot + state.yRot;
        state.bodyRot = Mth.rotLerp(weight, state.bodyRot, targetYaw);
        state.yRot = Mth.clamp(Mth.wrapDegrees(headYaw - state.bodyRot), -85, 85);
        return new WindrunnerRenderState.WallRun(mode, weight, wallOnRight);
    }

    public static void rotate(WindrunnerRenderState.WallRun run, PoseStack pose) {
        if (run.mode() == WindrunnerWallRunModel.UP) {
            pose.rotateDegrees(Axis.XP, UP_LEAN * run.weight());
        } else {
            pose.rotateDegrees(Axis.ZP, (run.wallOnRight() ? ALONG_LEAN : -ALONG_LEAN) * run.weight());
        }
    }

    public static void apply(HumanoidModel<?> model, AvatarRenderState state, WindrunnerRenderState.WallRun run) {
        float w = run.weight();
        float stride = Mth.sin(state.ageInTicks * STRIDE_RATE);
        if (run.mode() == WindrunnerWallRunModel.UP) {
            // One foot planted on the wall while the other drives down; hands reach up in turn.
            model.head.xRot = Mth.lerp(w * 0.6F, model.head.xRot, -0.5F);
            blend(model.rightLeg, w, -0.65F + 0.65F * stride, 0, 0.05F);
            blend(model.leftLeg, w, -0.65F - 0.65F * stride, 0, -0.05F);
            blend(model.rightArm, w, -2.75F - 0.3F * stride, 0, 0.12F);
            blend(model.leftArm, w, -2.75F + 0.3F * stride, 0, -0.12F);
            return;
        }
        blend(model.body, w, 0.25F, 0, 0);
        blend(model.rightLeg, w, stride, 0, 0);
        blend(model.leftLeg, w, -stride, 0, 0);
        // The arm away from the wall swings out for balance; the one beside it pumps with the stride.
        if (run.wallOnRight()) {
            blend(model.rightArm, w, -stride, 0, 0.2F);
            blend(model.leftArm, w, -0.4F + 0.2F * stride, 0, -1.1F);
        } else {
            blend(model.leftArm, w, stride, 0, -0.2F);
            blend(model.rightArm, w, -0.4F - 0.2F * stride, 0, 1.1F);
        }
    }

    private static void blend(ModelPart part, float weight, float xRot, float yRot, float zRot) {
        part.xRot = Mth.lerp(weight, part.xRot, xRot);
        part.yRot = Mth.lerp(weight, part.yRot, yRot);
        part.zRot = Mth.lerp(weight, part.zRot, zRot);
    }

    /** Vanilla yaw for a horizontal heading: forward is (-sin yaw, cos yaw). */
    private static float yaw(float x, float z) { return (float) Math.toDegrees(Math.atan2(-x, z)); }
}
