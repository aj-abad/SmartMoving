package choco.ratel.smartmoving.client.windrunner;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.Minecraft;

/**
 * Jump and Sneak presses seen at the key itself, so the set never consumes the vanilla clicks. Other
 * mods that drain those clicks every tick, such as Aircraft's wingsuit controls, keep receiving them,
 * and a press still counts under toggle sneak.
 */
public final class WindrunnerKeyPresses {
    private static boolean jump, sneak;

    private WindrunnerKeyPresses() {}

    public static void press(InputConstants.Key key) {
        Minecraft client = Minecraft.getInstance();
        if (client.options == null) return;
        if (key.equals(KeyMappingHelper.getBoundKeyOf(client.options.keyJump))) jump = true;
        if (key.equals(KeyMappingHelper.getBoundKeyOf(client.options.keyShift))) sneak = true;
    }

    static boolean takeJump() {
        boolean pressed = jump;
        jump = false;
        return pressed;
    }

    static boolean takeSneak() {
        boolean pressed = sneak;
        sneak = false;
        return pressed;
    }
}
