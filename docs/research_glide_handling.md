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
