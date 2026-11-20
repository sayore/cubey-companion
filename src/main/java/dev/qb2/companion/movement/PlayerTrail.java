package dev.qb2.companion.movement;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Records the player's walked path as a bounded breadcrumb trail so the companion can smoothly fly
 * after the player through hallways and doors without rubber-banding or teleporting.
 */
public final class PlayerTrail {
  private static final int MAX_POINTS = 64;
  private static final double MIN_SPACING = 1.2;
  private static final double REACHED_RADIUS = 1.35;
  private final Deque<Point> trail = new ArrayDeque<>();

  public void record(Point playerPos) {
    if (playerPos == null) return;
    Point elevated = new Point(playerPos.x(), playerPos.y() + 1.35, playerPos.z());
    if (trail.isEmpty() || trail.peekLast().distance(elevated) >= MIN_SPACING) {
      trail.addLast(elevated);
      while (trail.size() > MAX_POINTS) trail.removeFirst();
    }
  }

  public Point nextWaypoint(Point cubePos, Point fallbackGoal) {
    while (!trail.isEmpty() && cubePos.distance(trail.peekFirst()) <= REACHED_RADIUS) {
      trail.removeFirst();
    }
    return trail.isEmpty() ? fallbackGoal : trail.peekFirst();
  }

  public int size() {
    return trail.size();
  }

  public void clear() {
    trail.clear();
  }
}
