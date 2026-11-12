package choco.ratel.smartmoving.client.windrunner.render;

import choco.ratel.smartmoving.windrunner.Windrunner;
import choco.ratel.smartmoving.windrunner.WindrunnerItems;
import choco.ratel.smartmoving.windrunner.WindrunnerGlide;
import choco.ratel.smartmoving.client.windrunner.model.WindrunnerHeadbandModel;
import choco.ratel.smartmoving.client.windrunner.model.WindrunnerSashModel;
import choco.ratel.smartmoving.client.windrunner.model.WindrunnerGlideAnimation;
import choco.ratel.smartmoving.client.windrunner.model.WindrunnerMembrane;
import choco.ratel.smartmoving.client.windrunner.model.WindrunnerRibs;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;

public final class WindrunnerLayer extends RenderLayer<AvatarRenderState, PlayerModel> {
    private static final Identifier SASH_TEXTURE = Windrunner.id("textures/entity/windrunner_sash.png");
    private final WindrunnerSashModel sash;
    private final WindrunnerHeadbandModel headband;

    public WindrunnerLayer(RenderLayerParent<AvatarRenderState, PlayerModel> parent, EntityRendererProvider.Context context) {
        super(parent);
        sash = new WindrunnerSashModel(context.bakeLayer(WindrunnerSashModel.SASH));
        headband = new WindrunnerHeadbandModel(context.bakeLayer(WindrunnerHeadbandModel.LAYER));
    }

    @Override public void submit(PoseStack pose, SubmitNodeCollector collector, int light, AvatarRenderState state, float yaw, float pitch) {
        var flight = ((WindrunnerRenderState) state).windrunner$flight();
        if (state.isInvisible || state.isSpectator) return;
        if (state.headEquipment.is(WindrunnerItems.WINDRUNNER_HEADBAND)) {
            pose.pushPose();
            getParentModel().head.translateAndRotate(pose);
            collector.submitModel(headband, state, pose, SASH_TEXTURE, light, OverlayTexture.NO_OVERLAY, state.outlineColor);
            pose.popPose();
        }
        if (!flight.equipped()) return;
        pose.pushPose();
        getParentModel().body.translateAndRotate(pose);
        collector.submitModel(sash, state, pose, SASH_TEXTURE, light, OverlayTexture.NO_OVERLAY, state.outlineColor);
        pose.popPose();
        if (flight.mode() == WindrunnerGlide.GLIDING) {
            var animation = WindrunnerGlideAnimation.sample(flight, state.ageInTicks, state.id);
            for (int side : new int[] {-1, 1}) {
                // Capture geometry now; deferred drawing must never retain this shared mutable player model.
                var membrane = WindrunnerMembrane.capture(getParentModel(), animation, side, light);
                var frame = WindrunnerRibs.capture(membrane, light);
                collector.submitCustomGeometry(pose, RenderTypes.entityCutout(WindrunnerMembrane.TEXTURE), membrane);
                collector.submitCustomGeometry(pose, RenderTypes.entityCutout(WindrunnerRibs.TEXTURE), frame);
                if (state.outlineColor != 0) {
                    collector.submitCustomGeometry(pose, RenderTypes.outline(WindrunnerMembrane.TEXTURE), membrane.outline(state.outlineColor));
                    collector.submitCustomGeometry(pose, RenderTypes.outline(WindrunnerRibs.TEXTURE), frame.outline(state.outlineColor));
                }
            }
        }
    }
}
