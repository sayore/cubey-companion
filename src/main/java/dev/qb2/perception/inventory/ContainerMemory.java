package dev.qb2.perception.inventory;

import dev.qb2.companion.movement.Point;
import dev.qb2.memory.Episode;
import dev.qb2.perception.Keywords;
import java.util.*;

/**
 * Remembers the contents and coordinates of containers the player actively opens, deduplicated per
 * block position so QB2 can answer "where did I store X?" via NERVE retrieval.
 */
public final class ContainerMemory {
  private static final int MAX_TRACKED = 64;
  private static final int MAX_ITEMS_PER_EPISODE = 24;
  private final LinkedHashMap<String, String> lastSignature = new LinkedHashMap<>();
  private final LinkedHashMap<String, StoredSummary> summaries = new LinkedHashMap<>();

  private record StoredSummary(Point pos, String text) {}

  public Optional<Episode> record(String scope, Point containerPos, InventorySensor.View view) {
    return record(scope, containerPos, view, null);
  }

  public Optional<Episode> record(
      String scope, Point containerPos, InventorySensor.View view, String dimension) {
    if (scope == null || scope.isBlank() || containerPos == null || view == null)
      return Optional.empty();
    if (view.container().isEmpty()) return Optional.empty();

    int x = (int) Math.floor(containerPos.x());
    int y = (int) Math.floor(containerPos.y());
    int z = (int) Math.floor(containerPos.z());
    String posKey = x + "," + y + "," + z;

    var totals = new LinkedHashMap<String, Integer>();
    var displayNames = new LinkedHashMap<String, String>();
    for (var item : view.container()) {
      totals.merge(item.registryId(), item.count(), Integer::sum);
      displayNames.putIfAbsent(item.registryId(), item.name());
    }

    var sorted =
        totals.entrySet().stream()
            .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
            .limit(MAX_ITEMS_PER_EPISODE)
            .toList();

    var sigBuilder = new StringBuilder(view.title()).append('|');
    var summary = new StringBuilder();
    String label = view.title().isBlank() ? "Container" : view.title();
    summary.append(label).append(" at ").append(posKey).append(" contains: ");
    var kw = new LinkedHashSet<String>();
    kw.add("container");
    kw.add("chest");
    kw.add("storage");
    kw.addAll(Keywords.extract(label));
    kw.add(posKey);

    for (int i = 0; i < sorted.size(); i++) {
      var entry = sorted.get(i);
      String id = entry.getKey();
      String name = displayNames.getOrDefault(id, id);
      if (i > 0) summary.append(", ");
      summary.append(entry.getValue()).append("x ").append(name).append(" (").append(id).append(')');
      sigBuilder.append(id).append('=').append(entry.getValue()).append(';');
      kw.addAll(Keywords.extract(name + " " + id));
    }
    if (totals.size() > MAX_ITEMS_PER_EPISODE) {
      summary.append(" (+").append(totals.size() - MAX_ITEMS_PER_EPISODE).append(" more types)");
    }

    String signature = sigBuilder.toString();
    if (signature.equals(lastSignature.get(posKey))) return Optional.empty();

    lastSignature.put(posKey, signature);
    summaries.put(posKey, new StoredSummary(containerPos, summary.toString()));
    while (lastSignature.size() > MAX_TRACKED) {
      String oldest = lastSignature.keySet().iterator().next();
      lastSignature.remove(oldest);
      summaries.remove(oldest);
    }
    return Optional.of(
        Episode.createAt(
            scope,
            "container_memory",
            kw.stream().filter(w -> w.length() <= 200).limit(1000).toList(),
            summary.toString(),
            containerPos,
            dimension));
  }

  public List<String> lookupNear(Point center, double radius) {
    if (center == null || summaries.isEmpty()) return List.of();
    return summaries.values().stream()
        .filter(s -> s.pos().distance(center) <= radius)
        .sorted(Comparator.comparingDouble(s -> s.pos().distance(center)))
        .limit(3)
        .map(StoredSummary::text)
        .toList();
  }

  public void copyFrom(ContainerMemory other) {
    if (other == null) return;
    lastSignature.clear();
    lastSignature.putAll(other.lastSignature);
    summaries.clear();
    summaries.putAll(other.summaries);
  }

  public void reset() {
    lastSignature.clear();
    summaries.clear();
  }
}
