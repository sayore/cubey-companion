package dev.qb2.companion.movement;

import java.util.List;
import java.util.function.Predicate;

/**
 * Fixed-step virtual physics, confined to the actual player's sphere even during collision
 * avoidance.
 */
public final class Motion {
  private static final double MAX_STEP = 0.14;
  private static final List<Point> DETOURS =
      List.of(
          new Point(0, 0.10, 0),
          new Point(0.10, 0, 0),
          new Point(-0.10, 0, 0),
          new Point(0, 0, 0.10),
          new Point(0, 0, -0.10));
  private Point previous = Point.ZERO, position = Point.ZERO, velocity = Point.ZERO;
  private Point lastCenter;
  private Point cachedWaypoint, cachedGoal;
  private int waypointTtl;
  private boolean initialized;

  public void reset(Point point) {
    position = point;
    previous = point;
    velocity = Point.ZERO;
    lastCenter = null;
    cachedWaypoint = null;
    cachedGoal = null;
    waypointTtl = 0;
    initialized = true;
  }

  /** Direct physics step (used during grab-hold & ballistic throw) with smooth interpolation. */
  public void stepDirect(Point nextPos) {
    if (!initialized) {
      reset(nextPos);
      return;
    }
    previous = position;
    velocity = nextPos.subtract(position);
    position = nextPos;
  }

  public static Point constrain(Point center, Point point, double radius) {
    Point delta = point.subtract(center);
    return delta.length() > radius ? center.add(delta.unit().scale(radius)) : point;
  }

  public void tick(Point center, Point target, double radius, Predicate<Point> free) {
    if (!initialized) reset(center);
    if (position.distance(center) > 32 && free.test(center)) reset(center);
    if (position.distance(center) > radius) {
      Point recovery = constrain(center, position, radius * 0.95);
      if (!free.test(recovery)) recovery = center.add(new Point(0, Math.min(1.6, radius * 0.7), 0));
      if (free.test(recovery)) reset(recovery);
      else
        reset(
            constrain(
                center,
                position,
                radius
                    * 0.95)); // No free space: remain inside the permitted sphere, even if hidden
      // by solid blocks.
    }
    if (!free.test(position)) reset(FlightClearance.nearest(center, position, radius, free));
    previous = position;
    Point bounded = constrain(center, target, radius);
    Point steerTarget = bounded;
    if (!pathFree(position, bounded, free)) {
      boolean reuse =
          cachedWaypoint != null
              && waypointTtl > 0
              && cachedGoal != null
              && cachedGoal.distance(bounded) < 0.75
              && position.distance(cachedWaypoint) > 0.35
              && cachedWaypoint.distance(center) <= radius
              && free.test(cachedWaypoint);
      if (reuse) {
        waypointTtl--;
        steerTarget = cachedWaypoint;
      } else {
        var waypoint = VoxelPathfinder.nextWaypoint(center, position, bounded, radius, free);
        cachedGoal = bounded;
        cachedWaypoint = waypoint.orElse(null);
        waypointTtl = cachedWaypoint != null ? 8 : 0;
        if (cachedWaypoint != null) steerTarget = cachedWaypoint;
      }
    } else {
      cachedWaypoint = null;
      waypointTtl = 0;
    }
    Point delta = steerTarget.subtract(position);
    Point desired = delta.unit().scale(Math.min(MAX_STEP, delta.length() * 0.06));
    velocity = velocity.lerp(desired, 0.15);
    Point next = constrain(center, position.add(velocity), radius);
    if (!pathFree(position, next, free)) {
      cachedWaypoint = null;
      waypointTtl = 0;
      next = position;
      for (Point offset : DETOURS) {
        Point candidate = position.add(offset);
        if (candidate.distance(center) <= radius && pathFree(position, candidate, free)) {
          next = candidate;
          break;
        }
      }
      velocity = Point.ZERO;
    }
    position = next;
  }

