package choco.ratel.smartmoving.server;

import choco.ratel.smartmoving.config.SmartMovingConfig;
import choco.ratel.smartmoving.network.SmartMovingNetwork;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.Attributes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Server-side SM state component per player.
 * Integrates original SmartMovingServerPlayerBase + SmartMovingServer using Map-based approach.
 * Uses UUID→SmartMovingServer map instead of Mixin @Unique injection —
 * Injecting fields directly into ServerPlayerEntity layer risks mixin conflicts,
 * and coupling with player lifecycle (JOIN/DISCONNECT) makes Map-based approach safer.
 */
public final class SmartMovingServer {

    /** Server-side console logger. Original: FMLLog (Forge). 1.21.1: SLF4J (vanilla logger chain). */
    private static final Logger LOGGER = LoggerFactory.getLogger("smartmoving");

    /** Y-direction expansion range for item pickup during crawling. Original: SmartMovingServer.SmallSizeItemGrabHeight = 0.25F */
    public static final double SMALL_SIZE_ITEM_GRAB_HEIGHT = 0.25;

    // ── 3-1 Server State Fields ────────────────────────────────────────

    /** Whether to reset fall distance in afterOnUpdate() (maintained while isClimbing family is true) */
    public boolean resetFallDistance;

    /** Whether to reset floatingTicks in afterOnUpdate() (3-type climbing) */
    public boolean resetTicksForFloatKick;

    /** Whether initialize() has completed */
    public boolean initialized;

    /** Whether onLivingUpdate is in progress */
    public boolean withinOnLivingUpdate;

    /** Cooldown after crawling ends (10 ticks) — used to suppress isInsideWall */
    public int crawlingCooldown;

    /** Server-side crawling state */
    public boolean isCrawling;
    /** 🔵 fix #153 v3 (2026-05-22): swim/dive → crawl multi-step transition history flag. */
    public int smRecentSwimDiveEndTick_fix153 = -9999;

    /** Server-side small size state. Server AABB updated via setSmall() → calculateDimensions() path. */
    public boolean isSmall;

    /** Client exhaustion value. -1=suppression disabled, 0=no exhaustion */
    public float hunger = -1F;

    /** Exhaustion suppression nesting depth */
    public int disableAddExhaustionDepth;

    /** Whether to disable additional exhaustion */
    public boolean disableAddExhaustion;

    /** Client sneak button state (State packet bit 33) */
    public boolean isSneakButtonPressed;

    /** isSneaking() force override. null=not forced, true/false=forced return value */
    public Boolean forceIsSneaking;

    /** Accumulated climbing movement distance (for fatigue calculation). Original distanceClimbedModified ported. */
    public double distanceClimbedModified;

    /** SM version string sent by client (ConfigInfo packet). null=not received */
    public String clientVersion;

    // ── Movement State Decoded from Packet ──────────────────────────────

    public boolean isClimbing;
    public boolean isCrawlClimbing;
    public boolean isCeilingClimbing;
    public boolean isWallJumping;

    // ── Focus #2.7 D-1 (Session 4): Client/Server POSE Complete Symmetry — 5 Fields Added ──
    // Original uses PacketStream 7-bit sync only, server dimensions determined by isSmall alone.
    // However, 1.21.1 vanilla POSE system requires identical branch mapping on both sides (prevent datatracker sync oscillation).
    // Phase 1 client already maps 8 SM states → 4 branches (SWIMMING/SLIDING),
    // so server needs additional bit decoding for identical branch handling. SmartMovingState already encodes 21 bits
    // (bit 9 isDiving / 11 isSwimming / 19 isLevitating / 20 isHeadJumping / 21 isSliding) —
    // server only needs to add decoding.

    /** SmartMovingState bit 9. SM diving state. Maps to POSE.SWIMMING. */
    public boolean isDiving;
    /** SmartMovingState bit 11. SM swimming state. Maps to POSE.SWIMMING. */
    public boolean isSwimming;
    /** SmartMovingState bit 19. Levitate potion state. Maps to POSE.SLIDING. */
    public boolean isLevitating;
    /** SmartMovingState bit 20. SM head jump state. Maps to POSE.SLIDING. */
    public boolean isHeadJumping;
    /** SmartMovingState bit 21. SM sliding state. Maps to POSE.SLIDING. */
    public boolean isSliding;
    /**
     * SmartMovingState bit 22. SM climb crawling state. Box +1 mixin offset applied.
     * User-reported fix (X/Z pulling): 1m Y difference between client/server box during ICC active → server reconcile.
     * Additional bit for client/server dimension synchronization.
     */
    public boolean isClimbCrawling;

