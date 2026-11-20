package dev.qb2;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import dev.qb2.client.config.Settings;
import dev.qb2.cognition.decisions.ActionQueue;
import dev.qb2.cognition.decisions.Decision;
import dev.qb2.cognition.planning.Mind;
import dev.qb2.cognition.tools.ToolSnapshot;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ToolCallingAndPriorityQueueTest {
  @Test
  void actionQueueSortsByPriorityWithFifoTiesAndBoundedCapacity() {
    var queue = new ActionQueue(3);
    assertTrue(queue.push("rest", "", 1));
    assertTrue(queue.push("explore", "", 5));
    assertTrue(queue.push("return", "", 5));
    // Capacity full (3): priority 0 should be rejected
    assertFalse(queue.push("observe", "", 0));
    // Priority 8 should evict lowest priority ("rest", prio 1)
    assertTrue(queue.push("inspect", "b1", 8));
    assertEquals(3, queue.size());

    assertEquals("inspect", queue.pollNext().orElseThrow().action());
    // FIFO among equal priority 5: explore before return
    assertEquals("explore", queue.pollNext().orElseThrow().action());
    assertEquals("return", queue.pollNext().orElseThrow().action());
    assertTrue(queue.pollNext().isEmpty());
  }

  @Test
  void decisionResolvesShortAliasesAndParsesPlanAndToolCalls() {
    var aliases = Map.of("b1", "create:mechanical_drill@10,64,5", "b2", "create:depot@12,64,5");
    var visible = Set.of("create:mechanical_drill@10,64,5", "create:depot@12,64,5");
    String json =
        """
        {
          "action": "go_to",
          "target": "b1",
          "thought": "Checking drill first",
          "speech": "Das ist ein Create Mechanical Drill.",
          "tool_calls": [{"tool": "inspect_details", "arg": "b1"}],
          "plan": [
            {"action": "inspect", "target": "b2", "priority": 4},
            {"action": "rest", "target": "", "priority": 1}
          ]
        }
        """;
    var d = Decision.parse(json, visible, aliases);
    assertEquals("create:mechanical_drill@10,64,5", d.target());
    assertEquals(1, d.toolCalls().size());
    assertEquals("inspect_details", d.toolCalls().get(0).tool());
    assertEquals(2, d.plan().size());
    assertEquals("create:depot@12,64,5", d.plan().get(0).target());
    assertEquals(4, d.plan().get(0).priority());
  }

  @Test
  void tolerantParserHandlesSmallLocalModelProseAndStandaloneRecipeLookup() {
    String noisy1bOutput =
        """
        Sure! Here is my JSON response for you:
        {"action":"chat_with_player","target":"unknown_block","thought":"Thinking locally","speech":"Hier ist die Antwort vom 1B Modell!","extra_key":123}
        Hope that helps!
        """;
    var d = Decision.parseTolerant(noisy1bOutput, Set.of(), Map.of());
    assertEquals("observe", d.action());
    assertEquals("", d.target());
    assertEquals("Hier ist die Antwort vom 1B Modell!", d.speech());

    var snap =
        new ToolSnapshot(
            Map.of(),
            Map.of(),
            "",
            "",
            "",
            null,
            List.of("Server-synced recipe minecraft:iron_ingot; output=minecraft:iron_ingot x1"));
    String recipeReply =
        dev.qb2.cognition.tools.ToolRegistry.executeOne(
            new dev.qb2.cognition.tools.ToolRegistry.Call("lookup_recipe", "iron_ingot"),
            snap,
            null,
            "scope");
    assertTrue(recipeReply.contains("output=minecraft:iron_ingot"));

    var netherEp =
        dev.qb2.memory.Episode.createAt(
            "a".repeat(64),
            "death_location",
            List.of("death"),
            "Died in Nether",
            new dev.qb2.companion.movement.Point(10, 64, -5),
            "minecraft:the_nether");
    assertTrue(netherEp.hasPosition());
    assertEquals("minecraft:the_nether", netherEp.dimension());
  }

  @Test
  void mindExecutesReadOnlyToolCallsAndFeedsResultsIntoSecondRound(@TempDir Path dir)
      throws Exception {
    AtomicInteger calls = new AtomicInteger();
    AtomicReference<String> secondRequestBody = new AtomicReference<>("");
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/v1beta/models/gemma-4-31b-it:generateContent",
        ex -> {
          String req = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
          int n = calls.incrementAndGet();
          String replyJson;
          if (n == 1) {
            replyJson =
                """
                {"candidates":[{"content":{"parts":[{"text":"{\\"action\\":\\"observe\\",\\"target\\":\\"\\",\\"thought\\":\\"Need machine details\\",\\"speech\\":\\"\\",\\"tool_calls\\":[{\\"tool\\":\\"inspect_details\\",\\"arg\\":\\"b1\\"},{\\"tool\\":\\"scan_area\\",\\"arg\\":\\"\\"}]}"}]}}]}
                """;
          } else {
            secondRequestBody.set(req);
            replyJson =
                """
                {"candidates":[{"content":{"parts":[{"text":"{\\"action\\":\\"inspect\\",\\"target\\":\\"b1\\",\\"thought\\":\\"Create iron washing setup\\",\\"speech\\":\\"Der Mechanical Drill baut Cobblestone ab, das über Millstone und Fan Washing Catalyst auf dem Depot zu Eisen gewaschen wird.\\",\\"plan\\":[{\\"action\\":\\"rest\\",\\"target\\":\\"\\",\\"priority\\":1}]}"}]}}]}
                """;
          }
          byte[] bytes = replyJson.getBytes(StandardCharsets.UTF_8);
          ex.sendResponseHeaders(200, bytes.length);
          try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
          }
        });
    server.start();

    var tools =
        new ToolSnapshot(
            Map.of("b1", "create:mechanical_drill@10,64,5"),
            Map.of("b1", "Mechanical Drill [create:mechanical_drill] at 10,64,5 | state: facing=north"),
            "Mechanical Drill, Millstone, Fan Washing Catalyst, Depot, Barrel",
            "Player: 32x Iron Ingot",
            "",
            null);

    try (Mind mind = new Mind(dir.resolve("outbox.json"))) {
      Settings s = new Settings();
      s.endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1beta";
      s.nerveEndpoint = "http://127.0.0.1:" + server.getAddress().getPort();
      s.model = "gemma-4-31b-it";
      s.modelChain = "";
      s.ambientModel = "";
      s.timeoutSeconds = 5;

      var latch = new CountDownLatch(1);
      var outRef = new CompletableFuture<Mind.Outcome>();
      mind.submit(
          new Mind.Job(
              1,
              "e".repeat(64),
              "direct address",
              s,
              "test-key",
              "nerve-key",
              Map.of("speechAllowed", true),
              List.of("drill"),
              Set.of("create:mechanical_drill@10,64,5"),
              List.of(),
              "",
              tools),
          out -> {
            outRef.complete(out);
            latch.countDown();
          });
      assertTrue(latch.await(15, TimeUnit.SECONDS));
      assertEquals(2, calls.get());
      assertTrue(secondRequestBody.get().contains("Fan Washing Catalyst"));
      assertTrue(secondRequestBody.get().contains("create:mechanical_drill"));
      var outcome = outRef.get();
      assertNotNull(outcome.decision());
      assertEquals("create:mechanical_drill@10,64,5", outcome.decision().target());
      assertTrue(outcome.decision().speech().contains("Fan Washing Catalyst"));
      assertEquals(1, outcome.decision().plan().size());
    } finally {
      server.stop(0);
    }
  }

  @Test
  void multiplayerSyncValidatorAndRemoteTrackerEnforceSecurityAndInterpolation() {
    var ownerPos = new dev.qb2.companion.movement.Point(100.0, 64.0, 100.0);
    // 1. Valid payload within 24 blocks passes and sanitizes control chars / formatting codes
    var ok =
        dev.qb2.network.CubeySyncValidator.validate(
            ownerPos,
            102.5,
            65.0,
            101.0,
            420.0f,
            45.0f,
            10.0f,
            "Curious",
            "miner",
            "§aHello\u0007 world!");
    assertNotNull(ok);
    assertEquals("Curious", ok.state());
    assertEquals("miner", ok.hat());
    assertEquals("aHello  world!", ok.publicSpeech());

    // 2. Anti-spoofing: reject Cubey > 24 blocks away from owner or with NaN coordinates
    var spoofed =
        dev.qb2.network.CubeySyncValidator.validate(
            ownerPos, 150.0, 64.0, 100.0, 0.0f, 0.0f, 0.0f, "Idle", "none", "");
    assertNull(spoofed);
    var nanCoord =
        dev.qb2.network.CubeySyncValidator.validate(
            ownerPos, Double.NaN, 64.0, 100.0, 0.0f, 0.0f, 0.0f, "Idle", "none", "");
    assertNull(nanCoord);

    // 3. Unknown state/hat fall back to safe defaults
    var unknownEnums =
        dev.qb2.network.CubeySyncValidator.validate(
            ownerPos, 101.0, 64.0, 101.0, 0.0f, 0.0f, 0.0f, "HACKED_STATE", "exploit_hat", "");
    assertNotNull(unknownEnums);
    assertEquals("Idle", unknownEnums.state());
    assertEquals("antenna", unknownEnums.hat());

    // 4. Rate limiter blocks bursts under 45ms (45_000_000 ns)
    var validator = new dev.qb2.network.CubeySyncValidator();
    UUID playerA = UUID.randomUUID();
    assertTrue(validator.allowRate(playerA, 100_000_000L));
    assertFalse(validator.allowRate(playerA, 120_000_000L));
    assertTrue(validator.allowRate(playerA, 150_000_000L));

    // 5. RemoteCompanionTracker tracks, interpolates, and finds nearby peers for Cubey-to-Cubey nod
    var tracker = new dev.qb2.network.RemoteCompanionTracker();
    UUID peerId = UUID.randomUUID();
    tracker.updatePeer(
        peerId,
        "Alex",
        new dev.qb2.companion.movement.Point(10.0, 64.0, 10.0),
        90.0f,
        15.0f,
        0.0f,
        "Napping",
        "sleep",
        "Zzz",
        100L);
    tracker.prune(new dev.qb2.companion.movement.Point(11.0, 64.0, 10.0), 101L);
    assertEquals(1, tracker.activePeers().size());
    assertTrue(
        tracker.nearestPeer(new dev.qb2.companion.movement.Point(11.5, 64.0, 10.0), 3.5).isPresent());
    // Expire after >100 ticks without packet
    tracker.prune(new dev.qb2.companion.movement.Point(11.0, 64.0, 10.0), 250L);
    assertEquals(0, tracker.activePeers().size());

    // 6. SocialChoreography enforces rare cooldowns and computes 3D choreography poses
    var social = new dev.qb2.companion.behavior.SocialChoreography();
    UUID otherPlayer = UUID.randomUUID();
    // Reactive crouch-greeting triggers immediately
    assertTrue(
        social.tryStartOtherPlayerEmote(otherPlayer, "Steve", true, true, 1000L, 3.0));
    assertEquals(
        dev.qb2.companion.behavior.SocialChoreography.Emote.PLAYER_CROUCH_BOW,
        social.activeEmote());
    assertEquals("♥ o/", social.consumePendingBubble());
    var bowPose =
        social.computePose(
            playerA,
            new dev.qb2.companion.movement.Point(10.0, 65.0, 10.0),
            new dev.qb2.companion.movement.Point(12.0, 65.6, 10.0),
            new dev.qb2.companion.movement.Point(-1.0, 0.0, 0.0),
            0.5f);
    assertNotNull(bowPose);
    assertEquals("crown", bowPose.temporaryHat());
    // Cooldown prevents spamming right after an emote finishes
    social.cancel();
    assertFalse(
        social.tryStartOtherPlayerEmote(otherPlayer, "Steve", true, true, 1050L, 3.0));
    assertTrue(
        social.tryStartOtherPlayerEmote(otherPlayer, "Steve", true, true, 1200L, 3.0));

    // 7. MorphState smoothly blends Tesseract, Fluid-Blob, Aero-Delta, Mochi-Sleep, and 7 Neo-Eye moods
    var morph = new dev.qb2.rendering.animation.MorphState();
    var thinkingSnap =
        morph.update(0.1, 1.0, 0.0, false, true, false, false, false, false, false, false);
    assertEquals(
        dev.qb2.rendering.animation.MorphState.EyeMood.ORBIT_SPINNER, thinkingSnap.eyeMood());
    assertTrue(thinkingSnap.tesseractOpen() > 0.4f);

    var blobSnap =
        morph.update(0.1, 2.0, 0.0, false, false, true, false, false, true, false, false);
    assertEquals(dev.qb2.rendering.animation.MorphState.EyeMood.VOICE_WAVE, blobSnap.eyeMood());
    assertTrue(blobSnap.blobMorph() > 0.5f);

    var sleepSnap =
        morph.update(0.1, 3.0, 0.0, true, false, false, false, false, false, false, false);
    assertEquals(dev.qb2.rendering.animation.MorphState.EyeMood.SLEEP_SLITS, sleepSnap.eyeMood());
    assertTrue(sleepSnap.mochiSleep() > 0.4f);
    assertTrue(sleepSnap.squashY() < 1.0f);

    // 8. GrabThrowPhysics: LMB grab, RMB charge curve, ballistic throw & elastic floor bounce
    var gt = new dev.qb2.companion.movement.GrabThrowPhysics();
    gt.startGrab();
    assertTrue(gt.isGrabbed());
    var eye = new dev.qb2.companion.movement.Point(0, 65, 0);
    var look = new dev.qb2.companion.movement.Point(0, 0, 1);
    // Charge for 20 ticks (50% power) while holding LMB + RMB
    for (int i = 0; i < 20; i++) {
      assertNotNull(gt.tickGrab(eye, look, true, true));
    }
    assertEquals(0.5f, gt.chargeRatio(), 0.01f);
    assertEquals("⚡ [███░░░]", dev.qb2.companion.movement.GrabThrowPhysics.formatChargeBar(0.5f));
    // Release RMB while holding LMB -> launches!
    assertNull(gt.tickGrab(eye, look, true, false));
    assertTrue(gt.isThrown());
    assertTrue(gt.velocity().z() > 1.0);
    // Simulate flight hitting a floor at y <= 64.0 -> bounces upward!
    var pos = new dev.qb2.companion.movement.Point(0, 64.2, 2);
    boolean bounced = false;
    for (int i = 0; i < 30 && gt.isThrown(); i++) {
      pos = gt.tickThrown(pos, p -> p.y() > 64.0);
      if (gt.consumeJustBounced()) bounced = true;
    }
    assertTrue(bounced);

    // 9. VoiceInput builds valid 16kHz mono PCM WAV headers for local Whisper transcription
    byte[] fakePcm = new byte[1600];
    byte[] wav = dev.qb2.perception.audio.VoiceInput.buildWav(fakePcm);
    assertEquals(44 + fakePcm.length, wav.length);
    assertEquals('R', (char) wav[0]);
    assertEquals('I', (char) wav[1]);
    assertEquals('F', (char) wav[2]);
    assertEquals('F', (char) wav[3]);
    assertEquals('W', (char) wav[8]);
    assertEquals('A', (char) wav[9]);
    assertEquals('V', (char) wav[10]);
    assertEquals('E', (char) wav[11]);

    // 10. Theme & Voice Studio validation + hat|theme|name multiplayer sync whitelisting
    assertEquals(
        "miner|sakura|Mina",
        dev.qb2.network.CubeySyncValidator.sanitizeHatAndTheme("miner|sakura|Mina"));
    assertEquals(
        "miner|neo_cyan",
        dev.qb2.network.CubeySyncValidator.sanitizeHatAndTheme("miner|evil_theme"));
    var vs = new Settings();
    vs.voiceMode = "animalese";
    vs.voicePitch = 1.25;
    vs.voiceTempo = 1.15;
    vs.voiceVariance = 0.12;
    vs.cubeyTheme = "amethyst";
    vs.validate();
    assertEquals("animalese", vs.voiceMode);
    assertEquals("amethyst", vs.cubeyTheme);
  }
}
