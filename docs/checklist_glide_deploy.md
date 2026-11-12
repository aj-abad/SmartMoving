# Checklist: glide deploy on Jump, and the post-deploy nosedive

- [x] Research (`docs/research_glide_deploy.md`)
- [x] Remove the glide key; open the robes on an otherwise unspent air Jump
- [x] Deploy catch: capped pitch, velocity along the trim flight path
- [x] Simulation checks for hands-off deploys
- [x] README (English and Korean), lang
- [x] Build, test, deploy

## Follow-up: opening brake

- [x] Research (stall simulated and rejected; brake design)
- [x] `State.brakeTicks`, set on deploy, in copy and the state payload
- [x] Brake step in `WindrunnerGlideModel.tick`
- [x] Tune in simulation; checks for fast-fall deploys
- [x] Verify in game, README, deploy
