package dev.qb2.companion.behavior;

import dev.qb2.companion.movement.Point;
import java.util.UUID;

/**
 * Pure-Java choreographer for rare, special social animations between Cubeys (Cubey <-> Cubey) and
 * between Cubey and other nearby players (Cubey <-> Other Player). Uses generous cooldowns and
 * deterministic/probabilistic triggers so moments feel spontaneous and special rather than spammy.
 */
public final class SocialChoreography {
  public enum Emote {
    NONE,
    // Cubey <-> Cubey emotes
    DUAL_ORBIT_WALTZ,
    SYNC_BARREL_FLIP,
    ANTENNA_BOOP,
    CURIOUS_HAT_MIRROR,
    // Cubey <-> Other Player emotes
    PLAYER_SHOULDER_PERCH,
    PLAYER_PEEKABOO_SCAN,
    PLAYER_CROUCH_BOW,
    PLAYER_HALO_ORBIT
  }

  public record PoseOffset(
      Point goalPosition,
      Point lookTarget,
      float extraYaw,
      float extraPitch,
      float extraRoll,
      double extraBob,
      String temporaryHat,
      boolean showResonanceLink) {}

  /** Minimum ticks (~40s) between spontaneous random social animations so they stay special. */
  public static final long SPONTANEOUS_COOLDOWN_TICKS = 800L;

  /** Shorter cooldown (~7s) when another player actively crouch-greets or waves at Cubey. */
  public static final long REACTIVE_COOLDOWN_TICKS = 140L;

  private Emote activeEmote = Emote.NONE;
  private int remainingTicks;
  private int totalTicks;
  private long lastSocialTick = -1000L;
  private UUID targetId;
  private String targetName = "";
  private String targetHat = "none";
  private String pendingBubble = "";
  private boolean triggerSound;

  public Emote activeEmote() {
    return activeEmote;
  }

  public int remainingTicks() {
    return remainingTicks;
  }

  public UUID targetId() {
    return targetId;
  }

  public String consumePendingBubble() {
    String b = pendingBubble;
    pendingBubble = "";
    return b;
  }

  public boolean consumeTriggerSound() {
    boolean s = triggerSound;
    triggerSound = false;
    return s;
  }

  public boolean isActive() {
    return activeEmote != Emote.NONE && remainingTicks > 0;
  }

  public void cancel() {
    activeEmote = Emote.NONE;
    remainingTicks = 0;
    targetId = null;
  }

  public void tickDown() {
    if (remainingTicks > 0) {
      remainingTicks--;
      if (remainingTicks == 0) {
        activeEmote = Emote.NONE;
        targetId = null;
      }
    }
  }

  /**
   * Attempts to trigger a rare Cubey <-> Cubey social animation when another player's Cubey is
   * nearby. Deterministic bucket matching on {@code tick / 40} helps both clients pick a social
   * moment at the same time when they meet!
   */
  public boolean tryStartPeerCubeyEmote(
      UUID myOwnerId,
      UUID peerOwnerId,
      String peerOwnerName,
      String peerHat,
      long tick,
      double distance) {
    if (isActive() || peerOwnerId == null || distance > 5.5) return false;
    if (tick - lastSocialTick < SPONTANEOUS_COOLDOWN_TICKS) return false;

    // ~14% chance per check window after cooldown expires so encounters feel natural and special
    long pairSeed =
        (myOwnerId != null ? myOwnerId.getLeastSignificantBits() : 0L)
            ^ peerOwnerId.getLeastSignificantBits()
            ^ (tick / 40L);
    int roll = (int) Math.floorMod(mix64(pairSeed), 100L);
    if (roll >= 14) return false;

    Emote[] peerEmotes = {
      Emote.DUAL_ORBIT_WALTZ,
      Emote.SYNC_BARREL_FLIP,
      Emote.ANTENNA_BOOP,
      Emote.CURIOUS_HAT_MIRROR
    };
    Emote chosen = peerEmotes[(int) Math.floorMod(mix64(pairSeed ^ 0x9E3779B97F4A7C15L), peerEmotes.length)];
    startEmote(chosen, peerOwnerId, peerOwnerName, peerHat, tick);
    return true;
  }

