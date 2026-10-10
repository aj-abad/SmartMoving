# Checklist: Movement Prototypes (Chain Climb, Landing Roll, Sprint Vault)

Based on `research_movement_prototypes.md`. Each phase ends with a compile and a commit.

## Phase 0 — Shared plumbing
- [x] `SmartMovingState`: add bits 37 `isRolling`, 38 `rollArmed`, 39 `isVaulting`, 40 `isChainClimbing` (fields, encode, decode, header table)
- [x] `SmartMovingClientState`: new fields, encode in `sendStatePacket`, decode in `processStatePacket`, clear in `resetState`
- [x] `SmartMovingServer`: decode `rollArmed`, `isRolling`, `isVaulting`, `isChainClimbing`
- [x] `SmartMovingConfig`: roll / vault / chain fields + `readFrom` + `writeTo`
- [x] Compile + commit

## Phase 1 — Landing roll
- [x] `SmartMovingJumper`: airborne dodge press arms the roll (direction from WASD, forward default)
- [x] `SmartMovingClientState.tickEssential`: track peak fall while armed, buffer countdown, landing → start roll or fall through to buffered dodge
- [x] `SmartMovingRoller.handleRolling`: per-tick roll motion, gravity, move, fall reset, end + dodge cooldown
- [x] `MixinLivingEntityClient.sm_travel_client`: call `handleRolling` before `handleJumping` (cancel; jump and dodge input ignored during the roll)
- [x] `MixinPlayerEntity`: `@ModifyVariable` on `causeFallDamage` damage multiplier for server player while `rollArmed`
- [x] Model: `sm_animateRolling` (tuck pose) in the dispatch chain; exclude roll from dodge overlay
- [x] Renderer: body yaw forced to roll yaw, tumble rotation about the hips
- [x] Remote: track `rollStartTick` on bit edge in `SmartMovingClient` receive lambda
- [x] Compile + commit

## Phase 2 — Sprint vault
- [x] `SmartMovingVaulter.tick`: trigger, obstacle probe, clearance, launch (plus `move.vault.min.height` so fences-only is configurable)
- [x] `SmartMovingVaulter.tick`: end on landing / timeout
- [x] `MixinLivingEntityClient.sm_travel_client`: call after `handleJumping`
- [x] Model: `sm_animateVaulting` (hand plant, tucked legs)
- [x] Renderer: forward lean during vault
- [x] Remote: track `vaultStartTick` on bit edge
- [x] Compile + commit

## Phase 3 — Chain climb
- [x] `SmartMovingChainClimber.findChain` (3×3 columns, feet + head level, nearest vertical `ChainBlock`)
- [x] `SmartMovingChainClimber.handle`: enter / exit rules, pull to hang point, climb / hold / slide motion, flags, cancel travel
- [x] `MixinLivingEntityClient.sm_travel_client`: call before `[5-1]` climbing detection
- [x] `CeilingClimbBlocks.supports`: horizontal chains
- [x] Renderer: body yaw toward the chain for `isChainClimbing` (self and remote)
- [x] Compile + commit

## Phase 4 — Docs
- [x] README (en + ko): features + controls rows for roll, vault, chain climb
- [x] Tick this checklist, commit

## Known prototype limits (follow-ups)
- Hitbox stays 1.8 tall during roll / vault; the first-person camera does not dip during the roll.
- No exhaustion, sounds or particles for the three moves.
- Vanilla auto-jump (if enabled) can fire before the vault probe reaches the obstacle.
- Remote roll direction comes from the last position step, not the exact roll yaw.

## Verification (in-game, manual)
- [ ] Roll: dodge pressed mid-fall from 4+ blocks → tumble, reduced damage; short hop + press → regular dodge on landing
- [ ] Roll: no roll when landing in water or on a ladder
- [ ] Vault: sprint into fence / 1-block ledge → hop over; no vault under a 2-block ceiling
- [ ] Chain: walk into a hanging chain → climb; sneak → fast slide; jump → release; fall past a chain holding grab → catch
- [ ] Multiplayer: remote players show roll / vault / chain poses
