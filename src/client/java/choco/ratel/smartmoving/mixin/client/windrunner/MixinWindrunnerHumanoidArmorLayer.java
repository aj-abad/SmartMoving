package choco.ratel.smartmoving.mixin.client.windrunner;

import choco.ratel.smartmoving.client.windrunner.model.WindrunnerFittedRobes;
import choco.ratel.smartmoving.windrunner.WindrunnerItems;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Draws the Windrunner Robes on their fitted chest model; babies keep vanilla's scaled armor. */
@Mixin(HumanoidArmorLayer.class)
public abstract class MixinWindrunnerHumanoidArmorLayer {
    @ModifyExpressionValue(method = "renderArmorPiece", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/entity/layers/HumanoidArmorLayer;getArmorModel(Lnet/minecraft/client/renderer/entity/state/HumanoidRenderState;Lnet/minecraft/world/entity/EquipmentSlot;)Lnet/minecraft/client/model/HumanoidModel;"))
    private HumanoidModel<?> windrunner$fittedRobes(HumanoidModel<?> original, PoseStack pose, SubmitNodeCollector collector,
                                                    ItemStack stack, EquipmentSlot slot, int light, HumanoidRenderState state) {
        return slot == EquipmentSlot.CHEST && !state.isBaby && stack.is(WindrunnerItems.WINDRUNNER_ROBES)
                ? WindrunnerFittedRobes.model() : original;
    }
}
