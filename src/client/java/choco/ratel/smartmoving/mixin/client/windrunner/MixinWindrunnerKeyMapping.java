package choco.ratel.smartmoving.mixin.client.windrunner;

import choco.ratel.smartmoving.client.windrunner.WindrunnerKeyPresses;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Every physical key press passes through here; the Windrunner set notes Jump and Sneak presses. */
@Mixin(KeyMapping.class)
public abstract class MixinWindrunnerKeyMapping {
    @Inject(method = "click", at = @At("HEAD"))
    private static void windrunner$notePress(InputConstants.Key key, CallbackInfo ci) {
        WindrunnerKeyPresses.press(key);
    }
}
