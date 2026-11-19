package choco.ratel.smartmoving.windrunner;

import net.fabricmc.fabric.api.particle.v1.FabricParticleTypes;
import net.minecraft.core.Registry;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.registries.BuiltInRegistries;

public final class WindrunnerParticles {
    /** The glide boost's white shockwave ring; always shown, like the vanilla sonic boom it replaces. */
    public static final SimpleParticleType BOOM = Registry.register(BuiltInRegistries.PARTICLE_TYPE,
            Windrunner.id("windrunner_boom"), FabricParticleTypes.simple(true));

    private WindrunnerParticles() {}

    public static void init() {}
}
