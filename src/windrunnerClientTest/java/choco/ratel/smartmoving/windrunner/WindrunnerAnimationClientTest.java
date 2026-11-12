package choco.ratel.smartmoving.windrunner;

import choco.ratel.smartmoving.client.windrunner.model.WindrunnerGlideAnimation;
import choco.ratel.smartmoving.client.windrunner.model.WindrunnerMembrane;
import choco.ratel.smartmoving.client.windrunner.render.WindrunnerRenderState;
import choco.ratel.smartmoving.client.windrunner.render.WindrunnerRenderState.Flight;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

/** Native skin/fan attachment and animation checks for both standard and slim avatars. */
final class WindrunnerAnimationClientTest {
    static void run(Minecraft client) {
        for (boolean slim : new boolean[] {false, true}) {
            var model = new PlayerModel(client.getEntityModels().bakeLayer(slim ? ModelLayers.PLAYER_SLIM : ModelLayers.PLAYER), slim);
            var state = state(10, 0, 0, 0);
            model.setupAnim(state);
            Vec3 spreadWrist = transformed(model.rightArm, 0.8F, 9, 2.2F);
            check(Math.abs(spreadWrist.x) > 0.8, "Glide spreads the forearms well beyond the torso");
            var open = membrane(model, state, -1);
            double openArea = area(open, true);
            check(openArea > 0.28, "Arm-to-foot wings have over twice the old arm-to-back wing area");
            Vec3 capturedWrist = open.point(WindrunnerMembrane.SIZE - 1, WindrunnerMembrane.SIZE - 1);

            var dive = state(80, 0, 3, 0);
            model.setupAnim(dive);
            Vec3 tuckedWrist = transformed(model.rightArm, 0.8F, 9, 2.2F);
            var folded = membrane(model, dive, -1);
            System.out.printf("Wingsuit exposed membrane areas (slim=%s): spread %.4f, tucked %.4f%n", slim, openArea, area(folded, true));
            check(Math.abs(tuckedWrist.x) < 0.45 && tuckedWrist.y > 0.60, "Diving brings the hands beside the hips");
            // The fan gathers beside the hips; its exposed planform should shrink in a dive.
            check(area(folded, true) < openArea * 0.3, "Diving collapses the exposed wing surface beside the hips");
            check(open.point(WindrunnerMembrane.SIZE - 1, WindrunnerMembrane.SIZE - 1).equals(capturedWrist),
                    "Deferred cloth geometry cannot change when the renderer poses another avatar");

            model.setupAnim(state(10, -55, 0, -4));
            check(Math.abs(model.rightArm.zRot) < Math.abs(model.leftArm.zRot), "Right bank draws the right arm inward and extends the left");
            model.setupAnim(state(10, 55, 0, 4));
            check(Math.abs(model.leftArm.zRot) < Math.abs(model.rightArm.zRot), "Left bank mirrors the steering pose");

            for (float pitch : new float[] {-60, 10, 45, 80}) for (float roll : new float[] {-65, 0, 65})
                    for (float deployment : new float[] {0, 0.1F, 0.35F, 0.65F, 1}) {
                var pose = state(pitch, roll, -2, 3, deployment);
                for (int frame = 0; frame < 12; frame++) {
                    pose.ageInTicks = 40 + frame / 3F;
                    model.setupAnim(pose);
                    for (int side : new int[] {-1, 1}) {
                        var mesh = membrane(model, pose, side);
                        int end = WindrunnerMembrane.SIZE - 1;
                        check(mesh.point(0, 0).distanceTo(transformed(side < 0 ? model.rightArm : model.leftArm,
                                -side * 0.8F, -0.5F, 2.2F)) < 1.0E-6, "The leading wing root follows the arm");
                        check(mesh.point(0, end).distanceTo(transformed(side < 0 ? model.rightLeg : model.leftLeg,
                                side * 1.8F, 11.5F, 2.2F)) < 1.0E-6, "The trailing wing root stays attached to the foot through leg motion");
                        check(mesh.point(end, end).distanceTo(transformed(side < 0 ? model.rightArm : model.leftArm,
                                -side * 0.8F, 9, 2.2F)) < 1.0E-6, "Flutter never detaches the membrane from the forearm");
                        check(area(mesh, false) > 0.001, "Folded cloth retains finite surface area");
                        checkFan(mesh);
                    }
                }
            }

            var f = ((WindrunnerRenderState) state).windrunner$flight();
            var a = WindrunnerGlideAnimation.sample(f, 40, 1);
            var b = WindrunnerGlideAnimation.sample(f, 40.01F, 1);
            check(Math.abs(a.spread(1) - b.spread(1)) < 0.001, "Limb motion stays continuous across render frames");
            check(Math.abs(a.buffetRoll()) <= 0.65 && Math.abs(a.buffetPitch()) <= 0.45, "Body buffet stays subtle");
            check(Math.abs(a.spread(1) - WindrunnerGlideAnimation.sample(f, 44, 1).spread(1)) > 0.001,
                    "Steady gliding still has gentle airflow motion");
            model.setupAnim(state);
            var stillFrame = WindrunnerMembrane.capture(model, a, -1, 0xF000F0);
            var flutterFrame = WindrunnerMembrane.capture(model, WindrunnerGlideAnimation.sample(f, 44, 1), -1, 0xF000F0);
            check(stillFrame.ribTip(0).equals(flutterFrame.ribTip(0))
                            && stillFrame.ribTip(WindrunnerMembrane.PANELS).equals(flutterFrame.ribTip(WindrunnerMembrane.PANELS)),
                    "Hinge flutter preserves the foot and forearm attachments");
            for (int rib = 1; rib < WindrunnerMembrane.PANELS; rib++) {
                Vec3 first = stillFrame.ribTip(rib).subtract(stillFrame.hinge());
                Vec3 next = flutterFrame.ribTip(rib).subtract(flutterFrame.hinge());
                check(Math.abs(first.length() - next.length()) < 1.0E-6, "Airflow rotates wooden ribs without stretching them");
                check(first.distanceTo(next) > 1.0E-5 && first.distanceTo(next) < first.length() * 0.045,
                        "Fan flutter is visible but limited to small hinge movements");
            }
            checkFan(stillFrame);
            checkFan(flutterFrame);
            var deploying = WindrunnerMembrane.capture(model,
                    WindrunnerGlideAnimation.sample(((WindrunnerRenderState) state(10, 0, 0, 0, 0.5F)).windrunner$flight(), 40, 1), -1, 0xF000F0);
            check(deploying.ribTip(1).distanceTo(stillFrame.ribTip(1)) > 0.02,
                    "Intermediate ribs lag behind the wrist while the fan opens");
            WindrunnerMaterialClientTest.run(client, deploying);
            float arm = model.rightArm.zRot;
            model.setupAnim(state);
            check(model.rightArm.zRot == arm, "Animation does not accumulate with repeated renders");
            var walking = new AvatarRenderState();
            model.setupAnim(walking);
            check(Math.abs(model.rightArm.zRot) < 0.2, "Normal arm animation returns after flight");
            model.setupAnim(state);
            WindrunnerMaterialClientTest.run(client, membrane(model, state, -1));
        }
        Windrunner.LOGGER.info("Folding fan spread, tuck, bank, flat leaves, hinge flutter and skin attachments passed.");
    }

