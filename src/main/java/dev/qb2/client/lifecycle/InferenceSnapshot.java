package dev.qb2.client.lifecycle;

import dev.qb2.cognition.tools.ToolSnapshot;
import dev.qb2.memory.Episode;
import dev.qb2.perception.Keywords;
import dev.qb2.perception.Observation;
import java.util.*;
import java.util.stream.Collectors;
import net.minecraft.client.Minecraft;

/**
 * Captured exclusively on the client thread; no mutable world objects cross the worker boundary.
 */
public record InferenceSnapshot(
    Map<String, Object> context,
    List<String> keywords,
    Set<String> targets,
    List<Episode> events,
    ToolSnapshot tools) {
  public InferenceSnapshot(
      Map<String, Object> context,
      List<String> keywords,
      Set<String> targets,
      List<Episode> events) {
    this(context, keywords, targets, events, ToolSnapshot.EMPTY);
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

    // Attention-focused observations with short prefix-free aliases (b1, b2, ...).
    var attentionTarget = session.attention.target();
    var playerPosition = dev.qb2.perception.world.WorldSensor.point(player.position());
    var selectedObs = new ArrayList<Observation>();
    if (attentionTarget != null) selectedObs.add(attentionTarget);
    session.observations.stream()
        .filter(o -> attentionTarget == null || !o.key().equals(attentionTarget.key()))
        .sorted(
            Comparator.comparingInt((Observation o) -> isMundaneTerrain(o.registryId()) ? 1 : 0)
                .thenComparingDouble(o -> -o.salience()))
        .limit(direct ? 8 : 5)
        .forEach(selectedObs::add);

    var aliasToFullKey = new LinkedHashMap<String, String>();
    var detailsByAlias = new LinkedHashMap<String, String>();
    var visibleLines = new ArrayList<String>();
    var targets = new HashSet<String>();
    var reachableAliases = new ArrayList<String>();
    int idx = 1;
    for (Observation o : selectedObs) {
      String alias = "b" + idx++;
      aliasToFullKey.put(alias, o.key());
      int att = attentionScore(o, attentionTarget);
      int distM = Math.max(1, (int) Math.round(o.position().distance(playerPosition)));
      visibleLines.add(alias + ": " + o.name() + " (att:" + att + ", " + distM + "m)");
      detailsByAlias.put(
          alias,
          o.name()
              + " ["
              + o.registryId()
              + "] at "
              + (int) Math.floor(o.position().x())
              + ","
              + (int) Math.floor(o.position().y())
              + ","
              + (int) Math.floor(o.position().z())
              + " | state: "
              + o.state());
      if (dev.qb2.companion.movement.FlightPlanner.hoverAbove(o).distance(playerPosition)
          <= exploreRadius * 0.9) {
        targets.add(o.key());
        reachableAliases.add(alias);
      }
    }
    // Also allow full keys from all visible observations for compatibility with smoke fixtures.
    for (Observation o : session.observations) {
      if (dev.qb2.companion.movement.FlightPlanner.hoverAbove(o).distance(playerPosition)
          <= exploreRadius * 0.9) {
        targets.add(o.key());
      }
    }

    context.put(
        "world",
        Map.of(
            "dim",
            level.dimension().location().getPath(),
            "visible",
            visibleLines));
    if (attentionTarget != null) {
      context.put("focus", "b1: " + attentionTarget.name());
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
    String compactInventory = formatCompactInventory(inventory);
    boolean needInventory =
        !inventory.container().isEmpty()
            || "inventory changed".equals(reason)
            || text.toLowerCase(Locale.ROOT)
                .matches(
                    "(?s).*(inventar|inventory|chest|kiste|truhe|rucksack|backpack|menu|item|items|craft|rezept|recipe|hast du|hab ich|habe ich).*");
    if (needInventory && !compactInventory.isBlank()) {
      context.put("inventory", compactInventory);
    }
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
    if (!reachableAliases.isEmpty()) {
      context.put("reachableTargets", reachableAliases);
    }

    // Build compact non-terrain area scan for the scan_area tool.
    var areaCounts = new LinkedHashMap<String, Integer>();
    for (Observation o : session.observations) {
      if (isMundaneTerrain(o.registryId())) continue;
      areaCounts.merge(o.name(), 1, Integer::sum);
    }
    String areaScan =
        areaCounts.entrySet().stream()
            .limit(18)
            .map(e -> e.getValue() > 1 ? e.getValue() + "x " + e.getKey() : e.getKey())
            .collect(Collectors.joining(", "));
    var toolSnapshot =
        new ToolSnapshot(
            Map.copyOf(aliasToFullKey),
            Map.copyOf(detailsByAlias),
            areaScan,
            compactInventory,
            knowledgeScope,
            playerPosition);
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
        Collections.unmodifiableMap(context),
        keywords,
        Set.copyOf(targets),
        List.copyOf(events),
        toolSnapshot);
  }

  private static int attentionScore(Observation o, Observation focus) {
    if (focus != null && o.key().equals(focus.key())) return 10;
    if (o.state() != null && (o.state().contains("clientBlockEntity=") || o.state().contains("entity")))
      return 8;
    if (!isMundaneTerrain(o.registryId())) return Math.clamp((int) Math.round(5 + o.salience()), 5, 9);
    return 2;
  }

  private static String formatCompactInventory(
      dev.qb2.perception.inventory.InventorySensor.View view) {
    var sb = new StringBuilder();
    if (!view.player().isEmpty()) {
      var counts = new LinkedHashMap<String, Integer>();
      for (var item : view.player()) counts.merge(item.name(), item.count(), Integer::sum);
      sb.append("Player: ")
          .append(
              counts.entrySet().stream()
                  .limit(20)
                  .map(e -> e.getValue() + "x " + e.getKey())
                  .collect(Collectors.joining(", ")));
    }
    if (!view.container().isEmpty()) {
      var counts = new LinkedHashMap<String, Integer>();
      for (var item : view.container()) counts.merge(item.name(), item.count(), Integer::sum);
      if (!sb.isEmpty()) sb.append(" | ");
      sb.append(view.title().isBlank() ? "Container" : view.title())
          .append(": ")
          .append(
              counts.entrySet().stream()
                  .limit(20)
                  .map(e -> e.getValue() + "x " + e.getKey())
                  .collect(Collectors.joining(", ")));
    }
    return sb.toString();
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
