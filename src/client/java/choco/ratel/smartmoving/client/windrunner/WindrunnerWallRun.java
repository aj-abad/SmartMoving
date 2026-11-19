package choco.ratel.smartmoving.client.windrunner;

import choco.ratel.smartmoving.client.SmartMovingClientState;
import choco.ratel.smartmoving.client.input.SmartMovingKeys;
import choco.ratel.smartmoving.config.SmartMovingConfig;
import choco.ratel.smartmoving.windrunner.WindrunnerActionPayload;
import choco.ratel.smartmoving.windrunner.WindrunnerArmor;
import choco.ratel.smartmoving.windrunner.WindrunnerGlide;
import choco.ratel.smartmoving.windrunner.WindrunnerMoves;
import choco.ratel.smartmoving.windrunner.WindrunnerWallRunModel;
import choco.ratel.smartmoving.windrunner.WindrunnerWallRunPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * The boots' wall run: Grab held while sprinting into a wall runs up it (near head-on) or along it
 * (glancing, in the air). Movement is the local player's, as for the dashes; the server only keeps the
 * fall reset and the double-jump refresh honest.
 */
public final class WindrunnerWallRun {
    /** How far past the player's box a wall still counts as touching. */
    private static final double REACH = 0.1;
    private record Wall(Direction side, int plane) {}

    private static int mode = WindrunnerWallRunModel.NONE, ticks;
    private static Wall wall, lastWall;
    private static Vec3 tangent = Vec3.ZERO;

    private WindrunnerWallRun() {}

    public static boolean running() { return mode != WindrunnerWallRunModel.NONE; }
    public static int mode() { return mode; }

    /** Vanilla ends a sprint on a head-on wall hit; with the boots and Grab held it carries into the wall. */
    public static boolean keepsSprintIntoWall(LocalPlayer player) {
        return player.horizontalCollision && WindrunnerArmor.boots(player) && SmartMovingKeys.grab.isDown()
                && !Minecraft.getInstance().options.keyShift.isDown() && !WindrunnerGlide.of(player).active();
    }

    /** Runs before the player's tick, so the velocity it sets is this tick's move. */
    static void tick(Minecraft client, LocalPlayer player, boolean available) {
        if (player.onGround() && !running()) lastWall = null;
        if (running()) {
            if (!held(client, player) || claimed(player) || !touching(player, wall.side())) { stop(); return; }
            Vec3 into = into(wall.side());
            if (mode == WindrunnerWallRunModel.UP) {
                boolean headClear = !blocked(player, wall.side(), true);
                if (headClear && !blocked(player, wall.side(), false)) {
                    // Feet over the top: step onto it.
                    player.setDeltaMovement(into.x * WindrunnerWallRunModel.CREST_PUSH,
                            WindrunnerWallRunModel.CREST_LIFT, into.z * WindrunnerWallRunModel.CREST_PUSH);
                    stop();
                    return;
                }
                if (WindrunnerWallRunModel.upSpeed(ticks) <= 0 && !headClear) { stop(); return; }
                player.setDeltaMovement(WindrunnerWallRunModel.up(ticks, into, headClear));
            } else {
                if (ticks >= WindrunnerWallRunModel.ALONG_TICKS || ticks > 0 && player.onGround()) { stop(); return; }
                player.setDeltaMovement(WindrunnerWallRunModel.along(player.getDeltaMovement(), tangent, into));
            }
            player.resetFallDistance();
            ticks++;
            return;
        }
        if (!available || !held(client, player) || !player.horizontalCollision || claimed(player)) return;
        Vec3 heading = WindrunnerMoves.wish(player.getYRot(), forward(client), left(client));
        Direction best = null;
        double bestIncidence = -90;
        for (Direction side : Direction.Plane.HORIZONTAL) {
            if (!blocked(player, side, false) || !blocked(player, side, true)) continue;
            double incidence = WindrunnerWallRunModel.incidence(heading, into(side));
            if (incidence > bestIncidence) { bestIncidence = incidence; best = side; }
        }
        if (best == null) return;
        int kind = WindrunnerWallRunModel.classify(bestIncidence);
        Wall found = new Wall(best, plane(player, best));
        if (kind == WindrunnerWallRunModel.NONE || found.equals(lastWall)) return;
        if (kind == WindrunnerWallRunModel.ALONG && player.onGround()) return;
        mode = kind;
        wall = lastWall = found;
        ticks = 0;
        tangent = WindrunnerWallRunModel.tangent(heading, into(best));
        show(player, kind, best.get2DDataValue());
        tick(client, player, available);
    }

