package dev.qb2.perception;

import java.util.*;
import net.minecraft.client.player.LocalPlayer;

/** Client-visible player conditions. Damage causes are not inferred from a health change. */
public final class PlayerSituationSensor {
  public record Vitals(
      float health, int food, int air, boolean burning, boolean submerged, boolean sprinting) {}

  public static Vitals capture(LocalPlayer player) {
    return new Vitals(
        player.getHealth(),
        player.getFoodData().getFoodLevel(),
        player.getAirSupply(),
        player.isOnFire(),
        player.isUnderWater(),
        player.isSprinting());
  }

  public static List<String> changes(Vitals before, Vitals now) {
    if (before == null) return List.of();
    var events = new ArrayList<String>();
    if (now.health() < before.health())
      events.add(
          now.health() <= 0
              ? "Player died (client health reached zero)."
              : "Player health dropped from "
                  + before.health()
                  + " to "
                  + now.health()
                  + "; cause unknown.");
    if (now.burning() && !before.burning()) events.add("Player is now on fire.");
    if (now.submerged() && !before.submerged()) events.add("Player went underwater.");
    return List.copyOf(events);
  }
}
