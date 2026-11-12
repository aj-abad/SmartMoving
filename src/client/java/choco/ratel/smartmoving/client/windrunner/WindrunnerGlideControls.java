package choco.ratel.smartmoving.client.windrunner;

import choco.ratel.smartmoving.windrunner.WindrunnerGlide;
import choco.ratel.smartmoving.windrunner.WindrunnerGlideActionPayload;
import choco.ratel.smartmoving.windrunner.WindrunnerGlidePhysics.Input;
import choco.ratel.smartmoving.windrunner.WindrunnerGlideStatePayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;

/**
 * An airborne Jump press that nothing else spends (the wall-run kick, the dash's hop jump, the pants'
 * double jump, SmartMoving's own wall and climb moves) opens the robes; Sneak folds them. Each fresh
 * dash press while gliding is a boost.
 */
public final class WindrunnerGlideControls {
    public static final float LOOK_YAW_LIMIT = 100;
    public static final float LOOK_PITCH_LIMIT = 70;
    private static LocalPlayer cameraPlayer;
    private static boolean following;
    private static float lastYaw, lastPitch, yawStep, pitchStep;
    private static int cameraEpoch;
    private WindrunnerGlideControls() {}

    static void init() {
        ClientPlayNetworking.registerGlobalReceiver(WindrunnerGlideStatePayload.TYPE, (packet, context) ->
                context.client().execute(() -> {
                    if (context.client().level != null && context.client().level.getEntity(packet.playerId()) instanceof Player player) {
                        WindrunnerGlide.of(player).receive(packet);
                    }
                }));
    }

    static void beforeTick(Minecraft client) {
        if (client.player == null || client.isPaused()) return;
        var flight = WindrunnerGlide.of(client.player);
        boolean available = client.gui.screen() == null;
        // Jump and Sneak presses are noted where the key is pressed rather than drained as clicks, which
        // other mods may be draining too. Vanilla's own jump, creative double-tap and dismount are untouched.
        boolean jumped = WindrunnerKeyPresses.takeJump(), sneaked = WindrunnerKeyPresses.takeSneak();
        LocalPlayer player = client.player;
        if (available && jumped && !flight.active() && !WindrunnerControls.jump(client, player)
                && WindrunnerGlide.equipped(player) && flight.canDeploy() && !WindrunnerSmartMoving.claimsAirJump(player)) {
            send(WindrunnerGlide.GLIDING);
        }
        if (available && sneaked && flight.active()) send(WindrunnerGlide.CLOSED);
        WindrunnerControls.tick(client, player, available);
        if (flight.active()) {
            float pitch = available ? (client.options.keyUp.isDown() ? 1 : 0) - (client.options.keyDown.isDown() ? 1 : 0) : 0;
            float turn = available ? (client.options.keyRight.isDown() ? 1 : 0) - (client.options.keyLeft.isDown() ? 1 : 0) : 0;
            boolean boost = available && WindrunnerControls.dashHeld();
            ClientPlayNetworking.send(flight.localInput(new Input(pitch, turn, boost)));
        }
    }

    private static void send(int mode) { ClientPlayNetworking.send(new WindrunnerGlideActionPayload(mode)); }

    static void afterTick(Minecraft client) {
        LocalPlayer player = client.player;
        if (player == null) { following = false; cameraPlayer = null; return; }
        if (client.isPaused()) return;
        var flight = WindrunnerGlide.of(player);
        if (!flight.active()) { following = false; return; }
        float yaw = flight.yaw(1), pitch = flight.pitch(1);
        boolean firstPerson = client.options.getCameraType().isFirstPerson();
        yawStep = pitchStep = 0;
        if (following && cameraPlayer == player && cameraEpoch == flight.epoch() && firstPerson) {
            float dy = Mth.wrapDegrees(yaw - lastYaw), dp = pitch - lastPitch;
            float oldPitch = player.getXRot();
            player.setYRot(player.getYRot() + dy);
            player.setXRot(Mth.clamp(oldPitch + dp, -90, 90));
            player.yRotO += dy;
            pitchStep = player.getXRot() - oldPitch;
            yawStep = dy;
            player.xRotO = Mth.clamp(player.xRotO + pitchStep, -90, 90);
        }
        lastYaw = yaw; lastPitch = pitch; cameraPlayer = player;
        cameraEpoch = flight.epoch(); following = firstPerson;
        clampFirstPersonLook(player);
    }

    /**
     * Keeps free look inside the original mod's neck cone. This also runs from the camera every frame,
     * after mouse input, so the view stops at an edge instead of overshooting and snapping back at 20 Hz.
     */
    public static void clampFirstPersonLook(Player player) {
        if (!following || cameraPlayer != player || cameraEpoch != WindrunnerGlide.of(player).epoch()
                || !Minecraft.getInstance().options.getCameraType().isFirstPerson()) return;
        var flight = WindrunnerGlide.of(player);
        float yawOvershoot = overshoot(Mth.wrapDegrees(player.getYRot() - flight.yaw(1)),
                -LOOK_YAW_LIMIT, LOOK_YAW_LIMIT);
        if (yawOvershoot != 0) {
            player.setYRot(player.getYRot() - yawOvershoot);
            player.yRotO -= yawOvershoot;
        }
        float centerPitch = flight.pitch(1);
        float pitchOvershoot = overshoot(player.getXRot(),
                Math.max(centerPitch - LOOK_PITCH_LIMIT, -90),
                Math.min(centerPitch + LOOK_PITCH_LIMIT, 90));
        if (pitchOvershoot != 0) {
            player.setXRot(player.getXRot() - pitchOvershoot);
            player.xRotO -= pitchOvershoot;
        }
    }

    private static float overshoot(float value, float minimum, float maximum) {
        return value > maximum ? value - maximum : value < minimum ? value - minimum : 0;
    }

    // LocalPlayer deliberately does not interpolate mouse look. Remove the tick-sized flight
    // contribution and substitute the same partial-tick attitude used by the body and bank.
    public static float cameraYawOffset(Player player, float partial) {
        return following && cameraPlayer == player && cameraEpoch == WindrunnerGlide.of(player).epoch()
                ? (partial - 1) * yawStep : 0;
    }

    public static float cameraPitchOffset(Player player, float partial) {
        // Use the applied step so looking straight up/down cannot repeatedly bounce off the clamp.
        return following && cameraPlayer == player && cameraEpoch == WindrunnerGlide.of(player).epoch()
                ? (partial - 1) * pitchStep : 0;
    }
}
