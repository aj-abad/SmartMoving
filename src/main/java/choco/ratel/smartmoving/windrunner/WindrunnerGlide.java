package choco.ratel.smartmoving.windrunner;

import choco.ratel.smartmoving.SmartMovingSounds;

import java.util.ArrayDeque;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import choco.ratel.smartmoving.windrunner.WindrunnerGlidePhysics.Environment;
import choco.ratel.smartmoving.windrunner.WindrunnerGlidePhysics.Input;
import choco.ratel.smartmoving.windrunner.WindrunnerGlidePhysics.State;

/** Native player travel adapter. The server owns flight; the local player predicts the same step. */
public final class WindrunnerGlide {
    public static final int CLOSED = 0, GLIDING = 1;
    /** A fresh deployment needs this much clear air above terrain or water. */
    public static final double DEPLOY_CLEARANCE = 3;
    private static final float OPEN_TICKS = 5;
    private final Player player;
    private final WindrunnerAir.Sampler weather = new WindrunnerAir.Sampler();
    private final ArrayDeque<Frame> prediction = new ArrayDeque<>();
    private final WindrunnerGlideVisuals visuals = new WindrunnerGlideVisuals();
    private State state = new State(), previous = new State();
    private Input input = Input.NONE;
    private int mode, epoch, sequence, flightTicks, wearTicks;
    private boolean underStallStress, wasPassenger;
    private long lastTravel = Long.MIN_VALUE, lastSnapshot = Long.MIN_VALUE, lastInputTick;
    private Vec3 lastPosition, entryVelocity = Vec3.ZERO;
    private Environment environment = Environment.CALM;

    private record Frame(int sequence, Input input, Environment air) {}

    public WindrunnerGlide(Player player) { this.player = player; }
    public static WindrunnerGlide of(Player player) { return ((WindrunnerUser) player).windrunner$glide(); }
    public static boolean equipped(Player player) {
        ItemStack chest = player.getItemBySlot(EquipmentSlot.CHEST);
        return chest.is(WindrunnerItems.WINDRUNNER_ROBES) && !chest.nextDamageWillBreak();
    }
    public boolean active() { return mode != CLOSED; }

    /**
     * Both sides evaluate the same deployment gate, so the client never sends a request the server will
     * refuse. The robes may open immediately with enough room above terrain or water. Vehicles do not
     * count as ground.
     */
    public boolean canDeploy() {
        // A dismount the tick has not seen yet still carries the rider's stale ground flag for a moment.
        boolean dismounting = wasPassenger && !player.isPassenger();
        if ((player.onGround() && !dismounting) || player.isPassenger() || player.isInWater()
                || player.getAbilities().flying) return false;
        AABB clearance = below(DEPLOY_CLEARANCE);
        return player.level().noBlockCollision(player, clearance) && !hasWater(clearance);
    }

    private AABB below(double depth) {
        AABB feet = player.getBoundingBox();
        return new AABB(feet.minX, feet.minY - depth, feet.minZ, feet.maxX, feet.minY, feet.maxZ);
    }

