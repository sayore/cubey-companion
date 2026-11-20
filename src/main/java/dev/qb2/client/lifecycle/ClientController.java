package dev.qb2.client.lifecycle;

import dev.qb2.client.config.*;
import dev.qb2.cognition.planning.Mind;
import dev.qb2.companion.movement.Point;
import dev.qb2.memory.Episode;
import dev.qb2.perception.*;
import dev.qb2.perception.chat.ChatHistory;
import dev.qb2.rendering.cube.CompanionRenderer;
import dev.qb2.ui.overlay.DebugOverlay;
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
  private final KeyMapping pauseKey =
      new KeyMapping("key.qb2.pause", GLFW.GLFW_KEY_UNKNOWN, "key.categories.qb2");
  private final KeyMapping summonKey =
      new KeyMapping("key.qb2.summon", GLFW.GLFW_KEY_UNKNOWN, "key.categories.qb2");
  private final KeyMapping lookHereKey =
      new KeyMapping("key.qb2.look_here", GLFW.GLFW_KEY_UNKNOWN, "key.categories.qb2");
  private final CompanionRenderer renderer = new CompanionRenderer();
  private final dev.qb2.rendering.audio.CompanionAudio audio =
      new dev.qb2.rendering.audio.CompanionAudio();
  private final dev.qb2.perception.inventory.ContainerMemory containerMemory =
      new dev.qb2.perception.inventory.ContainerMemory();
  private final dev.qb2.memory.SessionSummarizer summarizer =
      new dev.qb2.memory.SessionSummarizer();
  private final dev.qb2.cognition.planning.AmbientBuffer ambientBuffer =
      new dev.qb2.cognition.planning.AmbientBuffer();
  private final List<Episode> pendingBackgroundEpisodes = new ArrayList<>();
  private final MemoryInspectorClient inspectorClient = new MemoryInspectorClient();
  private Point lastInteractedBlock;
  private Point lastDeathPoint;
  private boolean deathRecorded;
  private long lastDirectConvoTick = -10000;
  private String lastDirectPrompt = "";
  private boolean wasPlayerIdle;
  private long lastAmbientFallbackTick = -1000;
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
    modBus.addListener(
        (RegisterKeyMappingsEvent e) -> {
          e.register(settingsKey);
          e.register(pauseKey);
          e.register(summonKey);
          e.register(lookHereKey);
        });
    NeoForge.EVENT_BUS.addListener(this::tick);
    NeoForge.EVENT_BUS.addListener(this::receivedChat);
    NeoForge.EVENT_BUS.addListener(this::outgoingChat);
    NeoForge.EVENT_BUS.addListener(this::interaction);
    NeoForge.EVENT_BUS.addListener(
        (RenderLevelStageEvent e) -> {
          if (session != null && !configuration.settings().paused)
            renderer.render(e, session, configuration.settings());
        });
    NeoForge.EVENT_BUS.addListener((RenderGuiEvent.Post e) -> DebugOverlay.render(e, this));
    NeoForge.EVENT_BUS.addListener(
        (RegisterClientCommandsEvent e) -> QB2ClientCommands.register(e, this));
    NeoForge.EVENT_BUS.addListener(
        (RecipesUpdatedEvent e) -> {
          knowledge.close();
          knowledge = new KnowledgeIndex();
        });
    NeoForge.EVENT_BUS.addListener(
        (net.neoforged.neoforge.event.GameShuttingDownEvent e) -> {
          mind.close();
          knowledge.close();
          inspectorClient.close();
        });
  }

  private void reset() {
    knowledge.close();
    knowledge = new KnowledgeIndex();
    lastInventory = null;
    lastVitals = null;
    lastInteractedBlock = null;
    containerMemory.reset();
    summarizer.reset();
    ambientBuffer.clear();
    wasPlayerIdle = false;
    pendingBackgroundEpisodes.clear();
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
    while (pauseKey.consumeClick()) setPaused(!configuration.settings().paused);
    while (summonKey.consumeClick()) summonCompanion();
    while (lookHereKey.consumeClick()) lookHereCompanion();
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
    if (lastPlayerPosition != null && lastPlayerPosition.distance(currentPosition) > 96) {
      reset();
      session = new CompanionSession();
    }
    lastPlayerPosition = currentPosition;
    var s = configuration.settings();
    if (player.isDeadOrDying()) {
      if (!deathRecorded) {
        deathRecorded = true;
        lastDeathPoint = currentPosition;
        int dx = (int) Math.floor(currentPosition.x());
        int dy = (int) Math.floor(currentPosition.y());
        int dz = (int) Math.floor(currentPosition.z());
        queueBackgroundEpisode(
            Episode.createAt(
                scope,
                "death_location",
                List.of("death", "grave", "tod", "gestorben", "items"),
                "Player died at " + dx + "," + dy + "," + dz,
                currentPosition));
      }
    } else if (player.getHealth() > 1.0f) {
      deathRecorded = false;
    }
    session.apiBackoff = mind.inCooldown();
    session.tick(mc, s, mind.busy());
    if (session.situational.triggerGreetingSound) {
      audio.play(mc, s, session.motion.position(), dev.qb2.rendering.audio.CompanionAudio.Cue.DISCOVERY);
    } else if (session.situational.triggerCelebrateSound) {
      audio.play(mc, s, session.motion.position(), dev.qb2.rendering.audio.CompanionAudio.Cue.SUMMON);
    }
    boolean idleNow = session.playerIdle();
    if (wasPlayerIdle && !idleNow && ambientBuffer.hasDeferred()) {
      deliverDeferredAmbient(s);
    }
    wasPlayerIdle = idleNow;
    if (mind.inCooldown()
        && s.autonomous
        && !idleNow
        && session.tick - lastAmbientFallbackTick >= 300
        && !session.observations.isEmpty()) {
      lastAmbientFallbackTick = session.tick;
      var obs = session.observations.get(0);
      session.focusOn(obs);
      ambientBuffer
          .consumePhrase(obs.registryId())
          .ifPresent(
              phrase -> {
                if (s.thoughts) session.bubbles.thought(phrase, session.tick);
              });
    }
    knowledge.tick(mc, s.nerveEndpoint, configuration.nerveKey());
    if (session.tick % 20 == 0) {
      var vitals = PlayerSituationSensor.capture(player);
      for (String change : PlayerSituationSensor.changes(lastVitals, vitals))
        if (session.dedup.accept("condition:" + change, session.tick, 200))
          enqueue(new Trigger("player condition", 1, change));
      lastVitals = vitals;
      var inventory = dev.qb2.perception.inventory.InventorySensor.capture(mc);
      if (!inventory.container().isEmpty()) {
        Point pos =
            lastInteractedBlock != null
                ? lastInteractedBlock
                : dev.qb2.perception.world.WorldSensor.point(player.position());
        containerMemory.record(scope, pos, inventory).ifPresent(this::queueBackgroundEpisode);
      }
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
    if (session.tick % 10 == 0 && !session.sensor.changes().isEmpty()) {
      String worldChanges = String.join("; ", session.sensor.changes());
      summarizer.record(scope, "world_change", worldChanges).ifPresent(this::queueBackgroundEpisode);
      enqueue(new Trigger("world change", 1, worldChanges));
    }
    if (s.autonomous
        && session.tick - session.lastRequest >= s.inferenceSeconds * 20L
        && triggers.isEmpty()) enqueue(new Trigger("curiosity", 3, ""));
    if (!mind.busy() && !triggers.isEmpty()) {
      Trigger trigger =
          triggers.stream().min(Comparator.comparingInt(Trigger::priority)).orElseThrow();
      boolean urgent = trigger.priority() == 0;
      if (urgent || (!mind.inCooldown() && session.tick - session.lastRequest >= 40)) {
        triggers.remove(trigger);
        request(trigger);
      }
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
    boolean explicit = ChatHistory.addressed(text, configuration.settings().triggers);
    boolean activeFollowUp =
        !explicit
            && session.tick - lastDirectConvoTick <= 300
            && (mc.getSingleplayerServer() != null
                || (!configuration.settings().serverChat && text.trim().endsWith("?")));
    if (explicit || activeFollowUp) {
      event.setCanceled(true);
      lastDirectConvoTick = session.tick;
      lastDirectPrompt = text;
      mc.gui.getChat().addMessage(Component.literal("[You → QB2] " + text));
      if (activeFollowUp) {
        session.wakeUp();
        session.markPlayerActive();
        session.chat.add(ChatHistory.Source.PLAYER, text, session.tick);
        enqueue(new Trigger("direct address", 0, text));
      } else {
        chat(ChatHistory.Source.PLAYER, text);
      }
    }
  }

  private void chat(ChatHistory.Source source, String text) {
    if (!session.dedup.accept("chat:" + text, session.tick, 40)) return;
    if (source == ChatHistory.Source.PLAYER) session.markPlayerActive();
    session.chat.add(source, text, session.tick);
    if (ChatHistory.addressed(text, configuration.settings().triggers)) {
      session.wakeUp();
      enqueue(new Trigger("direct address", 0, text));
    }
    else if (configuration.settings().autonomous
        && session.tick - session.lastSpeech > 1200
        && session.attention.target() != null
        && text.toLowerCase(Locale.ROOT)
            .contains(session.attention.target().name().toLowerCase(Locale.ROOT)))
      enqueue(new Trigger("relevant conversation", 2, text));
  }

  private void interaction(InputEvent.InteractionKeyMappingTriggered event) {
    if (session == null || mc.player == null || mc.level == null || event.isCanceled()) return;
    session.markPlayerActive();
    // 1. Direct right-click interaction on Cubey's 0.26-radius hitbox
    if (!event.isAttack() && clickedOnCompanion()) {
      event.setCanceled(true);
      event.setSwingHand(true);
      if (mc.player.isCrouching()) {
        if (session.perched) wakeCompanion();
        else sitCompanion();
        return;
      }
      var held = mc.player.getMainHandItem();
      if (held.isEmpty()) {
        session.wakeUp();
        session.situational.celebrateTicks = 30;
        session.situational.greetingTicks = 25;
        session.bubbles.thought("♥", session.tick);
        audio.play(
            mc,
            configuration.settings(),
            session.motion.position(),
            dev.qb2.rendering.audio.CompanionAudio.Cue.DISCOVERY);
      } else {
        session.wakeUp();
        String itemName = held.getHoverName().getString();
        String itemId = BuiltInRegistries.ITEM.getKey(held.getItem()).toString();
        lastDirectConvoTick = session.tick;
        lastDirectPrompt = itemName;
        mc.gui.getChat().addMessage(Component.literal("[You → QB2] *shows " + itemName + "*"));
        enqueue(
            new Trigger(
                "direct address",
                0,
                "The player is showing you the item in their hand: "
                    + itemName
                    + " ("
                    + itemId
                    + "). Use lookup_recipe or your modpack knowledge to explain what it does and how it is crafted or used."));
      }
      return;
    }
    session.situational.markWorking();
    if (!(mc.hitResult instanceof BlockHitResult hit)) return;
    if (!event.isAttack()) {
      var bp = hit.getBlockPos();
      lastInteractedBlock = new Point(bp.getX() + 0.5, bp.getY() + 0.5, bp.getZ() + 0.5);
    }
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
    var s = configuration.settings().copy();
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
            s.autonomous,
            s.speechSeconds);
    keywords = snapshot.keywords();
    var allEvents = new ArrayList<Episode>(snapshot.events());
    if (!pendingBackgroundEpisodes.isEmpty()) {
      allEvents.addAll(pendingBackgroundEpisodes);
      pendingBackgroundEpisodes.clear();
    }
    var contextMap = new LinkedHashMap<>(snapshot.context());
    if (s.style != null && !s.style.isBlank()) contextMap.put("style", s.style);
    var job =
        new Mind.Job(
            epoch,
            scope,
            trigger.reason(),
            s,
            configuration.apiKey(),
            configuration.nerveKey(),
            contextMap,
            keywords,
            snapshot.targets(),
            List.copyOf(allEvents),
            configuration.fallbackApiKey(),
            snapshot.tools());
    mind.submit(job, outcome -> mc.execute(() -> apply(outcome)));
  }

  private void queueBackgroundEpisode(Episode episode) {
    if (pendingBackgroundEpisodes.size() >= 16) pendingBackgroundEpisodes.remove(0);
    pendingBackgroundEpisodes.add(episode);
  }

  private void handleLookHere() {
    if (mc.level == null || mc.player == null || mc.hitResult == null) return;
    Observation focused = null;
    if (mc.hitResult instanceof BlockHitResult hit) {
      var pos = hit.getBlockPos();
      var state = mc.level.getBlockState(pos);
      if (!state.isAir()) {
        String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        String key = id + "@" + pos.getX() + "," + pos.getY() + "," + pos.getZ();
        String name = state.getBlock().getName().getString();
        String stateText = state.toString();
        var be = mc.level.getBlockEntity(pos);
        if (be != null) {
          stateText +=
              " clientBlockEntity="
                  + BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(be.getType())
                  + dev.qb2.perception.world.ModpackInspector.describeClientCapabilities(
                      mc.level, pos, state);
        }
        focused =
            new Observation(
                key,
                id,
                name,
                stateText,
                new Point(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5),
                10.0,
                session.tick);
      }
    } else if (mc.hitResult instanceof net.minecraft.world.phys.EntityHitResult hit) {
      var e = hit.getEntity();
      String id = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString();
      String key = id + "@" + e.getId();
      focused =
          new Observation(
              key,
              id,
              e.getName().getString(),
              "player pointed entity",
              dev.qb2.perception.world.WorldSensor.point(e.getBoundingBox().getCenter()),
              10.0,
              session.tick);
    }
    if (focused == null) return;
    var updated = new ArrayList<>(session.observations);
    Observation target = focused;
    updated.removeIf(o -> o.key().equals(target.key()));
    updated.add(0, focused);
    session.observations = List.copyOf(updated);
    session.focusOn(focused);
    audio.play(
        mc,
        configuration.settings(),
        session.motion.position(),
        dev.qb2.rendering.audio.CompanionAudio.Cue.LOOK_HERE);
    mc.gui.getChat().addMessage(Component.translatable("msg.qb2.look_here", focused.name()));
    String nearbyContraption = "";
    if (mc.hitResult instanceof BlockHitResult hit) {
      nearbyContraption = scanContraptionNeighbors(hit.getBlockPos());
    }
    enqueue(
        new Trigger(
            "direct address",
            0,
            "The player is pointing directly at "
                + focused.name()
                + " ("
                + focused.registryId()
                + ", state: "
                + focused.state()
                + ")"
                + (nearbyContraption.isBlank() ? "" : ". Nearby connected/surrounding blocks: " + nearbyContraption)
                + ". Explain what this block/machine does, how it fits with the surrounding setup/contraption, and share useful facts — never just repeat its name."));
  }

  private String scanContraptionNeighbors(net.minecraft.core.BlockPos center) {
    if (mc.level == null) return "";
    var counts = new LinkedHashMap<String, Integer>();
    for (int dx = -2; dx <= 2; dx++) {
      for (int dy = -1; dy <= 2; dy++) {
        for (int dz = -2; dz <= 2; dz++) {
          if (dx == 0 && dy == 0 && dz == 0) continue;
          var pos = center.offset(dx, dy, dz);
          if (!mc.level.hasChunkAt(pos)) continue;
          var state = mc.level.getBlockState(pos);
          if (state.isAir()) continue;
          String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
          if (id.equals("minecraft:grass_block")
              || id.equals("minecraft:dirt")
              || id.equals("minecraft:stone")
              || id.equals("minecraft:deepslate")
              || id.equals("minecraft:short_grass")
              || id.equals("minecraft:tall_grass")) continue;
          String label = state.getBlock().getName().getString() + " (" + id + ")";
          counts.merge(label, 1, Integer::sum);
          if (counts.size() >= 14) break;
        }
      }
    }
    if (counts.isEmpty()) return "";
    return counts.entrySet().stream()
        .map(e -> e.getValue() > 1 ? e.getValue() + "x " + e.getKey() : e.getKey())
        .collect(java.util.stream.Collectors.joining(", "));
  }

  public void setPaused(boolean paused) {
    configuration.settings().paused = paused;
    mc.gui.getChat().addMessage(Component.translatable(paused ? "msg.qb2.paused" : "msg.qb2.resumed"));
  }

  public void summonCompanion() {
    if (session == null || configuration.settings().paused) return;
    session.summon();
    audio.play(
        mc,
        configuration.settings(),
        session.motion.position(),
        dev.qb2.rendering.audio.CompanionAudio.Cue.SUMMON);
    mc.gui.getChat().addMessage(Component.translatable("msg.qb2.summoned"));
  }

  public void lookHereCompanion() {
    if (session == null || configuration.settings().paused) return;
    handleLookHere();
  }

  public void sitCompanion() {
    if (session == null || mc.level == null || configuration.settings().paused) return;
    CompanionSession.PerchPlacement placement = null;
    if (mc.hitResult instanceof BlockHitResult hit) {
      var pos = hit.getBlockPos();
      var below = mc.level.getBlockState(pos);
      if (!below.isAir()
          && !below.getCollisionShape(mc.level, pos).isEmpty()
          && mc.level.getBlockState(pos.above()).isAir()) {
        double topY =
            pos.getY()
                + below.getCollisionShape(mc.level, pos).max(net.minecraft.core.Direction.Axis.Y);
        // Sit near the edge facing the player so Cubey can watch the room!
        Point center = new Point(pos.getX() + 0.5, topY + 0.125, pos.getZ() + 0.5);
        Point toPlayer =
            new Point(mc.player.getX() - center.x(), 0, mc.player.getZ() - center.z());
        Point dir = toPlayer.length() > 0.1 ? toPlayer.unit() : new Point(0, 0, 1);
        Point edgeSeat =
            new Point(
                center.x() + Math.clamp(dir.x() * 0.26, -0.30, 0.30),
                center.y(),
                center.z() + Math.clamp(dir.z() * 0.26, -0.30, 0.30));
        placement = new CompanionSession.PerchPlacement(edgeSeat, edgeSeat.add(dir.scale(3.0)));
      }
    }
    if (placement == null) placement = CompanionSession.findPerchSpot(mc, session.motion.position());
    if (placement != null) {
      session.perchAt(placement);
      mc.gui.getChat().addMessage(Component.translatable("msg.qb2.nap"));
    }
  }

  public void wakeCompanion() {
    if (session == null) return;
    session.wakeUp();
    mc.gui.getChat().addMessage(Component.translatable("msg.qb2.wake"));
  }

  public void guideToDeath() {
    if (session == null) return;
    if (lastDeathPoint != null) {
      session.startGuiding(lastDeathPoint, "Death location");
      mc.gui
          .getChat()
          .addMessage(
              Component.literal(
                  "[QB2] Guiding you to your last death spot ("
                      + (int) Math.floor(lastDeathPoint.x())
                      + ", "
                      + (int) Math.floor(lastDeathPoint.y())
                      + ", "
                      + (int) Math.floor(lastDeathPoint.z())
                      + "). Follow my beam!"));
      return;
    }
    searchMemoriesAsync(
        "death grave gestorben",
        results -> {
          var hit = results.stream().filter(Episode::hasPosition).findFirst();
          if (hit.isPresent() && session != null) {
            var ep = hit.get();
            session.startGuiding(new Point(ep.x(), ep.y(), ep.z()), "Death location");
            mc.gui.getChat().addMessage(Component.literal("[QB2] Guiding you to your death spot: " + ep.text()));
          } else {
            mc.gui.getChat().addMessage(Component.literal("[QB2] No recorded death location found."));
          }
        });
  }

  public void guideToItemAsync(String query) {
    if (session == null) return;
    searchMemoriesAsync(
        query,
        results -> {
          var hit = results.stream().filter(Episode::hasPosition).findFirst();
          if (hit.isPresent() && session != null) {
            var ep = hit.get();
            session.startGuiding(new Point(ep.x(), ep.y(), ep.z()), query);
            mc.gui
                .getChat()
                .addMessage(
                    Component.literal(
                        "[QB2] Guiding you to "
                            + (int) Math.floor(ep.x())
                            + ", "
                            + (int) Math.floor(ep.y())
                            + ", "
                            + (int) Math.floor(ep.z())
                            + " ("
                            + ep.text()
                            + ")"));
          } else {
            mc.gui
                .getChat()
                .addMessage(
                    Component.literal(
                        "[QB2] No container memory with coordinates found for '" + query + "'."));
          }
        });
  }

  private boolean clickedOnCompanion() {
    if (session == null || mc.player == null) return false;
    var eye = mc.player.getEyePosition();
    var look = mc.player.getLookAngle();
    double reach = 4.5;
    if (mc.hitResult instanceof BlockHitResult bhr
        && mc.hitResult.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK) {
      reach = Math.min(reach, bhr.getLocation().distanceTo(eye));
    }
    var end = eye.add(look.scale(reach));
    Point cp = session.motion.position();
    var box =
        new net.minecraft.world.phys.AABB(
            cp.x() - 0.26, cp.y() - 0.26, cp.z() - 0.26, cp.x() + 0.26, cp.y() + 0.26, cp.z() + 0.26);
    return box.clip(eye, end).isPresent();
  }

  public int pendingTriggerCount() {
    return triggers.size();
  }

  public void searchMemoriesAsync(String query, java.util.function.Consumer<List<Episode>> cb) {
    inspectorClient.searchAsync(
        mc, configuration.settings(), configuration.nerveKey(), scope, query, cb);
  }

  public void deleteMemoryAsync(String id, java.util.function.Consumer<Boolean> cb) {
    inspectorClient.deleteAsync(
        mc, configuration.settings(), configuration.nerveKey(), scope, id, cb);
  }

  private void apply(Mind.Outcome outcome) {
    if (session == null || outcome.epoch() != epoch || configuration.settings().paused) return;
    error = outcome.error();
    contextTokens = outcome.tokens();
    inferenceMillis = outcome.millis();
    recalled = outcome.memories();
    if (outcome.decision() == null) {
      session.errorTintTicks = 120;
      if (mind.inCooldown())
        audio.play(
            mc,
            configuration.settings(),
            session.motion.position(),
            dev.qb2.rendering.audio.CompanionAudio.Cue.BACKOFF);
      if (!error.isBlank()) {
        org.slf4j.LoggerFactory.getLogger("qb2").warn("QB2 inference failed: {}", error);
      }
      return;
    }
    session.errorTintTicks = 0;
    var d = outcome.decision();
    if (Set.of("inspect", "approach", "go_to", "orbit").contains(d.action())
        && session.observations.stream().noneMatch(o -> o.key().equals(d.target()))) {
      error = "Decision expired: target no longer visible";
      return;
    }
    String targetRegistry =
        session.observations.stream()
            .filter(o -> o.key().equals(d.target()))
            .map(Observation::registryId)
            .findFirst()
            .orElse(
                session.attention.target() == null
                    ? "general"
                    : session.attention.target().registryId());
    if (!d.thought().isBlank()) ambientBuffer.storePhrase(targetRegistry, d.thought());

    boolean deferSpeech = activePriority > 0 && session.playerIdle() && !d.speech().isBlank();
    if (deferSpeech) {
      ambientBuffer.deferForActiveReturn(d.speech(), d.thought());
      var silent = new dev.qb2.cognition.decisions.Decision(
          d.action(), d.target(), d.thought(), "", d.memory());
      session.apply(silent);
      lastAction = silent.action();
      if (!silent.thought().isBlank())
        summarizer.record(scope, "thought", silent.thought()).ifPresent(this::queueBackgroundEpisode);
      return;
    }

    if (activePriority == 0) {
      lastDirectConvoTick = session.tick;
      String lowerPrompt = lastDirectPrompt.toLowerCase(Locale.ROOT);
      if (lowerPrompt.matches("(?s).*(wo |where |find|kiste|chest|truhe|lager|tod|died|grave|grab).*")) {
        recalled.stream()
            .filter(Episode::hasPosition)
            .findFirst()
            .ifPresent(
                ep ->
                    session.startGuiding(
                        new Point(ep.x(), ep.y(), ep.z()),
                        ep.kind().equals("death_location") ? "Death spot" : "Stored item"));
      }
    }
    session.apply(d);
    lastAction = d.action();
    if (!d.thought().isBlank())
      summarizer.record(scope, "thought", d.thought()).ifPresent(this::queueBackgroundEpisode);
    if (Set.of("inspect", "go_to").contains(d.action()))
      audio.play(
          mc,
          configuration.settings(),
          session.motion.position(),
          dev.qb2.rendering.audio.CompanionAudio.Cue.DISCOVERY);
    if (!d.speech().isBlank()) {
      speakLocally(d.speech());
    }
    if (d.action().equals("recall")) enqueue(new Trigger("memory reflection", 2, ""));
  }

  private void deliverDeferredAmbient(Settings s) {
    ambientBuffer
        .pollDeferredThought()
        .ifPresent(
            t -> {
              if (s.thoughts) session.bubbles.thought(t, session.tick);
            });
    ambientBuffer
        .pollDeferredSpeech()
        .ifPresent(
            speech -> {
              if (s.speech) {
                session.bubbles.speech(speech, session.tick);
                session.lastSpeech = session.tick;
                speakLocally(speech);
              }
            });
  }

  private void speakLocally(String rawSpeech) {
    String speech = rawSpeech.replace('\n', ' ');
    session.chat.add(ChatHistory.Source.QB2, speech, session.tick);
    summarizer.record(scope, "speech", speech).ifPresent(this::queueBackgroundEpisode);
    audio.play(
        mc,
        configuration.settings(),
        session.motion.position(),
        dev.qb2.rendering.audio.CompanionAudio.Cue.SPEECH);
    mc.gui.getChat().addMessage(Component.literal("[QB2] " + speech));
    if (configuration.settings().serverChat && player.connection != null)
      player.connection.sendChat("[QB2] " + speech.substring(0, Math.min(240, speech.length())));
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
}
