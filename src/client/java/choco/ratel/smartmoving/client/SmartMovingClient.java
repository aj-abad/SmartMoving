package choco.ratel.smartmoving.client;

import choco.ratel.smartmoving.client.input.SmartMovingKeys;
import choco.ratel.smartmoving.config.SmartMovingConfig;
import choco.ratel.smartmoving.network.SmartMovingNetwork;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.phys.Vec3;

import java.net.URI;

public class SmartMovingClient implements ClientModInitializer {

    /**
     * 🔴 (2026-05-05 user report — "when player a's config is off, everything appears vanilla on player a's side"):
     *   The `Config.enabled` check in render-path blocks all SM branching for player rendering when self's client cfg is disabled
     *   → everything appears vanilla. Remote side is correct since their cfg=true.
     *   Solution: Limit render-path enabled check to self-only.
     *     - self → own Config.enabled
     *     - remote → always true (= independent of own cfg, uses remote's SM state itself for branching)
     *   When self is disabled, all own SM states are false (= state update/packet transmission stops) so
     *   remote sees self as vanilla automatically (= correct).
     */
    public static boolean isSmRenderEnabled(net.minecraft.world.entity.Entity entity) {
        if (SmartMovingConfig.Config.enabled) return true;
        // Own cfg disabled — only allow remote players (= independent of vanilla cfg + remote SM branching works correctly).
        if (!(entity instanceof AbstractClientPlayer ap)) return false;
        return ap != Minecraft.getInstance().player;
    }

    @Override
    public void onInitializeClient() {
        SmartMovingKeys.register();
        SmartMovingHud.register();
        registerClientReceivers();
        registerConnectionEvents();
        registerGameMessageHandler();
    }

