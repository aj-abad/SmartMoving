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

## Outcome

- State bit 20 (`isHeadJumping`) is reserved. Bit 35 (`wasSelfSlideFire`) is reserved too: only the
  head-jump render branches read it, so it had no readers left.
- Jump type ids 3, 9, 10, 12 and 14 (head-jump only) are gone; the rest keep their numbers. They are
  not sent over the network.
- Config keys no longer read or written: `move.jump.head.charge`, `move.forward.jump.control.factor`,
  `move.forward.jump.charge.maximum`, `move.fall.head.damage.start.distance`,
  `move.fall.head.damage.factor`, `move.jump.wall.head` and its three sub-keys,
  `move.climb.jump.back.head`, `move.jump.climb.back.head` and its four sub-keys. The server feature
  block code that disabled head jumping now does nothing.
- Knock-on behaviour:
  - A wall jump with Grab held is the up wall jump (2-block fall limit, was 3 for the head variant).
  - The climb back jump is the ordinary one with or without Grab (the default used to dive without it).
  - A slide that leaves a ledge used to turn into a near-dragless dive; it now carries on in the air
    until Sneak is released, speed drops or it has fallen more than 3 blocks.
  - Landing from a slide slides on only with Grab held (the "was diving" case is gone).
  - Rope sliding's body-yaw fade used head-jump fields, renamed `smRopeSlideYaw_*`; unchanged.