    private boolean hasWater(AABB area) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int x = Mth.floor(area.minX); x < Mth.ceil(area.maxX); x++) {
            for (int y = Mth.floor(area.minY); y < Mth.ceil(area.maxY); y++) {
                for (int z = Mth.floor(area.minZ); z < Mth.ceil(area.maxZ); z++) {
                    var fluid = player.level().getFluidState(pos.set(x, y, z));
                    if (fluid.is(FluidTags.WATER)
                            && y + fluid.getHeight(player.level(), pos) > area.minY) return true;
                }
            }
        }
        return false;
    }
    public boolean movedThisTick() { return lastTravel == player.level().getGameTime(); }
    public int mode() { return mode; }
    public int epoch() { return epoch; }
    public State state() { return state; }
    public Vec3 forward() { return WindrunnerGlidePhysics.nose(state.pitch, state.yaw); }
    public float yaw(float partial) { return visuals.yaw(state, partial); }
    public float pitch(float partial) { return visuals.pitch(state, partial); }
    public float roll(float partial) { return visuals.roll(state, partial); }
    public Vec3 visualPositionOffset(float partial) { return visuals.positionOffset(partial); }
    public double visualAirspeed(float partial) { return visuals.airspeed(partial); }
    public float pitchRate(float partial) { return Mth.lerp(partial, previous.pitchRate, state.pitchRate); }
    public float rollRate(float partial) { return Mth.lerp(partial, previous.rollRate, state.rollRate); }
    public float deployment(float partial) {
        return mode == GLIDING ? Mth.clamp((flightTicks + partial) / OPEN_TICKS, 0, 1) : 0;
    }
    public double airspeed() { return player.getDeltaMovement().subtract(environment.wind()).length(); }

    /** Called on the normal player tick, independently of the camera and of input packets. */
    public void beforeTick() {
        visuals.beginTick(state);
        previous = state.copy();
        boolean clear = !player.onClimbable() && !player.isSleeping() && !player.isPassenger() && !player.isInWater()
                && !player.isInLava() && !player.getAbilities().flying && !player.isSpectator() && player.isAlive();
        boolean free = clear && !player.onGround();
        wasPassenger = player.isPassenger();
        if (!active() && lastPosition != null) {
            Vec3 observed = player.position().subtract(lastPosition);
            entryVelocity = observed.lengthSqr() < 64 ? observed : Vec3.ZERO;
        }
        lastPosition = player.position();
        if (player instanceof ServerPlayer && active() && (!free || !equipped(player))) stop();
    }

    public void afterTick() {
        if (!active()) return;
        double airspeed = airspeed();
        visuals.updateSpeed(airspeed);
        flightTicks++;
        if (!(player instanceof ServerPlayer serverPlayer)) return;
        int wear = WindrunnerGlideWear.periodic(++wearTicks, airspeed);
        WindrunnerGlideModel.Flow flow = WindrunnerGlideModel.sample(player.getDeltaMovement(), state, environment);
        boolean stallStress = WindrunnerGlideWear.deepStall(flow.stall(), state.recovery);
        if (stallStress && !underStallStress) wear++;
        underStallStress = stallStress;
        if (WindrunnerGlideModel.boosted(state)) {
            wear += WindrunnerGlideWear.BOOST;
            WindrunnerMoves.boostEffects(serverPlayer, forward());
        }
        if (!damageEquipment(wear)) { stop(); return; }
        if (flightTicks % 2 == 0) broadcast();
        serverPlayer.level().getChunkSource().move(serverPlayer);
    }

    public boolean request(int requested) {
        if (!(player instanceof ServerPlayer) || requested < CLOSED || requested > GLIDING) return false;
        if (requested == CLOSED) {
            stop(); return true;
        }
        if (!equipped(player) || player.onGround() || player.onClimbable() || player.isSleeping() || player.isPassenger() || player.isInWater()
                || player.isInLava() || player.getAbilities().flying || player.isSpectator() || !player.isAlive()) return false;
        if (mode == requested || !canDeploy()) return false;
        // Use authoritative knockback velocity where present, otherwise the observed freefall.
        Vec3 entry = player.getDeltaMovement();
        if (!active() && entryVelocity.lengthSqr() > entry.lengthSqr()) entry = entryVelocity;
        environment = atmosphere();
        if (!damageEquipment(WindrunnerGlideWear.deployment(entry.subtract(environment.wind()).length()))) {
            stop();
            return false;
        }
        mode = requested;
        epoch++;
        sequence = 0;
        flightTicks = 0;
        underStallStress = false;
        input = Input.NONE;
        lastInputTick = player.level().getGameTime();
        state = State.deploy(entry, player.getYRot());
        previous = state.copy();
        player.setDeltaMovement(entry);
        visuals.reset(state, airspeed());
        player.stopFallFlying();
        player.setSprinting(false);
        player.setPose(Pose.FALL_FLYING);
        broadcast();
        player.level().playSound(null, player.getX(), player.getY(), player.getZ(), SmartMovingSounds.WINDRUNNER_UNFURL,
                net.minecraft.sounds.SoundSource.PLAYERS, 0.65F, 1.0F);
        return true;
    }

    public void stop() {
        if (!active()) return;
        mode = CLOSED;
        epoch++;
        input = Input.NONE;
        underStallStress = false;
        prediction.clear();
        // Do not let vanilla ease out its fall-flying pose after the custom flight pose disappears.
        if (player.getPose() == Pose.FALL_FLYING) player.setPose(Pose.STANDING);
        retainDescentEnergy();
        if (player instanceof ServerPlayer) broadcast();
    }

    /** Cutting away retains descent energy for vanilla landing damage, and counts toward redeployment. */
    private void retainDescentEnergy() {
        double down = Math.max(0, -player.getDeltaMovement().y);
        player.fallDistance = down * down / 0.16;
    }

    private boolean damageEquipment(int amount) {
        if (amount > 0) {
            player.getItemBySlot(EquipmentSlot.CHEST).hurtAndBreak(amount, player, EquipmentSlot.CHEST);
        }
        return equipped(player);
    }

    public void beforeTeleport() {
        stop();
        lastPosition = null;
        entryVelocity = Vec3.ZERO;
    }

    public WindrunnerGlideInputPayload localInput(Input next) {
        input = next;
        return new WindrunnerGlideInputPayload(epoch, ++sequence, next.pitch(), next.turn(), next.boost());
    }

    public void acceptInput(WindrunnerGlideInputPayload packet) {
        if (!active() || packet.epoch() != epoch || packet.sequence() <= sequence
                || packet.sequence() - sequence > 200) return;
        sequence = packet.sequence();
        input = new Input(packet.pitch(), packet.turn(), packet.boost());
        lastInputTick = player.level().getGameTime();
    }

    private Environment atmosphere() {
        WindrunnerAir.Conditions conditions = weather.tick(player.level(), player.position());
        return conditions.vacuum() ? Environment.VACUUM
                : new Environment(conditions.wind(), WindrunnerGlidePhysics.density(player.getY()));
    }

    /** Returning true consumes vanilla travel, so there is exactly one movement integration. */
    public boolean travel() {
        if (!active()) return false;
        long tick = player.level().getGameTime();
        if (tick == lastTravel) return true;
        lastTravel = tick;
        if (player.level().isClientSide() && !player.isLocalPlayer()) return true;
        if (!player.level().isClientSide() && tick - lastInputTick > 10) input = Input.NONE;
        environment = atmosphere();
        if (player.level().isClientSide()) {
            prediction.addLast(new Frame(sequence, input, environment));
            while (prediction.size() > 60) prediction.removeFirst();
        }
        move(input, environment);
        if (player instanceof ServerPlayer server && (player.onGround() || player.horizontalCollision || player.isInWater())) {
            Vec3 intended = lastIntended;
            Vec3 actual = player.position().subtract(lastMoveStart);
            float damage = WindrunnerGlidePhysics.impactDamage(intended, actual, player.onGround() || player.isInWater(), player.horizontalCollision);
            if (damage > 0) server.hurtServer(server.level(), player.damageSources().flyIntoWall(), damage);
            stop();
            if (player.onGround() || player.isInWater()) player.resetFallDistance();
        }
        return true;
    }

    private Vec3 lastIntended = Vec3.ZERO, lastMoveStart = Vec3.ZERO;

    private void move(Input controls, Environment air) {
        lastIntended = WindrunnerGlideModel.tick(player.getDeltaMovement(), state, controls, air);
        lastMoveStart = player.position();
        player.resetFallDistance();
        player.setDeltaMovement(lastIntended);
        player.move(MoverType.SELF, lastIntended);
        player.resetFallDistance();
    }

    public WindrunnerGlideStatePayload snapshot() {
        return new WindrunnerGlideStatePayload(player.getId(), epoch, mode, sequence, player.level().getGameTime(),
                player.position(), player.getDeltaMovement(), state.copy());
    }

    public void broadcast() {
        if (!(player instanceof ServerPlayer server)) return;
        var snapshot = snapshot();
        ServerPlayNetworking.send(server, snapshot);
        for (ServerPlayer observer : PlayerLookup.tracking(server)) ServerPlayNetworking.send(observer, snapshot);
    }

    /** Restore the acknowledged state, then replay only inputs the server has not processed yet. */
    public void receive(WindrunnerGlideStatePayload snapshot) {
        if (snapshot.epoch() < epoch || (snapshot.epoch() == epoch && snapshot.tick() < lastSnapshot)) return;
        State before = state.copy();
        Vec3 positionBefore = player.position();
        boolean changed = epoch != snapshot.epoch() || mode != snapshot.mode();
        lastSnapshot = snapshot.tick();
        epoch = snapshot.epoch();
        mode = snapshot.mode();
        if (changed) {
            prediction.clear();
            sequence = snapshot.acknowledged();
            flightTicks = 0;
        }
        state = snapshot.state().copy();
        if (changed) previous = state.copy();
        if (!player.isLocalPlayer()) {
            if (changed) visuals.reset(state, airspeed());
            else visuals.correct(before, state, Vec3.ZERO, airspeed());
            return;
        }
        while (!prediction.isEmpty() && prediction.peekFirst().sequence <= snapshot.acknowledged()) prediction.removeFirst();
        player.setPos(snapshot.position());
        player.setDeltaMovement(snapshot.velocity());
        if (active()) {
            player.setOnGround(false);
            for (Frame frame : prediction) move(frame.input, frame.air);
        } else {
            prediction.clear();
            // The authoritative close should end the local landing pose on this same frame.
            if (player.getPose() == Pose.FALL_FLYING) player.setPose(Pose.STANDING);
            // Prediction reset the local fall distance every tick; match the server so the
            // deployment gate agrees on both sides after a cut-away.
            if (changed) retainDescentEnergy();
        }
        if (changed || !active()) visuals.reset(state, airspeed());
        else visuals.correct(before, state, positionBefore.subtract(player.position()), airspeed());
    }
}
