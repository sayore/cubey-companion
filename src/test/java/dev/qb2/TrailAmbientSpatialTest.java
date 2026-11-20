package dev.qb2;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import dev.qb2.client.config.Settings;
import dev.qb2.cognition.planning.AmbientBuffer;
import dev.qb2.companion.movement.Motion;
import dev.qb2.companion.movement.PlayerTrail;
import dev.qb2.companion.movement.Point;
import dev.qb2.inference.HttpTransport;
import dev.qb2.inference.provider.ApiLanguageModel;
import dev.qb2.inference.provider.ModelHealth;
import dev.qb2.memory.nerve.NerveMemory;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class TrailAmbientSpatialTest {
  @Test
  void smoothFollowGlidesAlongWalkedTrailWithoutTeleporting() {
    var trail = new PlayerTrail();
    var motion = new Motion();
    motion.reset(new Point(0, 65.5, 0));

    // Player runs 22 blocks along +X then 10 blocks along +Z (beyond exploreRadius=7).
    Point player = new Point(0, 64, 0);
    for (int step = 1; step <= 22; step++) {
      player = new Point(step, 64, 0);
      trail.record(player);
    }
    for (int step = 1; step <= 10; step++) {
      player = new Point(22, 64, step);
      trail.record(player);
    }

    double maxSingleTickJump = 0;
    for (int i = 0; i < 240; i++) {
      Point before = motion.position();
      motion.tickSmoothFollow(player, player.add(new Point(0, 1.5, 0)), 7.0, p -> true, trail);
      double jump = before.distance(motion.position());
      maxSingleTickJump = Math.max(maxSingleTickJump, jump);
    }
    assertTrue(
        maxSingleTickJump < 0.35,
        "Expected smooth flight without teleport/drag, max single-tick jump was "
            + maxSingleTickJump);
    assertTrue(
        motion.position().distance(player) < 4.0,
        "Expected companion to catch up smoothly along player trail, distance="
            + motion.position().distance(player));
  }

  @Test
  void ambientBufferDefersSpeechUntilReturnAndBridgesOutages() {
    var buffer = new AmbientBuffer();
    buffer.deferForActiveReturn("Willkommen zurück!", "Der Ofen ist fertig.");
    assertTrue(buffer.hasDeferred());
    assertEquals("Willkommen zurück!", buffer.pollDeferredSpeech().orElse(""));
    assertEquals("Der Ofen ist fertig.", buffer.pollDeferredThought().orElse(""));
    assertFalse(buffer.hasDeferred());

    buffer.storePhrase("mekanism:metallurgic_infuser", "Infuser läuft ruhig.");
    buffer.storePhrase("mekanism:metallurgic_infuser", "Infuser läuft ruhig."); // dedup
    assertEquals(
        "Infuser läuft ruhig.", buffer.consumePhrase("mekanism:metallurgic_infuser").orElse(""));
    assertTrue(buffer.consumePhrase("mekanism:metallurgic_infuser").isEmpty());
  }

  @Test
  void modelChainDemotesUnhealthy503ModelAndSkipsItOnNextRequest() throws Exception {
    ModelHealth.resetAll();
    AtomicInteger badModelHits = new AtomicInteger();
    AtomicInteger goodModelHits = new AtomicInteger();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/v1beta/models/gemma-4-31b-it:generateContent",
        ex -> {
          badModelHits.incrementAndGet();
          byte[] body = "overloaded".getBytes(StandardCharsets.UTF_8);
          ex.sendResponseHeaders(503, body.length);
          try (OutputStream out = ex.getResponseBody()) {
            out.write(body);
          }
        });
    server.createContext(
        "/v1beta/models/gemma-4-26b-a4b-it:generateContent",
        ex -> {
          goodModelHits.incrementAndGet();
          byte[] body =
              """
              {"candidates":[{"content":{"parts":[{"text":"{\\"action\\":\\"observe\\",\\"target\\":\\"\\",\\"thought\\":\\"Chain ok\\",\\"speech\\":\\"\\"}"}]}}]}
              """
                  .getBytes(StandardCharsets.UTF_8);
          ex.sendResponseHeaders(200, body.length);
          try (OutputStream out = ex.getResponseBody()) {
            out.write(body);
          }
        });
    server.start();
    try (HttpTransport http = new HttpTransport()) {
      Settings s = new Settings();
      s.provider = "google";
      s.endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1beta";
      s.model = "gemma-4-31b-it";
      s.modelChain = "gemma-4-31b-it,gemma-4-26b-a4b-it";
      s.ambientModel = "";
      s.timeoutSeconds = 5;

      var m1 = new ApiLanguageModel(http, s, "test-key", "", false);
      assertTrue(m1.complete("first").contains("Chain ok"));
      assertEquals("gemma-4-26b-a4b-it", m1.lastUsedModel());
      int hitsAfterFirst = badModelHits.get();
      assertTrue(hitsAfterFirst >= 1);

      // Second request should skip demoted gemma-4-31b-it immediately!
      var m2 = new ApiLanguageModel(http, s, "test-key", "", false);
      assertTrue(m2.complete("second").contains("Chain ok"));
      assertEquals(hitsAfterFirst, badModelHits.get(), "Demoted 503 model should be skipped");
      assertEquals(2, goodModelHits.get());
    } finally {
      server.stop(0);
      ModelHealth.resetAll();
    }
  }

  @Test
  void nerveMemoryNearbyParsesSpatialHits() throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    String scope = "d".repeat(64);
    server.createContext(
        "/nearby",
        ex -> {
          byte[] body =
              ("""
              [{"episode":{"id":"11111111-2222-3333-4444-555555555555","scope":"%s","kind":"container_memory","keywords":["iron"],"text":"Chest at 10,64,5 contains: 32x Iron Ingot","createdAt":"2026-11-20T14:00:00Z","x":10.5,"y":64.5,"z":5.5},"distance":2.4}]
              """
                      .formatted(scope))
                  .getBytes(StandardCharsets.UTF_8);
          ex.sendResponseHeaders(200, body.length);
          try (OutputStream out = ex.getResponseBody()) {
            out.write(body);
          }
        });
    server.start();
    try (HttpTransport http = new HttpTransport()) {
      var mem =
          new NerveMemory(
              http, "http://127.0.0.1:" + server.getAddress().getPort(), "token-123456789012345678901234");
      var hits = mem.nearby(scope, new Point(12.0, 64.5, 5.5), 16.0, 4);
      assertEquals(1, hits.size());
      assertEquals(2.4, hits.get(0).distance(), 0.01);
      assertTrue(hits.get(0).episode().text().contains("Iron Ingot"));
    } finally {
      server.stop(0);
    }
  }
}
