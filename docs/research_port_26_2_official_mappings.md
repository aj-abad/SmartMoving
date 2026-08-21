# Research: Fabric 26.2 Port with Official Names

## Goal

Port SmartMoving from Fabric 1.21.1 with Yarn mappings to Fabric 26.2 using the
official, unobfuscated Minecraft names while preserving the existing movement,
networking, rendering, and dedicated-server behavior.

## Baseline

- Minecraft: `1.21.1`
- Mappings: Yarn `1.21.1+build.3`
- Fabric Loader: `0.19.2`
- Fabric API: `0.116.11+1.21.1`
- Loom: legacy `fabric-loom` plugin, resolved as `1.16.3`
- Gradle wrapper: `9.4.0`
- Java compile target: 21
- Source layout: split common/client source sets
- Size: 47 Java files and approximately 23,304 lines
- Baseline verification: `gradle build` succeeds before porting

## Official Fabric 26.2 Toolchain

The Fabric 26.2 documentation and example project establish the following
configuration:

- Minecraft `26.2`
- Fabric Loader `0.19.3`
- Fabric API `0.157.0+26.2` (current official example project value)
- Loom `1.17-SNAPSHOT`
- Gradle `9.5.1`
- Java release/source/target `25`
- Loom plugin ID `net.fabricmc.fabric-loom`

Minecraft 26.1 and newer are not obfuscated. Loom therefore uses the
non-remapping plugin and the source directly uses the official names shipped by
the game. A `mappings` dependency is not declared in a 26.2 build.

## Mapping Migration

Fabric explicitly recommends changing Yarn-based projects to Mojang mappings on
the old Minecraft version before changing the Minecraft dependency. Loom's
`migrateMappings` task was tested against this repository with
`net.minecraft:mappings:1.21.1` for both source sets.

The temporary migration succeeded and changed:

- 15 common Java files (approximately 356 additions and 358 deletions)
- 26 client Java files (approximately 1,427 additions and 1,444 deletions)

Representative official-name changes include:

| Yarn name | Official name |
| --- | --- |
| `MinecraftClient` | `Minecraft` |
| `ClientPlayerEntity` | `LocalPlayer` |
| `PlayerEntity` | `Player` |
| `ServerPlayerEntity` | `ServerPlayer` |
| `LivingEntity` | `LivingEntity` (package changes) |
| `Identifier` | `ResourceLocation` |
| `Vec3d` | `Vec3` |
| `Box` | `AABB` |
| `MathHelper` | `Mth` |
| `PlayerEntityModel` | `PlayerModel` |
| `MatrixStack` | `PoseStack` |

Loom's migration is semi-automatic. Mixin annotations, descriptors, shadows,
injection callbacks, and any members changed between 1.21.1 and 26.2 must be
reviewed manually after the automated rename.

## Expected 26.2 Migration Areas

SmartMoving directly touches several unstable vanilla and Fabric surfaces:

1. Entity movement and pose/dimensions (`Entity`, `LivingEntity`, `Player`).
2. Client input and local-player travel.
3. Server movement validation and teleport interception.
4. Custom payload registration, encoding, sending, and receiving.
5. HUD rendering and resource identifiers.
6. Player model, player renderer, cape layer, camera, and model-part transforms.
7. Block collision-shape and climbing queries.
8. Statistics, sounds, attributes, and interaction results.

The 26.2 Fabric announcement calls out rendering changes and GUI/HUD
reorganization. The port must therefore treat renderer and HUD mixins as
high-risk even when they compile.

## Implementation Strategy

1. Apply Loom's official-name migration to the common and client sources while
   still on Minecraft 1.21.1.
2. Switch the 1.21.1 build from Yarn to `loom.officialMojangMappings()` and build
   once. Repair any missed mapping or mixin changes at this intermediate stage.
3. Upgrade the Gradle wrapper, Loom plugin ID/version, Minecraft, Loader, Fabric
   API, Java target, resource metadata, and mod version to 26.2.
4. Remove the mappings dependency because 26.2 is unobfuscated.
5. Compile common and client sources iteratively, consulting the generated 26.2
   sources/Javadocs for changed vanilla and Fabric signatures.
6. Build the final jar and run non-interactive launch smoke tests where practical.
7. Review every mixin target and descriptor rather than accepting compilation as
   sufficient proof.

## Local Environment Notes

- The shell default is Java 17, but `C:\Program Files\Java\jdk-26.0.1` is
  installed and can compile with `--release 25`.
- The global Gradle cache path is not writable in the sandbox. Port verification
  uses a repository-local Gradle user home.

## Port Results

- `clean build` succeeds with Loom 1.17.19, Minecraft 26.2, Loader 0.19.3,
  Fabric API 0.157.0+26.2, and Java release 25.
- The build has no mappings dependency. The 26.2 runtime reports that mappings
  are not present, as expected for the unobfuscated release.
- Custom payload registration now uses the 26.2 serverbound/clientbound play
  registries, and the integrated-server smoke test completed login and Smart
  Moving configuration synchronization.
- Player dimensions are injected through the new `Avatar` base class. Client
  interpolation uses `InterpolationHandler` instead of removed lerp fields.
- Player rendering now carries the client player through `AvatarRenderState`.
  Model, cape, rotation, name display, hand rendering, and HUD hooks use the
  26.2 extraction/submission APIs.
- The client smoke test completed resource reload, opened an integrated world,
  joined the player, rendered the world, and toggled Smart Moving off and on.
- The dedicated-server smoke test generated a world and reached the `Done`
  state with the common/server mixins active.
- `build/libs/smartmoving-1.0.1+26.2.jar` contains the expanded version and
  dependency metadata plus both mixin configurations and the new bridge mixins.

These smoke tests validate startup and the main client/server integration path.
They do not replace manual gameplay regression testing of every movement mode,
animation, block shape, and multiplayer edge case.

## Primary Sources

- Fabric 26.2 announcement: <https://fabricmc.net/2026/06/15/262.html>
- Fabric porting guide: <https://docs.fabricmc.net/develop/porting/>
- Fabric mapping migration guide:
  <https://docs.fabricmc.net/develop/porting/mappings/>
- Loom mapping migration guide (1.21.11; commands also accept the current old
  version): <https://docs.fabricmc.net/1.21.11/develop/porting/mappings/loom>
- Loom plugin IDs: <https://docs.fabricmc.net/develop/loom/>
- Fabric 26.2 example project:
  <https://github.com/FabricMC/fabric-example-mod/tree/26.2>
