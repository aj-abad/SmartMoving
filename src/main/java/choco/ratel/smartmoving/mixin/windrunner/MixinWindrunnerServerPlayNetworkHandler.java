package choco.ratel.smartmoving.mixin.windrunner;

import choco.ratel.smartmoving.windrunner.WindrunnerGlide;
import net.minecraft.network.protocol.PacketUtils;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.util.Mth;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Flight inputs control motion; ordinary look packets continue to control the player's free look. */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class MixinWindrunnerServerPlayNetworkHandler {
    @Shadow public ServerPlayer player;
    @Shadow private boolean clientIsFloating;
    @Shadow private int aboveGroundTickCount;
    @Shadow public abstract void resetPosition();

    /** Vanilla restores client-authoritative players after doTick; retain our simulated endpoint. */
    @Inject(method = "tickPlayer", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerPlayer;doTick()V", shift = At.Shift.AFTER))
    private void windrunner$retainFlightStep(CallbackInfoReturnable<Boolean> cir) {
        if (WindrunnerGlide.of(player).movedThisTick()) {
            resetPosition();
            clientIsFloating = false;
            aboveGroundTickCount = 0;
        }
    }

    @Inject(method = "handleMovePlayer", at = @At("HEAD"), cancellable = true)
    private void windrunner$flightMovement(ServerboundMovePlayerPacket packet, CallbackInfo ci) {
        PacketUtils.ensureRunningOnSameThread(packet, (ServerGamePacketListenerImpl) (Object) this, player.level());
        if (!WindrunnerGlide.of(player).active()) return;
        float yaw = packet.getYRot(player.getYRot()), pitch = packet.getXRot(player.getXRot());
        if (!Float.isFinite(yaw) || !Float.isFinite(pitch) || !Double.isFinite(packet.getX(0))
                || !Double.isFinite(packet.getY(0)) || !Double.isFinite(packet.getZ(0))) return;
        player.absSnapRotationTo(Mth.wrapDegrees(yaw), Mth.clamp(pitch, -90, 90));
        clientIsFloating = false;
        aboveGroundTickCount = 0;
        ci.cancel();
    }

    @Inject(method = "teleport(Lnet/minecraft/world/entity/PositionMoveRotation;Ljava/util/Set;)V", at = @At("HEAD"))
    private void windrunner$stopOnTeleport(CallbackInfo ci) { WindrunnerGlide.of(player).beforeTeleport(); }
}
