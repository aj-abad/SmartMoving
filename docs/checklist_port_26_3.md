# Checklist: Fabric 26.3 Port

## Research

- [x] Read repository rules and the 26.2 port notes.
- [x] Confirm the 26.3 toolchain (Loader, Fabric API, Gradle, Loom, Java).
- [x] Resolve every reference in the 26.2 build against the 26.3 jars.
- [x] Check every mixin target, shadow, accessor, selector and `@At` count.
- [x] Run the same check 26.2 against 26.2 as a control.
- [x] Diff every changed vanilla class that SmartMoving hooks or calls.
- [x] Record findings in `research_port_26_3.md`.

## Build

- [x] Set Minecraft 26.3, Loader 0.19.5, Fabric API 0.161.0+26.3.
- [x] Set the mod version to `1.0.1+26.3`.
- [x] Upgrade the Gradle wrapper to 9.6.0.
- [x] Update the `fabric.mod.json` description.

## Source

- [x] Keybindings: `InputConstants.Type.KEYBOARD` and `InputConstants.KEY_*` instead of GLFW codes.
- [x] Swing: `renderState.attackTime` → `renderState.swingAnimation`.
- [x] Swing: `player.attackAnim` → `player.getSwingAnimation(1.0F)`.
- [x] Interpolation: rebuild `MixinLivingEntityAccessor` on `getInterpolation()` / `getClientPosition()`.
- [x] `PoseStack.mulPose(Quaternionfc)` → `rotate(Quaternionfc)`.
- [x] Server move check: retarget the two `@ModifyVariable`s and the teleport `@Redirect` to `handlePlayerPositionChange`, selecting `movedDist` by name.

## Verification

- [x] Full Gradle build.
- [x] Re-run the reference and mixin check against the 26.3 build.
- [x] Confirm the `handlePlayerPositionChange` injectors applied.
- [x] Dev client smoke test (two clients on the dedicated server).
- [x] Dedicated server smoke test.
- [x] Update README and record results.
- [x] Commit to `master`.
- [ ] In-game check of input-driven transitions (crawl, slide, climb, head jump, flying exit), including how remote players look during them.
