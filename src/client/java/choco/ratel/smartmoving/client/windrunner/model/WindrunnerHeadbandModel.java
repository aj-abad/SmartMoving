package choco.ratel.smartmoving.client.windrunner.model;

import choco.ratel.smartmoving.windrunner.Windrunner;
import choco.ratel.smartmoving.windrunner.WindrunnerGlide;
import choco.ratel.smartmoving.client.windrunner.render.WindrunnerRenderState;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.util.Mth;

/** The headband's two tails, drawn in head space from the knot; they hang, sway and stream behind. */
public final class WindrunnerHeadbandModel extends EntityModel<AvatarRenderState> {
    public static final ModelLayerLocation LAYER = new ModelLayerLocation(Windrunner.id("windrunner_headband"), "main");
    private final ModelPart left, right;

    public WindrunnerHeadbandModel(ModelPart root) {
        super(root);
        left = root.getChild("tail_left");
        right = root.getChild("tail_right");
    }

    /** Shares the sash texture; the knot sits on the helmet layer's inflated back face at the band. */
    public static LayerDefinition create() {
        var mesh = new MeshDefinition();
        mesh.getRoot().addOrReplaceChild("tail_left", CubeListBuilder.create().texOffs(22, 0)
                .addBox(-0.6F, 0, 0, 1.2F, 6, 0.3F), PartPose.offset(0.6F, -5.2F, 5.0F));
        mesh.getRoot().addOrReplaceChild("tail_right", CubeListBuilder.create().texOffs(26, 0)
                .addBox(-0.6F, 0, 0, 1.2F, 4.5F, 0.3F), PartPose.offset(-0.6F, -5.2F, 5.0F));
        return LayerDefinition.create(mesh, 32, 16);
    }

    @Override public void setupAnim(AvatarRenderState state) {
        super.setupAnim(state);
        var flight = ((WindrunnerRenderState) state).windrunner$flight();
        boolean gliding = flight.mode() == WindrunnerGlide.GLIDING;
        float airflow = gliding ? Mth.clamp(flight.airspeed() / 1.2F, 0, 1) : 0;
        float walk = Math.min(1, state.walkAnimationSpeed);
        // Head pitch tilts the knot; counter it so the tails keep hanging, never swinging into the head.
        float lift = gliding ? 0.1F : 0.2F + walk * 0.7F - state.xRot * Mth.DEG_TO_RAD;
        float rate = Mth.lerp(airflow, 0.15F, 1.8F), flutter = Mth.lerp(airflow, 0.06F, 0.2F);
        left.xRot = Math.max(0.05F, lift + Mth.sin(state.ageInTicks * rate) * flutter);
        right.xRot = Math.max(0.05F, lift + Mth.sin(state.ageInTicks * rate + 2.1F) * flutter);
        left.zRot = -0.12F - Mth.sin(state.ageInTicks * 0.11F) * 0.05F;
        right.zRot = 0.12F + Mth.sin(state.ageInTicks * 0.11F + 1) * 0.05F;
    }
}
