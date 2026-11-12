package choco.ratel.smartmoving.windrunner;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Headband, pants and boots abilities outside the glide. Ordinary movement stays client-authoritative as in vanilla,
 * so the client applies each impulse; the server keeps the once-per-airtime charges, the fall-damage
 * bookkeeping, and the effects other players see.
 */
public final class WindrunnerMoves {
    // 3 and 4 were the slow fall's start and stop; left unused so the other ids keep their values.
    public static final int DOUBLE_JUMP = 0, AIR_DASH = 1, GROUND_DASH = 2, DASH_JUMP = 5,
            WALL_RUN_START = 6, WALL_RUN_STOP = 7, WALL_KICK = 8;
    /** The longest a run can last (a run up stops sooner); past it the server stops trusting the flag. */
    private static final int WALL_RUN_MAX_TICKS = 40;
    public static final double DOUBLE_JUMP_SPEED = 0.55, JUMP_STEER = 0.12;
    public static final double DASH_SPEED = 1.2, DASH_LIFT = 0.12, DASH_SIDE_KEEP = 0.3;
    /** A ground dash leaves as a low hop, about a third of a block, and slides on after landing. */
    public static final double GROUND_DASH_HOP = 0.2;
    /** How long a ground dash lasts: its hop, then the slide once it touches down again. */
    public static final int GROUND_DASH_TICKS = 12;
    /**
     * A ground dash that strays this far above or below where it started, off a cliff say, is no
     * longer on the ground: it ends, and the air dash and double jump apply as in any fall.
     */
    public static final double GROUND_DASH_ELEVATION = 0.6;
    /** The boots' fastest fall, 30 blocks/s against vanilla's 78; reached after about 19 blocks of falling. */
    public static final double FALL_CAP = 1.5;
    public static final int DASH_COOLDOWN = 10;
    /** Ticks the dash start's gust follows the dash, kicking up dust from the ground below it. */
    private static final int DASH_TRAIL_TICKS = 6;

    private final Player player;
    private boolean jumpSpent, dashSpent, wallRunning;
    private int wallRunTicks;
    private int dashCooldown, groundDashTicks, dashTrailTicks;
    private double groundDashY;

    public WindrunnerMoves(Player player) { this.player = player; }
    public static WindrunnerMoves of(Player player) { return ((WindrunnerUser) player).windrunner$moves(); }

    /** The boots' kick, scaled like a ground jump by jump strength and topped up by any Jump Boost. */
    public static double doubleJumpSpeed(double jumpScale, double jumpBoost) {
        return DOUBLE_JUMP_SPEED * jumpScale + jumpBoost;
    }

    public static Vec3 doubleJump(Vec3 velocity, Vec3 wish, double speed) {
        return new Vec3(velocity.x + wish.x * JUMP_STEER, Math.max(velocity.y, speed),
                velocity.z + wish.z * JUMP_STEER);
    }

    /** A ground jump launched from the dash's hop, keeping all of the dash's momentum. */
    public static Vec3 dashJump(Vec3 velocity, double jumpPower) {
        return new Vec3(velocity.x, Math.max(velocity.y, jumpPower), velocity.z);
    }

    /** Faster existing travel along the dash is kept; most sideways drift is shed so it reads as a turn. */
    public static Vec3 airDash(Vec3 velocity, Vec3 direction) {
        double along = velocity.x * direction.x + velocity.z * direction.z;
        double speed = Math.max(along, DASH_SPEED);
        double sideX = (velocity.x - direction.x * along) * DASH_SIDE_KEEP;
        double sideZ = (velocity.z - direction.z * along) * DASH_SIDE_KEEP;
        return new Vec3(direction.x * speed + sideX, Math.max(velocity.y, DASH_LIFT), direction.z * speed + sideZ);
    }

    /** The air dash's burst along the ground, launched as a low hop. */
    public static Vec3 groundDash(Vec3 velocity, Vec3 direction) {
        Vec3 dash = airDash(velocity, direction);
        return new Vec3(dash.x, GROUND_DASH_HOP, dash.z);
    }

