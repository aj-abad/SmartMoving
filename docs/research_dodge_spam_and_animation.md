# Research: Dodge Spam Speed + Stiff Dodge Animation

## Requests

1. Spamming the dodge key must not make the player move really fast.
2. The dodge animation is stiff.

Background: `docs/research_dodge_rework.md` (dodge key only, eased pose). That pass was never
verified in game.

## 1. Why spamming is fast

### Current physics

`SmartMovingJumper.handleJumping` → `tryKeyboundDodge` (needs `onGround`, no other state, WASD
direction) → `tryAngleJump` → `tryJump(ANGLE, …, worldAngleDeg)`.

In `tryJump` D-11 (`angle != null`), angle jumps use the original `getJumpMoving(reset = false)`:

```
horizontal = max(|v|, h)          // h = jumpHorizontalFactor (1) × angleJumpHorizontalFactor (0.4)
v' = v + dir × horizontal         // per axis
```

`horizontalMotion` is then set to 0, so the D-12 `maxHorizontalMotion` clamp never runs, and
`maxHorizontalMotion` is only computed when `h > 1` anyway. Nothing caps the result.

A dodge in the direction the player is already moving adds `max(|v|, 0.4)`, which doubles the speed
once `|v| > 0.4`. The hop lasts 6 ticks (vertical 0.2) at air damping 0.91 (`0.91^6 ≈ 0.57`), and
the dodge tick itself uses air damping because `sm.isJumping` is set. So a dodge pressed on the first
ground tick lands with `2 × 0.57 ≈ 1.14×` the previous speed. The growth is exponential. The only
gate is `onGround`; there is no cooldown.

The original mod only had side/back double-tap jumps, and those are naturally rate limited. The
dedicated dodge key (plus the new forward direction) makes same-direction chains trivial.

### Simulation (no movement input, dodge on the first possible tick)

| Formula | Speed after dodges 1…6 (blocks/tick) | 12th | Average |
| --- | --- | --- | --- |
| Current | 0.40, 0.63, 0.76, 0.86, 0.98, 1.11 | 2.38 | 1.02 b/t = 20.5 m/s |
| Capped (below), no cooldown | 0.40 every time | 0.40 | 0.32 b/t = 6.4 m/s |
| Capped + 10 ground-tick cooldown | 0.40 every time | 0.40 | 0.16 b/t = 3.2 m/s |

Vanilla sprinting is about 5.6 m/s, so the cap alone still makes dodge-spam a faster way to travel.
The cooldown makes it slower than running.

### Fix

- **Dodge velocity (ANGLE only; wall jumps keep `getJumpMoving`):** split the current velocity into
  the part along the dodge direction and the part across it. Along it, the new speed is
  `max(along, h)`, so a dodge always gives at least `h` in its direction and cancels momentum
  against it. The sideways part is kept. The total is clamped to `max(h, |v|)`.
  - Standing still: `h` in the dodge direction, same as before.
  - Chained dodges: 0.4 every time, no compounding.
  - Sprint (0.28) + back dodge: a clean 0.4 backwards. Before, it was a weak 0.12.
  - Sprint + side dodge: 0.4 total, split between sideways and the kept forward momentum.
  - A dodge never increases speed beyond `max(h, current speed)`.
- **Cooldown:** `SmartMovingClientState.dodgeCooldown`, set on a successful dodge to
  `ceil(angleJumpCooldownTicks)` and counted down only while standing on the ground and not dodging.
  So every dodge is followed by that many ground ticks, which also leaves room for the landing
  animation. New config `angleJumpCooldownTicks` (`move.jump.angle.cooldown.ticks`, default `10`,
  float like the other `*Ticks` options). Reset in `resetState()`. No input buffering: a press
  during the cooldown is ignored.

## 2. Why the animation is stiff

### Current structure

- Timer: `angleJumpAnimStartAge` is set on the first rendered frame of a dodge and cleared on the
  first rendered frame without one (`angleJumpAnimTicks`).
  - If the end is never rendered (first person, or a remote player off screen), the next dodge
    reuses the old start and begins in its relaxed pose.
- Envelope `angleJumpPoseWeight`: smoothstep ease-in over 2.5 ticks, which starts slowly, then a
  25% relax from tick 4.
- Model (`MixinPlayerEntityModelClient.sm_animateAngleJumping`): one static target pose per
  direction times the weight. It overwrites limb angles and forces `head.yRot = 0`.
- Renderer (`MixinPlayerEntityRenderer.sm_setupTransforms`): body pitch/roll about the hips times
  the weight.
- Body yaw: `sm_captureBodyYaw` puts angle jumps in the force branch, so on the first frame the body
  snaps to the camera yaw. While strafing, vanilla turns the body up to 50° toward the movement, so
  a side dodge starts with a 50° twist.
- Walk animation: `MixinLivingEntityRenderer` zeroes `walkAnimationSpeed` during angle jumps, so the
  walking leg swing vanishes on the first frame.
