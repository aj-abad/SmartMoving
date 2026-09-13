# 엘리트라 활공 중 낙하 애니메이션 충돌 조사

## 증상

엘리트라로 활공하는 동안 `fallDistance`가 낙하 애니메이션 임계값을 넘으면 SmartMoving의 낙하 애니메이션이 바닐라 엘리트라 자세를 덮어쓴다.

## 원인

- `SmartMovingClientState.sendStatePacket()`이 `doFallingAnimation`을 계산할 때 `player.isFallFlying()`을 제외하지 않는다.
- 계산 결과는 `SmartMovingState` bit 16으로 서버를 거쳐 원격 클라이언트에도 전달되므로, 로컬 플레이어뿐 아니라 다른 플레이어에게도 잘못된 낙하 애니메이션이 보일 수 있다.
- `MixinPlayerEntityRenderer`, `MixinClientPlayerEntity`, `MixinPlayerEntity`에도 `fallDistance` 기반 낙하 판정이 중복되어 있으며, 이 판정들 역시 엘리트라 활공을 명시적으로 제외하지 않는다.
- Minecraft 26.2의 `LivingEntity.isFallFlying()`은 entity shared flag 7을 반환하며, 바닐라 `Player.getDesiredPose()`도 이 상태를 `Pose.FALL_FLYING`으로 매핑한다.
- SmartMoving의 이동 처리 자체는 이미 `player.isFallFlying()`일 때 `resetState()`를 호출하므로, 낙하 애니메이션 및 자세 보정도 동일하게 비활성화하는 것이 기존 호환 정책과 일치한다.

## 수정 방안

모든 SmartMoving 낙하 판정에 `!player.isFallFlying()` 가드를 추가한다.

1. `doFallingAnimation`의 원본 계산 지점에서 차단하여 로컬 및 네트워크 동기화 상태를 바로잡는다.
2. 렌더 회전 보정의 독립 낙하 판정에서도 차단하여 바닐라 엘리트라 회전을 보존한다.
3. 클라이언트 및 공통 자세 보정의 독립 낙하 판정에서도 차단하여 엘리트라 중 바닐라 crouch/pose 계산에 SmartMoving이 개입하지 않게 한다.

## 검증 기준

- Minecraft 26.2/Fabric 대상 소스가 컴파일된다.
- 모든 `fallAnimationDistanceMinimum` 기반 낙하 판정이 `isFallFlying()`을 제외한다.
- 일반 공중 낙하 조건은 기존과 동일하게 유지된다.
- 엘리트라 활공 중 `doFallingAnimation`은 false로 전송된다.
