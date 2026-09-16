package choco.ratel.smartmoving.mixin.server;

import choco.ratel.smartmoving.server.SmartMovingServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Server-side block interaction — forceIsSneaking override hook.
 *
 * Original: SmartMovingCoreEventHandler calls SmartMovingServer.beforeActivateBlockOrUseItem() /
 *           afterActivateBlockOrUseItem() at ItemInWorldManager.activateBlockOrUseItem start/end.
 *
 * Original SmartMovingServer body:
 *   beforeActivateBlockOrUseItem() { forceIsSneaking = isSneakButtonPressed; }
 *   afterActivateBlockOrUseItem()  { forceIsSneaking = null; }
 *
 * 1.21.1 mapping: ItemInWorldManager.activateBlockOrUseItem → ServerPlayerInteractionManager.interactBlock.
 * During block interaction period, MixinEntity.sm_isSneaking forcibly returns client sneak button state.
 */
@Mixin(ServerPlayerGameMode.class)
public abstract class MixinServerPlayerInteractionManager {

    @Inject(method = "useItemOn", at = @At("HEAD"))
    private void sm_beforeInteractBlock(ServerPlayer player, Level world, ItemStack stack,
                                         InteractionHand hand, BlockHitResult hitResult,
                                         CallbackInfoReturnable<InteractionResult> cir) {
        SmartMovingServer sm = SmartMovingServer.get(player);
        sm.forceIsSneaking = sm.isSneakButtonPressed;
    }

    @Inject(method = "useItemOn", at = @At("RETURN"))
    private void sm_afterInteractBlock(ServerPlayer player, Level world, ItemStack stack,
                                        InteractionHand hand, BlockHitResult hitResult,
                                        CallbackInfoReturnable<InteractionResult> cir) {
        SmartMovingServer.get(player).forceIsSneaking = null;
    }
}
