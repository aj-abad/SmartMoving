# Research: Config Toggle Cleanup (Speed Keys, Difficulty Presets, On-Screen Status)

## Requests

1. Remove the speed increase/decrease feature.
2. Remove the difficulty options.
3. Show the Smart Moving status (the result of the toggle key) as the on-screen overlay message,
   the way the debug stick reports its property, instead of a chat message.

## Current Structure

### 1. Speed increase/decrease ("user speed")

A port of the 1.7.10 `speedIncrease`/`speedDecrease` buttons and `_speedUser*` properties. The
player's speed is `speedFactor × (1 + speedUserFactor)^exponent`, the exponent stored per player on
the server.

| Piece | Location |
| --- | --- |
| Key mappings `speedIncrease` (O) / `speedDecrease` (I) | `SmartMovingKeys` L8-17 (javadoc), L27-28, L55-66 |
| Key handling, gated by a hardcoded `userSpeedEnabled = false` since Session 35 (`docs/fix/integration_test_bugs.md` BUG-6) | `SmartMovingClientState.tickEssential` L1455-1482 |
| `SpeedChangePayload` (C2S + S2C) and its two registrations | `SmartMovingNetwork` L105-127, L205, L214 |
| Server receiver | `SmartMoving.registerServerReceivers` L101-104 |
| `processSpeedChangePacket` (Creative + `speedUser` gate, per-player exponent, save, reply), `adminChangeSpeed` (no callers), `logSpeedState` (only used by `adminChangeSpeed`) | `SmartMovingServer` L465-478, L514-517, L542-566 |
| Client receiver: applies `changeSpeed`, writes "speed changed/reset" or "no rights" to chat | `SmartMovingClient` L705-733 |
| Fields `speedUser`, `speedUserFactor`, `speedUserExponent`, `playerSpeedExponents`; keys `move.speed.user`, `move.speed.user.factor`, `move.speed.user.exponent`, `move.speed.users.exponents` | `SmartMovingConfig` L418-437, L1604-1607, L1774-1777 |
| `getUserSpeedFactor`, `changeSpeed`, `changeSingleSpeed`, `getSpeedPercent`, per-player substitution in `toArray(String)`, `parseIntegerMap`/`serializeIntegerMap` (only used for the map), `HashMap`/`Map` imports | `SmartMovingConfig` L7-8, L1565-1597, L1939-1944, L2108-2132, L2152-2184 |
| Movement: `getConfigSpeedFactor(player, cfg) = cfg.speedFactor × (isCreative ? getUserSpeedFactor() : 1F)`; `isCreative` and the `player` parameter exist only for this gate (B-6) | `SmartMovingMover` L30-79, L111-119, L182-193, L236; `MixinLivingEntityClient` L347 |
| Stale doc on the disabled `getSpeed` hook mentioning the Creative gate | `MixinLivingEntityClient` L680-681 |
| Lang: `key.smartmoving.speed_increase`, `key.smartmoving.speed_decrease`, `smartmoving.message.speed.*` (4) | `en_us.json` L8-9, L22-23, L28-29 |
| README Controls rows "Increase speed" / "Decrease speed" | `README.md` Controls |

Observations:

- The keys have done nothing since Session 35; they only show up in Controls and the README.
- `speedUser` defaults to `false` and the exponent to `0`, so `getUserSpeedFactor()` returns `1F`
  for every default config. Removing it does not change movement.
- `speedFactor` (`move.speed.factor`) is a separate, static multiplier read from the config file.
  It is not an increase/decrease control and stays. `getConfigSpeedFactor` becomes
  `cfg.enabled ? cfg.speedFactor : 1F`, which is the original's formula without the user factor.
- Protocol: clients never send `speed_change` (the gate is hardcoded off) and the server only sends
  it as a reply, so dropping the payload keeps old and new builds compatible with each other.
- Saved configs keep their `move.speed.user*` lines until the next save; `readFrom` ignores them
  and `save()` rewrites the file from `writeTo`, so they disappear on the next toggle. Old
  `key_key.smartmoving.speed_*` lines in `options.txt` are ignored by vanilla.

### 2. Difficulty presets

In 1.7.10 the toggle key cycled `disabled → Easy → Medium → Hard` (Creative: `c`), and each key
changed many property values. `docs/fix/focus_05_config_toggle.md` (Sessions 13-23) replaced that
with a 2-state `disabled ↔ enabled` toggle whose values are the Easy preset baked into the field
defaults. The key-cycling machinery was left in place "because it is harmless", along with comments
promising to restore Medium/Hard later (`focus_09_difficulty_presets.md`, never written) and to
clean up later (`focus_10_config_toggle_cleanup.md`, never written).

