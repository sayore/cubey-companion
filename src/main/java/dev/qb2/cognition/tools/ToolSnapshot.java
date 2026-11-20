package dev.qb2.cognition.tools;

import dev.qb2.companion.movement.Point;
import java.util.*;

/**
 * Immutable, client-captured data backing read-only worker-thread tool calls without touching live
 * Minecraft state from background threads.
 */
public record ToolSnapshot(
    Map<String, String> aliasToFullKey,
    Map<String, String> detailsByAlias,
    String areaScan,
    String inventorySummary,
    String knowledgeScope,
    Point playerPosition,
    List<String> localCatalogFacts,
    String machineGraphSummary) {
  public static final ToolSnapshot EMPTY =
      new ToolSnapshot(Map.of(), Map.of(), "", "", "", null, List.of(), "");

  public ToolSnapshot(
      Map<String, String> aliasToFullKey,
      Map<String, String> detailsByAlias,
      String areaScan,
      String inventorySummary,
      String knowledgeScope,
      Point playerPosition,
      List<String> localCatalogFacts) {
    this(
        aliasToFullKey,
        detailsByAlias,
        areaScan,
        inventorySummary,
        knowledgeScope,
        playerPosition,
        localCatalogFacts,
        "");
  }

  public ToolSnapshot(
      Map<String, String> aliasToFullKey,
      Map<String, String> detailsByAlias,
      String areaScan,
      String inventorySummary,
      String knowledgeScope,
      Point playerPosition) {
    this(
        aliasToFullKey,
        detailsByAlias,
        areaScan,
        inventorySummary,
        knowledgeScope,
        playerPosition,
        List.of(),
        "");
  }

  public String resolveTarget(String rawTarget) {
    if (rawTarget == null || rawTarget.isBlank()) return "";
    return aliasToFullKey.getOrDefault(rawTarget.trim(), rawTarget.trim());
  }
}
