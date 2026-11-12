# Research: Windrunner traversal armor set

## Goal

Move the Windrunner armor set out of the sibling Aircraft mod (`D:\Repos\mc_mods\aircraft`, commits
`d43d116`..`8a344e4`, version 0.41.0) and make it a first-party SmartMoving feature. Aircraft then
returns to its firework-powered wingsuit (commit `6005762`).

## What the set does (as of Aircraft 0.41.0)

| Piece | Ability |
|---|---|
| Windrunner Headband | Dash key: a ground dash (low hop, 24 blocks/s) or, once per airtime, an air dash |
| Windrunner Robes | Glide (the wingsuit flight model); each fresh dash press while gliding is a boost; sneak slow fall |
| Windrunner Pants | +15% movement speed |
| Windrunner Boots | +10% jump strength, 30 blocks/s fall cap, +3 safe fall blocks, double jump |

Rules carried over unchanged:

- Leather-grade armor (1/3/2/1), enchantable, repaired with breeze rods (robes also with leather);
  robes keep 240 durability. Recipes upgrade leather armor with breeze rods (robes also take four
  phantom membranes). Recipes unlock on picking up a breeze rod. Creative: Combat tab after leather boots.
- Glide key (Mouse 4) opens and folds the robes with three blocks of clearance; sneak also folds them.
  The glide is server-authoritative with client prediction (input/state payloads, epochs, replay).
- Boost: one-off 0.4 blocks/tick kick along the nose on a fresh dash press, 15-tick cooldown, one point
  of robe wear. Clicks shorter than a tick still count.
- Ground dash: hop 0.2, launched airborne so travel's pre-move ground friction does not eat it; the hop
  counts as ground (no air dash; a jump out of it is a free ground jump keeping the double jump) until it
  leaves its starting height by more than 0.6 blocks, after which it is an ordinary fall.
- Slow fall: keeps 62% of sink per tick but sheds at most 0.12 per tick; server resets fall distance
  only while observed sink stays under 0.35 blocks/tick.
- Effects: dash FOV dart (2-tick rise, 10 ticks), following gust and sprint-style block dust for six
  ticks, walked distance at 30% while a ground dash slides (slower view and hand bob), boost gusts.
- Look: Breeze-palette monk robe generated from the mage-robe baseline in `art/windrunner`, knotted sash
  and headband tails as 3D models, phantom-membrane glide leaves on breeze-rod ribs.

## Aircraft dependencies to replace

| Aircraft code | SmartMoving replacement |
|---|---|
| `AircraftWeather.Sampler` (wind, storms, lava thermal, End vacuum) | `WindrunnerAir`: the same field, thermal and vacuum rules, without icing exposure |
| `WindCompatibility` (optional Wind mod) | Folded into `WindrunnerAir` |
| `BiplaneFlightModel.nose/up/density` | `WindrunnerGlidePhysics` static helpers |
| `ModItems`, `ModSounds`, creative tab, payload registration in `Aircraft` | `WindrunnerItems`, `SmartMovingSounds`, `Windrunner.init()` |
| `CameraMixin` flight branches (look clamp, position smoothing, roll, FOV) | `windrunner.MixinWindrunnerCamera` |
| `SmartMovingCompat` reflection and `SmartMovingJumperMixin` @Pseudo hooks | Direct calls inside `SmartMovingJumper` and `SmartMovingClientState` fields |
| Separate Windrunner Dash key (R) | SmartMoving's own Dodge binding |

## SmartMoving hook points (first-party)

- `SmartMovingJumper.tryKeyboundDodge`: refuse when the headband is worn (the headband's ground dash
  fires from the same press in `WindrunnerControls`); this also covers the landing-roll replay.
- `SmartMovingJumper.handleJumping`: the charge step is multiplied by the boots' charge rate (2x).
- `SmartMovingJumper.tryJump`: the final `setDeltaMovement` scales a positive vertical kick by the
  player's jump-strength ratio, so the boots raise SmartMoving's jumps like vanilla's.
- `SmartMovingClientState` fields gate the double jump (climbing, swimming, flying, wall contact) and the
  ground dash (crawling, sliding, climbing, rolling, vaulting).
- `LivingEntity.travel` is replaced by SmartMoving for the local player; the glide cancels `Player.travel`
  at its head first, so the glide's single integration is unchanged.

## Tests

Aircraft verifies the glide with a plain-Java simulation (`flightPhysicsTest`) and Fabric client game
tests. Both move with the set, minus the aircraft-only scenarios (helicopter bailout).

## Follow-up: slow fall moves to the robes

Aircraft's firework wingsuit is also chest armor and opens its parachute on a Sneak press in free air.
With the slow fall on the boots, a player in the wingsuit and Windrunner Boots got both from one press.
Moving the slow fall to the Windrunner Robes puts both behaviours in the chest slot, so only one can
ever be worn.

- `WindrunnerMoves.canSlowFall` checks the robes (`WindrunnerArmor.robes`) instead of the boots. It
  checks the item alone, not `WindrunnerGlide.equipped`, because the slow fall costs no durability and
  should work on robes too worn to open.
- The glide already blocks moves while it is open (`able()`), so Sneak still folds an open glide first;
  holding Sneak on after the fold brakes into the slow fall.
- The boots keep the double jump, jump strength, fall damage reduction and the faster charged jump.

## Follow-up: the boots cap falling speed

Fall damage counts blocks fallen, not landing speed, so a speed cap alone saves no health. The boots
trade the 10% fall damage cut for a 30 blocks/s fall cap and three more blocks of safe fall.

- Vanilla and SmartMoving both apply 0.08 gravity and 0.98 drag, settling near 78 blocks/s. A
  1.5 blocks/tick cap is reached after about 24 ticks and 22 blocks of falling.
- SmartMoving has several gravity paths (land, climbing, rolling, sliding), so the cap clamps once at
  `Player.travel` RETURN, after vanilla or SmartMoving finished integrating. Anything that cancels
  `Player.travel` at HEAD (the robes' glide, Aircraft's wingsuit and parachute) never reaches it.
- Skipped during elytra flight, riptide, creative flight and in fluids; client side only, since the
  local player's movement is client-authoritative and the server accepts a slower fall.
- `Attributes.SAFE_FALL_DISTANCE` +3 (ADD_VALUE) replaces `FALL_DAMAGE_MULTIPLIER` -0.1, taking every
  landing's damage down by three hearts' worth of blocks.
