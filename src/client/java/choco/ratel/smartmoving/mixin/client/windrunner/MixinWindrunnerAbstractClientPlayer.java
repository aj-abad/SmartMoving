package choco.ratel.smartmoving.mixin.client.windrunner;

import choco.ratel.smartmoving.client.windrunner.WindrunnerWallRun;
import choco.ratel.smartmoving.windrunner.WindrunnerMoves;
import choco.ratel.smartmoving.windrunner.WindrunnerWallRunModel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * View and hand bobbing step with walked distance and swing by the avatar's bob. A ground dash's slide
 * piles distance up at several times a sprint's rate, so it takes much shorter steps; a wall run is off
 * the ground, where vanilla stops bobbing and a climb walks no horizontal distance at all.
 */
@Mixin(AbstractClientPlayer.class)
public abstract class MixinWindrunnerAbstractClientPlayer {
    @Unique private static final float GROUND_DASH_BOB = 0.3F;
    /** Vanilla's walked distance per block moved, and its cap on the bob's strength. */
    @Unique private static final float STEP_SCALE = 0.6F, MAX_BOB = 0.1F;

    @ModifyVariable(method = "addWalkedDistance", at = @At("HEAD"), argsOnly = true)
    private float windrunner$stepDistance(float distance) {
        AbstractClientPlayer self = (AbstractClientPlayer) (Object) this;
        if (!(self instanceof LocalPlayer)) return distance;
        if (WindrunnerWallRun.mode() == WindrunnerWallRunModel.UP) {
            return Math.max(distance, (float) Math.abs(self.getDeltaMovement().y) * STEP_SCALE);
        }
        return self.onGround() && WindrunnerMoves.of(self).groundDashing() ? distance * GROUND_DASH_BOB : distance;
    }

    @ModifyArg(method = "updateBob", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/entity/ClientAvatarState;updateBob(F)V"))
    private float windrunner$wallRunBob(float bob) {
        AbstractClientPlayer self = (AbstractClientPlayer) (Object) this;
        if (!(self instanceof LocalPlayer) || !WindrunnerWallRun.running()) return bob;
        double speed = WindrunnerWallRun.mode() == WindrunnerWallRunModel.UP
                ? Math.abs(self.getDeltaMovement().y) : self.getDeltaMovement().horizontalDistance();
        return Math.min(MAX_BOB, (float) speed);
    }
}