    // Movement prototypes (docs/research_movement_prototypes.md), SmartMovingState bit 40.
    /** bit 40: vertical chain climb (relayed; isClimbing bit 14 carries the server behavior). */
    public boolean isChainClimbing;

    /**
     * 🔴 (Phase 2 multi-network optimization) For server-side broadcast dirty check.
     *   Even if client sends identical state packet every tick, server compares with last broadcast bits →
     *   broadcasts to other clients only on change. Traffic savings of 80-90% (e.g. 100-player hub environments).
     *   Late join handling: Replay lastBroadcastBits to new tracker at EntityTrackingEvents.START_TRACKING.
     */
    public long lastBroadcastBits = 0L;

    // ── Instance Management ─────────────────────────────────────────────

    private static final Map<UUID, SmartMovingServer> INSTANCES = new HashMap<>();

    /** Returns the player's SmartMovingServer instance. Creates new one if it doesn't exist. */
    public static SmartMovingServer get(ServerPlayer player) {
        return INSTANCES.computeIfAbsent(player.getUUID(), id -> new SmartMovingServer());
    }

    /** Removes the instance when player disconnects. */
    public static void remove(ServerPlayer player) {
        INSTANCES.remove(player.getUUID());
    }

    // ── 3-1 + 3-9: State Packet Handling ──────────────────────────────