    /** Jump during a run: kick off the wall with the double jump handed back. */
    static boolean kick(LocalPlayer player) {
        if (!running()) return false;
        Vec3 into = into(wall.side());
        mode = WindrunnerWallRunModel.NONE;
        WindrunnerMoves.of(player).showWallRun(WindrunnerWallRunModel.NONE, 0);
        WindrunnerMoves.of(player).refreshJump();
        player.setDeltaMovement(WindrunnerWallRunModel.kick(player.getDeltaMovement(), into, WindrunnerArmor.jumpScale(player)));
        player.resetFallDistance();
        send(WindrunnerMoves.WALL_KICK);
        return true;
    }

    static void stop() {
        if (!running()) return;
        mode = WindrunnerWallRunModel.NONE;
        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null) show(player, WindrunnerWallRunModel.NONE, 0);
    }

    /** Watchers learn of other players' runs from the server. */
    static void init() {
        ClientPlayNetworking.registerGlobalReceiver(WindrunnerWallRunPayload.TYPE, (packet, context) ->
                context.client().execute(() -> {
                    if (context.client().level != null && context.client().level.getEntity(packet.playerId()) instanceof Player other
                            && other != context.client().player) {
                        WindrunnerMoves.of(other).showWallRun(packet.mode(), packet.side());
                    }
                }));
    }

    /** The local model's pose and the server's relay to watchers. */
    private static void show(LocalPlayer player, int kind, int side) {
        WindrunnerMoves.of(player).showWallRun(kind, side);
        ClientPlayNetworking.send(new WindrunnerWallRunPayload(player.getId(), kind, side));
    }

    private static boolean held(Minecraft client, LocalPlayer player) {
        return WindrunnerArmor.boots(player) && SmartMovingKeys.grab.isDown() && client.options.keyUp.isDown()
                && !client.options.keyShift.isDown() && player.isSprinting() && !WindrunnerGlide.of(player).active()
                && !player.isInWater() && !player.isInLava() && !player.isPassenger() && !player.isFallFlying()
                && !player.getAbilities().flying;
    }

    /** SmartMoving's own wall and ledge moves win. */
    private static boolean claimed(LocalPlayer player) {
        if (!SmartMovingConfig.Config.enabled) return false;
        SmartMovingClientState sm = SmartMovingClientState.get(player);
        return sm.isClimbing || sm.isClimbHolding || sm.isCeilingClimbing || sm.isChainClimbing || sm.isCrawlClimbing
                || sm.isSwimming_sm || sm.isDiving || sm.isFlying || sm.isSliding || sm.isCrawling;
    }

    private static boolean touching(LocalPlayer player, Direction side) {
        return blocked(player, side, false) || blocked(player, side, true);
    }

    /** Whether the wall on {@code side} reaches the lower (feet) or upper (head) half of the player. */
    private static boolean blocked(LocalPlayer player, Direction side, boolean upper) {
        AABB box = player.getBoundingBox();
        double middle = box.minY + box.getYsize() / 2;
        AABB half = upper ? box.setMinY(middle) : box.setMaxY(middle);
        return !player.level().noBlockCollision(player, half.move(into(side).scale(REACH)));
    }

    private static int plane(LocalPlayer player, Direction side) {
        AABB box = player.getBoundingBox();
        double face = switch (side) {
            case EAST -> box.maxX;
            case WEST -> box.minX;
            case SOUTH -> box.maxZ;
            default -> box.minZ;
        };
        return (int) Math.floor(face + (side.getStepX() + side.getStepZ()) * REACH);
    }

    private static Vec3 into(Direction side) { return new Vec3(side.getStepX(), 0, side.getStepZ()); }

    private static float forward(Minecraft client) {
        return (client.options.keyUp.isDown() ? 1 : 0) - (client.options.keyDown.isDown() ? 1 : 0);
    }

    private static float left(Minecraft client) {
        return (client.options.keyLeft.isDown() ? 1 : 0) - (client.options.keyRight.isDown() ? 1 : 0);
    }

    private static void send(int action) { ClientPlayNetworking.send(new WindrunnerActionPayload(action)); }
}
