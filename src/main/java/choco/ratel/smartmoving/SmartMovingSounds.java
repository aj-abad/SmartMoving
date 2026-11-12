package choco.ratel.smartmoving;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;

public final class SmartMovingSounds {

    public static final SoundEvent CLIMB         = register("climb");
    public static final SoundEvent SLIDE         = register("slide");
    public static final SoundEvent CEILING_CLIMB = register("ceiling_climb");
    public static final SoundEvent WINDRUNNER_UNFURL = register("windrunner_unfurl");
    public static final SoundEvent WINDRUNNER_WIND = register("windrunner_wind");

    public static void register() {}

    private static SoundEvent register(String name) {
        Identifier id = Identifier.fromNamespaceAndPath(SmartMoving.MOD_ID, name);
        return Registry.register(BuiltInRegistries.SOUND_EVENT, id, SoundEvent.createVariableRangeEvent(id));
    }

    private SmartMovingSounds() {}
}
