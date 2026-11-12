package choco.ratel.smartmoving.windrunner;

import choco.ratel.smartmoving.client.windrunner.WindrunnerGlideControls;
import choco.ratel.smartmoving.client.windrunner.WindrunnerControls;
import choco.ratel.smartmoving.client.input.SmartMovingKeys;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.minecraft.client.KeyMapping;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.CameraType;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

/** Exercises the actual mixins, equipment, packets and renderer in an isolated integrated server. */
public final class WindrunnerClientTest implements FabricClientGameTest {
    @Override public void runTest(ClientGameTestContext context) {
        try (var world = context.worldBuilder().create()) {
            context.waitFor(client -> client.player != null && client.gui.screen() == null);
            context.runOnClient(client -> {
                SmartMovingKeys.dodge.setKey(InputConstants.Type.KEYBOARD.getOrCreate(InputConstants.KEY_V));
                KeyMapping.resetMapping();
            });
            context.runOnClient(client -> {
                client.options.setCameraType(CameraType.FIRST_PERSON);
                WindrunnerAnimationClientTest.run(client);
            });
            // Deployment gate: no fall delay, but three blocks above both terrain and water.
            world.getServer().runOnServer(server -> {
                var player = server.getPlayerList().getPlayers().getFirst();
                player.setGameMode(GameType.SURVIVAL);
                player.setItemSlot(EquipmentSlot.CHEST, new ItemStack(WindrunnerItems.WINDRUNNER_ROBES));
                int ground = player.level().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, 0, 0);
                for (int y = ground; y < ground + 12; y++) {
                    player.level().setBlock(new BlockPos(0, y, 0), Blocks.STONE.defaultBlockState(), 3);
                }
                // The pillar ends at ground + 12, leaving only 2.5 blocks beneath the player's feet.
                player.connection.teleport(0.5, ground + 14.5, 0.5, 0, 0);
                player.setDeltaMovement(Vec3.ZERO);
                player.resetFallDistance();
                player.syncVelocity = true;
            });
            context.waitTicks(2);
            if (context.computeOnClient(client -> WindrunnerGlide.of(client.player).canDeploy())) {
                throw new AssertionError("A terrain surface within three blocks must prevent deployment");
            }
            context.getInput().pressKey(options -> WindrunnerGlideControls.glideKey());
            context.waitTicks(4);
            if (context.computeOnClient(client -> WindrunnerGlide.of(client.player).active())) {
                throw new AssertionError("Too little clearance below must not deploy");
            }
            context.waitFor(client -> client.player.onGround(), 60);
            // Real play: walking clear of the ledge may deploy immediately, before falling a block.
            context.getInput().holdKey(options -> options.keyUp);
            context.waitFor(client -> !client.player.onGround()
                    && WindrunnerGlide.of(client.player).canDeploy(), 60);
            if (context.computeOnClient(client -> client.player.fallDistance) >= 1) {
                throw new AssertionError("Ledge deployment must not require a block of fall");
            }
            context.getInput().pressKey(options -> WindrunnerGlideControls.glideKey());
            context.waitFor(client -> WindrunnerGlide.of(client.player).mode() == WindrunnerGlide.GLIDING, 40);
            context.getInput().releaseKey(options -> options.keyUp);
            context.getInput().pressKey(options -> options.keyShift);
            context.waitFor(client -> !WindrunnerGlide.of(client.player).active(), 40);

            world.getServer().runOnServer(server -> {
                var player = server.getPlayerList().getPlayers().getFirst();
                int ground = player.level().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, 3, 0);
                player.level().setBlock(new BlockPos(3, ground, 0), Blocks.STONE.defaultBlockState(), 3);
                player.level().setBlock(new BlockPos(3, ground + 1, 0), Blocks.WATER.defaultBlockState(), 3);
                // The solid surface is 3.5 blocks below the feet, but the water surface is only 2.5 below.
                player.connection.teleport(3.5, ground + 4.5, 0.5, 0, 0);
                player.setDeltaMovement(Vec3.ZERO);
                player.resetFallDistance();
                player.syncVelocity = true;
            });
            context.waitTicks(2);
            if (context.computeOnClient(client -> WindrunnerGlide.of(client.player).canDeploy())) {
                throw new AssertionError("A water surface within three blocks must prevent deployment");
            }
            context.getInput().pressKey(options -> WindrunnerGlideControls.glideKey());
            context.waitTicks(3);
            if (context.computeOnClient(client -> WindrunnerGlide.of(client.player).active())) {
                throw new AssertionError("Water clearance must be enforced by the deployment input path");
            }

