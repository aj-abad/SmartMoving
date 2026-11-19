# Research: endgame glide handling

The Windrunner glide is still the Aircraft wingsuit's model, number for number (`WindrunnerGlideModel`):
about 22 blocks/s settled, roughly 5 blocks forward per block down, dives capped near 30 blocks/s,
3.8 degrees/tick of pitch and 6.5 of roll. The robes should fly like an endgame, post-elytra glider:
faster, quicker to answer the controls, and sinking slower.

## Levers in the model

- Speed and glide ratio: parasitic and induced drag (`PARASITIC_DRAG`, `INDUCED_DRAG`) set the best
  glide and the speed it settles at; `OVERSPEED_DRAG` above `relative.length() > 1.1` caps dives.
- Response: `PITCH_RATE`, `ROLL_RATE`, `BANK_ANGLE` and the rate limits passed to
  `WindrunnerGlidePhysics.rotate` (response 0.5, pitch and roll acceleration 0.9 and 1.8 degrees/tick
  squared). Turn rate comes from bank (`yawTarget = -roll * 0.065`) and from lift turning the path.
- Control authority ramps with airspeed squared up to 0.7 blocks/tick (`authority`).
- Wear: `WindrunnerGlideWear.OVERSPEED` (1.25 b/t) and `SEVERE_OVERSPEED` (1.75) were set for the old
  envelope and must move with it, or a normal cruise would wear the robes like an overspeed.
- Deploy: `DEPLOY_MIN_SPEED` (0.65) is the opening and brake target, scaled to the old cruise.

## Boost

- `BOOST` (0.4 b/t along the nose, 15-tick cooldown, 1 point of wear) becomes a much stronger kick.
- Effect: `ParticleTypes.SONIC_BOOM` (the Warden's shockwave particle) from the server where the boost
  fires, so every viewer sees it, with the existing boost sound.

## Measurement

A temporary bench over `WindrunnerGlideModel.tick` measures, before and after: settled speed, sink and
glide ratio; time to pitch 30 degrees and to roll to full bank; time for a 180 degree turn; terminal
dive speed and the pull-out; speed added by a boost. Results and the final constants are recorded
below once tuned, and the simulation test thresholds move to the new envelope.

## Results

Benchmarked over `WindrunnerGlideModel.tick`, calm air, before (the Aircraft wingsuit) and after:

| | Before | After |
|---|---|---|
| Settled cruise | 14.9 b/s | 36.3 b/s |
| Settled sink, glide ratio | 3.0 b/s, 4.8 | 2.5 b/s, 14.3 |
| Hands-off swing, 5 to 20 s after opening | (not measured) | sink 2.7 to 3.3 b/s |
| 30 degrees of pull-up, 55 degrees of bank | 11 ticks, 11 ticks | 10 ticks, 8 ticks |
| 180 / 360 degree turn | 2.7 s, 26 blocks lost / not measured | 2.05 s, 4.6 lost / 3.55 s, 11.8 lost |
| Dive terminal | 29.6 b/s | 74.6 b/s |
| Boost | +7.7 b/s | +17.7 b/s |

What it took, beyond the constants:

- Speed without losing the glide: `AREA` 0.13 to 0.0228 raises the speed the wing flies at (lift goes
  as area times speed squared) without changing its lift-to-drag; drag 0.07/0.14 to 0.02/0.049 lifts
  the best glide to about 14. Overspeed drag now starts at 2.5 b/t (was 1.1) at 0.08 (was 0.6).
- Turns: at 36 b/s a physical bank needs about 3g to turn briskly, so a held turn spiralled down
  (146 blocks lost over 4.6 s, then worse with a nose-up "coordinated turn" pull). Instead
  `WindrunnerGlideModel.align` swings the horizontal airflow toward the nose's heading by 12% a tick
  at full authority, keeping speed, like an elytra following the view. Bank 65 to 60 degrees, bank
  turn rate 0.065 to 0.1, roll 6.5 to 11 degrees/tick, pitch 3.8 to 6.5.
- The nose-down stability push is scaled by the cosine of the bank, so a held turn does not tuck into
  a spiral; a 360 now ends near level.
- Hands off, the fast glide porpoised (sink swinging from 6.7 down to a 3.1 climb). The nose now eases
  against vertical speed away from a 0.13 b/t settled sink (`PHUGOID_DAMPING` 10), only without pitch
  input.
- Stall protection: the faster pitch rate over-rotated a pull-up into a stall at modest speeds, which
  failed the dive pull-out check. Nose-up input now fades over the last 6 degrees before the stall
  angle.
- Opening: the catch now opens no steeper than 5 degrees nose-down at no less than 1.5 b/t (was 15
  and 0.65), the new cruise being much flatter and faster; every entry settles within a second and
  loses about 10 blocks in the first two.
- Wear thresholds moved with the envelope: overspeed 1.25 to 2.6 b/t, severe 1.75 to 3.4.
- Wall impacts use vanilla's elytra crash rule (lost speed x10 - 3, 15 at cruise) instead of a
  squared rule that would have been fatal at the new cruise.
- Boost `BOOST` 0.4 to 0.9. Three `SONIC_BOOM` rings, one at the player and two ahead along the nose:
  the booster's client draws them on the tick it predicts the boost (server particles arrived after the
  player had flown 3 to 5 blocks past them), and the server sends them only to watchers.
- In game: 33.8 b/s and a 3.1 b/s sink after four hands-off seconds; a boost took 33.8 to 51.3 b/s;
  two seconds of right bank turned 176 degrees at 38.6 b/s; screenshots show the rings in third and
  first person.
