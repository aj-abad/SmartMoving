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

- The deploy pitch is capped at 15 degrees nose-down (and -10 up).
- The entry velocity is redirected along the flight path that sits `TRIM` below the nose, so the glide
  opens at its trim angle of attack with no nose-down push. Speed is kept at 85% of the entry's, with
  a floor of 0.65 blocks/tick so even a standstill has control authority.
- The server sets this velocity when the robes open and sends it in the state snapshot, which the
  client already adopts, so prediction stays in step.

## Results

Tuned by simulation (cap 30/20/15 degrees, floor 0.5/0.6/0.65/0.7); 15 degrees and 0.65 opened gentlest:

| Entry | Max pitch | Worst sink | Lost in 2 s | Settled |
|---|---|---|---|---|
| Standstill, sprint jump, 2-block ledge | 15.5 | 0.32 b/t | 11.7 | 1.75 s |
| Falling fast | 15.0 | 0.39 b/t | 7.7 | 0.65 s |

In game (temporary client test): with the pants, the first air jump double-jumped and the second
opened the robes, holding 15.9 degrees and about 18 blocks/s forward half a second later; without
the pants the first air jump opened them.

## Follow-up: stall versus an opening brake

A stall on deploy while falling (nose level or 15 degrees up, entry velocity untouched) was simulated
against the catch, hands off:

| Entry | Catch: nose / lost in 2 s / settled | Stall, nose level | Stall, nose 15 up |
|---|---|---|---|
| Sprint jump, falling | 15 / 11.7 / 1.8 s | 24 / 15.4 / 2.8 s | 22 / 15.9 / 2.8 s |
| 2-block drop | 15 / 11.7 / 1.8 s | 28 / 19.6 / 2.6 s | 28 / 20.8 / 2.8 s |
| Falling 24 b/s | 15 / 7.7 / 0.7 s | 28 / 22.9 / 2.4 s | 38 / 28.1 / 3.3 s |
| Falling 40 b/s | 15 / 4.5 / 0.5 s | 26 / 23.2 / 2.1 s | 39 / 30.3 / 3.3 s |

A stalled wing has no lift and recovers by diving for airspeed, so a stall reintroduces the nosedive,
and half a second in the player still sinks at 7 to 16 blocks/s: it does not read as a brake either.

Instead, the opening brakes: for the first `BRAKE_TICKS` of a glide, airspeed above the opening speed
(`DEPLOY_MIN_SPEED`) is cut by a fixed share each tick, along the velocity, so the direction (and the
trim angle of attack the catch set up) is untouched. A fast fall snaps down to a slow glide, then the
glide builds speed on its own.

- `State.brakeTicks` counts the brake down inside `WindrunnerGlideModel.tick`, the step both the server
  and client prediction run, and goes in the state payload so a correction replays it the same way.
- Entries already at the opening speed (a jump, a short drop) are not braked.

### Brake results

`BRAKE_TICKS` 10, `BRAKE_KEEP` 0.75, armed only when the caught speed would exceed the opening speed:

| Entry | Sinking at 0.5 s | Lost in 2 s | Settled |
|---|---|---|---|
| Standstill to 2-block drop | 6.4 b/s (unbraked) | 11.7 | 1.75 s |
| Falling 24 b/s | 5.7 b/s | 11.5 | 1.85 s |
| Falling 40 b/s | 5.1 b/s | 10.4 | 1.25 s |
| Falling 70 b/s | 4.9 b/s | 10.2 | 0.45 s |

The brake costs fast entries some height against the bare catch (which turned the whole fall into a
fast forward glide) in exchange for a clear stop. In game, a 41.5 b/s fall opened to 16.5 b/s and a
7.7 b/s sink half a second later at y 291 (thin air), client and server speeds within 0.3 b/s.
