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
  public final dev.qb2.companion.behavior.SituationalBehavior situational =
      new dev.qb2.companion.behavior.SituationalBehavior();
  public final dev.qb2.cognition.decisions.ActionQueue actionQueue =
      new dev.qb2.cognition.decisions.ActionQueue();
  public String flightMode = "Idle";
  public Point flightGoal = Point.ZERO;
  public volatile boolean apiBackoff;
  public int errorTintTicks;
  public boolean perched;
  public Point perchSpot;
  public Point perchLook;
  private long perchedSinceTick;
  private Point lastTrackedPlayerPos;
  private float lastTrackedYaw, lastTrackedPitch;
  private long lastActivityTick;

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
    action = "return";
    actionTarget = null;
    actionUntil = tick + 120;
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
    perched = true;
    perchSpot = placement.position();
    perchLook = placement.lookAt();
    perchedSinceTick = tick;
    action = "rest";
    actionUntil = tick + 2400;
  }

  public void wakeUp() {
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
    situational.tick(mc, motion.position(), tick, perched);
    if (situational.concernTicks > 0 || situational.threatTicks > 0 || situational.celebrateTicks > 0) {
      wakeUp();
    }
    Vec3 forward = mc.player.getLookAngle().multiply(1, 0, 1).normalize();
    if (forward.lengthSqr() < 0.01) forward = new Vec3(0, 0, 1);
    Vec3 side = forward.cross(new Vec3(0, 1, 0)).normalize();
    double homeHeight = situational.sneaking ? 1.05 : 1.55;
    Point home =
        WorldSensor.point(
            mc.player.position().add(forward.scale(2.05)).add(side.scale(0.95)).add(0, homeHeight, 0));
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
    // Auto-perch when resting or quietly hanging around the player's factory.
    if (!perched && ("rest".equals(action) || (tick > 600 && tick - lastSpeech > 800 && !thinking && tick % 200 == 0 && player.distance(motion.position()) < settings.exploreRadius))) {
      var spot = findPerchSpot(mc, motion.position());
      if (spot != null) perchAt(spot);
    }
    if (perched && perchSpot != null) {
      double wakeDistance = Math.max(18.0, settings.exploreRadius * 2.5);
      var underPos =
          net.minecraft.core.BlockPos.containing(
              perchSpot.x(), perchSpot.y() - 0.25, perchSpot.z());
      boolean supportGone =
          !mc.level.hasChunkAt(underPos)
              || mc.level.getBlockState(underPos).isAir()
              || mc.level.getBlockState(underPos).getCollisionShape(mc.level, underPos).isEmpty();
      if (player.distance(perchSpot) > wakeDistance || supportGone) {
        wakeUp();
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
    if (perched && perchSpot != null) {
      flightGoal = perchSpot;
      flightMode = "Napping";
      if (motion.position().distance(perchSpot) > 0.05) {
        motion.tickSmoothFollow(perchSpot, perchSpot, 4.0, p -> true, null);
      } else {
        motion.reset(perchSpot);
      }
      state = bubbles.speaking(tick) ? "Speaking" : thinking ? "Thinking" : "Napping";
    } else {
      String effectiveAction =
          mc.player.isSprinting() || situational.wantForwardFov || bubbles.speaking(tick)
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
      observations = sensor.sample(mc, motion.position(), facing, settings, tick);
      attention.update(observations, tick);
      if (!sensor.changes().isEmpty()) {
        for (String c : sensor.changes()) remember(observationsHistory, "t=" + tick + " " + c, 16);
        // Nearby world change wakes a napping companion!
        if (perched && tick - perchedSinceTick > 60) wakeUp();
      }
    }
  }

  public void apply(Decision d) {
    if (!"rest".equals(d.action()) && (!d.speech().isBlank() || !"observe".equals(d.action()))) {
      wakeUp();
    }
    action = d.action();
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
    }
  }

  public static void remember(Deque<String> queue, String text, int max) {
    queue.addLast(text);
    while (queue.size() > max) queue.removeFirst();
  }
}
