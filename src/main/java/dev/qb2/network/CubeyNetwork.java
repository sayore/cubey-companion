package dev.qb2.network;

import dev.qb2.companion.movement.Point;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * Optional NeoForge 1.21.1 payload channel for Cubey telemetry, peer Cubey interactions,
 * server-backed Cubey Mini-Backpack & Equipment Slot sync, and visual helper choreography. Because
 * the registrar is marked {@code optional()}, vanilla servers and vanilla/non-modded clients remain
 * 100% compatible.
 */
public final class CubeyNetwork {
  private static final CubeySyncValidator VALIDATOR = new CubeySyncValidator();
  private static final CubeySyncValidator INTERACT_VALIDATOR = new CubeySyncValidator();

  public static volatile Consumer<S2CPeerCubeyPayload> clientReceiver;
  public static volatile Consumer<S2CPeerInteractPayload> clientPeerInteractReceiver;
  public static volatile Consumer<S2CPackSyncPayload> clientPackSyncReceiver;
  public static volatile Consumer<S2CTaskVisualPayload> clientTaskVisualReceiver;
  public static volatile BiConsumer<ServerPlayer, C2SPackActionPayload> serverPackActionHandler;
  public static volatile BiConsumer<ServerPlayer, C2SPackSlotClickPayload>
      serverPackSlotClickHandler;
  public static volatile BiConsumer<ServerPlayer, C2SThrowHitMobPayload> serverThrowHitMobHandler;

  public record C2STelemetryPayload(
      double x,
      double y,
      double z,
      float yaw,
      float pitch,
      float roll,
      String state,
      String hat,
      String speech)
      implements CustomPacketPayload {
    public static final Type<C2STelemetryPayload> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath("qb2", "c2s_telemetry"));

    public static final StreamCodec<FriendlyByteBuf, C2STelemetryPayload> CODEC =
        StreamCodec.of(
            (buf, p) -> {
              buf.writeDouble(p.x);
              buf.writeDouble(p.y);
              buf.writeDouble(p.z);
              buf.writeFloat(p.yaw);
              buf.writeFloat(p.pitch);
              buf.writeFloat(p.roll);
              buf.writeUtf(p.state == null ? "Idle" : p.state, 24);
              buf.writeUtf(p.hat == null ? "antenna" : p.hat, 128);
              buf.writeUtf(p.speech == null ? "" : p.speech, 140);
            },
            buf ->
                new C2STelemetryPayload(
                    buf.readDouble(),
                    buf.readDouble(),
                    buf.readDouble(),
                    buf.readFloat(),
                    buf.readFloat(),
                    buf.readFloat(),
                    buf.readUtf(24),
                    buf.readUtf(128),
                    buf.readUtf(140)));