    /**
     * Extracts only the bits the server needs from the long bit field of State packet.
     * Ported from original SmartMovingServer.processStatePacket().
     *
     * Bits read by server:
     *   bit 12: isCrawlClimbing  (original)
     *   bit 13: isCrawling       (original)
     *   bit 14: isClimbing       (original)
     *   bit 15: isSmall          (original)
     *   bit 18: isCeilingClimbing (original)
     *   bit 31: isWallJumping    (original)
     *   bit 33: isSneakButtonPressed (original)
     *
     * **Focus #2.7 D-1 (Session 4)**: Complete Client/Server POSE Symmetry — 5 Bits Added Decoding.
     * Server decodes unused PacketStream bits (already encoded by 1.21.1 SmartMovingState)
     * so sm_updatePose_server can handle identical 4-branch logic as client.
     *   bit  9: isDiving        ★ New (Phase D-1, maps to POSE.SWIMMING)
     *   bit 11: isSwimming      ★ New (Phase D-1, maps to POSE.SWIMMING)
     *   bit 19: isLevitating    ★ New (Phase D-1, maps to POSE.SLIDING)
     *   bit 20: isHeadJumping   ★ New (Phase D-1, maps to POSE.SLIDING)
     *   bit 21: isSliding       ★ New (Phase D-1, maps to POSE.SLIDING)
     */
    public void processStatePacket(ServerPlayer player, long bits) {
        // 🔴 fix #90 (2026-05-13, BUG: remote stuck against wall then slide→crawl transition model locks 1m then recovers):
        //   Save wasSL/wasCR at start of processStatePacket — used to check auto slide→crawl transition match at end.
        boolean _wasSL_fix90 = this.isSliding;
        boolean _wasCR_fix90 = this.isCrawling;
        // 🔵 fix #98-v2 (2026-05-14, BUG 3: REMOTE collision ground touch before head jump ends + lock on landing):
        //   User instruction — verify 3-way sync server/self/remote + memory review results.
        //   Memory key: feedback_server_side_state_push_mirror — "when self side setPos call detected,
        //     must add mirror call in identical match branch in server side processStatePacket".
        //   self side standupIfPossible standUp branch (= L3760, setPos(getY()+1) when gap=0) +
        //     toSlidingOrCrawling branch + fix #88 resetHO branch all do entity.y +1m push.
        //   Existing server side: only mirrors fix #90 (slide→crawl). Missing isHJ=true→false mirror →
        //     server.y broadcast = self.y c2s sync timing difference causes lerp → REMOTE.y < ground
        //     (= lock moment) or REMOTE.y > ground (= standing in air) BUG occurs.
        //   fix: Identical mapping of +1m push from self side standUp to server side.
        boolean _wasHJ_fix98 = this.isHeadJumping;
        // 🔵 fix #153 v2 (2026-05-22 restored): swim → crawl transition server mirror push.
        //   log measurement: SERVER-CR tick=616 server.y=70 persists (= state packet processed before c2s movement).
        //   self.y c2s = 71 (= setPos+1). When processing state packet, server.y = prev (= 70). Mirror push needed.
        boolean _wasSwim_fix153 = this.isSwimming;
        boolean _wasDive_fix153 = this.isDiving;

        isClimbing        = ((bits >> 14) & 1) != 0;
        isCrawlClimbing   = ((bits >> 12) & 1) != 0;
        isCeilingClimbing = ((bits >> 18) & 1) != 0;
        isWallJumping     = ((bits >> 31) & 1) != 0;
        // Movement prototypes (bit 40; 37-39 are unused).
        isChainClimbing   = ((bits >> 40) & 1) != 0;
        boolean _oldCrawl = isCrawling;
        setCrawling(((bits >> 13) & 1) != 0);
        // 🔴 (2026-05-22) User-reported fix: 1-block space swim end + crouch → water entry server reconcile.
        //   cause: isSmall formula (= 8 SM OR) unchanged during swim ↔ crawl transition (swim=true → crawl=true).
        //     → setSmall not called → server calculateDimensions not called → server dim persists (eye=1.62) +
        //     client dim updated (eye=0.62) → mixin offset guard match differs → server box +1m up,
        //     client box normal → server reconcile → user verbatim "crouch sometimes" / "standing upward" /
        //     "ground lock" all same cause.
        //   fix: Explicit calculateDimensions call when bit 13 (isCrawling) changes — same pattern as other bits
        //     (= isClimbCrawling L246 / isHeadJumping L213 / isSliding L235).
        //   memory: feedback_processStatePacket_calculateDimensions, feedback_server_reconcile_box_sync.
        if (_oldCrawl != isCrawling) {
            player.refreshDimensions();
        }
        // R-04: calculateDimensions() called via setSmall() → server AABB updated
        boolean newSmall = ((bits >> 15) & 1) != 0;
        if (newSmall != isSmall) setSmall(player, newSmall);
        isSneakButtonPressed = ((bits >> 33) & 1) != 0;

        // ── Focus #2.7 D-1 New Decoding (Complete POSE Symmetry) ──────────────
        isDiving       = ((bits >>  9) & 1) != 0;
        isSwimming     = ((bits >> 11) & 1) != 0;
        isLevitating   = ((bits >> 19) & 1) != 0;
        // 🔴 fix #42 (2026-05-09, user-reported "collision locked 1 block up"):
        //   isHeadJumping/isSliding change detection + calculateDimensions() call added.
        //   Why: When only decoding without refresh, server dim cache (= STANDING 1.8) persists → mixin offset
        //     (= height<1 && eyeHeight>1) not applied → server box (entity.y, entity.y+1.8) →
        //     perceives collision ground lock → sends server position correction packet → client position reset
        //     → entity stops + infinite reconcile.
        //   fix: Same pattern as ICC (bit 34) — change detection + calculateDimensions() call →
        //     dim updates immediately → mixin offset activates immediately → server box = client box same position →
        //     no server reconcile.
        //   memory: feedback_server_reconcile_box_sync + project_isclimbcrawling_complete pattern.
        // 🔴 fix #55 (2026-05-10, user-reported "after fix #53 bounces like stuck against wall"):
        //   client uses fix #29 headJumpCharge==0 guard to skip calculateDimensions in case A (=headJumpCharge>0)
        //   → dim cache (=eye 1.62) persists → mixin offset active →
        //   box foot = entity.y+1m = ground (correct).
        //   server unconditionally calls calculateDimensions with fix #42 → dim updated (=eye 0.62,
        //   smSmall match with isHeadJumping=false+isSliding=true) → mixin offset blocked → box foot
        //   = entity.y = original ground -1m → perceives ground lock → server position correction
        //   packet → client position reset → user reported "bounces like stuck against wall".
        //   fix: server also dim cache persists like client (= call only at enter, skip at exit).
        //     - isHeadJumping=false→true (enter): call (maintains fix #42 intent, prevents collision 1-block lock regression).
        //     - isHeadJumping=true→false (exit): skip (= fix #53 scenario + normal head jump exit).
        //     - isSliding=false→true (enter): skip (= fix #53 scenario + normal slide enter).
        //     - isSliding=true→false (exit): call (= box SLIDING→STANDING normal restore).
        //   1-tick lag scenario (= skip at enter) vanilla updatePose auto-call refreshes dim →
        //   persists equivalent to client → no reconcile.
        boolean newHeadJumping = ((bits >> 20) & 1) != 0;
        if (newHeadJumping != isHeadJumping) {
            isHeadJumping = newHeadJumping;
            if (newHeadJumping) player.refreshDimensions();   // Call only on enter.
        }
        // 🔴 fix #63 (2026-05-10, user-reported "sliding model box 1m below + exit box lock"):
        //   fix #55 isSliding branch guard (= skip on enter) causes server reconcile during *normal self slide enter*.
        //   Normal enter flow:
        //     - Before enter: isHeadJumping=false, isSliding=false → server dim = STANDING (1.8).
        //     - After enter: isSliding=true. fix #55 → skip calculateDimensions → server dim
        //       STANDING persists → mixin offset guard mismatch (height>=1) → blocks → server box foot =
        //       entity.y = ground - 1m (= sync with fix #60 result) → server box lock 1m below ground.
        //     - server reconcile or model position handling mismatch.
        //   fix: Call calculateDimensions on isSliding=false→true enter when isHeadJumping=false
        //         (= server dim updates → fix #59 match → eye=1.62 → mixin offset active → box +1m
        //         up = ground aligned). Skip when isHeadJumping=true (= fix #53 scenario = maintain
        //         server dim cache effect during concurrent head jump + self slide enter).
        //   fix #42 history BUG (= "collision locked 1 block up") also exactly mapped recover via fix #63.
        boolean newSliding = ((bits >> 21) & 1) != 0;
        if (newSliding != isSliding) {
            isSliding = newSliding;
            if (newSliding) {
                // On enter: call only when isHeadJumping=false (= normal slide enter).
                //   Skip when isHeadJumping=true (= fix #53 scenario: concurrent head jump + self slide).
                if (!isHeadJumping) {
                    player.refreshDimensions();
                }
            } else {
                // On exit: always call (= STANDING restore).
                player.refreshDimensions();
            }
        }
        // ── X/Z Pull Fix: Client/Server Box Sync (bit 34, bit 22 uses angleJumpType) ──
        boolean newClimbCrawling = ((bits >> 34) & 1) != 0;
        if (newClimbCrawling != isClimbCrawling) {
            isClimbCrawling = newClimbCrawling;
            player.refreshDimensions();
        }

        // 3-9: Fall distance reset condition (includes wall jump)
        resetFallDistance     = isClimbing || isCrawlClimbing || isCeilingClimbing || isWallJumping;
        // 3-2: floatingTick reset condition (excludes wall jump — wall jump is instantaneous so no kick risk)
        resetTicksForFloatKick = isClimbing || isCrawlClimbing || isCeilingClimbing;

        // 🔴 fix #90 (2026-05-13, user-reported "remote stuck against wall then slide→crawl model locks 1m then recovers"):
        //   Match condition: wasSL=true && !newSL && !wasCR && newCR (= slide → crawl auto transition).
        //
        //   Cause (verified via dump log_temp_2.txt):
        //     On server packet reception: setCrawling + setSliding(false) → only calculateDimensions call.
        //     No explicit entity.y push → relies on vanilla auto push out of blocks.
        //     - Flat ground (no wall): vanilla auto push completes +1m within 1 tick → server.y=71 broadcast immediately.
        //     - Stuck against wall: vanilla auto push *combines horizontal+vertical* → only +0.2m per tick → next tick +0.8m.
        //       server.y trajectory: 70 → 70.2 → 71. broadcast lag.
        //     client (remote) vanilla lerpPosAndRotation chases srvY every tick → client.y 70.733
        //     → 70.822 → ... → 71 (~500ms lerp) → user sees "locks then recovers".
        //
        //   fix: Mirror mapping of self side fix #70 (= SmartMovingClientState L2455 setPos(y+1)) to server side.
        //     Explicit setPos(y+1) call at end of processStatePacket → server.y immediately 71 → broadcast srvY=71
        //     → client lerp target=71 → no lock moment.
        //
        //   Regression check:
        //     - Normal (no wall): After setPos(71), box foot=71=1m above flat. Vanilla auto push not triggered (requires
        //       *bb collision* condition) → only +1m applied. No regression.
        //     - Stuck against wall: After setPos(71), box position = (71, 71.8). Self on flat beside wall → box doesn't
        //       penetrate wall (= only match wall edge top). Safe.
        if (_wasSL_fix90 && !this.isSliding && !_wasCR_fix90 && this.isCrawling) {
            player.setPosRaw(player.getX(), player.getY() + 1.0, player.getZ());
        }

        // 🔵 fix #99 v3 (2026-05-14): Direct HJ → CR transition server-side mirror.
        //   User-reported: head jump + wall collision + GRAB+SNEAK → auto crouch REMOTE locked visual.
        //   Actual path: HJ:true→false + CR:false→true simultaneous (= self side toSlidingOrCrawling
        //     else branch → toCrawling). Self side `toCrawling` doesn't push entity.y →
        //     c2s position packet also persists ground-1m → server.y no auto push → broadcast srvY
        //     = ground - 1m → REMOTE locked visual.
        //   fix: Adopt fix #90 (= SL→CR) pattern — direct setPos(y+1) push at end of server side processStatePacket.
        //     server.y immediately ground → broadcast srvY=ground →
        //     [feedback_server_side_state_push_mirror] pattern.
        //   client side: fix #99 v4 aligns immediately with setPos(srvY + 1) after broadcast arrival.
        if (_wasHJ_fix98 && !this.isHeadJumping
                && !_wasSL_fix90 && !this.isSliding
                && !_wasCR_fix90 && this.isCrawling
                && !this.isClimbCrawling && !this.isClimbing && !this.isCeilingClimbing
                && !this.isSwimming && !this.isDiving && !this.isLevitating) {
            player.setPosRaw(player.getX(), player.getY() + 1.0, player.getZ());
        }

        // 🔵 fix #153 v3 (2026-05-22 history flag): swim/dive → crawl mirror push.
        //   log measurement: Server side also capable of multi-step state changes (= cause of 2 packets received from REMOTE).
        //   fix: history flag — set on swim/dive end detection, detect + push within 5 ticks on crawl enter.
        boolean _smEndedFix153 = (_wasSwim_fix153 || _wasDive_fix153)
                && !this.isSwimming && !this.isDiving;
        if (_smEndedFix153) {
            this.smRecentSwimDiveEndTick_fix153 = player.tickCount;
        }
        boolean _recentSwimEndedFix153 = (player.tickCount - this.smRecentSwimDiveEndTick_fix153) >= 0
                && (player.tickCount - this.smRecentSwimDiveEndTick_fix153) <= 10;
        if (_recentSwimEndedFix153
                && !_wasCR_fix90 && this.isCrawling
                && !_wasHJ_fix98 && !this.isHeadJumping
                && !_wasSL_fix90 && !this.isSliding
                && !this.isClimbCrawling && !this.isClimbing && !this.isCeilingClimbing
                && !this.isLevitating) {
            player.setPosRaw(player.getX(), player.getY() + 1.0, player.getZ());
            this.smRecentSwimDiveEndTick_fix153 = -9999;
        }

        // 🔵 fix #98-v2 (2026-05-14): isHeadJumping=true → false + all other SM phases false match
        //   Apply identical +1m push as self side standUp branch. Matches exactly self side L3926-L3930 standUp condition
        //   "force POSE=STANDING when all SM states false".
        //   Guard conditions (match self side L3926):
        //     - wasHJ=true → newHJ=false (= head jump end edge).
        //     - All other SM phases false (= simple end, not auto transition to self slide/fly).
        //   Regression check:
        //     - Other phase transitions (= head jump → auto slide transition etc): guard doesn't match → skip setPos.
        //     - Normal end: only apply +1m. Vanilla auto push (requires *box collision* condition) doesn't trigger.
        if (_wasHJ_fix98 && !this.isHeadJumping
                && !this.isSliding && !this.isCrawling && !this.isCrawlClimbing
                && !this.isClimbing && !this.isCeilingClimbing
                && !this.isSwimming && !this.isDiving && !this.isLevitating) {
            player.setPosRaw(player.getX(), player.getY() + 1.0, player.getZ());
        }
    }

