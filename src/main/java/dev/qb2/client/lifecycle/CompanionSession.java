package dev.qb2.client.lifecycle;

import dev.qb2.client.config.Settings;
import dev.qb2.cognition.decisions.Decision;
import dev.qb2.companion.attention.Attention;
import dev.qb2.companion.movement.*;
import dev.qb2.perception.*;
import dev.qb2.perception.chat.ChatHistory;
import dev.qb2.perception.world.WorldSensor;
import dev.qb2.rendering.bubbles.Bubbles;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public final class CompanionSession {
  public final Motion motion = new Motion();
  public final Attention attention = new Attention();
  public final ChatHistory chat = new ChatHistory();
  public final WorldSensor sensor = new WorldSensor();
  public final EventDeduplicator dedup = new EventDeduplicator();
  public final Bubbles bubbles = new Bubbles();
  public final Deque<String> recentThoughts = new ArrayDeque<>(),
      actions = new ArrayDeque<>(),
      observationsHistory = new ArrayDeque<>();
  public List<Observation> observations = List.of();
  public long tick, lastRequest = -10000, lastSpeech = -10000;
  public String action = "observe", state = "Idle";
  private long actionUntil;
  private Observation actionTarget;
  public final FlightPlanner flight = new FlightPlanner();
  public final PlayerTrail trail = new PlayerTrail();
  public final GrabThrowPhysics grabThrow = new GrabThrowPhysics();
  public final dev.qb2.companion.behavior.SituationalBehavior situational =
      new dev.qb2.companion.behavior.SituationalBehavior();
  public final dev.qb2.cognition.decisions.ActionQueue actionQueue =
      new dev.qb2.cognition.decisions.ActionQueue();
  public final dev.qb2.network.RemoteCompanionTracker peers =
      new dev.qb2.network.RemoteCompanionTracker();
  public final dev.qb2.memory.MachineGraphMemory machineMemory =
      new dev.qb2.memory.MachineGraphMemory();
  public final dev.qb2.perception.inventory.ContainerMemory containerMemory =
      new dev.qb2.perception.inventory.ContainerMemory();
  public final Map<String, Point> baseZones = new LinkedHashMap<>();
  public float lastRenderedYaw, lastRenderedPitch, lastRenderedRoll;
  public String pendingSyncSpeech = "";
  public boolean pendingWeeeeSound;
  public boolean pendingBoingSound;
  public boolean pendingSleepSound;
  public boolean pendingGentlePlace;
  public int catchVisitTicks;
  public Point catchVisitPos;
  public String serverTaskType = "";
  public Point serverTaskPos;
  public int serverTaskTicks;
  public int combatActionTicks;
  public String lastMachineGraph = "";
  public long lastMachineGraphTick = -10000;
  public String flightMode = "Idle";
  public Point flightGoal = Point.ZERO;
  public volatile boolean apiBackoff;
  public int errorTintTicks;
  public boolean perched;
  public Point perchSpot;
  public Point perchLook;
  public Point guideDestination;
  public String guideLabel = "";
  private long perchedSinceTick;
  private Point calmAreaAnchor;
  private long calmAreaSinceTick;
  private Point lastTrackedPlayerPos;
  private float lastTrackedYaw, lastTrackedPitch;
  private long lastActivityTick;

  public boolean isSleeping() {
    return perched || situational.sleepingWithPlayer;
  }

  public void markPlayerActive() {
    lastActivityTick = tick;
  }

  public boolean playerIdle() {
    return tick > 160 && tick - lastActivityTick > 160;
  }

  public Observation activeTarget() {
    return actionTarget != null ? actionTarget : attention.target();
  }

  public void summon() {
    wakeUp();
    stopGuiding();
    action = "return";
    actionTarget = null;
    actionUntil = tick + 120;
  }

  public void startGuiding(Point dest, String label) {
    if (dest == null) return;
    wakeUp();
    guideDestination = dest;
    guideLabel = label == null || label.isBlank() ? "Target" : label;
    bubbles.thought("➔ " + guideLabel, tick);
  }

  public void stopGuiding() {
    guideDestination = null;
    guideLabel = "";
  }

  public void focusOn(Observation obs) {
    if (obs == null) return;
    wakeUp();
    attention.select(obs, tick);
    actionTarget = obs;
    action = "inspect";
    actionUntil = tick + 200;
    attention.holdUntil(actionUntil);
  }

  public record PerchPlacement(Point position, Point lookAt) {}

  public void perchAt(Point spot) {
    perchAt(new PerchPlacement(spot, null));
  }

  public void perchAt(PerchPlacement placement) {
    if (placement == null || placement.position() == null) return;
    boolean wasSleeping = isSleeping();
    perched = true;
    perchSpot = placement.position();
    perchLook = placement.lookAt();
    perchedSinceTick = tick;
    action = "rest";
    actionUntil = tick + 2400;
    if (!wasSleeping) pendingSleepSound = true;
  }

  public void applyServerTask(dev.qb2.network.CubeyNetwork.S2CTaskVisualPayload payload) {
    if (payload == null) return;
    wakeUp();
    String type = payload.taskType();
    Point pos = new Point(payload.x(), payload.y(), payload.z());
    int dur = Math.clamp(payload.durationTicks(), 12, 100);
    switch (type) {
      case "harvest" -> {
        serverTaskType = "harvest";
        serverTaskPos = pos.add(new Point(0, 0.35, 0));
        serverTaskTicks = dur;
        bubbles.emote("🌾", tick);
      }
      case "craft_start", "cook_start" -> {
        serverTaskType = type;
        serverTaskPos = pos.add(new Point(0, 0.45, 0));
        serverTaskTicks = dur;
        if ("cook_start".equals(type)) situational.activeHat = "chef";
        String label = shortCraftLabel(payload.itemId());
        bubbles.emote(("cook_start".equals(type) ? "🍳 " : "✦ ") + label + "...", tick);
      }
      case "craft_done" -> {
        serverTaskType = "";
        serverTaskPos = null;
        serverTaskTicks = 0;
        situational.celebrateTicks = 34;
        String label = shortCraftLabel(payload.itemId());
        bubbles.emote("🍞 " + label + "!", tick);
      }
      case "torch" -> {
        serverTaskType = "torch";
        serverTaskPos = pos.add(new Point(0, 0.35, 0));
        serverTaskTicks = dur;
        bubbles.emote("🔥", tick);
      }
      case "combat_dash" -> {
        serverTaskType = "combat_dash";
        serverTaskPos = pos;
        serverTaskTicks = 14;
        combatActionTicks = 18;
        bubbles.emote("⚔", tick);
      }
      case "combat_shoot" -> {
        combatActionTicks = 16;
        bubbles.emote("🏹", tick);
      }
      case "combat_shield" -> {
        combatActionTicks = 22;
        bubbles.emote("🛡", tick);
      }
      case "combat_heal" -> {
        situational.celebrateTicks = 28;
        bubbles.emote("♥ Heilung!", tick);
      }
      case "combat_zap" -> {
        combatActionTicks = 16;
        bubbles.emote("✦ Zap!", tick);
      }
      case "combat_flash" -> {
        combatActionTicks = 16;
        bubbles.emote("☀ Blitz!", tick);
      }
      case "feed" -> {
        situational.celebrateTicks = 24;
        bubbles.emote("🍞 *mampf!*", tick);
      }
      case "magnet" -> {
        serverTaskType = "magnet";
        serverTaskPos = pos;
        serverTaskTicks = dur;
        bubbles.emote("✦", tick);
      }
      case "deposit" -> {
        serverTaskType = "deposit";
        serverTaskPos = pos;
        serverTaskTicks = dur;
        bubbles.emote("📦 Abgelegt!", tick);
      }
      case "gift" -> {
        serverTaskType = "gift";
        serverTaskPos = pos;
        serverTaskTicks = dur;
        situational.celebrateTicks = 30;
        bubbles.emote("🎁 Übergabe!", tick);
      }
      case "fetch" -> {
        situational.celebrateTicks = 28;
        bubbles.emote("✦ Apportiert!", tick);
      }
      default -> {}
    }
  }

  private static String shortCraftLabel(String itemId) {
    if (itemId == null || itemId.isBlank()) return "Craft";
    return switch (itemId) {
      case "minecraft:bread" -> "Brot";
      case "minecraft:cookie" -> "Kekse";
      case "minecraft:pumpkin_pie" -> "Kuchen";
      case "minecraft:mushroom_stew" -> "Suppe";
      case "minecraft:torch" -> "Fackeln";
      case "minecraft:cooked_beef" -> "Steak";
      case "minecraft:cooked_porkchop" -> "Braten";
      case "minecraft:cooked_chicken" -> "Hähnchen";
      case "minecraft:cooked_mutton" -> "Lamm";
      case "minecraft:baked_potato" -> "Kartoffel";
      case "minecraft:cooked_cod", "minecraft:cooked_salmon" -> "Fisch";
      default -> "Craft";
    };
  }

  public void wakeUp() {
    situational.perchedEntity = null;
    calmAreaSinceTick = tick;
    if (!perched) return;
    perched = false;
    perchSpot = null;
    perchLook = null;
    if ("rest".equals(action)) action = "observe";
  }

  /**
   * Prefers elevated block ledges/edges (sitting near the edge looking into the room) and cozy
   * wall alcoves/holes over flat floor centers.
   */
  public static PerchPlacement findPerchSpot(Minecraft mc, Point near) {
    if (mc == null || mc.level == null || near == null) return null;
    var origin = net.minecraft.core.BlockPos.containing(near.x(), near.y(), near.z());
    var horizontals =
        new net.minecraft.core.Direction[] {
          net.minecraft.core.Direction.NORTH,
          net.minecraft.core.Direction.SOUTH,
          net.minecraft.core.Direction.EAST,
          net.minecraft.core.Direction.WEST
        };
    PerchPlacement best = null;
    double bestScore = Double.NEGATIVE_INFINITY;
    for (int dx = -4; dx <= 4; dx++) {
      for (int dy = -2; dy <= 3; dy++) {
        for (int dz = -4; dz <= 4; dz++) {
          var pos = origin.offset(dx, dy, dz);
          if (!mc.level.hasChunkAt(pos)) continue;
          var below = mc.level.getBlockState(pos);
          var abovePos = pos.above();
          var above = mc.level.getBlockState(abovePos);
          if (below.isAir()
              || below.getCollisionShape(mc.level, pos).isEmpty()
              || !above.isAir()) continue;
          double topY =
              pos.getY()
                  + below.getCollisionShape(mc.level, pos).max(net.minecraft.core.Direction.Axis.Y);

          int solidWalls = 0;
          int openDropEdges = 0;
          double openDirX = 0, openDirZ = 0;
          for (var dir : horizontals) {
            var sideAbove = abovePos.relative(dir);
            var sideBelow = pos.relative(dir);
            boolean wallAtHead =
                mc.level.hasChunkAt(sideAbove)
                    && !mc.level.getBlockState(sideAbove).isAir()
                    && !mc.level.getBlockState(sideAbove).getCollisionShape(mc.level, sideAbove).isEmpty();
            boolean dropBelow =
                mc.level.hasChunkAt(sideBelow) && mc.level.getBlockState(sideBelow).isAir();
            if (wallAtHead) {
              solidWalls++;
            } else {
              openDirX += dir.getStepX();
              openDirZ += dir.getStepZ();
              if (dropBelow) openDropEdges++;
            }
          }
          boolean hasCeiling =
              mc.level.hasChunkAt(abovePos.above())
                  && !mc.level.getBlockState(abovePos.above()).isAir();
          boolean isAlcove = solidWalls >= 2 && solidWalls <= 3 && (openDirX != 0 || openDirZ != 0);
          boolean isLedge = openDropEdges >= 1;

          // Sit near the open edge (offset 0.28 so the 0.25 cube still sits solidly on the block).
          double offX = 0, offZ = 0;
          double dirLen = Math.hypot(openDirX, openDirZ);
          if (dirLen > 0.1 && (isLedge || isAlcove)) {
            double ux = openDirX / dirLen;
            double uz = openDirZ / dirLen;
            double edgeOffset = isAlcove ? 0.18 : 0.28;
            offX = Math.clamp(ux * edgeOffset, -0.30, 0.30);
            offZ = Math.clamp(uz * edgeOffset, -0.30, 0.30);
          }
          Point seat = new Point(pos.getX() + 0.5 + offX, topY + 0.125, pos.getZ() + 0.5 + offZ);
          Point lookAt =
              dirLen > 0.1
                  ? seat.add(new Point((openDirX / dirLen) * 3.0, -0.15, (openDirZ / dirLen) * 3.0))
                  : null;

          double dist = seat.distance(near);
          double score = -dist * 0.7;
          if (isAlcove) score += 4.2 + (hasCeiling ? 1.2 : 0.0); // Cozy hole in a wall!
          if (isLedge) score += 3.0; // Perched on an open edge overlooking the room!
          if (dy >= 0 && dy <= 2) score += 0.8; // Slightly elevated vantage point
          if (mc.level.isRaining() && hasCeiling) score += 3.5; // Shelter from rain!
          if (nearWarmth(mc, pos)) score += 2.4; // Cozy near lit furnaces/campfires!

          if (score > bestScore) {
            bestScore = score;
            best = new PerchPlacement(seat, lookAt);
          }
        }
      }
    }
    return best;
  }

  private static boolean nearWarmth(Minecraft mc, net.minecraft.core.BlockPos pos) {
    for (int dx = -2; dx <= 2; dx++) {
      for (int dz = -2; dz <= 2; dz++) {
        var bp = pos.offset(dx, 0, dz);
        if (!mc.level.hasChunkAt(bp)) continue;
        var st = mc.level.getBlockState(bp);
        if ((st.is(net.minecraft.world.level.block.Blocks.CAMPFIRE)
                || st.is(net.minecraft.world.level.block.Blocks.SOUL_CAMPFIRE)
                || st.is(net.minecraft.world.level.block.Blocks.FURNACE)
                || st.is(net.minecraft.world.level.block.Blocks.BLAST_FURNACE)
                || st.is(net.minecraft.world.level.block.Blocks.SMOKER))
            && st.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.LIT)
            && st.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.LIT)) {
          return true;
        }
      }
    }
    return false;
  }

  public void tick(Minecraft mc, Settings settings, boolean thinking) {
    if (mc.player == null || mc.level == null) return;
    tick++;
    if (errorTintTicks > 0) errorTintTicks--;
    Point player = WorldSensor.point(mc.player.position());
    float yaw = mc.player.getYRot();
    float pitch = mc.player.getXRot();
    if (lastTrackedPlayerPos == null
        || lastTrackedPlayerPos.distance(player) > 0.05
        || Math.abs(yaw - lastTrackedYaw) > 2.0f
        || Math.abs(pitch - lastTrackedPitch) > 2.0f) {
      lastActivityTick = tick;
      lastTrackedPlayerPos = player;
      lastTrackedYaw = yaw;
      lastTrackedPitch = pitch;
    }
    trail.record(player);
    peers.prune(player, tick);
    situational.tick(mc, motion.position(), tick, perched);
    if (!isSleeping() && settings.multiplayerSync) {
      situational.tickSocialInteractions(mc, motion.position(), tick, peers, bubbles);
    }
    if (!situational.pendingDurabilityWarning.isBlank()) {
      if (settings.thoughts) bubbles.thought(situational.pendingDurabilityWarning, tick);
      situational.pendingDurabilityWarning = "";
    }
    if (!situational.pendingEmote.isBlank()) {
      bubbles.emote(situational.pendingEmote, tick);
      situational.pendingEmote = "";
    }
    if (catchVisitTicks > 0) catchVisitTicks--;
    if (serverTaskTicks > 0) {
      serverTaskTicks--;
      if ("cook_start".equals(serverTaskType)) {
        situational.activeHat = "chef";
      }
      if (serverTaskTicks == 0) {
        serverTaskType = "";
        serverTaskPos = null;
      }
    }
    if (combatActionTicks > 0) combatActionTicks--;
    if (situational.concernTicks > 0 || situational.threatTicks > 0 || situational.celebrateTicks > 0) {
      wakeUp();
    }
    Vec3 forward = mc.player.getLookAngle().multiply(1, 0, 1).normalize();
    if (forward.lengthSqr() < 0.01) forward = new Vec3(0, 0, 1);
    Vec3 side = forward.cross(new Vec3(0, 1, 0)).normalize();
    boolean inCombat = situational.combatTicks > 0;
    boolean working = situational.workClearanceTicks > 0;
    // During combat OR mining/working, pull cleanly BEHIND the player's shoulder so Cubey NEVER
    // blocks a sword swing, pickaxe strike, or emergency retreat from lava!
    boolean hideThunder = situational.thunderHide && !inCombat && !working;
    double homeHeight = inCombat ? 2.45 : working ? 1.65 : situational.sneaking ? 1.05 : 1.55;
    double forwardOffset = inCombat ? -1.75 : working ? -1.45 : hideThunder ? -0.95 : 2.05;
    double sideOffset = inCombat ? 1.95 : working ? 1.15 : hideThunder ? 0.78 : 0.95;
    Point home =
        WorldSensor.point(
            mc.player
                .position()
                .add(forward.scale(forwardOffset))
                .add(side.scale(sideOffset))
                .add(0, homeHeight, 0));
    if (tick == 1) motion.reset(home);
    if (actionUntil < tick && !perched) {
      var queued = actionQueue.pollNext();
      if (queued.isPresent()) {
        var step = queued.get();
        action = step.action();
        actionUntil = tick + 120;
        actionTarget =
            step.target().isBlank()
                ? null
                : observations.stream()
                    .filter(o -> o.key().equals(step.target()))
                    .findFirst()
                    .orElse(null);
        if (actionTarget != null) {
          attention.select(actionTarget, tick);
          attention.holdUntil(actionUntil);
        }
      } else {
        action = "observe";
        actionTarget = null;
      }
    }
    // Track how long the player has been working locally within a 12-block factory/room radius.
    if (calmAreaAnchor == null || player.distance(calmAreaAnchor) > 12.0 || situational.wingmanMode) {
      calmAreaAnchor = player;
      calmAreaSinceTick = tick;
    }
    // Auto-perch when explicitly resting OR after 45s (900 ticks) of quiet local factory work.
    boolean busyActivity =
        inCombat
            || working
            || grabThrow.isGrabbed()
            || grabThrow.isThrown()
            || situational.social.isActive()
            || guideDestination != null;
    boolean calmFactoryNap =
        tick - calmAreaSinceTick >= 900
            && tick - lastSpeech >= 700
            && !thinking
            && !situational.wingmanMode
            && !busyActivity
            && tick % 100 == 0
            && player.distance(motion.position()) < settings.exploreRadius;
    if (!perched && !busyActivity && ("rest".equals(action) || calmFactoryNap)) {
      var spot = findPerchSpot(mc, motion.position());
      if (spot != null) perchAt(spot);
    }
    if (perched && perchSpot != null) {
      double wakeDistance = Math.max(16.0, settings.exploreRadius * 2.2);
      var underPos =
          net.minecraft.core.BlockPos.containing(
              perchSpot.x(), perchSpot.y() - 0.25, perchSpot.z());
      boolean supportGone =
          !mc.level.hasChunkAt(underPos)
              || mc.level.getBlockState(underPos).isAir()
              || mc.level.getBlockState(underPos).getCollisionShape(mc.level, underPos).isEmpty();
      if (player.distance(perchSpot) > wakeDistance || supportGone || situational.wingmanMode) {
        wakeUp();
        calmAreaAnchor = player;
        calmAreaSinceTick = tick;
      }
    }
    java.util.function.Predicate<Point> free =
        p -> {
          Vec3 v = WorldSensor.vec(p);
          return mc.level.hasChunkAt(net.minecraft.core.BlockPos.containing(v))
              && mc.level.noCollision(
                  new AABB(v.x - .17, v.y - .17, v.z - .17, v.x + .17, v.y + .17, v.z + .17));
        };
    var target = actionTarget != null ? actionTarget : attention.target();
    if (inCombat || working || guideDestination != null || actionTarget != null || grabThrow.isGrabbed() || grabThrow.isThrown()) {
      situational.perchedEntity = null;
    }
    if (grabThrow.isGrabbed()) {
      boolean lmbDown = mc.screen == null && mc.options.keyAttack.isDown();
      boolean rmbDown = mc.screen == null && mc.options.keyUse.isDown();
      Point eyePos = WorldSensor.point(mc.player.getEyePosition());
      Point lookUnit = WorldSensor.point(mc.player.getLookAngle().normalize());
      Point holdTarget = grabThrow.tickGrab(eyePos, lookUnit, lmbDown, rmbDown);
      if (holdTarget != null) {
        Point safeHold =
            free.test(holdTarget) ? holdTarget : FlightClearance.nearest(eyePos, holdTarget, 2.5, free);
        Point smoothed = motion.position().lerp(safeHold, 0.55);
        motion.stepDirect(smoothed);
        flightGoal = safeHold;
        flightMode = "Curious";
        state = "Curious";
      } else if (grabThrow.isThrown()) {
        // Just launched!
        situational.barrelRollTicks = 28;
        situational.recordThrow(tick);
        pendingWeeeeSound = true;
        String yeetText = grabThrow.lastLaunchCharge() >= 0.78f ? "YEET!! ✦" : "Weeeee! ✦";
        bubbles.emote(yeetText, tick);
        pendingSyncSpeech = yeetText;
        flightMode = "Wingman";
        state = "Wingman";
      } else {
        // Gently placed down without throwing
        pendingGentlePlace = true;
        flightMode = "Idle";
        state = "Idle";
      }
    } else if (grabThrow.isThrown()) {
      java.util.function.Function<Point, GrabThrowPhysics.Surface> surfaceAt =
          p -> {
            var bp = net.minecraft.core.BlockPos.containing(p.x(), p.y(), p.z());
            if (!mc.level.hasChunkAt(bp)) return GrabThrowPhysics.Surface.SOLID;
            var fl = mc.level.getFluidState(bp);
            if (fl.is(net.minecraft.tags.FluidTags.LAVA)) return GrabThrowPhysics.Surface.LAVA;
            if (fl.is(net.minecraft.tags.FluidTags.WATER)) return GrabThrowPhysics.Surface.WATER;
            if (free.test(p)) return GrabThrowPhysics.Surface.AIR;
            var st = mc.level.getBlockState(bp);
            if (st.is(net.minecraft.world.level.block.Blocks.SLIME_BLOCK))
              return GrabThrowPhysics.Surface.SLIME;
            if (st.is(net.minecraft.world.level.block.Blocks.HAY_BLOCK)
                || st.is(net.minecraft.tags.BlockTags.WOOL)
                || st.is(net.minecraft.tags.BlockTags.BEDS)) return GrabThrowPhysics.Surface.SOFT;
            return GrabThrowPhysics.Surface.SOLID;
          };
      Point next = grabThrow.tickThrownSurface(motion.position(), surfaceAt);
      motion.stepDirect(next);
      flightGoal = next;
      flightMode = "Wingman";
      state = "Wingman";
      if (grabThrow.consumeJustBounced()) {
        var impact = grabThrow.consumeLastImpact();
        pendingBoingSound = true;
        switch (impact) {
          case SPLASH -> {
            bubbles.emote("*splash!*", tick);
            for (int k = 0; k < 6; k++) {
              mc.level.addParticle(
                  net.minecraft.core.particles.ParticleTypes.SPLASH,
                  next.x(),
                  next.y(),
                  next.z(),
                  (Math.random() - 0.5) * 0.25,
                  0.18,
                  (Math.random() - 0.5) * 0.25);
            }
          }
          case LAVA_NOPE -> {
            situational.barrelRollTicks = 28;
            situational.concernTicks = 35;
            bubbles.emote("🔥 NOPE!!", tick);
            mc.level.addParticle(
                net.minecraft.core.particles.ParticleTypes.FLAME,
                next.x(),
                next.y() - 0.1,
                next.z(),
                0,
                0.08,
                0);
          }
          case SLIME_BOING -> bubbles.emote("*BOIIING!*", tick);
          case SOFT_PLOP -> bubbles.emote("*plop*", tick);
          default -> bubbles.emote("*boing*", tick);
        }
      }
      // Multiplayer "Fang mich!" (Catch!): if thrown Cubey flies close to another player or peer Cubey!
      if (grabThrow.isThrown() && settings.multiplayerSync) {
        Point catchTarget = null;
        var nearPeer = peers.nearestPeer(next, 2.4);
        if (nearPeer.isPresent()) {
          catchTarget = nearPeer.get().pos;
        } else {
          for (var other : mc.level.players()) {
            if (other != mc.player && other.isAlive() && !other.isSpectator()) {
              Point op = WorldSensor.point(other.getEyePosition());
              if (op.distance(next) <= 2.4) {
                catchTarget = op;
                break;
              }
            }
          }
        }
        if (catchTarget != null) {
          grabThrow.release();
          situational.celebrateTicks = 36;
          situational.greetingTicks = 28;
          situational.triggerCelebrateSound = true;
          situational.focusLookOverride = catchTarget;
          catchVisitTicks = 60; // Hover happily by the other player for ~3s before flying back!
          catchVisitPos = next;
          String catchText = "✦ Fang! ♥";
          bubbles.emote(catchText, tick);
          pendingSyncSpeech = catchText;
        }
      }
      if (!grabThrow.isThrown() && catchVisitTicks == 0) {
        // Throw-to-Fetch ("Apportieren!"): scoop up dropped items around Cubey's landing spot!
        if (mc.getConnection() != null
            && mc.getConnection().hasChannel(dev.qb2.network.CubeyNetwork.C2SThrowFetchPayload.TYPE)) {
          net.neoforged.neoforge.network.PacketDistributor.sendToServer(
              new dev.qb2.network.CubeyNetwork.C2SThrowFetchPayload(next.x(), next.y(), next.z()));
        }
        action = "return";
        actionUntil = tick + 100;
      } else if (grabThrow.isThrown()
          && mc.getConnection() != null
          && mc.getConnection().hasChannel(dev.qb2.network.CubeyNetwork.C2SThrowHitMobPayload.TYPE)) {
        // Throw Cubey directly at a hostile or neutral mob -> BONK & mark as Cubey's attack target!
        AABB hitBox = new AABB(next.x(), next.y(), next.z(), next.x(), next.y(), next.z()).inflate(0.95);
        for (var mob : mc.level.getEntitiesOfClass(net.minecraft.world.entity.Mob.class, hitBox)) {
          if (!mob.isAlive() || mob.isInvisible()) continue;
          if (mob instanceof net.minecraft.world.entity.TamableAnimal t && t.isTame()) continue;
          if (mob instanceof net.minecraft.world.entity.npc.AbstractVillager) continue;
          grabThrow.release();
          pendingBoingSound = true;
          combatActionTicks = 20;
          bubbles.emote("💥 BONK!", tick);
          net.neoforged.neoforge.network.PacketDistributor.sendToServer(
              new dev.qb2.network.CubeyNetwork.C2SThrowHitMobPayload(mob.getId()));
          action = "return";
          actionUntil = tick + 80;
          break;
        }
      }
    } else if (catchVisitTicks > 0 && catchVisitPos != null) {
      flightGoal = catchVisitPos;
      flightMode = "Curious";
      state = "Curious";
      motion.tickSmoothFollow(catchVisitPos, catchVisitPos, 4.0, free, null);
    } else if (serverTaskTicks > 0 && serverTaskPos != null) {
      double orbitAngle = tick * 0.24;
      Point taskTarget =
          ("craft_start".equals(serverTaskType) || "cook_start".equals(serverTaskType))
              ? serverTaskPos.add(
                  new Point(
                      Math.cos(orbitAngle) * 0.42,
                      Math.sin(tick * 0.35) * 0.12,
                      Math.sin(orbitAngle) * 0.42))
              : serverTaskPos;
      flightGoal = taskTarget;
      flightMode = "Exploring";
      state = "Exploring";
      motion.tickSmoothFollow(player, taskTarget, Math.max(settings.exploreRadius, 8.0), free, null);
    } else if (guideDestination != null) {
      double distToDest = player.distance(guideDestination);
      if (distToDest <= 3.0) {
        Point aboveDest = guideDestination.add(new Point(0, 0.95, 0));
        flightGoal = FlightClearance.nearest(player, aboveDest, settings.exploreRadius, free);
        motion.tickSmoothFollow(player, flightGoal, settings.exploreRadius, free, trail);
        if (motion.position().distance(aboveDest) < 1.2) {
          bubbles.thought("✓ " + guideLabel, tick);
          stopGuiding();
        }
      } else {
        // Lead the player smoothly (~4.8 blocks ahead along the vector toward guideDestination)
        Point toDest = guideDestination.subtract(player);
        Point lead =
            player
                .add(toDest.unit().scale(Math.min(4.8, Math.min(distToDest, settings.exploreRadius * 0.85))))
                .add(new Point(0, 1.35, 0));
        flightGoal = FlightClearance.nearest(player, lead, settings.exploreRadius, free);
        flightMode = "Guiding";
        motion.tickSmoothFollow(player, flightGoal, settings.exploreRadius, free, trail);
      }
      state = bubbles.speaking(tick) ? "Speaking" : thinking ? "Thinking" : "Exploring";
    } else if (situational.perchedEntity != null && situational.specialGoalOverride != null) {
      Point entitySeat = situational.specialGoalOverride;
      flightGoal = entitySeat;
      flightMode = "Riding";
      if (motion.position().distance(entitySeat) > 0.20) {
        motion.tickSmoothFollow(entitySeat, entitySeat, 6.0, p -> true, null);
      } else {
        motion.reset(entitySeat);
      }
      state = bubbles.speaking(tick) ? "Speaking" : thinking ? "Thinking" : "Curious";
    } else if (perched && perchSpot != null) {
      flightGoal = perchSpot;
      flightMode = "Napping";
      if (motion.position().distance(perchSpot) > 0.05) {
        motion.tickSmoothFollow(perchSpot, perchSpot, 4.0, p -> true, null);
      } else {
        motion.reset(perchSpot);
      }
      // Strictly powered-down state while napping!
      state = "Napping";
    } else if (situational.specialGoalOverride != null) {
      double effRadius =
          situational.wingmanMode ? Math.max(settings.exploreRadius, 12.0) : settings.exploreRadius;
      flightGoal =
          situational.wingmanMode
              ? situational.specialGoalOverride
              : FlightClearance.nearest(
                  player, situational.specialGoalOverride, effRadius, free);
      flightMode =
          situational.sleepingWithPlayer
              ? "Napping"
              : situational.wingmanMode ? "Wingman" : "Curious";
      motion.tickSmoothFollow(player, flightGoal, effRadius, free, trail);
      state =
          situational.sleepingWithPlayer
              ? "Napping"
              : bubbles.speaking(tick) ? "Speaking" : thinking ? "Thinking" : flightMode;
    } else {
      String effectiveAction =
          inCombat
                  || working
                  || mc.player.isSprinting()
                  || situational.wantForwardFov
                  || bubbles.speaking(tick)
              ? "return"
              : action;
      var plan =
          flight.plan(
              player,
              home,
              motion.position(),
              target,
              effectiveAction,
              settings.exploreRadius,
              tick,
              thinking);
      Point desired = plan.position();
      // Hold position still when the player is looking directly at Cubey so he never runs away from
      // your gaze when you turn to read his bubble or inspect him!
      Vec3 toCubeVec = WorldSensor.vec(motion.position()).subtract(mc.player.getEyePosition());
      double cubeDist = toCubeVec.length();
      boolean playerLookingAtCube =
          cubeDist >= 1.15
              && cubeDist <= settings.exploreRadius
              && mc.player.getLookAngle().normalize().dot(toCubeVec.normalize()) > 0.76;
      if (playerLookingAtCube
          && !mc.player.isSprinting()
          && !situational.wingmanMode
          && !inCombat
          && !working) {
        desired = motion.position();
      }
      // Auto-sidestep when holding a BlockItem so Cubey never blocks your first block placement!
      if (mc.player.getMainHandItem().getItem() instanceof net.minecraft.world.item.BlockItem
          && mc.hitResult instanceof net.minecraft.world.phys.BlockHitResult bhr) {
        var placeBp = bhr.getBlockPos().relative(bhr.getDirection());
        Point placeCenter = new Point(placeBp.getX() + 0.5, placeBp.getY() + 0.5, placeBp.getZ() + 0.5);
        if (desired.distance(placeCenter) < 0.95) {
          desired = desired.add(new Point(side.x * 1.15, 0.75, side.z * 1.15));
        }
      }
      // Prevent clipping into the player's body or camera: keep >= 1.25 blocks horizontal clearance.
      Point fromPlayer = desired.subtract(player);
      double horizDist = Math.hypot(fromPlayer.x(), fromPlayer.z());
      if (horizDist < 1.25) {
        Point pushDir =
            horizDist > 0.05
                ? new Point(fromPlayer.x() / horizDist, 0, fromPlayer.z() / horizDist)
                : WorldSensor.point(forward.scale(0.8).add(side.scale(0.6))).unit();
        desired =
            Motion.constrain(
                player,
                new Point(
                    player.x() + pushDir.x() * 1.35,
                    Math.max(desired.y(), player.y() + (situational.sneaking ? 0.9 : 1.35)),
                    player.z() + pushDir.z() * 1.35),
                settings.exploreRadius * 0.9);
      }
      flightGoal = FlightClearance.nearest(player, desired, settings.exploreRadius, free);
      flightMode = plan.mode();
      if (settings.smoothFollow) {
        motion.tickSmoothFollow(player, flightGoal, settings.exploreRadius, free, trail);
      } else {
        motion.tick(player, flightGoal, settings.exploreRadius, free);
      }
      state = bubbles.speaking(tick) ? "Speaking" : thinking ? "Thinking" : flightMode;
    }
    if (tick % 10 == 0) {
      Point facing =
          target == null
              ? WorldSensor.point(mc.player.getLookAngle())
              : target.position().subtract(motion.position());
      var sampled = sensor.sample(mc, motion.position(), facing, settings, tick);
      if (actionTarget != null && tick < actionUntil) {
        var merged = new ArrayList<Observation>(sampled.size() + 1);
        merged.add(actionTarget);
        for (Observation o : sampled) {
          if (!o.key().equals(actionTarget.key())) merged.add(o);
        }
        observations = List.copyOf(merged);
      } else {
        observations = sampled;
      }
      attention.update(observations, tick);
      if (!sensor.changes().isEmpty()) {
        for (String c : sensor.changes()) remember(observationsHistory, "t=" + tick + " " + c, 16);
      }
    }
  }

  public void apply(Decision d) {
    // If Cubey is going to speak or take an active action, always wake up first so he never talks
    // about being "back by your side" while still visibly sleeping on the ground!
    if (!d.speech().isBlank() || (!"rest".equals(d.action()) && !"observe".equals(d.action()))) {
      wakeUp();
    }
    action = (!d.speech().isBlank() && "rest".equals(d.action())) ? "observe" : d.action();
    actionUntil = tick + 160;
    if (Set.of("inspect", "approach", "go_to", "orbit").contains(d.action()))
      attention.holdUntil(actionUntil);
    if (!d.target().isBlank())
      observations.stream()
          .filter(o -> o.key().equals(d.target()))
          .findFirst()
          .ifPresent(
              o -> {
                attention.select(o, tick);
                actionTarget = o;
              });
    if (!d.plan().isEmpty()) {
      for (var step : d.plan()) {
        actionQueue.push(step.action(), step.target(), step.priority());
      }
    }
    remember(actions, "t=" + tick + " " + d.action() + " " + d.target(), 12);
    if (!d.thought().isBlank() && !recentThoughts.contains(d.thought())) {
      remember(recentThoughts, d.thought(), 8);
      bubbles.thought(d.thought(), tick);
    }
    if (!d.speech().isBlank()) {
      bubbles.speech(d.speech(), tick);
      lastSpeech = tick;
      pendingSyncSpeech = d.speech();
    }
  }

  public static void remember(Deque<String> queue, String text, int max) {
    queue.addLast(text);
    while (queue.size() > max) queue.removeFirst();
  }
}
