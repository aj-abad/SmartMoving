# Research: wall run animation and first-person bob

## What other players need

The wall run is client-authoritative and the server only knew "running" (actions 6/7). Remote clients
need the run's kind and the wall's side to pose the model, so a `WindrunnerWallRunPayload(playerId,
mode, side)` goes both ways: the runner sends its start and stop, the server checks the boots and
relays to every tracking player, and also sends the current run on `START_TRACKING`. Actions 6 and 7
are left unused; `WALL_KICK` stays an action and also ends the run for watchers.

`WindrunnerMoves` holds the shown run on both sides (`showWallRun(mode, side)`) with the tick it
changed, so the pose blends in and out over three ticks from the same data for local and remote
players.

## Third-person pose

- Hooks: `AvatarRenderer.extractRenderState` TAIL (capture into `WindrunnerRenderState`),
  `HumanoidModel.setupAnim` TAIL at priority 1100 (limbs), `AvatarRenderer.setupRotations` TAIL (whole-body lean).
  SmartMoving poses at the same `HumanoidModel.setupAnim` TAIL; the higher priority puts the wall
  run's callback after it. The run resets fall distance, so SmartMoving's falling pose
  (`doFallingAnimation`) stays off.
- Run up: the body faces the wall, leans back off it, and the head tips up. Legs take high
  alternating strides and the arms reach up in turn, opposite to the legs.
- Run along: the body faces along the run (from the player's movement) and leans 20 degrees away from
  the wall, feet on it. Legs sprint; the outer arm swings out for balance while the wall-side arm pumps.
- Stride cycle from `ageInTicks`, quicker than a sprint, so it reads the same for every viewer.

## First-person bob

- View and hand bob both come from `GameRenderer.bobView`, which reads the avatar's walked distance
  and `bob`.
- `LocalPlayer.move` adds walked distance from horizontal movement only, so a run up adds none: the
  walked-distance hook adds the climb's vertical speed (x0.6, as vanilla scales its steps).
- `AbstractClientPlayer.updateBob` sets the bob target to zero off the ground: during a run it takes
  `min(0.1, run speed)` instead, the same cap as on the ground.

## Findings from the screenshots

- A `PlayerModel` hook posed only the skin: armor (all four Windrunner pieces) is drawn by separate
  humanoid armor models that pose themselves from the same render state, so the armor stayed in the
  vanilla pose over it. Posing at `HumanoidModel.setupAnim` covers the skin and every armor model.
- Running up, the body's center is 0.3 blocks from the wall, so a planted foot and raised hands sank
  into the stone. The model is drawn 0.3 blocks off the wall (render state only), leans back 12
  degrees, and the arms reach nearly straight up.
- Bob measured in a run up and a run along: 0.10, the same cap as sprinting on the ground; the run up
  walks 0.14 per tick of view-bob distance.