    // ── 3-1-A: Crawling State Transition + Cooldown Setting ──────────────────────

    /**
     * Updates crawling state and sets crawlingCooldown to 10 on end.
     * Original: SmartMovingServer.setCrawling(boolean)
     * During cooldown, isInsideWall() returns false to prevent false wall collision at crawl exit.
     */
    public void setCrawling(boolean crawling) {
        if (!crawling && isCrawling) crawlingCooldown = 10;
        isCrawling = crawling;
    }

    // ── 3-9: Fall Distance Reset ──────────────────────────────────────

    /**
     * Resets fall distance during climbing/crawl climbing/ceiling climbing/wall jump.
     * Original: fallDistance=0, motionY=0.08 (minimum upward initial velocity to counteract gravity).
     */
    public void applyFallDistanceReset(ServerPlayer player) {
        player.fallDistance = 0;
        double vx = player.getDeltaMovement().x;
        double vz = player.getDeltaMovement().z;
        player.setDeltaMovement(vx, player.getAttributeValue(Attributes.GRAVITY), vz);
    }

    // ── 3-3: Exhaustion Batch System ────────────────────────────────────

    /**
     * Called just before movement hunger batch start — prevents exhaustion addition.
     * Original SmartMovingServerPlayerBase.afterUpdatePotionEffects() → moving.beforeAddMovingHungerBatch()
     * (before/after intentionally reversed — aligns with potion update cycle timing)
     */
    public void beforeAddMovingHungerBatch() {
        disableAddExhaustionDepth++;
        // Original: if(hunger != -1) disableAddExhaustion = true;
        // hunger = -1F (initial/not received) → vanilla exhaustion allowed, hunger >= 0 → SM overrides
        if (hunger >= 0F) disableAddExhaustion = true;
    }

