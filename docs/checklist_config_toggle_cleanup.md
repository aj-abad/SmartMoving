# Checklist: Config toggle cleanup (speed keys, difficulty presets, on-screen status)

Reference: `docs/research_config_toggle_cleanup.md`

## 1. Remove speed increase/decrease
- [x] `SmartMovingKeys`: remove `speedIncrease`/`speedDecrease` fields, registrations and the O/I
      javadoc lines
- [x] `SmartMovingClientState.tickEssential`: remove the IMPL-05 speed key block
- [x] `SmartMovingNetwork`: remove `SpeedChangePayload` and both registrations; update the header
- [x] `SmartMoving`: remove the SpeedChange server receiver
- [x] `SmartMovingServer`: remove `processSpeedChangePacket`, `adminChangeSpeed`, `logSpeedState`
- [x] `SmartMovingClient`: remove the SpeedChange client receiver
- [x] `SmartMovingConfig`: remove `speedUser*` fields, `playerSpeedExponents`, their read/write
      keys, `getUserSpeedFactor`, `changeSpeed`, `changeSingleSpeed`, `getSpeedPercent`, the
      per-player `toArray(String)` overload, `parseIntegerMap`/`serializeIntegerMap`, `getInt`
      (only read the exponent), unused imports
- [x] `SmartMovingMover`: `getConfigSpeedFactor(cfg)` = `cfg.enabled ? cfg.speedFactor : 1F`;
      remove `isCreative` and unused imports; update the call sites (Mover, `MixinLivingEntityClient`)
      and stale docs
- [x] `en_us.json`: remove `key.smartmoving.speed_*` and `smartmoving.message.speed.*`
- [x] `README.md`: remove the Increase/Decrease speed rows
- [x] Build + commit

## 2. Remove difficulty presets
- [x] `SmartMovingConfig`: `toggle()` flips `enabled` and saves; remove `DEFAULT_KEYS`,
      `configKeys`, `toggler`, `updateToggler`, `setKeys` and the preset comments; fix the
      `enabled` javadoc
- [x] `SmartMovingClientState.tickEssential`: remove the H-15 preset comment block
- [x] `SmartMovingServer.logConfigState`: drop the preset note from the javadoc
- [x] `en_us.json`: relabel `key.smartmoving.config_toggle` to "Toggle Smart Moving"
- [x] Build + commit

## 3. On-screen status
- [x] `SmartMovingClientState.tickEssential`: send the toggle result with `sendOverlayMessage`
- [x] `SmartMovingClient`: send the server's "no rights" reply with `sendOverlayMessage`
- [x] `README.md`: mention the on-screen status in the Toggle Smart Moving row
- [x] Build + commit

## 4. Verification
- [x] `pnpm build` compilation success
- [x] Leftover scan: no references to removed symbols, keys or payloads (source and built jar)
- [ ] In-game user verification: F9 shows "Smart Moving enabled/disabled" above the hotbar and not in chat;
      Controls lists Grab, Prone, Dodge, Toggle Smart Moving only; after quitting while disabled,
      the first F9 press enables Smart Moving
