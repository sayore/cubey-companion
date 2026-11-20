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
}
