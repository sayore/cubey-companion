package dev.qb2.companion.movement;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Records the player's walked path as a bounded breadcrumb trail so the companion can smoothly fly
 * after the player through hallways and doors without rubber-banding or bouncing backward to stale
 * trail points.
 */
public final class PlayerTrail {
  private static final int MAX_POINTS = 64;
  private static final double MIN_SPACING = 1.2;
  private static final double REACHED_RADIUS = 1.45;
  private final Deque<Point> trail = new ArrayDeque<>();

  public void record(Point playerPos) {
    if (playerPos == null) return;
    Point elevated = new Point(playerPos.x(), playerPos.y() + 1.35, playerPos.z());
    if (trail.isEmpty() || trail.peekLast().distance(elevated) >= MIN_SPACING) {
      trail.addLast(elevated);
      while (trail.size() > MAX_POINTS) trail.removeFirst();
    }
  }

  /**
   * Advances forward along the trail, discarding any stale breadcrumbs behind the companion or
   * farther from the player than the companion already is. Guarantees Cubey NEVER bounces backward
   * to the beginning of an old trail!
   */
  public Point nextWaypoint(Point cubePos, Point fallbackGoal) {
    if (trail.isEmpty() || cubePos == null || fallbackGoal == null) return fallbackGoal;

    // 1. Find the breadcrumb closest to cubePos and drop everything older than it so we never
    //    backtrack to stale points recorded while Cubey was already beside the player.
    Point[] pts = trail.toArray(new Point[0]);
    int nearestIdx = 0;
    double nearestDist = cubePos.distance(pts[0]);
    for (int i = 1; i < pts.length; i++) {
      double d = cubePos.distance(pts[i]);
      if (d <= nearestDist) {
        nearestDist = d;
        nearestIdx = i;
      }
    }
    for (int i = 0; i < nearestIdx; i++) {
      trail.removeFirst();
    }

    // 2. Pop reached points and any point that is farther from fallbackGoal than cubePos already is
    double cubeDistToGoal = cubePos.distance(fallbackGoal);
    while (!trail.isEmpty()) {
      Point head = trail.peekFirst();
      if (cubePos.distance(head) <= REACHED_RADIUS
          || head.distance(fallbackGoal) > cubeDistToGoal + 0.5) {
        trail.removeFirst();
      } else {
        break;
      }
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
