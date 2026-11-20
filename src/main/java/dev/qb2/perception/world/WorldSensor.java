package dev.qb2.perception.world;

import dev.qb2.client.config.Settings;
import dev.qb2.companion.movement.Point;
import dev.qb2.perception.Observation;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.*;

public final class WorldSensor {
  private final Map<String, String> previous = new LinkedHashMap<>();
  private final List<String> changes = new ArrayList<>();

  public List<Observation> sample(Minecraft mc, Point cube, Point facing, Settings s, long tick) {
    if (mc.level == null || mc.player == null) return List.of();
    changes.clear();
    Vec3 origin = vec(cube), forward = vec(facing).normalize();
    if (forward.lengthSqr() < 0.1) forward = mc.player.getLookAngle();
    Vec3 side = forward.cross(new Vec3(0, 1, 0)).normalize();
    if (side.lengthSqr() < 0.1) side = new Vec3(1, 0, 0);
    Vec3 up = side.cross(forward).normalize();
    var observations = new LinkedHashMap<String, Observation>();
    double spread = Math.tan(Math.toRadians(s.fieldOfView / 2));
    for (int i = 0; i < 40; i++) {
      double u = ((i % 8) / 7.0 * 2 - 1) * spread, v = ((i / 8) / 4.0 * 2 - 1) * spread * 0.6;
      Vec3 end =
          origin.add(
              forward.add(side.scale(u)).add(up.scale(v)).normalize().scale(s.perceptionRadius));
      var hit =
          mc.level.clip(
              new ClipContext(
                  origin, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player));
      if (hit.getType() != HitResult.Type.BLOCK || !mc.level.hasChunkAt(hit.getBlockPos()))
        continue;
      BlockPos pos = hit.getBlockPos();
      var state = mc.level.getBlockState(pos);
      if (state.isAir()) continue;
      String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
      String key = id + "@" + pos.getX() + "," + pos.getY() + "," + pos.getZ();
      String stateText = state.toString();
      var be = mc.level.getBlockEntity(pos);
      if (be != null) {
        stateText +=
            " clientBlockEntity=" + BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(be.getType());
        stateText += ModpackInspector.describeClientCapabilities(mc.level, pos, state);
      }
      String location = pos.toShortString();
      String old = previous.put(location, stateText);
      if (old != null && !old.equals(stateText))
        changes.add("Observed change at " + location + ": " + old + " -> " + stateText);
      double novelty = old == null ? 2 : 1;
      Point p = new Point(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
      observations.put(
          key,
          new Observation(
              key,
              id,
              state.getBlock().getName().getString(),
              stateText,
              p,
              novelty / (1 + cube.distance(p) * 0.15),
              tick));
    }
    double cosine = Math.cos(Math.toRadians(s.fieldOfView / 2));
    for (var e :
        mc
            .level
            .getEntities(
                mc.player,
                new AABB(origin, origin).inflate(s.perceptionRadius),
                e -> !e.isInvisible())
            .stream()
            .limit(32)
            .toList()) {
      Vec3 at = e.getBoundingBox().getCenter(), delta = at.subtract(origin);
      if (delta.normalize().dot(forward) < cosine) continue;
      var hit =
          mc.level.clip(
              new ClipContext(
                  origin, at, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player));
      if (hit.getType() == HitResult.Type.BLOCK
          && hit.getLocation().distanceTo(origin) < delta.length() - 0.2) continue;
      String id = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString(),
          key = id + "@" + e.getId();
      observations.put(
          key,
          new Observation(
              key,
              id,
              e.getName().getString(),
              "visible entity",
              point(at),
              1.3 / (1 + delta.length() * 0.1),
              tick));
    }
    while (previous.size() > 256) previous.remove(previous.keySet().iterator().next());
    return observations.values().stream()
        .sorted(Comparator.comparingDouble(Observation::salience).reversed())
        .limit(24)
        .toList();
  }

  public List<String> changes() {
    return List.copyOf(changes);
  }

  public static Point point(Vec3 v) {
    return new Point(v.x, v.y, v.z);
  }

  public static Vec3 vec(Point p) {
    return new Vec3(p.x(), p.y(), p.z());
  }
}
