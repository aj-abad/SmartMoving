# SmartMoving

> [English](README.md) | [한국어](README.ko.md)

마인크래프트 캐릭터 무빙을 풍부하게 만드는 모드입니다.

원본 [SmartMoving](https://www.curseforge.com/minecraft/mc-mods/smart-moving) (1.7.10 Forge) 의 Fabric 1.21.1 포팅.

![Minecraft](https://img.shields.io/badge/Minecraft-1.21.1-62B47A?style=flat-square)
![Fabric](https://img.shields.io/badge/Fabric-0.19.2%2B-DBB69B?style=flat-square)
![Java](https://img.shields.io/badge/Java-21-ED8B00?style=flat-square)
![License](https://img.shields.io/badge/License-GPL--3.0-blue?style=flat-square)

## Features

- **Climbing** — 블록 / 사다리 / 덩굴 / 울타리 / 철창 자체등반 + 천장 등반
- **Crawling** — 1칸 공간 진입, 머리 회전, 크롤 클라이밍, 슬라이드 ↔ 크롤 전환
- **Sliding** — Sneak 자체 슬라이딩, 비행 착지 슬라이딩, 전환 카메라 안정화
- **Head Jump** — 점프 + Sneak 발사형 점프, 여우무빙
- **Swimming** — Swim / Dive 상태 + 좌클릭 swing 애니메이션
- **Wall Jump** — 공중 벽 점프 + 식 순서 / 회전 보정
- **Vine Climbing** — Weeping / Twisting vines 사다리 애니메이션
- **Chain Climbing** *(프로토타입)* — 세로 사슬(철/구리)을 사다리와 똑같이 등반, 손을 번갈아 잡는 전용 자세; 머리 위 가로 사슬은 천장 등반 손잡이
- **Sprint Vault** *(프로토타입)* — Grab 키를 누른 채 달리기로 1칸 턱 / 울타리 / 담장에 돌진하면 손을 짚고 앞·위 관성을 유지하며 넘어가기
- **Windrunner 방어구 세트** — 브리즈 색상의 바람 수도승 이동 세트: 대시하는 머리띠, 활공하는 로브, 더 빨리 달리고 이단 점프를 하는 바지, 더 높이 뛰고 낙하를 버텨 내는 장화 (아래 참조)
- **Multiplayer Sync** — self ↔ server ↔ remote 상태 동기화

## Requirements

- Minecraft `1.21.1`
- [Fabric Loader](https://fabricmc.net/use/installer/) `0.19.2+`
- [Fabric API](https://modrinth.com/mod/fabric-api)
- Java `21`

## Installation

1. Fabric Loader 설치
2. Fabric API 를 `mods/` 폴더에 추가
3. SmartMoving jar 를 `mods/` 폴더에 추가
4. 마인크래프트 실행

멀티플레이는 서버 / 클라이언트 양쪽 모두 설치 필요.

## Windrunner 방어구 세트

가죽 방어구를 브리즈 막대로 강화한 세트입니다 (가죽과 같은 방어력, 방어구 마법 부여 가능, 브리즈
막대로 수리; 로브는 가죽으로도 수리). 브리즈 막대를 얻으면 제작법이 열리고, 크리에이티브에서는 전투 탭의
가죽 방어구 뒤에 있습니다. 로브 제작에는 가죽 흉갑, 브리즈 막대 1개, 팬텀 막 4개가 필요하고, 나머지는
각 가죽 방어구에 브리즈 막대(머리띠 1개, 바지·장화 2개)를 더합니다.

| 부위 | 능력 |
|---|---|
| 머리띠 | Dodge 키로 대시: 지상 대시, 또는 공중에서 체공당 한 번. 착용 중에는 Dodge 대신 발동 |
| 로브 | 이단 점프를 쓴 뒤 공중에서 점프를 누르면 활공 (바지가 없으면 첫 공중 점프); Sneak 로 접기. 활공 중 Dodge 키를 새로 누를 때마다 내구도를 소모해 가속 |
| 바지 | 이동 속도 15% 증가, 체공당 이단 점프 한 번 |
| 장화 | 점프력 10% 증가 (SmartMoving 점프 포함), 낙하 속도 상한 30블록/초 (바닐라는 78), 6블록 넘게 떨어져야 낙하 피해, 하트 9칸 이상에서 치명적인 낙하는 하트 반 칸을 남김, Grab 을 누른 채 달려 벽 달리기, 웅크려 모으는 점프가 두 배 빠르게 충전 |

벽 달리기: 장화를 신고 Grab 을 누른 채 달리며 두 칸 이상 높이의 벽에 부딪히면 됩니다. 거의 정면(벽면과 70도
이상)이면 약 네 칸 위로 달려 오르고, 꼭대기에 닿으면 그 위로 올라섭니다. 공중에서 비스듬히(40도 이하)
부딪히면 최대 1.5초 동안 천천히 가라앉으며 벽을 따라 달립니다. 그 사이 각도는 아무 일도 일어나지 않습니다.
달리는 중 점프하면 벽을 차고 떨어지며 바지의 이단 점프가 되살아납니다. 착지하기 전에는 같은 벽을 두 번
달릴 수 없습니다. 벽 달리기 중에는 전용 자세가 다른 플레이어에게도 보이고, 1인칭에서는 화면과 손이
달리는 걸음에 맞춰 흔들립니다.

지상 대시는 낮게 튀어 오른 뒤 미끄러지며, 튀어 오른 동안에는 공중 대시가 되지 않고 점프하면 대시의
관성을 유지하는 지상 점프가 되어 이단 점프가 남습니다. 절벽에서 대시해 시작 높이를 반 블록 넘게
벗어나면 일반 낙하가 됩니다. 대시마다 시야가 잠깐 넓어지고, 바람과 먼지 입자가 뒤따릅니다.

## Build

```bash
git clone https://github.com/View-Studio/SmartMoving.git
cd SmartMoving
./gradlew build
```

빌드 결과물은 `build/libs/` 에 생성됩니다.

```bash
./gradlew runClient   # 클라이언트 실행
./gradlew runServer   # 서버 실행
```

## Credits

| Version | Loader | Maintainer |
|---------|--------|------------|
| 1.21.1  | Fabric | [View-Studio](https://github.com/View-Studio) |
| 1.12.2  | Forge  | [doch2](https://github.com/doch2/SmartMovingReboot) |
| 1.7.10  | Forge  | [Divisor](https://www.curseforge.com/minecraft/mc-mods/smart-moving) *(original)* |

## Support ☕

SmartMoving 이 도움이 되셨다면, 작은 후원이 다음 프로젝트의 원동력이 됩니다. 😊

| Platform | Audience  | Link |
|----------|-----------|------|
| Ko-fi    | Worldwide | [ko-fi.com/viewstudio](https://ko-fi.com/viewstudio) |

## Bug Reports

[GitHub Issues](https://github.com/View-Studio/SmartMoving/issues) 에 보고해주세요. 재현 스텝 + 로그 첨부 시 빠른 처리가 가능합니다.

## License

[GPL-3.0-or-later](LICENSE) — 원본 SmartMoving 의 라이선스를 따릅니다.
