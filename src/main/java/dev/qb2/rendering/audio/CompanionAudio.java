package dev.qb2.rendering.audio;

import dev.qb2.client.config.Settings;
import dev.qb2.companion.movement.Point;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;

/**
 * Client-local cute synthesized audio cues for Cubey, scaled by {@link Settings#soundVolume} and
 * gated by {@link Settings#sounds}.
 */
public final class CompanionAudio {
  private static SoundEvent custom(String path) {
    return SoundEvent.createVariableRangeEvent(ResourceLocation.fromNamespaceAndPath("qb2", path));
  }

  private long lastCueMs;
  private long lastChargeMs;
  private final java.util.Random rng = new java.util.Random();

  public enum Cue {
    SPEECH(custom("speech"), 0.52f, 1.02f),
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
    } else if (cue != Cue.WEEEE && cue != Cue.BOING && cue != Cue.GRAB) {
      if (now - lastCueMs < 260) return;
      lastCueMs = now;
    }
    float vol = (float) Math.clamp(cue.volume * settings.soundVolume, 0.0, 1.5);
    // Natural +-5% pitch variation (0.95..1.05) on every sound cue so Cubey stays expressive!
    float pitchJitter = 0.95f + rng.nextFloat() * 0.10f;
    float finalPitch = Math.clamp(pitchOverride * pitchJitter, 0.5f, 2.0f);
    mc.level.playLocalSound(
        at.x(), at.y(), at.z(), cue.event, SoundSource.PLAYERS, vol, finalPitch, false);
  }
}
