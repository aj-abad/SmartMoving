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
 * 서버측 블록 상호작용 — forceIsSneaking 오버라이드 훅.
 *
 * 원본: SmartMovingCoreEventHandler가 ItemInWorldManager.activateBlockOrUseItem 시작/종료 시
 *       SmartMovingServer.beforeActivateBlockOrUseItem() / afterActivateBlockOrUseItem() 호출.
 *
 * 원본 SmartMovingServer 본체:
 *   beforeActivateBlockOrUseItem() { forceIsSneaking = isSneakButtonPressed; }
 *   afterActivateBlockOrUseItem()  { forceIsSneaking = null; }
 *
 * 1.21.1 매핑: ItemInWorldManager.activateBlockOrUseItem → ServerPlayerInteractionManager.interactBlock.
 * 블록 상호작용 구간 동안 MixinEntity.sm_isSneaking 가 클라이언트 스니크 버튼 상태를 강제 반환.
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
