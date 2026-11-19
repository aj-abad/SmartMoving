package choco.ratel.smartmoving.client.windrunner;

import choco.ratel.smartmoving.client.windrunner.model.WindrunnerHeadbandModel;
import choco.ratel.smartmoving.client.windrunner.model.WindrunnerSashModel;
import choco.ratel.smartmoving.windrunner.WindrunnerParticles;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.particle.v1.ParticleProviderRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.ModelLayerRegistry;

/** Client-side registration for the Windrunner set: glide packets, per-tick controls, sound and models. */
public final class WindrunnerClient {
    private WindrunnerClient() {}

    public static void init() {
        WindrunnerGlideControls.init();
        WindrunnerWallRun.init();
        ClientTickEvents.START_CLIENT_TICK.register(WindrunnerGlideControls::beforeTick);
        ClientTickEvents.END_CLIENT_TICK.register(WindrunnerGlideControls::afterTick);
        ClientTickEvents.END_CLIENT_TICK.register(WindrunnerGlideSound::update);
        ModelLayerRegistry.registerModelLayer(WindrunnerSashModel.SASH, WindrunnerSashModel::sash);
        ModelLayerRegistry.registerModelLayer(WindrunnerHeadbandModel.LAYER, WindrunnerHeadbandModel::create);
        ParticleProviderRegistry.getInstance().register(WindrunnerParticles.BOOM, WindrunnerBoomParticle.Provider::new);
    }
}