    /**
     * Called after movement hunger batch completion — releases exhaustion addition block.
     * Original SmartMovingServerPlayerBase.beforeUpdatePotionEffects() → moving.afterAddMovingHungerBatch()
     */
    public void afterAddMovingHungerBatch() {
        disableAddExhaustionDepth--;
        disableAddExhaustion = disableAddExhaustionDepth > 0;
    }

    // ── 3-6: Server Hitbox Dynamic Change ─────────────────────────────

    /**
     * Updates server-side isSmall hitbox.
     * calculateDimensions() calls getBaseDimensions() (MixinPlayerEntity) to
     * update server bounding box + client sync automatically.
     */
    public void setSmall(ServerPlayer player, boolean small) {
        this.isSmall = small;
        player.refreshDimensions();
    }

    // ── 3-7: Player Connection Initialization ────────────────────────────────

    /**
     * Sends server-to-client initialization packet on player connection.
     * Original: SmartMovingServer.initialize(player) (based on config_system.md 2-1)
     *
     * globalConfig=true: sends entire server config → client Config = ServerConfig switched.
     * Otherwise: new String[0] → delegates to client (Config = Options maintained).
     * username=null: no config edit permission.
     *
     * serverConfig (per-player config) not yet supported — globalConfig takes priority.
     */
    public static void initialize(ServerPlayer player, MinecraftServer server) {
        SmartMovingServer sm = SmartMovingServer.get(player);
        if (sm.initialized) return;
        sm.initialized = true;
        // 🔴 (2026-05-05 user request — "config is own toggle. Other players unaffected"):
        //   server INSTANCE.globalConfig=true (default) broadcasts config to all clients →
        //   client Config = SERVER_CONFIG forced → all clients SM enabled/disabled controlled BUG.
        //   fix: stop broadcast itself. Each client uses own INSTANCE.
        //   separate work for user-explicit partial options like server-side block-code.
        logConfigState(SmartMovingConfig.INSTANCE, null, false);
    }

