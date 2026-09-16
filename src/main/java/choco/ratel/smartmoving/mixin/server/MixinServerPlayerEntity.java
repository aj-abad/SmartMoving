package choco.ratel.smartmoving.mixin.server;

import choco.ratel.smartmoving.server.SmartMovingServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Inject server-player-only behaviors to Player-declared methods, restricted to ServerPlayer.
 *
 * - 3-1-B: tick() HEAD — crawlingCooldown countdown
 * - C-22: travel() HEAD/TAIL — SM exhaustion batch handling
 *
 * Note: isSneaking(Entity), isInsideWall(LivingEntity), addExhaustion(PlayerEntity) are not
 * declared in ServerPlayer, so target Player declaration class.
 */
@Mixin(Player.class)
public abstract class MixinServerPlayerEntity {

    /**
     * 3-1-B: crawlingCooldown countdown (beforeOnUpdate equivalent).
     * Decrements isInsideWall() suppression timer each tick for 10 ticks after crawling ends.
     * Original: SmartMovingServer.beforeOnUpdate() → if (crawlingCooldown > 0) crawlingCooldown--
     */
    @Inject(method = "tick", at = @At("HEAD"))
    private void sm_beforeTick(CallbackInfo ci) {
        if (!((Object) this instanceof ServerPlayer player)) return;
        SmartMovingServer sm = SmartMovingServer.get(player);
        if (sm.crawlingCooldown > 0) sm.crawlingCooldown--;
    }

    // ── C-22: travel() HEAD/TAIL — SM exhaustion batch handling ─────────────────────────
    // Original: addMovementStat() removed → travel() inline handling (see A-27)
    // HEAD: beforeAddMovingHungerBatch() — start exhaustion blocking
    // TAIL: after hunger applied, afterAddMovingHungerBatch() — end exhaustion blocking

    @Inject(method = "travel", at = @At("HEAD"))
    private void sm_beforeTravel(Vec3 movementInput, CallbackInfo ci) {
        if (!((Object) this instanceof ServerPlayer player)) return;
        SmartMovingServer.get(player).beforeAddMovingHungerBatch();
    }

    @Inject(method = "travel", at = @At("TAIL"))
    private void sm_afterTravel(Vec3 movementInput, CallbackInfo ci) {
        if (!((Object) this instanceof ServerPlayer player)) return;
        SmartMovingServer sm = SmartMovingServer.get(player);
        if (sm.hunger > 0F) {
            sm.disableAddExhaustion = false;
            player.causeFoodExhaustion(sm.hunger);
            sm.hunger = 0F;
        }
        sm.afterAddMovingHungerBatch();
    }
}
