package choco.ratel.smartmoving.client.input;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

/**
 * Register SM-specific keybindings.
 * Original LWJGL2 key code → GLFW key code conversion:
 *   LCONTROL(29) → GLFW_KEY_LEFT_CONTROL(341)
 *   F9(67)       → GLFW_KEY_F9(298)
 *   O(24)        → GLFW_KEY_O(79)
 *   I(23)        → GLFW_KEY_I(73)
 */
public class SmartMovingKeys {

    private static final KeyMapping.Category CATEGORY_SMARTMOVING = KeyMapping.Category.register(
            Identifier.fromNamespaceAndPath("smartmoving", "controls"));

    public static KeyMapping grab;
    public static KeyMapping prone;
    public static KeyMapping dodge;
    public static KeyMapping configToggle;
    public static KeyMapping speedIncrease;
    public static KeyMapping speedDecrease;

    public static void register() {
        grab = KeyMappingHelper.registerKeyMapping(new KeyMapping(
            "key.smartmoving.grab",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_R,
            CATEGORY_SMARTMOVING
        ));
        prone = KeyMappingHelper.registerKeyMapping(new KeyMapping(
            "key.smartmoving.prone",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_C,
            CATEGORY_SMARTMOVING
        ));
        dodge = KeyMappingHelper.registerKeyMapping(new KeyMapping(
            "key.smartmoving.dodge",
            InputConstants.Type.KEYSYM,
            InputConstants.UNKNOWN.getValue(),
            CATEGORY_SMARTMOVING
        ));
        configToggle = KeyMappingHelper.registerKeyMapping(new KeyMapping(
            "key.smartmoving.config_toggle",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_F9,
            CATEGORY_SMARTMOVING
        ));
        speedIncrease = KeyMappingHelper.registerKeyMapping(new KeyMapping(
            "key.smartmoving.speed_increase",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_O,
            CATEGORY_SMARTMOVING
        ));
        speedDecrease = KeyMappingHelper.registerKeyMapping(new KeyMapping(
            "key.smartmoving.speed_decrease",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_I,
            CATEGORY_SMARTMOVING
        ));
    }
}
