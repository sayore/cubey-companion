package dev.qb2;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import dev.qb2.companion.attention.Attention;
import dev.qb2.companion.movement.FlightPlanner;
import dev.qb2.companion.movement.Motion;
import dev.qb2.companion.movement.Point;
import dev.qb2.inference.HttpTransport;
import dev.qb2.memory.SessionSummarizer;
import dev.qb2.memory.nerve.NerveMemory;
import dev.qb2.perception.Observation;
import dev.qb2.perception.inventory.ContainerMemory;
import dev.qb2.perception.inventory.InventorySensor;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class ContainerSummaryAndLifecycleTest {
  @Test
  void containerMemoryRecordsDeduplicatesAndDetectsChanges() {
    var mem = new ContainerMemory();
    String scope = "a".repeat(64);
    Point chestPos = new Point(12.5, 64.5, -8.5);
    var view1 =
        new InventorySensor.View(
            List.of(),
            List.of(
                new InventorySensor.Item(0, "container", "minecraft:iron_ingot", "Iron Ingot", 32),
                new InventorySensor.Item(1, "container", "minecraft:redstone", "Redstone Dust", 16)),
            "Chest",
            0);

    var ep1 = mem.record(scope, chestPos, view1);
    assertTrue(ep1.isPresent());
    assertEquals("container_memory", ep1.get().kind());
    assertTrue(ep1.get().text().contains("12,64,-9"));
    assertTrue(ep1.get().text().contains("Iron Ingot"));
    assertTrue(ep1.get().keywords().contains("iron"));

    // Same contents at same position -> deduplicated
    assertTrue(mem.record(scope, chestPos, view1).isEmpty());

    // Changed contents -> new episode
    var view2 =
        new InventorySensor.View(
            List.of(),
            List.of(
                new InventorySensor.Item(0, "container", "minecraft:diamond", "Diamond", 4)),
            "Chest",
            0);
    var ep2 = mem.record(scope, chestPos, view2);
    assertTrue(ep2.isPresent());
    assertTrue(ep2.get().text().contains("Diamond"));
  }

  @Test
  void sessionSummarizerCompactsAfterThreshold() {
    var summarizer = new SessionSummarizer(4);
    String scope = "b".repeat(64);
    assertTrue(summarizer.record(scope, "world_change", "Furnace lit at 10,64,5").isEmpty());
    assertTrue(summarizer.record(scope, "thought", "Observing smelting iron ore").isEmpty());
    assertTrue(summarizer.record(scope, "speech", "Your iron furnace is active!").isEmpty());
    var summary = summarizer.record(scope, "world_change", "Chest opened near furnace");
    assertTrue(summary.isPresent());
    assertEquals("session_summary", summary.get().kind());
    assertTrue(summary.get().text().contains("4 events"));
    assertTrue(summary.get().keywords().contains("furnace"));
    assertEquals(0, summarizer.bufferedCount());
  }

  @Test
  void attentionFocusSummonPlanAndTeleportReset() {
    var attention = new Attention();
    var obs =
        new Observation(
            "minecraft:chest@10,64,10",
            "minecraft:chest",
            "Chest",
            "chest",
            new Point(10.5, 64.5, 10.5),
            5.0,
            1);
    attention.select(obs, 1);
    attention.holdUntil(200);
    assertEquals(obs, attention.target());

    var planner = new FlightPlanner();
    Point player = new Point(0, 64, 0);
    Point home = new Point(1.5, 65.5, 0);
    var returnGoal = planner.plan(player, home, new Point(5, 65, 0), obs, "return", 7.0, 10, false);
    assertEquals(home, returnGoal.position());

    // Simulate large teleport (> 32 blocks): Motion immediately snaps to new player center.
    Motion m = new Motion();
    m.reset(new Point(0, 64, 0));
    Point farCenter = new Point(500, 70, -300);
    m.tick(farCenter, farCenter, 7.0, p -> true);
    assertTrue(m.position().distance(farCenter) < 1.0);
  }

  @Test
  void nerveMemoryDeleteSendsAuthenticatedScopeAndId() throws Exception {
    AtomicReference<String> receivedBody = new AtomicReference<>("");
    AtomicReference<String> receivedAuth = new AtomicReference<>("");
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/delete",
        ex -> {
          receivedAuth.set(ex.getRequestHeaders().getFirst("Authorization"));
          receivedBody.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          byte[] ok = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
          ex.sendResponseHeaders(200, ok.length);
          try (OutputStream out = ex.getResponseBody()) {
            out.write(ok);
          }
        });
    server.start();
    try (HttpTransport http = new HttpTransport()) {
      var mem =
          new NerveMemory(
              http, "http://127.0.0.1:" + server.getAddress().getPort(), "secret-token-123456789012");
      String scope = "c".repeat(64);
      String id = "11111111-2222-3333-4444-555555555555";
      mem.delete(scope, id);
      assertEquals("Bearer secret-token-123456789012", receivedAuth.get());
      assertTrue(receivedBody.get().contains(scope));
      assertTrue(receivedBody.get().contains(id));
    } finally {
      server.stop(0);
    }
  }
}
