# Research: Dodge Rework (Remove Double-Tap + Improve Animation)

## Requests
1. Disable directional key double-tap for dodging (retain only the dedicated dodge key).
2. Dodge animation feels stiff—especially the newly added forward dodge.

## Current Structure

### Input → Jump (`SmartMovingJumper.handleJumping`, L534-L575)
- `sm.dodgeJustPressed` (dedicated key, `SmartMovingKeys.dodge`) → `tryKeyboundDodge` → `tryAngleJump(relAngle)`.
- Double-tap path: If `sm.leftJumpCount` / `rightJumpCount` / `backJumpCount == -1`, then `tryAngleJump`. 
- Counter updates: `SmartMovingClientState.tickEssential` L2593-L2660 (IMPL-03 block). 
- Fields: `leftJumpCount`/`rightJumpCount`/`backJumpCount`, `prevPressLeft`/`Right`/`Back` (L665-L680, reset L3303-L3310). 
- Config: `SmartMovingConfig.angleJumpDoubleClickTicks` (`move.jump.angle.double.click.ticks`, L767/L1700/L1869) — specific to double-tap.
- `tryAngleJump`: `angleJumpType = 8 - relAngle/45` (Forward=8, Left=2, Back=4, Right=6, Diagonal 1/3/5/7).
- Physics: `tryJump(ANGLE)` → `verticalMotion = jumpVerticalFactor * angleJumpVerticalFactor (0.2)` ≈ 0.2 → Short hop with ~5–6 ticks of airtime.
- Upon landing: `angleJumpType = 0` (`ClientState` L2665-L2667, `onGround` || `verticalCollision`).
- Remote players: Synchronize `angleJumpType` via state packet bits 22–24 + 36. ### Animation
- Model: `MixinPlayerEntityModelClient.sm_animateAngleJumping` (L1921-L1965). 
- Sets a static pose immediately; no time-based progression (only idle swaying uses `animationProgress`). 
- `angle = directionIndex * π/4`, `backness/leftness/rightness = sm_directionBlend`. 
- Legs: `xRot = π/32 * (1 + right/leftness)`, `yRot = -angle` (90° twist at the pelvis), `zRot = ±π/32 * backness`. 
- Arms: `zRot = ±π/8 * left/rightness`, `xRot = -π/4 * backness`. 
- **Forward dodge (angle=0)**: backness=leftness=rightness=0 → legs at π/32 only, arms at 0 → effectively an upright posture = cause of the "stiffness."
- Renderer: `MixinPlayerEntityRenderer.sm_setupTransforms` TAIL — angle jump has no branching for torso tilt (only forces `bodyYaw` to match camera yaw, L486-L491). 
- Other states (flying/head-jumping/swimming) follow the `translate(0,pivot,0) → mulPose → translate(0,-pivot,0)` pattern. 
- Axis conventions (`setupRotations` TAIL, space prior to `scale(-1,-1,1)`): Forward=-Z, Up=+Y, Player Left=-X. 
- `XP.rotation(-θ)` = leaning forward (consistent with existing comments/code). 
- `ZP.rotation(+φ)` = tilting to the player's left. 
- Model coordinates: leg/arm `xRot < 0` = swinging forward, `> 0` = backward (based on vanilla riding pose `leg.xRot=-1.41`).

### Animation Timer
- `tickEssential` is local-only. For remote entities, the packet-based approach—recording the start time based on `ageInTicks` within the render path—functions identically for both local and remote instances.
- Render order: `setupRotations` (renderer TAIL) → `model.setupAnim` (model TAIL). Both can utilize the same `ageInTicks` value.

## Design

### 1. Remove Double-Tap
- Remove the block consuming Jumper L550–L575 and the counter reset at L544–L546.
- ClientState: Remove counter/prevPress fields, the IMPL-03 update block, and the reset line. Retain `angleJumpType=0 on ground` (moved to a separate location).
- Config: Remove `angleJumpDoubleClickTicks` field and associated load/save logic.

### 2. Animation
- Add `angleJumpAnimStartAge` (initialized to NaN) and `angleJumpAnimTicks(float age)` helper to ClientState. 
- Record `startAge = age` on the first render frame where `isAngleJumping()` becomes true; subsequently return `age - startAge`. Reset to NaN upon completion.
- Progression envelope: `k = smoothstep(clamp(t / 2.5))` (approx. 2.5-tick ease-in); remove immediate snapping.
- Add `forwardness` factor to directional blending (target: 0).
- New poses (all scaled by `k`):
- Forward: Lead leg tucked forward (xRot −0.9), trailing leg back (xRot +0.5), arms swinging oppositely + slightly spread, torso leaning forward ~18°. 
- Backward: Legs forward (xRot −0.5), arms forward for balance (xRot −0.7), torso leaning backward ~14°. 
- Sideways: Outer leg spread (zRot), inner leg tucked, outer arm raised (zRot), torso rolled ~14° toward the direction of movement. - Removed leg yRot twist (-angle).
- Renderer: Added an 'angle jump' branch — `XP.rotation(-pitch*k)` + `ZP.rotation(roll*k)` based on pivot 0.75 (pelvis). 
- Mutually exclusive with the flight/head-jump branch (`else if`); naturally disabled after landing if `angleJumpType=0`.