package dev.qb2.memory;

import dev.qb2.companion.movement.Point;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/**
 * 3D spatial cache & change-detection memory for connected machine contraptions. Computes a
 * deterministic structural hash over components, flows, and live contents; when a machine has not
 * changed, zero duplicate episodes are written. When modified, the cached graph is invalidated, a
 * compact diff is computed, and an updated spatial {@link Episode} is emitted for NERVE/local
 * memory.
 */
public final class MachineGraphMemory {
  public enum Status {
    NEW,
    UPDATED,
    UNCHANGED
  }

  public record StoredMachine(
      String dimension,
      Point centroid,
      String anchorBlock,
      String structuralHash,
      Map<String, Integer> componentCounts,
      List<String> flows,
      List<String> contents,
      String summary,
      long updatedMillis) {}

  public record ObserveResult(Status status, StoredMachine machine, String diff, Episode episode) {}

  private static final double MATCH_RADIUS = 6.5;
  private static final int MAX_ENTRIES = 64;
  private final List<StoredMachine> machines = new ArrayList<>();

  public static String computeHash(
      Map<String, Integer> componentCounts, List<String> flows, List<String> contents) {
    StringBuilder sb = new StringBuilder();
    new TreeMap<>(componentCounts)
        .forEach((k, v) -> sb.append(k).append('=').append(v).append(';'));
    sb.append('|');
    if (flows != null) {
      flows.stream().sorted().forEach(f -> sb.append(f).append(';'));
    }
    sb.append('|');
    if (contents != null) {
      contents.stream().sorted().forEach(c -> sb.append(c).append(';'));
    }
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(sb.toString().getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest, 0, 8); // compact 16-hex-char hash
    } catch (Exception e) {
      return Integer.toHexString(sb.toString().hashCode());
    }
  }

  public synchronized ObserveResult observe(
      String scope,
      String dimension,
      Point centroid,
      String anchorBlock,
      Map<String, Integer> componentCounts,
      List<String> flows,
      List<String> contents,
      String summary) {
    if (centroid == null || componentCounts == null || componentCounts.isEmpty()) {
      return new ObserveResult(Status.UNCHANGED, null, "", null);
    }
    String dim = dimension == null ? "" : dimension;
    String hash = computeHash(componentCounts, flows, contents);

    int existingIdx = -1;
    double bestDist = MATCH_RADIUS;
    for (int i = 0; i < machines.size(); i++) {
      StoredMachine m = machines.get(i);
      if (!m.dimension().equals(dim)) continue;
      double d = m.centroid().distance(centroid);
      if (d <= bestDist) {
        bestDist = d;
        existingIdx = i;
      }
    }

    if (existingIdx >= 0) {
      StoredMachine prev = machines.get(existingIdx);
      if (prev.structuralHash().equals(hash)) {
        return new ObserveResult(Status.UNCHANGED, prev, "", null);
      }
      // Structural hash changed -> invalidate old entry, compute diff, emit updated spatial episode!
      String diff = computeDiff(prev.componentCounts(), componentCounts, prev.flows(), flows);
      StoredMachine updated =
          new StoredMachine(
              dim,
              centroid,
              anchorBlock,
              hash,
              Map.copyOf(componentCounts),
              List.copyOf(flows),
              List.copyOf(contents),
              summary,
              System.currentTimeMillis());
      machines.set(existingIdx, updated);
      Episode ep = buildEpisode(scope, dim, centroid, componentCounts, flows, contents, summary, diff);
      return new ObserveResult(Status.UPDATED, updated, diff, ep);
    }

    StoredMachine created =
        new StoredMachine(
            dim,
            centroid,
            anchorBlock,
            hash,
            Map.copyOf(componentCounts),
            List.copyOf(flows),
            List.copyOf(contents),
            summary,
            System.currentTimeMillis());
    machines.add(created);
    if (machines.size() > MAX_ENTRIES) machines.removeFirst();
    Episode ep = buildEpisode(scope, dim, centroid, componentCounts, flows, contents, summary, "");
    return new ObserveResult(Status.NEW, created, "", ep);
  }

  public synchronized List<StoredMachine> nearby(String dimension, Point pos, double maxRadius) {
    if (pos == null) return List.of();
    String dim = dimension == null ? "" : dimension;
    return machines.stream()
        .filter(m -> m.dimension().equals(dim) && m.centroid().distance(pos) <= maxRadius)
        .sorted(Comparator.comparingDouble(m -> m.centroid().distance(pos)))
        .limit(4)
        .toList();
  }

  public synchronized void clear() {
    machines.clear();
  }

  private static String computeDiff(
      Map<String, Integer> oldC,
      Map<String, Integer> newC,
      List<String> oldFlows,
      List<String> newFlows) {
    List<String> parts = new ArrayList<>();
    Set<String> allKeys = new LinkedHashSet<>(oldC.keySet());
    allKeys.addAll(newC.keySet());
    for (String k : allKeys) {
      int delta = newC.getOrDefault(k, 0) - oldC.getOrDefault(k, 0);
      if (delta != 0) {
        String shortK = k.contains(":") ? k.substring(k.indexOf(':') + 1) : k;
        parts.add((delta > 0 ? "+" : "") + delta + " " + shortK);
      }
      if (parts.size() >= 4) break;
    }
    if (!Objects.equals(oldFlows, newFlows) && !newFlows.isEmpty()) {
      parts.add("flow:" + newFlows.getFirst());
    }
    return String.join(", ", parts);
  }

  private static Episode buildEpisode(
      String scope,
      String dim,
      Point centroid,
      Map<String, Integer> counts,
      List<String> flows,
      List<String> contents,
      String summary,
      String diff) {
    Set<String> kws = new LinkedHashSet<>();
    kws.add("machine");
    kws.add("contraption");
    for (String id : counts.keySet()) {
      kws.add(id);
      int idx = id.indexOf(':');
      if (idx >= 0) kws.add(id.substring(idx + 1));
    }
    for (String f : flows) {
      for (String w : dev.qb2.perception.Keywords.extract(f)) kws.add(w);
    }
    for (String c : contents) {
      for (String w : dev.qb2.perception.Keywords.extract(c)) kws.add(w);
    }
    String text = diff.isBlank() ? summary : (summary + " [Updated: " + diff + "]");
    return Episode.createAt(scope, "machine_graph", List.copyOf(kws), text, centroid, dim);
  }
}
