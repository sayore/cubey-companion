package dev.qb2.network;

import dev.qb2.companion.movement.Point;
import java.util.UUID;
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
 * Optional NeoForge 1.21.1 payload channel for syncing Cubey visual telemetry between players when
 * both client and server have QB2 installed. Because the registrar is marked {@code optional()},
 * vanilla servers without QB2 and vanilla clients without QB2 remain 100% compatible.
 */
public final class CubeyNetwork {
  private static final CubeySyncValidator VALIDATOR = new CubeySyncValidator();
  public static volatile Consumer<S2CPeerCubeyPayload> clientReceiver;

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
              buf.writeUtf(p.hat == null ? "antenna" : p.hat, 24);
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
                    buf.readUtf(24),
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
              buf.writeUtf(p.hat == null ? "antenna" : p.hat, 24);
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
                    buf.readUtf(24),
                    buf.readUtf(140)));

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

  private static void handleClient(S2CPeerCubeyPayload payload, IPayloadContext ctx) {
    ctx.enqueueWork(
        () -> {
          var receiver = clientReceiver;
          if (receiver != null) receiver.accept(payload);
        });
  }
}
