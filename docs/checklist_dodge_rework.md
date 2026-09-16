# Checklist: Dodge rework

Reference: `docs/research_dodge_rework.md`

## 1. Remove double-tap dodge
- [x] SmartMovingJumper.handleJumping: Remove double-tap consumption logic and counter reset
- [x] SmartMovingClientState: Remove `leftJumpCount/rightJumpCount/backJumpCount` and `prevPressLeft/Right/Back` fields
- [x] SmartMovingClientState.tickEssential: Remove double-tap counter update logic (retain `angleJumpType=0 on ground`)
- [x] SmartMovingClientState reset: Remove counter/prevPress reset lines
- [x] SmartMovingConfig: Remove `angleJumpDoubleClickTicks` field + load/save logic

## 2. Animation improvements
- [x] SmartMovingClientState: Add `angleJumpAnimStartAge` + helpers/reset for `angleJumpAnimTicks/angleJumpPoseWeight/angleJumpDirectionWeight/angleJumpBodyPitch/angleJumpBodyRoll`
- [x] MixinPlayerEntityModelClient.sm_animateAngleJumping: Ease-in envelope + new directional poses (forward/back/side blend), remove leg yRot twisting, adjust head pitch
- [x] MixinPlayerEntityRenderer.sm_setupTransforms: Add branching for angle jump torso pitch/roll (pelvis pivot 0.75)

## 3. Verification
- [x] `pnpm build` compilation success (JDK 26 auto-selected)
- [ ] In-game user verification: Forward/backward/side dodge poses, double-tap disabled, remote player display
- Tuning points: `SmartMovingClientState.angleJumpBodyPitch/Roll` (torso angles),
`angleJumpPoseWeight` (ease-in 2.5 ticks / relax), directional limb coefficients in `sm_animateAngleJumping`
- [x] Commit