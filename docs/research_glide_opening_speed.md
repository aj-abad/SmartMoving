# Research: glide opening speed

The robes open at no less than `DEPLOY_MIN_SPEED` (1.5 blocks/tick, 30 blocks/s), raised from 0.65
with the endgame retune. A standstill opening, such as the top of a double jump, therefore gains 30
blocks/s at once, close to the 36 blocks/s cruise. The same value is the opening brake's target, so a
fast fall is braked to about 30 blocks/s.

## Trade-off

Lift goes as speed squared and the wing is sized to cruise at 36 blocks/s, so at low speed it carries
little of the player's weight: a slower opening must spend height to build speed. Hands-off from a
standstill, calm air, the opening floor against the nose-down cap (`DEPLOY_PITCH_DOWN`):

| Floor, nose 5 degrees | Speed at 0.5 / 1 / 2 s | Worst sink | Lost in 2 s |
|---|---|---|---|
| 30 b/s (current) | 31.5 / 32.1 / 32.5 | 8.2 b/s | 9.8 |
| 24 b/s | 26.1 / 27.8 / 29.6 | 8.0 b/s | 13.4 |
| 20 b/s | 22.6 / 25.2 / 29.4 | 9.5 b/s | 17.3 |
| 16 b/s | 19.2 / 22.9 / 29.5 | 12.4 b/s, nose drops to 9 | 21.1 |
| 13 b/s | 16.7 / 21.4 / 29.9 | 15.1 b/s, nose drops to 16 | 23.9 |

A steeper cap only builds speed faster by losing more height. Below 20 blocks/s the nose drops after
opening, which is the nosedive the catch was added to stop.

## Plan

- `DEPLOY_MIN_SPEED` 1.5 to 1.0 (20 blocks/s), nose cap stays 5 degrees. The brake target follows,
  so a fast fall is braked to about 20 blocks/s; entries faster than that still keep 85% of their speed.
- Simulation test thresholds move with it (height lost in the first two seconds).