    public static Vec3 capFall(Vec3 velocity) {
        return velocity.y < -FALL_CAP ? new Vec3(velocity.x, -FALL_CAP, velocity.z) : velocity;
    }


    /** Horizontal unit vector for vanilla-style movement input facing {@code yaw}; zero without input. */
    public static Vec3 wish(float yaw, float forward, float left) {
        double length = Math.sqrt(forward * forward + left * left);
        if (length < 1.0E-4) return Vec3.ZERO;
        double sin = Math.sin(Math.toRadians(yaw)), cos = Math.cos(Math.toRadians(yaw));
        return new Vec3((left * cos - forward * sin) / length, 0, (forward * cos + left * sin) / length);
    }

    /** As {@link #wish}, but an idle dash goes the way the player faces. */
    public static Vec3 dashDirection(float yaw, float forward, float left) {
        Vec3 wish = wish(yaw, forward, left);
        return wish == Vec3.ZERO ? wish(yaw, 1, 0) : wish;
    }

    private boolean grounded() {
        return player.onGround() || player.isInWater() || player.isInLava() || player.onClimbable()
                || player.getAbilities().flying || player.isPassenger();
    }

    private boolean able() {
        return player.isAlive() && !player.isSpectator() && !player.isSleeping() && !WindrunnerGlide.of(player).active();
    }

    public boolean airborne() { return !grounded() && able(); }

    /** True from a ground dash's launch until it lands and slides out, unless it left its starting height. */
    public boolean groundDashing() { return groundDashTicks > 0; }

    public boolean canDoubleJump() { return !jumpSpent && WindrunnerArmor.pants(player) && airborne(); }
    /** A jump out of a ground dash's hop is the ground jump, so the double jump stays in hand. */
    public boolean canDashJump() { return groundDashing() && airborne(); }
    /** A ground dash's hop is still on the ground for dashing: no air dash until it lands or leaves its height. */
    public boolean canAirDash() {
        return !dashSpent && !groundDashing() && dashCooldown == 0 && WindrunnerArmor.headband(player) && airborne();
    }
    public boolean canGroundDash() {
        return dashCooldown == 0 && WindrunnerArmor.headband(player) && player.onGround() && !player.isInWater()
                && !player.isInLava() && !player.isPassenger() && !player.getAbilities().flying && able();
    }
    /** Elytra, riptide and fluids keep their own speeds; the glide never reaches the travel hook at all. */
    public boolean capsFall() {
        return WindrunnerArmor.boots(player) && !player.isFallFlying() && !player.isAutoSpinAttack()
                && !player.getAbilities().flying && !player.isInWater() && !player.isInLava();
    }

    /**
     * Clamps once after travel rather than in each of SmartMoving's gravity paths. Client side only:
     * the local player's movement is client-authoritative, and the server accepts a slower fall.
     */
    public void afterTravel() {
        if (player.level().isClientSide() && capsFall()) player.setDeltaMovement(capFall(player.getDeltaMovement()));
    }

    public void spendJump() { jumpSpent = true; }
    /** A wall kick hands the pants' double jump back. */
    public void refreshJump() { jumpSpent = false; }
    public void spendDash() { dashSpent = true; dashCooldown = DASH_COOLDOWN; }
    /** A ground dash leaves the air dash in hand: it spends only the shared cooldown. */
    public void groundDash() {
        groundDashTicks = GROUND_DASH_TICKS;
        groundDashY = player.getY();
        dashCooldown = DASH_COOLDOWN;
    }
    public void spendDashJump() { groundDashTicks = 0; }

    /** Runs at the head of every player tick on both sides. */
    public void tick() {
        if (grounded()) jumpSpent = dashSpent = false;
        if (dashCooldown > 0) dashCooldown--;
        if (groundDashTicks > 0 && (--groundDashTicks == 0
                || Math.abs(player.getY() - groundDashY) > GROUND_DASH_ELEVATION)) {
            groundDashTicks = 0;
        }
        if (!(player instanceof ServerPlayer server)) return;
        if (dashTrailTicks > 0) trail(server, --dashTrailTicks);
        if (wallRunning) {
            // A run up may start from the ground, so its first few ticks still read as grounded.
            if (++wallRunTicks > WALL_RUN_MAX_TICKS || !WindrunnerArmor.boots(player) || grounded() && wallRunTicks > 3) {
                wallRunning = false;
            }
            else player.resetFallDistance();
        }
    }

