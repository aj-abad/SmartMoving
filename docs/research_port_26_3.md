# Research: Fabric 26.3 Port

## Goal

Move SmartMoving from Minecraft 26.2 to Minecraft 26.3 (released 2026-09-15) on
Fabric, keeping every movement, animation, networking and server behavior the
same.

## Toolchain

| | 26.2 (before) | 26.3 (after) |
| --- | --- | --- |
| Minecraft | `26.2` | `26.3` |
| Fabric Loader | `0.19.3` | `0.19.5` |
| Fabric API | `0.157.0+26.2` | `0.161.0+26.3` |
| Loom | `1.17-SNAPSHOT` | unchanged |
| Gradle wrapper | `9.5.1` | `9.6.0` (Fabric's minimum for 26.3) |
| Java | release 25 | unchanged |

26.3 is still unobfuscated, so there is still no mappings dependency.

## Method

Compiling alone cannot prove a mixin port: injector selectors, `@At` targets,
`@Shadow` and `@Accessor` names are strings, and `require = 0` injectors fail
silently at runtime. The port was scoped before editing anything:

1. Every class in the 26.2 build (`smartmoving-1.0.1+26.2.jar`) was read with
   ASM and every method, field and type reference to Minecraft, its libraries,
   Fabric Loader and Fabric API was resolved against the 26.3 jars, walking the
   class hierarchy (JDK classes by reflection).
2. Every mixin was checked the same way: target classes, `@Shadow` fields and
   methods, `@Accessor` names, and each injector's `method` selector (with
   Mixin's "first match wins" rule for bare names). For each `@At`, the number
   of matching instructions in the target method was counted in 26.2 and in
   26.3. Target method bodies were compared instruction by instruction to find
   the ones that changed.
3. The same check was run 26.2 against 26.2 as a control. It reported only the
   two pre-existing items listed at the end, so every other finding is a real
   26.2 → 26.3 difference.
4. Each changed class was then diffed in the decompiled vanilla sources
   (`vanilla-decompiled/26.2` vs `26.3`) to judge semantic changes that no
   reference check can see.

## Findings and Answers

### 1. Keyboard input moved from GLFW to SDL

`InputConstants.Type.KEYSYM` became `KEYBOARD`, and key values are now SDL
scancodes (`InputConstants.KEY_R` is 21, where GLFW's `R` was 82).
`SmartMovingKeys` passed raw `GLFW.GLFW_KEY_*` values. Those are compile-time
constants, so the old values would have compiled into the new build and bound
the wrong keys.

Answer: use `InputConstants.Type.KEYBOARD` and the `InputConstants.KEY_*`
constants (`KEY_R`, `KEY_C`, `KEY_F9`, `KEY_O`, `KEY_I`). The unbound dodge key
keeps `InputConstants.UNKNOWN.getValue()` (now 0).

### 2. Swing animation became `LivingEntity.SwingState`

The public `swinging`, `swingingArm`, `swingTime`, `attackAnim` and
`oAttackAnim` fields are gone. The swing lives in a private `SwingState`, read
through `getSwingAnimation(partialTicks)`, `isSwinging()` and
`getCurrentSwing()`. On the render side, `ArmedEntityRenderState.attackTime`,
`attackArm` and `swingAnimationType` became `swingAnimation` and
`currentSwing`.

| 26.2 | 26.3 | Why it is the same value |
| --- | --- | --- |
| `renderState.attackTime` | `renderState.swingAnimation` | Both are filled from the entity's partial-tick swing progress (`getAttackAnim(pt)` then, `getSwingAnimation(pt)` now) |
| `player.attackAnim` | `player.getSwingAnimation(1.0F)` | `attackAnim` was the tick value; at `partialTicks = 1` the new getter returns the new tick value. Both stay 0 on a swing's first tick and are > 0 from the second |

Uses: `MixinPlayerEntityModelClient` (the arm-swing override and the
per-mode "preserve the swinging arm" guards), `MixinClientPlayerEntity` and
`MixinPlayerEntityClient` (the body-yaw replica of vanilla's
`if (attackAnim > 0) yBodyRotT = getYRot()`, which 26.3 writes against
`swingState.animation`).

The one theoretical difference: when a swing restarts with an identical
description during its second half, `getSwingAnimation(1.0F)` reports ≥ 1 for
one tick where the raw field would be 0. Vanilla's own renderer sees the same
continuing arc, so the "preserve the arm" guards stay consistent with what
vanilla draws.

### 3. Client interpolation was rebuilt

26.2's concrete `InterpolationHandler` class (`position()`, `yRot()`, `xRot()`,
`interpolateTo(Vec3, float, float)`, `setInterpolationLength(int)`) and
`LivingEntity.interpolation` are gone. 26.3 has an `InterpolationHandler`
interface created per entity by `Entity.createInterpolationHandler()` and held
in `Entity.interpolationHandler`. Living entities use `SteppedInterpolationHandler`,
whose step length is the entity type's update interval (2 ticks for players).
`Entity.commonTick()` now runs `interpolate()` on the client before `tick()`.
26.2 ran it inside `LivingEntity.aiStep()`.

`MixinLivingEntityAccessor` exposed the old handler to the remote-player
Y corrections in `SmartMovingClient` (packet lambdas that `setPos` a remote
player, then "cancel the lerp"). New implementation, through public API only:

| Accessor | 26.2 | 26.3 |
| --- | --- | --- |
| `sm_getServerX/Y/Z` | `interpolation.position()` (target if active, else current) | `getClientPosition()`, which is the same rule |
| `sm_setServerY(y)` | `interpolateTo(target with y, yRot(), xRot())`: restart from the current position unless already heading there | Same rule: return if already heading to that target, else `cancel()` and `interpolateTo(PositionPath.of(...), yRot, xRot, true)`. The stepped handler queues steps, so the explicit cancel is what reproduces 26.2's restart |
| `sm_getBodyTrackingIncrements` | `hasActiveInterpolation() ? 3 : 0` | unchanged |
| `sm_setBodyTrackingIncrements(0)` | `cancel()` | `cancel()` |
| `sm_setBodyTrackingIncrements(n > 0)` | `setInterpolationLength(n)`, only ever called with the default 3, a no-op | no-op: the stepped handler takes its length from the entity type |

Timing: hooks at `Player.tick` TAIL and `aiStep` TAIL see the same state as
before. The two `aiStep` HEAD hooks (`sm_jumpingFilter`, the local player's
`sm_tickMovement`) only touch the local player, which does not interpolate.

### 4. `PoseStack.mulPose(Quaternionfc)` became `rotate(Quaternionfc)`

A rename (26.3 also adds `rotate(Axis, float)` and `rotateDegrees`). Used by
`MixinModelPart`'s rotation-order override and by `MixinPlayerEntityRenderer`.

### 5. The server's movement check moved to `handlePlayerPositionChange`

26.3 split `ServerGamePacketListenerImpl.handleMovePlayer` into packet
validation plus a new private `handlePlayerPositionChange(double, double,
double, float, float, boolean, boolean)`. The body is 26.2's inner block
almost line for line: the same two stores to `movedDist` (the "moved too
quickly" and "moved wrongly" distances, now local slot 33 instead of 25), and
the same three `teleport(DDDFF)V` rubber-band calls (sleeping, too quickly,
collision fallback). 26.2's fourth call site, the awaiting-teleport resend,
was already in `updateAwaitingTeleport()` and was never intercepted.

All three SmartMoving hooks there are `require = 0`, so on 26.3 they would
have silently matched nothing and SM moves would be rubber-banded on servers.

Answer: retarget `sm_suppressDistanceSq`, `sm_suppressPostDistSq` and
`sm_cancelTeleportForSmMove` to `handlePlayerPositionChange`. The two
`@ModifyVariable`s select the variable by name (`movedDist`, which the
unobfuscated jar's local variable table carries) instead of by slot, with the
same `STORE` ordinals 0 and 1. The `handleMovePlayer` HEAD hook that caches
the SM phase flag still runs before the new method is called.

New in 26.3 and not relevant here: `handleMovePlayer` disconnects a client
that sends two position packets in one tick. SmartMoving sends no movement
packets of its own (only `ServerboundPlayerAbilitiesPacket`).

### 6. Semantic changes reviewed and needing no change

| Change | Why SmartMoving is unaffected |
| --- | --- |
| `LocalPlayer.tick()` no longer sends input/position; `Minecraft.tick()` calls `LocalPlayer.sendChanges()` after all entities tick | SM's state packet is still sent from `aiStep` TAIL, before the position packet of the same tick; SM registers no client tick events |
| `Entity.move()` throws in Mojang's IDE builds when an entity that cannot simulate moves itself with `MoverType.SELF` | Nothing sets `SharedConstants.IS_RUNNING_IN_IDE`: vanilla only declares it, Loader never mentions it, and Fabric API only reads it (debug-subscriber permissions, command registration). The remote-player `move(SELF, …)` corrections keep working, and `recordMovement` does not feed `SELF` moves of players into interpolation |
| `ServerPlayerGameMode.useItemOn` has 8 returns instead of 7 | `sm_afterInteractBlock` injects at every `RETURN` |
| `LivingEntity.baseTick` no longer copies `yRotO`/`xRotO` | `setOldPosAndRot()` in `commonTick()` still does, before `tick()` |
| `HumanoidModel.setupAnim` moved the swim head tilt into `setupSwimAnimation` and rewrote arm-pose priority | Both still run before SM's `setupAnim` TAIL hook, with the same results |
| `HumanoidModel.setupAttackAnimation` reads `swingAnimation`/`currentSwing` | SM's HEAD override reads `swingAnimation`; it always used the main arm, as the 1.7.10 original did |
| `CapeLayer.submit`, `ModelPart.translateAndRotate`, `AvatarRenderer.setupRotations`/`renderHand` bytecode changed | `submitModel` lost a trailing argument, `mulPose` became `rotate`, lambda renumbering; no injection point moved |
| `Camera.extractRenderState(CameraRenderState, float)` now takes a `DeltaTracker` | SM only uses the `eyeHeight`/`eyeHeightOld` accessors, which are unchanged |
| `ClientPacketListener.handleMovePlayer` acknowledges teleports with the position in `ServerboundAcceptTeleportationPacket` | SM's HEAD hook only sets `multiPlayerInitialized` |
| `LevelReader.containsAnyLiquid` now scans with an inclusive upper bound | This matches 1.7.10's `World.isAnyLiquid`, which `SmartMovingSwimmer`'s lava wall-jump check replicates |
| `GuiGraphicsExtractor.blit(Identifier, IIIIFFFF)` | Same signature and parameter order; its body only references renamed render classes |
| `ServerGamePacketListenerImpl.tick` | Only adds command-suggestion ticking; `aboveGroundTickCount` handling is unchanged |

## Pre-existing, Unchanged

- `MixinPlayerEntity.sm_getBaseDimensions_server` and
  `MixinPlayerEntityClient.sm_getBaseDimensions_client` inject into
  `Player.getDefaultDimensions`, which `Player` does not declare in 26.2 or
  26.3 (`Avatar` does). Both are `require = 0`, so they have never applied;
  `MixinAvatarDimensions`/`MixinAvatarDimensionsClient` do that work. Left as-is.

## Port Results

- `gradlew build` succeeds with Loom 1.17.21, Gradle 9.6.0, Minecraft 26.3,
  Loader 0.19.5 and Fabric API 0.161.0+26.3. The only deprecation warnings are
  the two `Entity.hurt(DamageSource, float)` calls, which were already
  deprecated in 26.2.
- The reference and mixin check, re-run on the 26.3 jar against 26.3, reports
  nothing except the two pre-existing dead injectors: every reference resolves,
  every selector matches, every `@At` has matches.
- Smoke test on a dedicated 26.3 server (offline mode, isolated run dirs under
  `build/smoke`, the 26.2 worlds in `run/` untouched) with two dev clients:
  - Both clients joined; the server logged SmartMoving's config handshake
    ("Smart Moving allows client configurations"). No errors or exceptions in
    the server or client logs.
  - `-Dmixin.debug.export=true` showed all three server move-check injectors
    applied in `handlePlayerPositionChange`: both `movedDist` stores routed
    through `sm_suppressDistanceSq`/`sm_suppressPostDistSq`, and all three
    `teleport` calls redirected.
  - The client saved the SM keys as `key.keyboard.r`, `c`, `f9`, `o`, `i` and
    `unknown` (dodge), so the SDL codes are right. The old GLFW numbers would
    have landed on other keys (82 is SDL's Up arrow, 67 is F10).
  - Each client rendered the other player (name tag, standing model), a remote
    player falling with SmartMoving's arms-out falling pose, and a submerged
    remote player in SmartMoving's horizontal diving pose.
- Not exercised: transitions that need keyboard input (crawl, slide, climb,
  head jump, flying). That includes the remote-player Y corrections in
  `SmartMovingClient` that call the rebuilt interpolation accessor, so those
  are covered by the reasoning in section 3 only and deserve an in-game check.

Observations that are the same on 26.2 and outside this port:

- An idle player loses about one food point every four seconds.
  `handleExhaustion` adds `exhaustionLossHungerFactor × exhaustionLoss` every
  tick (0.02 × 1.2 × 2.0 while standing), even when SmartMoving's own fatigue is
  already zero. The original mod has the same coupling (default 0.05, easy 0.02,
  hard 0.08). `FoodData` and `causeFoodExhaustion` are identical in 26.2 and 26.3.
- A standing player placed in a one-block gap is not forced to crawl:
  `mustCrawl` is only evaluated while already crawling.
- `Requested post effect does not exist: minecraft:end_of_frame` on join comes
  from vanilla 26.3 (`GameRenderer.END_OF_FRAME_POST_EFFECT` has no asset).

## Primary Sources

- Decompiled vanilla: `X:\Repos\mc_mods\vanilla-decompiled\26.2` and `26.3`
- Firearms 26.3 port notes: `X:\Repos\mc_mods\Firearms\PORTING.md`,
  "Moving to 26.3"
- Previous port: `docs/research_port_26_2_official_mappings.md`
