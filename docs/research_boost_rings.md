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
  eight frames from a small ring through an expanding double ring to a broken outer ring.

## Height

While gliding the player is in `Pose.FALL_FLYING`, 0.6 blocks tall, so 0.9 blocks above the feet
is above the body. The rings move to the middle of the player's current hitbox (`getBbHeight() / 2`,
0.3 while gliding), on both the booster's local draw and the copy sent to watchers.

## Results

- `SonicBoomParticle.Provider` alone still drew the rings grey: the tint is set in
  `HugeExplosionParticle`'s constructor. `WindrunnerBoomParticle` subclasses it and resets the colour
  to white, with its own provider.
- Moving the rings to the hitbox middle was not enough: the two rings ahead were strung along the nose,
  which at cruise points about 6 degrees above the descending flight path, so the ring 5 blocks ahead
  sat close to a block above where the player passed. They now follow the velocity's direction.
- Screenshots two ticks after a boost (temporary client test): from behind, the player is framed inside
  the ring; from the side the rings lie along the path, with the newest at the body.
- In first person the ring just ahead fills much of the view for the tick or two before the player
  flies through it, as a ring placed on the path must. The bands are kept 1 to 2 pixels wide (about
  0.1 to 0.2 blocks) so it reads as a ring rather than a white wall.

## Follow-up: no rings in first person

The booster's own rings are hidden in first person, where the ring ahead fills the view. The local
draw sits in common code (`WindrunnerMoves.localSonicBoom`, called from the glide's predicted tick),
which cannot see the camera, so the client sets a hook at init that answers whether the camera is
first person. Watchers still get the server's rings, and third person still draws them.
