package dev.qb2;

import static org.junit.jupiter.api.Assertions.*;

import dev.qb2.companion.movement.Point;
import dev.qb2.network.CubeySyncValidator;
import dev.qb2.network.RemoteCompanionTracker;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class CubeyV120Test {
  @Test
  void syncValidatorPreservesFifthEquipMetadataField() {
    String meta =
        CubeySyncValidator.sanitizeHatAndTheme(
            "chef|sakura|Mochi|porcelain|minecraft:diamond_sword");
    assertEquals("chef|sakura|Mochi|porcelain|minecraft:diamond_sword", meta);

    String dirty =
        CubeySyncValidator.sanitizeHatAndTheme(
            "antenna|neo_cyan|Cubey|obsidian|minecraft:bow;DROP TABLE");
    assertTrue(dirty.endsWith("|minecraft:bowdroptable"));
  }

  @Test
  void syncValidatorWhitelistsPeerInteractActions() {
    assertEquals("pet", CubeySyncValidator.sanitizePeerAction("pet"));
    assertEquals("poke", CubeySyncValidator.sanitizePeerAction("poke"));
    assertEquals("treat", CubeySyncValidator.sanitizePeerAction("treat"));
    assertEquals("toss_back", CubeySyncValidator.sanitizePeerAction("toss_back"));
    assertEquals("", CubeySyncValidator.sanitizePeerAction("exploit_kill"));
  }

  @Test
  void remoteTrackerRayHitsPeerAndParsesEquipment() {
    RemoteCompanionTracker tracker = new RemoteCompanionTracker();
    UUID id = UUID.randomUUID();
    tracker.updatePeer(
        id,
        "Alex",
        new Point(0, 1.6, 3.0),
        0f,
        0f,
        0f,
        "Idle",
        "antenna|emerald|Pixel|brass|minecraft:shield",
        "",
        10L);

    var hit = tracker.findRayHit(new Point(0, 1.6, 0), new Point(0, 0, 1), 6.0, 0.38);
    assertTrue(hit.isPresent());
    assertEquals("Pixel", hit.get().cubeyName);
    assertEquals("brass", hit.get().skin);
    assertEquals("minecraft:shield", hit.get().equipItemId);

    var miss = tracker.findRayHit(new Point(0, 1.6, 0), new Point(1, 0, 0), 6.0, 0.38);
    assertTrue(miss.isEmpty());

    tracker.triggerPeerReaction(id, "♥", 12L);
    assertEquals(24, hit.get().interactWobbleTicks);
    assertEquals("♥", hit.get().speech);
  }

  @Test
  void toolRegistrySupportsInspectMachineGraph() {
    var snap =
        new dev.qb2.cognition.tools.ToolSnapshot(
            java.util.Map.of(),
            java.util.Map.of(),
            "",
            "",
            "",
            null,
            java.util.List.of(),
            "Connected assembly (6 blocks around depot): 3x create:encased_fan, 3x create:depot | Flows: encased_fan --(water (Bulk Washing))--> depot");
    String out =
        dev.qb2.cognition.tools.ToolRegistry.executeOne(
            new dev.qb2.cognition.tools.ToolRegistry.Call("inspect_machine_graph", ""),
            snap,
            null,
            "");
    assertTrue(out.contains("Bulk Washing"));
    assertTrue(out.contains("create:depot"));
  }

  @Test
  void settingsLanguageAndChatColorWork() {
    var s = new dev.qb2.client.config.Settings();
    s.language = "de";
    s.cubeyTheme = "sakura";
    s.validate();
    assertEquals("Deutsch", s.languageLabel());
    assertEquals(0xFF88C8, s.chatColorRgb());
  }

  @Test
  void chatHistorySlowDispenseCompactsOverflowIntoLogicChains() {
    var hist = new dev.qb2.perception.chat.ChatHistory();
    hist.add(
        dev.qb2.perception.chat.ChatHistory.Source.PLAYER,
        "@cube Was bedeutet Smite auf einem Schwert?",
        1L);
    hist.add(
        dev.qb2.perception.chat.ChatHistory.Source.QB2,
        "Smite bedeutet mehr Schaden gegen Untote damit Zombies schneller fallen.",
        2L);
    for (int i = 0; i < 20; i++) {
      hist.add(dev.qb2.perception.chat.ChatHistory.Source.PLAYER, "Test message " + i, 10L + i);
    }
    assertEquals(20, hist.snapshot().size());
    var chains = hist.compactedMemory();
    assertFalse(chains.isEmpty());
    assertTrue(chains.getFirst().contains("Smite"));
    assertTrue(chains.getFirst().contains("→"));
  }

  @Test
  void machineGraphMemoryHashesDeduplicatesAndInvalidatesOnChange() {
    var mem = new dev.qb2.memory.MachineGraphMemory();
    String scope = "a".repeat(64);
    var pos = new Point(82.5, 62.5, -260.5);

    var r1 =
        mem.observe(
            scope,
            "minecraft:overworld",
            pos,
            "create:depot",
            java.util.Map.of("create:encased_fan", 3, "create:depot", 3),
            java.util.List.of("encased_fan --(water (Bulk Washing))--> depot"),
            java.util.List.of("depot holds minecraft:gravel"),
            "Create washing station");
    assertEquals(dev.qb2.memory.MachineGraphMemory.Status.NEW, r1.status());
    assertNotNull(r1.episode());

    // Exact same machine -> UNCHANGED, zero new episode writes!
    var r2 =
        mem.observe(
            scope,
            "minecraft:overworld",
            pos.add(new Point(0.4, 0, 0)),
            "create:depot",
            java.util.Map.of("create:encased_fan", 3, "create:depot", 3),
            java.util.List.of("encased_fan --(water (Bulk Washing))--> depot"),
            java.util.List.of("depot holds minecraft:gravel"),
            "Create washing station");
    assertEquals(dev.qb2.memory.MachineGraphMemory.Status.UNCHANGED, r2.status());
    assertNull(r2.episode());

    // Player adds a Brass Funnel & switches water to lava -> hash invalidated & diff computed!
    var r3 =
        mem.observe(
            scope,
            "minecraft:overworld",
            pos,
            "create:depot",
            java.util.Map.of("create:encased_fan", 3, "create:depot", 3, "create:brass_funnel", 1),
            java.util.List.of("encased_fan --(lava (Bulk Smelting))--> depot"),
            java.util.List.of("depot holds minecraft:raw_iron"),
            "Create smelting station");
    assertEquals(dev.qb2.memory.MachineGraphMemory.Status.UPDATED, r3.status());
    assertNotNull(r3.episode());
    assertTrue(r3.diff().contains("+1 brass_funnel"));
  }
}
