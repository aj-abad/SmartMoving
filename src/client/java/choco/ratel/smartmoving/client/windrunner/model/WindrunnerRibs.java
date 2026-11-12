package choco.ratel.smartmoving.client.windrunner.model;

import choco.ratel.smartmoving.windrunner.Windrunner;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;

/** Five straight stick ribs share the fan's shoulder hinge and protrude beyond its membrane leaves. */
public final class WindrunnerRibs implements SubmitNodeCollector.CustomGeometryRenderer {
    public static final Identifier TEXTURE = Windrunner.id("textures/entity/windrunner_rib.png");
    private static final double HALF_WIDTH = 0.35 / 16;
    // Like the vanilla stick it replaces, the rib sprite is drawn diagonally, but every column from 3
    // to 13 stacks the same cross-section straight down: edge, core, shadow. Each rib is built from
    // one-pixel segments that copy those axis-aligned columns in turn, so the texels sit square.
    public static final int FIRST_COLUMN = 3, COLUMNS = 11, ROWS = 3;
    private static final float INSET = 0.02F;
    private final List<Spar> spars;
    private final int light, color;

    private record Spar(Vec3 start, Vec3 end) {}

    private WindrunnerRibs(List<Spar> spars, int light, int color) {
        this.spars = spars; this.light = light; this.color = color;
    }

    public static WindrunnerRibs capture(WindrunnerMembrane membrane, int light) {
        List<Spar> spars = new ArrayList<>();
        for (int rib = 0; rib <= WindrunnerMembrane.PANELS; rib++) {
            Vec3 start = membrane.hinge(), end = membrane.ribTip(rib);
            end = end.add(end.subtract(start).normalize().scale(0.65 / 16));
            spars.add(new Spar(start, end));
        }
        return new WindrunnerRibs(List.copyOf(spars), light, 0xFFFFFFFF);
    }

    public WindrunnerRibs outline(int color) { return new WindrunnerRibs(spars, light, color); }

    /** Number of one-pixel texture segments along a rib of the given length. */
    public static int segments(double length) { return Math.max(1, (int) Math.round(length * 16)); }

    @Override public void render(PoseStack.Pose pose, VertexConsumer buffer) {
        for (Spar spar : spars) {
            Vec3 shaft = spar.end.subtract(spar.start);
            Vec3 along = shaft.normalize();
            if (along.lengthSqr() < 0.5) continue;
            Vec3 reference = Math.abs(along.z) < 0.9 ? new Vec3(0, 0, 1) : new Vec3(0, 1, 0);
            Vec3 across = along.cross(reference).normalize();
            Vec3 normal = across.cross(along).normalize();
            Vec3 width = across.scale(HALF_WIDTH), depth = normal.scale(HALF_WIDTH);
            Vec3[] offsets = {width.add(depth), depth.subtract(width), width.add(depth).scale(-1), width.subtract(depth)};
            Vec3[] outward = new Vec3[4];
            for (int face = 0; face < 4; face++) outward[face] = offsets[face].add(offsets[(face + 1) % 4]).normalize();
            int segments = segments(shaft.length());
            Vec3[] start = corners(spar.start, offsets);
            for (int corner = 0; corner < 4; corner++) cap(pose, buffer, start[corner], FIRST_COLUMN, corner, along.scale(-1));
            for (int segment = 0; segment < segments; segment++) {
                int column = FIRST_COLUMN + segment % COLUMNS;
                Vec3[] end = corners(spar.start.add(shaft.scale((segment + 1) / (double) segments)), offsets);
                for (int face = 0; face < 4; face++) {
                    int next = (face + 1) % 4;
                    vertex(pose, buffer, start[face], column, 0, 0, outward[face]);
                    vertex(pose, buffer, end[face], column, 1, 0, outward[face]);
                    vertex(pose, buffer, end[next], column, 1, 1, outward[face]);
                    vertex(pose, buffer, start[next], column, 0, 1, outward[face]);
                }
                if (segment == segments - 1) {
                    for (int corner = 3; corner >= 0; corner--) cap(pose, buffer, end[corner], column, corner, along);
                }
                start = end;
            }
        }
    }

    private static Vec3[] corners(Vec3 center, Vec3[] offsets) {
        Vec3[] corners = new Vec3[4];
        for (int corner = 0; corner < 4; corner++) corners[corner] = center.add(offsets[corner]);
        return corners;
    }

    private void cap(PoseStack.Pose pose, VertexConsumer buffer, Vec3 point, int column, int corner, Vec3 normal) {
        vertex(pose, buffer, point, column, corner / 2, corner == 1 || corner == 2 ? 1 : 0, normal);
    }

    private void vertex(PoseStack.Pose pose, VertexConsumer buffer, Vec3 point, int column, float along, float across, Vec3 normal) {
        // One sprite column per segment: u runs down the shaft, v runs across it from edge to shadow.
        // The inset keeps nearest-neighbour sampling off the transparent or neighbouring texels.
        float u = (column + INSET + along * (1 - 2 * INSET)) / 16;
        float v = (15 - column + INSET + across * (ROWS - 2 * INSET)) / 16;
        buffer.addVertex(pose, (float) point.x, (float) point.y, (float) point.z).setColor(color).setUv(u, v)
                .setOverlay(OverlayTexture.NO_OVERLAY).setLight(light)
                .setNormal(pose, (float) normal.x, (float) normal.y, (float) normal.z);
    }
}
