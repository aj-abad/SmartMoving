package choco.ratel.smartmoving.client;

import choco.ratel.smartmoving.config.SmartMovingConfig;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.Identifier;

/**
 * SM exclusive HUD rendering.
 * Original: SmartMovingRender.renderGuiIngame() — jumpCharge bar + exhaustion bar
 *
 * Included items:
 *   10-6: jumpCharge bar — left, screen center-91 basis, icons.png drawIcon(2,0)/drawIcon(3,0)
 *   C-33: exhaustion bar — right, screen center+90 basis, icons.png drawIcon(0,0)/drawIcon(1,0)
 *
 * Icon layout (icons.png 256×256, 9×9 grid):
 *   (0,0) full fitness  (1,0) half fitness
 *   (2,0) full charge   (3,0) half charge
 */
@Environment(EnvType.CLIENT)
public final class SmartMovingHud {

    private static final Identifier ICONS = Identifier.fromNamespaceAndPath("smartmoving", "textures/gui/icons.png");
    private static final Identifier HUD_ELEMENT_ID = Identifier.fromNamespaceAndPath("smartmoving", "movement_bars");
    private static final int ICON_SIZE = 9;
    private static final int JUMP_FULL_ICON_X = 2;
    private static final int JUMP_PARTIAL_ICON_X = 3;

    private SmartMovingHud() {}

    public static void register() {
        HudElementRegistry.addLast(HUD_ELEMENT_ID, SmartMovingHud::render);
    }

    private static void render(GuiGraphicsExtractor context, DeltaTracker tickCounter) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) return;

        SmartMovingClientState sm = SmartMovingClientState.get(player);
        SmartMovingConfig cfg = SmartMovingConfig.Config;

        // Original SmartMovingRender.md L405 entry condition 1:1:
        //   Config.enabled && (_displayExhaustionBar.value || _displayJumpChargeBar.value)
        if (!cfg.enabled) return;
        if (!cfg.displayExhaustionBar && !cfg.displayJumpChargeBar) return;

        // ── Jump charge bar ──────────────────────────────────────────────────
        // Original: stillJumpCharge = sm.jumpCharge, runJumpCharge = sm.headJumpCharge
        // Display the larger and use its maximum as reference
        float maxStillCharge = cfg.jumpChargeMaximum;
        float maxRunCharge   = cfg.headJumpChargeMaximum;
        float stillCharge    = Math.min(sm.jumpCharge,     maxStillCharge);
        float runCharge      = Math.min(sm.headJumpCharge, maxRunCharge);
        boolean drawJump     = stillCharge > 0 || runCharge > 0;

        // ── Exhaustion bar ───────────────────────────────────────────────────────
        // Original: maxExhaustion = climbExhaustionStop, display condition exhaustion > 0
        float maxExhaustion   = cfg.climbExhaustionStop;
        float exhaustion      = Math.min(sm.exhaustion, maxExhaustion);
        boolean drawExhaustion = exhaustion > 0 && exhaustion <= maxExhaustion;

        if (!drawJump && !drawExhaustion) return;

        int width  = mc.getWindow().getGuiScaledWidth();
        int height = mc.getWindow().getGuiScaledHeight();

        if (drawJump) {
            float maxCharge = stillCharge > runCharge ? maxStillCharge : maxRunCharge;
            float charge    = Math.max(stillCharge, runCharge);

            // Original: when maxed fulls=10 half=0, otherwise ceil formula
            boolean maxed = charge >= maxCharge;
            int fulls = maxed ? 10 : Math.max(0, (int) Math.ceil(((double)(charge - 2) * 10D) / maxCharge));
            int half  = maxed ? 0  : Math.max(0, (int) Math.ceil((double)charge * 10D / maxCharge) - fulls);

            // Y: height - 49. If armor present, 10 upward
            int jy = height - 49 - (player.getArmorValue() > 0 ? 10 : 0);

            for (int i = 0; i < fulls + half; i++) {
                drawIcon(context, (width / 2 - 91) + i * 8, jy,
                        i < fulls ? JUMP_FULL_ICON_X : JUMP_PARTIAL_ICON_X, 0);
            }
        }

        if (drawExhaustion) {
            // fitness = remaining margin (lower = more fatigue accumulated)
            float fitness = maxExhaustion - exhaustion;
            int halfs = (int) Math.floor(fitness / maxExhaustion * 21F);
            int fulls = halfs / 2;
            int half  = halfs % 2;

            // Y: height - 49. If in water, 10 upward
            int jy = height - 49 - (player.isInWater() ? 10 : 0);

            for (int i = 0; i < Math.min(fulls + half, 10); i++) {
                drawIcon(context, (width / 2 + 90) - (i + 1) * 8, jy, i < fulls ? 0 : 1, 0);
            }
        }
    }

    // Render 9×9 sprite at (iconX*9, iconY*9) position in icons.png to (x, y).
    // 26.2 blit overload order is (x0, y0, x1, y1, u0, u1, v0, v1).
    private static void drawIcon(GuiGraphicsExtractor context, int x, int y, int iconX, int iconY) {
        float u0 = (float) (iconX * ICON_SIZE) / 256.0F;
        float v0 = (float) (iconY * ICON_SIZE) / 256.0F;
        float u1 = (float) ((iconX + 1) * ICON_SIZE) / 256.0F;
        float v1 = (float) ((iconY + 1) * ICON_SIZE) / 256.0F;
        context.blit(ICONS, x, y, x + ICON_SIZE, y + ICON_SIZE, u0, u1, v0, v1);
    }
}
