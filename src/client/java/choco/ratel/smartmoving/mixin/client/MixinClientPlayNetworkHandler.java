package choco.ratel.smartmoving.mixin.client;

import choco.ratel.smartmoving.client.SmartMovingClientState;
import choco.ratel.smartmoving.config.SmartMovingConfig;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * beforeSetPositionAndRotation: 서버→클라이언트 위치 동기화 직후 multiPlayerInitialized 세팅.
 * 원본: SmartMovingPlayerBase.beforeSetPositionAndRotation (SmartMovingPlayerBase.java)
 *       → moving.initialized = false; moving.multiPlayerInitialized = 5;
 *
 * 1.21.1: setPositionAndRotation 단일 메서드 없음.
 * onPlayerPositionLook이 setPosition+setYaw+setPitch를 개별 호출하므로
 * HEAD에서 multiPlayerInitialized = 5 세팅.
 */
@Mixin(ClientPacketListener.class)
@Environment(EnvType.CLIENT)
public abstract class MixinClientPlayNetworkHandler {

    @Inject(method = "handleMovePlayer", at = @At("HEAD"))
    private void sm_beforePlayerPositionLook(ClientboundPlayerPositionPacket packet, CallbackInfo ci) {
        // BUG-9 (세션 36): SM disabled 시 multiPlayerInitialized 갱신 안 함 →
        //   sm_travel_client 의 pushOutOfBlocks 억제가 작동하지 않도록 보장 (BUG-7 확장).
        if (!SmartMovingConfig.Config.enabled) return;
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) return;
        SmartMovingClientState sm = SmartMovingClientState.get(player);
        sm.multiPlayerInitialized = 5;
    }

}
