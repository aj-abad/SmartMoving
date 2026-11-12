# Research: Movement Prototypes (Chain Climb, Landing Roll, Sprint Vault)

Three new movement options prototyped together. None of them exist in the original
SmartMoving, so there is no 1:1 port target; the design below is derived from the
infrastructure the port already has. Everything uses vanilla blocks and no new key bindings.

## Requests

1. **Chain climb** — vertical chains (iron and copper) become climbable: hand-over-hand climbing,
   sneak to slide down fast, catch a chain while falling. Horizontal chains overhead count as
   ceiling-climb handholds.
2. **Landing roll** — press the dodge key while airborne to arm a roll; on landing the player
   tumbles in the pressed direction, keeps momentum, and takes reduced fall damage.
3. **Sprint vault** — sprinting into a 1-block (or fence / wall, 1.5-block) obstacle hops over it
   with a hand-plant pose instead of vanilla's hop, as long as there is clearance.

## Existing infrastructure (verified in code)

### State sync packet (`SmartMovingState`)
- 64-bit long bitmap, bits 0-36 used. Free bits start at 37.
- Encoded in `SmartMovingClientState.sendStatePacket` (sent every tick), decoded for remote
  players in `SmartMovingClientState.processStatePacket` and on the server in
  `SmartMovingServer.processStatePacket`.
- Remote receive lambda in `SmartMovingClient.registerClientReceivers` snapshots "was" flags
  before decode and tracks dodge start/end ticks via `trackRemoteAngleJump`. New timed
  animations (roll, vault) need the same edge tracking there.
- Bit 32 `isRopeSliding` is encoded, decoded and animated (`sm_animateRopeSliding`) but never
  set: the original's rope blocks (BetterThanWolves / RopesPlus / ASRope) were never ported.
  `Orientation.isRope` / `isOnWallRope` return false.

### Dodge input (`SmartMovingJumper.tryKeyboundDodge`, L581)
- Fires on `sm.dodgeJustPressed` (rising edge, computed in `tickEssential` L1476).
- Direction = WASD at press time → `relAngle` in 45° steps → `tryAngleJump`.
- Guard at L585 returns false while `!player.onGround()` without touching the cooldown or
  remembering the press. An airborne press is therefore an unused input.
- `dodgeCooldown` counts down only on the ground and not while angle jumping (ClientState
  L2731).
- Dodge animation is tick-driven (`angleJumpStartTick` / `angleJumpEndTick`,
  `sampleDodgePose`, `sm_animateDodge`); the renderer leans the body about the hips at
  `pivotY = 1.5 - 12/16` in pre-scale space where `XP.rotation(-θ)` pitches forward.

### Fall damage
- Client-side `handleCrash` (Climber L1138, ClientState L2487) calls `player.hurt`, which only
  matters on the server in modern Minecraft. Real fall damage comes from the server:
  `ServerGamePacketListenerImpl.handleMovePlayer` → `checkFallDamage` →
  `Player.causeFallDamage(double fallDistance, float damageMultiplier, DamageSource)` →
  `LivingEntity.calculateFallDamage`. Confirmed by `javap` on the 26.3 deobf jar.
- `fallDistance` is a `double` in 26.3.
- Server resets fall distance while `isClimbing || isCrawlClimbing || isCeilingClimbing ||
  isWallJumping` (`SmartMovingServer.resetFallDistance`, applied in
  `MixinServerPlayNetworkHandler.sm_tick`).

### Travel flow (`MixinLivingEntityClient.sm_travel_client`)
Order each tick: spectator/elytra return → `cfg.enabled` guard → weeping/twisting vine bypass →
`handleJumping` → stuck-box fix → swim state + `handleSwimming` (cancel) → `handleLava`
(cancel) → `handleSliding` (cancel) → head-jump reset → wall jump → `handleFlying` (cancel)
→ `[5-1]` ladder/vine detection → `handleLand` (cancel) or `handleClimbing` +
`handleCeilingClimbing` → climb motion (cancel).
- New handlers that own the motion must `ci.cancel()` after `player.move`.
- `onClimbable` / `handleOnClimbable` client mixins already return false / pass-through while
  `sm.isClimbing`, and `MixinLivingEntity.sm_isClimbing_server` does the same server-side.

