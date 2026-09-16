package choco.ratel.smartmoving;

import choco.ratel.smartmoving.config.SmartMovingConfig;
import choco.ratel.smartmoving.network.SmartMovingNetwork;
import choco.ratel.smartmoving.server.SmartMovingServer;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.networking.v1.EntityTrackingEvents;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;

public class SmartMoving implements ModInitializer {

    public static final String MOD_ID = "smartmoving";

    @Override
    public void onInitialize() {
        System.out.println("🚀ViewStudio SmartMoving Start🚀");
        SmartMovingConfig.load();
        SmartMovingSounds.register();
        SmartMovingNetwork.register();
        registerServerReceivers();
        registerConnectionEvents();
    }

    private static void registerConnectionEvents() {
        // 3-7: Player join — SM initialization and send ConfigContent packet
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
                SmartMovingServer.initialize(handler.player, server));

        // Player disconnect — clean up instance
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            SmartMovingServer.remove(handler.player);
        });

        // 🔴 (Phase 2 multi network optimization) on-tracking-start replay.
        //   When new tracker (= player) starts tracking tracked, send tracked's latest SM state.
        //   Use together with server-side broadcast dirty check → broadcast only on change while safe for late join.
        EntityTrackingEvents.START_TRACKING.register((trackedEntity, player) -> {
            if (!(trackedEntity instanceof ServerPlayer tracked)) return;
            if (tracked == player) return;
            SmartMovingServer sm = SmartMovingServer.get(tracked);
            ServerPlayNetworking.send(player,
                    new SmartMovingNetwork.StatePayload(tracked.getId(), sm.lastBroadcastBits));
        });
    }

    private static void registerServerReceivers() {
        // State: Receive client movement state → relay to tracking players + update server state
        // 🔴 (Phase 2 multi network optimization) server-side broadcast dirty check:
        //   client sends per tick (= safe, late join auto backup), broadcast only on server-side change.
        //   client → server traffic same, server → clients broadcast saves 80-90%.
        ServerPlayNetworking.registerGlobalReceiver(SmartMovingNetwork.StatePayload.ID,
            (payload, context) -> {
                ServerPlayer sender = context.player();
                SmartMovingServer sm = SmartMovingServer.get(sender);
                sm.processStatePacket(sender, payload.state());
                long state = payload.state();
                if (state == sm.lastBroadcastBits) return;  // Dirty check: skip broadcast if same state.
                sm.lastBroadcastBits = state;
                context.server().execute(() -> {
                    for (ServerPlayer tracker : PlayerLookup.tracking(sender)) {
                        if (tracker != sender) {
                            ServerPlayNetworking.send(tracker, payload);
                        }
                    }
                });
            });

        // 🔵 Stats: self → server → REMOTE per tick stats angle sync (2026-05-14, Option F).
        //   No dirty check (= angle changes per tick). Broadcast only to trackers using PlayerLookup.tracking.
        //   No equivalent packet in original SmartMoving — addition specific to our mapping to bypass
        //   1.21.1 vanilla lerpPosAndRotation position flattening.
        ServerPlayNetworking.registerGlobalReceiver(SmartMovingNetwork.StatsPayload.ID,
            (payload, context) -> {
                ServerPlayer sender = context.player();
                context.server().execute(() -> {
                    for (ServerPlayer tracker : PlayerLookup.tracking(sender)) {
                        if (tracker != sender) {
                            ServerPlayNetworking.send(tracker, payload);
                        }
                    }
                });
            });

        // ConfigInfo: Receive client config info → save SM version (C-16)
        ServerPlayNetworking.registerGlobalReceiver(SmartMovingNetwork.ConfigInfoPayload.ID,
            (payload, context) ->
                SmartMovingServer.get(context.player()).processConfigInfoPacket(payload.config()));

        // ConfigChange: Receive client config change request → send rejection response (C-17)
        ServerPlayNetworking.registerGlobalReceiver(SmartMovingNetwork.ConfigChangePayload.ID,
            (payload, context) ->
                SmartMovingServer.processConfigChangePacket(context.player()));

        // SpeedChange: Receive client speed change request → verify permission and respond (C-18)
        ServerPlayNetworking.registerGlobalReceiver(SmartMovingNetwork.SpeedChangePayload.ID,
            (payload, context) ->
                SmartMovingServer.processSpeedChangePacket(context.player(), payload.difference()));

        // HungerChange: Receive client exhaustion value — update server hunger field
        ServerPlayNetworking.registerGlobalReceiver(SmartMovingNetwork.HungerChangePayload.ID,
            (payload, context) -> {
                SmartMovingServer.get(context.player()).hunger = payload.hunger();
            });

        // Sound: Receive SM sound request → play to nearby players (C-19)
        ServerPlayNetworking.registerGlobalReceiver(SmartMovingNetwork.SoundPayload.ID,
            (payload, context) -> {
                ServerPlayer sender = context.player();
                SoundEvent event = BuiltInRegistries.SOUND_EVENT.getValue(Identifier.parse(payload.soundId()));
                if (event == null) return;
                context.server().execute(() ->
                    sender.level().playSound(
                        sender,
                        sender.getX(), sender.getY(), sender.getZ(),
                        event, SoundSource.PLAYERS,
                        payload.volume(), payload.pitch()));
            });
    }
}
