package choco.ratel.smartmoving.windrunner;

import java.util.Map;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.tags.TagKey;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.equipment.ArmorMaterial;
import net.minecraft.world.item.equipment.ArmorType;
import net.minecraft.world.item.equipment.EquipmentAssets;

/** The sky-cloth traversal set: leather-grade protection, each piece carrying one movement ability. */
public final class WindrunnerArmor {
    public static final TagKey<Item> REPAIRS_ARMOR = TagKey.create(Registries.ITEM, Windrunner.id("repairs_windrunner_armor"));
    public static final TagKey<Item> REPAIRS_ROBES = TagKey.create(Registries.ITEM, Windrunner.id("repairs_windrunner_robes"));
    public static final double PANTS_SPEED = 0.15;
    public static final double BOOTS_JUMP = 0.1, BOOTS_SAFE_FALL = 3;
    /** Nine hearts: at or above this when a lethal fall lands, the boots leave half a heart instead. */
    public static final float BOOTS_SAVE_HEALTH = 18;
    public static final ArmorMaterial MATERIAL = new ArmorMaterial(9,
            Map.of(ArmorType.HELMET, 1, ArmorType.CHESTPLATE, 3, ArmorType.LEGGINGS, 2, ArmorType.BOOTS, 1, ArmorType.BODY, 3),
            15, SoundEvents.ARMOR_EQUIP_LEATHER, 0, 0, REPAIRS_ARMOR, ResourceKey.create(EquipmentAssets.ROOT_ID, Windrunner.id("windrunner")));

    private WindrunnerArmor() {}

    public static Item headband(Item.Properties properties) {
        return new Item(properties.humanoidArmor(MATERIAL, ArmorType.HELMET));
    }

    /** The glide membranes are sewn into the robes, so they keep a 240-point fabric budget. */
    public static Item robes(Item.Properties properties) {
        return new Item(properties.humanoidArmor(MATERIAL, ArmorType.CHESTPLATE)
                .durability(WindrunnerGlideWear.DURABILITY).repairable(REPAIRS_ROBES));
    }

    public static Item pants(Item.Properties properties) {
        return new Item(properties.humanoidArmor(MATERIAL, ArmorType.LEGGINGS)
                .attributes(MATERIAL.createAttributes(ArmorType.LEGGINGS).withModifierAdded(Attributes.MOVEMENT_SPEED,
                        new AttributeModifier(Windrunner.id("windrunner_pants_speed"), PANTS_SPEED,
                                AttributeModifier.Operation.ADD_MULTIPLIED_BASE), EquipmentSlotGroup.LEGS)));
    }

    /**
     * Ten percent more jump strength lifts a standing jump from 1.25 to about 1.5 blocks; vanilla Jump
     * Boost still adds on top. Falls hurt from six blocks instead of three, and the fall
     * itself tops out at {@link WindrunnerMoves#FALL_CAP}.
     */
    public static Item boots(Item.Properties properties) {
        return new Item(properties.humanoidArmor(MATERIAL, ArmorType.BOOTS)
                .attributes(MATERIAL.createAttributes(ArmorType.BOOTS)
                        .withModifierAdded(Attributes.JUMP_STRENGTH, new AttributeModifier(Windrunner.id("windrunner_boots_jump"),
                                BOOTS_JUMP, AttributeModifier.Operation.ADD_MULTIPLIED_BASE), EquipmentSlotGroup.FEET)
                        .withModifierAdded(Attributes.SAFE_FALL_DISTANCE, new AttributeModifier(Windrunner.id("windrunner_boots_safe_fall"),
                                BOOTS_SAFE_FALL, AttributeModifier.Operation.ADD_VALUE), EquipmentSlotGroup.FEET)));
    }

    /**
     * How much stronger than standard this player's jump is, from the jump-strength attribute; the boots
     * make it 1.1. Jumps this mod or SmartMoving compute themselves scale by it to match vanilla's.
     */
    public static double jumpScale(Player player) {
        double base = player.getAttributeBaseValue(Attributes.JUMP_STRENGTH);
        return base > 0 ? player.getAttributeValue(Attributes.JUMP_STRENGTH) / base : 1;
    }

    /** A vanilla ground jump's launch speed: jump strength plus any Jump Boost. */
    public static double jumpPower(Player player) {
        return player.getAttributeValue(Attributes.JUMP_STRENGTH) + player.getJumpBoostPower();
    }

    public static boolean headband(Player player) { return player.getItemBySlot(EquipmentSlot.HEAD).is(WindrunnerItems.WINDRUNNER_HEADBAND); }
    public static boolean savesFall(LivingEntity entity, DamageSource source, float healthBefore) {
        return entity instanceof Player player && boots(player) && source.is(DamageTypeTags.IS_FALL)
                && healthBefore >= BOOTS_SAVE_HEALTH;
    }

    public static boolean pants(Player player) { return player.getItemBySlot(EquipmentSlot.LEGS).is(WindrunnerItems.WINDRUNNER_PANTS); }
    public static boolean boots(Player player) { return player.getItemBySlot(EquipmentSlot.FEET).is(WindrunnerItems.WINDRUNNER_BOOTS); }
}
