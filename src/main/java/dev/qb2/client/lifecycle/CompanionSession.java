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
  public String flightMode = "Idle";
  public Point flightGoal = Point.ZERO;
  public volatile boolean apiBackoff;
  public int errorTintTicks;
  public boolean perched;
  public Point perchSpot;
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

  public void perchAt(Point spot) {
    if (spot == null) return;
    perched = true;
    perchSpot = spot;
    perchedSinceTick = tick;
    action = "rest";
    actionUntil = tick + 2400;
  }

  public void wakeUp() {
    if (!perched) return;
    perched = false;
    perchSpot = null;
    if ("rest".equals(action)) action = "observe";
  }

  public static Point findPerchSpot(Minecraft mc, Point near) {
    if (mc == null || mc.level == null || near == null) return null;
    var origin = net.minecraft.core.BlockPos.containing(near.x(), near.y(), near.z());
    Point best = null;
    double bestDist = Double.POSITIVE_INFINITY;
    for (int dx = -4; dx <= 4; dx++) {
      for (int dy = -2; dy <= 2; dy++) {
        for (int dz = -4; dz <= 4; dz++) {
          var pos = origin.offset(dx, dy, dz);
          if (!mc.level.hasChunkAt(pos)) continue;
          var below = mc.level.getBlockState(pos);
          var above = mc.level.getBlockState(pos.above());
          if (!below.isAir()
              && !below.getCollisionShape(mc.level, pos).isEmpty()
              && above.isAir()) {
            double topY = pos.getY() + below.getCollisionShape(mc.level, pos).max(net.minecraft.core.Direction.Axis.Y);
            Point candidate = new Point(pos.getX() + 0.5, topY + 0.125, pos.getZ() + 0.5);
            double d = candidate.distance(near);
            if (d < bestDist) {
              bestDist = d;
              best = candidate;
            }
          }
        }
      }
    }
    return best;
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
    Vec3 forward = mc.player.getLookAngle().multiply(1, 0, 1).normalize();
    Vec3 side = forward.cross(new Vec3(0, 1, 0));
    Point home =
        WorldSensor.point(
            mc.player.position().add(forward.scale(1.3)).add(side.scale(0.8)).add(0, 1.6, 0));
    if (tick == 1) motion.reset(home);
    if (actionUntil < tick && !perched) {
      action = "observe";
      actionTarget = null;
    }
    // Auto-perch when resting or quietly hanging around the player's factory.
    if (!perched && ("rest".equals(action) || (tick > 600 && tick - lastSpeech > 800 && !thinking && tick % 200 == 0 && player.distance(motion.position()) < settings.exploreRadius))) {
      Point spot = findPerchSpot(mc, motion.position());
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
      var plan =
          flight.plan(
              player,
              home,
              motion.position(),
              target,
              mc.player.isSprinting() ? "return" : action,
              settings.exploreRadius,
              tick,
              thinking);
      Point desired = plan.position();
      Point eye = WorldSensor.point(mc.gameRenderer.getMainCamera().getPosition());
      if (desired.distance(eye) < 0.9)
        desired =
            Motion.constrain(
                player,
                desired.add(WorldSensor.point(side).scale(1.1)),
                settings.exploreRadius * 0.9);
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
