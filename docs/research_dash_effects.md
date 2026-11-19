# Research: dash effects and small ledges

## Dash effects today

`WindrunnerMoves.dashEffects` runs on the server when it accepts a ground or air dash: a `GUST` at the
chest, sprint-style block dust under the feet and the wind charge sound, then for six ticks
(`DASH_TRAIL_TICKS`) a following gust every other tick and dust every tick. They are sent to every
player, the dasher included, so:

- in first person the dasher's own gust fills the view, as the boost rings did;
- they play wherever the dash was pressed, even straight into a wall that stops it on the spot, and
  keep puffing at the wall for the rest of the six ticks.

The glide boost shows only its rings and a small gust behind.

## Plan

- One trail, `WindrunnerMoves` ticking it on both sides. The dasher's client draws its own (like the
  boost rings, which already do for timing), behind the `drawsOwnRings` hook, renamed
  `drawsOwnEffects`; the server sends the same particles to watchers only.
- A trail tick emits only once the player has moved since the last one: the first tick on the client
  runs before the dash's move, and a dash into a wall moves nowhere. The opening gust is drawn at the
  launch point on the first tick that moved, so nothing shows unless the dash carried the player.
- A tick whose move hit a wall (`horizontalCollision`, which the server takes from the client's move
  packet) ends the trail. Ticks without a move are skipped, so a collision flag left from before the
  dash cannot end it.
- The glide boost starts the same trail, alongside its rings.

## Small ledges

Reproduced in a client test over striped dirt path and grass (path tops sit 1/16 lower): most dashes
cross fine, as the hop clears the lip or the slide steps up, but a hop that comes down to just under
the grass top while still over the path (feet 0.0035 below it) hits the grass block's side in the
air. `Entity.collide` steps up only when on the ground or landing on that move, so the dash stops
dead: 0.75 to 0.34 b/t. 2 of 96 test dashes did this.

Fix: during a ground dash, `collide` treats the player as on the ground for its step-up check, so
anything up to the step height is stepped onto mid-hop. Both sides run it (the server replays the
client's moves through `collide`), and `groundDashing` is set on both. A ground dash still ends after
leaving its starting height by more than `GROUND_DASH_ELEVATION`.

## Results

- Ledges: `MixinWindrunnerEntity` steps a ground dash from where `collide`'s vertical move ended: up by
  the step height, across, back down. Making `collide` think the player was on the ground was not
  enough, since its step search starts at the feet and missed a lip just above them. All 96 dashes over
  the striped field now travel the full 7.3 to 7.8 blocks with no wall hit (2 stalled before).
- Trail, client particle counts after the press (third person unless noted): open ground builds to
  about 38; flush against a wall, 0; a wall 2.5 blocks ahead, the opening burst of 7 on the tick that
  moved and nothing after the hit; first person, 0 for ground and air dashes. A glide boost in third
  person adds the trail to its rings (about 38); in first person only the server's small gust behind
  the booster (2) remains.
