package dev.qb2.memory.knowledge;

import dev.qb2.memory.Episode;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Derived game knowledge, separate from experiential memory and never invented by the model. */
public record KnowledgeFact(String kind, String key, List<String> keywords, String text) {
  public KnowledgeFact {
    keywords =
        keywords.stream()
            .filter(w -> !w.isBlank() && w.length() <= 200)
            .distinct()
            .limit(1000)
            .toList();
    if (keywords.isEmpty()) keywords = List.of(kind);
  }

  public Episode episode(String scope) {
    return new Episode(
        UUID.nameUUIDFromBytes((scope + "|" + kind + "|" + key).getBytes(StandardCharsets.UTF_8))
            .toString(),
        scope,
        "knowledge_" + kind,
        List.copyOf(keywords),
        text.substring(0, Math.min(2000, text.length())),
        "1970-01-01T00:00:00Z");
  }
}
