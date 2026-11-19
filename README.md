# SmartMoving

> [English](README.md) | [한국어](README.ko.md)

A Minecraft mod that enriches character movement.

Fabric 26.3 port of the classic [SmartMoving](https://www.curseforge.com/minecraft/mc-mods/smart-moving) (1.7.10 Forge), using Minecraft's official names.

![Minecraft](https://img.shields.io/badge/Minecraft-26.3-62B47A?style=flat-square)
![Fabric](https://img.shields.io/badge/Fabric-0.19.5%2B-DBB69B?style=flat-square)
![Java](https://img.shields.io/badge/Java-25%2B-ED8B00?style=flat-square)
![License](https://img.shields.io/badge/License-GPL--3.0-blue?style=flat-square)

## Features

- **Climbing** — Block / ladder / vine / fence / iron bar self-climbing + ceiling climbing
- **Crawling** — 1-block-tall space entry, head rotation, crawl-climbing, slide ↔ crawl transitions
- **Sliding** — Sneak-launched sliding, post-flight landing slide, transition camera stabilization
- **Swimming** — Swim / dive states + left-click swing animation
- **Wall Jump** — Mid-air wall jump with motion order / rotation correction
- **Vine Climbing** — Weeping / twisting vines ladder animation
- **Chain Climbing** *(prototype)* — Vertical iron / copper chains climb exactly like ladders, with a hand-over-hand pose; horizontal chains overhead work as ceiling handholds
- **Windrunner armor set** — A wind monk's traversal set in the Breeze's colours: a headband that dashes, robes that glide, pants that run faster and double-jump, and boots that jump higher and soften falls (see below)
- **Multiplayer Sync** — Full self ↔ server ↔ remote state synchronization

## Requirements

- Minecraft `26.3`
- [Fabric Loader](https://fabricmc.net/use/installer/) `0.19.5+`
- [Fabric API](https://modrinth.com/mod/fabric-api)
- Java `25+`

## Installation

1. Install Fabric Loader
2. Add Fabric API to the `mods/` folder
3. Add the SmartMoving jar to the `mods/` folder
4. Launch Minecraft

For multiplayer, both server and client must have the mod installed.

## Controls

All bindings appear under **Options → Controls → Key Binds → Smart Moving**.

| Action | Default key | Usage |
|--------|-------------|-------|
| Grab | `R` | Hold while climbing or grabbing an edge; with the Windrunner Boots, hold while sprinting into a wall to wall run |
| Prone | `C` | Hold to crawl, or press when crawl toggle is enabled |
| Dodge / Windrunner Dash | Unbound | With the Windrunner Headband, a ground or air dash instead of the dodge (see below). Otherwise, press with a movement key to dodge in that direction; after landing there is a short cooldown before the next dodge |
| Chain climb | — | Same as a ladder: face a vertical chain and move forward to climb, back to descend, Sneak to hold, step sideways to let go |
| Toggle Smart Moving | `F9` | Enable or disable Smart Moving; the new state shows above the hotbar |

## Windrunner armor set

Upgraded leather armor (leather's protection, armor enchantments, repaired with breeze rods; the robes
also take leather). Finding a breeze rod unlocks the recipes; in creative the set follows the leather
armor in the Combat tab.

```text
Headband  leather helmet + breeze rod                 (shapeless)
Robes     P B P      P = phantom membrane   B = breeze rod
          P C P      C = leather chestplate
Pants     leather leggings + 2 breeze rods            (shapeless)
Boots     leather boots + 2 breeze rods               (shapeless)
```

| Piece | Ability |
|---|---|
| Headband | Dash with the Dodge key: along the ground, or once per airtime in the air. Replaces the dodge while worn |
| Robes | Glide: press Jump in free air once the double jump is spent (first air jump without the pants); Sneak folds it. Each fresh Dodge press while gliding is a boost, at the cost of durability |
| Pants | 15% faster movement; double jump once per airtime |
| Boots | 10% stronger jumps (SmartMoving's own jumps included); falls top out at 30 blocks/s (vanilla reaches 78) and only hurt past six blocks; a fall that would kill you from nine hearts or more leaves half a heart; wall run with Grab; sneak-charged jumps build twice as fast |

- **Dashes** burst 24 blocks/s along your movement keys, or along your view without input. A ground dash
  launches as a low hop that slides on after landing, every half second. The hop still counts as the
  ground: no air dash, and a jump out of it is a ground jump that keeps the dash's momentum and your
  double jump. A ground dash that leaves its starting height by more than about half a block, off a cliff
  say, becomes an ordinary fall. A ground dash steps up small ledges, like a dirt path's edge onto grass,
  even mid-hop. Each dash darts the view wider (scaled by FOV effects), leaves a following gust and kicks
  up dust while it carries you; a dash into a wall leaves none, and hitting one mid-dash ends the trail.
  You don't see your own gusts in first person. A sliding ground dash slows the view and hand bob.
- **The double jump** kicks about 2 blocks high (2.4 with the boots) and resets your fall. The headband's
  dash and the double jump give way to SmartMoving while you climb, swim, fly, crawl or slide,
  and the double jump yields to wall jumps next to a wall.
- **The wall run** needs the boots, Grab held while sprinting, and a wall at least two blocks tall.
  Hit it nearly head-on (70 degrees or more to its face) to run up it, about four blocks, slowing as
  you go; reach the top and you step onto it. Hit it at a glancing angle (40 degrees or less) in the
  air to run along it for up to 1.5 seconds, sinking slowly the whole way. Angles in between do
  nothing. Jump during a run to kick off the wall, which also gives back the pants' double jump. You
  can chain runs between walls, but not run the same wall twice before landing; holding Grab from
  takeoff works for a run along a wall.
  Runs have their own poses for every viewer (feet on the wall and hands reaching up, or leaning off
  the wall at a sprint), and in first person the view and hands bob as you run.
- **The boots' fall save** works like a totem for falls only: with nine hearts or more when you land, a
  fall that would kill you leaves half a heart instead, and a held totem is not used up. Absorption hearts
  don't count toward the nine.
- **The glide** flies like an endgame glider: it cruises at about 36 blocks/s, sinks about 2.5
  blocks/s (some 14 blocks forward per block down), banks into a half turn in about two seconds, dives
  to about 75 blocks/s and settles by itself when you let go. Pulling up stops at the edge of a stall
  rather than going over it. It needs three blocks of clear air below; the robes catch the air as they
  open, turning your fall straight into a shallow glide at about 20 blocks/s that builds to cruise
  over a couple of seconds; a faster fall is braked to about 20 blocks/s within half a second. W/S pitch, A/D bank; wind, storms and lava updrafts still push it
  about, with server-authoritative flight and client prediction, and a fan of phantom-membrane leaves
  on breeze-rod ribs. Boosts add about 18 blocks/s each, leaving white shockwave rings and a dash's gusts, on a
  three-quarter-second cooldown. Wall crashes hurt like an elytra's. The robes have 240
  durability: opening costs one point, loaded fabric wears every five seconds, and overspeed, deep stalls
  and boosts add wear.

Textures come from `scripts/GenerateWindrunnerTextures.java`, which adapts the mage-robe baseline in
`art/windrunner/` to the Breeze palette. The optional Wind mod, when installed,
supplies the glide's horizontal breeze.

## Build

```bash
git clone https://github.com/View-Studio/SmartMoving.git
cd SmartMoving
./gradlew build
```

The built jar will be located in `build/libs/`.

```bash
./gradlew runClient   # Run client
./gradlew runServer   # Run server
./gradlew windrunnerPhysicsTest   # Windrunner glide and movement simulations (also run by build)
./gradlew runWindrunnerClientTest # client game test harness in a disposable world; add tests under src/windrunnerClientTest
```

## Credits

| Version | Loader | Maintainer |
|---------|--------|------------|
| 26.3    | Fabric | [View-Studio](https://github.com/View-Studio) |
| 26.2    | Fabric | [View-Studio](https://github.com/View-Studio) |
| 1.21.1  | Fabric | [View-Studio](https://github.com/View-Studio) |
| 1.12.2  | Forge  | [doch2](https://github.com/doch2/SmartMovingReboot) |
| 1.7.10  | Forge  | [Divisor](https://www.curseforge.com/minecraft/mc-mods/smart-moving) *(original)* |

## Support ☕

If SmartMoving has been useful, a small tip helps fuel the next project. 😊

| Platform | Audience  | Link |
|----------|-----------|------|
| Ko-fi    | Worldwide | [ko-fi.com/viewstudio](https://ko-fi.com/viewstudio) |

## Bug Reports

Please report bugs on [GitHub Issues](https://github.com/View-Studio/SmartMoving/issues). Including reproduction steps and logs helps with faster resolution.

## License

[GPL-3.0-or-later](LICENSE) — inherits the original SmartMoving license.
