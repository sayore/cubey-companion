package dev.qb2.cognition.decisions;

import com.google.gson.*;
import java.util.Set;

public record Decision(String action, String target, String thought, String speech, String memory) {
  public Decision(String action, String target, String thought, String speech) {
    this(action, target, thought, speech, "");
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

  public static Decision parse(String text, Set<String> visibleTargets) {
    String json = text.trim();
    if (json.startsWith("```")) {
      int newline = json.indexOf('\n');
      if (newline < 0 || !json.endsWith("```"))
        throw new IllegalArgumentException("Invalid fenced JSON");
      json = json.substring(newline + 1, json.length() - 3).trim();
    }
    JsonObject o = JsonParser.parseString(json).getAsJsonObject();
    for (String key : o.keySet())
      if (!Set.of("action", "target", "thought", "speech", "memory").contains(key))
        throw new IllegalArgumentException("Unknown decision property");
    String action = string(o, "action", 30), target = string(o, "target", 200);
    if (!ACTIONS.contains(action)) throw new IllegalArgumentException("Action unavailable");
    if (Set.of("inspect", "approach", "go_to", "orbit").contains(action)
        && !visibleTargets.contains(target))
      throw new IllegalArgumentException("Target not visible");
    return new Decision(
        action,
        target,
        string(o, "thought", 360),
        string(o, "speech", 500),
        string(o, "memory", 600));
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
