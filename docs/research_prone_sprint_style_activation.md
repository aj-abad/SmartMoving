# Research: Prone Sprint-Style Activation + Double-Tap Shift

## Requests
1. Make prone activation behave like vanilla sprinting: pressing and releasing the prone key while
   moving keeps prone engaged until the player stops moving (no need to hold the key the whole time).
2. Also allow entering prone by double-tapping the sneak (shift) key, similar to double-tap-forward
   sprinting.

## Current Structure

### Prone input (`SmartMovingClientState.tickEssential`)
- Key edge tracking (L1407-1409): `proneJustPressed = curPronePressed && !prevProneKeyPressed`
  (`SmartMovingKeys.prone`, dedicated keybind, default unbound / user-assigned).
- Activation (L1490-1500):
  ```java
  boolean proneHeld0 = SmartMovingKeys.prone.isDown();
  if (cfg0.isCrawlToggleEnabled() && proneJustPressed && player.onGround()) {
      crawlToggled = !crawlToggled;
  }
  boolean proneRequested0 = cfg0.isCrawlToggleEnabled() ? crawlToggled : proneHeld0;
  ```
- Two existing modes, selected by `SmartMovingConfig.crawlToggle` (`isCrawlToggleEnabled()` =
  `crawlToggle && enabled`):
  - **Hold** (default, `crawlToggle=false`): `proneRequested0 = proneHeld0` — must hold the key the
    entire time.
  - **Toggle** (`crawlToggle=true`): `proneJustPressed` flips `crawlToggled`; persists regardless of
    movement until pressed again. Also entangled with sneak/climb interactions further down
    (`willStartCrawl`/`willStopCrawl` block, L3126-3205) which forcibly clears `crawlToggled` on
    ceiling-clear-and-jump/sneak-release edges (`willStopCrawlStartSneak`), and with climb-holding
    (`wantClimbHolding`, L1844-1848) and shallow-water crawl transitions.
- Neither mode currently auto-releases based on movement state — that's the missing piece for
  sprint-style behavior.

### Reference pattern: existing double-click state machine (wall jump)
`SmartMovingJumper.updateWallJumpState` (L611-649) + `SmartMovingConfig.wallJumpDoubleClick` /
`wallJumpDoubleClickTicks` is the established double-tap pattern in this codebase:
```java
if (cfg.wallJumpDoubleClick) {
    if (canWallJumping) {
        if (sm.jumpKeyStartPressed) {
            if (sm.wallJumpCount == 0) sm.wallJumpCount = (int) Math.ceil(cfg.wallJumpDoubleClickTicks);
            else { sm.triggerWallJumping = true; sm.wallJumpCount = 0; }
        } else if (sm.wallJumpCount > 0) sm.wallJumpCount--;
    } else sm.wallJumpCount = 0;
}
```
A counter field starts the double-click window on first press; a second press before the counter
decays to 0 fires the action.

### Sneak key edges
`sneakKeyStartPressed`/`sneakKeyStopPressed` (L1388-1390) are already computed early in
`tickEssential`, before the prone activation block — usable as-is for a sneak double-tap trigger.

### Movement input
`player.input.getMoveVector()` (`.x`/`.y`) is already used elsewhere in the same method
(`_movePressed3a`, L1766-1768) to detect forward/strafe input. No import needed — matches existing
inline usage style (no local `Vec2` variable stored).

### `mustCrawl` / ceiling forcing (L1564+)
Ceiling-constrained crawling is a separate, stronger force computed later from `isCrawling` state —
unaffected by how `proneRequested0` was derived. Our new latch only changes the *voluntary*
activation signal, same role as `proneHeld0` today, so it's safe to extend without touching the
ceiling logic.

## Design

### 1. Sprint-style latch (new fields, `SmartMovingClientState`)
- `proneLatched` (boolean): true while prone is engaged without the key held.
- `proneLatchMoving` (boolean): true once movement has been observed during the current latch
  session — gates the auto-release edge so a latch started while stationary doesn't instantly cancel
  itself the same tick.

Only active when `!cfg0.isCrawlToggleEnabled()` (toggle mode keeps its existing, separate behavior
untouched):
- `proneJustPressed` while not yet latched and moving (`isMoving0`) and grounded → `proneLatched =
  true`, `proneLatchMoving = true` (mirrors vanilla: sprint only starts with forward impulse).
- `proneJustPressed` while already latched → cancel (`proneLatched = false`) — explicit early
  stand-up, since prone has no separate "stop" key.
- Every tick while latched: if moving, mark `proneLatchMoving = true`; if not moving and
  `proneLatchMoving` was true (i.e. the player *stopped* moving, not just "isn't moving this
  instant") → release the latch. Using the stop-edge (not the level) means a latch started while
  stationary (via double-tap, see below) persists until the player actually moves and then stops.
- `proneRequested0` becomes `proneHeld0 || proneLatched` in hold mode (holding the key still works
  unchanged; tapping while moving now also works and self-releases).

### 2. Double-tap sneak → prone (new config, mirrors wall-jump double-click)
- `SmartMovingConfig.sneakDoubleTapProne` (default `true`), `sneakDoubleTapProneTicks` (default `4F`
  ticks window).
- New counter field `sneakDoubleTapCount` on `SmartMovingClientState` (same shape as
  `wallJumpCount`).
- Gated on `player.onGround()` only (matches the existing toggle-mode activation gate at L1497 — no
  climb/swim/dive/fly guard needed since those are separate movement modes with their own key
  handling).
- On a detected double-tap, toggle `proneLatched` (same as re-pressing the dedicated prone key) and
  seed `proneLatchMoving` from current `isMoving0` — so double-tapping while already moving behaves
  like the sprint-style tap, and double-tapping while stationary drops the player prone in place
  until they later move and stop.
- Only runs when `!isCrawlToggleEnabled()`, same scope as the latch itself.

### 3. Reset handling
Add `proneLatched = false; proneLatchMoving = false; sneakDoubleTapCount = 0;` to the existing
state-reset block (L3255-3284) alongside the other prone/crawl fields.
