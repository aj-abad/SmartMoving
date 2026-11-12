package choco.ratel.smartmoving.client.windrunner;

import choco.ratel.smartmoving.client.input.SmartMovingKeys;
import choco.ratel.smartmoving.windrunner.WindrunnerArmor;
import choco.ratel.smartmoving.windrunner.WindrunnerGlide;
import net.minecraft.client.player.LocalPlayer;

/** The boots' wall run. So far only the sprint survives a wall hit; the run itself is still to come. */
public final class WindrunnerWallRun {
    private WindrunnerWallRun() {}

    /** Vanilla ends a sprint on a head-on wall hit; with the boots and Grab held it carries into the wall. */
    public static boolean keepsSprintIntoWall(LocalPlayer player) {
        return player.horizontalCollision && WindrunnerArmor.boots(player) && SmartMovingKeys.grab.isDown()
                && !player.isShiftKeyDown() && !WindrunnerGlide.of(player).active();
    }
}
