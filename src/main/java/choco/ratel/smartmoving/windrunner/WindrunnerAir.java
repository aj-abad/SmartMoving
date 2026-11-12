package choco.ratel.smartmoving.windrunner;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

/**
 * The moving air a glide flies through, in blocks/tick: a breeze that strengthens with rain and storms
 * and with height above the ground, a small updraft over exposed lava, and the End's vacuum. The
 * optional Wind mod supplies the horizontal breeze when installed.
 */
public final class WindrunnerAir {
    public record Conditions(Vec3 wind, boolean vacuum) {
        public static final Conditions CALM = new Conditions(Vec3.ZERO, false);
    }

    private static final MethodHandle WIND = findWind();

    private WindrunnerAir() {}

    public static Conditions sample(Level level, Vec3 position) {
        if (level.dimension() == Level.END) return new Conditions(Vec3.ZERO, true);
        BlockPos pos = BlockPos.containing(position.add(0, 1, 0));
        double surface = level.getHeight(Heightmap.Types.MOTION_BLOCKING, pos.getX(), pos.getZ());
        boolean exposed = level.canSeeSky(pos) && position.y + 1 >= surface;
        Vec3 weather = level.canHaveWeather()
                ? field(level.getGameTime(), position, position.y - surface, exposed, level.getRainLevel(1), level.getThunderLevel(1))
                : Vec3.ZERO;
        double thermal = thermal(level.getGameTime(), lavaDistance(level, position));
        return new Conditions(windMod(level, position.add(0, 1, 0), weather).add(0, thermal, 0), false);
    }

    /** A small updraft above exposed lava; roofs and other surfaces interrupt the rising air. */
    static double thermal(long tick, double distance) {
        double reach = Mth.clamp(1 - distance / 24, 0, 1);
        return reach * 0.18 * (0.85 + 0.15 * Math.sin(tick * 0.03));
    }

    private static double lavaDistance(Level level, Vec3 position) {
        BlockPos.MutableBlockPos probe = BlockPos.containing(position).mutable();
        for (int down = 0; down < 24 && probe.getY() >= level.getMinY(); down++, probe.move(0, -1, 0)) {
            var fluid = level.getFluidState(probe);
            if (fluid.is(FluidTags.LAVA)) return Math.max(0, position.y - (probe.getY() + fluid.getHeight(level, probe)));
            if (!fluid.isEmpty() || !level.getBlockState(probe).getCollisionShape(level, probe).isEmpty()) break;
        }
        return Double.POSITIVE_INFINITY;
    }

    /** Continuous across time and space, including dry biomes inside the same storm system. */
    static Vec3 field(long tick, Vec3 position, double height, boolean exposed, float rain, float storm) {
        if (!exposed) return Vec3.ZERO;
        rain = Mth.clamp(rain, 0, 1);
        storm = Mth.clamp(storm, 0, rain);
        double exposure = Mth.clamp(height / 32.0, 0, 1);
        exposure = exposure * exposure * (3 - 2 * exposure);
        double phase = tick * 0.014 + position.x * 0.002 + position.z * 0.003;
        double gust = 0.55 + Math.sin(phase) * 0.3 + Math.sin(phase * 2.31) * 0.15;
        double heading = 0.8 + Math.sin(tick * 0.0007) * 0.7
                + Math.sin(position.x * 0.0007 + position.z * 0.0005) * 0.4;
        double speed = (0.055 + rain * 0.10 + storm * 0.18) * (0.65 + 0.35 * gust) * (0.12 + 0.88 * exposure);
        double vertical = storm * exposure * (Math.sin(phase * 0.77) * 0.28 + Math.sin(phase * 1.13) * 0.12 - 0.05);
        return new Vec3(Math.cos(heading) * speed, vertical, Math.sin(heading) * speed);
    }

    /** Eases shelter and biome boundaries; a dimension change to vacuum takes effect immediately. */
    public static final class Sampler {
        private Conditions current = Conditions.CALM;
        private Vec3 previousPosition;

        public Conditions tick(Level level, Vec3 position) {
            Conditions target = sample(level, position);
            boolean reset = previousPosition == null || previousPosition.distanceToSqr(position) > 256;
            previousPosition = position;
            current = reset || target.vacuum() || current.vacuum() ? target
                    : new Conditions(current.wind().lerp(target.wind(), 0.08), false);
            return current;
        }
    }

    private static MethodHandle findWind() {
        if (!FabricLoader.getInstance().isModLoaded("wind")) return null;
        try {
            Class<?> api = Class.forName("com.ajabad.wind.api.Wind");
            return MethodHandles.publicLookup().findStatic(api, "at", MethodType.methodType(Vec3.class, Level.class, Vec3.class));
        } catch (ReflectiveOperationException e) {
            Windrunner.LOGGER.warn("Wind is installed but its sampling API is unavailable; using the built-in breeze", e);
            return null;
        }
    }

    private static Vec3 windMod(Level level, Vec3 position, Vec3 fallback) {
        if (WIND == null) return fallback;
        try {
            Vec3 wind = (Vec3) WIND.invokeExact(level, position);
            return new Vec3(wind.x, fallback.y, wind.z);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable e) {
            throw new IllegalStateException("Wind sampling failed", e);
        }
    }
}
