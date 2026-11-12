package choco.ratel.smartmoving.windrunner;

import choco.ratel.smartmoving.client.windrunner.model.WindrunnerRibs;
import choco.ratel.smartmoving.client.windrunner.model.WindrunnerMembrane;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;

/** Check the actual submitted materials and 3D spars, including UV transparency and face winding. */
final class WindrunnerMaterialClientTest {
    static void run(Minecraft client, WindrunnerMembrane membrane) {
        var pose = new PoseStack().last();
        var fabric = new Capture(texture(client, WindrunnerMembrane.TEXTURE));
        membrane.render(pose, fabric);
        fabric.checkQuads(true);
        var timber = new Capture(texture(client, WindrunnerRibs.TEXTURE));
        WindrunnerRibs.capture(membrane, 0xF000F0).render(pose, timber);
        timber.checkQuads(false);
        timber.checkRibUvAxes();
        if (timber.vertices.size() < 100) throw new AssertionError("The wing must have visible wooden structure");
    }

    private static BufferedImage texture(Minecraft client, Identifier id) {
        try (var input = client.getResourceManager().getResourceOrThrow(id).open()) {
            return ImageIO.read(input);
        } catch (IOException exception) { throw new AssertionError("Missing wingsuit material " + id, exception); }
    }

    private static final class Vertex {
        Vec3 position, normal;
        float u, v;
    }

    private static final class Capture implements VertexConsumer {
        final BufferedImage texture;
        final List<Vertex> vertices = new ArrayList<>();
        private Vertex vertex;
        Capture(BufferedImage texture) { this.texture = texture; }
        public VertexConsumer addVertex(float x, float y, float z) {
            vertex = new Vertex(); vertex.position = new Vec3(x, y, z); vertices.add(vertex); return this;
        }
        public VertexConsumer setNormal(float x, float y, float z) { vertex.normal = new Vec3(x, y, z); return this; }
        public VertexConsumer setUv(float u, float v) { vertex.u = u; vertex.v = v; return this; }
        public VertexConsumer setColor(int color) { return this; }
        public VertexConsumer setColor(int r, int g, int b, int a) { return this; }
        public VertexConsumer setUv1(int u, int v) { return this; }
        public VertexConsumer setUv2(int u, int v) { return this; }
        public VertexConsumer setUv3(float u, float v) { return this; }
        public VertexConsumer setLineWidth(float width) { return this; }

        void checkQuads(boolean twoSided) {
            if (vertices.size() % 4 != 0) throw new AssertionError("Material geometry must submit complete quads");
            for (int i = 0; i < vertices.size(); i += 4) {
                Vertex a = vertices.get(i), b = vertices.get(i + 1), c = vertices.get(i + 2), d = vertices.get(i + 3);
                Vec3 face = b.position.subtract(a.position).cross(c.position.subtract(a.position));
                if (face.lengthSqr() < 1.0E-15) face = c.position.subtract(a.position).cross(d.position.subtract(a.position));
                if (!Double.isFinite(face.lengthSqr()) || face.normalize().dot(a.normal) < 0.5) {
                    throw new AssertionError("Wing faces and stick caps must have outward normals");
                }
                // Sample inside every quad: opaque artwork must not accidentally cut holes in the wing/frame.
                for (int y = 1; y < 10; y++) for (int x = 1; x < 10; x++) {
                    float s = x / 10F, t = y / 10F;
                    float u = (a.u * (1 - s) + b.u * s) * (1 - t) + (d.u * (1 - s) + c.u * s) * t;
                    float v = (a.v * (1 - s) + b.v * s) * (1 - t) + (d.v * (1 - s) + c.v * s) * t;
                    if ((texture.getRGB((int) (u * texture.getWidth()), (int) (v * texture.getHeight())) >>> 24) != 255) {
                        throw new AssertionError("Material UVs must remain inside the opaque membrane or stick pixels");
                    }
                }
                if (twoSided && i % 8 == 0 && a.normal.dot(vertices.get(i + 4).normal) > -0.99) {
                    throw new AssertionError("The membrane needs correctly lit front and back faces");
                }
            }
        }

        void checkRibUvAxes() {
            int sides = 0, caps = 0;
            for (int i = 0; i < vertices.size(); i += 4) {
                Vertex a = vertices.get(i), b = vertices.get(i + 1), c = vertices.get(i + 2), d = vertices.get(i + 3);
                Vertex[] ring = {a, b, c, d, a};
                float uMin = a.u, uMax = a.u, vMin = a.v, vMax = a.v;
                for (int edge = 0; edge < 4; edge++) {
                    Vertex from = ring[edge], to = ring[edge + 1];
                    boolean shiftsU = Math.abs(to.u - from.u) > 1.0E-6F, shiftsV = Math.abs(to.v - from.v) > 1.0E-6F;
                    if (shiftsU == shiftsV) throw new AssertionError("Stick texels must sit square on each rib, never diagonally");
                    uMin = Math.min(uMin, to.u); uMax = Math.max(uMax, to.u);
                    vMin = Math.min(vMin, to.v); vMax = Math.max(vMax, to.v);
                }
                int column = (int) (uMin * 16);
                if ((int) (uMax * 16) != column || column < WindrunnerRibs.FIRST_COLUMN
                        || column >= WindrunnerRibs.FIRST_COLUMN + WindrunnerRibs.COLUMNS
                        || (uMax - uMin) * 16 < 0.9F || (vMax - vMin) * 16 < WindrunnerRibs.ROWS - 0.1F
                        || (int) (vMin * 16) != 15 - column) {
                    throw new AssertionError("Each rib pixel must copy one whole stick column: edge, core and shadow");
                }
                if (Math.abs(b.v - a.v) < 1.0E-6F) {
                    // A side pixel runs u down the shaft and must be one model pixel long.
                    double length = b.position.distanceTo(a.position) * 16;
                    if (length < 0.9 || length > 1.1) throw new AssertionError("Rib pixels must match native model pixel density");
                    sides++;
                } else caps++;
            }
            int ribs = WindrunnerMembrane.PANELS + 1;
            if (caps != ribs * 2 || sides % 4 != 0 || sides < ribs * 4 * 6) {
                throw new AssertionError("Every rib needs two caps and a run of four-sided pixels");
            }
        }
    }
}
