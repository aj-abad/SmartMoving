package choco.ratel.smartmoving.mixin.client;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Expose Camera cameraY / lastCameraY field setters.
 *
 * Original 1.7.10: camera position = posY + (yOffset or 1.62F). No interpolation.
 * 1.21.1 vanilla: Camera.updateEyeHeight() each frame
 *     lastCameraY = cameraY;
 *     cameraY     = cameraY + (entity.getStandingEyeHeight() - cameraY) * 0.5F;
 * → Chases standingEyeHeight with 0.5 lerp.
 *
 * During SmartMoving ICC EXIT mapping, entity.y +=1, eyeHeight changes 1.62→0.62 immediately.
 * When Camera.cameraY lerps from previous standing 1.62 to 0.62 → camera jumps up +0.5 for 1 frame,
 * then gradually descends → user viewpoint jittery.
 *
 * Solution: Force cameraY/lastCameraY to standingEyeHeight (= 0.62) after ICC toCrawling
 * → next frame update interpolation baseline matches → prevents jump.
 *
 * Keep interface form — Mixin class cannot be cast directly (IllegalClassLoadError).
 *   @Inject handled in separate MixinCameraDebug abstract class.
 */
@Mixin(Camera.class)
@Environment(EnvType.CLIENT)
public interface MixinCamera {

    @Accessor("eyeHeight")
    void sm_setCameraY(float cameraY);

    @Accessor("eyeHeightOld")
    void sm_setLastCameraY(float lastCameraY);

    @Accessor("eyeHeight")
    float sm_getCameraY();

    @Accessor("eyeHeightOld")
    float sm_getLastCameraY();
}
