package dev.qb2.network;

import dev.qb2.companion.movement.Point;
import java.util.*;

/**
 * Tracks and smoothly interpolates up to 16 nearby remote players' Cubey companions on the client.
 * Uses continuous frame-time exponential smoothing + velocity dead-reckoning instead of 1-tick
 * partial-tick snapping so 10-20 Hz network updates glide at 60-240 FPS without stutter!
 */
public final class RemoteCompanionTracker {
  public static final int MAX_PEERS = 16;
  public static final long EXPIRY_TICKS = 100L;

  public static final class PeerCubey {
    public final UUID ownerId;
    public final String ownerName;
    public Point pos;
    public Point velocity = Point.ZERO;
    public Point renderPos;
    public float yaw, pitch, roll;
    public float renderYaw, renderPitch, renderRoll;
    public long lastPacketNanos;
    public String state = "Idle";
    public String hat = "antenna";
    public String theme = "neo_cyan";
    public String skin = "obsidian";
    public String cubeyName = "Cubey";
    public String equipItemId = "";
    public String speech = "";
    public long speechUntilTick;
    public long lastUpdateTick;
    public int interactWobbleTicks;

    PeerCubey(UUID ownerId, String ownerName, Point initialPos, float yaw, long nowNanos) {
      this.ownerId = ownerId;
      this.ownerName = ownerName;
      this.pos = initialPos;
      this.renderPos = initialPos;
      this.yaw = yaw;
      this.renderYaw = yaw;
      this.lastPacketNanos = nowNanos;
    }

    /**
     * Advances continuous frame-rate-independent exponential smoothing + gentle velocity
     * dead-reckoning so movement stays silky smooth between network packets.
     */
    public void stepRender(double dt, long nowNanos) {
      double clampedDt = Math.clamp(dt, 0.0005, 0.1);
      double ageSec = Math.clamp((nowNanos - lastPacketNanos) / 1e9, 0.0, 0.14);
      Point extrapolated = pos.add(velocity.scale(ageSec));
      // Snap only on huge teleports (> 8 blocks), otherwise smooth exponentially!
      if (renderPos.distance(extrapolated) > 8.0) {
        renderPos = extrapolated;
      } else {
        double alpha = 1.0 - Math.exp(-clampedDt * 14.0);
        renderPos = renderPos.lerp(extrapolated, alpha);
      }
      float rotAlpha = (float) (1.0 - Math.exp(-clampedDt * 15.0));
      float dyaw = (yaw - renderYaw) % 360f;
      if (dyaw > 180f) dyaw -= 360f;
      if (dyaw < -180f) dyaw += 360f;
      renderYaw += dyaw * rotAlpha;
      renderPitch += (pitch - renderPitch) * rotAlpha;
      renderRoll += (roll - renderRoll) * rotAlpha;
    }
  }

  private final Map<UUID, PeerCubey> peers = new LinkedHashMap<>();

  public synchronized void updatePeer(
      UUID ownerId,
      String ownerName,
      Point position,
      float yaw,
      float pitch,
      float roll,
      String state,
      String hatAndMeta,
      String publicSpeech,
      long clientTick) {
    if (ownerId == null || position == null) return;
    long nowNanos = System.nanoTime();
    String safeName =
        ownerName == null || ownerName.isBlank()
            ? "Player"
            : ownerName.substring(0, Math.min(24, ownerName.length()));
    PeerCubey peer = peers.get(ownerId);
    if (peer == null) {
      if (peers.size() >= MAX_PEERS) {
        peers.remove(peers.keySet().iterator().next());
      }
      peer = new PeerCubey(ownerId, safeName, position, yaw, nowNanos);
      peers.put(ownerId, peer);
    } else {
      double packetDt = Math.clamp((nowNanos - peer.lastPacketNanos) / 1e9, 0.025, 0.35);
      Point rawVel = position.subtract(peer.pos).scale(1.0 / packetDt);
      // Cap extrapolated speed to prevent wild overshoot on packet bursts
      if (rawVel.length() > 18.0) rawVel = rawVel.unit().scale(18.0);
      peer.velocity = peer.velocity.lerp(rawVel, 0.55);
      peer.pos = position;
      peer.yaw = yaw;
      peer.lastPacketNanos = nowNanos;
    }
    peer.pitch = pitch;
    peer.roll = roll;
    peer.state = CubeySyncValidator.ALLOWED_STATES.contains(state) ? state : "Idle";
    String safeMeta = CubeySyncValidator.sanitizeHatAndTheme(hatAndMeta);
    String[] parts = safeMeta.split("\\|", -1);
    peer.hat = parts.length >= 1 && !parts[0].isBlank() ? parts[0] : "antenna";
    peer.theme = parts.length >= 2 && !parts[1].isBlank() ? parts[1] : "neo_cyan";
    peer.cubeyName = parts.length >= 3 && !parts[2].isBlank() ? parts[2] : "Cubey";
    peer.skin = parts.length >= 4 && !parts[3].isBlank() ? parts[3] : "obsidian";
    peer.equipItemId = parts.length >= 5 ? parts[4] : "";
    peer.lastUpdateTick = clientTick;
    String cleanSpeech = CubeySyncValidator.sanitizeSpeech(publicSpeech);
    if (!cleanSpeech.isBlank()) {
      peer.speech = cleanSpeech;
      peer.speechUntilTick = clientTick + 140;
    }
  }

  public synchronized void prune(Point localPlayerPos, long clientTick) {
    peers
        .values()
        .removeIf(
            p ->
                clientTick - p.lastUpdateTick > EXPIRY_TICKS
                    || (localPlayerPos != null
                        && p.pos.distance(localPlayerPos)
                            > CubeySyncValidator.BROADCAST_RADIUS + 8.0));
  }

  public synchronized Optional<PeerCubey> nearestPeer(Point myCubePos, double maxDistance) {
    if (myCubePos == null || peers.isEmpty()) return Optional.empty();
    PeerCubey best = null;
    double bestDist = maxDistance;
    for (PeerCubey p : peers.values()) {
      double d = p.pos.distance(myCubePos);
      if (d <= bestDist) {
        bestDist = d;
        best = p;
      }
    }
    return Optional.ofNullable(best);
  }

  public synchronized Optional<PeerCubey> findRayHit(
      Point eye, Point lookUnit, double maxDistance, double hitRadius) {
    if (eye == null || lookUnit == null || peers.isEmpty()) return Optional.empty();
    PeerCubey best = null;
    double bestAlong = maxDistance;
    for (PeerCubey p : peers.values()) {
      Point toPeer = p.renderPos.subtract(eye);
      double along =
          toPeer.x() * lookUnit.x() + toPeer.y() * lookUnit.y() + toPeer.z() * lookUnit.z();
      if (along < 0.25 || along > bestAlong) continue;
      Point closest = eye.add(lookUnit.scale(along));
      if (closest.distance(p.renderPos) <= hitRadius) {
        bestAlong = along;
        best = p;
      }
    }
    return Optional.ofNullable(best);
  }

  public synchronized void triggerPeerReaction(UUID ownerId, String emote, long clientTick) {
    PeerCubey peer = peers.get(ownerId);
    if (peer == null) return;
    peer.interactWobbleTicks = 24;
    if (emote != null && !emote.isBlank()) {
      peer.speech = emote;
      peer.speechUntilTick = clientTick + 55;
    }
  }

  public synchronized List<PeerCubey> activePeers() {
    return List.copyOf(peers.values());
  }

  public synchronized void clear() {
    peers.clear();
  }
}
