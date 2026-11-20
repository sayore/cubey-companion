package dev.qb2.client.lifecycle;

import dev.qb2.memory.Episode;
import dev.qb2.perception.Keywords;
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
    context.put(
        "movement",
        Map.of(
            "goal",
            session.flightGoal,
            "mode",
            session.flightMode,
            "distanceFromPlayer",
            mc.player == null
                ? 0
                : session
                    .motion
                    .position()
                    .distance(dev.qb2.perception.world.WorldSensor.point(mc.player.position()))));
    context.put("request", Map.of("reason", reason, "message", text, "action", session.action));
    context.put(
        "world",
        Map.of(
            "dimension",
            level.dimension().location().toString(),
            "dayTime",
            level.getDayTime(),
            "observations",
            List.copyOf(session.observations),
            "cube",
            session.motion.position()));
    context.put(
        "attention", session.attention.target() == null ? "none" : session.attention.target());
    context.put("chat", session.chat.snapshot());
    context.put("recentThoughts", List.copyOf(session.recentThoughts));
    context.put("actions", List.copyOf(session.actions));
    context.put("history", List.copyOf(session.observationsHistory));
    var inventory = dev.qb2.perception.inventory.InventorySensor.capture(mc);
    context.put("inventory", inventory);
    context.put(
        "player",
        Map.of(
            "position",
            dev.qb2.perception.world.WorldSensor.point(player.position()),
            "condition",
            dev.qb2.perception.PlayerSituationSensor.capture(player)));
    var words = new LinkedHashSet<String>();
    words.add(level.dimension().location().toString());
    words.add(reason);
    session.observations.stream()
        .limit(16)
        .forEach(
            o -> {
              words.add(o.registryId());
              words.addAll(Keywords.extract(o.name()));
            });
    words.addAll(inventory.keywords());
    words.addAll(Keywords.extract(text));
    var keywords = List.copyOf(words);
    var targets = new HashSet<String>();
    var playerPosition = dev.qb2.perception.world.WorldSensor.point(player.position());
    session.observations.stream()
        .filter(
            o ->
                dev.qb2.companion.movement.FlightPlanner.hoverAbove(o).distance(playerPosition)
                    <= exploreRadius * 0.9)
        .forEach(o -> targets.add(o.key()));
    context.put(
        "reachableTargets", targets.stream().sorted().map(key -> Map.of("key", key)).toList());
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
}
