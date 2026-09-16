package choco.ratel.smartmoving.mixin.client;

import choco.ratel.smartmoving.client.SmartMovingClientState;
import choco.ratel.smartmoving.config.SmartMovingConfig;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.KeyboardInput;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Input;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 🔴 Block input.sneaking during ladder/vine climbing + W + sneak.
 *
 * User intent: "ladder + W + sneak → just climb without any other action".
 *
 * Previous attempts (failed):
 *   1. ClientPlayerEntity.shouldEnterCrouchingPose mixin — method doesn't exist.
 *   2. LivingEntity.shouldEnterCrouchingPose mixin — different yarn mapping.
 *   3. Entity.setSneaking mixin — user report: no effect (vanilla pose decision uses different source).
 *
 * Core issue: Unknown exactly which method/source vanilla pose decision uses.
 * → Override sneaking to false after update in most fundamental KeyboardInput.tick.
 *   All subsequent vanilla processing (pose, isSneaking, setSneaking, applyClimbingSpeed etc)
 *   that uses input.sneaking as source will be blocked.
 *
 * Guard: sm.isClimbing && forward > 0F. Cases without W pressed maintain normal sneak hold.
 */
@Mixin(KeyboardInput.class)
@Environment(EnvType.CLIENT)
public abstract class MixinKeyboardInput {

    @Inject(method = "tick", at = @At("TAIL"))
    private void sm_blockSneakInClimb(CallbackInfo ci) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) return;
        if (!SmartMovingConfig.Config.enabled) return;
        SmartMovingClientState sm = SmartMovingClientState.get(player);
        ClientInput self = (ClientInput) (Object) this;
        // 🔴 Track sneak press during climbing + block until sneak key released even after climbing ends.
        //   User intent: Pressing sneak while climbing ladder, after climbing ends, vanilla CROUCHING
        //   pose should not activate. Normal activation only after releasing and pressing sneak key again.
        //   Original isSneaking() override allows sneak pose right after climbing (1.7.10 pose visual minimal)
        //   but 1.21.1 CROUCHING is prominent, user-noticeable → add extra correction.
        if (sm.isClimbing && self.keyPresses.shift()) {
            sm.sneakHeldDuringClimb = true;
        }
        if (!self.keyPresses.shift()) {
            sm.sneakHeldDuringClimb = false;
        }
        if ((sm.isClimbing || sm.sneakHeldDuringClimb) && self.hasForwardImpulse()) {
            Input input = self.keyPresses;
            self.keyPresses = new Input(input.forward(), input.backward(), input.left(), input.right(),
                    input.jump(), false, input.sprint());
        }
    }
}