    public void handle(int action) {
        if (!(player instanceof ServerPlayer server)) return;
        switch (action) {
            case DOUBLE_JUMP -> {
                if (!canDoubleJump()) return;
                spendJump();
                player.resetFallDistance();
                puff(server, ParticleTypes.SMALL_GUST, player.position(), 3, 0.3);
                sound(server, 1.35F);
            }
            case AIR_DASH -> {
                if (!canAirDash()) return;
                spendDash();
                dashEffects(server);
            }
            case GROUND_DASH -> {
                if (!canGroundDash()) return;
                groundDash();
                dashEffects(server);
            }
            case DASH_JUMP -> {
                if (!canDashJump()) return;
                spendDashJump();
                player.resetFallDistance();
                puff(server, ParticleTypes.SMALL_GUST, player.position(), 2, 0.25);
                sound(server, 1.15F);
            }
            case WALL_RUN_START -> {
                if (!WindrunnerArmor.boots(player) || !able()) return;
                wallRunning = true;
                wallRunTicks = 0;
                player.resetFallDistance();
                puff(server, ParticleTypes.SMALL_GUST, player.position(), 2, 0.2);
            }
            case WALL_RUN_STOP -> wallRunning = false;
            case WALL_KICK -> {
                if (!wallRunning) return;
                wallRunning = false;
                refreshJump();
                player.resetFallDistance();
                puff(server, ParticleTypes.SMALL_GUST, player.position(), 3, 0.3);
                sound(server, 1.25F);
            }
            default -> {}
        }
    }

    private void dashEffects(ServerPlayer server) {
        puff(server, ParticleTypes.GUST, player.position().add(0, 0.9, 0), 1, 0);
        dust(server);
        dashTrailTicks = DASH_TRAIL_TICKS;
        server.level().playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.BREEZE_WIND_CHARGE_BURST,
                SoundSource.PLAYERS, 0.5F, 1.4F);
    }

    /** The start's gust keeps pace with the dash, and the ground under it kicks up like a sprint. */
    private void trail(ServerPlayer server, int remaining) {
        if (remaining % 2 == 1) puff(server, ParticleTypes.GUST, player.position().add(0, 0.9, 0), 1, 0);
        dust(server);
    }

    /** Sprint-style block particles from the first visible block within two below the feet, if any. */
    private void dust(ServerPlayer server) {
        ServerLevel level = server.level();
        for (int depth = 0; depth <= 2; depth++) {
            BlockPos below = BlockPos.containing(player.getX(), player.getY() - 0.2 - depth, player.getZ());
            BlockState state = level.getBlockState(below);
            if (state.isAir() || state.getRenderShape() == RenderShape.INVISIBLE) continue;
            level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, state),
                    player.getX(), below.getY() + 1.05, player.getZ(), 6, 0.3, 0.02, 0.3, 0.15);
            return;
        }
    }

    /** A glide boost cracks like a wind charge and leaves a gust behind. */
    static void boostEffects(ServerPlayer server, Vec3 forward) {
        Vec3 behind = server.position().add(0, 0.4, 0).subtract(forward.scale(0.8));
        puff(server, ParticleTypes.GUST, behind, 1, 0);
        puff(server, ParticleTypes.SMALL_GUST, behind.subtract(forward.scale(0.6)), 2, 0.15);
        server.level().playSound(null, server.getX(), server.getY(), server.getZ(), SoundEvents.WIND_CHARGE_BURST,
                SoundSource.PLAYERS, 0.45F, 1.3F);
    }

    private void sound(ServerPlayer server, float pitch) {
        server.level().playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.BREEZE_JUMP,
                SoundSource.PLAYERS, 0.6F, pitch);
    }

    private static void puff(ServerPlayer server, SimpleParticleType type, Vec3 at, int count, double spread) {
        ServerLevel level = server.level();
        level.sendParticles(type, at.x, at.y, at.z, count, spread, 0.05, spread, 0.01);
    }
}
