# Research: Windrunner Boots wall run

Goal: with the Windrunner Boots, holding Grab while sprinting into a wall runs along it (glancing hit)
or up it (head-on hit). Step one is keeping the sprint alive through the collision.

## Vanilla sprint stop

- `LocalPlayer.aiStep` ends a sprint when the private `shouldStopRunSprinting()` returns true:
  `!isSprintingPossible(flying) || !input.hasForwardImpulse() || (horizontalCollision && !minorHorizontalCollision)`.
- `minorHorizontalCollision` is a hit within `MINOR_COLLISION_ANGLE_THRESHOLD_RADIAN` (about 8 degrees)
  of parallel, so vanilla already keeps a sprint through near-parallel scrapes, but not steeper ones.
- The check is client-only; the server takes the sprint flag from the client's player command.
- Override: inject at `shouldStopRunSprinting` RETURN and return false only when the collision is the
  sole reason (sprinting still possible, forward still held) and the boots plus Grab are held.
- SmartMoving sets sprinting itself only on sprint-key edges (`SmartMovingClientState` ~L2084), not on
  collisions, so it does not undo the override.
- Confirmed in a temporary client game test (sprinting into a six-high stone wall, 30 ticks against
  it): without the boots the sprint ends; with the boots and Grab it stays on, on the client and the
  server; releasing Grab ends it within three ticks. SmartMoving neither climbs nor vaults on a flat
  wall that tall, so nothing else claims the input.

## Grab + sprint is already used

| Mechanic | Trigger | Wall run must |
|---|---|---|
| Sprint Vault | Grab + sprint, on the ground, at a 1-block ledge with clear space above and beyond | yield: a vaultable obstacle vaults |
| Head jump (dive) | Grab + sprint + Jump | yield to Jump on the ground; a jump off the wall is open for design |
| Slide | Grab + Sneak + sprint | not apply while sneaking |
| Climbing / ledge grab | Grab near holds or a wall top in reach | hand over at the top of a vertical run |

## Design (agreed)

- Trigger: boots, Grab held, sprinting with forward held, not sneaking, hitting a wall at least two
  blocks tall. The angle is the move direction against the wall's face:
  - 70 degrees or steeper (near head-on): run up. Allowed from the ground or the air.
  - 40 degrees or shallower: run along. Airborne only (on the ground it is ordinary sprinting).
  - In between, including 45 plus or minus several degrees: nothing.
- Run up: starts at 0.4 blocks/tick and decelerates by 0.02 per tick, so it tops out about four
  blocks up after a second. Reaching the wall's top early pops the player up and onto it.
- Run along: at least 0.32 blocks/tick along the wall (more if already faster), sinking at a steady
  0.06 blocks/tick from the start, for up to 1.5 seconds.
- Ends: timer, releasing Grab or forward, sneaking, the wall ending, landing, a dash, or SmartMoving
  taking over (climbing, ledge grab, swimming, flying, vaulting, sliding, crawling, head jump).
- Jump during a run: kick away from the wall (0.4 out, 0.5 up, scaled by jump strength), keeping the
  along-wall momentum, and the pants' double jump comes back.
- Once per wall: a wall (its side and plane) cannot be run twice in a row before landing; chaining to
  a different wall is unlimited.
- Server: client-authoritative movement as for the dashes. Actions WALL_RUN_START/STOP keep fall
  distance reset while running; WALL_KICK, valid only during a run, refreshes the double jump charge.
- SmartMoving: its wall jump (`SmartMovingJumper.handleWallJumping`) stands down during a run, so the
  Jump press is the kick alone. The Grab + sprint + Jump head jump on the ground is unchanged, so an
  along-the-wall run is entered by pressing Grab after takeoff.

## Implementation notes

- SmartMoving's ledge climb claimed a run up as soon as the top came within reach and took about 1.4 s
  to haul the player over. Climb entry (`SmartMovingClientState.wouldWantClimb`) now stands down while
  a run is active, so the run crests itself; once a run ends short of the top, the ledge grab works
  as before.
- The crest waits for the feet, not the head, to clear the top: past the head the climb keeps at
  least crest speed (0.35 blocks/tick), then pushes onto the top.
- Temporary client game test results: run up peaked 4.2 blocks; a three-high wall was topped 32 ticks
  after sprinting from five blocks away; a 55 degree hit did nothing; a 30 degree airborne run sank
  0.7 blocks over 12 ticks while covering 4 blocks; the kick pushed off the wall, the server handed
  the double jump back and the landing did no damage. Not covered: repeat runs on one wall.
