package choco.ratel.smartmoving.mixin;

import choco.ratel.smartmoving.server.SmartMovingServer;
import java.util.List;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 5-5 (server): isClimbing() override — return false during SM custom climbing.
 * 6-2 (server): isInSwimmingPose() override — return false during SM crawling.
 * 3-3-C: tickStatusEffects HEAD/TAIL — potion update cycle reversal handling.
 * 3-7: tickMovement TAIL — extend item pickup range when isSmall.
 *
 * Note: Server movement pipeline (floatKick/fall distance/position checks) handled in MixinServerPlayNetworkHandler.
 * Client-only items (jump, applyClimbingSpeed, isClimbing) → MixinLivingEntityClient
 */
@Mixin(LivingEntity.class)
public abstract class MixinLivingEntity {

    /**
     * 5-5 (server): Force isClimbing() = false during SM custom climbing.
     *
     * Purpose 1: block applyClimbingSpeed() x/z ±0.15F clamp
     * Purpose 2: block vanilla y=0.2 forced application when isClimbing() in applyMovementInput()
     *
     * Check ServerPlayerEntity instance → determine based on SmartMovingServer state.
     * Client-side equivalent logic → MixinLivingEntityClient.sm_isClimbing_client()
     */
    @Inject(method = "onClimbable", at = @At("HEAD"), cancellable = true)
    private void sm_isClimbing_server(CallbackInfoReturnable<Boolean> cir) {
        if (!((Object) this instanceof ServerPlayer player)) return;
        SmartMovingServer sm = SmartMovingServer.get(player);
        if (sm.isClimbing || sm.isCrawlClimbing || sm.isCeilingClimbing) {
            cir.setReturnValue(false);
        }
    }

    /**
     * 6-2 (server): Force isInSwimmingPose() = false during SM crawling.
     *
     * SM crawling uses SWIMMING pose, but vanilla isInSwimmingPose()=true causes:
     *   - setupTransforms Branch 2: X-90° rotation + translate(0,-1,0.3) → render broken
     *   - updateLeaningPitch(): leaningPitch rises to 1.0 → auto-rotation occurs
     *
     * Server side: check ServerPlayerEntity instance.
     * Client side → MixinLivingEntityClient.sm_isInSwimmingPose_client()
     * Yarn: isInSwimmingPose (intermediary: method_20232 confirmed)
     */
    @Inject(method = "isVisuallySwimming", at = @At("HEAD"), cancellable = true)
    private void sm_isInSwimmingPose_server(CallbackInfoReturnable<Boolean> cir) {
        if (!((Object) this instanceof ServerPlayer player)) return;
        SmartMovingServer sm = SmartMovingServer.get(player);
        if (sm.isCrawling || sm.isCrawlClimbing) {
            cir.setReturnValue(false);
        }
    }

    /**
     * 3-7: afterOnLivingUpdate() — extend item pickup range Y+0.25F when isSmall.
     * During crawling, nearby-ground items unreachable with default AABB are now obtainable.
     * Original: SmartMovingServer.afterOnLivingUpdate() / SmallSizeItemGrabHeight = 0.25F
     *
     * Algorithm:
     *   offsetBox  = playerBox + (1, 0.25, 1) — Y-direction 0.25 extension
     *   standardBox = offsetBox.expand(0, -0.25, 0) — Y restored
     *   Entities in offsetBox only and not in standardBox → onPlayerCollision
     */
    @Inject(method = "aiStep", at = @At("TAIL"))
    private void sm_afterTickMovement(CallbackInfo ci) {
        if (!((Object) this instanceof ServerPlayer player)) return;
        SmartMovingServer sm = SmartMovingServer.get(player);
        if (!sm.isSmall) return;
        if (player.getHealth() <= 0) return;

        AABB offsetBox = player.getBoundingBox().inflate(1.0, SmartMovingServer.SMALL_SIZE_ITEM_GRAB_HEIGHT, 1.0);
        List<Entity> offsetEntities = player.level().getEntities(player, offsetBox);
        if (offsetEntities.isEmpty()) return;

        AABB standardBox = offsetBox.inflate(0.0, -SmartMovingServer.SMALL_SIZE_ITEM_GRAB_HEIGHT, 0.0);
        List<Entity> standardEntities = player.level().getEntities(player, standardBox);

        for (Entity entity : offsetEntities) {
            if (standardEntities.contains(entity)) continue;
            if (!entity.isRemoved()) entity.playerTouch(player);
        }
    }

    // ── C-23: tickStatusEffects() HEAD/TAIL — potion update cycle reversal handling ──
    // Original: beforeUpdatePotionEffects→afterAddMovingHungerBatch,
    //           afterUpdatePotionEffects→beforeAddMovingHungerBatch (intentional reversal, see A-28)
    // HEAD: afterAddMovingHungerBatch (depth decrease)
    // TAIL: beforeAddMovingHungerBatch (depth increase)

    @Inject(method = "tickEffects", at = @At("HEAD"))
    private void sm_beforeTickStatusEffects(CallbackInfo ci) {
        if (!((Object) this instanceof ServerPlayer player)) return;
        SmartMovingServer.get(player).afterAddMovingHungerBatch();
    }

    @Inject(method = "tickEffects", at = @At("TAIL"))
    private void sm_afterTickStatusEffects(CallbackInfo ci) {
        if (!((Object) this instanceof ServerPlayer player)) return;
        SmartMovingServer.get(player).beforeAddMovingHungerBatch();
    }

    /**
     * 3-5: Suppress isInsideWall().
     * Suppress inside-wall detection for crawlingCooldown ticks after crawling ends
     * to prevent player from being stuck in wall at crawling exit.
     *
     * isInsideWall() is last overridden in LivingEntity, so inject here.
     * Not declared in ServerPlayerEntity, so cannot inject in MixinServerPlayerEntity.
     */
    @Inject(method = "isInWall", at = @At("HEAD"), cancellable = true)
    private void sm_isInsideWall(CallbackInfoReturnable<Boolean> cir) {
        if (!((Object) this instanceof ServerPlayer player)) return;
        SmartMovingServer sm = SmartMovingServer.get(player);
        if (sm.crawlingCooldown > 0) {
            cir.setReturnValue(false);
        }
    }
}