    private static void registerConnectionEvents() {
        // When joining world/server, send brand + link message to chat (self only).
        // Line 1: ──────────────  (top border)
        // Line 2: [SmartMoving] by View-Studio
        // Line 3: [GitHub]  ·  [Support ☕]
        // Line 4: ──────────────  (bottom border)
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) ->
                client.execute(() -> {
                    if (client.player == null) return;
                }));

        // On client disconnect, clean up state instance + restore Config
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            if (client.player != null) {
                SmartMovingClientState.remove(client.player);
            }
            // Restore server config switch — use client config until next server join
            SmartMovingConfig.Config = SmartMovingConfig.INSTANCE;
            // Corresponds to original SmartMovingServerConfig.reset() — initialize SERVER_CONFIG instance.
            // Prevents lingering server config on next server join.
            SmartMovingConfig.resetServerConfig();
        });
    }

    /** Brand + link message to display in chat when joining world. */
    private static final String GITHUB_URL = "https://github.com/View-Studio/SmartMoving";
    private static final String SUPPORT_URL = "https://ko-fi.com/viewstudio";

    /** Horizontal border lines (top/bottom) — continuous lines without breaks using spaces + strikethrough. */
    private static Component buildBorderLine() {
        return Component.literal(" ".repeat(40))
                .withStyle(s -> s.withColor(ChatFormatting.DARK_GRAY).withStrikethrough(true));
    }

    /** Line 1: Brand + Author. */
    private static Component buildBrandLine() {
        MutableComponent tag = Component.literal("[SmartMoving]")
                .withStyle(s -> s.withColor(ChatFormatting.GOLD));

        MutableComponent by = Component.literal(" by ")
                .withStyle(s -> s.withColor(ChatFormatting.GRAY));

        MutableComponent author = Component.literal("View-Studio")
                .withStyle(s -> s.withColor(ChatFormatting.WHITE));

        return Component.empty().append(tag).append(by).append(author);
    }

    /** Line 2: Clickable GitHub + Support links. */
    private static Component buildLinksLine() {
        MutableComponent sep = Component.literal("  ·  ").withStyle(s -> s.withColor(ChatFormatting.DARK_GRAY));

        MutableComponent github = Component.literal("[GitHub]")
                .withStyle(s -> s
                        .withColor(ChatFormatting.AQUA)
                        .withUnderlined(true)
                        .withClickEvent(new ClickEvent.OpenUrl(URI.create(GITHUB_URL)))
                        .withHoverEvent(new HoverEvent.ShowText(
                                Component.literal("Open repository\n" + GITHUB_URL))));

        MutableComponent support = Component.literal("[Support ☕]")
                .withStyle(s -> s
                        .withColor(ChatFormatting.YELLOW)
                        .withUnderlined(true)
                        .withClickEvent(new ClickEvent.OpenUrl(URI.create(SUPPORT_URL)))
                        .withHoverEvent(new HoverEvent.ShowText(
                                Component.literal("Buy me a coffee\n" + SUPPORT_URL))));

        return Component.empty().append(github).append(sep).append(support);
    }

    private static void registerClientReceivers() {
        // State: Receive other players' movement state (server relay) — C-24
        // Original: SmartMovingFactory.getOtherSmartMoving(entityId).processStatePacket(state)
        ClientPlayNetworking.registerGlobalReceiver(SmartMovingNetwork.StatePayload.ID,
            (payload, context) -> {
                Minecraft client = context.client();
                client.execute(() -> {
                    ClientLevel world = client.level;
                    if (world == null) return;
                    Entity entity = world.getEntity(payload.entityId());
                    if (entity == null) return;
                    SmartMovingClientState target = SmartMovingClientState.get(entity.getUUID());
                    // 🔴 (Phase 2 multi BUG-7) Block remote ICC EXIT box/model 1-block down jump.
                    //   Cause: 1 tick lag between packet processing (= ICC=false applied + dim changed) and PlayerEntity.tick HEAD
                    //          → ICC dim offset (+1m bb) disappears but entity.y remains same →
                    //          same tick render frames show bb=[entity.y..entity.y+0.8] (= 1m below).
                    //   Solution: Detect enter-edge in packet processing lambda + setPos +1m + lerp cancel
                    //             apply immediately → ICC=false and entity.y +1m simultaneously → maintain bb consistency.
                    //   self (= ClientPlayerEntity) has own fix (= memory *project_isclimbcrawling_complete*).
                    boolean wasIcc = target.isClimbCrawling;
                    // 🔴 (Phase 2 multi BUG-15, 2026-05-13) remote sliding entry/exit mapping.
                    //   self side fix #60 (= move(0,-1,0)) + fix #62 v2 + fix #70 (= setPos(y+1)
                    //   predictive drop check) missing from remote packet handler → on entry, 1 tick airborne 1-block above
                    //   + on exit, 1 tick inside ground 1m BUG. BUG-7 ICC EXIT v25.3 symmetrical pattern —
                    //   block 1 tick lag with immediate setPos in packet processing lambda.
                    //   self (= ClientPlayerEntity) has own fix.
                    boolean wasSliding = target.isSliding;
                    boolean wasFlying = target.isFlying;
                    boolean wasHJ = target.isHeadJumping;
                    boolean wasICC = target.isClimbCrawling;
                    // 🔵 fix #154 (2026-05-22) — swim/dive → crawl direct transition snapshot (remote sink fix).
                    boolean wasSwim_fix154 = target.isSwimming_sm;
                    boolean wasDive_fix154 = target.isDiving;
                    boolean wasCR_fix154 = target.isCrawling;
                    int wasAngleJumpType = target.angleJumpType;
                    double slideBoxMinYBefore = 0.0;
                    double flyBoxMinYBefore = 0.0;
                    if (entity instanceof AbstractClientPlayer rsPre
                            && !(entity instanceof LocalPlayer)) {
                        double bbMinY = rsPre.getBoundingBox().minY;
                        // calc dim *before* bb (= moment when mixin offset active). self fix #70 equivalent _currentBoxMinY70.
                        if (wasSliding) slideBoxMinYBefore = bbMinY;
                        // 🔴 (BUG D fix, 2026-05-13) bb just before flight end (= moment when mixin offset active) — for gap measurement.
                        if (wasFlying) flyBoxMinYBefore = bbMinY;
                    }
                    target.processStatePacket(payload.state());
                    // Dodge takeoff/landing ticks for the remote player's dodge animation.
                    if (entity instanceof AbstractClientPlayer remoteDodge
                            && !(entity instanceof LocalPlayer)) {
                        target.trackRemoteAngleJump(wasAngleJumpType, remoteDodge.tickCount,
                                remoteDodge.getYRot());
                    }
                    // 🔴 (Phase 2 multi BUG-12/14) Update dim immediately on packet arrival → determine dim based on sm.* bits
                    //   without waiting for vanilla pose sync. Remove boolean OR guard — avoid missing detection on
                    //   simultaneous bit changes. Minimal vanilla effect if dim is same.
                    if (entity instanceof LivingEntity living) {
                        living.refreshDimensions();
                    }
                    // 🔴 (slide→crawl box floating fix, 2026-05-13) immediately update POSE on remote side.
                    //   User report BUG: REMOTE side slide → crawl auto-transition *between* (~2 tick = 100ms)
                    //     box itself floats 1m above ground = "STANDING collision transition" visual.
                    //   root cause (= dump verification):
                    //     - SmartMovingState packet arrival → isCrawling=true update + calculateDimensions
                    //       → sm_getBaseDimensions_client smSmall branch 5 match → dim=(0.8, 0.62).
                    //     - vanilla EntityDataS2CPacket (POSE) broadcast lag ~2 tick → POSE=SLIDING
                    //       persists.
                    //     - MixinEntity.sm_offsetBoundingBoxForFlying guard `eye<=1 && pose!=SLIDING`
                    //       no match (= pose==SLIDING persists) → mixin offset active → bb=y+1.
                    //     - → box ground top + 1m floating frame ~2 tick. head position same as STANDING box head
                    //       (= y+1.8) → user visual "STANDING collision transition".
                    //   self side: PlayerEntity.tick → updatePose → sm_updatePose_client same tick
                    //     calls setPose(SWIMMING) → next render frame bb=y. floating frame almost X.
                    //   Solution: immediately call setPose in packet lambda on remote side. matches self side
                    //     sm_updatePose_client 4-branch mapping.
                    if (entity instanceof AbstractClientPlayer apPose
                            && !(entity instanceof LocalPlayer)) {
                        Pose targetPose = null;
                        if (target.isCrawling && !target.isClimbing) {
                            targetPose = Pose.SWIMMING;
                        } else if (target.isHeadJumping || target.isSliding) {
                            targetPose = Pose.SLIDING;
                        } else if (target.isSwimming_sm || target.isDiving) {
                            targetPose = Pose.SWIMMING;
                        } else {
                            // 🔴 (wall collision cycle fix, 2026-05-13) block box floating from orphan POSE persisting frames.
                            //   User report BUG: box bounces up on wall collision during sliding.
                            //   root cause (= dump verification):
                            //     - self side wouldWantCrawl formula + isClimbing guard matrix creates cycle
                            //       (= slide→crawl→standing→crawl). tick N: isCR=true→false PKT match.
                            //     - remote PKT processing: target.isCrawling=false update. our 4-branch matrix
                            //       no match → setPose not called.
                            //     - vanilla POSE broadcast lag ~1 tick → POSE=SLIDING persists.
                            //     - mixin offset guard `eye<=1 && pose!=SLIDING` no match (= pose==SLIDING)
                            //       → mixin offset active → bb=y+1 (= box 1m above ground floating).
                            //   Solution: force setPose(STANDING) when all SM phases inactive + POSE=SWIMMING/SLIDING persists.
                            //     mixin offset guard match → block → bb=y.
                            //   regression prevention:
                            //     - vanilla own pose (sleep/elytra/spin_attack etc): isFallFlying / isInSwimmingPose
                            //       guard. match → skip our fix.
                            //     - normal STANDING: curPose=STANDING → no match (condition SWIMMING|SLIDING) → skip.
                            //     - vanilla own swimming (in water): isInSwimmingPose=true → skip.
                            Pose curPose = apPose.getPose();
                            if ((curPose == Pose.SWIMMING
                                    || curPose == Pose.SLIDING)
                                    && !apPose.isVisuallySwimming()
                                    && !apPose.isFallFlying()) {
                                targetPose = Pose.STANDING;
                            }
                        }
                        if (targetPose != null && apPose.getPose() != targetPose) {
                            apPose.setPose(targetPose);
                            if (entity instanceof LivingEntity living2) {
                                living2.refreshDimensions();
                            }
                        }
                    }
                    // 🔴 (Phase 2 BUG-7) 1:1 replication of self side ICC ENTER mapping.
                    //   self side ICC entry (SmartMovingClientState L2367-L2423):
                    //     heightOffset = -1F + calculateDimensions + move(0, 0.05, 0) + restore entity.y
                    //     + restore horizontalCollision + Camera correction.
                    //   User report "remote entry point x,z pulled back + y lowered": move(0, 0.05, 0) +
                    //   entity.y restore + horizontalCollision restore mapping missing. self side vanilla
                    //   collision result cancel mechanism (memory comment = "ICC entry/exit per tick vibration
                    //   accumulates entity.y +0.05m → camera jitter fix") applied as-is.
                    //   heightOffset/Camera are self style/first-person only so not needed for remote.
                    if (!wasIcc && target.isClimbCrawling
                            && entity instanceof AbstractClientPlayer remoteEnter
                            && !(entity instanceof LocalPlayer)) {
                        boolean wasColH = remoteEnter.horizontalCollision;
                        double iccEnterYBefore = remoteEnter.getY();
                        remoteEnter.move(MoverType.SELF, new Vec3(0, 0.05, 0));
                        if (remoteEnter.getY() != iccEnterYBefore) {
                            remoteEnter.setPos(remoteEnter.getX(), iccEnterYBefore, remoteEnter.getZ());
                        }
                        remoteEnter.horizontalCollision = wasColH;
                    }

                    if (wasIcc && !target.isClimbCrawling
                            && entity instanceof AbstractClientPlayer remote
                            && !(entity instanceof LocalPlayer)) {
                        // 🔴 (Phase 2 BUG-7 fix settled) 1:1 replication of self side ICC EXIT mapping (cd25cfc pattern).
                        //   self side: setPosition(x, y+1, z) + lastRenderY/prevY +=1 + calculateDimensions
                        //              + bottom snap (ladder/vine cases not applied).
                        //   isCrawling guard = self guard (mustCrawl||sneakPressedRaw||crawlToggled+gap<1)
                        //                      check result directly.
                        //   calculateDimensions already called above (L109-L111) in packet handler.
                        if (target.isCrawling) {
                            double newY = remote.getY() + 1.0;
                            remote.setPos(remote.getX(), newY, remote.getZ());
                            remote.yOld = newY;
                            remote.yo = newY;
                            // 🔴 (BUG B fix, 2026-05-13) lerp cancel — same pattern as BUG-15.
                            //   vanilla OtherClientPlayerEntity.lerpPosAndRotation drags entity.y with srvY per tick
                            //   → invalidates our setPos+1m → gradual -1m down → direct cause of user report
                            //   "descended 1 block then came back up". block lerp with srvY/bti sync.
                            SmartMovingLivingEntityAccess accI =
                                    (SmartMovingLivingEntityAccess)(Object) remote;
                            accI.sm_setServerY(newY);
                            accI.sm_setBodyTrackingIncrements(0);
                            // bottom snap only for normal blocks. ladder/vine cases not applied (keep ladder maxY+1 above).
                            if (!remote.onClimbable()) {
                                double minY = remote.getBoundingBox().minY;
                                double gap = minY - choco.ratel.smartmoving.client.SmartMovingClientState
                                        .getMaxPlayerSolidBetween(remote, minY - 1.0, minY, 0);
                                if (gap >= 0.0 && gap < 1.0) {
                                    remote.move(MoverType.SELF, new Vec3(0, -gap, 0));
                                    // Re-sync srvY after bottom snap (= move changed entity.y).
                                    accI.sm_setServerY(remote.getY());
                                    accI.sm_setBodyTrackingIncrements(0);
                                }
                            }
                        }
                    }

                    // 🔴 (Phase 2 multi BUG-15, 2026-05-13) remote sliding entry — 1:1 with self fix #60.
                    //   self side L2106-L2111: heightOffset=-1 + isSliding=true + calc dim +
                    //   move(0,-1,0) (= entity.y -1m). dim eye=1.62 active + mixin offset bb +1m up →
                    //   bb feet = entity.y + 1m = ground.
                    //   remote side: SM state packet arrival → isSliding=true + calc dim up to (above L82-L84)
                    //     already done. but self's entity.y -1m arrives before server broadcast (1 tick lag)
                    //     → remote.y = old self.y (= self ground) → bb feet = remote.y + 1m = self ground
                    //     + 1m = **1 block airborne BUG**.
                    //   Solution: immediate setPos(y-1) in packet processing lambda → entity.y -= 1m → bb feet = (remote.y
                    //     -1) + 1m = self ground match. sync lastRenderY/prevY — block vanilla lerp jump.
                    //   BUG-7 ICC EXIT v25.3 sign inversion (+1 → -1) pattern.
                    // 🔴 (BUG D fix, 2026-05-13) Add !wasFlying guard.
                    //   On direct flying → slide transition, *flying exit branch* handles as self side standupIfPossible branch 3
                    //   (= move(0, -gap, 0)) 1:1 mapping. prevent conflict between our slide entry fix's fixed -1m push and
                    //   gap-change formula.
                    if (!wasSliding && target.isSliding && !wasFlying
                            && entity instanceof AbstractClientPlayer remoteSlideEnter
                            && !(entity instanceof LocalPlayer)) {
                        SmartMovingLivingEntityAccess accE =
                                (SmartMovingLivingEntityAccess)(Object) remoteSlideEnter;
                        // 🔴 fix #93 (2026-05-13, dump verification — user report "fox moving → simultaneous sliding entry gets briefly stuck"):
                        //   Previous newY = remoteSlideEnter.getY() - 1.0:
                        //     when client.y before entry is locked by *head jump in progress server.y broadcast lag*
                        //     state (= -0.022m above ground) then newY = locked position - 1m → after setPos bb feet =
                        //     -0.022m above ground (= remains locked). after next broadcast vanilla lerp
                        //     recovers in ~3 tick. user visual "briefly stuck then recovers".
                        //   Solution: adopt fix #92 pattern. directly measure ground top → newY = groundY - 1.
                        //     Normal entry: groundY = ground → same result. Locked entry: groundY = ground →
                        //     align to ground. no regression.
                        double bbMinBefore93 = remoteSlideEnter.getBoundingBox().minY;
                        double groundY93 = choco.ratel.smartmoving.client.SmartMovingClientState
                                .getMaxPlayerSolidBetween(remoteSlideEnter, bbMinBefore93 - 1.5, bbMinBefore93 + 0.5, 0);
                        double newY = groundY93 - 1.0;
                        remoteSlideEnter.setPos(remoteSlideEnter.getX(), newY, remoteSlideEnter.getZ());
                        remoteSlideEnter.yOld = newY;
                        remoteSlideEnter.yo = newY;
                        // 🔴 lerp cancel — vanilla OtherClientPlayerEntity.lerpPosAndRotation drags
                        //   entity.y with srvY (= previous broadcast standing y) → invalidates our setPos.
                        //   BUG-7 v25.3 pattern: srvY = newY + bti = 0 → block lerp until next broadcast arrives.
                        accE.sm_setServerY(newY);
                        accE.sm_setBodyTrackingIncrements(0);
                    }

                    // 🔴 (Phase 2 multi BUG-15, 2026-05-13) remote sliding exit — 1:1 with self fix #62 v2 + #70.
                    //   self side L2337-L2360: after predictive drop check setPos(y+1) + lastRenderY/prevY +=1.
                    //   remote side: SM state packet arrival → isSliding=false + calc dim up to (above L82-L84)
                    //     already done → mixin offset blocked → bb feet = remote.y. self's entity.y +1m push arrives
                    //     before server broadcast (1 tick lag) → remote.y = old self.y (= self ground - 1m)
                    //     → bb feet = self ground - 1m = **1m underground BUG**.
                    //   Solution: check willDrop via difference between slideBoxMinYBefore (= bb before calc dim, when mixin offset active)
                    //     and newY (= entity.y after calc dim) → if drop > 0.5m do setPos(y+1).
                    //   regression prevention: flying → sliding exit (fix #87) etc where slideBoxMinYBefore ≈ remote.y cases
                    //     (= mixin offset already blocked) → willDrop=false → no push.
                    // 🔵 fix #100 (2026-05-20, user report "sliding → cliff → head jump auto-transition REMOTE
                    //   briefly rises 1 block then returns to normal"):
                    //   Real path: SlideToHeadJumping auto-transition in SmartMovingClientState L2309-L2315.
                    //     = only set bits `isSliding=false; isHeadJumping=true; isAerodynamic=true`.
                    //     self side no entity.y push (= L2389 push guard `!isHeadJumping` no match).
                    //     POSE side line 126-127 `isHJ || isSL → SLIDING` → keep POSE=SLIDING → mixin offset
                    //     remains active (MixinEntity L122 guard `pose!=SLIDING` no match) → keep bb=entity.y+1.
                    //     = no visual change on self side.
                    //   remote side this branch: match wasSliding=true && target.isSliding=false.
                    //     slideBoxMinYBefore = remote.y+1 (mixin active before calc dim),
                    //     newY = remote.y (entity.y after calc dim, mixin offset still active).
                    //     willDrop = (remote.y+1 - remote.y) > 0.5 = **false positive true**.
                    //     → setPos(remote.y+1) + lerp cancel → keep POSE=SLIDING so mixin offset active →
                    //     bb=(remote.y+1)+1=ground+2m → "1 block above floating" visual. next broadcast srvY=self.y
                    //     arrival resumes lerp → -1m down → returns to normal position.
                    //   Solution: add guard `!target.isHeadJumping` — 1:1 matching self side L2389 `!isHeadJumping` guard.
                    //     block push on slide → HJ auto-transition. other slide-exit cases (crawl/standing/
                    //     flying) have isHJ=false so pass → maintain existing behavior.
                    if (wasSliding && !target.isSliding && !target.isHeadJumping
                            && entity instanceof AbstractClientPlayer remoteSlideExit
                            && !(entity instanceof LocalPlayer)) {
                        SmartMovingLivingEntityAccess accX =
                                (SmartMovingLivingEntityAccess)(Object) remoteSlideExit;
                        double newY = remoteSlideExit.getY();
                        boolean willDrop = (slideBoxMinYBefore - newY) > 0.5;
                        if (willDrop) {
                            newY += 1.0;
                            remoteSlideExit.setPos(remoteSlideExit.getX(), newY, remoteSlideExit.getZ());
                            remoteSlideExit.yOld = newY;
                            remoteSlideExit.yo = newY;
                            // 🔴 lerp cancel — same pattern as entry branch (srvY/bti sync → block lerp).
                            accX.sm_setServerY(newY);
                            accX.sm_setBodyTrackingIncrements(0);
                        }
                    }

                    // 🔴 (BUG 2 fix, 2026-05-13) remote head jump landing 1-block downward jump block.
                    //   self side L2196 EXIT branch (SmartMovingClientState):
                    //     `wasHJ && !isHJ && onGround` → standupIfPossible(false, true) → entity.y +1m push.
                    //     verification (= log_temp.txt attempts 1~3): SELF-EXIT-POST dy=1.000.
                    //   server side: self c2s SmartMovingState packet processed before c2s position packet
                    //     → SERVER-RECV when server.y = before push (70.000). next server tick updates to 71.000.
                    //   broadcast: SmartMovingState packet broadcast ~1 server tick earlier than EntityPositionS2CPacket
                    //     (= reference_vanilla_server_tick_order, BUG-7/15/D same mechanism).
                    //   remote: packet arrival when remote.y = 70.X (= lerp chasing srvY=70). bbMin=remote.y+1
                    //     (mixin offset active, MixinEntity.sm_offsetBoundingBoxForFlying SLIDING POSE guard).
                    //     box feet above ground (= no diving). **However model origin = entity.y = 70.X = 0.5~0.8m below ground
                    //     → user visual "1-block submerged"**. after srvY=71 update, lerp chases for ~3~15 ticks
                    //     (= 150~750ms).
                    //   solution: BUG-7/15 same pattern — inside packet lambda immediately setPos(groundY) + lerp cancel.
                    //     newY formula = directly measure ground top (= getMaxPlayerSolidBetween, yMax<bbMin blocks ceiling
                    //     match). more accurate than srvY+1 (= srvY may persist inaccurate broadcast value on entry frame,
                    //     attempts 3/5 verification).
                    //   guard `!target.isSliding && !target.isCrawling`:
                    //     - self sliding auto-transition (= fox movement, attempts 4/5): self.y no change → fix not applied, matches.
                    //     - head jump → crawl transition etc: separate fix branch.
                    // 🔵 fix #99 (2026-05-14) — HJ→CR direct transition branch.
                    //   user report: head jump + wall collision + fall + GRAB+SNEAK press → auto-crouch
                    //     transition when REMOTE briefly locks visually.
                    //   real path: HJ:true→false + CR:false→true simultaneously.
                    //   = self side `standupIfPossible` → `toSlidingOrCrawling` else branch (= grab
                    //     no match) → `toCrawling()` call.
                    //   existing fix branch gap: fix #93 (SL entry) / fix #98 v5 (HJ→standing) / fix #90
                    //     (SL→CR) all guards no match.
                    //   newY formula = srvY + 1m (= server side fix #99 v3 push then broadcast position accurate).
                    //     direct groundY measurement (= fix #92/#93 pattern) entry frame box dim change (=
                    //     calculateDimensions after isCrawling dim) causes yMax<ground top → clamp error.
                    if (wasHJ && !target.isHeadJumping
                            && !wasSliding && !target.isSliding
                            && target.isCrawling
                            && !wasIcc && !target.isClimbCrawling
                            && !target.isClimbing && !target.isCeilingClimbing
                            && !target.isSwimming_sm && !target.isDiving
                            && !target.isFlying && !target.isLevitating
                            && !target.isAngleJumping() && !target.isRopeSliding
                            && entity instanceof AbstractClientPlayer remoteHJCr
                            && !(entity instanceof LocalPlayer)) {
                        SmartMovingLivingEntityAccess accHJCr =
                                (SmartMovingLivingEntityAccess)(Object) remoteHJCr;
                        double newYHJCr = accHJCr.sm_getServerY() + 1.0;
                        remoteHJCr.setPos(remoteHJCr.getX(), newYHJCr, remoteHJCr.getZ());
                        remoteHJCr.yOld = newYHJCr;
                        remoteHJCr.yo = newYHJCr;
                        accHJCr.sm_setServerY(newYHJCr);
                        accHJCr.sm_setBodyTrackingIncrements(0);
                    }
                    // 🔵 fix #154 (2026-05-22) — swim/dive → crawl direct transition remote sink fix.
                    //   User report: "swim → enter 1 block space auto crawl momentary remote opponent briefly enters ground then rises".
                    //
                    //   Real path:
                    //   - self side: fix #150 fromSwimmingOrDiving branch 1 → setPos(y+1) + isCrawling=true.
                    //     self.y c2s = ground. server.y = ground (= c2s movement packet auto-sync).
                    //   - server side: fix #150 only calls calculateDimensions (= no mirror push, self setPos syncs
                    //     server via c2s). broadcast srvY = ground.
                    //   - remote side: entry time remote.y = ground - 1 (= lerp result of previous swim broadcast srvY).
                    //     CR dim → mixin offset blocked → box feet = remote.y = ground - 1 → stuck visual.
                    //     vanilla lerp chases srvY=ground → reaches in ~3 ticks.
                    //
                    //   Memory: [project_remote_landing_sink_pattern] fix #92/#93 pattern (= directly measure groundY).
                    //   [feedback_remote_setpos_newY_srvY_plus_1]: no mirror push on server side = groundY-1 formula.
                    //
                    //   fix: setPos(groundY-1) + lerp cancel in remote packet lambda. 1:1 with fix #92 pattern.
                    //   newY = groundY - 1 (= sync with self.y, after mixin offset blocked box feet = entity.y = ground).
                    // 🔵 fix #154 v4 (2026-05-22): multi-step transition history flag.
                    //   Log measurement: server side broadcasts separate 2 packets for dive change vs crawl change. REMOTE side
                    //   lambda each call was[dv=true]→cur[dv=false cr=false] (= dive exit) +
                    //   was[dv=false]→cur[dv=false cr=true] (= crawl entry) 2 steps. single-transition guard no match.
                    //   Solution: set flag on swim/dive exit detection + check flag within 5 ticks on crawl entry detection.
                    boolean _swimEndedFix154 = (wasSwim_fix154 || wasDive_fix154)
                            && !target.isSwimming_sm && !target.isDiving;
                    if (_swimEndedFix154 && entity instanceof AbstractClientPlayer _rememF154
                            && !(entity instanceof LocalPlayer)) {
                        target.smRecentSwimDiveEndTick = _rememF154.tickCount;
                    }
                    boolean _recentSwimEndedFix154 = entity instanceof AbstractClientPlayer _checkRecent
                            && (_checkRecent.tickCount - target.smRecentSwimDiveEndTick) >= 0
                            && (_checkRecent.tickCount - target.smRecentSwimDiveEndTick) <= 10;
                    if (_recentSwimEndedFix154
                            && !wasCR_fix154 && target.isCrawling
                            && !wasHJ && !target.isHeadJumping
                            && !wasSliding && !target.isSliding
                            && !wasIcc && !target.isClimbCrawling
                            && !target.isClimbing && !target.isCeilingClimbing
                            && !target.isFlying && !target.isLevitating
                            && !target.isAngleJumping() && !target.isRopeSliding
                            && entity instanceof AbstractClientPlayer remoteSwCr
                            && !(entity instanceof LocalPlayer)) {
                        // 🔵 (2026-05-22 fix #154 v3) 1:1 with fix #99 v4 pattern — newY = srvY + 1.
                        //   Previous v2 (directly measure groundY) formula: getMaxPlayerSolidBetween clamp when locked
                        //     no ground top match (= yMax small). v2 formula at normal position tries ceiling match (= yMax large).
                        //   Solution: adopt fix #99 v4 formula — newY = srvY + 1. server side fix #153 v2 mirror push
                        //     applied → server.y = self.y = ground. broadcast S2C state time srvY prev = ground-1
                        //     (= previous broadcast remains). newY = prev srvY + 1 = ground = self.y. align REMOTE.y.
                        //   [project_remote_hjcr_direct_transition_fix] / [feedback_remote_setpos_newY_srvY_plus_1].
                        SmartMovingLivingEntityAccess accSwCr =
                                (SmartMovingLivingEntityAccess)(Object) remoteSwCr;
                        double newYSwCr = accSwCr.sm_getServerY() + 1.0;
                        remoteSwCr.setPos(remoteSwCr.getX(), newYSwCr, remoteSwCr.getZ());
                        remoteSwCr.yOld = newYSwCr;
                        remoteSwCr.yo = newYSwCr;
                        accSwCr.sm_setServerY(newYSwCr);
                        accSwCr.sm_setBodyTrackingIncrements(0);
                        target.smRecentSwimDiveEndTick = -9999;  // Clear once only.
                    }
                    if (wasHJ && !target.isHeadJumping
                            && !target.isSliding && !target.isCrawling
                            && entity instanceof AbstractClientPlayer remoteHJ
                            && !(entity instanceof LocalPlayer)) {
                        SmartMovingLivingEntityAccess accH =
                                (SmartMovingLivingEntityAccess)(Object) remoteHJ;
                        double bbMin = remoteHJ.getBoundingBox().minY;
                        // 🔴 fix #92 (2026-05-13, dump verification — user report "sometimes briefly stuck then recovers"):
                        //   Previous yMax = bbMin - 0.01: on locked state (= bbMin < ground top) search range
                        //   no ground top match → groundY = bbMin - 0.01 (= remains locked position).
                        //   Guard |newY - curY| > 0.05 no match (shallow lock 0.010m) → setPos not applied →
                        //   srvY = locked position + bti=0 lerp cancel → client.y remains locked → on next broadcast
                        //   (= srvY=ground top) arrival vanilla lerp recovers in ~3 tick.
                        //   fix: yMax = bbMin + 0.5 (= extend 0.5m upward) → locked state also matches ground top →
                        //   groundY accurate. ceiling (= near bbMin+1.0) no match (= 1 block space safe).
                        //   + relax guard to 0.005m → shallow lock (= 0.010m) also gets setPos applied.
                        double groundY = choco.ratel.smartmoving.client.SmartMovingClientState
                                .getMaxPlayerSolidBetween(remoteHJ, bbMin - 1.5, bbMin + 0.5, 0);
                        // 🔵 (2026-05-14, fix #98 v3 — use prevY): result of client tick order analysis.
                        //   1) networkHandler.tick → packet callback queue
                        //   2) world.tick → entity.tick → lerpPosAndRotation (= REMOTE.y 1-step progress)
                        //   3) processTasks → packet processing (= this branch)
                        //   4) render
                        //   = at packet processing time remoteHJ.getY() already progressed through lerp (= reached ground).
                        //   → hold condition (curY > groundY+0.005) no match → cycle 2-9 fix has no effect.
                        //   Solution: use prevY (= previous frame y, before lerp progress). precisely detect
                        //     airborne position right before EXIT. lastRenderY and prevY both same value (= right after resetPosition).
                        double curY = remoteHJ.yo;
                        double diff = curY - groundY;  // positive = airborne, negative = locked
                        // 🔵 (2026-05-14, fix #98 position-based) separate fix #92 guard — locked vs airborne.
                        //   Locked state (= curY < groundY - 0.005): existing fix #92 — setPos + lerp cancel.
                        //   Airborne state (= curY > groundY + 0.005): user reported 3 times BUG cause. no setPos
                        //     (= REMOTE.y reaches ground naturally via own vanilla lerp). meanwhile pose visual
                        //     hold flag set + groundY saved. setupTransforms X/Y rotation branch guard adds with OR
                        //     → maintain pose. sm_tickStatsForRemote TAIL position comparison (= REMOTE.y <=
                        //     groundY+0.005) releases hold. not tick timeout (= user principle match).
                        if (diff < -0.005) {
                            // Locked state — existing fix #92.
                            double newY = groundY;
                            remoteHJ.setPos(remoteHJ.getX(), newY, remoteHJ.getZ());
                            remoteHJ.yOld = newY;
                            remoteHJ.yo = newY;
                            // 🔴 lerp cancel — same pattern as BUG-7 v25.3 / BUG-15 / BUG B.
                            accH.sm_setServerY(newY);
                            accH.sm_setBodyTrackingIncrements(0);
                        } else if (diff > 0.005) {
                            // 🔵 (fix #98 v5) Airborne state — setPos(prevY) + 4-sync + lerp cancel + hold.
                            //   Dump verification: srvY=71 (= remains before self.y push) → REMOTE.y lerp chases locked position
                            //     → getY < groundY → user report "stuck on landing" cause.
                            //   Solution: setPos(curY=prevY) + lerp cancel keeps REMOTE.y at airborne
                            //     position. on next broadcast (= server.y=ground reached) arrival resumes lerp →
                            //     REMOTE.y → ground. blocked locked trajectory during pose hold.
                            //   Memory pattern: feedback_remote_setpos_lerp_cancel 4-sync.
                            double newY = curY;  // prevY = remains airborne position.
                            remoteHJ.setPos(remoteHJ.getX(), newY, remoteHJ.getZ());
                            remoteHJ.yOld = newY;
                            remoteHJ.yo = newY;
                            accH.sm_setServerY(newY);
                            accH.sm_setBodyTrackingIncrements(0);
                            // Set pose visual hold flag.
                            target.smRemoteHJVisualHold = true;
                            target.smRemoteHJExitGroundY = groundY;
                        }
                    }

                    // 🔴 (BUG D fix, 2026-05-13) remote flying exit — 1:1 mapping to self side standupIfPossible.
                    //   Estimate 3 branches of self side `standupIfPossible(player, tryLanding=true, restoreFromFlying)` via
                    //   *result bits* (target.isSliding/isCrawling) + *gap measurement* + apply each
                    //   branch entity.y change formula 1:1:
                    //     - Branch 1 (resetHeightOffset, gap>=1 && !sneak): no entity.y change. lerp cancel only.
                    //     - Branch 2 (standUp, gap<1): entity.y += (1 - gap).
                    //     - Branch 3 (toSlidingOrCrawling, sneak+grab): entity.y -= gap. result isSliding/
                    //       isCrawling=true → identify via packet.
                    //
                    //   Memory feedback_self_to_server_remote_one_to_one — "1:1 replication of self code as-is".
                    //   Memory feedback_packet_lambda_immediate_fix — "immediate fix inside packet lambda".
                    //   More accurate than existing sm_handleRemoteFlyingExitYSync (= per tick HEAD inject, fixed +1m) —
                    //   exact entity.y change formula per branch.
                    //
                    //   Avoid conflict with slide entry fix: add !wasFlying guard to slide entry fix → flying →
                    //   slide case is *this branch (= branch 3)* with -gap push.
                    // 🔴 (BUG D fix, 2026-05-13) remote flying exit — 1:1 mapping to self side standupIfPossible.
                    //   Estimate branch via 3 branch result bits from self side + srvY-based gap measurement +
                    //   apply entity.y change formula per branch 1:1.
                    //
                    //   Gap measurement: use srvY + 1.0 (= server accurate value) instead of flyBoxMinYBefore (= lerp lag remains).
                    //   newY formula: directly based on groundY (= map to self.y_final, remove gap measurement inaccuracy effect).
                    //
                    //   Derive self side formula + dump verification:
                    //     - Branch 1 (resetHO, gap>=1 && !sneak): no self.y change.
                    //     - Branch 2 (standUp, gap<1): self.y_new = self.y + (1 - gap) = groundY.
                    //     - Branch 3 (toSldOrCr, sneak+grab): self side `move(0, -gap, 0)` no self.y change via vanilla
                    //       collision (= dump delta=0). when setPos applied remote.y
                    //       (= vanilla lerp lag remains) ↔ self.y_self 1m difference creates *visual jump*.
                    //       Solution: remove branch 3 setPos+lerp cancel itself → vanilla lerp handles naturally
                    //       (= per tick 1/bti step gradual, ~15 tick smooth transition).
                    if (wasFlying && !target.isFlying
                            && entity instanceof AbstractClientPlayer remoteFly
                            && !(entity instanceof LocalPlayer)) {
                        SmartMovingLivingEntityAccess accF =
                                (SmartMovingLivingEntityAccess)(Object) remoteFly;
                        double srvY = accF.sm_getServerY();
                        double srvBBMinY = srvY + 1.0;  // Flying mixin offset active = bb feet = self.y + 1.
                        double groundY = choco.ratel.smartmoving.client.SmartMovingClientState
                                .getMaxPlayerSolidBetween(remoteFly,
                                        srvBBMinY - 1.1, srvBBMinY + 0.5, 0);
                        double gap = srvBBMinY - groundY;
                        boolean groundClose = gap < 1.0;

                        // Branch decision:
                        //   - Branch 2 (standing landing): !crawl && !slide && groundClose → serverY=groundY,
                        //     guarantee bti (>=3) → vanilla lerp progresses gradually. no setPos (= block visual jump).
                        //   - Branch 3-A (natural crawl, BUG E): isCrawling && groundClose → setPos(srvY+1.0)
                        //     + lerp cancel. map self side L3908 push +1m.
                        //   - Branch 3-B (sneak+grab slide): isSliding → skip fix (BUG D: verified no self.y change,
                        //     vanilla lerp handles).
                        //   - Branch 1 (gap>=1): groundClose=false → skip fix.
                        if (!target.isSliding && !target.isCrawling && groundClose) {
                            // 🔴 BUG E regression fix (2026-05-12): setPos+bti=0 = visual jump root.
                            //   When entry frame remote.y is mid vanilla lerp (e.g. 71.254)
                            //   setPos(groundY=71.000) + bti=0 = immediate 0.254m visual jump down.
                            //   Solution: remove setPos/lastRenderY/prevY changes. only set
                            //   serverY (= lerp target) as newY + keep bti (force 3 if 0) → vanilla lerp progresses gradually →
                            //   maintain sink prevention effect + remove visual jump.
                            double newY = groundY;
                            accF.sm_setServerY(newY);
                            int curBti = accF.sm_getBodyTrackingIncrements();
                            if (curBti < 3) {
                                accF.sm_setBodyTrackingIncrements(3);
                            }
                        } else if (target.isCrawling && !target.isSliding && groundClose) {
                            // 🔴 BUG E fix (2026-05-12): Branch 3-A natural crawl push +1m mapping.
                            //   self side `SmartMovingClientState.standupIfPossible` L3908 guard matches:
                            //     `wasSmallBox && isCrawling && wasFlying` → setPos(y + 1.0).
                            //   self.y_final = srvY + 1.0. here srvY is accurate value server about to broadcast
                            //   (= right before flying exit broadcast). self side c2s packet reaches server +
                            //   server broadcast → vanilla srvY updates after ~1 tick = 71.
                            //   Solution: on remote side *immediately now* setPos(srvY+1) + lerp cancel.
                            //
                            //   Correct misinterpretation: existing BUG D fix assumed "branch 3 = no self.y change = skip fix" —
                            //   only sneak+grab slide case has no self.y change. natural crawl case has +1m.
                            double newY = srvY + 1.0;
                            if (newY != remoteFly.getY()) {
                                remoteFly.setPos(remoteFly.getX(), newY, remoteFly.getZ());
                                remoteFly.yOld = newY;
                                remoteFly.yo = newY;
                            }
                            accF.sm_setServerY(newY);
                            accF.sm_setBodyTrackingIncrements(0);
                        }
                        // Branch 1 (gap>=1) / Branch 3-B (sneak+grab slide) — skip fix, vanilla lerp handles.
                    }
                });
            });

        // 🔵 Stats: self → server → REMOTE per tick stats angle sync (2026-05-14, Option F fix #97).
        //   Cause: vanilla lerpPosAndRotation interpolates REMOTE position over 5-tick equal division → realDxYz
        //     flattening + spike → atan2 result thetaT flows differently from self trajectory → lerpFadeAngle
        //     0.2 lerp insufficient time to chase peak → insufficient head rotation at end of head jump.
        //   Application: set sm.smRemoteStatsVerticalAngle/HorizontalAngle_fromPacket on packet arrival.
        //     Override own stats.calculate result with packet value in sm_tickStatsForRemote TAIL.
        ClientPlayNetworking.registerGlobalReceiver(SmartMovingNetwork.StatsPayload.ID,
            (payload, context) -> {
                Minecraft client = context.client();
                client.execute(() -> {
                    ClientLevel world = client.level;
                    if (world == null) return;
                    Entity entity = world.getEntity(payload.entityId());
                    if (!(entity instanceof AbstractClientPlayer ap)) return;
                    if (ap == client.player) return;  // Safety guard — server broadcasts excluding self.
                    SmartMovingClientState target = SmartMovingClientState.get(ap);
                    target.smRemoteStatsVerticalAngle_fromPacket = payload.verticalAngle();
                    target.smRemoteStatsHorizontalAngle_fromPacket = payload.horizontalAngle();
                    // 🔵 (2026-05-14 fix #97-v2): set sm.stats directly immediately on packet arrival.
                    //   Cause: vanilla MinecraftClient.tick order →
                    //     1) networkHandler.tick (= packet callback + client.execute queue)
                    //     2) world.tick → entity.tick → sm_tickStatsForRemote → stats.calculate + override
                    //     3) processTasks → client.execute lambda run → fromPacket set
                    //     4) render → setupTransforms
                    //   = on entry tick at sm_tickStatsForRemote time fromPacket=NaN → skip override →
                    //     stats.currentVerticalAngle = atan2(realDy=0, ...) = 0 remains →
                    //     at setupTransforms target=π/2 (= 90° immediate jump) → user report "entry disconnect".
                    //   fix: also immediately set stats inside packet receive lambda (= inside processTasks).
                    //     next entity.tick sm_tickStatsForRemote calls stats.calculate (= set 0) then
                    //     override (= packet value) → same result. block 1 tick stale for entry frame.
                    target.stats.currentVerticalAngle = payload.verticalAngle();
                    target.stats.currentHorizontalAngle = payload.horizontalAngle();
                    target.stats.prevHorizontalAngle = payload.horizontalAngle();
                });
            });

        // ConfigContent: Receive server config → switch Config (C-11)
        // Original: SmartMovingComm.processConfigContentPacket(content, username, blockCode=false)
        ClientPlayNetworking.registerGlobalReceiver(SmartMovingNetwork.ConfigContentPayload.ID,
            (payload, context) -> {
                String[] lines = payload.lines();
                Minecraft client = context.client();
                client.execute(() -> processConfigContentPacket(lines));
            });

        // ConfigChange: Server notifies "no permission to change config" (C-12)
        // Original: SmartMovingOptions.writeNoRightsToChangeConfigMessageToChat(isConnectedToRemoteServer())
        // A-26: Verify original — remote/local differentiated message (en_us.json smartmoving.message.config.illegal.*)
        // Shown on screen like the toggle key's own enabled/disabled status, not in chat.
        ClientPlayNetworking.registerGlobalReceiver(SmartMovingNetwork.ConfigChangePayload.ID,
            (payload, context) -> {
                Minecraft client = context.client();
                boolean isRemote = isRemoteServer(client);
                String key = isRemote
                    ? "smartmoving.message.config.illegal.remote"
                    : "smartmoving.message.config.illegal.local";
                client.execute(() -> {
                    if (client.player != null) client.player.sendOverlayMessage(Component.translatable(key));
                });
            });
    }

    // Original: SmartMovingComm.isConnectedToRemoteServer() — 3 conditions like MinecraftServer.getServer()==null
    // 1.21.1: if client.getServer() is null, it's remote server (no integrated server)
    private static boolean isRemoteServer(Minecraft client) {
        return client.getSingleplayerServer() == null;
    }

    // ── 13-1/13-2: processConfigContentPacket ────────────────────────────────
    //
    // Original: SmartMovingComm.processConfigContentPacket / processConfigPacket (check SmartMovingComm.md)
    // Based on config_system.md 2-4 / 3-4.
    //
    // Processing by content value:
    //   null         → SM completely disabled. Config = INSTANCE maintained.
    //   length == 0  → no server config, delegated to client. Config = INSTANCE.
    //   length > 0   → SERVER_CONFIG.loadFromArray(content) → Config = SERVER_CONFIG.
    //
    // On first receipt (first=true): send sendConfigInfo packet (original: SmartMovingConfig._sm_current = "3.2")
    // Note: Config restoration on server disconnect handled in registerConnectionEvents() DISCONNECT.
    // A-26: Verified message strings. keys → en_us.json smartmoving.message.config.server.*
    private static void processConfigContentPacket(String[] content) {
        // 🔴 (2026-05-05 user request — "config is own toggle. unaffected by other players"):
        //   Original intends broadcast only when explicitly enabled by server admin. our server mapping
        //   unconditionally broadcasts → all clients config changes → all SM disabled BUG.
        //   Solution: remove Config change itself. ignore server-broadcast — all clients use own INSTANCE.
        //   own disabled = don't update own SM state → don't send packets → other
        //   clients see own as vanilla (= correct).
        //   Keep SERVER_CONFIG itself updated for server-side options (= block-code etc) to be usable.
        //   But client Config remains INSTANCE as-is.
        Minecraft client = Minecraft.getInstance();
        if (content == null || content.length == 0) {
            // Only message (= original message). Don't change Config.
            return;
        }
        // Update SERVER_CONFIG (= for reading some server-side options. independent of client Config).
        SmartMovingConfig.SERVER_CONFIG.loadFromArray(content);
        // Send ConfigInfo (= server recognizes client version).
        ClientPlayNetworking.send(new SmartMovingNetwork.ConfigInfoPayload(SmartMovingConfig.SM_VERSION));
    }

    // ── 4-4: processBlockCode ────────────────────────────────────────────────

    // Original: handled SmartMovingComm.processBlockCode by directly scanning chat history (Reflect+GuiNewChat).
    // 1.21.1: replaced with ClientReceiveMessageEvents.ALLOW_GAME event. process immediately on message receive (no updateCounter<10 scan needed).
    // Original chatMessageList.remove(i--) correspondence: return false from ALLOW_GAME to suppress chat.
    private static void registerGameMessageHandler() {
        ClientReceiveMessageEvents.ALLOW_GAME.register((message, overlay) -> {
            // overlay=true is action bar (display above hotbar) → not block code target (verified B-19)
            if (overlay) return true;
            String text = message.getString();
            if (text.startsWith("§0§1") && text.endsWith("§f§f")) {
                processBlockCode(text);
                return false; // Original: chatMessageList.remove — suppress from chat
            }
            return true;
        });
    }

    // Original: SmartMovingComm.processBlockCode(String text) — public static boolean
    // 1.21.1: simplified to directly set SmartMovingConfig.Config fields (C-11: Config switch coordination).
    //
    // Format: "§0§1...§f§f" (first 4 chars = start marker, last 4 chars = end marker)
    // Change feature to config value corresponding to §code included in codes:
    //   §0 → baseClimb = true ("standard"), §1~§b → each feature = false
    // Codes not in codes unchanged (maintain current value).
    //
    // Text.getString() §code inclusion: possible if server sends as LiteralText (verified B-19).
    // Marker check before call (`§0§1`/`§f§f`) completed in registerGameMessageHandler.
    private static void processBlockCode(String text) {
        String codes = text.substring(4, text.length() - 4);
        SmartMovingConfig cfg = SmartMovingConfig.Config;

        // §0 → _baseClimb="standard" → all _isFreeBaseClimb/Smart/Simple = false
        if (codes.contains("§0")) { cfg.freeClimb = false; cfg.simpleClimb = false; cfg.smartClimb = false; }
        if (codes.contains("§1")) cfg.freeClimb = false;
        if (codes.contains("§2")) cfg.ceilingClimbing = false;
        if (codes.contains("§3")) cfg.swim = false;
        if (codes.contains("§4")) cfg.dive = false;
        if (codes.contains("§5")) cfg.crawl = false;
        if (codes.contains("§6")) cfg.slide = false;
        if (codes.contains("§7")) cfg.fly = false;
        if (codes.contains("§8")) cfg.jumpCharge = false;
        if (codes.contains("§9")) cfg.headJump = false;
        if (codes.contains("§a")) cfg.angleJumpSide = false;
        if (codes.contains("§b")) cfg.angleJumpBack = false;
    }
}
