package dev.qb2.rendering.bubbles;

public final class Bubbles {
  public record Bubble(String text, boolean thought, long start, long end) {
    public float opacity(long tick, float partial) {
      return (float)
          Math.clamp(Math.min((tick + partial - start) / 8.0, (end - tick - partial) / 12.0), 0, 1);
    }
  }

  private Bubble thought, speech;

  public void thought(String text, long tick) {
    thought = new Bubble(text, true, tick, tick + Math.clamp(text.length() * 3L, 140, 280));
  }

  public void speech(String text, long tick) {
    speech = new Bubble(text, false, tick, tick + Math.clamp(text.length() * 2L, 100, 340));
  }

  public Bubble visible(long tick, boolean thoughts, boolean spoken) {
    if (spoken && speech != null && speech.end() > tick) return speech;
    if (thoughts && thought != null && thought.end() > tick) return thought;
    return null;
  }

  /** Returns active bubbles ordered bottom-to-top (speech first, thought stacked above). */
  public java.util.List<Bubble> visibleAll(long tick, boolean thoughts, boolean spoken) {
    var list = new java.util.ArrayList<Bubble>(2);
    if (spoken && speech != null && speech.end() > tick) list.add(speech);
    if (thoughts && thought != null && thought.end() > tick) list.add(thought);
    return list;
  }

  public boolean speaking(long tick) {
    return speech != null && speech.end() > tick;
  }
}
