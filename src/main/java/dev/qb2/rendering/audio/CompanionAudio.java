package dev.qb2.rendering.audio;

import dev.qb2.client.config.Settings;
import dev.qb2.companion.movement.Point;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;

/**
 * Client-local cute synthesized audio cues for Cubey, supporting Silent / Single Chirp /
 * Animal-Crossing-style syllable voice modes, pitch/tempo/variance tuning, and live Settings
 * preview playback.
 */
public final class CompanionAudio {
  private static SoundEvent custom(String path) {
    return SoundEvent.createVariableRangeEvent(ResourceLocation.fromNamespaceAndPath("qb2", path));
  }

  private long lastCueMs;
  private long lastChargeMs;
  private long lastPreviewMs;
  private final java.util.Random rng = new java.util.Random();

  // Animal-Crossing-style syllable stream state
  private int pendingSyllables;
  private long nextSyllableMs;
  private boolean risingEnding;

  public enum Cue {
    SPEECH(custom("speech"), 0.52f, 1.02f),
    SYLLABLE(custom("animalese"), 0.46f, 1.00f),
    DISCOVERY(custom("happy"), 0.56f, 1.04f),
    HAPPY(custom("happy"), 0.62f, 1.08f),
    SUMMON(custom("summon"), 0.58f, 1.00f),
    LOOK_HERE(custom("summon"), 0.50f, 1.12f),
    GRAB(custom("grab"), 0.60f, 1.05f),
    CHARGE(custom("charge"), 0.36f, 1.00f),
    WEEEE(custom("weeee"), 0.72f, 1.02f),
    BOING(custom("boing"), 0.65f, 1.04f),
    SLEEP(custom("sleep"), 0.45f, 1.00f),
    BACKOFF(SoundEvents.NOTE_BLOCK_BASS.value(), 0.14f, 0.78f);

    final SoundEvent event;
    final float volume;
    final float pitch;

    Cue(SoundEvent event, float volume, float pitch) {
      this.event = event;
      this.volume = volume;
      this.pitch = pitch;
    }
  }

  public void play(Minecraft mc, Settings settings, Point at, Cue cue) {
    play(mc, settings, at, cue, cue.pitch);
  }

  public void play(Minecraft mc, Settings settings, Point at, Cue cue, float pitchOverride) {
    if (mc == null
        || mc.level == null
        || settings == null
        || !settings.sounds
        || settings.soundVolume <= 0.01
        || settings.paused) return;
    long now = System.currentTimeMillis();
    if (cue == Cue.CHARGE) {
      if (now - lastChargeMs < 130) return;
      lastChargeMs = now;
    } else if (cue != Cue.WEEEE && cue != Cue.BOING && cue != Cue.GRAB && cue != Cue.SYLLABLE) {
      if (now - lastCueMs < 240) return;
      lastCueMs = now;
    }
    float vol = (float) Math.clamp(cue.volume * settings.soundVolume, 0.0, 1.5);
    float var = (float) Math.clamp(settings.voiceVariance, 0.0, 0.30);
    float pitchJitter = (1.0f - var) + rng.nextFloat() * (var * 2.0f);
    float userPitch = (float) Math.clamp(settings.voicePitch, 0.65, 1.55);
    float finalPitch = Math.clamp(pitchOverride * userPitch * pitchJitter, 0.5f, 2.0f);
    mc.level.playLocalSound(
        at.x(), at.y(), at.z(), cue.event, SoundSource.PLAYERS, vol, finalPitch, false);
  }

  /** Starts Cubey's configured speech voice response (silent / chirp / animalese). */
  public void speak(Minecraft mc, Settings settings, Point at, String text) {
    if (settings == null || !settings.sounds || settings.soundVolume <= 0.01) return;
    String mode = settings.voiceMode == null ? "animalese" : settings.voiceMode;
    if ("silent".equals(mode)) {
      pendingSyllables = 0;
      return;
    }
    if ("chirp".equals(mode)) {
      pendingSyllables = 0;
      play(mc, settings, at, Cue.SPEECH);
      return;
    }
    // Animal-Crossing-style syllable stream (4..16 cute syllables scaled by message length)
    int len = text == null ? 0 : text.trim().length();
    pendingSyllables = Math.clamp(len / 5 + 3, 4, 16);
    risingEnding = text != null && (text.contains("?") || text.contains("!"));
    nextSyllableMs = System.currentTimeMillis();
  }

  /** Drives queued Animal-Crossing syllables each client tick. */
  public void tickSpeechVoice(Minecraft mc, Settings settings, Point at) {
    if (pendingSyllables <= 0 || mc == null || mc.level == null || settings == null) return;
    if (!settings.sounds || !"animalese".equals(settings.voiceMode)) {
      pendingSyllables = 0;
      return;
    }
    long now = System.currentTimeMillis();
    if (now < nextSyllableMs) return;
    pendingSyllables--;
    float inflection = (pendingSyllables == 0 && risingEnding) ? 1.12f : 1.0f;
    play(mc, settings, at, Cue.SYLLABLE, inflection);
    double tempo = Math.clamp(settings.voiceTempo, 0.6, 1.6);
    long intervalMs = Math.round((86.0 / tempo) + rng.nextInt(26));
    nextSyllableMs = now + intervalMs;
  }

  /** Instant live preview sound when dragging voice sliders in SettingsScreen! */
  public void playPreview(
      Minecraft mc, double volume, double pitch, double variance, String voiceMode) {
    if (mc == null || mc.player == null || mc.level == null) return;
    long now = System.currentTimeMillis();
    if (now - lastPreviewMs < 180) return;
    lastPreviewMs = now;
    if ("silent".equals(voiceMode) || volume <= 0.01) return;
    Cue cue = "animalese".equals(voiceMode) ? Cue.SYLLABLE : Cue.SPEECH;
    float vol = (float) Math.clamp(cue.volume * volume, 0.0, 1.5);
    float var = (float) Math.clamp(variance, 0.0, 0.30);
    float jitter = (1.0f - var) + rng.nextFloat() * (var * 2.0f);
    float finalPitch = (float) Math.clamp(cue.pitch * pitch * jitter, 0.5, 2.0);
    var p = mc.player.position();
    mc.level.playLocalSound(p.x, p.y + 1.4, p.z, cue.event, SoundSource.PLAYERS, vol, finalPitch, false);
  }
}
