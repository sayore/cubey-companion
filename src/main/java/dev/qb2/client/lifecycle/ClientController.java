package dev.qb2.client.lifecycle;

import com.google.gson.Gson;
import dev.qb2.client.config.*;
import dev.qb2.cognition.planning.Mind;
import dev.qb2.companion.movement.Point;
import dev.qb2.memory.Episode;
import dev.qb2.perception.*;
import dev.qb2.perception.chat.ChatHistory;
import dev.qb2.rendering.cube.CompanionRenderer;
import dev.qb2.ui.screens.SettingsScreen;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import net.minecraft.client.*;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.*;
import net.neoforged.neoforge.common.NeoForge;
import org.lwjgl.glfw.GLFW;

public final class ClientController {
  public final Configuration configuration = new Configuration(FMLPaths.CONFIGDIR.get());
  public final Mind mind = new Mind(FMLPaths.CONFIGDIR.get().resolve("qb2-memory-outbox.json"));
  private final Minecraft mc = Minecraft.getInstance();
  private final KeyMapping settingsKey =
      new KeyMapping("key.qb2.settings", GLFW.GLFW_KEY_K, "key.categories.qb2");
  private final CompanionRenderer renderer = new CompanionRenderer();
  private final dev.qb2.diagnostics.ClientSmokeProbe smoke =
      new dev.qb2.diagnostics.ClientSmokeProbe();
  private final Deque<Trigger> triggers = new ArrayDeque<>();
  public KnowledgeIndex knowledge = new KnowledgeIndex();
  private dev.qb2.perception.inventory.InventorySensor.View lastInventory;
  private PlayerSituationSensor.Vitals lastVitals;
  private ClientLevel level;
  private LocalPlayer player;
  public CompanionSession session;
  private long epoch;
  private String scope = "";
  private int activePriority = 9;
  private Point lastPlayerPosition;
  private long lastFlushNanos;
  public String error = "", lastAction = "none";
  public int contextTokens;
  public long inferenceMillis;
  public List<Episode> recalled = List.of();
  public List<String> keywords = List.of();

  private record Trigger(String reason, int priority, String text) {}

  public ClientController(IEventBus modBus) {
    try {
      configuration.load();
    } catch (Exception e) {
      error = "Configuration could not be loaded; check qb2.json";
    }
    modBus.addListener((RegisterKeyMappingsEvent e) -> e.register(settingsKey));
    NeoForge.EVENT_BUS.addListener(this::tick);
    NeoForge.EVENT_BUS.addListener(this::receivedChat);
    NeoForge.EVENT_BUS.addListener(this::outgoingChat);
    NeoForge.EVENT_BUS.addListener(this::interaction);
    NeoForge.EVENT_BUS.addListener(
        (RenderLevelStageEvent e) -> {
          if (session != null && !configuration.settings().paused)
            renderer.render(e, session, configuration.settings());
        });
    NeoForge.EVENT_BUS.addListener(this::overlay);
    NeoForge.EVENT_BUS.addListener(
        (RecipesUpdatedEvent e) -> {
          knowledge.close();
          knowledge = new KnowledgeIndex();
        });
    NeoForge.EVENT_BUS.addListener(
        (net.neoforged.neoforge.event.GameShuttingDownEvent e) -> {
          mind.close();
          knowledge.close();
        });
  }

  private void reset() {
    knowledge.close();
    knowledge = new KnowledgeIndex();
    lastInventory = null;
    lastVitals = null;
    epoch++;
    mind.cancel(epoch);
    triggers.clear();
    session = null;
    recalled = List.of();
    keywords = List.of();
    lastAction = "none";
    lastPlayerPosition = null;
  }

