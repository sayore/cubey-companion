package dev.qb2.cognition.tools;

import dev.qb2.memory.nerve.NerveMemory;
import dev.qb2.perception.Keywords;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Executes bounded, read-only tool calls on the cognition worker thread so the model can start with
 * a tiny attention prompt and pull details only when needed.
 */
public final class ToolRegistry {
  public record Call(String tool, String arg) {}

  public static final Set<String> TOOLS =
      Set.of(
          "inspect_details",
          "inspect_machine_graph",
          "scout_block",
          "scan_area",
          "check_inventory",
          "search_memory",
          "lookup_recipe");

  private ToolRegistry() {}

  public static Map<String, String> executeAll(
      List<Call> calls, ToolSnapshot snapshot, NerveMemory memory, String scope) {
    var results = new LinkedHashMap<String, String>();
    if (calls == null || calls.isEmpty()) return results;
    int limit = Math.min(calls.size(), 4);
    for (int i = 0; i < limit; i++) {
      Call c = calls.get(i);
      String key = c.arg().isBlank() ? c.tool() : c.tool() + "(" + c.arg() + ")";
      results.put(key, executeOne(c, snapshot, memory, scope));
    }
    return results;
  }

  public static String executeOne(
      Call call, ToolSnapshot snapshot, NerveMemory memory, String scope) {
    if (call == null || call.tool() == null) return "Unknown tool";
    ToolSnapshot snap = snapshot == null ? ToolSnapshot.EMPTY : snapshot;
    String arg = call.arg() == null ? "" : call.arg().trim();
    return switch (call.tool()) {
      case "inspect_details" -> {
        if (arg.isBlank()) yield "Provide an alias like b1 or a block name.";
        String exact = snap.detailsByAlias().get(arg);
        if (exact != null) yield exact;
        String lower = arg.toLowerCase(Locale.ROOT);
        for (var entry : snap.detailsByAlias().entrySet()) {
          if (entry.getValue().toLowerCase(Locale.ROOT).contains(lower)) yield entry.getValue();
        }
        yield "No visible details found for '" + arg + "'.";
      }
      case "scan_area" ->
          snap.areaScan().isBlank()
              ? "No notable non-terrain blocks found nearby."
              : snap.areaScan();
      case "inspect_machine_graph", "scout_block" ->
          snap.machineGraphSummary().isBlank()
              ? "No connected machine or logistics assembly detected at the current focus."
              : snap.machineGraphSummary();
      case "check_inventory" ->
          snap.inventorySummary().isBlank()
              ? "Inventory is empty and no container is open."
              : snap.inventorySummary();
      case "search_memory" -> {
        if (memory == null || scope == null || scope.isBlank()) yield "Memory unavailable.";
        try {
          var words = Keywords.extract(arg);
          if (words.isEmpty()) words = List.of("container", "session", "player");
          var eps = memory.search(scope, words, 4);
          var out = new ArrayList<String>();
          for (var e : eps) out.add("[" + e.kind() + "] " + e.text());
          if (snap.playerPosition() != null) {
            try {
              for (var hit : memory.nearby(scope, snap.playerPosition(), 20.0, 3)) {
                out.add("[nearby " + hit.distance() + "m] " + hit.episode().text());
              }
            } catch (Exception ignored) {
              /* Spatial lookup is optional. */
            }
          }
          yield out.isEmpty() ? "No matching memories." : String.join(" | ", out);
        } catch (Exception e) {
          yield "Memory search unavailable.";
        }
      }
      case "lookup_recipe" -> {
        var words = Keywords.extract(arg);
        if (words.isEmpty()) yield "Provide an item or block name.";
        if (memory != null
            && snap.knowledgeScope() != null
            && snap.knowledgeScope().matches("[a-f0-9]{64}")) {
          try {
            var facts = memory.search(snap.knowledgeScope(), words, 4);
            if (!facts.isEmpty()) {
              yield facts.stream()
                  .map(f -> "[" + f.kind() + "] " + f.text())
                  .collect(Collectors.joining(" | "));
            }
          } catch (Exception ignored) {
            /* Fall back to in-memory client recipe facts below. */
          }
        }
        String lower = arg.toLowerCase(Locale.ROOT);
        var localMatches =
            snap.localCatalogFacts().stream()
                .filter(
                    f ->
                        f.toLowerCase(Locale.ROOT).contains(lower)
                            || words.stream().anyMatch(w -> f.toLowerCase(Locale.ROOT).contains(w)))
                .limit(4)
                .toList();
        if (!localMatches.isEmpty()) yield String.join(" | ", localMatches);
        if (!snap.localCatalogFacts().isEmpty())
          yield String.join(" | ", snap.localCatalogFacts().stream().limit(3).toList());
        yield "No synced recipe/registry entry found for '" + arg + "'.";
      }
      default -> "Unknown tool: " + call.tool();
    };
  }
}
