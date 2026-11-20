package dev.qb2.memory;

import dev.qb2.perception.Keywords;
import java.util.*;

/**
 * Periodically compacts a bounded window of session events into a concise session_summary episode
 * without requiring an extra model call.
 */
public final class SessionSummarizer {
  public static final int DEFAULT_THRESHOLD = 12;
  private final int threshold;
  private final List<String> buffer = new ArrayList<>();
  private final Map<String, Integer> keywordCounts = new LinkedHashMap<>();

  public SessionSummarizer() {
    this(DEFAULT_THRESHOLD);
  }

  public SessionSummarizer(int threshold) {
    this.threshold = Math.max(3, threshold);
  }

  public Optional<Episode> record(String scope, String kind, String text) {
    if (scope == null || scope.isBlank() || text == null || text.isBlank()) return Optional.empty();
    String compact = text.trim().replaceAll("\\s+", " ");
    if (compact.length() > 140) compact = compact.substring(0, 140) + "...";
    buffer.add(kind + ": " + compact);
    for (String kw : Keywords.extract(compact)) {
      keywordCounts.merge(kw, 1, Integer::sum);
    }
    if (buffer.size() < threshold) return Optional.empty();
    return Optional.of(flush(scope));
  }

  public Episode flush(String scope) {
    var topKeywords =
        keywordCounts.entrySet().stream()
            .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
            .map(Map.Entry::getKey)
            .limit(24)
            .toList();
    var highlights = new LinkedHashSet<String>();
    for (int i = buffer.size() - 1; i >= 0 && highlights.size() < 4; i--) {
      highlights.add(buffer.get(i));
    }
    var summary = new StringBuilder("Session summary (");
    summary.append(buffer.size()).append(" events). Topics: ");
    summary.append(String.join(", ", topKeywords.stream().limit(8).toList()));
    summary.append(". Highlights: ");
    summary.append(String.join(" | ", highlights));
    String text = summary.length() > 900 ? summary.substring(0, 900) + "..." : summary.toString();

    var kw = new LinkedHashSet<String>();
    kw.add("session");
    kw.add("summary");
    kw.addAll(topKeywords);

    buffer.clear();
    keywordCounts.clear();
    return Episode.create(
        scope,
        "session_summary",
        kw.stream().filter(w -> w.length() <= 200).limit(1000).toList(),
        text);
  }

  public int bufferedCount() {
    return buffer.size();
  }

  public void reset() {
    buffer.clear();
    keywordCounts.clear();
  }
}
