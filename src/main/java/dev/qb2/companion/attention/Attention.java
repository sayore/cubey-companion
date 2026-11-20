package dev.qb2.companion.attention;

import dev.qb2.perception.Observation;
import java.util.*;

public final class Attention {
  private Observation target;
  private long since;
  private long lockedUntil;

  public void holdUntil(long tick) {
    lockedUntil = tick;
  }

  private final Map<String, Long> visited = new LinkedHashMap<>();

  public void update(List<Observation> observations, long tick) {
    Observation current =
        target == null
            ? null
            : observations.stream()
                .filter(o -> o.key().equals(target.key()))
                .findFirst()
                .orElse(null);
    if (current != null) target = current;
    if (target != null && (tick - since < 100 || tick < lockedUntil) && current != null) return;
    var best =
        observations.stream()
            .max(
                Comparator.comparingDouble(
                    o -> o.salience() * (visited.containsKey(o.key()) ? 0.25 : 1)))
            .orElse(null);
    if (best != null
        && (target == null
            || current == null
            || tick - since > 240
            || best.salience() > target.salience() * 1.5)) select(best, tick);
    if (current == null && tick - since > 60 && best == null) target = null;
    visited.entrySet().removeIf(e -> tick - e.getValue() > 2400);
  }

  public void select(Observation next, long tick) {
    if (target == null || !target.key().equals(next.key())) {
      target = next;
      since = tick;
      visited.put(next.key(), tick);
    }
    while (visited.size() > 128) visited.remove(visited.keySet().iterator().next());
  }

  public Observation target() {
    return target;
  }

  public void clear() {
    target = null;
    visited.clear();
  }
}