  private void tick(ClientTickEvent.Post event) {
    smoke.tick(this);
    while (settingsKey.consumeClick()) mc.setScreen(new SettingsScreen(mc.screen, this));
    if (mc.level != level || mc.player != player) {
      reset();
      level = mc.level;
      player = mc.player;
      if (level != null && player != null) {
        session = new CompanionSession();
        scope = scope();
      }
    }
    if (session == null || configuration.settings().paused || mc.isPaused()) return;
    Point currentPosition = dev.qb2.perception.world.WorldSensor.point(player.position());
    if (lastPlayerPosition != null && lastPlayerPosition.distance(currentPosition) > 32) {
      reset();
      session = new CompanionSession();
    }
    lastPlayerPosition = currentPosition;
    var s = configuration.settings();
    session.tick(mc, s, mind.busy());
    knowledge.tick(mc, s.nerveEndpoint, configuration.nerveKey());
    if (session.tick % 20 == 0) {
      var vitals = PlayerSituationSensor.capture(player);
      for (String change : PlayerSituationSensor.changes(lastVitals, vitals))
        if (session.dedup.accept("condition:" + change, session.tick, 200))
          enqueue(new Trigger("player condition", 1, change));
      lastVitals = vitals;
      var inventory = dev.qb2.perception.inventory.InventorySensor.capture(mc);
      if (lastInventory != null && !lastInventory.equals(inventory)) {
        String summary = "Inventory/menu changed: " + inventory;
        enqueue(
            new Trigger(
                "inventory changed", 2, summary.substring(0, Math.min(1500, summary.length()))));
      }
      lastInventory = inventory;
    }
    if (s.particles && session.tick % 12 == 0) {
      Point p = session.motion.position();
      mc.level.addParticle(
          new net.minecraft.core.particles.DustParticleOptions(
              new org.joml.Vector3f(0.3f, 0.8f, 1f), 0.25f),
          p.x(),
          p.y() - 0.15,
          p.z(),
          0,
          -0.005,
          0);
    }
    if (session.tick % 10 == 0 && !session.sensor.changes().isEmpty())
      enqueue(new Trigger("world change", 1, String.join("; ", session.sensor.changes())));
    if (s.autonomous
        && session.tick - session.lastRequest >= s.inferenceSeconds * 20L
        && triggers.isEmpty()) enqueue(new Trigger("curiosity", 3, ""));
    if (!mind.busy() && !triggers.isEmpty() && session.tick - session.lastRequest >= 40) {
      Trigger trigger =
          triggers.stream().min(Comparator.comparingInt(Trigger::priority)).orElseThrow();
      triggers.remove(trigger);
      request(trigger);
    } else if (!mind.busy() && System.nanoTime() - lastFlushNanos > 20_000_000_000L) {
      lastFlushNanos = System.nanoTime();
      activePriority = 9;
      mind.flush(s, configuration.nerveKey());
    }
  }

  private void enqueue(Trigger trigger) {
    if (session == null || configuration.settings().paused) return;
    if (trigger.priority() > 0
        && triggers.stream().anyMatch(t -> t.reason().equals(trigger.reason()))) return;
    if (triggers.size() >= 8) {
      var worst = triggers.stream().max(Comparator.comparingInt(Trigger::priority)).orElseThrow();
      if (worst.priority() < trigger.priority()) return;
      triggers.remove(worst);
    }
    if (trigger.priority() == 0 && mind.busy() && activePriority > 0) {
      epoch++;
      mind.cancel(epoch);
    }
    triggers.addLast(trigger);
  }

  private void receivedChat(ClientChatReceivedEvent event) {
    if (session == null || event.isCanceled()) return;
    String raw = event.getMessage().getString();
    String text = raw.substring(0, Math.min(1000, raw.length()));
    if (text.contains("[QB2]")) return;
    var source =
        event instanceof ClientChatReceivedEvent.Player
            ? ChatHistory.Source.PLAYER
            : ChatHistory.Source.SERVER;
    chat(source, text);
  }

  private void outgoingChat(ClientChatEvent event) {
    if (session == null || event.getMessage().startsWith("/")) return;
    String text = event.getMessage();
    if (ChatHistory.addressed(text, configuration.settings().triggers)) {
      event.setCanceled(true);
      mc.gui.getChat().addMessage(Component.literal("[You → QB2] " + text));
      chat(ChatHistory.Source.PLAYER, text);
    }
  }

  private void chat(ChatHistory.Source source, String text) {
    if (!session.dedup.accept("chat:" + text, session.tick, 40)) return;
    session.chat.add(source, text, session.tick);
    if (ChatHistory.addressed(text, configuration.settings().triggers))
      enqueue(new Trigger("direct address", 0, text));
    else if (configuration.settings().autonomous
        && session.tick - session.lastSpeech > 1200
        && session.attention.target() != null
        && text.toLowerCase(Locale.ROOT)
            .contains(session.attention.target().name().toLowerCase(Locale.ROOT)))
      enqueue(new Trigger("relevant conversation", 2, text));
  }

  private void interaction(InputEvent.InteractionKeyMappingTriggered event) {
    if (session == null || !(mc.hitResult instanceof BlockHitResult hit) || event.isCanceled())
      return;
    String id =
        BuiltInRegistries.BLOCK
            .getKey(mc.level.getBlockState(hit.getBlockPos()).getBlock())
            .toString();
    String text =
        "Player attempted "
            + (event.isAttack() ? "break" : "interact")
            + " "
            + id
            + " at "
            + hit.getBlockPos().toShortString();
    if (session.dedup.accept(text, session.tick, 80)) enqueue(new Trigger("interaction", 1, text));
  }

