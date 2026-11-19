package choco.ratel.smartmoving.client.windrunner.model;

import choco.ratel.smartmoving.windrunner.Windrunner;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.EntityModelSet;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;

/**
 * The robes' chest drawn close to the body: inflated 0.65 instead of the outer armor model's full pixel,
 * still above the leggings' 0.5 so the trousers' waistband stays under the jacket.
 */
public final class WindrunnerFittedRobes {
    public static final ModelLayerLocation LAYER = new ModelLayerLocation(Windrunner.id("windrunner_robes"), "fitted");
    private static final float FIT = 0.65F;
    private static EntityModelSet bakedFrom;
    private static HumanoidModel<HumanoidRenderState> model;

    private WindrunnerFittedRobes() {}

    public static LayerDefinition create() {
        return LayerDefinition.create(HumanoidModel.createArmorMeshSet(new CubeDeformation(0.5F), new CubeDeformation(FIT)).chest(), 64, 32);
    }

    /** Baked on first use and again after a resource reload replaces the model set. */
    public static HumanoidModel<HumanoidRenderState> model() {
        EntityModelSet models = Minecraft.getInstance().getEntityModels();
        if (model == null || models != bakedFrom) {
            model = new HumanoidModel<>(models.bakeLayer(LAYER));
            bakedFrom = models;
        }
        return model;
    }
}
