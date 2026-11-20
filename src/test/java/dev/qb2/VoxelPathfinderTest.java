package dev.qb2;

import static org.junit.jupiter.api.Assertions.*;

import dev.qb2.companion.movement.Motion;
import dev.qb2.companion.movement.Point;
import dev.qb2.companion.movement.VoxelPathfinder;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

final class VoxelPathfinderTest {
  @Test
  void findsPathAroundSolidWallWithinSphere() {
    // Solid wall at x = 2 for z in [-1, 1] and y in [0, 2]; open gap at z = 2.
    Predicate<Point> free =
        p -> {
          int x = (int) Math.floor(p.x());
          int y = (int) Math.floor(p.y());
          int z = (int) Math.floor(p.z());
          boolean wall = x == 2 && y >= 0 && y <= 2 && z >= -1 && z <= 1;
          return !wall;
        };
    Point center = new Point(2.5, 1.5, 0.5);
    Point start = new Point(0.5, 1.5, 0.5);
    Point goal = new Point(4.5, 1.5, 0.5);

    var wp = VoxelPathfinder.nextWaypoint(center, start, goal, 7.0, free);
    assertTrue(wp.isPresent(), "Expected a valid waypoint around the wall");
    assertTrue(free.test(wp.get()), "Waypoint must be in free space");
    assertTrue(wp.get().distance(center) <= 7.0, "Waypoint must stay within player radius");
  }

  @Test
  void motionNavigatesAroundWallWithoutCrossingBlockedCells() {
    Predicate<Point> free =
        p -> {
          int x = (int) Math.floor(p.x());
          int y = (int) Math.floor(p.y());
          int z = (int) Math.floor(p.z());
          return !(x == 2 && y >= 0 && y <= 2 && z >= -1 && z <= 1);
        };
    Motion motion = new Motion();
    Point center = new Point(2.5, 1.5, 0.5);
    Point start = new Point(0.5, 1.5, 0.5);
    Point goal = new Point(4.5, 1.5, 0.5);
    motion.reset(start);

    for (int i = 0; i < 160; i++) {
      motion.tick(center, goal, 7.0, free);
      assertTrue(motion.position().distance(center) <= 7.0 + 1e-6);
      assertTrue(free.test(motion.position()), "Companion entered blocked wall at tick " + i);
    }
    assertTrue(
        motion.position().x() > 2.5,
        "Expected companion to route around the wall past x=2.5, got " + motion.position());
  }

  @Test
  void returnsEmptyWhenGoalCompletelyEnclosed() {
    Predicate<Point> free = p -> p.distance(new Point(0.5, 1.5, 0.5)) < 0.8;
    var wp =
        VoxelPathfinder.nextWaypoint(
            new Point(0.5, 1.5, 0.5),
            new Point(0.5, 1.5, 0.5),
            new Point(4.5, 1.5, 0.5),
            7.0,
            free);
    assertTrue(wp.isEmpty());
  }
}
