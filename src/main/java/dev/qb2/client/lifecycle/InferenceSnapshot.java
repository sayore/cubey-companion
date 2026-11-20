package dev.qb2.client.lifecycle;

import dev.qb2.memory.Episode;
import dev.qb2.perception.Keywords;
import dev.qb2.perception.Observation;
import java.util.*;
import net.minecraft.client.Minecraft;

/**
 * Captured exclusively on the client thread; no mutable world objects cross the worker boundary.
 */
public record InferenceSnapshot(
    Map<String, Object> context, List<String> keywords, Set<String> targets, List<Episode> events) {
  public static InferenceSnapshot capture(
      Minecraft mc,
      CompanionSession session,
      String scope,
      String reason,
      String text,
      boolean direct,
      double exploreRadius,
      String knowledgeScope,
      boolean autonomous) {
    return capture(
        mc, session, scope, reason, text, direct, exploreRadius, knowledgeScope, autonomous, 45);
  }

  public static InferenceSnapshot capture(
      Minecraft mc,
      CompanionSession session,
      String scope,
      String reason,
      String text,
      boolean direct,
      double exploreRadius,
      String knowledgeScope,
      boolean autonomous,
      int speechSeconds) {
    var level = Objects.requireNonNull(mc.level);
    var player = Objects.requireNonNull(mc.player);
    var context = new LinkedHashMap<String, Object>();
    context.put("knowledgeScope", knowledgeScope);
    context.put(
        "speechAllowed",
        direct || (autonomous && session.tick - session.lastSpeech >= speechSeconds * 20L));
    context.put("request", Map.of("reason", reason, "message", text, "action", session.action));

    // Attention-focused observations: keep focused target + top functional/entity blocks, compactly.
    var attentionTarget = session.attention.target();
    var selectedObs = new ArrayList<Observation>();
    if (attentionTarget != null) selectedObs.add(attentionTarget);
    session.observations.stream()
        .filter(o -> attentionTarget == null || !o.key().equals(attentionTarget.key()))
        .sorted(
            Comparator.comparingInt((Observation o) -> isMundaneTerrain(o.registryId()) ? 1 : 0)
                .thenComparingDouble(o -> -o.salience()))
        .limit(direct ? 8 : 5)
        .forEach(selectedObs::add);

    var compactObs =
        selectedObs.stream()
            .map(
                o -> {
                  var m = new LinkedHashMap<String, Object>();
                  m.put("key", o.key());
                  m.put("name", o.name());
                  if (o.state() != null
                      && (o.state().contains("clientBlockEntity=")
                          || o.state().contains("clientEnergy=")
                          || o.state().contains("clientFluid=")
                          || o.state().equals("visible entity")
                          || o.state().equals("player pointed entity"))) {
                    m.put("state", o.state());
                  }
                  return m;
                })
            .toList();

    context.put(
        "world",
        Map.of(
            "dimension",
            level.dimension().location().toString(),
            "observations",
            compactObs));
    if (attentionTarget != null) {
      context.put(
          "attention", Map.of("key", attentionTarget.key(), "name", attentionTarget.name()));
    }
    var recentChat = session.chat.snapshot();
    if (!recentChat.isEmpty()) {
      context.put(
          "chat",
          recentChat.subList(Math.max(0, recentChat.size() - (direct ? 5 : 2)), recentChat.size()));
    }
    if (!session.recentThoughts.isEmpty()) {
      var rt = List.copyOf(session.recentThoughts);
      context.put("recentThoughts", rt.subList(Math.max(0, rt.size() - 3), rt.size()));
    }
    if (!session.observationsHistory.isEmpty()) {
      var hist = List.copyOf(session.observationsHistory);
      context.put("history", hist.subList(Math.max(0, hist.size() - 3), hist.size()));
    }

    var inventory = dev.qb2.perception.inventory.InventorySensor.capture(mc);
    boolean needInventory =
        !inventory.container().isEmpty()
            || "inventory changed".equals(reason)
            || text.toLowerCase(Locale.ROOT)
                .matches(
                    "(?s).*(inventar|inventory|chest|kiste|truhe|rucksack|backpack|menu|item|items|craft|rezept|recipe|hast du|hab ich|habe ich).*");
    if (needInventory) {
      context.put("inventory", inventory);
    }
    var playerPosition = dev.qb2.perception.world.WorldSensor.point(player.position());
    var vitals = dev.qb2.perception.PlayerSituationSensor.capture(player);
    var playerMap = new LinkedHashMap<String, Object>();
    playerMap.put("position", playerPosition);
    if ("player condition".equals(reason) || vitals.health() < 16 || player.isOnFire() || player.isUnderWater()) {
      playerMap.put("condition", vitals);
    }
    context.put("player", playerMap);

    var words = new LinkedHashSet<String>();
    words.add(level.dimension().location().toString());
    words.add(reason);
    selectedObs.forEach(
        o -> {
          words.add(o.registryId());
          words.addAll(Keywords.extract(o.name()));
        });
    if (needInventory) words.addAll(inventory.keywords());
    words.addAll(Keywords.extract(text));
    var keywords = List.copyOf(words);
    var targets = new HashSet<String>();
    selectedObs.stream()
        .filter(
            o ->
                dev.qb2.companion.movement.FlightPlanner.hoverAbove(o).distance(playerPosition)
                    <= exploreRadius * 0.9)
        .forEach(o -> targets.add(o.key()));
    if (!targets.isEmpty()) {
      context.put(
          "reachableTargets", targets.stream().sorted().map(key -> Map.of("key", key)).toList());
    }
    var events = new ArrayList<Episode>();
    if (!text.isBlank())
      events.add(
          Episode.create(
              scope,
              direct ? "conversation" : "observation",
              keywords,
              text.substring(0, Math.min(1900, text.length()))));
    var attention = session.attention.target();
    if (attention != null && session.dedup.accept("memory:" + attention.key(), session.tick, 12000))
      events.add(
          Episode.create(
              scope,
              "observation",
              keywords,
              "Observed "
                  + attention.registryId()
                  + " ("
                  + attention.name()
                  + ") in "
                  + level.dimension().location()
                  + " at "
                  + attention.position()));
    return new InferenceSnapshot(
        Collections.unmodifiableMap(context), keywords, Set.copyOf(targets), List.copyOf(events));
  }

  private static boolean isMundaneTerrain(String id) {
    return id.equals("minecraft:grass_block")
        || id.equals("minecraft:dirt")
        || id.equals("minecraft:stone")
        || id.equals("minecraft:deepslate")
        || id.equals("minecraft:sand")
        || id.equals("minecraft:gravel")
        || id.equals("minecraft:short_grass")
        || id.equals("minecraft:tall_grass")
        || id.equals("minecraft:water");
  }
}
