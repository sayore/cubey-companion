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
  private final Deque<String> compactedChains = new ArrayDeque<>();
  private String pendingPlayerCompact = "";

  public void add(Source source, String text, long tick) {
    lines.addLast(new Line(source, text.substring(0, Math.min(600, text.length())), tick));
    while (lines.size() > 20) {
      evictToCompacted(lines.removeFirst());
    }
  }

  private void evictToCompacted(Line old) {
    String dense = ChatCompactor.compactLine(old.source(), old.text());
    if (dense.isBlank()) return;
    if (old.source() == Source.PLAYER) {
      if (!pendingPlayerCompact.isBlank()) {
        pushChain(pendingPlayerCompact);
      }
      pendingPlayerCompact = dense;
    } else if (old.source() == Source.QB2 && !pendingPlayerCompact.isBlank()) {
      pushChain(ChatCompactor.compactPair(pendingPlayerCompact, dense));
      pendingPlayerCompact = "";
    } else {
      pushChain(dense);
    }
  }

  private void pushChain(String chain) {
    if (chain == null || chain.isBlank()) return;
    compactedChains.addLast(chain);
    while (compactedChains.size() > 12) compactedChains.removeFirst();
  }

  public List<String> compactedMemory() {
    List<String> out = new ArrayList<>(compactedChains);
    if (!pendingPlayerCompact.isBlank()) out.add(pendingPlayerCompact);
    return List.copyOf(out);
  }

  public void copyFrom(ChatHistory other) {
    if (other == null) return;
    lines.clear();
    lines.addAll(other.lines);
    compactedChains.clear();
    compactedChains.addAll(other.compactedChains);
    pendingPlayerCompact = other.pendingPlayerCompact;
    while (lines.size() > 20) lines.removeFirst();
  }

  public List<Line> snapshot() {
    return List.copyOf(lines);
  }

  public void clear() {
    lines.clear();
    compactedChains.clear();
    pendingPlayerCompact = "";
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
