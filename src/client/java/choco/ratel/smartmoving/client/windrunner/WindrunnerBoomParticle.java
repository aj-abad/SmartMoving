package choco.ratel.smartmoving.client.windrunner;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.SonicBoomParticle;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.util.RandomSource;

/**
 * The glide boost's ring: vanilla's sonic boom (16 ticks, full bright) over the white frames in
 * particles/windrunner_boom.json, without the random grey tint vanilla gives each one.
 */
public final class WindrunnerBoomParticle extends SonicBoomParticle {
    private WindrunnerBoomParticle(ClientLevel level, double x, double y, double z, SpriteSet sprites) {
        super(level, x, y, z, 0, sprites);
        setColor(1, 1, 1);
    }

    public record Provider(SpriteSet sprites) implements ParticleProvider<SimpleParticleType> {
        @Override
        public Particle createParticle(SimpleParticleType type, ClientLevel level, double x, double y, double z,
                                       double dx, double dy, double dz, RandomSource random) {
            return new WindrunnerBoomParticle(level, x, y, z, sprites);
        }
    }
}
