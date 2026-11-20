package dev.qb2.network;

import dev.qb2.companion.movement.Point;
import java.util.*;

/**
 * Tracks and interpolates up to 16 nearby remote players' Cubey companions on the client. Remote
 * speech is strictly visual and never enters the local LLM prompt.
 */
public final class RemoteCompanionTracker {
  public static final int MAX_PEERS = 16;
  public static final long EXPIRY_TICKS = 100L;

  public static final class PeerCubey {
    public final UUID ownerId;
    public final String ownerName;
    public Point prevPos;
    public Point pos;
    public float prevYaw, yaw, pitch, roll;
    public String state = "Idle";
    public String hat = "antenna";
    public String speech = "";
    public long speechUntilTick;
    public long lastUpdateTick;

    PeerCubey(UUID ownerId, String ownerName, Point initialPos, float yaw) {
      this.ownerId = ownerId;
      this.ownerName = ownerName;
      this.prevPos = initialPos;
      this.pos = initialPos;
      this.prevYaw = yaw;
      this.yaw = yaw;
    }

    public Point interpolatePos(float partial) {
      return prevPos.lerp(pos, Math.clamp(partial, 0f, 1f));
    }

    public float interpolateYaw(float partial) {
      float delta = (yaw - prevYaw) % 360f;
      if (delta > 180f) delta -= 360f;
      if (delta < -180f) delta += 360f;
      return prevYaw + delta * Math.clamp(partial, 0f, 1f);
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
      String hat,
      String publicSpeech,
      long clientTick) {
    if (ownerId == null || position == null) return;
    String safeName =
        ownerName == null || ownerName.isBlank()
            ? "Player"
            : ownerName.substring(0, Math.min(24, ownerName.length()));
    PeerCubey peer = peers.get(ownerId);
    if (peer == null) {
      if (peers.size() >= MAX_PEERS) {
        peers.remove(peers.keySet().iterator().next());
      }
      peer = new PeerCubey(ownerId, safeName, position, yaw);
      peers.put(ownerId, peer);
    } else {
      peer.prevPos = peer.pos;
      peer.pos = position;
      peer.prevYaw = peer.yaw;
      peer.yaw = yaw;
    }
    peer.pitch = pitch;
    peer.roll = roll;
    peer.state = CubeySyncValidator.ALLOWED_STATES.contains(state) ? state : "Idle";
    peer.hat = CubeySyncValidator.ALLOWED_HATS.contains(hat) ? hat : "antenna";
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
                        && p.pos.distance(localPlayerPos) > CubeySyncValidator.BROADCAST_RADIUS + 8.0));
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

  public synchronized List<PeerCubey> activePeers() {
    return List.copyOf(peers.values());
  }

  public synchronized void clear() {
    peers.clear();
  }
}
