package dev.qb2.cognition.decisions;

import com.google.gson.*;
import dev.qb2.cognition.tools.ToolRegistry;
import java.util.*;

public record Decision(
    String action,
    String target,
    String thought,
    String speech,
    String memory,
    List<ToolRegistry.Call> toolCalls,
    List<PlannedStep> plan) {
  public record PlannedStep(String action, String target, int priority) {}

  public Decision(String action, String target, String thought, String speech) {
    this(action, target, thought, speech, "", List.of(), List.of());
  }

  public Decision(String action, String target, String thought, String speech, String memory) {
    this(action, target, thought, speech, memory, List.of(), List.of());
  }

  public static final Set<String> ACTIONS =
      Set.of(
          "observe",
          "inspect",
          "approach",
          "go_to",
          "explore",
          "orbit",
          "return",
          "rest",
          "recall");

  private static final Set<String> TARGET_ACTIONS = Set.of("inspect", "approach", "go_to", "orbit");
  private static final Set<String> ALLOWED_KEYS =
      Set.of("action", "target", "thought", "speech", "memory", "tool_calls", "plan");

  public static Decision parse(String text, Set<String> visibleTargets) {
    return parse(text, visibleTargets, Map.of());
  }

  public static Decision parse(
      String text, Set<String> visibleTargets, Map<String, String> aliasToFullKey) {
    String json = text.trim();
    if (json.startsWith("```")) {
      int newline = json.indexOf('\n');
      if (newline < 0 || !json.endsWith("```"))
        throw new IllegalArgumentException("Invalid fenced JSON");
      json = json.substring(newline + 1, json.length() - 3).trim();
    }
    JsonObject o = JsonParser.parseString(json).getAsJsonObject();
    for (String key : o.keySet())
      if (!ALLOWED_KEYS.contains(key))
        throw new IllegalArgumentException("Unknown decision property");

    var toolCalls = parseToolCalls(o);
    String action = string(o, "action", 30);
    if (action.isBlank() && !toolCalls.isEmpty()) action = "observe";
    if (!ACTIONS.contains(action)) throw new IllegalArgumentException("Action unavailable");

    String rawTarget = string(o, "target", 200);
    String target = resolve(rawTarget, aliasToFullKey);
    if (TARGET_ACTIONS.contains(action) && !visibleTargets.contains(target))
      throw new IllegalArgumentException("Target not visible");

    var plan = parsePlan(o, visibleTargets, aliasToFullKey);
    return new Decision(
        action,
        target,
        string(o, "thought", 360),
        string(o, "speech", 900),
        string(o, "memory", 600),
        toolCalls,
        plan);
  }

  /**
   * Tolerant fallback parser for small local models (e.g. 1B VRAM models) that occasionally wrap
   * JSON in conversational prose, invent unknown action names, or omit targets.
   */
  public static Decision parseTolerant(
      String text, Set<String> visibleTargets, Map<String, String> aliasToFullKey) {
    try {
      return parse(text, visibleTargets, aliasToFullKey);
    } catch (RuntimeException strictError) {
      String trimmed = text == null ? "" : text.trim();
      int firstBrace = trimmed.indexOf('{');
      int lastBrace = trimmed.lastIndexOf('}');
      if (firstBrace < 0 || lastBrace <= firstBrace) throw strictError;
      String candidate = trimmed.substring(firstBrace, lastBrace + 1);
      JsonObject o = JsonParser.parseString(candidate).getAsJsonObject();
      var toolCalls = parseToolCalls(o);
      String action = safeString(o, "action", 30);
      if (!ACTIONS.contains(action)) action = "observe";
      String target = resolve(safeString(o, "target", 200), aliasToFullKey);
      if (!TARGET_ACTIONS.contains(action)) {
        target = "";
      } else if (!visibleTargets.contains(target)) {
        action = "observe";
        target = "";
      }
      var plan = parsePlan(o, visibleTargets, aliasToFullKey);
      return new Decision(
          action,
          target,
          safeString(o, "thought", 360),
          safeString(o, "speech", 900),
          safeString(o, "memory", 600),
          toolCalls,
          plan);
    }
  }

  private static String safeString(JsonObject o, String key, int max) {
    if (!o.has(key) || o.get(key).isJsonNull() || !o.get(key).isJsonPrimitive()) return "";
    String s = o.get(key).getAsString().replace('§', ' ').replaceAll("[\\p{Cntrl}&&[^\\n]]", "");
    return s.length() > max ? s.substring(0, max) : s;
  }

  private static String resolve(String raw, Map<String, String> aliases) {
    if (raw == null || raw.isBlank()) return "";
    if (aliases != null && aliases.containsKey(raw)) return aliases.get(raw);
    return raw;
  }

  private static List<ToolRegistry.Call> parseToolCalls(JsonObject o) {
    if (!o.has("tool_calls") || o.get("tool_calls").isJsonNull()) return List.of();
    if (!o.get("tool_calls").isJsonArray())
      throw new IllegalArgumentException("tool_calls must be an array");
    var out = new ArrayList<ToolRegistry.Call>();
    for (var el : o.getAsJsonArray("tool_calls")) {
      if (!el.isJsonObject()) continue;
      var obj = el.getAsJsonObject();
      String tool = string(obj, "tool", 40);
      String arg = string(obj, "arg", 120);
      if (ToolRegistry.TOOLS.contains(tool)) {
        out.add(new ToolRegistry.Call(tool, arg));
      }
      if (out.size() >= 4) break;
    }
    return List.copyOf(out);
  }

  private static List<PlannedStep> parsePlan(
      JsonObject o, Set<String> visibleTargets, Map<String, String> aliases) {
    if (!o.has("plan") || o.get("plan").isJsonNull()) return List.of();
    if (!o.get("plan").isJsonArray()) throw new IllegalArgumentException("plan must be an array");
    var out = new ArrayList<PlannedStep>();
    for (var el : o.getAsJsonArray("plan")) {
      if (!el.isJsonObject()) continue;
      var obj = el.getAsJsonObject();
      String act = string(obj, "action", 30);
      if (!ACTIONS.contains(act)) continue;
      String tgt = resolve(string(obj, "target", 200), aliases);
      if (TARGET_ACTIONS.contains(act) && !visibleTargets.contains(tgt)) continue;
      int prio =
          obj.has("priority") && obj.get("priority").isJsonPrimitive()
              ? Math.clamp(obj.get("priority").getAsInt(), 0, 10)
              : 1;
      out.add(new PlannedStep(act, tgt, prio));
      if (out.size() >= ActionQueue.DEFAULT_CAPACITY) break;
    }
    return List.copyOf(out);
  }

  private static String string(JsonObject o, String key, int max) {
    if (!o.has(key) || o.get(key).isJsonNull()) return "";
    if (!o.get(key).isJsonPrimitive() || !o.get(key).getAsJsonPrimitive().isString())
      throw new IllegalArgumentException("Expected text");
    String s = o.get(key).getAsString();
    if (s.length() > max || s.codePoints().anyMatch(c -> Character.isISOControl(c) && c != '\n'))
      throw new IllegalArgumentException("Invalid text");
    return s.replace('§', ' ');
  }
}
