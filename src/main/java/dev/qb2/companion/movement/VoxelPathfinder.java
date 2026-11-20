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

  private record Cell(int x, int y, int z) {
    Point center() {
      return new Point(x + 0.5, y + 0.5, z + 0.5);
    }

    int manhattan(Cell other) {
      return Math.abs(x - other.x) + Math.abs(y - other.y) + Math.abs(z - other.z);
    }
  }

  private record Node(Cell cell, double g, double f) {}

  private VoxelPathfinder() {}

  public static Optional<Point> nextWaypoint(
      Point sphereCenter, Point start, Point goal, double radius, Predicate<Point> free) {
    Cell startCell = toCell(start);
    Cell goalCell = toCell(goal);
    if (startCell.equals(goalCell)) return Optional.empty();
    if (start.distance(goal) > Math.max(radius * 2 + 2, 24)) return Optional.empty();

    var open = new PriorityQueue<Node>(Comparator.comparingDouble(Node::f));
    var gScore = new HashMap<Cell, Double>();
    var parent = new HashMap<Cell, Cell>();
    var closed = new HashSet<Cell>();

    gScore.put(startCell, 0.0);
    open.add(new Node(startCell, 0.0, startCell.manhattan(goalCell)));

    Cell best = startCell;
    int bestHeuristic = startCell.manhattan(goalCell);
    int expanded = 0;

    while (!open.isEmpty() && expanded < MAX_EXPANSIONS) {
      Node current = open.poll();
      if (!closed.add(current.cell)) continue;
      expanded++;

      int h = current.cell.manhattan(goalCell);
      if (h < bestHeuristic) {
        bestHeuristic = h;
        best = current.cell;
      }
      if (current.cell.equals(goalCell)) {
        best = goalCell;
        break;
      }

      for (int[] dir : CARDINALS) {
        Cell neighbor =
            new Cell(current.cell.x + dir[0], current.cell.y + dir[1], current.cell.z + dir[2]);
        if (closed.contains(neighbor)) continue;
        Point np = neighbor.center();
        if (np.distance(sphereCenter) > radius) continue;
        if (!neighbor.equals(goalCell) && !free.test(np)) continue;
        if (neighbor.equals(goalCell) && !free.test(np)) continue;

        double tentativeG = current.g + 1.0;
        if (tentativeG < gScore.getOrDefault(neighbor, Double.POSITIVE_INFINITY)) {
          gScore.put(neighbor, tentativeG);
          parent.put(neighbor, current.cell);
          open.add(new Node(neighbor, tentativeG, tentativeG + neighbor.manhattan(goalCell)));
        }
      }
    }

    if (best.equals(startCell)) return Optional.empty();
    Cell step = best;
    while (parent.containsKey(step) && !parent.get(step).equals(startCell)) {
      step = parent.get(step);
    }
    return Optional.of(step.center());
  }

  private static Cell toCell(Point p) {
    return new Cell((int) Math.floor(p.x()), (int) Math.floor(p.y()), (int) Math.floor(p.z()));
  }
}
