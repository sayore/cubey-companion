package dev.qb2.memory;

import java.time.Instant;
import java.util.*;

public record Episode(
    String id, String scope, String kind, List<String> keywords, String text, String createdAt) {
  public static Episode create(String scope, String kind, List<String> keywords, String text) {
    return new Episode(
        UUID.nameUUIDFromBytes(
                (scope + "|" + kind + "|" + text + "|" + (Instant.now().getEpochSecond() / 300))
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8))
            .toString(),
        scope,
        kind,
        List.copyOf(keywords),
        text,
        Instant.now().toString());
  }
}