### Climbing state and animation
- `handleClimbing` calls `sm.resetClimbing()` at entry, which clears `isClimbing`,
  `isHandsVineClimbing`, `actualHandsClimbType`, etc. A handler that runs *before* it and
  cancels travel keeps its own flags for the tick.
- Model dispatch (`MixinPlayerEntityModelClient` ~L516): `isRopeSliding` → ceiling →
  `isClimbing || isCrawlClimbing` (`sm_animateClimbing`) → weeping vines → climbJumping →
  swimming → diving → crawling → sliding → flying → headJumping → standard (+ dodge overlay).
- `sm_animateClimbing` reads `actualHandsClimbType` as an animation code (0 NoGrab, 1 UpGrab,
  2 MiddleGrab) and `actualFeetClimbType` (0 NoStep, 1 DownStep); `isHandsVineClimbing`
  widens the arm yaw and scales the arms. The weeping-vine branch temporarily forces 1/1 to get
  the ladder pose — the same trick works for a chain.
- Renderer forces body yaw to the movement direction while `isClimbing` (`sm_captureBodyYaw`,
  `smActive` list L179). A chain climber barely moves horizontally, so it needs its own yaw
  source (direction from the player to the chain).
- `CeilingClimbBlocks.supports` is a plain block-state predicate: iron bars, fences/walls,
  closed trapdoors.

### Hitbox / pose machinery (deliberately avoided)
- Small SM states use 0.6×0.8 dimensions, sometimes with a +1 m bounding-box offset
  (`MixinEntity.sm_offsetBoundingBoxForFlying`) keyed on eye height and `Pose.SLIDING`, and the
  server mirrors dimension changes bit-by-bit with many documented reconcile fixes.
- The prototypes keep the vanilla 1.8-tall hitbox and `Pose.STANDING`. Shrinking the box for a
  roll under a low gap or a vault under a low ceiling is a follow-up.

### Vanilla names (26.3, from `javap`)
- `Blocks.CHAIN` no longer exists: `Blocks.IRON_CHAIN` plus `Blocks.COPPER_CHAIN`
  (`WeatheringCopperCollection`). Detect with `state.getBlock() instanceof ChainBlock`;
  axis is `RotatedPillarBlock.AXIS` (`Direction.Axis`).
- Chain collision shape is the centre 3/16 of the block, so a 0.6-wide player can almost never
  have the chain in their own "feet" block — adjacency detection is required, not the
  original's base-block detection.
- `Entity.horizontalCollision`, `Entity.verticalCollision`, `Entity.onGround()`,
  `Level.noCollision(Entity, AABB)`, `Level.getBlockCollisions(Entity, AABB)`.
- `Attributes.SAFE_FALL_DISTANCE`, `Attributes.FALL_DAMAGE_MULTIPLIER`.

### Config (`SmartMovingConfig`)
- Plain fields with `getFloat` / `getBool` in `readFrom` and `setProperty` in `writeTo`;
  server config sync serialises the same key/value pairs (`toArray` / `loadFromArray`), so new
  keys need no extra sync code.

### Build
- `build.gradle` targets Java 25 via toolchain. Shell Java is 17; a JDK 26 is installed. A
  baseline `compileJava` is run first to confirm Gradle can resolve a JDK 25.

## Design

### Shared
New packet bits:

| bit | field | read by |
|-----|-------|---------|
| 37 | `isRolling` | remote clients (animation) |
| 38 | `rollArmed` | server (fall damage reduction) |
| 39 | `isVaulting` | remote clients (animation) |
| 40 | `isChainClimbing` | remote clients (body yaw / pose) |

Chain climbing also sets the existing `isClimbing` (bit 14, server fall reset + position check
suppression), `isHandsVineClimbing` (26), `actualHandsClimbType = 1`, `actualFeetClimbType = 1`
and `isRopeSliding` (32) while sliding down.

New config keys (all with defaults, all in `readFrom` / `writeTo`):

