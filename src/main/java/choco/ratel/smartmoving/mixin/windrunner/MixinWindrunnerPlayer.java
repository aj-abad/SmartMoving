package choco.ratel.smartmoving.mixin.windrunner;

import choco.ratel.smartmoving.windrunner.WindrunnerGlide;
import choco.ratel.smartmoving.windrunner.WindrunnerUser;
import choco.ratel.smartmoving.windrunner.WindrunnerMoves;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Hooks native player movement; no PlayerAPI, SmartMoving or camera-driven physics. */
@Mixin(Player.class)
public abstract class MixinWindrunnerPlayer implements WindrunnerUser {
    @Unique private WindrunnerGlide windrunner$flight;
    @Unique private WindrunnerMoves windrunner$moves;
    @Override public WindrunnerGlide windrunner$glide() {
        if (windrunner$flight == null) windrunner$flight = new WindrunnerGlide((Player) (Object) this);
        return windrunner$flight;
    }
    @Override public WindrunnerMoves windrunner$moves() {
        if (windrunner$moves == null) windrunner$moves = new WindrunnerMoves((Player) (Object) this);
        return windrunner$moves;
    }
    @Inject(method = "tick", at = @At("HEAD"))
    private void windrunner$beforeTick(CallbackInfo ci) {
        windrunner$glide().beforeTick();
        windrunner$moves().tick();
    }
    @Inject(method = "tick", at = @At("TAIL"))
    private void windrunner$afterTick(CallbackInfo ci) { windrunner$glide().afterTick(); }
    @Inject(method = "travel", at = @At("HEAD"), cancellable = true)
    private void windrunner$travel(Vec3 input, CallbackInfo ci) { if (windrunner$glide().travel()) ci.cancel(); }
    // Cancelled travel (the glide, other mods' flight) returns early and never reaches this.
    @Inject(method = "travel", at = @At("RETURN"))
    private void windrunner$afterTravel(Vec3 input, CallbackInfo ci) { windrunner$moves().afterTravel(); }
    @Inject(method = "causeFallDamage", at = @At("HEAD"), cancellable = true)
    private void windrunner$flightImpact(CallbackInfoReturnable<Boolean> cir) {
        if (windrunner$glide().active()) cir.setReturnValue(false);
    }
    @Inject(method = "updatePlayerPose", at = @At("HEAD"), cancellable = true)
    private void windrunner$pose(CallbackInfo ci) {
        if (windrunner$glide().active()) {
            ((Player) (Object) this).setPose(Pose.FALL_FLYING);
            ci.cancel();
        }
    }
}