    @Override
    public Type<? extends CustomPacketPayload> type() {
      return TYPE;
    }
  }

  public record S2CPeerCubeyPayload(
      UUID ownerId,
      String ownerName,
      double x,
      double y,
      double z,
      float yaw,
      float pitch,
      float roll,
      String state,
      String hat,
      String speech)
      implements CustomPacketPayload {
    public static final Type<S2CPeerCubeyPayload> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath("qb2", "s2c_peer_cubey"));

    public static final StreamCodec<FriendlyByteBuf, S2CPeerCubeyPayload> CODEC =
        StreamCodec.of(
            (buf, p) -> {
              buf.writeUUID(p.ownerId);
              buf.writeUtf(p.ownerName == null ? "Player" : p.ownerName, 24);
              buf.writeDouble(p.x);
              buf.writeDouble(p.y);
              buf.writeDouble(p.z);
              buf.writeFloat(p.yaw);
              buf.writeFloat(p.pitch);
              buf.writeFloat(p.roll);
              buf.writeUtf(p.state == null ? "Idle" : p.state, 24);
              buf.writeUtf(p.hat == null ? "antenna" : p.hat, 128);
              buf.writeUtf(p.speech == null ? "" : p.speech, 140);
            },
            buf ->
                new S2CPeerCubeyPayload(
                    buf.readUUID(),
                    buf.readUtf(24),
                    buf.readDouble(),
                    buf.readDouble(),
                    buf.readDouble(),
                    buf.readFloat(),
                    buf.readFloat(),
                    buf.readFloat(),
                    buf.readUtf(24),
                    buf.readUtf(128),
                    buf.readUtf(140)));

    @Override
    public Type<? extends CustomPacketPayload> type() {
      return TYPE;
    }
  }

  public record C2SPeerInteractPayload(UUID targetOwnerId, String action, String itemId)
      implements CustomPacketPayload {
    public static final Type<C2SPeerInteractPayload> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath("qb2", "c2s_peer_interact"));

    public static final StreamCodec<FriendlyByteBuf, C2SPeerInteractPayload> CODEC =
        StreamCodec.of(
            (buf, p) -> {
              buf.writeUUID(p.targetOwnerId);
              buf.writeUtf(p.action == null ? "" : p.action, 24);
              buf.writeUtf(p.itemId == null ? "" : p.itemId, 64);
            },
            buf -> new C2SPeerInteractPayload(buf.readUUID(), buf.readUtf(24), buf.readUtf(64)));

    @Override
    public Type<? extends CustomPacketPayload> type() {
      return TYPE;
    }
  }

  public record S2CPeerInteractPayload(
      UUID actorId, String actorName, UUID targetOwnerId, String action, String itemId)
      implements CustomPacketPayload {
    public static final Type<S2CPeerInteractPayload> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath("qb2", "s2c_peer_interact"));

    public static final StreamCodec<FriendlyByteBuf, S2CPeerInteractPayload> CODEC =
        StreamCodec.of(
            (buf, p) -> {
              buf.writeUUID(p.actorId);
              buf.writeUtf(p.actorName == null ? "Player" : p.actorName, 24);
              buf.writeUUID(p.targetOwnerId);
              buf.writeUtf(p.action == null ? "" : p.action, 24);
              buf.writeUtf(p.itemId == null ? "" : p.itemId, 64);
            },
            buf ->
                new S2CPeerInteractPayload(
                    buf.readUUID(),
                    buf.readUtf(24),
                    buf.readUUID(),
                    buf.readUtf(24),
                    buf.readUtf(64)));

    @Override
    public Type<? extends CustomPacketPayload> type() {
      return TYPE;
    }
  }

  public record C2SPackActionPayload(String action, int flags) implements CustomPacketPayload {
    public static final Type<C2SPackActionPayload> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath("qb2", "c2s_pack_action"));

    public static final StreamCodec<FriendlyByteBuf, C2SPackActionPayload> CODEC =
        StreamCodec.of(
            (buf, p) -> {
              buf.writeUtf(p.action == null ? "" : p.action, 24);
              buf.writeVarInt(p.flags);
            },
            buf -> new C2SPackActionPayload(buf.readUtf(24), buf.readVarInt()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
      return TYPE;
    }
  }

  public record C2SPackSlotClickPayload(int slot, int button, boolean shift)
      implements CustomPacketPayload {
    public static final Type<C2SPackSlotClickPayload> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath("qb2", "c2s_pack_slot_click"));

    public static final StreamCodec<FriendlyByteBuf, C2SPackSlotClickPayload> CODEC =
        StreamCodec.of(
            (buf, p) -> {
              buf.writeVarInt(p.slot);
              buf.writeVarInt(p.button);
              buf.writeBoolean(p.shift);
            },
            buf -> new C2SPackSlotClickPayload(buf.readVarInt(), buf.readVarInt(), buf.readBoolean()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
      return TYPE;
    }
  }

  public record C2SThrowHitMobPayload(int entityId) implements CustomPacketPayload {
    public static final Type<C2SThrowHitMobPayload> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath("qb2", "c2s_throw_hit_mob"));

    public static final StreamCodec<FriendlyByteBuf, C2SThrowHitMobPayload> CODEC =
        StreamCodec.of(
            (buf, p) -> buf.writeVarInt(p.entityId),
            buf -> new C2SThrowHitMobPayload(buf.readVarInt()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
      return TYPE;
    }
  }

  public record S2CPackSyncPayload(
      String equipItemId,
      int equipCount,
      List<String> slotItemIds,
      List<Integer> slotCounts,
      int flags)
      implements CustomPacketPayload {
    public static final Type<S2CPackSyncPayload> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath("qb2", "s2c_pack_sync"));

    public static final StreamCodec<FriendlyByteBuf, S2CPackSyncPayload> CODEC =
        StreamCodec.of(
            (buf, p) -> {
              buf.writeUtf(p.equipItemId == null ? "" : p.equipItemId, 64);
              buf.writeVarInt(p.equipCount);
              int n = Math.min(6, p.slotItemIds == null ? 0 : p.slotItemIds.size());
              buf.writeVarInt(n);
              for (int i = 0; i < n; i++) {
                String id = p.slotItemIds.get(i);
                int cnt = (p.slotCounts != null && i < p.slotCounts.size()) ? p.slotCounts.get(i) : 0;
                buf.writeUtf(id == null ? "" : id, 64);
                buf.writeVarInt(cnt);
              }
              buf.writeVarInt(p.flags);
            },
            buf -> {
              String eqId = buf.readUtf(64);
              int eqCnt = buf.readVarInt();
              int n = Math.clamp(buf.readVarInt(), 0, 6);
              List<String> ids = new ArrayList<>(6);
              List<Integer> cnts = new ArrayList<>(6);
              for (int i = 0; i < n; i++) {
                ids.add(buf.readUtf(64));
                cnts.add(buf.readVarInt());
              }
              while (ids.size() < 6) {
                ids.add("");
                cnts.add(0);
              }
              int fl = buf.readVarInt();
              return new S2CPackSyncPayload(eqId, eqCnt, ids, cnts, fl);
            });

    @Override
    public Type<? extends CustomPacketPayload> type() {
      return TYPE;
    }
  }

  public record S2CTaskVisualPayload(
      String taskType, double x, double y, double z, String itemId, int durationTicks)
      implements CustomPacketPayload {
    public static final Type<S2CTaskVisualPayload> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath("qb2", "s2c_task_visual"));

    public static final StreamCodec<FriendlyByteBuf, S2CTaskVisualPayload> CODEC =
        StreamCodec.of(
            (buf, p) -> {
              buf.writeUtf(p.taskType == null ? "" : p.taskType, 24);
              buf.writeDouble(p.x);
              buf.writeDouble(p.y);
              buf.writeDouble(p.z);
              buf.writeUtf(p.itemId == null ? "" : p.itemId, 64);
              buf.writeVarInt(p.durationTicks);
            },
            buf ->
                new S2CTaskVisualPayload(
                    buf.readUtf(24),
                    buf.readDouble(),
                    buf.readDouble(),
                    buf.readDouble(),
                    buf.readUtf(64),
                    buf.readVarInt()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
      return TYPE;
    }
  }

  private CubeyNetwork() {}

  public static void register(RegisterPayloadHandlersEvent event) {
    PayloadRegistrar registrar = event.registrar("1").optional();
    registrar.playToServer(
        C2STelemetryPayload.TYPE, C2STelemetryPayload.CODEC, CubeyNetwork::handleServer);
    registrar.playToClient(
        S2CPeerCubeyPayload.TYPE, S2CPeerCubeyPayload.CODEC, CubeyNetwork::handleClient);
    registrar.playToServer(
        C2SPeerInteractPayload.TYPE,
        C2SPeerInteractPayload.CODEC,
        CubeyNetwork::handlePeerInteractServer);
    registrar.playToClient(
        S2CPeerInteractPayload.TYPE,
        S2CPeerInteractPayload.CODEC,
        CubeyNetwork::handlePeerInteractClient);
    registrar.playToServer(
        C2SPackActionPayload.TYPE,
        C2SPackActionPayload.CODEC,
        CubeyNetwork::handlePackActionServer);
    registrar.playToServer(
        C2SPackSlotClickPayload.TYPE,
        C2SPackSlotClickPayload.CODEC,
        CubeyNetwork::handlePackSlotClickServer);
    registrar.playToServer(
        C2SThrowHitMobPayload.TYPE,
        C2SThrowHitMobPayload.CODEC,
        CubeyNetwork::handleThrowHitMobServer);
    registrar.playToClient(
        S2CPackSyncPayload.TYPE, S2CPackSyncPayload.CODEC, CubeyNetwork::handlePackSyncClient);
    registrar.playToClient(
        S2CTaskVisualPayload.TYPE,
        S2CTaskVisualPayload.CODEC,
        CubeyNetwork::handleTaskVisualClient);
  }

  private static void handleServer(C2STelemetryPayload payload, IPayloadContext ctx) {
    ctx.enqueueWork(
        () -> {
          if (!(ctx.player() instanceof ServerPlayer sender)) return;
          if (sender.isSpectator() || sender.isInvisible() || !sender.isAlive()) return;
          if (!VALIDATOR.allowRate(sender.getUUID(), System.nanoTime())) return;

          Point ownerPos = new Point(sender.getX(), sender.getY(), sender.getZ());
          var valid =
              CubeySyncValidator.validate(
                  ownerPos,
                  payload.x(),
                  payload.y(),
                  payload.z(),
                  payload.yaw(),
                  payload.pitch(),
                  payload.roll(),
                  payload.state(),
                  payload.hat(),
                  payload.speech());
          if (valid == null) return;

          var broadcast =
              new S2CPeerCubeyPayload(
                  sender.getUUID(),
                  sender.getGameProfile().getName(),
                  valid.x(),
                  valid.y(),
                  valid.z(),
                  valid.yaw(),
                  valid.pitch(),
                  valid.roll(),
                  valid.state(),
                  valid.hat(),
                  valid.publicSpeech());

          if (!(sender.level() instanceof ServerLevel level)) return;
          double maxDistSq =
              CubeySyncValidator.BROADCAST_RADIUS * CubeySyncValidator.BROADCAST_RADIUS;
          for (ServerPlayer peer : level.players()) {
            if (peer.getUUID().equals(sender.getUUID())) continue;
            if (peer.distanceToSqr(sender) > maxDistSq) continue;
            if (peer.connection.hasChannel(S2CPeerCubeyPayload.TYPE)) {
              PacketDistributor.sendToPlayer(peer, broadcast);
            }
          }
        });
  }

  private static void handlePeerInteractServer(
      C2SPeerInteractPayload payload, IPayloadContext ctx) {
    ctx.enqueueWork(
        () -> {
          if (!(ctx.player() instanceof ServerPlayer actor)) return;
          if (actor.isSpectator() || !actor.isAlive()) return;
          if (!INTERACT_VALIDATOR.allowRate(actor.getUUID(), System.nanoTime())) return;
          String action = CubeySyncValidator.sanitizePeerAction(payload.action());
          if (action.isEmpty()) return;
          if (actor.getUUID().equals(payload.targetOwnerId())) return;
          if (!(actor.level() instanceof ServerLevel level)) return;

          ServerPlayer targetOwner = level.getServer().getPlayerList().getPlayer(payload.targetOwnerId());
          if (targetOwner == null || targetOwner.level() != level) return;
          if (targetOwner.distanceToSqr(actor) > 28.0 * 28.0) return;

          String safeItem = CubeySyncValidator.sanitizeItemId(payload.itemId());
          var notify =
              new S2CPeerInteractPayload(
                  actor.getUUID(),
                  actor.getGameProfile().getName(),
                  targetOwner.getUUID(),
                  action,
                  safeItem);
          double maxDistSq =
              CubeySyncValidator.BROADCAST_RADIUS * CubeySyncValidator.BROADCAST_RADIUS;
          for (ServerPlayer peer : level.players()) {
            if (peer.distanceToSqr(targetOwner) > maxDistSq) continue;
            if (peer.connection.hasChannel(S2CPeerInteractPayload.TYPE)) {
              PacketDistributor.sendToPlayer(peer, notify);
            }
          }
        });
  }

  private static void handlePackActionServer(C2SPackActionPayload payload, IPayloadContext ctx) {
    ctx.enqueueWork(
        () -> {
          if (!(ctx.player() instanceof ServerPlayer player)) return;
          var handler = serverPackActionHandler;
          if (handler != null) handler.accept(player, payload);
        });
  }

  private static void handlePackSlotClickServer(
      C2SPackSlotClickPayload payload, IPayloadContext ctx) {
    ctx.enqueueWork(
        () -> {
          if (!(ctx.player() instanceof ServerPlayer player)) return;
          var handler = serverPackSlotClickHandler;
          if (handler != null) handler.accept(player, payload);
        });
  }

  private static void handleThrowHitMobServer(
      C2SThrowHitMobPayload payload, IPayloadContext ctx) {
    ctx.enqueueWork(
        () -> {
          if (!(ctx.player() instanceof ServerPlayer player)) return;
          var handler = serverThrowHitMobHandler;
          if (handler != null) handler.accept(player, payload);
        });
  }

  private static void handleClient(S2CPeerCubeyPayload payload, IPayloadContext ctx) {
    ctx.enqueueWork(
        () -> {
          var receiver = clientReceiver;
          if (receiver != null) receiver.accept(payload);
        });
  }

  private static void handlePeerInteractClient(
      S2CPeerInteractPayload payload, IPayloadContext ctx) {
    ctx.enqueueWork(
        () -> {
          var receiver = clientPeerInteractReceiver;
          if (receiver != null) receiver.accept(payload);
        });
  }

  private static void handlePackSyncClient(S2CPackSyncPayload payload, IPayloadContext ctx) {
    ctx.enqueueWork(
        () -> {
          var receiver = clientPackSyncReceiver;
          if (receiver != null) receiver.accept(payload);
        });
  }

  private static void handleTaskVisualClient(S2CTaskVisualPayload payload, IPayloadContext ctx) {
    ctx.enqueueWork(
        () -> {
          var receiver = clientTaskVisualReceiver;
          if (receiver != null) receiver.accept(payload);
        });
  }

  public static void sendPackSync(ServerPlayer player, S2CPackSyncPayload payload) {
    if (player != null && player.connection.hasChannel(S2CPackSyncPayload.TYPE)) {
      PacketDistributor.sendToPlayer(player, payload);
    }
  }

  public static void sendTaskVisual(ServerPlayer player, S2CTaskVisualPayload payload) {
    if (player != null && player.connection.hasChannel(S2CTaskVisualPayload.TYPE)) {
      PacketDistributor.sendToPlayer(player, payload);
    }
  }
}
