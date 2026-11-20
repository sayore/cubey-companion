package dev.qb2.perception;

import java.util.*;

public final class EventDeduplicator {
  private final Map<String, Long> seen = new LinkedHashMap<>();

  public boolean accept(String key, long tick, int window) {
    Long last = seen.get(key);
    if (last != null && tick - last < window) return false;
    seen.put(key, tick);
    while (seen.size() > 256) seen.remove(seen.keySet().iterator().next());
    return true;
  }

  public void clear() {
    seen.clear();
  }
}