| key | default | meaning |
|-----|---------|---------|
| `move.roll` | true | enable landing roll |
| `move.roll.buffer.ticks` | 8 | airborne dodge press stays armed this long |
| `move.roll.fall.min` | 3.0 | minimum fall distance for a roll |
| `move.roll.fall.max` | 12.0 | above this the roll no longer reduces damage |
| `move.roll.fall.damage.factor` | 0.25 | multiplier on vanilla fall damage |
| `move.roll.speed.factor` | 0.45 | initial roll speed (blocks/tick) |
| `move.roll.duration.ticks` | 10 | roll length |
| `move.vault` | true | enable sprint vault |
| `move.vault.vertical.factor` | 1.0 | scales vault jump velocity |
| `move.vault.horizontal.factor` | 1.15 | horizontal boost on takeoff |
| `move.vault.max.height` | 1.55 | tallest obstacle that can be vaulted |
| `move.vault.reach` | 0.6 | how far ahead the obstacle is detected |
| `move.climb.chain` | true | enable chain climbing |
| `move.climb.chain.speed.factor` | 1.0 | climb speed multiplier |
| `move.climb.chain.slide.speed.factor` | 1.0 | sneak slide-down speed multiplier |
| `move.climb.chain.grab.distance` | 0.8 | max horizontal distance to a chain centre |

### Landing roll (`SmartMovingRoller`, client)
1. **Arm.** In `handleJumping`'s dodge block, when the press happens while airborne and no
   blocking state is active (climbing, swimming, diving, flying, elytra, head jump), store
   `rollArmedTicks = rollBufferTicks`, `rollYaw = yRot + relAngle` (forward if no WASD) and
   reset `rollPeakFall = 0`. The ground path is unchanged.
2. **Track.** Each tick in `tickEssential` while armed: `rollPeakFall = max(rollPeakFall,
   player.fallDistance)`, decrement the buffer when it is not refreshed. Landing (`onGround`)
   with `rollPeakFall >= rollMinFall` starts the roll; landing with a shorter fall falls
   through to the regular dodge on that tick (buffered dodge). Landing in water or on a
   climbable clears the buffer.
3. **Roll.** `isRolling = true`, `rollTicks = duration`, `rollStartTick = tickCount`, velocity =
   roll direction × `rollSpeedFactor` × combined speed factor. In `sm_travel_client` (before
   swimming) `handleRolling` applies per-tick decay (×0.85), gravity, `player.move`,
   `fallDistance = 0`, ignores movement input and cancels travel. When `rollTicks` hits 0:
   `isRolling = false`, `dodgeCooldown = angleJumpCooldownTicks`.
4. **Damage.** `rollArmed` is sent while airborne, so the server knows before the landing move
   packet. `@ModifyVariable` on `Player.causeFallDamage`'s `damageMultiplier` (server player
   only): if `sm.rollArmed && rollMin <= fallDistance <= rollMax`, multiply by
   `rollFallDamageFactor`. The flag is consumed there and cleared by the next state packet.
5. **Animation.** Model: tuck (legs and arms forward, head down) blended with `sin(π·p)`,
   p = elapsed / duration. Renderer: body yaw forced to `rollYaw`, full `2π·p` pitch rotation
   about the hips plus a downward translate during the tumble. Remote players get
   `rollStartTick` from the bit edge in the receive lambda and compute yaw from their motion.

### Sprint vault (`SmartMovingVaulter`, client)
Revised after the first play-test request: the vault must be deliberate (sprint + Grab) and must
actually carry the player over the obstacle. A ballistic hop started within 0.6 blocks of the
face reaches only ~0.4-0.75 blocks of height before the box hits the face, so the player scraped
up the obstacle and lost momentum. The vault is now a short scripted rise followed by a release.

1. **Trigger** (in `sm_travel_client` before `handleJumping`, so jump input cannot interfere):
   `cfg.vault`, on ground, `player.isSprinting()`, **Grab held**, not sneaking, forward input,
   none of crawl/slide/head-jump/climb/chain/ceiling/swim/dive/fly/roll, no jump charge.
2. **Probe.** Direction = horizontal velocity if speed > 0.05 else look yaw. Probe box = player
   box moved `reach` along the direction, Y between `minY + max(0.6, vaultMinHeight)` and
   `minY + vaultMaxHeight`. Obstacle top = highest collision top intersecting the probe; the
   distance to the obstacle = smallest slab-test entry distance of the player box along the
   direction against each collision box (0 when already touching).
