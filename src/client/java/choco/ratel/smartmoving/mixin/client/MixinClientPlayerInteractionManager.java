package choco.ratel.smartmoving.mixin.client;

import choco.ratel.smartmoving.client.SmartMovingClientState;
import choco.ratel.smartmoving.config.SmartMovingConfig;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Client-side block interaction — forceIsSneaking override hook.
 *
 * Original SmartMovingSelf body (SmartMovingSelf.md L2063-2071):
 *   beforeActivateBlockOrUseItem() { forceIsSneaking = isp.localIsSneaking(); }
 *   afterActivateBlockOrUseItem()  { forceIsSneaking = null; }
 *
 * `localIsSneaking()` = vanilla `isSneaking()` value. Lock sneak state during block interaction period →
 * MixinEntityClient.sm_isSneaking forcibly returns this value to block changes by other logic.
 *
 * 1.21.1 mapping: ItemInWorldManager.activateBlockOrUseItem(client side)
 *            → ClientPlayerInteractionManager.interactBlock.
 */
@Mixin(MultiPlayerGameMode.class)
public abstract class MixinClientPlayerInteractionManager {

    @Inject(method = "useItemOn", at = @At("HEAD"))
    private void sm_beforeInteractBlock(LocalPlayer player, InteractionHand hand, BlockHitResult hitResult,
                                         CallbackInfoReturnable<InteractionResult> cir) {
        // BUG-10 (session 36): When SM disabled, do not force forceIsSneaking →
        //   sm_isSneaking() override returns vanilla isSneaking() result as-is (BUG-7 extension).
        if (!SmartMovingConfig.Config.enabled) return;
        SmartMovingClientState sm = SmartMovingClientState.get(player);
        sm.forceIsSneaking = player.isShiftKeyDown();
    }

    @Inject(method = "useItemOn", at = @At("RETURN"))
    private void sm_afterInteractBlock(LocalPlayer player, InteractionHand hand, BlockHitResult hitResult,
                                        CallbackInfoReturnable<InteractionResult> cir) {
        // BUG-10 (session 36): RETURN also same guard — do not force null when disabled (may already be null).
        if (!SmartMovingConfig.Config.enabled) return;
        SmartMovingClientState.get(player).forceIsSneaking = null;
    }
}
