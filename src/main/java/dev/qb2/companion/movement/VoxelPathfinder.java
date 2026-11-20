package dev.qb2.companion.movement;

import java.util.*;
import java.util.function.Predicate;

/**
 * Bounded 3D voxel A* pathfinder for local obstacle avoidance within the companion's permitted
 * sphere around the player.
 */
public final class VoxelPathfinder {
  private static final int MAX_EXPANSIONS = 256;
  private static final int[][] CARDINALS = {
    {1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}
  };
  private static final long MASK_21 = (1L << 21) - 1L;
  private static final int BIAS_21 = 1 << 20;

  private record Node(long key, double g, double f) {}

  private VoxelPathfinder() {}

  private static long pack(int x, int y, int z) {
    return (((long) (x + BIAS_21) & MASK_21) << 42)
        | (((long) (y + BIAS_21) & MASK_21) << 21)
        | ((long) (z + BIAS_21) & MASK_21);
  }

  private static int unpackX(long key) {
    return (int) ((key >>> 42) & MASK_21) - BIAS_21;
  }

  private static int unpackY(long key) {
    return (int) ((key >>> 21) & MASK_21) - BIAS_21;
  }

  private static int unpackZ(long key) {
    return (int) (key & MASK_21) - BIAS_21;
  }

  private static Point centerOf(long key) {
    return new Point(unpackX(key) + 0.5, unpackY(key) + 0.5, unpackZ(key) + 0.5);
  }

  private static int manhattan(long a, long b) {
    return Math.abs(unpackX(a) - unpackX(b))
        + Math.abs(unpackY(a) - unpackY(b))
        + Math.abs(unpackZ(a) - unpackZ(b));
  }

  public static Optional<Point> nextWaypoint(
      Point sphereCenter, Point start, Point goal, double radius, Predicate<Point> free) {
    long startKey = pack((int) Math.floor(start.x()), (int) Math.floor(start.y()), (int) Math.floor(start.z()));
    long goalKey = pack((int) Math.floor(goal.x()), (int) Math.floor(goal.y()), (int) Math.floor(goal.z()));
    if (startKey == goalKey) return Optional.empty();
    if (start.distance(goal) > Math.max(radius * 2 + 2, 24)) return Optional.empty();

    var open = new PriorityQueue<Node>(Comparator.comparingDouble(Node::f));
    var gScore = new HashMap<Long, Double>(64);
    var parent = new HashMap<Long, Long>(64);
    var closed = new HashSet<Long>(64);

    gScore.put(startKey, 0.0);
    open.add(new Node(startKey, 0.0, manhattan(startKey, goalKey)));

    long best = startKey;
    int bestHeuristic = manhattan(startKey, goalKey);
    int expanded = 0;

    while (!open.isEmpty() && expanded < MAX_EXPANSIONS) {
      Node current = open.poll();
      if (!closed.add(current.key)) continue;
      expanded++;

      int h = manhattan(current.key, goalKey);
      if (h < bestHeuristic) {
        bestHeuristic = h;
        best = current.key;
      }
      if (current.key == goalKey) {
        best = goalKey;
        break;
      }

      int cx = unpackX(current.key);
      int cy = unpackY(current.key);
      int cz = unpackZ(current.key);
      for (int[] dir : CARDINALS) {
        long neighbor = pack(cx + dir[0], cy + dir[1], cz + dir[2]);
        if (closed.contains(neighbor)) continue;
        Point np = neighbor == goalKey ? goal : centerOf(neighbor);
        if (np.distance(sphereCenter) > radius) continue;
        if (!free.test(np)) continue;

        double tentativeG = current.g + 1.0;
        if (tentativeG < gScore.getOrDefault(neighbor, Double.POSITIVE_INFINITY)) {
          gScore.put(neighbor, tentativeG);
          parent.put(neighbor, current.key);
          open.add(new Node(neighbor, tentativeG, tentativeG + manhattan(neighbor, goalKey)));
        }
      }
    }

    if (best == startKey) return Optional.empty();
    long step = best;
    while (parent.containsKey(step) && parent.get(step) != startKey) {
      step = parent.get(step);
    }
    return Optional.of(centerOf(step));
  }
}
