package choco.ratel.smartmoving.windrunner;

import choco.ratel.smartmoving.SmartMoving;
import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.fabricmc.fabric.api.networking.v1.EntityTrackingEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Items;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** The Windrunner traversal armor set: registration, creative placement and its server-side packets. */
public final class Windrunner {
    public static final Logger LOGGER = LoggerFactory.getLogger("smartmoving/windrunner");

    private Windrunner() {}

    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(SmartMoving.MOD_ID, path);
    }

    public static void init() {
        WindrunnerItems.init();
        WindrunnerParticles.init();
        // The set is upgraded leather armor, so it follows the leather set among the armor.
        ResourceKey<CreativeModeTab> combat = ResourceKey.create(Registries.CREATIVE_MODE_TAB, Identifier.withDefaultNamespace("combat"));
        CreativeModeTabEvents.modifyOutputEvent(combat).register(output -> output.insertAfter(Items.LEATHER_BOOTS,
                WindrunnerItems.WINDRUNNER_HEADBAND, WindrunnerItems.WINDRUNNER_ROBES,
                WindrunnerItems.WINDRUNNER_PANTS, WindrunnerItems.WINDRUNNER_BOOTS));
        registerNetworking();
    }

    private static void registerNetworking() {
        PayloadTypeRegistry.serverboundPlay().register(WindrunnerGlideInputPayload.TYPE, WindrunnerGlideInputPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(WindrunnerGlideActionPayload.TYPE, WindrunnerGlideActionPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(WindrunnerGlideStatePayload.TYPE, WindrunnerGlideStatePayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(WindrunnerActionPayload.TYPE, WindrunnerActionPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(WindrunnerWallRunPayload.TYPE, WindrunnerWallRunPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(WindrunnerWallRunPayload.TYPE, WindrunnerWallRunPayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(WindrunnerWallRunPayload.TYPE, (payload, context) ->
                context.server().execute(() -> WindrunnerMoves.of(context.player()).wallRun(payload.mode(), payload.side())));
        ServerPlayNetworking.registerGlobalReceiver(WindrunnerGlideInputPayload.TYPE, (payload, context) ->
                context.server().execute(() -> WindrunnerGlide.of(context.player()).acceptInput(payload)));
        ServerPlayNetworking.registerGlobalReceiver(WindrunnerGlideActionPayload.TYPE, (payload, context) ->
                context.server().execute(() -> {
                    WindrunnerGlide glide = WindrunnerGlide.of(context.player());
                    if (!glide.request(payload.mode())) ServerPlayNetworking.send(context.player(), glide.snapshot());
                }));
        ServerPlayNetworking.registerGlobalReceiver(WindrunnerActionPayload.TYPE, (payload, context) ->
                context.server().execute(() -> WindrunnerMoves.of(context.player()).handle(payload.action())));
        EntityTrackingEvents.START_TRACKING.register((entity, observer) -> {
            if (entity instanceof Player player && WindrunnerGlide.of(player).active()) {
                ServerPlayNetworking.send(observer, WindrunnerGlide.of(player).snapshot());
            }
            if (entity instanceof Player player && WindrunnerMoves.of(player).showsWallRun()) {
                ServerPlayNetworking.send(observer, WindrunnerMoves.of(player).wallRunSnapshot());
            }
        });
    }
}
