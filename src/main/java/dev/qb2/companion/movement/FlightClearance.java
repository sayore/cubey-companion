package dev.qb2.companion.movement;

import java.util.*;
import java.util.function.Predicate;

/** Bounded recovery for geometry that materializes around a client-only companion. */
public final class FlightClearance {
  private static final List<Point> DIRECTIONS = directions();
  private static final double[] RADII = {0.35, 0.7, 1.4, 2.8, 4.5};

  private FlightClearance() {}

  public static Point nearest(Point player, Point preferred, double radius, Predicate<Point> free) {
    Point bounded = Motion.constrain(player, preferred, radius * 0.95);
    if (free.test(bounded)) return bounded;
    for (double distance : RADII)
      for (Point direction : DIRECTIONS) {
        Point candidate =
            Motion.constrain(player, bounded.add(direction.scale(distance)), radius * 0.95);
        if (free.test(candidate)) return candidate;
      }
    return bounded; // Unloaded/fully solid space: stay bounded, never invent a clear location.
  }

  private static List<Point> directions() {
    var values = new ArrayList<Point>();
    for (int y : new int[] {1, 0, -1})
      for (int x : new int[] {0, -1, 1})
        for (int z : new int[] {0, -1, 1})
          if (x != 0 || y != 0 || z != 0) values.add(new Point(x, y, z).unit());
    return List.copyOf(values);
  }
}