  /**
   * Attempts to trigger a Cubey <-> Other Player interaction (either reactive to a player's
   * crouch-bow/wave, or a rare spontaneous shoulder-perch / peekaboo / halo-orbit).
   */
  public boolean tryStartOtherPlayerEmote(
      UUID otherPlayerId,
      String otherPlayerName,
      boolean otherPlayerCrouchGreeting,
      boolean otherPlayerStationary,
      long tick,
      double distance) {
    if (isActive() || otherPlayerId == null || distance > 6.0) return false;

    if (otherPlayerCrouchGreeting && tick - lastSocialTick >= REACTIVE_COOLDOWN_TICKS) {
      startEmote(Emote.PLAYER_CROUCH_BOW, otherPlayerId, otherPlayerName, "none", tick);
      return true;
    }

    if (tick - lastSocialTick < SPONTANEOUS_COOLDOWN_TICKS) return false;
    long seed = otherPlayerId.getLeastSignificantBits() ^ (tick / 40L);
    int roll = (int) Math.floorMod(mix64(seed), 100L);
    if (roll >= 11) return false;

    Emote chosen;
    int sub = (int) Math.floorMod(mix64(seed ^ 0xBF58476D1CE4E5B9L), 3);
    if (sub == 0 && otherPlayerStationary) {
      chosen = Emote.PLAYER_SHOULDER_PERCH;
    } else if (sub == 1) {
      chosen = Emote.PLAYER_PEEKABOO_SCAN;
    } else {
      chosen = Emote.PLAYER_HALO_ORBIT;
    }
    startEmote(chosen, otherPlayerId, otherPlayerName, "none", tick);
    return true;
  }

  public void startEmote(
      Emote emote, UUID targetId, String targetName, String targetHat, long tick) {
    this.activeEmote = emote;
    this.targetId = targetId;
    this.targetName = targetName == null ? "Friend" : targetName;
    this.targetHat = targetHat == null || targetHat.isBlank() ? "antenna" : targetHat;
    this.lastSocialTick = tick;
    this.triggerSound = true;

    switch (emote) {
      case DUAL_ORBIT_WALTZ -> {
        this.totalTicks = 76;
        this.pendingBubble = "♪ ✦";
      }
      case SYNC_BARREL_FLIP -> {
        this.totalTicks = 44;
        this.pendingBubble = "✦ ^.^ ✦";
      }
      case ANTENNA_BOOP -> {
        this.totalTicks = 48;
        this.pendingBubble = "*boop* ♥";
      }
      case CURIOUS_HAT_MIRROR -> {
        this.totalTicks = 64;
        this.pendingBubble = "? ✦";
      }
      case PLAYER_SHOULDER_PERCH -> {
        this.totalTicks = 75;
        this.pendingBubble = "👋 " + this.targetName;
      }
      case PLAYER_PEEKABOO_SCAN -> {
        this.totalTicks = 56;
        this.pendingBubble = "o/ " + this.targetName;
      }
      case PLAYER_CROUCH_BOW -> {
        this.totalTicks = 42;
        this.pendingBubble = "♥ o/";
      }
      case PLAYER_HALO_ORBIT -> {
        this.totalTicks = 68;
        this.pendingBubble = "✦ ♪";
      }
      default -> {
        this.totalTicks = 0;
        this.pendingBubble = "";
      }
    }
    this.remainingTicks = this.totalTicks;
  }

