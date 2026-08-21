# Checklist: Fabric 26.2 Port with Official Names

## Research and Baseline

- [x] Read repository rules and existing build configuration.
- [x] Confirm the unmodified 1.21.1 project builds successfully.
- [x] Verify the official Fabric 26.2 toolchain versions.
- [x] Verify Loom can migrate both split source sets to official names.
- [x] Record the migration strategy and high-risk API areas.

## Official-Name Migration on 1.21.1

- [x] Apply the generated official-name migration to common sources.
- [x] Apply the generated official-name migration to client sources.
- [x] Replace Yarn with `loom.officialMojangMappings()` temporarily.
- [x] Compile common and client source sets on 1.21.1 official mappings.
- [x] Repair unmigrated mixin targets, descriptors, and shadows.
- [x] Run the intermediate 1.21.1 official-mappings build.

## Build Upgrade to 26.2

- [x] Upgrade the Gradle wrapper to 9.5.1.
- [x] Switch to `net.fabricmc.fabric-loom` 1.17.
- [x] Set Minecraft 26.2, Loader 0.19.3, and Fabric API 0.157.0+26.2.
- [x] Remove the obsolete mappings property and dependency.
- [x] Raise Java source, target, and release compatibility to 25.
- [x] Update the mod version, Minecraft dependency, and description metadata.

## Source and API Port

- [x] Port common initialization, sounds, and statistics.
- [x] Port custom payload registration and server/client networking.
- [x] Port entity, living-entity, player, and server movement mixins.
- [x] Port block queries and climbing calculations.
- [x] Port client input, movement controllers, and state handling.
- [x] Port HUD rendering.
- [x] Port player model, renderer, cape, camera, and model-part mixins.
- [x] Audit every mixin target, descriptor, callback, and shadow against 26.2.

## Verification and Handoff

- [x] Compile common sources.
- [x] Compile client sources.
- [x] Run the full Gradle build.
- [x] Inspect the produced jar and expanded `fabric.mod.json`.
- [x] Run client and dedicated-server smoke tests where practical.
- [x] Review the final diff for generated files or unrelated changes.
- [x] Update this checklist and research notes with final results.
- [x] Commit the completed port using the repository convention.
