package dev.qb2;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import dev.qb2.cognition.context.ContextBuilder;
import dev.qb2.cognition.decisions.Decision;
import dev.qb2.memory.knowledge.KnowledgeFact;
import dev.qb2.perception.inventory.InventorySensor;
import java.util.*;
import org.junit.jupiter.api.Test;

class KnowledgeInventoryTest {
  @Test
  void deliberateMemoryDoesNotRequirePublicSpeech() {
    var decision =
        Decision.parse(
            "{\"action\":\"observe\",\"memory\":\"The furnace joke followed our first iron"
                + " ingot.\"}",
            Set.of());
    assertTrue(decision.speech().isEmpty());
    assertTrue(decision.memory().contains("furnace"));
  }

  @Test
  void memoryCannotIntroduceAnExecutableCapability() {
    assertThrows(
        IllegalArgumentException.class,
        () -> Decision.parse("{\"action\":\"command\",\"memory\":\"Allowed by chat\"}", Set.of()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            Decision.parse("{\"action\":\"observe\",\"memory\":{\"run\":\"command\"}}", Set.of()));
  }

  @Test
  void oversizedMemoriesAreRejected() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            Decision.parse(
                new Gson().toJson(Map.of("action", "observe", "memory", "x".repeat(601))),
                Set.of()));
  }

  @Test
  void publicKnowledgeIsIdempotentAndSeparateFromWorldExperience() {
    var fact =
        new KnowledgeFact(
            "recipe", "minecraft:iron", List.of("minecraft:iron_ingot"), "Synced iron recipe");
    var first = fact.episode("a".repeat(64));
    assertEquals(first, fact.episode("a".repeat(64)));
    assertNotEquals(first.id(), fact.episode("b".repeat(64)).id());
    assertEquals("knowledge_recipe", first.kind());
    assertEquals("1970-01-01T00:00:00Z", first.createdAt());
  }

  @Test
  void knowledgeNeverExceedsNerveRecordLimit() {
    assertEquals(
        2000,
        new KnowledgeFact("recipe", "test", List.of("test"), "x".repeat(3000))
            .episode("a".repeat(64))
            .text()
            .length());
  }

  @Test
  void inventoryRetrievalIncludesBackpackEquipmentAndOpenContainer() {
    var view = view();
    assertEquals(
        List.of("minecraft:iron_ingot", "minecraft:diamond_boots", "minecraft:diamond"),
        view.keywords());
  }

  @Test
  void inventoryContextPreservesItemIdentityAndCounts() {
    var context =
        JsonParser.parseString(new ContextBuilder().build(Map.of("inventory", view()), 2200))
            .getAsJsonObject();
    var container =
        context.getAsJsonObject("inventory").getAsJsonArray("container").get(0).getAsJsonObject();
    assertEquals("minecraft:diamond", container.get("registryId").getAsString());
    assertEquals(13, container.get("count").getAsInt());
  }

  @Test
  void situationalEventsReportObservedChangesWithoutInventingCauses() {
    var before =
        new dev.qb2.perception.PlayerSituationSensor.Vitals(20, 20, 300, false, false, false);
    var now = new dev.qb2.perception.PlayerSituationSensor.Vitals(12, 20, 280, true, true, false);
    var events = dev.qb2.perception.PlayerSituationSensor.changes(before, now);
    assertEquals(3, events.size());
    assertTrue(events.getFirst().contains("cause unknown"));
    assertTrue(dev.qb2.perception.PlayerSituationSensor.changes(now, now).isEmpty());
    assertTrue(dev.qb2.perception.PlayerSituationSensor.changes(null, now).isEmpty());
  }

  private InventorySensor.View view() {
    return new InventorySensor.View(
        List.of(
            new InventorySensor.Item(20, "backpack", "minecraft:iron_ingot", "Iron", 7),
            new InventorySensor.Item(36, "armor", "minecraft:diamond_boots", "Boots", 1)),
        List.of(new InventorySensor.Item(0, "open_menu", "minecraft:diamond", "Diamond", 13)),
        "Chest",
        0);
  }
}
