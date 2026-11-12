package choco.ratel.smartmoving.client.windrunner.model;

import choco.ratel.smartmoving.client.windrunner.render.WindrunnerRenderState.Flight;
import choco.ratel.smartmoving.client.windrunner.render.WindrunnerRenderState;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.util.Mth;

/** A continuous, per-avatar pose shared by the skin and its attached cloth. All angles are radians. */
public final class WindrunnerGlideAnimation {
    private WindrunnerGlideAnimation() {}

    public record Pose(float tuck, float pull, float bank, float airflow, float opening, float time) {
        public float wave(float phase) {
            return 0.6F * Mth.sin(time * 0.65F + phase) + 0.4F * Mth.sin(time * 1.31F + phase * 1.7F);
        }
        public float spread(int side) {
            float spread = Mth.lerp(tuck, 1.32F, 0.10F) + pull * 0.07F - side * bank * 0.18F;
            spread += wave(side * 2.3F) * airflow * 0.025F;
            return Mth.lerp(opening, 0.10F, Mth.clamp(spread, 0.07F, 1.5F));
        }
        public float armPitch(int side) {
            return opening * (1 - tuck) * (-0.04F - pull * 0.13F - side * bank * 0.07F
                    + wave(side * 3.1F + 1) * airflow * 0.025F);
        }
        public float legPitch(int side) {
            return opening * (-0.05F - pull * 0.12F + tuck * 0.06F - side * bank * 0.06F
                    + side * Mth.sin(time * 0.19F) * airflow * 0.035F);
        }
        public float buffetPitch() { return wave(0) * airflow * 0.45F; }
        public float buffetRoll() { return wave(4.7F) * airflow * 0.65F; }
    }

    public static Pose sample(Flight flight, float age, int id) {
        float opening = smooth(flight.deployment());
        float tuck = Mth.clamp(smooth((flight.pitch() - 15) / 65)
                + Math.max(0, flight.pitchRate()) / 3.8F * 0.08F, 0, 1);
        float pull = Mth.clamp(-flight.pitchRate() / 3.8F, -1, 1);
        float bank = Mth.clamp(flight.roll() / 65 * 0.6F + flight.rollRate() / 6.5F * 0.4F, -1, 1);
        float airflow = Mth.clamp((flight.airspeed() - 0.12F) / 1.2F, 0, 1) * opening;
        return new Pose(tuck, pull, bank, airflow, opening, age + (id & 1023) * 0.37F);
    }

    private static float smooth(float value) {
        float t = Mth.clamp(value, 0, 1);
        return t * t * (3 - 2 * t);
    }

    public static void apply(PlayerModel model, AvatarRenderState state) {
        Pose pose = sample(((WindrunnerRenderState) state).windrunner$flight(),
                state.ageInTicks, state.id);
        // Discard attack/item-use twists before posing both the avatar and the cloth anchors.
        model.body.resetPose(); model.head.resetPose();
        model.rightArm.resetPose(); model.leftArm.resetPose();
        model.rightLeg.resetPose(); model.leftLeg.resetPose();
        model.rightArm.zRot = pose.spread(-1);
        model.leftArm.zRot = -pose.spread(1);
        model.rightArm.xRot = pose.armPitch(-1);
        model.leftArm.xRot = pose.armPitch(1);
        model.rightLeg.xRot = pose.legPitch(-1);
        model.leftLeg.xRot = pose.legPitch(1);
        float legSpread = Mth.lerp(pose.tuck(), 0.13F, 0.025F) * pose.opening();
        model.rightLeg.zRot = legSpread;
        model.leftLeg.zRot = -legSpread;
        model.head.xRot = pose.opening() * (-1.05F + Mth.clamp(state.xRot * 0.30F, -20, 25) * Mth.DEG_TO_RAD
                - pose.pull() * 0.06F + pose.tuck() * 0.12F);
        model.head.yRot = pose.opening() * Mth.clamp(state.yRot * 0.35F, -25, 25) * Mth.DEG_TO_RAD;
        model.head.zRot = pose.opening() * -pose.bank() * 0.045F;
    }
}
