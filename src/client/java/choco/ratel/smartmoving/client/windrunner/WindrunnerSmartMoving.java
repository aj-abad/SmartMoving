package choco.ratel.smartmoving.client.windrunner;

import choco.ratel.smartmoving.client.SmartMovingClientState;
import choco.ratel.smartmoving.config.SmartMovingConfig;
import net.minecraft.client.player.LocalPlayer;

/** Where SmartMoving's own movement takes precedence over the Windrunner set's air jump and ground dash. */
public final class WindrunnerSmartMoving {
    private WindrunnerSmartMoving() {}

    /**
     * SmartMoving owns the airborne jump press while climbing, swimming or flying, and next to a wall
     * where its wall jump fires on the same press. Toggled off, SmartMoving claims nothing.
     */
    public static boolean claimsAirJump(LocalPlayer player) {
        if (!SmartMovingConfig.Config.enabled) return false;
        SmartMovingClientState sm = SmartMovingClientState.get(player);
        return player.horizontalCollision || sm.isClimbing || sm.isClimbHolding || sm.isCeilingClimbing
                || sm.isSwimming_sm || sm.isDiving || sm.isFlying;
    }

    /** The states SmartMoving's dodge also refuses: crawling, sliding, climbing, swimming, flying. */
    public static boolean claimsGroundDash(LocalPlayer player) {
        if (!SmartMovingConfig.Config.enabled) return false;
        SmartMovingClientState sm = SmartMovingClientState.get(player);
        return sm.isCrawling || sm.isSliding || sm.isClimbing || sm.isClimbCrawling || sm.isCrawlClimbing
                || sm.isCeilingClimbing || sm.isSwimming_sm || sm.isDiving || sm.isFlying
                || sm.isChainClimbing;
    }
}
