# Checklist: Config toggle cleanup (speed keys, difficulty presets, on-screen status)

Reference: `docs/research_config_toggle_cleanup.md`

## 1. Remove speed increase/decrease
- [ ] `SmartMovingKeys`: remove `speedIncrease`/`speedDecrease` fields, registrations and the O/I
      javadoc lines
- [ ] `SmartMovingClientState.tickEssential`: remove the IMPL-05 speed key block
- [ ] `SmartMovingNetwork`: remove `SpeedChangePayload` and both registrations; update the header
- [ ] `SmartMoving`: remove the SpeedChange server receiver
- [ ] `SmartMovingServer`: remove `processSpeedChangePacket`, `adminChangeSpeed`, `logSpeedState`
- [ ] `SmartMovingClient`: remove the SpeedChange client receiver
- [ ] `SmartMovingConfig`: remove `speedUser*` fields, `playerSpeedExponents`, their read/write
      keys, `getUserSpeedFactor`, `changeSpeed`, `changeSingleSpeed`, `getSpeedPercent`, the
      per-player `toArray(String)` overload, `parseIntegerMap`/`serializeIntegerMap`, unused imports
- [ ] `SmartMovingMover`: `getConfigSpeedFactor(cfg)` = `cfg.enabled ? cfg.speedFactor : 1F`;
      remove `isCreative` and unused imports; update the call sites (Mover, `MixinLivingEntityClient`)
      and stale docs
- [ ] `en_us.json`: remove `key.smartmoving.speed_*` and `smartmoving.message.speed.*`
- [ ] `README.md`: remove the Increase/Decrease speed rows
- [ ] Build + commit

## 2. Remove difficulty presets
- [ ] `SmartMovingConfig`: `toggle()` flips `enabled` and saves; remove `DEFAULT_KEYS`,
      `configKeys`, `toggler`, `updateToggler`, `setKeys` and the preset comments; fix the
      `enabled` javadoc
- [ ] `SmartMovingClientState.tickEssential`: remove the H-15 preset comment block
- [ ] `SmartMovingServer.logConfigState`: drop the preset note from the javadoc
- [ ] `en_us.json`: relabel `key.smartmoving.config_toggle` to "Toggle Smart Moving"
- [ ] Build + commit

## 3. On-screen status
- [ ] `SmartMovingClientState.tickEssential`: send the toggle result with `sendOverlayMessage`
- [ ] `SmartMovingClient`: send the server's "no rights" reply with `sendOverlayMessage`
- [ ] `README.md`: mention the on-screen status in the Toggle Smart Moving row
- [ ] Build + commit

## 4. Verification
- [ ] `pnpm build` compilation success
- [ ] Leftover scan: no references to removed symbols, keys or payloads
- [ ] In-game check: F9 shows "Smart Moving enabled/disabled" above the hotbar and not in chat;
      Controls lists Grab, Prone, Dodge, Toggle Smart Moving only; after quitting while disabled,
      the first F9 press enables Smart Moving
