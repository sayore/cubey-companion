package dev.qb2.inference.provider;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks per-model transient failures (HTTP 429/503/timeout) so unhealthy models are temporarily
 * demoted to the back of the failover chain without stalling subsequent requests.
 */
public final class ModelHealth {
  public static final long DEFAULT_COOLDOWN_MS = 60_000L;
  private static final ConcurrentHashMap<String, Long> UNHEALTHY_UNTIL = new ConcurrentHashMap<>();

  private ModelHealth() {}

  public static void markUnhealthy(String modelId) {
    markUnhealthy(modelId, DEFAULT_COOLDOWN_MS);
  }

  public static void markUnhealthy(String modelId, long cooldownMs) {
    if (modelId == null || modelId.isBlank()) return;
    UNHEALTHY_UNTIL.put(modelId, System.currentTimeMillis() + Math.max(1000L, cooldownMs));
  }

  public static void markHealthy(String modelId) {
    if (modelId == null || modelId.isBlank()) return;
    UNHEALTHY_UNTIL.remove(modelId);
  }

  public static boolean isHealthy(String modelId) {
    if (modelId == null || modelId.isBlank()) return false;
    Long until = UNHEALTHY_UNTIL.get(modelId);
    if (until == null) return true;
    if (System.currentTimeMillis() >= until) {
      UNHEALTHY_UNTIL.remove(modelId, until);
      return true;
    }
    return false;
  }

  /** Returns deduplicated candidates with healthy models first, unhealthy models last. */
  public static List<String> orderCandidates(List<String> preferredOrder) {
    var seen = new LinkedHashSet<String>();
    for (String m : preferredOrder) {
      if (m != null && !m.isBlank()) seen.add(m.trim());
    }
    var healthy = new ArrayList<String>();
    var demoted = new ArrayList<String>();
    for (String m : seen) {
      if (isHealthy(m)) healthy.add(m);
      else demoted.add(m);
    }
    healthy.addAll(demoted);
    return List.copyOf(healthy);
  }

  public static void resetAll() {
    UNHEALTHY_UNTIL.clear();
  }
}
