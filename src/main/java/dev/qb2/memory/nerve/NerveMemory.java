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
      if (bytes + size > 100000 || batch.size() == 24) {
        http.post(endpoint + "/knowledge", key, false, json.toJson(batch), 25);
        batch.clear();
        bytes = 2;
      }
      batch.add(episode);
      bytes += size;
    }
    if (!batch.isEmpty()) http.post(endpoint + "/knowledge", key, false, json.toJson(batch), 25);
  }

  public void delete(String scope, String id) throws Exception {
    http.post(endpoint + "/delete", key, false, json.toJson(Map.of("scope", scope, "id", id)), 5);
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
