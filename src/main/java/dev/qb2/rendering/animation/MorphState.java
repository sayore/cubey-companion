package dev.qb2.rendering.animation;

/**
 * Continuous, frame-smoothed morphing weights and spring-inertia state for Cubey's Neo-Fluid /
 * Sci-Fi split-shell geometry. Smoothly transitions between Cube, Open Hyper-Tesseract, Organic
 * Ferrofluid Blob, Aerodynamic Delta-Drone, and Sealed Mochi-Sleep.
 */
public final class MorphState {
  public enum EyeMood {
    NEUTRAL_BLINK,
    SLEEP_SLITS,
    HAPPY_ARCS,
    CURIOUS_ASYM,
    FOCUS_VISOR,
    ORBIT_SPINNER,
    VOICE_WAVE
  }

  public record Snapshot(
      float tesseractOpen,
      float blobMorph,
      float aeroDelta,
      float mochiSleep,
      float springLagZ,
      float springLagY,
      float squashY,
      float stretchXZ,
      EyeMood eyeMood) {}

  private float tesseractOpen;
  private float blobMorph;
  private float aeroDelta;
  private float mochiSleep;
  private float springLagZ;
  private float springLagY;
  private double lastSpeed;

  public Snapshot update(
      double dt,
      double time,
      double speed,
      boolean napping,
      boolean thinking,
      boolean speaking,
      boolean curious,
      boolean wingman,
      boolean happySocial,
      boolean threatOrAiming,
      boolean inspectingTarget) {
    float alpha = (float) (1.0 - Math.exp(-Math.clamp(dt, 0.001, 0.1) * 7.5));

    float targetSleep = napping ? 1.0f : 0.0f;
    float targetTesseract =
        napping ? 0.0f : (thinking ? 1.0f : inspectingTarget ? 0.68f : 0.0f);
    float targetBlob =
        napping
            ? 0.0f
            : (speaking || happySocial)
                ? 1.0f
                : curious
                    ? 0.72f
                    : 0.15f; // subtle baseline organic fluidity even in idle!
    float speedFactor = (float) Math.clamp((speed - 0.08) / 0.35, 0.0, 1.0);
    float targetAero = napping ? 0.0f : (wingman ? 1.0f : speedFactor);

    tesseractOpen += (targetTesseract - tesseractOpen) * alpha;
    blobMorph += (targetBlob - blobMorph) * alpha;
    aeroDelta += (targetAero - aeroDelta) * alpha;
    mochiSleep += (targetSleep - mochiSleep) * alpha;

    // Spring-inertia lag for floating outer plates & corner shards
    double accel = (speed - lastSpeed) / Math.max(0.01, dt);
    lastSpeed = speed;
    float targetLagZ =
        napping ? 0f : (float) Math.clamp(-speed * 0.055 - accel * 0.004, -0.048, 0.020);
    float targetLagY =
        napping ? 0f : (float) (Math.sin(time * 3.2) * 0.008 * (1.0 + blobMorph));
    springLagZ += (targetLagZ - springLagZ) * alpha;
    springLagY += (targetLagY - springLagY) * alpha;

    // Dynamic squash & stretch (Mochi-pillow flatten when sleeping; organic pulse in blob mode)
    float blobPulse =
        (float) (Math.sin(time * (speaking ? 9.5 : 4.2)) * 0.085 * blobMorph * (1.0f - mochiSleep));
    float sleepFlatten = mochiSleep * 0.16f;
    float squashY = 1.0f - sleepFlatten + blobPulse - aeroDelta * 0.10f;
    float stretchXZ = 1.0f + sleepFlatten * 0.65f - blobPulse * 0.55f;

    EyeMood mood;
    if (napping) {
      mood = EyeMood.SLEEP_SLITS;
    } else if (thinking) {
      mood = EyeMood.ORBIT_SPINNER;
    } else if (speaking) {
      mood = EyeMood.VOICE_WAVE;
    } else if (threatOrAiming) {
      mood = EyeMood.FOCUS_VISOR;
    } else if (happySocial) {
      mood = EyeMood.HAPPY_ARCS;
    } else if (curious || inspectingTarget) {
      mood = EyeMood.CURIOUS_ASYM;
    } else {
      mood = EyeMood.NEUTRAL_BLINK;
    }

    return new Snapshot(
        tesseractOpen,
        blobMorph,
        aeroDelta,
        mochiSleep,
        springLagZ,
        springLagY,
        squashY,
        stretchXZ,
        mood);
  }
}
