package dev.qb2.perception.chat;

import java.util.*;

/**
 * Zero-LLM-token "slow-dispense" chat compactor. Strips German/English filler & stop words and
 * turns older conversation turns into dense {@code A→B→C} logic chains so Cubey remembers topics
 * from much earlier in a session while spending almost no context tokens.
 */
public final class ChatCompactor {
  private static final Set<String> STOP_WORDS =
      Set.copyOf(
          List.of(
              // German fillers & function words
              "der", "die", "das", "den", "dem", "des", "ein", "eine", "einen", "einem", "einer",
              "ich", "du", "er", "sie", "es", "wir", "ihr", "mir", "dir", "mich", "dich", "uns",
              "also", "halt", "eigentlich", "irgendwie", "einfach", "mal", "denn", "doch", "schon",
              "noch", "jetzt", "gerade", "eben", "hier", "dort", "nur", "sehr", "ganz", "echt",
              "wirklich", "vielleicht", "glaube", "meine", "denke", "sagen", "fragen", "frage",
              "dass", "weil", "aber", "oder", "und", "auch", "wie", "was", "wer", "wo", "wann",
              "kannst", "kann", "könnte", "würde", "habe", "hast", "hat", "haben", "bin", "bist",
              "ist", "sind", "war", "waren", "wird", "werden", "vorher", "gesprochen", "erinnern",
              "auf", "in", "im", "an", "am", "zu", "zum", "zur", "von", "vom", "bei", "für", "über",
              "mit", "nach", "aus", "um", "so", "ja", "nein", "ok", "okay", "bitte", "danke",
              // English fillers & function words
              "the", "a", "this", "that", "these", "those", "i", "you", "he", "she", "it", "we",
              "they", "me", "my", "your", "just", "actually", "basically", "really", "very", "quite",
              "kind", "sort", "like", "well", "uh", "please", "thanks", "can", "could", "would",
              "should", "have", "has", "had", "is", "are", "was", "were", "be", "been", "do",
              "does", "did", "of", "to", "on", "at", "for", "from", "by", "about", "into",
              "what", "where", "when", "which", "who", "how", "and", "or", "but", "if", "then"));

  private ChatCompactor() {}

  /**
   * Compresses a single utterance into a dense, symbol-assisted logical chain (max ~85 chars).
   */
  public static String compactLine(ChatHistory.Source source, String raw) {
    if (raw == null || raw.isBlank()) return "";
    String text = raw.replace('\n', ' ').trim();
    // Normalize common causal/definitional phrases into compact arrows/symbols
    text =
        text.replaceAll(
            "(?i)\\b(damit|sodass|führt zu|ergibt|erzeugt|macht|becomes|yields|produces|turns into|makes)\\b",
            " → ");
    text =
        text.replaceAll(
            "(?i)\\b(bedeutet|heißt|steht für|ist für|means|stands for)\\b", " = ");
    boolean isQuestion = text.contains("?");

    String[] tokens = text.split("\\s+");
    List<String> kept = new ArrayList<>();
    for (String rawTok : tokens) {
      String tok = rawTok.replaceAll("^[,.;:!\"'()\\[\\]]+|[,.;:!\"'()\\[\\]]+$", "");
      if (tok.isEmpty()) continue;
      if (tok.equals("→") || tok.equals("=") || tok.equals("+")) {
        if (!kept.isEmpty() && !kept.getLast().equals(tok)) kept.add(tok);
        continue;
      }
      String lower = tok.toLowerCase(Locale.ROOT);
      if (lower.startsWith("@")) continue; // drop @cube / @qb trigger prefix
      if (STOP_WORDS.contains(lower)) continue;
      if (tok.length() <= 1 && !Character.isDigit(tok.charAt(0))) continue;
      kept.add(tok);
      if (kept.size() >= 11) break;
    }
    if (kept.isEmpty()) return "";
    // Remove dangling operator at end
    while (!kept.isEmpty()
        && (kept.getLast().equals("→") || kept.getLast().equals("=") || kept.getLast().equals("+"))) {
      kept.removeLast();
    }
    if (kept.isEmpty()) return "";

    String prefix =
        source == ChatHistory.Source.PLAYER
            ? "P:"
            : source == ChatHistory.Source.QB2 ? "Q:" : "S:";
    String body = String.join(" ", kept) + (isQuestion ? "?" : "");
    if (body.length() > 80) body = body.substring(0, 77) + "…";
    return prefix + body;
  }

  /**
   * Merges a player prompt + Cubey reply into one ultra-compact {@code P:... → Q:...} chain.
   */
  public static String compactPair(String playerCompact, String qb2Compact) {
    if (playerCompact == null || playerCompact.isBlank()) return qb2Compact == null ? "" : qb2Compact;
    if (qb2Compact == null || qb2Compact.isBlank()) return playerCompact;
    String merged = playerCompact + " → " + qb2Compact;
    return merged.length() > 115 ? merged.substring(0, 112) + "…" : merged;
  }
}
