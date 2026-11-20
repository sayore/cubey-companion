package dev.qb2.cognition.decisions;

import java.util.*;

/**
 * Bounded priority queue for multi-step companion actions. Higher priority executes first; ties
 * execute in FIFO order so planned thought/action sequences stay logical. When full, the lowest
 * priority item is evicted.
 */
public final class ActionQueue {
  public record Step(String action, String target, int priority, long seq) {}

  public static final int DEFAULT_CAPACITY = 6;
  private final int capacity;
  private final List<Step> items = new ArrayList<>();
  private long nextSeq;

  public ActionQueue() {
    this(DEFAULT_CAPACITY);
  }

  public ActionQueue(int capacity) {
    this.capacity = Math.max(1, capacity);
  }

  public synchronized boolean push(String action, String target, int priority) {
    if (action == null || !Decision.ACTIONS.contains(action)) return false;
    int clampedPriority = Math.clamp(priority, 0, 10);
    Step step = new Step(action, target == null ? "" : target, clampedPriority, nextSeq++);
    if (items.size() >= capacity) {
      int worstIdx = 0;
      for (int i = 1; i < items.size(); i++) {
        Step a = items.get(i);
        Step b = items.get(worstIdx);
        if (a.priority() < b.priority()
            || (a.priority() == b.priority() && a.seq() < b.seq())) {
          worstIdx = i;
        }
      }
      if (items.get(worstIdx).priority() >= clampedPriority) return false;
      items.remove(worstIdx);
    }
    items.add(step);
    return true;
  }

  public synchronized Optional<Step> pollNext() {
    if (items.isEmpty()) return Optional.empty();
    int bestIdx = 0;
    for (int i = 1; i < items.size(); i++) {
      Step a = items.get(i);
      Step b = items.get(bestIdx);
      if (a.priority() > b.priority()
          || (a.priority() == b.priority() && a.seq() < b.seq())) {
        bestIdx = i;
      }
    }
    return Optional.of(items.remove(bestIdx));
  }

  public synchronized int size() {
    return items.size();
  }

  public synchronized void clear() {
    items.clear();
  }
}
