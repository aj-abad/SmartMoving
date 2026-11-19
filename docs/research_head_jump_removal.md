# Research: head jump removal

SmartMoving's head jump is a forward dive launched from a jump. It comes in three variants, and all of
them go:

- Ground head jump: Grab + sprint + Jump charges (`headJumpCharge`, up to `headJumpChargeMaximum`) and
  launches a dive (`isHeadJumping`), with its flat pose, slide-out landing and reduced control
  (`headJumpControlFactor`).
- Wall head jump: Grab held during a wall jump (`WALL_HEAD`, `WALL_HEAD_SLIDE`; `wallHeadJump`,
  `wallHeadJumpFallMaximumDistance`, `wallHeadJumpVerticalFactor`, `wallHeadJumpHorizontalFactor`).
  The wall jump without Grab (`WALL_UP`) stays and is what a Grab press during a wall jump now does too.
- Climb back head jump (`climbJumpBackHeadOnGrab`): jumping backwards off a climb as a dive. The
  ordinary climb back jump stays.

Footprint: about 470 references in 25 files, most in `SmartMovingClientState` (153), the config (78),
`SmartMovingJumper` (45), `MixinPlayerEntityRenderer` (48), `SmartMovingServer` (30) and the pose,
hitbox (`MixinAvatarDimensions*`), HUD and statistics code.

- Network: whichever state bits carried head-jump flags stay reserved, as for the Landing Roll (37-38)
  and the Sprint Vault (39), so later bits keep their positions.
- Config keys go; old config files keep loading with the keys ignored.
- `MixinClientPlayerEntity` blocked the vanilla jump while Grab and sprint were held, to start the
  charge. That block goes, so Grab + sprint + Jump is an ordinary jump. For the Windrunner wall run this
  means a run along a wall can now be entered holding Grab from takeoff.
- Gates that listed `isHeadJumping` (dodge, climbing entry, sliding, Windrunner wall run) drop the term.
