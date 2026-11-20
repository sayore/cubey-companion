package dev.qb2.memory;

import dev.qb2.companion.movement.Point;
import java.time.Instant;
import java.util.*;

public record Episode(
    String id,
    String scope,
    String kind,
    List<String> keywords,
    String text,
    String createdAt,
    Double x,
    Double y,
    Double z) {
  public Episode(
      String id, String scope, String kind, List<String> keywords, String text, String createdAt) {
    this(id, scope, kind, keywords, text, createdAt, null, null, null);
  }

  public boolean hasPosition() {
    return x != null && y != null && z != null;
  }

  public static Episode create(String scope, String kind, List<String> keywords, String text) {
    return createAt(scope, kind, keywords, text, null);
  }

  public static Episode createAt(
      String scope, String kind, List<String> keywords, String text, Point pos) {
    return new Episode(
        UUID.nameUUIDFromBytes(
                (scope + "|" + kind + "|" + text + "|" + (Instant.now().getEpochSecond() / 300))
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8))
            .toString(),
        scope,
        kind,
        List.copyOf(keywords),
        text,
        Instant.now().toString(),
        pos == null ? null : pos.x(),
        pos == null ? null : pos.y(),
        pos == null ? null : pos.z());
  }
}
