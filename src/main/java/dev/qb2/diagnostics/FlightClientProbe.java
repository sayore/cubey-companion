package dev.qb2.diagnostics;

import dev.qb2.client.lifecycle.ClientController;
import dev.qb2.cognition.decisions.Decision;
import dev.qb2.companion.movement.*;
import dev.qb2.perception.Observation;
import dev.qb2.perception.world.WorldSensor;
import java.util.Comparator;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.world.phys.AABB;

/** Deterministic, explicit developer checks of real client physics and both bubble renderers. */
public final class FlightClientProbe {
  private int phase, ticks, failures, frames;
  private double traveled, maxDistance;
  private Point previous;
  private Observation target;

  public boolean tick(ClientController controller) {
    var mc = Minecraft.getInstance();
    var session = controller.session;
    if (session == null || mc.player == null || mc.level == null) return false;
    ticks++;
    Point player = WorldSensor.point(mc.player.position()), position = session.motion.position();
    double distance = position.distance(player);
    maxDistance = Math.max(maxDistance, distance);
    if (distance > controller.configuration.settings().exploreRadius + 0.0001)
      fail("radius exceeded");
    if (previous != null) traveled += previous.distance(position);
    previous = position;
    if (phase <= 1 && ticks % 10 == 0) {
      aim(mc, position);
      capture(mc, "qa-flight-" + String.format(java.util.Locale.ROOT, "%03d", frames++) + ".png");
    }
    if (phase == 0) {
      if (ticks == 1) session.apply(new Decision("explore", "", "", ""));
      if (ticks >= 120) {
        if (traveled < 1) fail("free flight did not travel");
        else System.out.println("QB2_QA_FREE_FLIGHT_OK traveled=" + traveled);
        phase++;
        ticks = 0;
      }
    } else if (phase == 1) {
      if (target == null) {
        target =
            session.observations.stream()
                .filter(o -> !o.state().equals("visible entity"))
                .filter(
                    o ->
                        FlightPlanner.hoverAbove(o).distance(player)
                            < controller.configuration.settings().exploreRadius * 0.85)
                .filter(o -> FlightPlanner.hoverAbove(o).distance(position) > 0.7)
                .filter(
                    o -> {
                      Point a = FlightPlanner.hoverAbove(o);
                      return mc.level.noCollision(
                          new AABB(
                              a.x() - .18, a.y() - .18, a.z() - .18, a.x() + .18, a.y() + .18,
                              a.z() + .18));
                    })
                .min(
                    Comparator.comparingDouble(
                        o -> Math.abs(FlightPlanner.hoverAbove(o).distance(position) - 2)))
                .orElse(null);
        if (target != null) {
          String response =
              new com.google.gson.Gson()
                  .toJson(java.util.Map.of("action", "go_to", "target", target.key()));
          session.apply(Decision.parse(response, Set.of(target.key())));
        } else if (ticks > 80) {
          fail("no reachable observed block for go_to");
          phase++;
          ticks = 0;
        }
      } else {
        double remaining = position.distance(FlightPlanner.hoverAbove(target));
        if (ticks >= 40 && remaining < 0.35 || ticks >= 220) {
          if (remaining >= 0.35 || position.y() < target.position().y() + .65)
            fail("go_to failed to hover above observed block; error=" + remaining);
          else
            System.out.println(
                "QB2_QA_GO_TO_OK error="
                    + remaining
                    + " heightAboveBlockCenter="
                    + (position.y() - target.position().y()));
          aim(mc, position);
          capture(mc, "qa-go-to.png");
          phase++;
          ticks = 0;
        }
      }
    } else if (phase == 2) {
      if (ticks == 1) session.apply(new Decision("return", "", "", ""));
      if (ticks >= 120) {
        double remaining = position.distance(session.flightGoal);
        if (remaining > 0.5) fail("return did not settle near player; error=" + remaining);
        else System.out.println("QB2_QA_RETURN_OK error=" + remaining);
        System.out.println(
            "QB2_QA_RADIUS_OK maxDistance="
                + maxDistance
                + " limit="
                + controller.configuration.settings().exploreRadius);
        phase++;
        ticks = 0;
      }
    } else if (phase == 3) {
      if (ticks == 1) {
        session.apply(new Decision("rest", "", "", ""));
        session.bubbles.thought(
            "UI-Test: Ein privater Gedanke bleibt in einer eigenen, dezenten Gedankenblase.",
            session.tick);
      }
      aim(mc, position);
      if (ticks == 40) {
        capture(mc, "qa-thought.png");
        System.out.println("QB2_QA_THOUGHT_BUBBLE_OK");
      }
      if (ticks >= 80) {
        phase++;
        ticks = 0;
      }
    } else if (phase == 4) {
      if (ticks == 1)
        session.bubbles.speech(
            "UI-Test: Diese lange Sprechblase prueft Zeilenumbruch, die maximale Breite und das Bildschirmbudget. "
                .repeat(4),
            session.tick);
      aim(mc, position);
      if (ticks == 40) {
        capture(mc, "qa-speech.png");
        System.out.println("QB2_QA_SPEECH_BUBBLE_OK");
      }
      if (ticks >= 80) {
        System.out.println("QB2_QA_FINISHED failures=" + failures);
        return true;
      }
    }
    return false;
  }

  private void fail(String reason) {
    failures++;
    System.out.println("QB2_QA_FAILURE " + reason);
  }

  public static void aim(Minecraft mc, Point position) {
    Point delta =
        position.subtract(new Point(mc.player.getX(), mc.player.getEyeY(), mc.player.getZ()));
    mc.player.setYRot((float) -Math.toDegrees(Math.atan2(delta.x(), delta.z())));
    mc.player.setXRot(
        (float) -Math.toDegrees(Math.atan2(delta.y(), Math.hypot(delta.x(), delta.z()))));
  }

  public static void capture(Minecraft mc, String name) {
    Screenshot.grab(mc.gameDirectory, name, mc.getMainRenderTarget(), c -> {});
  }
}
