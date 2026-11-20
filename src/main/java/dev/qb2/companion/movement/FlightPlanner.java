package dev.qb2.companion.movement;

import dev.qb2.perception.Observation;

/** Owns spatial goals, not dialogue. Goals stay stable while an investigation is in progress. */
public final class FlightPlanner {
  public record Goal(Point position, String mode) {}

  private Point held;
  private Point roam;
  private long lastRoam;
  private int waypoint;

  public static Point hoverAbove(Observation target) {
    double height = target.state().equals("visible entity") ? 0.8 : 0.95;
    return target.position().add(new Point(0, height, 0));
  }

  public Goal plan(
      Point player,
      Point home,
      Point position,
      Observation target,
      String action,
      double radius,
      long tick,
      boolean thinking) {
    Point desired;
    String mode;
    if (action.equals("return") || position.distance(player) > radius * 0.92) {
      desired = home;
      mode = "Returning";
    } else if (action.equals("rest")) {
      desired = position;
      mode = "Resting";
    } else if (thinking && held != null && held.distance(player) < radius * 0.9) {
      desired = held;
      mode = position.distance(held) > 0.35 ? "Exploring" : "Curious";
    } else if (target != null && !action.equals("explore")) {
      desired = hoverAbove(target);
      if (action.equals("orbit"))
        desired =
            desired.add(
                new Point(Math.cos(tick * 0.025) * 1.2, 0.15, Math.sin(tick * 0.025) * 1.2));
      mode = position.distance(desired) > 0.35 ? "Exploring" : "Curious";
    } else {
      if (roam == null
          || tick - lastRoam > 120
          || position.distance(roam) < 0.35
          || roam.distance(player) > radius * 0.8) {
        double angle = waypoint++ * 2.399963229728653;
        double distance = Math.max(0.5, radius * 0.52);
        roam =
            player.add(
                new Point(
                    Math.cos(angle) * distance,
                    Math.min(2.3, radius * 0.55),
                    Math.sin(angle) * distance));
        lastRoam = tick;
      }
      desired = roam;
      mode = "Exploring";
    }
    Point bounded = Motion.constrain(player, desired, radius * 0.9);
    if (bounded.distance(desired) > 0.01 && target != null) mode = "Returning";
    held = bounded;
    return new Goal(bounded, mode);
  }
}
