# Research: fall rework (Landing Roll removal, Windrunner fall pieces)

## Landing Roll removal

The Landing Roll prototype (`docs/research_movement_prototypes.md`) is removed outright: the airborne
Dodge press that armed a roll, `SmartMovingRoller`, its config (`move.roll*`), the rolling state, its
pose, model and renderer branches, and the server's fall damage factor.

- Network state bits 37 (`isRolling`) and 38 (`rollArmed`) stay reserved rather than renumbered, so
  later bits keep their positions.
- An airborne Dodge press no longer arms anything. With the Windrunner Headband it is still the air dash.
- Old config files that still carry `move.roll*` keys load fine; unknown keys are ignored.

## Windrunner set

| Piece | Before | After |
|---|---|---|
| Robes | Glide, boosts, sneak slow fall | Glide, boosts |
| Pants | +15% speed | +15% speed, double jump |
| Boots | Jump +10%, fall cap, +3 safe fall, double jump, faster charge | Jump +10%, fall cap, +3 safe fall, faster charge, lethal fall save |

- Slow fall goes entirely: `SLOW_FALL_*`, the start/stop actions and the server's fall-distance reset.
  Action ids stay numbered as they are, with 3 and 4 unused.
- Double jump: `canDoubleJump` checks the pants. The boots' jump strength still scales it when both
  are worn.
- Lethal fall save: a fall (`DamageTypeTags.IS_FALL`) that would kill a player wearing the boots, who
  had at least 18 health (nine hearts) when the hit landed, leaves them on 1 health (half a heart).
  - Hook: `LivingEntity.hurtServer` HEAD records health before the hit; `checkTotemDeathProtection`
    HEAD returns true after setting health to 1. Death is only checked after damage is applied, so
    this sees the final, post-reduction damage.
  - It runs before the totem's own check, so a held totem is not spent on a fall the boots survive.
  - Absorption hearts do not count toward the nine.
