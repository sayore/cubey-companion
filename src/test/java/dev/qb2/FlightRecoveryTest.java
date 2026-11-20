package dev.qb2;

import static org.junit.jupiter.api.Assertions.*;

import dev.qb2.companion.movement.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class FlightRecoveryTest {
  @Test
  void recoversWhenChunkGeometryAppearsAroundTheCube() {
    var motion = new Motion();
    motion.reset(Point.ZERO);
    motion.tick(Point.ZERO, new Point(2, 0, 0), 3, p -> p.x() > .8);
    assertTrue(motion.position().x() > .8);
    assertTrue(motion.position().length() <= 3);
  }

  @Test
  void blockedHomeGoalIsReplacedByAClearBoundedHoverPoint() {
    var point = FlightClearance.nearest(Point.ZERO, new Point(1, 1.6, 0), 3, p -> p.x() < .2);
    assertTrue(point.x() < .2);
    assertTrue(point.length() < 3);
  }

  @Test
  void unavailableChunksDoNotCauseAnUnboundedSearchOrOutOfRangeTeleport() {
    var checks = new AtomicInteger();
    var point =
        FlightClearance.nearest(
            Point.ZERO,
            new Point(100, 0, 0),
            7,
            p -> {
              checks.incrementAndGet();
              return false;
            });
    assertTrue(point.length() <= 7);
    assertTrue(checks.get() <= 131);
  }

  @Test
  void recoveryDoesNotTeleportThroughObstaclesWhenCurrentPositionIsClear() {
    var motion = new Motion();
    motion.reset(new Point(-1, 0, 0));
    for (int i = 0; i < 200; i++)
      motion.tick(Point.ZERO, new Point(1, 0, 0), 3, p -> Math.abs(p.x()) > .2);
    assertTrue(motion.position().x() < -.2);
  }
}