    // ── 3-12: Admin Voluntary Config Change + Full Retransmit ───────────────────

    /**
     * Retransmits ConfigContent to all initialized players currently connected.
     * Original: SmartMovingServerComm iterates each player session after config change and sends writeToProperties.
     * 1.21.1: reuses same path as initialize, but forces send instead of sm.initialized check.
     */
    public static void broadcastConfig(MinecraftServer server) {
        // 🔴 (2026-05-05 user request — "config own toggle. Other players unaffected"):
        //   When adminToggleConfig (= server console / admin permission) called, all clients'
        //   Config changed → all players SM disabled BUG. Stop broadcast itself.
        //   server-side INSTANCE change maintained for server-only options (= block-code).
    }

    /**
     * Admin voluntary config toggle.
     * Original: SmartMovingServerOptions.toggle(player) (L66-L71):
     *   config.toggle(); saveToOptionsFile; logConfigState(config, username, true);
     * 1.21.1: SmartMovingConfig.INSTANCE.toggle() + save() + log + full retransmit.
     */
    public static void adminToggleConfig(ServerPlayer player) {
        SmartMovingConfig.INSTANCE.toggle();
        SmartMovingConfig.save();
        logConfigState(SmartMovingConfig.INSTANCE, player.getName().getString(), true);
        MinecraftServer server = player.level().getServer();
        if (server != null) broadcastConfig(server);
    }