  private void request(Trigger trigger) {
    session.lastRequest = session.tick;
    activePriority = trigger.priority();
    var s = new Gson().fromJson(new Gson().toJson(configuration.settings()), Settings.class);
    var snapshot =
        InferenceSnapshot.capture(
            mc,
            session,
            scope,
            trigger.reason(),
            trigger.text(),
            trigger.priority() == 0,
            s.exploreRadius,
            knowledge.scope,
            s.autonomous);
    keywords = snapshot.keywords();
    var job =
        new Mind.Job(
            epoch,
            scope,
            trigger.reason(),
            s,
            configuration.apiKey(),
            configuration.nerveKey(),
            snapshot.context(),
            keywords,
            snapshot.targets(),
            snapshot.events());
    mind.submit(job, outcome -> mc.execute(() -> apply(outcome)));
  }

  private void apply(Mind.Outcome outcome) {
    if (session == null || outcome.epoch() != epoch || configuration.settings().paused) return;
    error = outcome.error();
    contextTokens = outcome.tokens();
    inferenceMillis = outcome.millis();
    recalled = outcome.memories();
    if (outcome.decision() == null) {
      if (activePriority == 0 && !error.isBlank())
        mc.gui.getChat().addMessage(Component.literal("[QB2 status] " + error));
      return;
    }
    var d = outcome.decision();
    if (Set.of("inspect", "approach", "go_to", "orbit").contains(d.action())
        && session.observations.stream().noneMatch(o -> o.key().equals(d.target()))) {
      error = "Decision expired: target no longer visible";
      return;
    }
    session.apply(d);
    lastAction = d.action();
    if (!d.speech().isBlank()) {
      String speech = d.speech().replace('\n', ' ');
      session.chat.add(ChatHistory.Source.QB2, speech, session.tick);
      mc.gui.getChat().addMessage(Component.literal("[QB2] " + speech));
      if (configuration.settings().serverChat && player.connection != null)
        player.connection.sendChat("[QB2] " + speech.substring(0, Math.min(240, speech.length())));
    }
    if (d.action().equals("recall")) enqueue(new Trigger("memory reflection", 2, ""));
  }

  private String scope() {
    String world =
        mc.getCurrentServer() != null
            ? mc.getCurrentServer().ip
            : mc.getSingleplayerServer() != null
                ? mc.getSingleplayerServer()
                    .getWorldPath(LevelResource.ROOT)
                    .toAbsolutePath()
                    .normalize()
                    .toString()
                : "local";
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest((world + "|" + player.getUUID()).getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 required", e);
    }
  }

  public void reconfigure() {
    reset();
    level = null;
    player = null;
    error = "";
  }

  public void testConnection() {
    if (session != null)
      enqueue(new Trigger("direct address", 0, "Reply briefly to confirm the connection."));
    else error = "Join a world to test perception, memory and inference together";
  }

  private void overlay(RenderGuiEvent.Post event) {
    if (!configuration.settings().debug || session == null) return;
    var lines =
        List.of(
            "QB2 · "
                + session.state
                + " · "
                + (configuration.settings().paused ? "PAUSED" : mind.status),
            "Target: "
                + (session.attention.target() == null ? "none" : session.attention.target().key()),
            "Seen: " + session.observations.size() + " | action: " + lastAction,
            "Keywords: " + String.join(", ", keywords),
            "NERVE: "
                + mind.memoryStatus
                + " · hits "
                + recalled.stream()
                    .limit(3)
                    .map(e -> e.kind() + ":" + e.id().substring(0, 8))
                    .collect(java.util.stream.Collectors.joining(","))
                + " · pending "
                + mind.pending,
            "Context ~"
                + contextTokens
                + " tokens · "
                + inferenceMillis
                + "ms · requests "
                + triggers.size(),
            "Knowledge: " + knowledge.status + " · recalled " + mind.knowledgeHits,
            "Error: " + error);
    int y = 8;
    for (String line : lines) {
      String clipped =
          mc.font.plainSubstrByWidth(line, Math.max(100, mc.getWindow().getGuiScaledWidth() - 16));
      event.getGuiGraphics().drawString(mc.font, clipped, 8, y, 0xC8EFFF);
      y += 11;
    }
  }
}
