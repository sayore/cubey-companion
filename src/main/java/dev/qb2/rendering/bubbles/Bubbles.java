package dev.qb2.rendering.bubbles;

public final class Bubbles {
  public record Bubble(String text, boolean thought, boolean emote, long start, long end) {
    public Bubble(String text, boolean thought, long start, long end) {
      this(text, thought, false, start, end);
    }

    public float opacity(long tick, float partial) {
      return (float)
          Math.clamp(Math.min((tick + partial - start) / 8.0, (end - tick - partial) / 12.0), 0, 1);
    }
  }

  private Bubble thought, speech, emote;
  private long talkEndTick;

  public void thought(String text, long tick) {
    thought = new Bubble(text, true, false, tick, tick + Math.clamp(text.length() * 3L, 140, 280));
  }

  public void speech(String text, long tick) {
    speech = new Bubble(text, false, false, tick, tick + Math.clamp(text.length() * 2L, 100, 340));
    // Active vocal mouth animation lasts ~1.5-3.2s even though the readable bubble stays longer!
    talkEndTick = tick + Math.clamp(text.length() / 2L + 24L, 30L, 65L);
  }

  /** Short expressive emoji/reaction bubble that never triggers "Speaking" / VOICE_WAVE state. */
  public void emote(String text, long tick) {
    emote = new Bubble(text, false, true, tick, tick + 58L);
  }

  public Bubble visible(long tick, boolean thoughts, boolean spoken) {
    if (emote != null && emote.end() > tick) return emote;
    if (spoken && speech != null && speech.end() > tick) return speech;
    if (thoughts && thought != null && thought.end() > tick) return thought;
    return null;
  }

  /**
   * Returns a single compact 3D bubble to show above the cube: prioritizes short emotes, then
   * inner thought (since full speech already appears in chat), avoiding double-stacked clutter.
   */
  public Bubble compactAboveCube(long tick, boolean thoughts, boolean spoken) {
    if (emote != null && emote.end() > tick) return emote;
    if (thoughts && thought != null && thought.end() > tick) return thought;
    if (spoken && speech != null && speech.end() > tick) return speech;
    return null;
  }

  public Bubble activeThought(long tick, boolean thoughts) {
    return (thoughts && thought != null && thought.end() > tick) ? thought : null;
  }

  /** True only while Cubey is actively vocalizing (drives VOICE_WAVE equalizer & Speaking state). */
  public boolean speaking(long tick) {
    return speech != null && speech.end() > tick && tick < talkEndTick;
  }

  /** True while a short emoji/reaction emote bubble is active (drives joyful emote hop). */
  public boolean emoting(long tick) {
    return emote != null && emote.end() > tick;
  }
}
