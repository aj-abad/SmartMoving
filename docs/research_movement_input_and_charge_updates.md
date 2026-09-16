# 이동 입력 및 차지 점프 개선 리서치

## 목표

- 점프 차지 HUD가 `icons.png` 상단의 파란색 작은/큰 화살표를 정확히 사용하도록 수정한다.
- 엎드리기(prone)를 기본 `C` 키로 직접 조작할 수 있게 한다.
- 회피(dodge)를 기본 미지정 키바인딩으로 추가하고 전방 회피를 지원한다.
- 스니크 이동 중에도 점프를 충전하되 정지 충전의 절반 속도로 진행한다.

## 조사 결과

### HUD 아이콘

- `assets/smartmoving/textures/gui/icons.png`는 256×256 텍스처이며 첫 행의 9×9 아이콘은 다음과 같다.
  - `(0, 0)`: 큰 노란 피로 아이콘
  - `(1, 0)`: 작은 노란 피로 아이콘
  - `(2, 0)`: 큰 파란 점프 화살표
  - `(3, 0)`: 작은 파란 점프 화살표
- `SmartMovingHud.drawIcon`은 아이콘 좌표 자체는 맞지만 Minecraft 26.2의
  `GuiGraphicsExtractor.blit(Identifier, x0, y0, x1, y1, u0, u1, v0, v1)`에
  UV를 `u0, v0, u1, v1` 순서로 넘기고 있다.
- 따라서 U/V 범위가 뒤섞여 파란 화살표가 아닌 영역을 샘플링한다. 호출 인자를
  `u0, u1, v0, v1`로 바로잡으면 기존 큰/작은 아이콘 채움 계산을 그대로 사용할 수 있다.

### 키바인딩과 엎드리기

- 전용 키는 `SmartMovingKeys`에서 Fabric `KeyMappingHelper`로 등록된다.
- 현재 크롤 진입은 주로 `grabJustPressed + sneak + onGround` 조합이고, 유지 입력은
  `inputContinueCrawl`에서 계산된다.
- 전용 prone 키는 기존 조합을 제거하지 않고 추가 입력으로 연결한다.
  - 기본 키: `C`
  - `move.crawl.toggle=false`: 누르고 있는 동안 진입/유지
  - `move.crawl.toggle=true`: 누를 때마다 `crawlToggled` 전환
- 사다리 앞에서 기존 grab+sneak 조합을 등반에 우선시키는 가드는 그대로 유지한다.
  전용 prone 키는 사용자의 명시적 엎드리기 입력이므로 별도 진입 경로로 처리한다.

### 회피와 전방 방향 상태

- 기존 방향 점프는 A/D/S 더블탭 카운터로 발동하며 `tryJump(ANGLE, ..., worldAngle)`을 사용한다.
- 물리 함수는 임의의 월드 각도를 이미 지원하므로, 전용 dodge 키의 rising edge에서 현재
  WASD 조합을 상대 각도(0°, 45°, ..., 315°)로 변환해 같은 경로를 재사용할 수 있다.
- 기본 dodge 키는 `InputConstants.UNKNOWN`으로 등록해 컨트롤 화면에는 표시하되 미지정 상태로 둔다.
- 기존 A/D/S 더블탭 회피는 호환성을 위해 유지한다. 전방 회피는 W 더블탭과 스프린트의
  충돌을 피하기 위해 전용 dodge 키와 방향 입력 조합에서 제공한다.
- 기존 `angleJumpType`은 0을 "비활성"으로 사용하고 2~6만 활성 방향으로 판정한다.
  전방까지 8방향을 표현하려면 1~8을 방향 코드로 사용한다.
  - 상대 각도 315°..0°를 코드 1..8로 매핑한다.
  - 코드 8의 상위 비트는 비어 있는 상태 패킷 bit 36에 저장한다.
  - `isAngleJumping()`은 코드 1~8 전체를 활성 상태로 판정한다.
  - 애니메이션 각도에서 코드 8은 0°로 정규화하고 방향별 blend 값을 원형 거리로 계산한다.

### 이동 중 스니크 차지

- 현재 `SmartMovingJumper.handleJumping`은 `player.onGround() && sm.isStanding`에서만
  일반 차지 점프를 허용하며 매 틱 `jumpCharge += 1`을 적용한다.
- 이동 입력은 `player.input.getMoveVector().lengthSquared()`로 안정적으로 확인할 수 있다.
- 지상에서 스니크 의도(`wouldIsSneaking`)가 있고 이동 입력이 있으면 정지 여부와 무관하게
  충전을 허용한다. 이때 증가량은 `0.5`, 이동 입력이 없으면 기존처럼 `1.0`으로 한다.
- 최대치 clamp와 키 해제 시 발사 흐름은 기존 로직을 유지한다.

## 검증 계획

- `compileJava`, `compileClientJava`, `build`로 API/매핑 및 리소스 변경을 검증한다.
- 아이콘 UV 호출 순서와 `(2,0)/(3,0)` 선택을 코드 검토한다.
- 키 등록, 번역 키, 입력 edge/reset, 상태 패킷 bit 36 encode/decode의 왕복 대칭을 확인한다.
- 정지 충전 1.0/tick, 이동 스니크 충전 0.5/tick, 비스니크 이동 차지 불가 조건을 확인한다.
