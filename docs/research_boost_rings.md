# Research: white, aligned boost rings

The glide boost draws three `ParticleTypes.SONIC_BOOM` rings (`WindrunnerMoves.sonicBoomRing`), one at
the player and two ahead along the nose, 0.9 blocks above the player's feet.

## Colour

- `SonicBoomParticle` (via `HugeExplosionParticle`) draws `sonic_boom_0` to `_15` from
  `particles/sonic_boom.json` and multiplies them by a random grey (0.4 to 1). The frames are drawn
  in two cyans, (44, 227, 235) and (11, 180, 170), so no tint can make them white.
- Overriding the vanilla textures would recolour the Warden's attack too. Instead the rings get their
  own particle type, `smartmoving:windrunner_boom` (`FabricParticleTypes.simple(true)`, always shown
  like the vanilla one), drawn on the client by vanilla's `SonicBoomParticle.Provider` with its own
  sprite set, so lifetime (16 ticks), size (1.5) and full brightness stay as they were.
- The vanilla sequence spends frames 0 to 7 (0.4 s) on arcs converging to a dot and only bursts into
  a ring at frame 8, by which time a boosted glide has flown 10 blocks or more. The Windrunner frames
  are original white artwork from `scripts/GenerateWindrunnerTextures.java` and start at the burst:
  eight frames from a white disc through an expanding double ring to a broken outer ring.

## Height

While gliding the player is in `Pose.FALL_FLYING`, 0.6 blocks tall, so 0.9 blocks above the feet
is above the body. The rings move to the middle of the player's current hitbox (`getBbHeight() / 2`,
0.3 while gliding), on both the booster's local draw and the copy sent to watchers.