    private static AvatarRenderState state(float pitch, float roll, float pitchRate, float rollRate) {
        return state(pitch, roll, pitchRate, rollRate, 1);
    }

    private static AvatarRenderState state(float pitch, float roll, float pitchRate, float rollRate, float deployment) {
        var state = new AvatarRenderState();
        state.ageInTicks = 40;
        ((WindrunnerRenderState) state).windrunner$flight(new Flight(true, WindrunnerGlide.GLIDING, 0, pitch, roll,
                1.2F, pitchRate, rollRate, deployment));
        return state;
    }

    private static void checkFan(WindrunnerMembrane mesh) {
        Vec3 hinge = mesh.hinge();
        Vec3 fanNormal = mesh.ribTip(0).subtract(hinge).cross(mesh.ribTip(WindrunnerMembrane.PANELS).subtract(hinge)).normalize();
        for (int panel = 0; panel < WindrunnerMembrane.PANELS; panel++) {
            Vec3 a = mesh.ribTip(panel).subtract(hinge), b = mesh.ribTip(panel + 1).subtract(hinge);
            Vec3 normal = a.cross(b).normalize();
            check(a.cross(b).dot(fanNormal) > 0, "Fan ribs stay ordered throughout deployment, banks and dives");
            for (int column = 0; column < WindrunnerMembrane.SIZE; column++) {
                float u = WindrunnerMembrane.parameter(column) * WindrunnerMembrane.PANELS;
                if (u < panel || u > panel + 1) continue;
                for (int row = 0; row < WindrunnerMembrane.SIZE; row++) {
                    check(Math.abs(mesh.point(column, row).subtract(hinge).dot(normal)) < 1.0E-6,
                            "Each fan leaf remains flat, including during folding and flutter");
                }
            }
        }
    }

    private static WindrunnerMembrane membrane(PlayerModel model, AvatarRenderState state, int side) {
        return WindrunnerMembrane.capture(model,
                WindrunnerGlideAnimation.sample(((WindrunnerRenderState) state).windrunner$flight(), state.ageInTicks, state.id), side, 0xF000F0);
    }

    private static Vec3 transformed(ModelPart part, float x, float y, float z) {
        var pose = new PoseStack();
        part.translateAndRotate(pose);
        Vector3f point = pose.last().pose().transformPosition(new Vector3f(x / 16, y / 16, z / 16));
        return new Vec3(point.x, point.y, point.z);
    }

    private static double area(WindrunnerMembrane mesh, boolean projected) {
        double area = 0;
        for (int row = 0; row < WindrunnerMembrane.SIZE - 1; row++) for (int col = 0; col < WindrunnerMembrane.SIZE - 1; col++) {
            Vec3 a = mesh.point(col, row), b = mesh.point(col + 1, row), c = mesh.point(col + 1, row + 1), d = mesh.point(col, row + 1);
            Vec3 first = b.subtract(a).cross(c.subtract(a)), second = c.subtract(a).cross(d.subtract(a));
            area += projected ? (Math.abs(first.z) + Math.abs(second.z)) * 0.5 : (first.length() + second.length()) * 0.5;
        }
        check(Double.isFinite(area), "Membrane geometry remains finite");
        return area;
    }

    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
