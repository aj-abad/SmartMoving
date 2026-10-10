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
- **Chain Climbing** *(프로토타입)* — 세로 사슬(철/구리) 등반, Sneak 으로 빠르게 하강, 낙하 중 사슬 잡기; 머리 위 가로 사슬은 천장 등반 손잡이
- **Landing Roll** *(프로토타입)* — 낙하 중 Dodge 키를 누르면 착지 시 구르기: 속도 유지 + 낙하 피해 감소
- **Sprint Vault** *(프로토타입)* — 달리기 중 1칸 턱 / 울타리 / 담장에 돌진하면 손을 짚고 뛰어넘기
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
