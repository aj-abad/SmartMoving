package choco.ratel.smartmoving.windrunner;

import java.util.function.Function;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;

/** Item registration using the id-before-construction pattern required by 26.3. */
public final class WindrunnerItems {
    public static final Item WINDRUNNER_HEADBAND = register("windrunner_headband", WindrunnerArmor::headband);
    public static final Item WINDRUNNER_ROBES = register("windrunner_robes", WindrunnerArmor::robes);
    public static final Item WINDRUNNER_PANTS = register("windrunner_pants", WindrunnerArmor::pants);
    public static final Item WINDRUNNER_BOOTS = register("windrunner_boots", WindrunnerArmor::boots);

    private WindrunnerItems() {}

    private static Item register(String name, Function<Item.Properties, Item> factory) {
        ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, Windrunner.id(name));
        Item.Properties properties = new Item.Properties().stacksTo(1).setId(key);
        return Registry.register(BuiltInRegistries.ITEM, key, factory.apply(properties));
    }

    public static void init() {}
}
