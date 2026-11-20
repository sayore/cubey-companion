package dev.qb2;

import static org.junit.jupiter.api.Assertions.*;

import dev.qb2.cognition.decisions.Decision;
import dev.qb2.companion.movement.*;
import dev.qb2.perception.Observation;
import dev.qb2.rendering.animation.CubeAnimation;
import dev.qb2.rendering.bubbles.BubbleLayout;
import java.util.Set;
import org.junit.jupiter.api.Test;

class FlightAndVisualTest {
  private static Observation block(Point point) {
    return new Observation(
        "minecraft:chest@3,0,0", "minecraft:chest", "Chest", "block", point, 2, 0);
  }

  @Test
  void goToHoversAboveTheTopSurface() {
    var target = block(new Point(3.5, 0.5, 0.5));
    Point above = FlightPlanner.hoverAbove(target);
    assertEquals(3.5, above.x());
    assertEquals(0.5, above.z());
    assertTrue(above.y() > 1.125);
    var decision =
        Decision.parse(
            "{\"action\":\"go_to\",\"target\":\"minecraft:chest@3,0,0\"}", Set.of(target.key()));
    assertEquals("go_to", decision.action());
  }

  @Test
  void goToRejectsUnknownCoordinates() {
    assertThrows(
        IllegalArgumentException.class,
        () -> Decision.parse("{\"action\":\"go_to\",\"target\":\"somewhere\"}", Set.of()));
  }

  @Test
  void flyToBlockAndSettleAboveIt() {
    var planner = new FlightPlanner();
    var motion = new Motion();
    motion.reset(new Point(0, 1.6, 0));
    var target = block(new Point(3.5, 0.5, 0.5));
    for (int i = 0; i < 220; i++) {
      var goal =
          planner.plan(
              Point.ZERO, new Point(0, 1.6, 0), motion.position(), target, "go_to", 7, i, false);
      motion.tick(Point.ZERO, goal.position(), 7, p -> p.y() >= 1.14);
    }
    assertTrue(motion.position().distance(FlightPlanner.hoverAbove(target)) < 0.15);
  }

  @Test
  void collisionDetoursNeverLeavePlayerRadius() {
    var motion = new Motion();
    motion.reset(new Point(2.9, 0, 0));
    for (int i = 0; i < 800; i++) {
      motion.tick(Point.ZERO, new Point(30, 20, 0), 3, p -> p.x() < 2.95);
      assertTrue(motion.position().distance(Point.ZERO) <= 3.000001);
    }
  }

  @Test
  void evenFullyBlockedRecoveryCannotLeaveThePermittedSphere() {
    var motion = new Motion();
    motion.reset(new Point(8, 0, 0));
    motion.tick(Point.ZERO, new Point(20, 0, 0), 3, p -> false);
    assertTrue(motion.position().length() <= 3);
  }

  @Test
  void movingPlayerAndMomentumNeverExceedRadius() {
    var motion = new Motion();
    motion.reset(new Point(0, 1.6, 0));
    for (int i = 0; i < 1200; i++) {
      Point player = new Point(i * 0.2, Math.sin(i * 0.05), 0);
      motion.tick(player, player.add(new Point(40, 20, 0)), 4, p -> true);
      assertTrue(motion.position().distance(player) <= 4.000001);
    }
  }

  @Test
  void freeExplorationCoversMultipleViewpointsWithoutModel() {
    var planner = new FlightPlanner();
    var motion = new Motion();
    motion.reset(new Point(0, 1.6, 0));
    Point first = motion.position();
    double traveled = 0;
    for (int i = 0; i < 600; i++) {
      var goal =
          planner.plan(
              Point.ZERO, new Point(0, 1.6, 0), motion.position(), null, "explore", 7, i, false);
      Point previous = motion.position();
      motion.tick(Point.ZERO, goal.position(), 7, p -> true);
      traveled += previous.distance(motion.position());
      assertTrue(motion.position().length() <= 7.000001);
    }
    assertTrue(traveled > 12);
    assertTrue(motion.position().distance(first) > 1);
  }

  @Test
  void thinkingHoldsGoalAndReturnOverridesIt() {
    var planner = new FlightPlanner();
    Point home = new Point(0, 1.6, 0);
    var first =
        planner.plan(Point.ZERO, home, home, block(new Point(3, 0, 0)), "go_to", 7, 0, false);
    var held =
        planner.plan(Point.ZERO, home, home, block(new Point(-3, 0, 0)), "go_to", 7, 20, true);
    assertEquals(first.position(), held.position());
    var returned = planner.plan(Point.ZERO, home, home, null, "return", 7, 30, true);
    assertEquals(home, returned.position());
  }

  @Test
  void visualTransitionsAreSmoothAndLightsRemainBounded() {
    var animation = new CubeAnimation();
    var before = animation.update("Idle", 0, 0, 0.016, 0, 0);
    var after = animation.update("Thinking", 0.016, 0, 0.016, 120, 40);
    assertTrue(Math.abs(after.yaw() - before.yaw()) < 20);
    for (int i = 0; i < 1000; i++) {
      var f = animation.update(i % 2 == 0 ? "Speaking" : "Resting", i * .016, .1, .016, 170, 30);
      assertTrue(f.light() >= 0.69 && f.light() <= 0.91);
      assertTrue(Float.isFinite(f.pitch()));
    }
  }

  @Test
  void bubblesStayWithinViewportAtAllDistancesAndAspectRatios() {
    for (double distance : new double[] {.5, .8, 1, 3, 7, 20})
      for (double px : new double[] {.6, 1, 2, 4})
        for (double py : new double[] {.8, 1.4, 3}) {
          var layout = BubbleLayout.calculate(distance, px, py, 160, 30);
          assertEquals(8, layout.lineCount());
          assertTrue(160 * layout.scale() * px / (2 * distance) <= .420001);
          assertTrue(80 * layout.scale() * py / (2 * distance) <= .300001);
        }
  }
}