Leftovers:

| Piece | Location |
| --- | --- |
| `DEFAULT_KEYS`, `configKeys`, `toggler`, plus tombstone comments (H-18/H-19/H-20) about deleted preset code and restoring presets | `SmartMovingConfig` L1384-1422 |
| `updateToggler`, `toggle` (key cycling), `setKeys` (no callers), more tombstones | `SmartMovingConfig` L2024-2106 |
| `enabled` javadoc: "derived as `toggler != -1`, direct writing prohibited" | `SmartMovingConfig` L1218-1223 |
| H-15 comment about `initializeForGameIfNeccessary` switching to `{"e","m","h"}` | `SmartMovingClientState.tickEssential` L1376-1388 |
| `logConfigState` javadoc about reintroducing preset branches | `SmartMovingServer` L482-497 |
| Key label `key.smartmoving.config_toggle` = "Toggle Config" (named for cycling configs); the README already calls it "Toggle Smart Moving" | `en_us.json` L7 |

Existing bug in the leftover machinery: `toggler` starts at `-2` and `load()` never syncs it with
the loaded `move.enabled`. When the saved config has `move.enabled=false`, the first toggle press
after launch moves `toggler` from `-2` to `-1`, so Smart Moving stays disabled and the player sees
"Smart Moving disabled" again; only the second press enables it. A plain `enabled = !enabled`
removes the machinery and this bug together.

Kept: the field defaults annotated "Easy 1:1". They are simply the defaults now, and their comments
explain where the values come from.

### 3. Status message

- Toggle key handling (`SmartMovingClientState.tickEssential` L1437-1453): when
  `Config == INSTANCE` it calls `INSTANCE.toggle()` and `player.sendSystemMessage(...)` with
  "Smart Moving enabled/disabled"; otherwise it asks the server (`ConfigChangePayload`).
- The server always answers `ConfigChangePayload` with a refusal, which the client writes to chat as
  "You have no rights..." (`SmartMovingClient` L690-703). `SmartMovingConfig.Config` is never
  switched to `SERVER_CONFIG` any more (2026-05-05 change in `processConfigContentPacket`), so this
  branch is unreachable today, but it is the other possible toggle outcome.

Vanilla 26.3 (`vanilla-decompiled/26.3`):

- `Player.sendOverlayMessage(Component)` is the overlay API. `LocalPlayer.sendOverlayMessage` calls
  `gui.chatListener().handleOverlay(message)`, which calls `gui.hud.setOverlayMessage(message,
  false)` (60 ticks, fades out, drawn centered above the hotbar with a backdrop) and queues it for
  the narrator.
- `DebugStickItem.message` calls `ServerPlayer.sendOverlayMessage`, which sends
  `ClientboundSystemChatPacket(message, overlay = true)` and ends in the same `Hud` overlay. Calling
  `LocalPlayer.sendOverlayMessage` on the client therefore looks exactly like the debug stick.
- `LocalPlayer.sendSystemMessage` calls `chatListener().handleSystemMessage(message, true)`; the
  flag is `remote`, and the message goes to chat. That is today's behavior.
- The bed's rejections ("You can sleep only at night", monsters nearby) use the overlay. In 26.3
  "Respawn point set" (`ServerPlayer.setRespawnPosition`) actually goes to chat, so the debug stick
  is the exact reference.
- The block-code handler (`ClientReceiveMessageEvents.ALLOW_GAME`) already ignores overlay messages.

## Plan

1. Remove the speed feature end to end: keys, client key handling, payload, server and client
   receivers, server methods, config fields/keys/methods and map helpers, the Creative gate and the
   now-unused `player` parameter of `getConfigSpeedFactor`, lang keys, README rows.
2. Remove the difficulty machinery: `toggle()` becomes `enabled = !enabled; save();`; delete
   `DEFAULT_KEYS`, `configKeys`, `toggler`, `updateToggler`, `setKeys` and the preset comments;
   relabel the key "Toggle Smart Moving" (same `config_toggle` ID, so existing bindings survive).
3. Send the toggle result with `player.sendOverlayMessage(...)`, and the server's refusal the same
   way so both outcomes of the key use the overlay. Mention it in the README Controls row.
4. Build, then check in game: F9 shows "Smart Moving enabled/disabled" above the hotbar and not in
   chat; the Controls screen lists Grab, Prone, Dodge, Toggle Smart Moving only; the state survives a
   restart, including a restart while disabled (first press enables).
