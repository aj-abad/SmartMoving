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
 * beforeSetPositionAndRotation: Set multiPlayerInitialized after server→client position sync.
 * Original: SmartMovingPlayerBase.beforeSetPositionAndRotation (SmartMovingPlayerBase.java)
 *       → moving.initialized = false; moving.multiPlayerInitialized = 5;
 *
 * 1.21.1: setPositionAndRotation single method not available.
 * onPlayerPositionLook calls setPosition+setYaw+setPitch individually, so
 * set multiPlayerInitialized = 5 at HEAD.
 */
@Mixin(ClientPacketListener.class)
@Environment(EnvType.CLIENT)
public abstract class MixinClientPlayNetworkHandler {

    @Inject(method = "handleMovePlayer", at = @At("HEAD"))
    private void sm_beforePlayerPositionLook(ClientboundPlayerPositionPacket packet, CallbackInfo ci) {
        // BUG-9 (session 36): When SM disabled, do not update multiPlayerInitialized →
        //   Ensure pushOutOfBlocks suppression in sm_travel_client does not work (BUG-7 extension).
        if (!SmartMovingConfig.Config.enabled) return;
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) return;
        SmartMovingClientState sm = SmartMovingClientState.get(player);
        sm.multiPlayerInitialized = 5;
    }

}