  /**
   * Computes the 3D goal position, look target, and procedural rotation offsets for the currently
   * active social animation.
   */
  public PoseOffset computePose(
      UUID myOwnerId,
      Point myCubePos,
      Point targetAnchor,
      Point targetLookDir,
      float partialTick) {
    if (!isActive() || targetAnchor == null) return null;
    float elapsed = (totalTicks - remainingTicks) + Math.clamp(partialTick, 0f, 1f);
    float progress = Math.clamp(elapsed / Math.max(1f, totalTicks), 0f, 1f);

    switch (activeEmote) {
      case DUAL_ORBIT_WALTZ -> {
        // Place both Cubeys on opposite sides of a 0.65m horizontal waltz ring around their midpoint
        boolean sideSign =
            myOwnerId != null && targetId != null && myOwnerId.compareTo(targetId) < 0;
        double basePhase = sideSign ? 0.0 : Math.PI;
        double angle = basePhase + progress * Math.PI * 3.0;
        Point mid = myCubePos.lerp(targetAnchor, 0.5);
        Point goal =
            new Point(
                mid.x() + Math.cos(angle) * 0.65,
                targetAnchor.y() + 0.12 + Math.sin(progress * Math.PI * 4.0) * 0.14,
                mid.z() + Math.sin(angle) * 0.65);
        return new PoseOffset(
            goal,
            targetAnchor,
            0f,
            (float) (Math.sin(progress * Math.PI * 4.0) * 12.0),
            22.0f,
            Math.sin(progress * Math.PI * 6.0) * 0.05,
            null,
            true);
      }
      case SYNC_BARREL_FLIP -> {
        Point dir = myCubePos.subtract(targetAnchor);
        Point unit = dir.length() > 0.05 ? dir.unit() : new Point(1, 0, 0);
        Point goal = targetAnchor.add(unit.scale(0.75)).add(new Point(0, Math.sin(progress * Math.PI) * 0.38, 0));
        float roll = progress * 360.0f;
        float pitch = (float) (Math.sin(progress * Math.PI * 2.0) * 18.0);
        return new PoseOffset(goal, targetAnchor, 0f, pitch, roll, 0.0, null, true);
      }
      case ANTENNA_BOOP -> {
        // Glide in to 0.38m at progress=0.5 ("boop"), then recoil back to 0.90m with a happy spin
        Point dir = myCubePos.subtract(targetAnchor);
        Point unit = dir.length() > 0.05 ? dir.unit() : new Point(0, 0, 1);
        double dist = progress < 0.5 ? (0.90 - progress * 1.04) : (0.38 + (progress - 0.5) * 1.15);
        Point goal = targetAnchor.add(unit.scale(dist));
        float pitch = progress < 0.55 ? 25.0f : -12.0f;
        float yawSpin = progress > 0.55 ? (progress - 0.55f) / 0.45f * 360.0f : 0f;
        return new PoseOffset(goal, targetAnchor, yawSpin, pitch, 0f, 0.0, null, true);
      }
      case CURIOUS_HAT_MIRROR -> {
        double angle = progress * Math.PI * 2.0;
        Point goal =
            new Point(
                targetAnchor.x() + Math.cos(angle) * 0.85,
                targetAnchor.y() + 0.08,
                targetAnchor.z() + Math.sin(angle) * 0.85);
        float headTilt = (float) (Math.sin(progress * Math.PI * 4.0) * 22.0);
        return new PoseOffset(goal, targetAnchor, 0f, 6.0f, headTilt, 0.0, targetHat, true);
      }
      case PLAYER_SHOULDER_PERCH -> {
        Point fwd =
            targetLookDir != null && targetLookDir.length() > 0.05
                ? new Point(targetLookDir.x(), 0, targetLookDir.z()).unit()
                : new Point(0, 0, 1);
        Point right = new Point(-fwd.z(), 0, fwd.x());
        Point shoulder = targetAnchor.add(right.scale(0.48)).add(new Point(0, -0.18, 0));
        Point lookAtFace = targetAnchor.add(fwd.scale(0.35));
        float gentleTilt = (float) (Math.sin(progress * Math.PI * 2.0) * 9.0);
        return new PoseOffset(shoulder, lookAtFace, 0f, -8.0f, gentleTilt, 0.0, null, false);
      }
      case PLAYER_PEEKABOO_SCAN -> {
        Point fwd =
            targetLookDir != null && targetLookDir.length() > 0.05
                ? new Point(targetLookDir.x(), 0, targetLookDir.z()).unit()
                : new Point(0, 0, 1);
        Point right = new Point(-fwd.z(), 0, fwd.x());
        double sway = Math.sin(progress * Math.PI * 2.0) * 0.45;
        Point inFront = targetAnchor.add(fwd.scale(1.25)).add(right.scale(sway));
        float tilt = (float) (Math.sin(progress * Math.PI * 4.0) * 20.0);
        return new PoseOffset(inFront, targetAnchor, 0f, 10.0f, tilt, 0.0, null, false);
      }
      case PLAYER_CROUCH_BOW -> {
        // Respectful double-bow + happy hop in place facing the other player
        float bowPitch = (float) (Math.sin(progress * Math.PI * 4.0) * 26.0);
        double hop = Math.abs(Math.sin(progress * Math.PI * 2.0)) * 0.14;
        return new PoseOffset(myCubePos, targetAnchor, 0f, bowPitch, 0f, hop, "crown", false);
      }
      case PLAYER_HALO_ORBIT -> {
        double angle = progress * Math.PI * 2.0;
        Point halo =
            new Point(
                targetAnchor.x() + Math.cos(angle) * 0.85,
                targetAnchor.y() + 0.52 + Math.sin(angle * 2.0) * 0.08,
                targetAnchor.z() + Math.sin(angle) * 0.85);
        return new PoseOffset(halo, targetAnchor, 0f, 12.0f, 18.0f, 0.0, "propeller", false);
      }
      default -> {
        return null;
      }
    }
  }

  private static long mix64(long z) {
    z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
    z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
    return z ^ (z >>> 31);
  }
}
