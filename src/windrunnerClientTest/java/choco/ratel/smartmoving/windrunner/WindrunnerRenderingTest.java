package choco.ratel.smartmoving.windrunner;

import choco.ratel.smartmoving.client.windrunner.render.WindrunnerRenderState;
import choco.ratel.smartmoving.client.windrunner.WindrunnerGlideControls;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.util.Mth;
import org.joml.Vector3f;

/** Checks the transformed native renderer and camera, including frames between physics ticks. */
final class WindrunnerRenderingTest {
    static void run(Minecraft client) {
        try {
            var renderer = client.getEntityRenderDispatcher().getRenderer(client.player);
            var setup = AvatarRenderer.class.getDeclaredMethod("setupRotations", AvatarRenderState.class,
                    PoseStack.class, float.class, float.class);
            setup.setAccessible(true);
            for (float yaw : new float[] {0, 90, 179}) for (float pitch : new float[] {-65, 0, 75}) {
                for (float roll : new float[] {-65, 0, 65}) {
                    var state = new AvatarRenderState();
                    ((WindrunnerRenderState) state).windrunner$flight(new WindrunnerRenderState.Flight(
                            true, WindrunnerGlide.GLIDING, yaw, pitch, roll, 1, 0, 0, 1));
                    var pose = new PoseStack();
                    setup.invoke(renderer, state, pose, yaw, 1F);
                    // Complete the native renderer's model coordinate transform.
                    pose.scale(-1, -1, 1);
                    pose.scale(0.9375F, 0.9375F, 0.9375F);
                    pose.translate(0, -1.501F, 0);
                    Vector3f chest = pose.last().pose().transformPosition(new Vector3f(0, 4F / 16, 0));
                    if (chest.distance(new Vector3f(0, 0.3F, 0)) > 0.001) {
                        throw new AssertionError("The wing-root pivot must stay fixed through pitch, bank and yaw: " + chest);
                    }
                }
            }

            Camera camera = client.gameRenderer.mainCamera();
            var align = Camera.class.getDeclaredMethod("alignWithEntity", float.class);
            align.setAccessible(true);
            align.invoke(camera, 0F);
            float startYaw = camera.yRot(), startPitch = camera.xRot();
            align.invoke(camera, 0.5F);
            float middleYaw = camera.yRot(), middlePitch = camera.xRot();
            align.invoke(camera, 1F);
            float yawStep = Mth.wrapDegrees(camera.yRot() - startYaw);
            float pitchStep = camera.xRot() - startPitch;
            if (Math.abs(yawStep) < 0.01) throw new AssertionError("Banked flight must move camera yaw between ticks");
            if (Math.abs(Mth.wrapDegrees(middleYaw - startYaw) - yawStep * 0.5F) > 0.001
                    || Math.abs(middlePitch - startPitch - pitchStep * 0.5F) > 0.001) {
                throw new AssertionError("First-person flight rotation must interpolate evenly across subframes");
            }
            float expected = Mth.wrapDegrees(WindrunnerGlide.of(client.player).yaw(1) - WindrunnerGlide.of(client.player).yaw(0));
            if (Math.abs(yawStep - expected) > 0.01) {
                throw new AssertionError("Camera and wings must use the same interpolated attitude");
            }
            checkLookEdges(camera, align, client);
            Windrunner.LOGGER.info("Glide torso pivot and camera subframe checks passed.");
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("Cannot exercise native flight rendering", exception);
        }
    }

    private static void checkLookEdges(Camera camera, java.lang.reflect.Method align, Minecraft client)
            throws ReflectiveOperationException {
        float savedYaw = client.player.getYRot(), savedOldYaw = client.player.yRotO;
        float savedPitch = client.player.getXRot(), savedOldPitch = client.player.xRotO;
        var flight = WindrunnerGlide.of(client.player);
        float centerYaw = flight.yaw(1), centerPitch = flight.pitch(1);
        for (int direction : new int[] {-1, 1}) {
            client.player.setYRot(centerYaw + direction * (WindrunnerGlideControls.LOOK_YAW_LIMIT + 20));
            client.player.yRotO = client.player.getYRot() - 3;
            align.invoke(camera, 1F);
            if (Math.abs(Mth.wrapDegrees(client.player.getYRot() - centerYaw))
                    > WindrunnerGlideControls.LOOK_YAW_LIMIT + 0.001F) {
                throw new AssertionError("First-person yaw must stop at the neck cone");
            }
            if (Math.abs(Mth.wrapDegrees(client.player.getYRot() - client.player.yRotO) - 3) > 0.001F) {
                throw new AssertionError("Yaw clamping must preserve the interpolation step at the cone edge");
            }
        }
        float minimumPitch = Math.max(centerPitch - WindrunnerGlideControls.LOOK_PITCH_LIMIT, -90);
        float maximumPitch = Math.min(centerPitch + WindrunnerGlideControls.LOOK_PITCH_LIMIT, 90);
        for (float outside : new float[] {minimumPitch - 20, maximumPitch + 20}) {
            client.player.setXRot(outside);
            client.player.xRotO = client.player.getXRot() - 3;
            align.invoke(camera, 1F);
            if (client.player.getXRot() < minimumPitch - 0.001F || client.player.getXRot() > maximumPitch + 0.001F) {
                throw new AssertionError("First-person pitch must stop at the neck cone and vanilla look limits");
            }
            if (Math.abs(client.player.getXRot() - client.player.xRotO - 3) > 0.001F) {
                throw new AssertionError("Pitch clamping must preserve the interpolation step at the cone edge");
            }
        }
        client.player.setYRot(savedYaw); client.player.yRotO = savedOldYaw;
        client.player.setXRot(savedPitch); client.player.xRotO = savedOldPitch;
    }
}