    // ── 3-11: Server Console Logging (Original: SmartMovingServerOptions Log Methods) ─────────

    /**
     * Original: SmartMovingServerOptions.logConfigState(config, username, reconfig)
     *           (SmartMovingServerOptions.md L80-L113).
     *
     * The original also logged the current difficulty key; this port has no difficulty presets, so only
     * its no-key "default server configuration" branch is kept.
     *
     * globalConfig=true path:
     *   reconfig=false → prints "Smart Moving overrides client configurations" first.
     *   enabled=true  → "... default server configuration [by X]"
     *   enabled=false → "... disabled [by X]"
     * globalConfig=false:
     *   → "Smart Moving allows client configurations"
     */
    static void logConfigState(SmartMovingConfig config, String username, boolean reconfig) {
        String message = "Smart Moving ";
        if (config.globalConfig) {
            if (!reconfig) LOGGER.info("{}overrides client configurations", message);
            String postfix = getPostfix(username);
            if (config.enabled) {
                String action = reconfig ? "changed to " : "uses ";
                LOGGER.info("{}{}default server configuration{}", message, action, postfix);
            } else {
                LOGGER.info("{}disabled{}", message, postfix);
            }
        } else {
            LOGGER.info("{}allows client configurations", message);
        }
    }

    /** Original: SmartMovingServerOptions.getPostfix(username). username=null → empty string. */
    private static String getPostfix(String username) {
        if (username == null) return "";
        return " by user '" + username + "'";
    }

    // ── C-16: Receive ConfigInfo — Save Client SM Version ─────────────

    /** Original: SmartMovingComm.processConfigInfoPacket (server-side) — records client version. */
    public void processConfigInfoPacket(String info) {
        clientVersion = info;
    }

    // ── C-17: Receive ConfigChange — Deny Permission Response for Server Config Change ────────

    /**
     * Client requested server config toggle but server config enabled, always deny.
     * Original: Server sends ConfigChange S2C → client displays "no rights" message.
     */
    public static void processConfigChangePacket(ServerPlayer player) {
        ServerPlayNetworking.send(player, new SmartMovingNetwork.ConfigChangePayload());
    }

    // ── 3-8: Permission Check ───────────────────────────────────────────

    /**
     * Check permission for config change.
     * Original used == reference comparison, but 1.21.1 provides no String interning guarantee, so replaced with equals().
     */
    public static boolean hasPermission(String expected, String actual) {
        if (expected == null || actual == null) return false;
        return expected.equals(actual);
    }
}
