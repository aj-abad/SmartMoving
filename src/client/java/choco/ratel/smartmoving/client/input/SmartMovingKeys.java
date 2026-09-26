package choco.ratel.smartmoving.client.input;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;

/**
 * Register SM-specific keybindings.
 * Original LWJGL2 key code → InputConstants key:
 *   LCONTROL(29) → KEY_LCONTROL
 *   F9(67)       → KEY_F9
 *   O(24)        → KEY_O
 *   I(23)        → KEY_I
 * Since 26.3, key values are SDL scancodes (KEY_R = 21, GLFW's R was 82), so bind through
 * InputConstants.KEY_* and never raw GLFW codes.
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
            InputConstants.Type.KEYBOARD,
            InputConstants.KEY_R,
            CATEGORY_SMARTMOVING
        ));
        prone = KeyMappingHelper.registerKeyMapping(new KeyMapping(
            "key.smartmoving.prone",
            InputConstants.Type.KEYBOARD,
            InputConstants.KEY_C,
            CATEGORY_SMARTMOVING
        ));
        dodge = KeyMappingHelper.registerKeyMapping(new KeyMapping(
            "key.smartmoving.dodge",
            InputConstants.Type.KEYBOARD,
            InputConstants.UNKNOWN.getValue(),
            CATEGORY_SMARTMOVING
        ));
        configToggle = KeyMappingHelper.registerKeyMapping(new KeyMapping(
            "key.smartmoving.config_toggle",
            InputConstants.Type.KEYBOARD,
            InputConstants.KEY_F9,
            CATEGORY_SMARTMOVING
        ));
        speedIncrease = KeyMappingHelper.registerKeyMapping(new KeyMapping(
            "key.smartmoving.speed_increase",
            InputConstants.Type.KEYBOARD,
            InputConstants.KEY_O,
            CATEGORY_SMARTMOVING
        ));
        speedDecrease = KeyMappingHelper.registerKeyMapping(new KeyMapping(
            "key.smartmoving.speed_decrease",
            InputConstants.Type.KEYBOARD,
            InputConstants.KEY_I,
            CATEGORY_SMARTMOVING
        ));
    }
}