  /**
   * Smooth trail-following flight: never drags or teleports when the player walks ahead; instead
   * glides along the player's walked breadcrumb trail until caught up, and dynamically matches
   * fast player/vehicle/aircraft speed.
   */
  public void tickSmoothFollow(
      Point center, Point target, double radius, Predicate<Point> free, PlayerTrail trail) {
    if (!initialized) reset(center);
    double playerSpeed =
        lastCenter == null ? 0.0 : Math.min(2.5, center.distance(lastCenter));
    lastCenter = center;
    // Only snap on extreme portal/dimension/command teleports (> 96 blocks).
    if (position.distance(center) > 96 && free.test(center)) {
      if (trail != null) trail.clear();
      reset(center);
      return;
    }
    if (!free.test(position)) {
      double recoveryRadius = Math.max(radius, position.distance(center) + 2.0);
      reset(FlightClearance.nearest(center, position, recoveryRadius, free));
    }
    previous = position;
    double distFromPlayer = position.distance(center);
    boolean fastMover = playerSpeed > 0.24;
    boolean catchingUp = distFromPlayer > radius || fastMover;
    // When in fast vehicle/aircraft flight, steer directly toward the wingman target rather than old ground breadcrumbs.
    Point goal =
        (distFromPlayer > radius && playerSpeed <= 0.45 && trail != null)
            ? trail.nextWaypoint(position, constrain(center, target, radius))
            : constrain(center, target, radius);
    double navRadius = catchingUp ? Math.max(radius, distFromPlayer + 3.0) : radius;

    Point steerTarget = goal;
    if (!pathFree(position, goal, free)) {
      boolean reuse =
          cachedWaypoint != null
              && waypointTtl > 0
              && cachedGoal != null
              && cachedGoal.distance(goal) < 0.75
              && position.distance(cachedWaypoint) > 0.35
              && free.test(cachedWaypoint);
      if (reuse) {
        waypointTtl--;
        steerTarget = cachedWaypoint;
      } else {
        var waypoint = VoxelPathfinder.nextWaypoint(center, position, goal, navRadius, free);
        cachedGoal = goal;
        cachedWaypoint = waypoint.orElse(null);
        waypointTtl = cachedWaypoint != null ? 8 : 0;
        if (cachedWaypoint != null) steerTarget = cachedWaypoint;
      }
    } else {
      cachedWaypoint = null;
      waypointTtl = 0;
    }

    Point delta = steerTarget.subtract(position);
    double distToGoal = delta.length();
    // Scale speed dynamically with actual player/aircraft speed + distance lag so Cubey pulls up crisply.
    double dynamicMaxStep =
        catchingUp
            ? Math.min(
                2.4,
                Math.max(MAX_STEP, playerSpeed * 1.28 + Math.max(0.0, distToGoal - 1.2) * 0.11))
            : Math.max(MAX_STEP, playerSpeed * 1.15);
    double gain = fastMover ? 0.34 : catchingUp ? 0.18 : 0.08;
    double accel = fastMover ? 0.42 : catchingUp ? 0.24 : 0.16;
    Point desired = delta.unit().scale(Math.min(dynamicMaxStep, distToGoal * gain));
    velocity = velocity.lerp(desired, accel);
    Point next =
        catchingUp ? position.add(velocity) : constrain(center, position.add(velocity), radius);
    if (!pathFree(position, next, free)) {
      cachedWaypoint = null;
      waypointTtl = 0;
      next = position;
      for (Point offset : DETOURS) {
        Point candidate = position.add(offset);
        if ((catchingUp || candidate.distance(center) <= radius)
            && pathFree(position, candidate, free)) {
          next = candidate;
          break;
        }
      }
      velocity = Point.ZERO;
    }
    position = next;
  }

  private static boolean pathFree(Point from, Point to, Predicate<Point> free) {
    int steps = Math.max(1, (int) Math.ceil(from.distance(to) / 0.07));
    for (int i = 1; i <= steps; i++)
      if (!free.test(from.lerp(to, (double) i / steps))) return false;
    return true;
  }

  public Point position() {
    return position;
  }

  public Point interpolate(double alpha) {
    return previous.lerp(position, Math.clamp(alpha, 0, 1));
  }

  public double speed() {
    return velocity.length();
  }
}
