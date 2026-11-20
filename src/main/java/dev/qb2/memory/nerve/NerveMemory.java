package dev.qb2.memory.nerve;

import com.google.gson.*;
import dev.qb2.inference.HttpTransport;
import dev.qb2.memory.Episode;
import java.util.*;

/** QB2 bridge protocol, implemented by bridge/server.ts; not a preexisting NERVE HTTP API. */
public final class NerveMemory {
  private final HttpTransport http;
  private final String endpoint, key;
  private final Gson json = new Gson();

  public NerveMemory(HttpTransport http, String endpoint, String key) {
    this.http = http;
    this.endpoint = endpoint.replaceAll("/+$", "");
    this.key = key;
  }

  public void write(Episode episode) throws Exception {
    http.post(endpoint + "/memories", key, false, json.toJson(episode), 5);
  }

  public void writeBatch(List<Episode> episodes) throws Exception {
    var batch = new ArrayList<Episode>();
    int bytes = 2;
    for (var episode : episodes) {
      int size = json.toJson(episode).getBytes(java.nio.charset.StandardCharsets.UTF_8).length + 1;
      if (size > 100000) throw new IllegalArgumentException("Knowledge record too large");
      if (bytes + size > 512000 || batch.size() == 128) {
        http.post(endpoint + "/knowledge", key, false, json.toJson(batch), 25);
        batch.clear();
        bytes = 2;
      }
      batch.add(episode);
      bytes += size;
    }
    if (!batch.isEmpty()) http.post(endpoint + "/knowledge", key, false, json.toJson(batch), 25);
  }

  public record NearbyHit(Episode episode, double distance) {}

  public void delete(String scope, String id) throws Exception {
    http.post(endpoint + "/delete", key, false, json.toJson(Map.of("scope", scope, "id", id)), 5);
  }

  public List<NearbyHit> nearby(
      String scope, dev.qb2.companion.movement.Point pos, double radius, int topK)
      throws Exception {
    if (pos == null) return List.of();
    var reply =
        JsonParser.parseString(
            http.post(
                endpoint + "/nearby",
                key,
                false,
                json.toJson(
                    Map.of(
                        "scope",
                        scope,
                        "x",
                        pos.x(),
                        "y",
                        pos.y(),
                        "z",
                        pos.z(),
                        "radius",
                        radius,
                        "topK",
                        topK)),
                5));
    var out = new ArrayList<NearbyHit>();
    for (var element : reply.getAsJsonArray()) {
      var obj = element.getAsJsonObject();
      var e = json.fromJson(obj.get("episode"), Episode.class);
      double dist = obj.has("distance") ? obj.get("distance").getAsDouble() : 0.0;
      if (e != null && scope.equals(e.scope()) && e.text() != null && e.text().length() <= 2000) {
        out.add(new NearbyHit(e, dist));
      }
      if (out.size() >= topK) break;
    }
    return List.copyOf(out);
  }

  public List<Episode> search(String scope, List<String> words, int topK) throws Exception {
    var reply =
        JsonParser.parseString(
            http.post(
                endpoint + "/search",
                key,
                false,
                json.toJson(
                    Map.of(
                        "scope",
                        scope,
                        "keywords",
                        words.stream().limit(1000).toList(),
                        "topK",
                        topK)),
                5));
    var out = new ArrayList<Episode>();
    for (var element : reply.getAsJsonArray()) {
      var e = json.fromJson(element, Episode.class);
      if (e.scope().equals(scope)
          && e.text() != null
          && e.text().length() <= 2000
          && out.stream().noneMatch(x -> x.text().equals(e.text()))) out.add(e);
      if (out.size() >= topK) break;
    }
    return List.copyOf(out);
  }
}
