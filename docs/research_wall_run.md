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
  collisions, so it should not undo the override. To be confirmed in a client game test.

## Grab + sprint is already used

| Mechanic | Trigger | Wall run must |
|---|---|---|
| Sprint Vault | Grab + sprint, on the ground, at a 1-block ledge with clear space above and beyond | yield: a vaultable obstacle vaults |
| Head jump (dive) | Grab + sprint + Jump | yield to Jump on the ground; a jump off the wall is open for design |
| Slide | Grab + Sneak + sprint | not apply while sneaking |
| Climbing / ledge grab | Grab near holds or a wall top in reach | hand over at the top of a vertical run |

## Open design questions

- Angle split between vertical and horizontal runs (head-on vs glancing).
- Duration, speed and gravity during each run; what ends it (time, releasing Grab, wall ends).
- Jump during a run: wall jump away, and whether it interacts with the pants' double jump.
- Once per airtime, or chained between walls.
