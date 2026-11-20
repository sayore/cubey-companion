package dev.qb2.cognition.planning;

import java.util.*;

/**
 * Holds deferred speech (generated while the player was idle/AFK, delivered when the player moves
 * again) and a bounded ring buffer of previously model-generated thoughts/phrases keyed by
 * block/entity registry ID to bridge transient API outages (e.g. HTTP 503) without static scripts.
 */
public final class AmbientBuffer {
  private static final int MAX_KEYS = 32;
  private static final int MAX_PER_KEY = 3;
  private final LinkedHashMap<String, ArrayDeque<String>> phrasesByKey = new LinkedHashMap<>();
  private String deferredSpeech = "";
  private String deferredThought = "";

  public synchronized void deferForActiveReturn(String speech, String thought) {
    if (speech != null && !speech.isBlank()) deferredSpeech = speech.trim();
    if (thought != null && !thought.isBlank()) deferredThought = thought.trim();
  }

  public synchronized Optional<String> pollDeferredSpeech() {
    if (deferredSpeech.isBlank()) return Optional.empty();
    String out = deferredSpeech;
    deferredSpeech = "";
    return Optional.of(out);
  }

  public synchronized Optional<String> pollDeferredThought() {
    if (deferredThought.isBlank()) return Optional.empty();
    String out = deferredThought;
    deferredThought = "";
    return Optional.of(out);
  }

  public synchronized void storePhrase(String registryId, String phrase) {
    if (phrase == null || phrase.isBlank()) return;
    String key = (registryId == null || registryId.isBlank()) ? "general" : registryId;
    var queue = phrasesByKey.computeIfAbsent(key, k -> new ArrayDeque<>());
    String trimmed = phrase.trim();
    if (queue.contains(trimmed)) return;
    queue.addLast(trimmed);
    while (queue.size() > MAX_PER_KEY) queue.removeFirst();
    while (phrasesByKey.size() > MAX_KEYS) {
      phrasesByKey.remove(phrasesByKey.keySet().iterator().next());
    }
  }

  public synchronized Optional<String> consumePhrase(String registryId) {
    if (registryId != null && !registryId.isBlank()) {
      var exact = phrasesByKey.get(registryId);
      if (exact != null && !exact.isEmpty()) return Optional.of(exact.removeFirst());
    }
    var general = phrasesByKey.get("general");
    if (general != null && !general.isEmpty()) return Optional.of(general.removeFirst());
    return Optional.empty();
  }

  public synchronized boolean hasDeferred() {
    return !deferredSpeech.isBlank() || !deferredThought.isBlank();
  }

  public synchronized void clear() {
    phrasesByKey.clear();
    deferredSpeech = "";
    deferredThought = "";
  }
}
