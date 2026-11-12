# Research: glide deploy on Jump, and the post-deploy nosedive

## Deploy on Jump

- The robes opened on a dedicated glide key (`SmartMovingKeys.glide`, Mouse 4) and folded on the same
  key or Sneak. The key goes; an airborne Jump press that nothing else spends opens the robes.
- Order of an airborne Jump press (`WindrunnerGlideControls.beforeTick` -> `WindrunnerControls.jump`):
  wall-run kick, then the ground dash's hop jump, then the pants' double jump. Only when none of them
  takes the press, and SmartMoving does not own it (wall jump next to a wall, climbing, swimming,
  flying), does it open the robes, subject to the usual three blocks of clearance.
- With the pants, the first air jump is the double jump and the next opens the robes; without them, the
  first air jump opens the robes. Sneak still folds an open glide.
- The old `key.smartmoving.windrunner_glide` lang entry and README rows go with the key.

## Why a fresh glide nosedives

Hands-off simulation from typical entries (`WindrunnerGlideModel.tick`, calm air), before the change:

| Entry | Start pitch | Max pitch | Worst sink | Lost in 2 s | Settled |
|---|---|---|---|---|---|
| Standing jump apex | 75 | 75 | 1.08 b/t | 28.9 | 4.3 s |
| Sprint jump, falling | 18 | 30 | 0.51 b/t | 18.0 | 2.8 s |
| Double jump apex, sprinting | 18 | 29 | 0.49 b/t | 15.0 | 3.2 s |
| Walking off a 2-block ledge | 27 | 37 | 0.61 b/t | 22.3 | 2.7 s |
| Falling fast | 46 | 51 | 1.12 b/t | 32.6 | 2.6 s |

- `State.deploy` points the nose to build airspeed from horizontal momentum alone:
  `atan2(0.15, horizontal) - TRIM`, so a near-standstill opens 75 degrees nose-down.
- The velocity is left as it was, mostly downward. The airflow then meets the nose at a large angle of
  attack, and passive stability (`(attack - TRIM) * 0.14`) lowers the nose further while speed builds:
  the nose keeps dropping for about a second after opening.

## Fix: the robes catch the air

- The deploy pitch is capped at 30 degrees nose-down (and -10 up).
- The entry velocity is redirected along the flight path that sits `TRIM` below the nose, so the glide
  opens at its trim angle of attack with no nose-down push. Speed is kept at 85% of the entry's, with
  a floor of 0.5 blocks/tick so even a standstill has control authority.
- The server sets this velocity when the robes open and sends it in the state snapshot, which the
  client already adopts, so prediction stays in step.
