# Checklist: Translate Korean Comments in 4 Files

## File 1: SmartMovingSwimmer.java
- [ ] Translate class-level documentation (클라이언트 측 수영/잠수 물리 로직)
- [ ] Translate section headers (8-1, 8-2, etc.)
- [ ] Translate offset threshold comments (L26-L36)
- [ ] Translate underwater movement constant comments (L38-L49)
- [ ] Translate updateSwimState() documentation and implementation comments (L52-L319)
- [ ] Translate handleSwimming() documentation and implementation comments (L321-L768)
- [ ] Translate moveFlying() documentation and implementation (L770-L826)
- [ ] Translate handleLava() documentation and implementation (L828-L953)
- [ ] Translate isTunnelAhead() documentation (L955-L978)

## File 2: SmartMovingJumper.java
- [ ] Translate class-level documentation (클라이언트 측 점프 물리 로직)
- [ ] Translate section headers (10-1, 10-2, etc.)
- [ ] Translate jumpType constant comments (L34-L56)
- [ ] Translate getJumpMoving() documentation and comments (L57-L75)
- [ ] Translate setPoseSmall() and resetHeightOffset() documentation (L77-L138)
- [ ] Translate tryJump() documentation and implementation (L140-L351)
- [ ] Translate handleJumping() documentation and implementation (L353-L576)
- [ ] Translate updateWallJumpState() documentation and implementation (L619-L692)
- [ ] Translate handleWallJumping() documentation and implementation (L694-L825)
- [ ] Translate helper methods: calculateSeparateCollisionAngle, getHorizontalCollisionangle (L827-L878)

## File 3: SmartMovingServer.java
- [ ] Translate class-level documentation (서버 플레이어당 SM 상태 컴포넌트)
- [ ] Translate LOGGER comment (L25-L26)
- [ ] Translate field comments (L28-L108)
- [ ] Translate instance management comments (L117-L129)
- [ ] Translate processStatePacket() documentation and implementation (L131-L351)
- [ ] Translate setCrawling() documentation and comments (L353-L363)
- [ ] Translate applyFallDistanceReset() documentation (L365-L376)
- [ ] Translate exhaustion batch system comments (L378-L399)
- [ ] Translate server hitbox documentation (L401-L411)
- [ ] Translate initialize() documentation (L413-L435)
- [ ] Translate config broadcasting documentation (L437-L449)
- [ ] Translate admin functions documentation (L451-L478)
- [ ] Translate logging functions documentation (L480-L524)
- [ ] Translate permission and info processing methods (L526-L578)

## File 4: SmartStatistics.java
- [ ] Translate class-level documentation (렌더 틱마다 플레이어별 이동 데이터)
- [ ] Translate distance accumulation comments (L10-L13)
- [ ] Translate current speed comments (L15-L19)
- [ ] Translate flattened history comments (L21-L30)
- [ ] Translate prev field comments (L32-L45)
- [ ] Translate monotonic guard comments (L48-L70)
- [ ] Translate frame-by-frame movement comments (L72-L75)
- [ ] Translate angle comments (L77-L86)
- [ ] Translate calculate() method documentation and implementation (L92-L184)
- [ ] Translate partial tick lerp getter comments (L186-L192)
- [ ] Translate getCurrentHorizontalSpeedFlattened() documentation (L197-L216)
- [ ] Translate getTotalDistance() and related methods comments (L228-L275)
- [ ] Translate reset() method comments (L277-L307)

## Progress
- [ ] **File 1 Complete** (SmartMovingSwimmer.java)
- [ ] **File 2 Complete** (SmartMovingJumper.java)
- [ ] **File 3 Complete** (SmartMovingServer.java)
- [ ] **File 4 Complete** (SmartStatistics.java)
- [ ] **All Files Committed**

## Notes
- Use Edit tool for all changes
- Only modify comments; do NOT change code logic
- Preserve exact indentation and formatting
- Keep technical term consistency with existing English translations
