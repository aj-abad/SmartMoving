# Checklist: Movement Prototypes (Chain Climb, Landing Roll, Sprint Vault)

Based on `research_movement_prototypes.md`. Each phase ends with a compile and a commit.

## Phase 0 — Shared plumbing
- [ ] `SmartMovingState`: add bits 37 `isRolling`, 38 `rollArmed`, 39 `isVaulting`, 40 `isChainClimbing` (fields, encode, decode, header table)
- [ ] `SmartMovingClientState`: new fields, encode in `sendStatePacket`, decode in `processStatePacket`, clear in `resetState`
- [ ] `SmartMovingServer`: decode `rollArmed`, `isRolling`, `isVaulting`, `isChainClimbing`
- [ ] `SmartMovingConfig`: roll / vault / chain fields + `readFrom` + `writeTo`
- [ ] Compile + commit

## Phase 1 — Landing roll
- [ ] `SmartMovingJumper`: airborne dodge press arms the roll (direction from WASD, forward default)
- [ ] `SmartMovingClientState.tickEssential`: track peak fall while armed, buffer countdown, landing → start roll or fall through to buffered dodge
- [ ] `SmartMovingRoller.handleRolling`: per-tick roll motion, gravity, move, fall reset, end + dodge cooldown
- [ ] `MixinLivingEntityClient.sm_travel_client`: call `handleRolling` before swimming (cancel)
- [ ] `MixinPlayerEntity`: `@ModifyVariable` on `causeFallDamage` damage multiplier for server player while `rollArmed`
- [ ] Model: `sm_animateRolling` (tuck pose) in the dispatch chain; exclude roll from dodge overlay
- [ ] Renderer: body yaw forced to roll yaw, tumble rotation about the hips
- [ ] Remote: track `rollStartTick` on bit edge in `SmartMovingClient` receive lambda
- [ ] Compile + commit

## Phase 2 — Sprint vault
- [ ] `SmartMovingVaulter.tryStartVault`: trigger, obstacle probe, clearance, launch
- [ ] `SmartMovingVaulter.tickVault`: end on landing / timeout
- [ ] `MixinLivingEntityClient.sm_travel_client`: call after `handleJumping`
- [ ] Model: `sm_animateVaulting` (hand plant, tucked legs)
- [ ] Renderer: forward lean during vault
- [ ] Remote: track `vaultStartTick` on bit edge
- [ ] Compile + commit

## Phase 3 — Chain climb
- [ ] `SmartMovingChainClimber.findChain` (3×3 columns, feet + head level, nearest vertical `ChainBlock`)
- [ ] `SmartMovingChainClimber.handle`: enter / exit rules, pull to hang point, climb / hold / slide motion, flags, cancel travel
- [ ] `MixinLivingEntityClient.sm_travel_client`: call before `[5-1]` climbing detection
- [ ] `CeilingClimbBlocks.supports`: horizontal chains
- [ ] Renderer: body yaw toward the chain for `isChainClimbing` (self and remote)
- [ ] Compile + commit

## Phase 4 — Docs
- [ ] README: features + controls rows for roll, vault, chain climb
- [ ] Tick this checklist, commit

## Verification (in-game, manual)
- [ ] Roll: dodge pressed mid-fall from 4+ blocks → tumble, reduced damage; short hop + press → regular dodge on landing
- [ ] Roll: no roll when landing in water or on a ladder
- [ ] Vault: sprint into fence / 1-block ledge → hop over; no vault under a 2-block ceiling
- [ ] Chain: walk into a hanging chain → climb; sneak → fast slide; jump → release; fall past a chain holding grab → catch
- [ ] Multiplayer: remote players show roll / vault / chain poses