3. **Clearance.** `noCollision` for the player box moved `distance + 0.5` and raised to
   `top + 0.1` with 0.4 extra headroom for the release arc, and again moved `distance + 1.3`
   (landing spot) so the player never vaults into a wall.
4. **Rise.** `riseTicks = clamp(ceil(max(distance / speed, lift / 0.6)), 2, 6)` where
   `speed = max(current, 0.28)` and `lift = h + 0.1`. Each rise tick sets velocity
   `(dir × speed, lift / riseTicks)` and calls `player.move`, with `fallDistance = 0` and vanilla
   travel cancelled (no gravity). The first rise step runs on the trigger tick. A vertical
   collision during the rise (ceiling) ends the vault immediately.
5. **Release.** On the last rise tick the velocity becomes `(dir × speed × vaultHorizontalFactor,
   0.25 × vaultVerticalFactor)`: forward with a little upward momentum, then normal air physics.
   `vaultVerticalFactor` therefore scales the release hop, not a takeoff jump.
6. **End.** `onGround` at least 2 ticks after the rise, 40 ticks elapsed, water, flight or a
   climbing state.
7. **Animation.** Unchanged: hand plant pose blended in over the first 3 ticks, forward lean
   about the hips, remote players track `vaultStartTick` from the packet bit edge.

### Chain climb (ladder detection hook + `SmartMovingChainClimber` helpers, client)
Revised: the first prototype was a separate controller that pulled the player to a hang point and
only moved up and down. The user wants chains to behave exactly like ladders with a different
pose, so the controller is gone and chains now enter the ladder path at the detection level.

1. **Detection.** `SmartMovingClimber.getOnLadderOrVine` only ever looked at the player's own
   column (a ladder's block is the one the player stands in). Chains have a centre collision box,
   so the player is almost never inside the chain block. A second pass now treats a vertical
   `ChainBlock` in the own column, or in one of the four neighbouring columns within
   `chainGrabDistance` of the player centre, as a ladder hold: `HandsClimbing.UP` at hands level,
   `FeetClimbing.SLOW_UP_WITH_HOLD_WITHOUT_HANDS` at feet level, `gap.state` = the chain state,
   `gap.direction` = the direction toward it. With `faceOnly` only the faced neighbour counts, so
   `isFacedToLadder` (auto entry when `freeClimbAutoLadder`) works for chains unchanged.
2. **Count.** `getOnLadderOrVineCount` counts a vertical chain in the own column or a neighbouring
   column within reach as one ladder, so the one-ladder climb speed factor applies.
3. **Mechanics.** Nothing chain-specific: `handleClimbing` runs as for a ladder (forward = up,
   back = down, sneak = hold, sideways movement and stepping off as on a ladder, `isClimbing`
   bit 14 for the server fall reset). Grab-free-climb near a chain behaves as before.
4. **Pose flag.** After `handleClimbing` / `handleCeilingClimbing` in `sm_travel_client`:
   `isChainClimbing = isClimbing && !isCeilingClimbing && (hands or feet gap state is a vertical
   chain)`; `chainYaw` = yaw toward the nearest chain. Cleared right after detection every tick
   and in `resetClimbing`, so it never outlives the climb.
5. **Rendering.** Model: `sm_animateChainClimbing` (arms overhead gripping hand over hand, knees
   bent and turned in, driven by climbed distance like the ladder pose) replaces the ladder
   pose when `isChainClimbing && isClimbing`. Renderer: body yaw toward the chain (`chainYaw`
   for the local player, recomputed from `findChain` for remote players). Remote players receive
   bit 40 plus the usual climbing bits.
6. **Ceiling.** `CeilingClimbBlocks.supports` returns true for a `ChainBlock` whose axis is
   not Y.
7. **Config.** `move.climb.chain` and `move.climb.chain.grab.distance` only; the speed and slide
   keys from the first prototype are removed (ladder speeds apply).

### Out of scope for the prototype
- Hitbox shrinking during roll / vault (see above).
- Exhaustion and hunger costs for the three moves.
- Sounds and particles.
- Chain-top mantle onto an adjacent ledge (free climbing still works from a chain by holding
  grab).

> Removed in `docs/research_fall_rework.md`: the Landing Roll is no longer part of SmartMoving.
