# Checklist: Prone sprint-style activation + double-tap shift

Reference: `docs/research_prone_sprint_style_activation.md`

## 1. Config fields
- [x] `SmartMovingConfig`: add `sneakDoubleTapProne` (default true) + `sneakDoubleTapProneTicks`
      (default 4F) near `crawlToggle`
- [x] `SmartMovingConfig`: load/save properties for both new fields

## 2. State fields
- [x] `SmartMovingClientState`: add `proneLatched`, `proneLatchMoving`, `sneakDoubleTapCount` near
      `proneJustPressed`/`prevProneKeyPressed`
- [x] `SmartMovingClientState`: add resets for the three new fields to the tick-reset block

## 3. Activation logic (`tickEssential`, hold-mode branch only)
- [x] Compute `isMoving0` from `player.input.getMoveVector()`
- [x] Double-tap counter update for `sneakKeyStartPressed` (mirrors `wallJumpCount` pattern),
      toggles `proneLatched` on second tap within the window
- [x] `proneJustPressed` toggles `proneLatched` on/off (start when moving+grounded, cancel if
      already latched)
- [x] Auto-release: `proneLatched` clears on the moving→stopped edge (`proneLatchMoving` gate),
      not simply whenever not currently moving
- [x] `proneRequested0 = cfg0.isCrawlToggleEnabled() ? crawlToggled : (proneHeld0 || proneLatched)`

## 4. Verification
- [x] `pnpm build` compilation success
- [ ] In-game user verification: tap-and-release prone while walking keeps crawling until stopped;
      tapping prone again cancels early; double-tap shift while moving and while stationary both
      work; holding the prone key still works unchanged; toggle mode (`crawlToggle=true`) unaffected
- [x] Commit
