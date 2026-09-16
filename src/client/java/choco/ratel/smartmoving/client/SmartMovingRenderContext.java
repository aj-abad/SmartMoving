package choco.ratel.smartmoving.client;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

/**
 * Temporary context flag shared in client render thread.
 *
 * 1.21.1's first-person arm in PlayerEntityRenderer.renderArm calls
 *   model.setAngles(player, 0,0,0,0,0)
 * → SM's sm_setAngles HEAD/TAIL inject triggers, overwriting arm/hand pose with SM branch
 * → First-person arm disappears or appears in strange pose BUG.
 *
 * Set/clear firstPersonArmRender flag in renderArm HEAD/RETURN → sm_setAngles
 * HEAD and TAIL check flag after entry and early return → first-person arm always maintains vanilla
 * default pose (third-person / other player's perspective SM animation unaffected).
 *
 * Client is single render thread → volatile static is sufficient.
 */
@Environment(EnvType.CLIENT)
public final class SmartMovingRenderContext {
    /** true during PlayerEntityRenderer.renderArm call (first-person arm rendering context). */
    public static volatile boolean firstPersonArmRender = false;

    private SmartMovingRenderContext() {}
}
