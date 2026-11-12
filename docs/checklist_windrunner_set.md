# Checklist: Windrunner traversal armor set

- [x] Research and checklist (`docs/research_windrunner_set.md`)
- [x] Common: glide physics, glide model, wear, visuals, air sampling, glide state machine
- [x] Common: armor material and items, moves, payloads, registration, sounds, mixins
- [x] Data: recipes, recipe advancements, item tags, repair tags
- [x] Client: glide and Windrunner controls, glide key, wind sound, camera, FOV dart, bob damping
- [x] Client: renderer mixins, flight pose, membrane and ribs, sash and headband models, layer
- [x] Assets: equipment layers, item icons, membrane, ribs, sash, sounds, lang, texture generator
- [x] First-party SmartMoving hooks: dodge override, charge rate, jump scaling, state gates
- [x] Tests: physics simulation source set and client game tests
- [x] Build, run both test suites
- [x] README (English and Korean)
- [x] Aircraft: restore the firework wingsuit state from `6005762` (Aircraft commit `52cea9e`, version 0.42.0)
- [x] Interop: note Jump and Sneak presses at `KeyMapping.click` so both mods see them

## Follow-up: slow fall on the robes

- [x] Research note (`docs/research_windrunner_set.md`, "slow fall moves to the robes")
- [ ] Gate `canSlowFall` on the robes; add `WindrunnerArmor.robes`
- [ ] Client test: slow fall with the robes alone, none with the boots alone
- [ ] README (English and Korean)
- [ ] Build, run both test suites, deploy
