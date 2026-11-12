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

/** The robes' knotted sash, drawn in body space; its two tails trail with walking and airflow. */
public final class WindrunnerSashModel extends EntityModel<AvatarRenderState> {
    public static final ModelLayerLocation SASH = new ModelLayerLocation(Windrunner.id("windrunner_sash"), "main");
    private final ModelPart left, right;

    public WindrunnerSashModel(ModelPart root) {
        super(root);
        left = root.getChild("tail_left");
        right = root.getChild("tail_right");
    }

    public static LayerDefinition sash() {
        var mesh = new MeshDefinition();
        // The knot sits just proud of the robe layer's inflated waist at the back of the body.
        mesh.getRoot().addOrReplaceChild("knot", CubeListBuilder.create().texOffs(0, 0)
                .addBox(-2, 9.5F, 2.4F, 4, 2, 1), PartPose.ZERO);
        mesh.getRoot().addOrReplaceChild("tail_left", CubeListBuilder.create().texOffs(10, 0)
                .addBox(-0.9F, 0, 0, 1.8F, 11, 0.5F), PartPose.offset(1.1F, 11, 2.9F));
        mesh.getRoot().addOrReplaceChild("tail_right", CubeListBuilder.create().texOffs(16, 0)
                .addBox(-0.9F, 0, 0, 1.8F, 9, 0.5F), PartPose.offset(-1.1F, 11, 2.9F));
        return LayerDefinition.create(mesh, 32, 16);
    }

    @Override public void setupAnim(AvatarRenderState state) {
        super.setupAnim(state);
        var flight = ((WindrunnerRenderState) state).windrunner$flight();
        float age = state.ageInTicks;
        // In a glide the body lies along the airflow, so the tails stream almost flat behind the hips.
        float airflow = flight.mode() == WindrunnerGlide.GLIDING ? Mth.clamp(flight.airspeed() / 1.2F, 0, 1) : 0;
        float walk = Math.min(1, state.walkAnimationSpeed);
        float lift = flight.mode() == WindrunnerGlide.GLIDING
                ? Mth.lerp(flight.deployment(), 0.2F, 0.12F)
                : 0.06F + walk * 0.55F + (state.isCrouching ? 0.45F : 0);
        float flutter = Mth.lerp(airflow, 0.04F, 0.16F);
        left.xRot = lift + Mth.sin(age * Mth.lerp(airflow, 0.12F, 1.4F)) * flutter;
        right.xRot = lift + Mth.sin(age * Mth.lerp(airflow, 0.12F, 1.4F) + 1.7F) * flutter;
        left.zRot = -0.05F - Mth.sin(age * 0.09F) * 0.03F;
        right.zRot = 0.05F + Mth.sin(age * 0.09F + 1) * 0.03F;
    }
}
