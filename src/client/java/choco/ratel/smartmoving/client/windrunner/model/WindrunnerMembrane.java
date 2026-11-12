package choco.ratel.smartmoving.client.windrunner.model;

import choco.ratel.smartmoving.windrunner.Windrunner;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/** Four flat fan leaves on five shoulder-hinged ribs, captured from the posed arm and foot. */
public final class WindrunnerMembrane implements SubmitNodeCollector.CustomGeometryRenderer {
    public static final Identifier TEXTURE = Windrunner.id("textures/entity/windrunner_membrane.png");
    public static final int PANELS = 4;
    // Subdivide only for pixel-scale texture repeats and hems; each fan leaf stays planar.
    private static final float[] GRID = {0, 0.035F, 0.125F, 0.25F, 0.375F, 0.5F, 0.625F, 0.75F, 0.875F, 0.965F, 1};
    public static final int SIZE = GRID.length;
    private final Vec3[] points;
    private final int light, outlineColor;

    private WindrunnerMembrane(Vec3[] points, int light, int outlineColor) {
        this.points = points;
        this.light = light;
        this.outlineColor = outlineColor;
    }

    public static WindrunnerMembrane capture(PlayerModel model, WindrunnerGlideAnimation.Pose animation, int side, int light) {
        Matrix4f arm = transform(model, side < 0 ? model.rightArm : model.leftArm);
        Matrix4f leg = transform(model, side < 0 ? model.rightLeg : model.leftLeg);
        Vec3 shoulder = point(arm, -side * 0.8F, -0.5F, 2.2F);
        Vec3 foot = point(leg, side * 1.8F, 11.5F, 2.2F);
        Vec3 wrist = point(arm, -side * 0.8F, 9F, 2.2F);
        Vec3 inner = foot.subtract(shoulder), outer = wrist.subtract(shoulder);
        Vec3 normal = inner.cross(outer).normalize().scale(-side);
        Vec3[] tips = new Vec3[PANELS + 1];
        tips[0] = foot;
        tips[PANELS] = wrist;
        for (int rib = 1; rib < PANELS; rib++) {
            float u = rib / (float) PANELS;
            // The wrist draws the fan open first; successive ribs follow toward the foot.
            float delay = (1 - u) * 0.30F;
            float progress = Mth.clamp((animation.opening() - delay) / (1 - delay), 0, 1);
            progress = progress * progress * (3 - 2 * progress);
            float spread = u * Mth.lerp(progress, 0.12F, 1F);
            double length = Mth.lerp(u, inner.length(), outer.length());
            Vec3 direction = inner.lerp(outer, spread).normalize();
            float folded = Math.max(animation.tuck(), 1 - animation.opening());
            float pleat = (rib % 2 == 0 ? -1 : 1) * Mth.lerp(folded, 0.025F, 0.065F);
            float flutter = animation.wave(side * 2.7F + rib * 1.4F) * animation.airflow()
                    * (1 - folded) * 0.022F;
            // Rotate a straight rib out of the fan plane. Its entire leaf follows as a flat facet.
            float angle = pleat + flutter;
            direction = direction.scale(Math.cos(angle)).add(normal.scale(Math.sin(angle)));
            tips[rib] = shoulder.add(direction.scale(length));
        }
        Vec3[] points = new Vec3[SIZE * SIZE];
        for (int row = 0; row < SIZE; row++) {
            float v = GRID[row];
            for (int column = 0; column < SIZE; column++) {
                float leaf = GRID[column] * PANELS;
                int panel = Math.min((int) leaf, PANELS - 1);
                points[row * SIZE + column] = shoulder.lerp(tips[panel].lerp(tips[panel + 1], leaf - panel), v);
            }
        }
        return new WindrunnerMembrane(points, light, 0);
    }

    private static Matrix4f transform(PlayerModel model, ModelPart part) {
        PoseStack pose = new PoseStack();
        model.root().translateAndRotate(pose);
        part.translateAndRotate(pose);
        return new Matrix4f(pose.last().pose());
    }

    private static Vec3 point(Matrix4f transform, float x, float y, float z) {
        Vector3f p = transform.transformPosition(x / 16, y / 16, z / 16, new Vector3f());
        return new Vec3(p.x, p.y, p.z);
    }

    public Vec3 point(int column, int row) { return points[row * SIZE + column]; }
    public static float parameter(int index) { return GRID[index]; }
    public Vec3 hinge() { return point(0, 0); }
    public Vec3 ribTip(int rib) {
        for (int column = 0; column < SIZE; column++) {
            if (GRID[column] == rib / (float) PANELS) return point(column, SIZE - 1);
        }
        throw new IllegalArgumentException("Invalid fan rib: " + rib);
    }

    public WindrunnerMembrane outline(int color) { return new WindrunnerMembrane(points, light, color); }

    @Override public void render(PoseStack.Pose pose, VertexConsumer buffer) {
        for (int row = 0; row < SIZE - 1; row++) for (int column = 0; column < SIZE - 1; column++) {
            Vec3 a = point(column, row), b = point(column + 1, row);
            Vec3 c = point(column + 1, row + 1), d = point(column, row + 1);
            Vec3 normal = b.subtract(a).cross(d.subtract(a)).normalize();
            // The first row tapers to the shoulder: its quads are triangles with a repeated tip.
            if (normal.lengthSqr() < 0.5) normal = c.subtract(a).cross(d.subtract(a)).normalize();
            boolean hem = row == 0 || row == SIZE - 2 || column == 0 || column == SIZE - 2;
            int panel = Math.min((int) (GRID[column] * PANELS), PANELS - 1);
            int color = outlineColor != 0 ? outlineColor : hem ? 0xFFB7A5BC : panel % 2 == 0 ? 0xFFFFFFFF : 0xFFF1EAF3;
            // Repeat the cloth's opaque 6x6 interior at roughly native pixel density.
            int tileU = (int) ((GRID[column] + GRID[column + 1]) * 0.5F * 2);
            int tileV = (int) ((GRID[row] + GRID[row + 1]) * 0.5F * 4);
            float u0 = (5 + (GRID[column] * 2 - tileU) * 6) / 16;
            float u1 = (5 + (GRID[column + 1] * 2 - tileU) * 6) / 16;
            float v0 = (5 + (GRID[row] * 4 - tileV) * 6) / 16;
            float v1 = (5 + (GRID[row + 1] * 4 - tileV) * 6) / 16;
            vertex(pose, buffer, a, u0, v0, normal, color);
            vertex(pose, buffer, b, u1, v0, normal, color);
            vertex(pose, buffer, c, u1, v1, normal, color);
            vertex(pose, buffer, d, u0, v1, normal, color);
            // Both sides have outward-facing normals, so back lighting is correct while banking.
            normal = normal.scale(-1);
            vertex(pose, buffer, d, u0, v1, normal, color);
            vertex(pose, buffer, c, u1, v1, normal, color);
            vertex(pose, buffer, b, u1, v0, normal, color);
            vertex(pose, buffer, a, u0, v0, normal, color);
        }
    }

    private void vertex(PoseStack.Pose pose, VertexConsumer buffer, Vec3 point, float u, float v, Vec3 normal, int color) {
        buffer.addVertex(pose, (float) point.x, (float) point.y, (float) point.z).setColor(color).setUv(u, v)
                .setOverlay(OverlayTexture.NO_OVERLAY).setLight(light)
                .setNormal(pose, (float) normal.x, (float) normal.y, (float) normal.z);
    }
}
