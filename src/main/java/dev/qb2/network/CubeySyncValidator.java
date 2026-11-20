package dev.qb2.network;

import dev.qb2.companion.movement.Point;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Pure Java security validator for optional multiplayer companion telemetry. Enforces finite
 * coordinates, 24-block anti-spoofing distance from the true player position, state/hat
 * whitelisting, speech sanitization, and per-player rate limiting.
 */
public final class CubeySyncValidator {
  public static final double MAX_OWNER_DISTANCE = 24.0;
  public static final double BROADCAST_RADIUS = 48.0;
  public static final long MIN_INTERVAL_NANOS = 80_000_000L; // ~12.5 Hz max

  public static final Set<String> ALLOWED_STATES =
      Set.of(
          "Idle",
          "Exploring",
          "Curious",
          "Thinking",
          "Speaking",
          "Napping",
          "Returning",
          "Wingman",
          "Riding",
          "Guiding",
          "Resting");

  public static final Set<String> ALLOWED_HATS =
      Set.of("none", "antenna", "crown", "miner", "rain", "sleep", "propeller", "chef");

  public record ValidatedTelemetry(
      double x,
      double y,
      double z,
      float yaw,
      float pitch,
      float roll,
      String state,
      String hat,
      String publicSpeech) {}

  private final Map<UUID, Long> lastPacketNanos = new ConcurrentHashMap<>();

  public boolean allowRate(UUID playerUuid, long nowNanos) {
    if (playerUuid == null) return false;
    Long prev = lastPacketNanos.get(playerUuid);
    if (prev != null && nowNanos - prev < MIN_INTERVAL_NANOS) return false;
    lastPacketNanos.put(playerUuid, nowNanos);
    if (lastPacketNanos.size() > 256) lastPacketNanos.clear();
    return true;
  }

  public static ValidatedTelemetry validate(
      Point trueOwnerPosition,
      double x,
      double y,
      double z,
      float yaw,
      float pitch,
      float roll,
      String rawState,
      String rawHat,
      String rawSpeech) {
    if (trueOwnerPosition == null) return null;
    if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) return null;
    if (!Float.isFinite(yaw) || !Float.isFinite(pitch) || !Float.isFinite(roll)) return null;

    Point cubeyPos = new Point(x, y, z);
    if (cubeyPos.distance(trueOwnerPosition) > MAX_OWNER_DISTANCE) return null;

    String state =
        rawState != null && ALLOWED_STATES.contains(rawState) ? rawState : "Idle";
    String hat =
        rawHat != null && ALLOWED_HATS.contains(rawHat) ? rawHat : "antenna";
    String speech = sanitizeSpeech(rawSpeech);

    return new ValidatedTelemetry(
        x,
        y,
        z,
        clampAngle(yaw),
        Math.clamp(pitch, -90f, 90f),
        Math.clamp(roll, -180f, 180f),
        state,
        hat,
        speech);
  }

  public static String sanitizeSpeech(String raw) {
    if (raw == null || raw.isBlank()) return "";
    String clean = raw.replace('§', ' ').replaceAll("\\p{Cntrl}", " ").trim();
    return clean.length() > 140 ? clean.substring(0, 140) : clean;
  }

  private static float clampAngle(float deg) {
    float mod = deg % 360f;
    return mod > 180f ? mod - 360f : mod < -180f ? mod + 360f : mod;
  }
}
