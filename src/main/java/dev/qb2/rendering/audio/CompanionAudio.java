package dev.qb2.rendering.audio;

import dev.qb2.client.config.Settings;
import dev.qb2.companion.movement.Point;
import net.minecraft.client.Minecraft;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;

/** Subtle client-local audio cues for companion state transitions. */
public final class CompanionAudio {
  private long lastCueMs;

  public enum Cue {
    SPEECH(SoundEvents.AMETHYST_BLOCK_CHIME, 0.14f, 1.35f),
    DISCOVERY(SoundEvents.EXPERIENCE_ORB_PICKUP, 0.08f, 1.65f),
    SUMMON(SoundEvents.NOTE_BLOCK_CHIME.value(), 0.15f, 1.25f),
    LOOK_HERE(SoundEvents.NOTE_BLOCK_CHIME.value(), 0.14f, 1.55f),
    BACKOFF(SoundEvents.NOTE_BLOCK_BASS.value(), 0.10f, 0.75f);

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
    if (mc == null || mc.level == null || settings == null || !settings.sounds || settings.paused)
      return;
    long now = System.currentTimeMillis();
    if (now - lastCueMs < 350) return;
    lastCueMs = now;
    mc.level.playLocalSound(
        at.x(), at.y(), at.z(), cue.event, SoundSource.AMBIENT, cue.volume, cue.pitch, false);
  }
}
