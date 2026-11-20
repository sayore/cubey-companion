package dev.qb2.perception.chat;

import java.util.*;

public final class ChatHistory {
  public enum Source {
    PLAYER,
    QB2,
    SYSTEM,
    SERVER,
    UNKNOWN
  }

  public record Line(Source source, String text, long tick) {}

  private final Deque<Line> lines = new ArrayDeque<>();

  public void add(Source source, String text, long tick) {
    lines.addLast(new Line(source, text.substring(0, Math.min(600, text.length())), tick));
    while (lines.size() > 20) lines.removeFirst();
  }

  public void copyFrom(ChatHistory other) {
    if (other == null) return;
    lines.clear();
    lines.addAll(other.lines);
    while (lines.size() > 20) lines.removeFirst();
  }

  public List<Line> snapshot() {
    return List.copyOf(lines);
  }

  public void clear() {
    lines.clear();
  }

  public static boolean addressed(String message, String triggers) {
    String lower = message.toLowerCase(Locale.ROOT).trim();
    for (String trigger : triggers.toLowerCase(Locale.ROOT).split(",")) {
      String t = trigger.trim();
      if (!t.isEmpty()
          && java.util.regex.Pattern.compile(
                  "(?<![\\p{L}\\p{N}_])" + java.util.regex.Pattern.quote(t) + "(?![\\p{L}\\p{N}_])")
              .matcher(lower)
              .find()) return true;
    }
    return lower.matches("(?s).*(?:^|\\s)(qb|cube)[.!?]*$");
  }
}
