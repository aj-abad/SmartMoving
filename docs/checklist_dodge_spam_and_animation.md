# Checklist: Dodge spam speed + dodge animation

Reference: `docs/research_dodge_spam_and_animation.md`

## 1. Dodge speed
- [x] `SmartMovingJumper`: `getDodgeMotion` (≥ `h` along the dodge, sideways kept, total ≤
      `max(h, |v|)`), used by `tryJump` D-11 for ANGLE only; wall jumps keep `getJumpMoving`
- [x] `SmartMovingConfig`: `angleJumpCooldownTicks` (default 10) + load/save
- [x] `SmartMovingClientState`: `dodgeCooldown` field, ground-only countdown in `tickEssential`,
      reset in `resetState()`
- [x] `SmartMovingJumper.tryKeyboundDodge`: refuse while cooling down, start the cooldown on success
- [x] Build + commit

## 2. Dodge animation
- [x] `SmartMovingClientState`: replace `angleJumpAnimStartAge` and the old pose helpers with
      `angleJumpStartTick` / `angleJumpEndTick` / `angleJumpWorldYaw`, `startAngleJump`,
      `trackRemoteAngleJump`, and `sampleDodgePose` (air + landing recovery outputs)
- [x] Local timing: start in `SmartMovingJumper.tryAngleJump`, end where `tickEssential` clears
      `angleJumpType`; reset in `resetState()`
- [x] Remote timing: `SmartMovingClient` state receiver records the `angleJumpType` edges
- [x] Render state: carry the rendered body yaw (`SmartMovingAvatarRenderState` +
      `MixinAvatarRenderState`), set in `sm_modifyBodyYaw`
- [x] `MixinPlayerEntityRenderer`: drop angle jumps from the body-yaw force branch; lean + crouch
      drop from the sample, only when no other SM pose is active
- [x] `MixinPlayerEntityModelClient`: blended dodge pose with tuck, landing crouch, head
      compensation and swinging-arm preservation, from the standard branch only; no forced
      `head.yRot`
- [x] `MixinLivingEntityRenderer`: stop zeroing the walk animation during dodges
- [x] Build + commit

## 3. Verification
- [x] `pnpm build` compilation success
- [x] Leftover scan for removed helpers/fields
- [x] Envelope continuity + direction weights checked numerically (air end = landing start)
- [x] Landing crouch skipped while sneaking (found in review)
- [x] README: dodge cooldown noted in Controls
- [ ] In-game user verification: spamming dodge no longer builds speed; cooldown feels OK;
      forward/back/side/diagonal dodges start without a body twist, move during the hop, and land
      with a short crouch instead of snapping; remote players look the same
- Tuning points: `angleJumpCooldownTicks` (config), `DODGE_LAUNCH_TICKS` / `DODGE_RECOVERY_TICKS` /
  `DODGE_LANDING_CROUCH` and the lean factors in `SmartMovingClientState.sampleDodgePose`, limb
  coefficients in `MixinPlayerEntityModelClient.sm_animateDodge`
