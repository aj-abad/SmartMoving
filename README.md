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
- **Head Jump** — Jump + sneak launched jump, fox movement
- **Swimming** — Swim / dive states + left-click swing animation
- **Wall Jump** — Mid-air wall jump with motion order / rotation correction
- **Vine Climbing** — Weeping / twisting vines ladder animation
- **Chain Climbing** *(prototype)* — Vertical iron / copper chains climb exactly like ladders, with a hand-over-hand pose; horizontal chains overhead work as ceiling handholds
- **Landing Roll** *(prototype)* — Press Dodge while falling to roll on landing: momentum is kept and fall damage is reduced
- **Sprint Vault** *(prototype)* — Hold Grab and sprint into a 1-block ledge, fence or wall to plant a hand and carry over it with forward and upward momentum
- **Windrunner armor set** — A wind monk's traversal set in the Breeze's colours: a headband that dashes, robes that glide, pants that run faster and boots that jump higher, double-jump and slow-fall (see below)
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
| Grab | `R` | Hold while climbing or grabbing an edge |
| Prone | `C` | Hold to crawl, or press when crawl toggle is enabled |
| Dodge / Windrunner Dash | Unbound | With the Windrunner Headband, a ground or air dash instead of the dodge (see below). Otherwise, press with a movement key to dodge in that direction; after landing there is a short cooldown before the next dodge. Press while falling (3+ blocks) to roll on landing in that direction; a shorter fall turns the press into a dodge on landing |
| Chain climb | — | Same as a ladder: face a vertical chain and move forward to climb, back to descend, Sneak to hold, step sideways to let go |
| Sprint vault | `R` + sprint | Hold Grab while sprinting straight at a 1-block ledge, fence or wall with clear space above and beyond it |
| Windrunner Glide | Mouse 4 | With the Windrunner Robes, open the glide in free air; press again or Sneak to fold it |
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
| Robes | Glide on the glide key; each fresh Dodge press while gliding is a boost, at the cost of durability |
| Pants | 15% faster movement |
| Boots | 10% stronger jumps (SmartMoving's own jumps included) and 10% less fall damage; double jump once per airtime; hold Sneak in free air to slow your fall; sneak-charged jumps build twice as fast |

- **Dashes** burst 24 blocks/s along your movement keys, or along your view without input. A ground dash
  launches as a low hop that slides on after landing, every half second. The hop still counts as the
  ground: no air dash, and a jump out of it is a ground jump that keeps the dash's momentum and your
  double jump. A ground dash that leaves its starting height by more than about half a block, off a cliff
  say, becomes an ordinary fall. Each dash darts the view wider (scaled by FOV effects), leaves a following
  gust and kicks up dust; a sliding ground dash slows the view and hand bob.
- **The double jump** kicks about 2.4 blocks high with the boots and resets your fall. The headband's
  dash and the double jump give way to SmartMoving while you climb, swim, fly, crawl, slide, roll or
  vault, and the double jump yields to wall jumps next to a wall.
- **Slow fall** eases a fall off gradually (a 60 blocks/s plunge takes about two seconds to settle near
  2.5 blocks/s) and lands without fall damage once settled.
- **The glide** needs three blocks of clear air below. It has responsive arcade handling (W/S pitch,
  A/D bank), stalls, wind, storms and lava updrafts, server-authoritative flight with client prediction,
  and a fan of phantom-membrane leaves on breeze-rod ribs. A settled glide covers about 4.8 blocks per
  block of altitude. Boosts add 8 blocks/s each, on a three-quarter-second cooldown. The robes have 240
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
./gradlew runWindrunnerClientTest # Windrunner client game tests in a disposable world
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