- Landing: `angleJumpType = 0` on the first ground tick, and pose, lean and walk swing all snap back
  in one frame.

The result: swing cut and body twist on takeoff, a frozen pose for most of the hop, and a snap on
landing.

### Vanilla 26.3 facts used

- `ClientLevel.tickNonPassenger`: `commonTick()` (old position, `tickCount++`) runs before `tick()`
  (`aiStep` → `tickEssential` → `travel` → dodge). The motion of tick `N` renders over
  `ageInTicks ∈ [N, N+1)`, since `EntityRenderer` sets `ageInTicks = tickCount + partialTicks`.
  Remote players tick on the client too.
- `Model.setupAnim` calls `resetPose()` first, so part offsets (`y`/`z`) set in the TAIL are safe.
- Vanilla crouch (`HumanoidModel.setupAnim`): `body.xRot = 0.5`, arms `xRot += 0.4`, legs `z += 4`,
  head `y += 4.2`, body and arms `y += 3.2`; `AvatarRenderer.getRenderOffset` lowers the model
  `2/16 × scale`.
- `LivingEntityRenderer.submit` runs `setupRotations` right away but defers the body model's
  `setupAnim` until the submitted model is drawn. Per-frame values the model needs from the renderer
  must travel on the render state (`SmartMovingAvatarRenderState`).
- Axes (checked from the matrices): renderer pre-scale space `XP(-θ)` leans forward and `ZP(+φ)`
  rolls toward the player's left. That space is model space rotated 180° about Z, so they equal
  model `R_x(+θ)` / `R_z(+φ)`: compensate with `head.xRot -= θ·c` and `head.zRot -= φ·c`. Model
  limbs: `xRot < 0` forward; `leftLeg/leftArm.zRot < 0` and `right…zRot > 0` outward.

### Fix

- **Tick-based timing:** `angleJumpStartTick`, `angleJumpEndTick` and `angleJumpWorldYaw` replace
  `angleJumpAnimStartAge`.
  - Local player: start in `tryAngleJump` (exact world yaw), end where `tickEssential` clears
    `angleJumpType` on landing.
  - Remote players: the state receiver detects the 0 → n and n → 0 edges of `angleJumpType` and
    records `tickCount`. The world yaw is `yRot + (8 − type) × 45°`.
  - Rendering never changes timing state, so culling and first person cannot leave a stale start.
- **Body yaw follows vanilla:** drop angle jumps from the force branch. The body keeps its normal
  lagged yaw, with no snap on takeoff or landing, and the head keeps its normal corrected yaw (no
  forced `head.yRot = 0`). The pose is computed relative to the body yaw actually rendered (stored
  on the render state by `sm_modifyBodyYaw`): direction = `bodyYaw − angleJumpWorldYaw`. When the
  body turns into a side dodge, the lean still points along the real motion.
- **One sampled envelope** (`SmartMovingClientState.sampleDodgePose(ageInTicks, bodyYaw)`), used by
  the renderer and the model, outputs weight, pitch, roll, tuck, landing crouch and 4 direction
  weights. Air phase at `t` ticks since takeoff:
  - `launch = easeOutCubic(t / 1.75)`: moves on the first frame.
  - `settle = smoothstep((t − 7) / 6)`: only for long falls (dodging off a ledge).
  - `weight = launch × (1 − 0.3·settle)`.
  - `lean = launch × (1 − 0.35·smoothstep((t − 1.5) / 4.5)) × (1 − 0.3·settle)`: peaks at takeoff
    and eases toward landing.
  - `tuck = sin(π · clamp((t − 0.5) / 5.5))`: legs gather toward the apex and extend to land.
- **Landing recovery** (6 ticks after landing, `s = r / 6`):
  - Start from the air sample at the landing time and fade weight, lean and tuck by `(1 − s)²`.
  - Add a partial vanilla crouch `0.4 × 6.75·s·(1 − s)²`, which peaks 2 ticks after touchdown and
    ends smoothly, plus the matching `2/16` model drop.
- **Blend, not overwrite:** limbs lerp from the vanilla pose to the dodge pose by `weight`. The walk
  swing keeps running underneath (no zeroing), so takeoff and landing blend with the walk cycle.
  The swinging arm is left alone, as in `sm_animateFalling`.
- **Poses per direction** (takeoff/landing base plus tuck): forward is a running lunge with lead leg
  forward and arms counter-swinging; back leans back with legs forward and arms reaching; side has
  the lead leg reaching out, the trailing leg tucking under, and the trailing arm flung out. The
  renderer lean is pitch `(fw·0.30 − bk·0.26)·lean` and roll `(lf − rt)·0.26·lean`, and the head
  counters 60% of the pitch and 50% of the roll.
- The dodge pose only shows when no other SM pose is active (the model's standard branch; a
  renderer check for climbing/swimming/crawling/sliding/flying/head jump).

## Out of scope

Input buffering, sounds and particles, and dodge i-frames.
