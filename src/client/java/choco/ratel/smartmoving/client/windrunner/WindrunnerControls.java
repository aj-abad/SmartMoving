package choco.ratel.smartmoving.client.windrunner;

import choco.ratel.smartmoving.windrunner.WindrunnerGlide;
import choco.ratel.smartmoving.windrunner.WindrunnerActionPayload;
import choco.ratel.smartmoving.windrunner.WindrunnerArmor;
import choco.ratel.smartmoving.windrunner.WindrunnerMoves;
import choco.ratel.smartmoving.client.input.SmartMovingKeys;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Double jump, ground and air dashes. The dash fires from SmartMoving's Dodge
 * binding, and with the headband on its ground dash replaces the dodge.
 */
public final class WindrunnerControls {
    /** The FOV kick of a dash: a smoothstep rise over two ticks, then a squared ease back out. */
    private static final int DART_ATTACK_TICKS = 2, DART_TICKS = 10;
    private static final float DART_STRENGTH = 0.12F;
    private static boolean dashPressed;
    private static int dartTick = DART_TICKS;
    private static float dart, dartOld;

    private WindrunnerControls() {}

    public static KeyMapping dashKey() { return SmartMovingKeys.dodge; }

    /** Held, or clicked since the last tick: a click shorter than a tick still reaches the glide boost. */
    public static boolean dashHeld() {
        KeyMapping key = dashKey();
        return dashPressed || key != null && key.isDown();
    }

    /**
     * Spends an airborne jump press: a jump out of a ground dash's hop first, which leaves the double
     * jump in hand, otherwise the pants' double jump.
     */
    static boolean jump(Minecraft client, LocalPlayer player) {
        WindrunnerMoves moves = WindrunnerMoves.of(player);
        if (WindrunnerSmartMoving.claimsAirJump(player)) return false;
        if (moves.canDashJump()) {
            moves.spendDashJump();
            player.setDeltaMovement(WindrunnerMoves.dashJump(player.getDeltaMovement(), WindrunnerArmor.jumpPower(player)));
            player.resetFallDistance();
            send(WindrunnerMoves.DASH_JUMP);
            return true;
        }
        if (!moves.canDoubleJump()) return false;
        moves.spendJump();
        double speed = WindrunnerMoves.doubleJumpSpeed(WindrunnerArmor.jumpScale(player), player.getJumpBoostPower());
        player.setDeltaMovement(WindrunnerMoves.doubleJump(player.getDeltaMovement(), wish(client, player), speed));
        player.resetFallDistance();
        send(WindrunnerMoves.DOUBLE_JUMP);
        return true;
    }

    static void tick(Minecraft client, LocalPlayer player, boolean available) {
        KeyMapping key = dashKey();
        boolean pressed = false;
        // Clicks are drained every tick so a press made while dashing is unavailable cannot fire later.
        // The dodge itself is tracked through isDown edges, which draining clicks leaves alone.
        if (key != null) while (key.consumeClick()) pressed = true;
        dashPressed = pressed;
        WindrunnerMoves moves = WindrunnerMoves.of(player);
        dartOld = dart;
        if (dartTick < DART_TICKS) dartTick++;
        dart = dartStrength(dartTick);
        if (available && pressed) {
            Vec3 direction = WindrunnerMoves.dashDirection(player.getYRot(), forward(client), left(client));
            if (moves.canAirDash()) {
                moves.spendDash();
                player.setDeltaMovement(WindrunnerMoves.airDash(player.getDeltaMovement(), direction));
                send(WindrunnerMoves.AIR_DASH);
                dartTick = 0;
            } else if (moves.canGroundDash() && !WindrunnerSmartMoving.claimsGroundDash(player)) {
                moves.groundDash();
                player.setDeltaMovement(WindrunnerMoves.groundDash(player.getDeltaMovement(), direction));
                // Travel picks ground or air friction before it moves; launching as airborne keeps the
                // ground's grip from eating half the dash on its very first tick.
                player.setOnGround(false);
                send(WindrunnerMoves.GROUND_DASH);
                dartTick = 0;
            }
        }
    }

    /** The dash's FOV kick at this partial tick; the camera scales it by the FOV effect setting. */
    public static float fovDart(float partialTick) { return DART_STRENGTH * Mth.lerp(partialTick, dartOld, dart); }

    private static float dartStrength(int tick) {
        if (tick >= DART_TICKS) return 0;
        if (tick <= DART_ATTACK_TICKS) {
            float rise = (float) tick / DART_ATTACK_TICKS;
            return rise * rise * (3 - 2 * rise);
        }
        float remaining = 1 - (float) (tick - DART_ATTACK_TICKS) / (DART_TICKS - DART_ATTACK_TICKS);
        return remaining * remaining;
    }

    /** The boots double the per-tick buildup of SmartMoving's sneak-charged jump. */
    public static float chargeRate(LocalPlayer player) { return WindrunnerArmor.boots(player) ? 2 : 1; }

    /** SmartMoving computes its jumps itself, ignoring jump strength; scale an upward kick as vanilla's is. */
    public static double jumpVelocity(LocalPlayer player, double vertical) {
        return vertical > 0 ? vertical * WindrunnerArmor.jumpScale(player) : vertical;
    }

    /** With the headband on, SmartMoving's dodge gives way to the headband's own ground dash. */
    public static boolean overridesDodge(LocalPlayer player) {
        return WindrunnerArmor.headband(player) && !WindrunnerGlide.of(player).active();
    }

    private static Vec3 wish(Minecraft client, LocalPlayer player) {
        return WindrunnerMoves.wish(player.getYRot(), forward(client), left(client));
    }

    private static float forward(Minecraft client) {
        return (client.options.keyUp.isDown() ? 1 : 0) - (client.options.keyDown.isDown() ? 1 : 0);
    }

    private static float left(Minecraft client) {
        return (client.options.keyLeft.isDown() ? 1 : 0) - (client.options.keyRight.isDown() ? 1 : 0);
    }

    private static void send(int action) { ClientPlayNetworking.send(new WindrunnerActionPayload(action)); }
}