            world.getServer().runOnServer(server -> {
                var player = server.getPlayerList().getPlayers().getFirst();
                player.connection.teleport(0, 180, 0, 0, 0);
                player.setDeltaMovement(new Vec3(0, -0.15, 0.8));
                player.syncVelocity = true;
            });
            context.waitTicks(8);
            context.getInput().pressKey(options -> WindrunnerGlideControls.glideKey()); // Mouse 4 by default
            context.waitFor(client -> WindrunnerGlide.of(client.player).mode() == WindrunnerGlide.GLIDING, 100);
            world.getServer().waitFor(server -> WindrunnerGlide.of(server.getPlayerList().getPlayers().getFirst()).mode() == WindrunnerGlide.GLIDING);
            context.runOnClient(client -> {
                client.options.setCameraType(CameraType.THIRD_PERSON_BACK);
                client.player.setYRot(WindrunnerGlide.of(client.player).state().yaw + 160);
                client.player.setXRot(35);
            });
            context.waitTicks(2);
            context.takeScreenshot("fan-wings-unfurl");
            context.runOnClient(client -> client.options.setCameraType(CameraType.FIRST_PERSON));
            context.waitTicks(78);
            context.runOnClient(client -> {
                client.options.setCameraType(CameraType.THIRD_PERSON_BACK);
                client.player.setYRot(WindrunnerGlide.of(client.player).state().yaw + 160);
                client.player.setXRot(35);
            });
            context.waitTicks(1);
            context.takeScreenshot("fan-wings-spread");
            context.runOnClient(client -> client.options.setCameraType(CameraType.FIRST_PERSON));
            context.waitTicks(2);
            // Bank from the settled, unpowered glide; a lit rocket would otherwise fund a climbing turn.
            double initialY = context.computeOnClient(client -> client.player.getY());
            context.getInput().holdKeyFor(options -> options.keyRight, 20);
            float roll = context.computeOnClient(client -> WindrunnerGlide.of(client.player).state().roll);
            if (roll > -2) throw new AssertionError("Native right input must bank the suit");
            context.runOnClient(WindrunnerRenderingTest::run);
            double y = context.computeOnClient(client -> client.player.getY());
            world.getServer().runOnServer(server -> {
                var p = server.getPlayerList().getPlayers().getFirst();
                Vec3 position = p.position();
                p.connection.handleMovePlayer(new ServerboundMovePlayerPacket.Pos(position.add(100, 100, 100), true, false));
                if (!p.position().equals(position)) throw new AssertionError("Client position packets cannot override server flight");
            });
            if (y >= initialY) throw new AssertionError("Unpowered turning must spend altitude");
            // Fireworks no longer touch the glide; each fresh dash press is a boost along the flight axis.
            world.getServer().runOnServer(server -> {
                var player = server.getPlayerList().getPlayers().getFirst();
                player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.FIREWORK_ROCKET));
                Items.FIREWORK_ROCKET.use(player.level(), player, InteractionHand.MAIN_HAND);
                if (player.getItemInHand(InteractionHand.MAIN_HAND).isEmpty()) {
                    throw new AssertionError("A firework must not be spent on the Windrunner glide");
                }
                player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
                player.setItemSlot(EquipmentSlot.HEAD, new ItemStack(WindrunnerItems.WINDRUNNER_HEADBAND));
            });
            context.waitTicks(2);
            Vec3 boostDirection = world.getServer().computeOnServer(server ->
                    WindrunnerGlide.of(server.getPlayerList().getPlayers().getFirst()).forward());
            double speedBeforeBoost = world.getServer().computeOnServer(server ->
                    server.getPlayerList().getPlayers().getFirst().getDeltaMovement().dot(boostDirection));
            int wearBeforeBoost = world.getServer().computeOnServer(server ->
                    server.getPlayerList().getPlayers().getFirst().getItemBySlot(EquipmentSlot.CHEST).getDamageValue());
            context.getInput().pressKey(options -> WindrunnerControls.dashKey());
            context.waitTicks(3);
            double speedAfterBoost = world.getServer().computeOnServer(server ->
                    server.getPlayerList().getPlayers().getFirst().getDeltaMovement().dot(boostDirection));
            if (speedAfterBoost < speedBeforeBoost + 0.2) {
                throw new AssertionError("A dash press while gliding must boost the robes along their flight axis");
            }
            world.getServer().runOnServer(server -> {
                var player = server.getPlayerList().getPlayers().getFirst();
                if (player.getItemBySlot(EquipmentSlot.CHEST).getDamageValue() < wearBeforeBoost + WindrunnerGlideWear.BOOST) {
                    throw new AssertionError("A glide boost must cost robe durability");
                }
            });
            context.runOnClient(client -> {
                client.options.setCameraType(CameraType.THIRD_PERSON_BACK);
                client.player.setYRot(WindrunnerGlide.of(client.player).state().yaw + 135);
                client.player.setXRot(20);
            });
            context.waitTicks(2);
            context.takeScreenshot("wingsuit-flight");
            context.getInput().holdKeyFor(options -> options.keyUp, 16);
            context.takeScreenshot("fan-wings-dive");
            var clientPosition = context.computeOnClient(client -> client.player.position());
            var serverPosition = world.getServer().computeOnServer(server -> server.getPlayerList().getPlayers().getFirst().position());
            if (clientPosition.distanceTo(serverPosition) > 4) throw new AssertionError("Client prediction must follow an authoritative boost");
            context.getInput().pressKey(options -> options.keyShift);
            context.waitFor(client -> !WindrunnerGlide.of(client.player).active(), 100);
            world.getServer().runOnServer(server -> {
                var player = server.getPlayerList().getPlayers().getFirst();
                if (WindrunnerGlide.of(player).active()) throw new AssertionError("Cut-away must reach the server");
                player.setItemSlot(EquipmentSlot.CHEST, ItemStack.EMPTY);
            });
            context.getInput().pressKey(options -> WindrunnerGlideControls.glideKey());
            context.waitTicks(5);
            if (context.computeOnClient(client -> WindrunnerGlide.of(client.player).active())) throw new AssertionError("Unequipped player cannot deploy");

            world.getServer().runOnServer(server -> server.getPlayerList().getPlayers().getFirst()
                    .setItemSlot(EquipmentSlot.CHEST, new ItemStack(WindrunnerItems.WINDRUNNER_ROBES)));
            context.waitTicks(3);
            context.getInput().pressKey(options -> WindrunnerGlideControls.glideKey());
            context.waitFor(client -> WindrunnerGlide.of(client.player).active(), 100);
            world.getServer().runOnServer(server -> server.getPlayerList().getPlayers().getFirst()
                    .setItemSlot(EquipmentSlot.CHEST, ItemStack.EMPTY));
            context.waitFor(client -> !WindrunnerGlide.of(client.player).active(), 100);

            // Robes: holding sneak in free air slows the fall; the boots alone do not.
            world.getServer().runOnServer(server -> {
                var p = server.getPlayerList().getPlayers().getFirst();
                p.setItemSlot(EquipmentSlot.CHEST, ItemStack.EMPTY);
                p.setItemSlot(EquipmentSlot.FEET, new ItemStack(WindrunnerItems.WINDRUNNER_BOOTS));
                int ground = p.level().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, 0, 0);
                p.connection.teleport(0.5, ground + 35, 0.5, 0, 0);
                p.setDeltaMovement(new Vec3(0, -0.1, 0.25));
                p.resetFallDistance();
                p.syncVelocity = true;
                p.setHealth(p.getMaxHealth());
            });
            context.waitTicks(3);
            context.getInput().holdKey(options -> options.keyShift);
            context.waitTicks(12);
            if (context.computeOnClient(client -> -client.player.getDeltaMovement().y) < WindrunnerMoves.SLOW_FALL_MAX_SINK) {
                throw new AssertionError("Sneaking with the boots alone must not slow the fall");
            }
            world.getServer().runOnServer(server -> server.getPlayerList().getPlayers().getFirst()
                    .setItemSlot(EquipmentSlot.CHEST, new ItemStack(WindrunnerItems.WINDRUNNER_ROBES)));
            context.waitTicks(30);
            double sink = context.computeOnClient(client -> -client.player.getDeltaMovement().y);
            if (sink > WindrunnerMoves.SLOW_FALL_MAX_SINK) throw new AssertionError("Sneaking with the robes must slow the fall");
            context.takeScreenshot("windrunner-slow-fall");
            context.waitFor(client -> client.player.onGround(), 600);
            context.getInput().releaseKey(options -> options.keyShift);
            context.waitTicks(5);
            world.getServer().runOnServer(server -> {
                var p = server.getPlayerList().getPlayers().getFirst();
                if (p.getHealth() != p.getMaxHealth()) throw new AssertionError("A slow-fall landing must not trigger vanilla fall damage");
                if (Math.abs(WindrunnerArmor.jumpScale(p) - 1.1) > 1.0E-6
                        || Math.abs(p.getAttributeValue(Attributes.FALL_DAMAGE_MULTIPLIER) - 0.9) > 1.0E-6) {
                    throw new AssertionError("The boots must add 10% jump strength and take 10% off fall damage");
                }
                int ground = p.level().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, 0, 0);
                p.connection.teleport(0.5, ground + 20, 0.5, 0, 0);
                p.setDeltaMovement(Vec3.ZERO);
                p.syncVelocity = true;
            });
            context.waitTicks(8);
            context.getInput().pressKey(options -> WindrunnerGlideControls.glideKey());
            context.waitFor(client -> WindrunnerGlide.of(client.player).active(), 100);
            world.getServer().runOnServer(server -> {
                var p = server.getPlayerList().getPlayers().getFirst();
                int ground = p.level().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, 0, 0);
                p.connection.teleport(0.5, ground + 20, 0.5, 0, 0);
                if (WindrunnerGlide.of(p).active()) throw new AssertionError("Teleport must cancel flight");
            });
            context.waitFor(client -> !WindrunnerGlide.of(client.player).active(), 100);
            context.waitTicks(6);
            // Jump in the air is the boots' double jump only; the robes wait for the glide key.
            context.getInput().pressKey(options -> options.keyJump);
            context.waitTicks(2);
            if (context.computeOnClient(client -> WindrunnerGlide.of(client.player).active()
                    || client.player.getDeltaMovement().y < 0.2)) {
                throw new AssertionError("An airborne jump must double-jump without opening the robes");
            }
            context.waitFor(client -> client.player.getDeltaMovement().y < -0.1, 60);
            context.getInput().pressKey(options -> options.keyJump);
            context.waitTicks(2);
            if (context.computeOnClient(client -> client.player.getDeltaMovement().y > 0)) {
                throw new AssertionError("A spent double jump cannot fire again before the dash");
            }
            // The headband dashes along the view; the air dash no longer refills the double jump.
            context.runOnClient(client -> client.player.setYRot(0));
            context.getInput().pressKey(options -> WindrunnerControls.dashKey());
            context.waitTicks(1);
            double dash = context.computeOnClient(client -> client.player.getDeltaMovement().z);
            if (dash < 0.9) throw new AssertionError("The headband's mid-air dash must launch along the view");
            context.getInput().pressKey(options -> options.keyJump);
            context.waitTicks(2);
            if (context.computeOnClient(client -> client.player.getDeltaMovement().y > 0.2)) {
                throw new AssertionError("An air dash must not refill the double jump");
            }
            context.waitFor(client -> client.player.getDeltaMovement().y < -0.3, 60);
            context.getInput().pressKey(options -> WindrunnerGlideControls.glideKey());
            context.waitFor(client -> WindrunnerGlide.of(client.player).mode() == WindrunnerGlide.GLIDING, 40);
            context.getInput().pressKey(options -> options.keyShift);
            context.waitFor(client -> !WindrunnerGlide.of(client.player).active(), 40);
            context.getInput().pressKey(options -> WindrunnerControls.dashKey());
            context.waitTicks(15);
            if (context.computeOnClient(client -> client.player.getDeltaMovement().horizontalDistance()) > 0.5) {
                throw new AssertionError("The dash is spent until the player lands");
            }
            // The headband's own ground dash hops low along the ground. Its hop is still on the ground for
            // dashing: no air dash, and a jump out of it is the ground jump, keeping the double jump.
            world.getServer().runOnServer(server -> {
                var p = server.getPlayerList().getPlayers().getFirst();
                int ground = p.level().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, 40, 0);
                for (int x = 38; x < 100; x++) for (int z = -2; z <= 2; z++) {
                    for (int h = ground; h < ground + 12; h++) p.level().setBlock(new BlockPos(x, h, z), Blocks.AIR.defaultBlockState(), 3);
                    p.level().setBlock(new BlockPos(x, ground - 1, z), Blocks.STONE.defaultBlockState(), 3);
                }
                // Lift the pilot out of the fall after the air dash straight onto clear, flat ground.
                p.resetFallDistance();
                p.connection.teleport(40.5, ground, 0.5, -90, 0);
                p.setDeltaMovement(Vec3.ZERO);
                p.syncVelocity = true;
            });
            context.waitFor(client -> client.player.onGround() && client.player.getX() > 40, 100);
            context.waitTicks(12);
            context.getInput().pressKey(options -> WindrunnerControls.dashKey());
            context.waitTicks(1);
            Vec3 groundDash = context.computeOnClient(client -> client.player.getDeltaMovement());
            if (groundDash.x < 0.9 || groundDash.y <= 0) {
                throw new AssertionError("The headband must ground-dash along the view as a low hop: " + groundDash);
            }
            context.getInput().pressKey(options -> WindrunnerControls.dashKey());
            context.waitTicks(1);
            if (context.computeOnClient(client -> client.player.getDeltaMovement().x) > 1.0) {
                throw new AssertionError("A ground dash's hop must not air-dash");
            }
            context.getInput().pressKey(options -> options.keyJump);
            context.waitTicks(1);
            Vec3 dashJump = context.computeOnClient(client -> client.player.getDeltaMovement());
            if (dashJump.y < 0.2 || dashJump.horizontalDistance() < 0.4) {
                throw new AssertionError("A jump out of a ground dash must launch like a ground jump, keeping the dash: " + dashJump);
            }
            context.waitFor(client -> client.player.getDeltaMovement().y < -0.1, 60);
            context.getInput().pressKey(options -> options.keyJump);
            context.waitTicks(2);
            if (context.computeOnClient(client -> client.player.getDeltaMovement().y < 0.3)) {
                throw new AssertionError("Jumping out of a ground dash must leave the double jump in hand");
            }
            context.waitFor(client -> client.player.onGround(), 200);
            // Off a cliff the ground dash is a fall: the next jump spends the double jump, and the air dash returns.
            world.getServer().runOnServer(server -> {
                var p = server.getPlayerList().getPlayers().getFirst();
                int ground = p.level().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, 45, 0);
                for (int h = ground; h < ground + 8; h++) p.level().setBlock(new BlockPos(45, h, 0), Blocks.STONE.defaultBlockState(), 3);
                p.setHealth(p.getMaxHealth());
                p.resetFallDistance();
                p.connection.teleport(45.5, ground + 8, 0.5, -90, 0);
                p.setDeltaMovement(Vec3.ZERO);
                p.syncVelocity = true;
            });
            context.waitFor(client -> client.player.onGround() && client.player.getX() > 45 && client.player.getX() < 46, 100);
            double cliffTop = context.computeOnClient(client -> client.player.getY());
            context.waitTicks(12);
            context.getInput().pressKey(options -> WindrunnerControls.dashKey());
            context.waitFor(client -> client.player.getY() < cliffTop - 1.2, 40);
            context.getInput().pressKey(options -> options.keyJump);
            context.waitTicks(2);
            if (context.computeOnClient(client -> client.player.getDeltaMovement().y < 0.3)) {
                throw new AssertionError("A jump after ground-dashing off a cliff must be the double jump");
            }
            context.waitFor(client -> client.player.getDeltaMovement().y < -0.1, 60);
            context.getInput().pressKey(options -> options.keyJump);
            context.waitTicks(2);
            if (context.computeOnClient(client -> client.player.getDeltaMovement().y > 0)) {
                throw new AssertionError("Ground-dashing off a cliff must not leave a free jump behind the double jump");
            }
            context.getInput().pressKey(options -> WindrunnerControls.dashKey());
            context.waitTicks(1);
            if (context.computeOnClient(client -> client.player.getDeltaMovement().horizontalDistance()) < 0.9) {
                throw new AssertionError("Once a ground dash leaves its height, the air dash returns");
            }
            context.runOnClient(client -> {
                client.options.setCameraType(CameraType.THIRD_PERSON_BACK);
                client.player.setXRot(25);
            });
            context.takeScreenshot("windrunner-air-dash");
            context.getInput().holdKey(options -> options.keyShift);
            context.waitFor(client -> client.player.onGround(), 200);
            context.getInput().releaseKey(options -> options.keyShift);
            if (!context.computeOnClient(client -> client.player.isAlive())) throw new AssertionError("The test pilot must survive the dash jumps");
            world.getServer().runOnServer(server -> {
                var p = server.getPlayerList().getPlayers().getFirst();
                p.setItemSlot(EquipmentSlot.FEET, ItemStack.EMPTY);
                p.setItemSlot(EquipmentSlot.HEAD, ItemStack.EMPTY);
                p.setHealth(p.getMaxHealth());
                p.setGameMode(GameType.CREATIVE);
                int ground = p.level().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, 0, 0);
                p.connection.teleport(0.5, ground + 40, 0.5, 0, 0);
                p.setDeltaMovement(Vec3.ZERO);
                p.syncVelocity = true;
            });
            context.waitTicks(8);
            // A quick double tap of jump is still vanilla's creative flight, which closes the robes.
            context.getInput().pressKey(options -> WindrunnerGlideControls.glideKey());
            context.waitFor(client -> WindrunnerGlide.of(client.player).mode() == WindrunnerGlide.GLIDING, 100);
            context.getInput().holdKeyFor(options -> options.keyJump, 1);
            context.waitTicks(2);
            context.getInput().holdKeyFor(options -> options.keyJump, 1);
            context.waitFor(client -> !WindrunnerGlide.of(client.player).active() && client.player.getAbilities().flying, 100);
            world.getServer().waitFor(server -> {
                var p = server.getPlayerList().getPlayers().getFirst();
                return !WindrunnerGlide.of(p).active() && p.getAbilities().flying;
            });
            Windrunner.LOGGER.info("Windrunner glide, camera, glide key, boosts, slow fall, boots attributes, double jump, dash jump, dash, equipment, authority, teleport, landing and creative double-tap scenarios passed.");
        }
    }
}
