# Research: Sprint Vault removal

The Sprint Vault prototype (`docs/research_movement_prototypes.md`): holding Grab while sprinting into a
1 to 1.5 block obstacle planted a hand and carried the player over it. It is removed outright.

Footprint (about 115 references in 13 files):

- `SmartMovingVaulter` (deleted) and its call sites in `SmartMovingClient`, `SmartMovingClientState`
  and `MixinLivingEntityClient` (travel takeover).
- Pose and render: `MixinPlayerEntityModelClient` (`sm_animateVaulting`), `MixinPlayerEntityRenderer`,
  `MixinLivingEntityRenderer`.
- Config: `vault`, `vaultVerticalFactor`, `vaultHorizontalFactor`, `vaultMinHeight`,
  `vaultMaxHeight`, `vaultReach` and their `move.vault*` load and save lines. Old config files keep
  loading; unknown keys are ignored.
- Network: state bit 39 (`isVaulting`) stays reserved, like bits 37 and 38 from the Landing Roll, so
  bit 40 (`isChainClimbing`) keeps its position. `SmartMovingServer` drops its decode.
- Gates that listed the vault: the dodge (`SmartMovingJumper.tryKeyboundDodge`), the headband's ground
  dash (`WindrunnerSmartMoving.claimsGroundDash`) and the wall run (`WindrunnerWallRun.claimed`).

Effect on the Windrunner wall run: a 1-block obstacle no longer vaults, and still never starts a wall
run, which needs a wall at least two blocks tall. Grab + sprint into a low obstacle is ordinary sprinting.
