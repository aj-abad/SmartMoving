# Korean Comment Translation Checklist

## Client Files - Main Classes

- [x] src/client/java/choco/ratel/smartmoving/client/input/SmartMovingKeys.java
- [x] src/client/java/choco/ratel/smartmoving/client/SmartMovingAvatarRenderState.java
- [🔄] src/client/java/choco/ratel/smartmoving/client/SmartMovingClient.java (agent a3447d247284b7396)
- [🔄] src/client/java/choco/ratel/smartmoving/client/SmartMovingClientState.java (agent ab0fe1e9a44173947)
- [🔄] src/client/java/choco/ratel/smartmoving/client/SmartMovingClimber.java (agent a2a41a126c7779789)
- [x] src/client/java/choco/ratel/smartmoving/client/SmartMovingFlyer.java
- [x] src/client/java/choco/ratel/smartmoving/client/SmartMovingHud.java
- [🔄] src/client/java/choco/ratel/smartmoving/client/SmartMovingJumper.java (agent ac26db3cb5cdbe89f)
- [x] src/client/java/choco/ratel/smartmoving/client/SmartMovingLivingEntityAccess.java
- [🔄] src/client/java/choco/ratel/smartmoving/client/SmartMovingMover.java (agent a82222e35b61e66c2)
- [x] src/client/java/choco/ratel/smartmoving/client/SmartMovingRenderContext.java
- [x] src/client/java/choco/ratel/smartmoving/client/SmartMovingSlider.java
- [🔄] src/client/java/choco/ratel/smartmoving/client/SmartMovingSwimmer.java (agent ac26db3cb5cdbe89f)
- [🔄] src/client/java/choco/ratel/smartmoving/client/SmModelPartOverride.java (agent a82222e35b61e66c2)

## Client Mixin Files

- [ ] src/client/java/choco/ratel/smartmoving/mixin/client/MixinAbstractClientPlayerEntityClient.java
- [ ] src/client/java/choco/ratel/smartmoving/mixin/client/MixinAvatarDimensionsClient.java
- [ ] src/client/java/choco/ratel/smartmoving/mixin/client/MixinAvatarRenderState.java
- [ ] src/client/java/choco/ratel/smartmoving/mixin/client/MixinCamera.java
- [ ] src/client/java/choco/ratel/smartmoving/mixin/client/MixinCapeFeatureRenderer.java
- [ ] src/client/java/choco/ratel/smartmoving/mixin/client/MixinClientPlayerEntity.java
- [ ] src/client/java/choco/ratel/smartmoving/mixin/client/MixinClientPlayerInteractionManager.java
- [ ] src/client/java/choco/ratel/smartmoving/mixin/client/MixinClientPlayNetworkHandler.java
- [ ] src/client/java/choco/ratel/smartmoving/mixin/client/MixinEntityClient.java
- [ ] src/client/java/choco/ratel/smartmoving/mixin/client/MixinKeyboardInput.java
- [ ] src/client/java/choco/ratel/smartmoving/mixin/client/MixinLivingEntityAccessor.java
- [ ] src/client/java/choco/ratel/smartmoving/mixin/client/MixinLivingEntityClient.java
- [ ] src/client/java/choco/ratel/smartmoving/mixin/client/MixinLivingEntityRenderer.java
- [ ] src/client/java/choco/ratel/smartmoving/mixin/client/MixinModelPart.java
- [ ] src/client/java/choco/ratel/smartmoving/mixin/client/MixinPlayerEntityClient.java
- [ ] src/client/java/choco/ratel/smartmoving/mixin/client/MixinPlayerEntityModelClient.java
- [ ] src/client/java/choco/ratel/smartmoving/mixin/client/MixinPlayerEntityRenderer.java

## Common Files

- [x] src/main/java/choco/ratel/smartmoving/climbing/CeilingClimbBlocks.java
- [x] src/main/java/choco/ratel/smartmoving/climbing/ClimbGap.java
- [x] src/main/java/choco/ratel/smartmoving/climbing/FeetClimbing.java
- [x] src/main/java/choco/ratel/smartmoving/climbing/HandsClimbing.java
- [ ] src/main/java/choco/ratel/smartmoving/climbing/Orientation.java
- [ ] src/main/java/choco/ratel/smartmoving/config/SmartMovingConfig.java

## Mixin Files - Common

- [ ] src/main/java/choco/ratel/smartmoving/mixin/MixinAvatarDimensions.java
- [ ] src/main/java/choco/ratel/smartmoving/mixin/MixinEntity.java
- [x] src/main/java/choco/ratel/smartmoving/mixin/MixinLivingEntity.java
- [x] src/main/java/choco/ratel/smartmoving/mixin/MixinLivingEntityServer.java
- [ ] src/main/java/choco/ratel/smartmoving/mixin/MixinPlayerEntity.java

## Mixin Files - Server

- [ ] src/main/java/choco/ratel/smartmoving/mixin/server/MixinServerPlayerEntity.java
- [ ] src/main/java/choco/ratel/smartmoving/mixin/server/MixinServerPlayerInteractionManager.java
- [ ] src/main/java/choco/ratel/smartmoving/mixin/server/MixinServerPlayNetworkHandler.java

## Core Files

- [x] src/main/java/choco/ratel/smartmoving/network/SmartMovingNetwork.java
- [x] src/main/java/choco/ratel/smartmoving/network/SmartMovingState.java
- [ ] src/main/java/choco/ratel/smartmoving/server/SmartMovingServer.java
- [x] src/main/java/choco/ratel/smartmoving/SmartMoving.java
- [x] src/main/java/choco/ratel/smartmoving/SmartMovingSounds.java
- [ ] src/main/java/choco/ratel/smartmoving/stat/SmartStatistics.java
