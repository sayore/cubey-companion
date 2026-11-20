package dev.qb2.memory;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import dev.qb2.client.config.Configuration;
import dev.qb2.companion.movement.Point;
import dev.qb2.memory.nerve.NerveMemory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Built-in standalone Java memory & spatial fallback so Modrinth players who install only the JAR
 * (without running the external Bun/Rust NERVE bridge) still get full episodic & spatial memory
 * without outbox queue saturation.
 */
public final class LocalFallbackMemory {
  private static final int MAX_EPISODES = 400;
  private static final Gson JSON = new Gson();
  private final Path file;
  private final List<Episode> episodes = new ArrayList<>();
  private boolean loaded;

  public LocalFallbackMemory(Path file) {
    this.file = file;
  }

  private synchronized void ensureLoaded() {
    if (loaded) return;
    loaded = true;
    try {
      if (Files.exists(file)) {
        List<Episode> list =
            JSON.fromJson(Files.readString(file), new TypeToken<List<Episode>>() {}.getType());
        if (list != null) episodes.addAll(list);
      }
    } catch (Exception ignored) {
      episodes.clear();
    }
  }

  private synchronized void persist() {
    try {
      Configuration.write(file, JSON.toJson(episodes));
    } catch (Exception ignored) {
      /* Best-effort standalone fallback persistence. */
    }
  }

  public synchronized void writeAll(List<Episode> incoming) {
    ensureLoaded();
    if (incoming == null || incoming.isEmpty()) return;
    for (Episode ep : incoming) {
      if (ep == null || ep.id() == null) continue;
      episodes.removeIf(x -> ep.id().equals(x.id()));
      episodes.add(ep);
    }
    while (episodes.size() > MAX_EPISODES) episodes.remove(0);
    persist();
  }

  public synchronized List<Episode> search(String scope, List<String> keywords, int topK) {
    ensureLoaded();
    if (scope == null || keywords == null || keywords.isEmpty()) return List.of();
    var kwSet = new HashSet<>(keywords);
    record Scored(Episode ep, int score) {}
    var scored = new ArrayList<Scored>();
    for (int i = episodes.size() - 1; i >= 0; i--) {
      Episode e = episodes.get(i);
      if (!scope.equals(e.scope()) || e.keywords() == null) continue;
      int hits = 0;
      for (String w : e.keywords()) if (kwSet.contains(w)) hits++;
      if (hits > 0) scored.add(new Scored(e, hits));
    }
    scored.sort(Comparator.comparingInt(Scored::score).reversed());
    var out = new ArrayList<Episode>();
    for (Scored s : scored) {
      if (out.stream().noneMatch(x -> x.text().equals(s.ep().text()))) out.add(s.ep());
      if (out.size() >= topK) break;
    }
    return List.copyOf(out);
  }

  public synchronized List<NerveMemory.NearbyHit> nearby(
      String scope, Point pos, double radius, int topK) {
    ensureLoaded();
    if (scope == null || pos == null) return List.of();
    var hits = new ArrayList<NerveMemory.NearbyHit>();
    for (Episode e : episodes) {
      if (!scope.equals(e.scope()) || !e.hasPosition()) continue;
      double d = Math.hypot(e.x() - pos.x(), Math.hypot(e.y() - pos.y(), e.z() - pos.z()));
      if (d <= radius) hits.add(new NerveMemory.NearbyHit(e, Math.round(d * 10.0) / 10.0));
    }
    hits.sort(Comparator.comparingDouble(NerveMemory.NearbyHit::distance));
    return hits.subList(0, Math.min(topK, hits.size()));
  }
}
